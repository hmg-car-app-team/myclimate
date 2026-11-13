package com.hmgcarapp.myclimate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.drive.DriveState
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.sim.SimulationHub
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.voice.PleosSpeaker
import com.hmgcarapp.core.voice.Speaker
import com.hmgcarapp.myclimate.data.EnvFeed
import com.hmgcarapp.myclimate.data.EnvironmentRepositories
import com.hmgcarapp.myclimate.data.HttpGet
import com.hmgcarapp.myclimate.data.NetPrefs
import com.hmgcarapp.myclimate.data.NetPrefsData
import com.hmgcarapp.myclimate.data.NetPrefsWriter
import com.hmgcarapp.myclimate.data.NetWrite
import com.hmgcarapp.myclimate.data.Regions
import com.hmgcarapp.myclimate.data.UrlConnectionHttp
import com.hmgcarapp.myclimate.vehicle.SeatOccupancySource
import com.hmgcarapp.myclimate.domain.SeatOfferPolicy
import com.hmgcarapp.myclimate.domain.*
import com.hmgcarapp.myclimate.vehicle.ClimateHardware
import com.hmgcarapp.myclimate.vehicle.OutsideTemp
import com.hmgcarapp.myclimate.vehicle.PleosClimateHardware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/**
 * 화면 상태의 단일 소유자이자 효과 실행 배선(얇게). 판단은 [ClimateFlow]/[PresetEngine]/[RecircPolicy]/[HabitLearner] 순수 로직이 한다.
 * 모든 상태 변경은 메인 스레드(Dispatchers.Main.immediate). SDK 스레드 콜백은 scope.launch 로 메인에 넘긴다.
 */
class AppController(
    private val context: Context,
    val signals: CarSignals,
    private val hw: ClimateHardware = PleosClimateHardware(context),
    private val speaker: Speaker = PleosSpeaker(context),
    /** 달력 시각(규칙 시간대·날짜·만료). */
    private val clock: () -> Long = System::currentTimeMillis,
    private val monoClock: () -> Long = SystemClock::elapsedRealtime,
    /** 날씨·대기질 HTTP 클라이언트(테스트에서 교체). */
    http: HttpGet = UrlConnectionHttp,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> HmgLog.w("App", "코루틴 예외(무시): ${t.javaClass.simpleName}") })
    private val stateStore = StateStore(LocalJsonStore.forApp(context))
    val debuggable: Boolean = SimulationHub.isDebuggable(context)
    private val netPrefs = NetPrefs(LocalJsonStore.forApp(context))
    private val feed = EnvFeed({ EnvironmentRepositories.create(it, http) }, clock)
    private var envJob: Job? = null

    private val _state = MutableStateFlow(UiState(debuggable = debuggable))
    val state: StateFlow<UiState> = _state

    private var saveJob: Job? = null
    private var hwJob: Job? = null
    private var simReceiver: BroadcastReceiver? = null

    // 시연 값(메모리 전용, 저장하지 않음)
    private var simHour: Int? = null
    private var simWeather: Weather? = null
    private var simAir: AirGrade? = null
    private var outsideC: Float? = null

    private val restored: Boolean get() = !_state.value.loading

    init {
        signals.hub.setDriveOverride(null); signals.hub.setEnabled(false)
        hw.onState = { st, byUs -> scope.launch { dispatch(FlowEvent.Hw(st, byUs)) } }
        hw.onApplyDone = { r -> scope.launch { dispatch(FlowEvent.ApplyResult(r)); if (_state.value.fs.parked) HvacErrors.summary(r).takeIf { it.isNotBlank() }?.let { toast(it) } } }
        hw.onUserOn = { a, l -> scope.launch { dispatch(FlowEvent.UserTurnedOn(a, l)) } }
        (hw as? SeatOccupancySource)?.onOccupied = { occ -> scope.launch { onSeatOccupied(occ) } }
        hwJob = scope.launch { hw.run() }
        scope.launch { restore() }
        scope.launch {
            OutsideTemp.flow(context) { signals.hub.state.value }.catch { HmgLog.w("Outside", "외기온도 flow 실패") }.collect { outsideC = it; refreshCtx() }
        }
        scope.launch { loadNetPrefs() }
        scope.launch { while (true) { delay(TICK_MS); refreshCtx(); dispatch(FlowEvent.Tick) } }
        if (debuggable) simReceiver = SimReceiver.register(context, this, monoClock)
    }

    // ---------- 환경/상황 ----------
    private fun airNow(): AirReading = feed.view(simWeather, simAir).air
    private fun weatherNow(): Weather = feed.view(simWeather, simAir).weather
    private fun hourNow(): Int = simHour ?: Calendar.getInstance().apply { timeInMillis = clock() }.get(Calendar.HOUR_OF_DAY)
    private fun ctxNow() = Ctx(outsideC ?: signals.hub.state.value.takeIf { it.enabled }?.outsideTempC, hourNow(), weatherNow())
    private fun epochDay(): Long { val now = clock(); return (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L }
    private fun envNow() = Env(clock(), ctxNow(), airNow(), epochDay(), demo = simWeather != null || simAir != null)

    private fun refreshCtx() {
        val c = ctxNow(); val v = feed.view(simWeather, simAir)
        _state.update { it.copy(ctx = c, air = v.air.grade, weather = c.weather, envBadge = v.badge, regionId = feed.region.id, netConsent = feed.consent, demoWeather = simWeather, demoAir = simAir, simActive = simHour != null || simWeather != null || simAir != null || signals.hub.state.value.let { h -> h.enabled && h.outsideTempC != null }) }
    }

    /** 동의했을 때만 네트워크를 쓴다(백그라운드 IO). 실패하면 마지막 성공값이 유지된다. */
    private suspend fun refreshEnv() {
        try { feed.refresh() } catch (e: CancellationException) { throw e } catch (t: Throwable) { HmgLog.w("Env", "갱신 실패: ${t.javaClass.simpleName}") }
        refreshCtx()
    }

    private fun restartEnvLoop() {
        envJob?.cancel()
        envJob = scope.launch { while (true) { refreshEnv(); delay(ENV_REFRESH_MS) } }
    }

    private suspend fun loadNetPrefs() {
        val loaded = withContext(Dispatchers.IO) { netPrefs.load() }
        val d = netLoadResult(netWiped, loaded) ?: return // 읽는 중 삭제됐다면 적용을 건너뜀(reset 이 이미 기본값을 세웠고 이후 사용자 선택도 보존)
        feed.setRegion(Regions.byId(d.regionId)); feed.setConsent(d.consent)
        _state.update { it.copy(netLoaded = true, netDecided = d.decided) }
        refreshCtx()
        restartEnvLoop()
    }

    @Volatile private var netWiped = false
    private val netSaveMutex = Mutex()
    @Volatile private var latestNet: NetPrefsData? = null

    /** 단일 순차 저장: 요청이 겹쳐도 마지막 요청 값만 쓰고(순서 역전 없음), 취소돼도 끝까지 쓴다. */
    private fun saveNetPrefs() {
        latestNet = NetPrefsData(decided = _state.value.netDecided, consent = feed.consent, regionId = feed.region.id)
        scope.launch {
            val r = withContext(NonCancellable) {
                netSaveMutex.withLock {
                    val d = latestNet ?: return@withLock null
                    latestNet = null
                    withContext(Dispatchers.IO) { NetPrefsWriter(netPrefs).write(d) }
                }
            }
            if (r == NetWrite.FAILED_MAY_REAPPEAR) toast("동의 철회를 저장하지 못했어요")
        }
    }

    // ---------- 날씨·대기질 네트워크 사용(정차 중에만) ----------
    /** 동의/철회. 동의 전에는 외부 호출이 없고, 철회하면 받은 값을 버리고 이후 호출하지 않는다. */
    fun setNetConsent(on: Boolean) = parkedOnly {
        if (!_state.value.netLoaded) return@parkedOnly
        feed.setConsent(on)
        _state.update { it.copy(netDecided = true, netPromptSnoozed = false) }
        saveNetPrefs(); refreshCtx(); restartEnvLoop()
    }
    /** 최초 고지를 이번 실행에서만 닫는다(결정은 저장하지 않음 -> 호출 없음). */
    fun snoozeNetPrompt() = _state.update { it.copy(netPromptSnoozed = true) }
    fun selectRegion(id: String) = parkedOnly {
        if (!_state.value.netLoaded) return@parkedOnly
        feed.setRegion(Regions.byId(id))
        saveNetPrefs(); refreshCtx(); restartEnvLoop()
    }

    // ---------- 이벤트 -> reducer -> 효과 ----------
    internal fun dispatch(e: FlowEvent) {
        refreshCtx()
        var fx: List<FlowEffect> = emptyList()
        val e0 = envNow()
        _state.update { st -> ClimateFlow.reduce(st.fs, e, e0).let { (n, f) -> fx = f; st.copy(fs = n) } }
        fx.forEach(::run)
    }

    private fun run(f: FlowEffect) {
        when (f) {
            is FlowEffect.Apply -> { val n = hw.apply(f.settings); HmgLog.i("Climate", "적용 요청 ${n}건 (${f.reason.take(40)})") }
            is FlowEffect.SetRecirc -> hw.setRecirc(f.on) { ok -> scope.launch { if (ok) speaker.speak(f.reason); dispatch(FlowEvent.RecircResult(ok, f.reason, f.on)) } } // 안내는 쓰기 성공 뒤에만
            is FlowEffect.Speak -> speaker.speak(f.text)
            FlowEffect.Persist -> scheduleSave()
        }
    }

    private fun toast(m: String) = _state.update { it.copy(toast = m) }
    fun clearToast() = _state.update { it.copy(toast = null) }

    // ---------- 복원 ----------
    private suspend fun restore() {
        var res = StateStore.Restored(Persisted(), corrupt = false, quarantined = false, readError = false)
        try {
            res = withContext(Dispatchers.IO) { stateStore.restore(clock()) }
        } catch (e: CancellationException) { throw e } catch (t: Throwable) {
            HmgLog.w("Store", "복원 실패 -> 기본값, 이번 세션 저장 보류: ${t.javaClass.simpleName}")
            stateStore.holdWrites(); res = res.copy(readError = true)
        } finally {
            _state.update { it.copy(loading = false, saveHeld = res.readError, corruptKept = res.corrupt && res.quarantined) }
            dispatch(FlowEvent.Restored(res.p))
            evalSeatOffer()
        }
    }

    // ---------- 수명주기 ----------
    fun onAppStart() { hw.retryNow() }

    fun onDriveState(d: DriveState) {
        val parked = d == DriveState.PARKED
        if (_state.value.fs.parked == parked) return
        dispatch(FlowEvent.DriveChanged(parked))
        evalSeatOffer()
    }

    // ---------- 착석 기반 프로필 제안(확인 후에만 적용) ----------
    private var seatOccupied: Boolean? = null
    private var seatHandled = false

    internal fun onSeatOccupied(occ: Boolean?) {
        if (occ != true) seatHandled = false
        seatOccupied = occ
        evalSeatOffer()
    }

    private fun evalSeatOffer() {
        val fs = _state.value.fs
        val o = SeatOfferPolicy.offer(fs.p, restored, fs.parked, seatOccupied, seatHandled)
        _state.update { if (it.seatOffer == o) it else it.copy(seatOffer = o) }
    }

    /** 확인: 제안한 프로필이 지금도 마지막 사용 프로필일 때만 적용한다. */
    fun acceptSeatOffer() = parkedOnly {
        val offer = _state.value.seatOffer ?: return@parkedOnly
        seatHandled = true
        _state.update { it.copy(seatOffer = null) }
        if (_state.value.fs.p.selectedId == offer.profileId) dispatch(FlowEvent.ApplyNow)
    }

    fun dismissSeatOffer() { seatHandled = true; _state.update { it.copy(seatOffer = null) } }

    // ---------- 사용자 조작 (모두 정차 중에만) ----------
    private inline fun parkedOnly(block: () -> Unit) { if (!_state.value.fs.parked || !restored) return; block() }

    private fun editP(f: (Persisted) -> Persisted?) {
        var changed = false
        _state.update { st -> f(st.fs.p)?.let { np -> changed = np != st.fs.p; st.copy(fs = st.fs.copy(p = np)) } ?: st }
        if (changed) scheduleSave()
    }

    fun selectProfile(id: String) = parkedOnly { editP { Edits.select(it, id) }; dispatch(FlowEvent.ProfileChanged) }

    /** 현재 차량 값(읽은 것만)으로 새 프로필을 만든다. */
    fun addProfile() = parkedOnly {
        val st = _state.value.fs
        // 체감 유지 보정이 적용된 값이면 보정량만큼 되돌려 기준 온도로 저장한다(다음 적용 때 이중 보정 방지)
        val base = st.hw.current.let { c -> if (c.isEmpty) ClimateSettings(tempC = 22f, fan = 3, ac = true, recirc = false, seatHeat = 0, seatVent = 0, wheelHeat = 0) else c.copy(tempC = FeelKeep.undo(c.tempC, st.fkTemp, st.fkDelta)) }
        editP { p -> Edits.addProfile(p, "", base) ?: p }
    }

    fun editProfile(id: String, f: (ClimateSettings) -> ClimateSettings) = parkedOnly { editP { p -> Edits.updateProfile(p, id) { it.copy(settings = f(it.settings)) } } }
    fun deleteProfile(id: String) = parkedOnly { editP { Edits.deleteProfile(it, id) } }
    fun setAutoApply(on: Boolean) = parkedOnly { editP { it.copy(autoApply = on) } }
    fun setSmartRecirc(on: Boolean) = parkedOnly { editP { it.copy(smartRecirc = on) } }
    fun setFeelKeep(on: Boolean) = parkedOnly { editP { it.copy(feelKeep = on) } }
    /** 시연용 날씨/대기질 선택(정차 중, 메모리 전용). null = 실제/기본 값. */
    fun setDemoWeather(w: Weather?) = parkedOnly { simWeather = w; refreshCtx() }
    fun setDemoAir(a: AirGrade?) = parkedOnly { simAir = a; refreshCtx() }
    fun applyNow() = parkedOnly { dispatch(FlowEvent.ApplyNow) }

    fun upsertRule(r: PresetRule) = parkedOnly {
        editP { p -> Edits.upsertRule(p, r) ?: p }
    }
    fun newRuleId(): String = Edits.newId("u", _state.value.fs.p.rules.map { it.id })
    fun deleteRule(id: String) = parkedOnly { editP { Edits.deleteRule(it, id) } }
    fun toggleRule(id: String) = parkedOnly { editP { Edits.toggleRule(it, id) } }
    fun acceptSuggestion() = parkedOnly {
        dispatch(FlowEvent.SuggestAccepted)
    }
    fun dismissSuggestion() = parkedOnly { dispatch(FlowEvent.SuggestDismissed) }

    /** 학습 기록(습관·거절 목록)만 지운다. */
    fun clearLearned() = parkedOnly { dispatch(FlowEvent.LearnedCleared) }

    /** 저장된 모든 데이터(프로필·규칙·학습) 삭제 + 기본값으로 재기록. */
    fun clearAllData() = parkedOnly {
        saveJob?.cancel()
        editP { Persisted() }
        _state.update { it.copy(fs = it.fs.copy(pending = null)) }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { stateStore.clearAll { persistedCopy() } }
            _state.update { it.copy(saveHeld = !stateStore.writable, corruptKept = if (ok) false else it.corruptKept) }
            if (!ok) toast("삭제하지 못했어요")
        }
        resetNetPrefs()
    }

    /** 인터넷 동의·지역·실시간 값을 처음 상태로: 메모리 즉시 초기화(이후 호출 없음), 파일은 직렬화 큐로 삭제, 최초 고지가 다시 뜬다. */
    private fun resetNetPrefs() {
        netWiped = true
        envJob?.cancel(); envJob = null
        feed.resetAll()
        latestNet = null // 대기 중이던 저장 요청은 버린다
        _state.update { it.copy(netLoaded = true, netDecided = false, netPromptSnoozed = false) }
        refreshCtx()
        scope.launch(start = CoroutineStart.ATOMIC) {
            val r = withContext(NonCancellable) { netSaveMutex.withLock { withContext(Dispatchers.IO) { NetPrefsWriter(netPrefs).reset() } } }
            if (r == NetWrite.FAILED_MAY_REAPPEAR) toast("동의 철회를 저장하지 못했어요")
        }
    }

    // ---------- 시연(debuggable, 시스템 주도: 주행 가드 예외) ----------
    internal fun simOutside(v: Float?) { val h = signals.hub; if (v != null) { h.setEnabled(true); h.setOutsideTempC(v) } else { h.setOutsideTempC(null); if (h.state.value.driveOverride == null) h.setEnabled(false) }; outsideC = v; refreshCtx() }
    internal fun simHour(v: Int?) { simHour = v; refreshCtx() }
    internal fun simWeather(v: Weather?) { simWeather = v; refreshCtx() }
    internal fun simAir(v: AirGrade?) { simAir = v; refreshCtx() }
    internal fun simUserTurnedOn(a: HabitAction, level: Int) = dispatch(FlowEvent.UserTurnedOn(a, level))
    internal fun simTick() { refreshCtx(); dispatch(FlowEvent.Tick) }
    internal fun simRecircAge(minutes: Int) {
        _state.update { st -> st.copy(fs = st.fs.copy(recirc = st.fs.recirc.copy(onSince = clock() - minutes * 60_000L, lastDecisionAt = Long.MIN_VALUE / 2))) }
    }
    internal fun simSmart(on: Boolean) = editP { it.copy(smartRecirc = on) }
    internal fun simSelectProfile(id: String) { editP { Edits.select(it, id) } }
    internal fun simAddProfile(name: String, s: ClimateSettings) = editP { Edits.addProfile(it, name, s) }

    /** 시연 주행 강제(DRIVING 만 효과, null=실신호). */
    fun setDemoDrive(v: String?) {
        if (!debuggable) return
        val h = signals.hub
        if (v == "DRIVING") { h.setEnabled(true); h.setDriveOverride(DriveState.DRIVING) } else { h.setDriveOverride(null); if (h.state.value.outsideTempC == null) h.setEnabled(false) }
        refreshCtx()
    }
    val demoDriving: Boolean get() = signals.hub.state.value.driveOverride == DriveState.DRIVING

    // ---------- 저장 ----------
    private fun persistedCopy(): Persisted = _state.value.fs.p

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            if (!restored) return@launch
            onWriteResult(withContext(Dispatchers.IO + NonCancellable) { stateStore.writeLatest { persistedCopy() } })
        }
    }

    private fun onWriteResult(r: WriteResult) {
        when (r) {
            WriteResult.WROTE -> _state.update { if (it.corruptKept) it.copy(corruptKept = false) else it }
            WriteResult.FAILED -> _state.update { it.copy(saveHeld = true, toast = "저장하지 못했어요") }
            WriteResult.UNCHANGED, WriteResult.HELD -> Unit
        }
    }

    fun saveNow() {
        if (!restored) return
        saveJob?.cancel()
        onWriteResult(stateStore.writeLatest { persistedCopy() })
    }

    fun release() {
        saveNow()
        simReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        // 해제 순서: 코루틴 취소 -> 하드웨어 리스너 해제 -> 음성
        scope.cancel()
        hw.close(); speaker.release()
    }

    companion object {
        const val TICK_MS = 10_000L
        const val ENV_REFRESH_MS = 10 * 60_000L
    }
}

/** 늦게 끝난 읽기 결과 적용 여부: 삭제됐다면 null(적용 생략), 아니면 읽은 값. */
internal fun netLoadResult(wiped: Boolean, loaded: NetPrefsData): NetPrefsData? = if (wiped) null else loaded
