package com.hmgcarapp.myclimate.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.HvacSteeringWheelHeatStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import android.content.Context
import android.os.SystemClock
import com.hmgcarapp.core.connection.ConnectionPolicy
import com.hmgcarapp.core.connection.ManagedConnection
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.myclimate.domain.AreaPicker
import com.hmgcarapp.myclimate.domain.ClimateCaps
import com.hmgcarapp.myclimate.domain.ClimateSettings
import com.hmgcarapp.myclimate.domain.EchoClassifier
import com.hmgcarapp.myclimate.domain.Origin
import com.hmgcarapp.myclimate.domain.UserDetect
import com.hmgcarapp.myclimate.domain.FanDir
import com.hmgcarapp.myclimate.domain.HabitAction
import com.hmgcarapp.myclimate.domain.ReadingSanity
import com.hmgcarapp.myclimate.domain.HwConnection
import com.hmgcarapp.myclimate.domain.HvacErrors
import com.hmgcarapp.myclimate.domain.ItemResult
import com.hmgcarapp.myclimate.domain.HwState
import com.hmgcarapp.myclimate.domain.Limits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** 공조 하드웨어 포트(컨트롤러가 의존하는 최소 인터페이스; 테스트에서는 가짜). 콜백은 SDK 스레드에서 올 수 있다. */
interface ClimateHardware {
    /** 현재 값/capability 갱신. 두 번째 인자: 내기 변화가 우리 명령의 결과(에코)인지. */
    var onState: (HwState, Boolean) -> Unit
    /** 사용자가 직접 ‘켠’ 조작(우리 명령의 에코는 제외). */
    var onUserOn: (HabitAction, Int) -> Unit
    /** [apply] 의 항목별 결과(보고용 apply 만). 응답이 없는 항목은 일정 시간 뒤 실패로 채워 호출된다. */
    var onApplyDone: (List<com.hmgcarapp.myclimate.domain.ItemResult>) -> Unit
    suspend fun run()
    fun retryNow()
    /** 쓰기 가능한 항목만 보낸다. 실패는 상태의 error 로 알린다. 반환: 보낸 명령 수. */
    fun apply(s: ClimateSettings, report: Boolean = true, onDone: ((List<com.hmgcarapp.myclimate.domain.ItemResult>) -> Unit)? = null): Int
    /** 스마트 내외기 전용: 내기 상태만 바꾼다. [onResult] 는 쓰기 성공 여부(전송 0건이면 false). */
    fun setRecirc(on: Boolean, onResult: (Boolean) -> Unit = {}): Boolean
    fun close()
}

/**
 * Pleos Vehicle SDK HVAC / Seat / SteeringWheel 어댑터. 운전석(1열 좌) 기준.
 * 연결·복구는 core 의 [ManagedConnection] 이 맡는다(여기서 재연결 로직을 만들지 않는다). 모든 SDK 콜백은 Unit 을 반환하고 세대(gen) 필터를 거친다.
 * 권한: pleos.car.permission.CAR_CLIMATE / CONTROL_CAR_CLIMATE / CAR_SEATS / CONTROL_CAR_SEATS (조회·제어 각각).
 * capability: 항목별 check*Capabilities 의 AccessType 이 WRITE/READ_WRITE 이고 운전석 영역이 있어야 쓰기 가능으로 본다. 범위(min/max)는 bounds 로 맞춘다.
 */
class PleosClimateHardware(context: Context, private val clock: () -> Long = SystemClock::elapsedRealtime) : ClimateHardware {
    override var onState: (HwState, Boolean) -> Unit = { _, _ -> }
    override var onUserOn: (HabitAction, Int) -> Unit = { _, _ -> }
    override var onApplyDone: (List<ItemResult>) -> Unit = {}

    private val appCtx = context.applicationContext
    /** 속성별로 capability 가 알려 준 운전석 포함 영역(락 보호). 확정 전에는 get/set/register 하지 않는다. */
    private val areas = HashMap<String, SeatArea>()
    private fun areaOf(kind: String): SeatArea = synchronized(lock) { areas[kind] } ?: throw IllegalStateException("area not ready: $kind")
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val echo = EchoClassifier()
    private val lock = Any()

    private class Session(val vehicle: Vehicle) {
        val unregs = CopyOnWriteArrayList<() -> Unit>()
        fun close() { unregs.forEach { runCatching { it() } }; unregs.clear(); runCatching { vehicle.release() } }
    }

    private val conn = ManagedConnection<Session>(
        tag = "Climate",
        create = { Session(Vehicle(appCtx)) },
        open = { s, _ -> s.vehicle.initialize() },
        dispose = { s -> s.close() },
        clock = clock,
        policy = ConnectionPolicy(silenceCycles = SILENCE_CYCLES, deathWaitsBackoff = false, minReplaceIntervalMs = 60_000L),
        onCreateFailed = { t -> HmgLog.w("Climate", "Vehicle SDK 사용 불가: ${t.javaClass.simpleName}"); publish(error = "SDK 불가", byUs = false) },
    )

    // ----- 상태(락 보호) -----
    private var syncedGen = 0
    private var pendingCaps = 0
    private var capsAt = 0L
    private var caps = ClimateCaps()
    private var cur = ClimateSettings()
    private var err: String? = null
    private var lastRecircByUs = false
    private var respSeen = false

    private fun publish(error: String? = null, byUs: Boolean) {
        val hw = synchronized(lock) {
            if (error != null) err = error
            HwState(connected = HwConnection.connected(pendingCaps, syncedGen, conn.isReady(), conn.isLost()), caps = caps, current = cur, error = err)
        }
        onState(hw, byUs)
    }

    // ----- 등록(리스너): 영역이 확정된 속성만, capability 응답 뒤에 -----
    private fun registerKind(s: Session, gen: Int, kind: String, area: SeatArea?) {
        val v = s.vehicle
        val h = v.getHVAC(); val seat = v.getSeat(); val wheel = v.getSteeringWheel()
        fun alive(): Boolean = conn.isCurrent(gen)
        fun <R> reg(register: () -> R, unregister: () -> Unit) { register(); s.unregs += unregister }
        try {
            when (kind) {
                "ac" -> { val l = object : HvacAcStateListener {
                    override fun onHvacAcStateUpdated(state: Boolean?) { if (alive()) observe("ac", state?.let { if (it) 1.0 else 0.0 }) }
                    override fun onFailed(e: Exception) { conn.onFailure(gen, e) } }
                    reg({ h.registerHvacAcState(area!!, l) }, { h.unregisterHvacAcState(area!!, l) }) }
                "recirc" -> { val l = object : HvacRecirculationStateListener {
                    override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) { if (alive()) observe("recirc", state?.let { if (it) 1.0 else 0.0 }) }
                    override fun onFailed(e: Exception) { conn.onFailure(gen, e) } }
                    reg({ h.registerHvacRecirculationState(area!!, l) }, { h.unregisterHvacRecirculationState(area!!, l) }) }
                "fan" -> { val l = object : HvacFanSpeedListener {
                    override fun onHvacFanSpeedUpdated(speed: Int?) { if (alive()) observe("fan", speed?.toDouble()) }
                    override fun onFailed(e: Exception) { conn.onFailure(gen, e) } }
                    reg({ h.registerHvacFanSpeed(area!!, l) }, { h.unregisterHvacFanSpeed(area!!, l) }) }
                "temp" -> { val l = object : HvacTemperatureValueListener {
                    override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) { if (alive()) observe("temp", celsius?.toDouble()) }
                    override fun onFailed(e: Exception) { conn.onFailure(gen, e) } }
                    reg({ h.registerHvacTemperature(area!!, l) }, { h.unregisterHvacTemperature(area!!, l) }) }
                "seatHeat" -> { val l = object : HvacSeatTemperatureListener {
                    override fun onHvacSeatTemperatureUpdated(temperature: Int?) { if (alive()) observe("seatHeat", temperature?.toDouble()) }
                    override fun onFailed(e: Exception) { conn.onFailure(gen, e) } }
                    reg({ seat.registerHvacSeatTemperature(area!!, l) }, { seat.unregisterHvacSeatTemperature(area!!, l) }) }
                "seatVent" -> { val l = object : HvacSeatVentilationListener {
                    override fun onHvacSeatVentilationUpdated(ventilation: Int?) { if (alive()) observe("seatVent", ventilation?.toDouble()) }
                    override fun onFailed(e: Exception) { conn.onFailure(gen, e) } }
                    reg({ seat.registerHvacSeatVentilation(area!!, l) }, { seat.unregisterHvacSeatVentilation(area!!, l) }) }
                "wheelHeat" -> { val l = object : HvacSteeringWheelHeatStateListener {
                    override fun onHvacSteeringWheelHeatStateUpdated(state: Int?) { if (alive()) observe("wheelHeat", state?.toDouble()) }
                    override fun onFailed(e: Exception) { conn.onFailure(gen, e) } }
                    reg({ wheel.registerHvacSteeringWheelHeatState(l) }, { wheel.unregisterHvacSteeringWheelHeatState(l) }) }
            }
        } catch (t: Throwable) { if (t is CancellationException) throw t; conn.onFailure(gen, t); HmgLog.w("Climate", "리스너 등록 실패 $kind: ${t.javaClass.simpleName}") }
    }

    /** 값 관찰(리스너·읽기 공통). 에코면 사용자 조작으로 세지 않는다. */
    private fun valueOf(c: ClimateSettings, kind: String): Double? = when (kind) {
        "ac" -> c.ac?.let { if (it) 1.0 else 0.0 }; "recirc" -> c.recirc?.let { if (it) 1.0 else 0.0 }
        "fan" -> c.fan?.toDouble(); "temp" -> c.tempC?.toDouble(); "seatHeat" -> c.seatHeat?.toDouble()
        "seatVent" -> c.seatVent?.toDouble(); else -> c.wheelHeat?.toDouble()
    }

    private fun observe(kind: String, value: Double?) {
        if (value == null) return
        // 서비스 초기화 전 기본값(예: 온도 0.0)은 ‘아직 읽지 못함’: 상태·학습·에코 어디에도 반영하지 않는다
        if (!ReadingSanity.accept(kind, value, synchronized(lock) { caps.limits })) { HmgLog.w("Climate", "범위 밖 읽기값 무시 kind=$kind value=$value"); return }
        val origin = echo.classify(kind, value, clock())
        var user: Pair<HabitAction, Int>? = null
        synchronized(lock) {
            val prev = valueOf(cur, kind)
            cur = when (kind) {
                "ac" -> cur.copy(ac = value > 0.5); "recirc" -> cur.copy(recirc = value > 0.5)
                "fan" -> cur.copy(fan = value.toInt()); "temp" -> cur.copy(tempC = value.toFloat())
                "seatHeat" -> cur.copy(seatHeat = value.toInt()); "seatVent" -> cur.copy(seatVent = value.toInt())
                else -> cur.copy(wheelHeat = value.toInt())
            }
            user = UserDetect.turnedOn(kind, prev, value, origin)
            respSeen = true
        }
        user?.let { onUserOn(it.first, it.second) }
        publish(byUs = origin == Origin.SMART_ECHO && kind == "recirc")
    }

    // ----- capability -----
    private fun writable(c: CapabilityConfig) = c.accessType == AccessType.WRITE || c.accessType == AccessType.READ_WRITE

    /** 운전석 포함 영역 선택(AreaPicker). 선택 결과를 속성당 한 번 로그(상수 이름뿐). */
    private fun pick(kind: String, list: Collection<SeatArea>): SeatArea? {
        val l = list.toList()
        val i = AreaPicker.pickDriverArea(l.map { a -> a.values.map { it.name } })
        HmgLog.i("Climate", "area $kind 후보=${l.map { a -> a.values.map { it.name } }} 선택=${i?.let { l[it].values.map { v -> v.name } } ?: "없음(미지원)"}")
        return i?.let { l[it] }
    }

    private fun capDone(gen: Int, kind: String, access: CapabilityConfig?, area: SeatArea?, needsArea: Boolean = true, update: (ClimateCaps, Boolean) -> ClimateCaps) {
        if (!conn.isCurrent(gen)) return
        conn.onAlive(gen) // 실패 콜백 경로에서도 불리므로 사망 표시를 지우지 않는다(onResponse 금지)
        val ok = access != null && writable(access) && (area != null || !needsArea)
        HmgLog.i("Climate", "capability $kind access=${access?.accessType} 지원=$ok")
        synchronized(lock) { if (area != null) areas[kind] = area }
        // 영역이 확정된 속성만 리스너 등록(읽기 전용이어도 영역이 있으면 등록)
        if (area != null || (!needsArea && access != null)) conn.current()?.takeIf { it.gen == gen }?.let { registerKind(it.value, gen, kind, area) }
        synchronized(lock) { caps = update(caps, ok); respSeen = true; if (pendingCaps > 0) pendingCaps-- }
        publish(byUs = false)
    }

    private fun requestCaps(c: com.hmgcarapp.core.connection.Conn<Session>) {
        val v = c.value.vehicle; val gen = c.gen
        val h = v.getHVAC(); val seat = v.getSeat(); val wheel = v.getSteeringWheel()
        synchronized(lock) { pendingCaps = 8; capsAt = clock(); caps = ClimateCaps(); cur = ClimateSettings(); err = null; areas.clear() }
        val fail: (String) -> (Exception) -> Unit = { name -> { e -> conn.ifCurrent(gen) { conn.onFailure(gen, e); HmgLog.w("Climate", "capability $name 실패: ${e.javaClass.simpleName}"); capDone(gen, name, null, null) { cp, _ -> cp } } } }
        h.checkHvacAcCapabilities({ cfg: CapabilityConfig, l: List<SeatArea> -> capDone(gen, "ac", cfg, pick("ac", l)) { cp, ok -> cp.copy(ac = ok) } }, fail("ac"))
        h.checkHvacRecirculationCapabilities({ cfg: CapabilityConfig, l: List<SeatArea> -> capDone(gen, "recirc", cfg, pick("recirc", l)) { cp, ok -> cp.copy(recirc = ok) } }, fail("recirc"))
        h.checkHvacFanDirectionCapabilities({ cfg: CapabilityConfig, m: Map<SeatArea, List<FanDirectionType>> ->
            val a = pick("fanDir", m.filterValues { it.isNotEmpty() }.keys)
            capDone(gen, "fanDir", cfg, a) { cp, ok -> cp.copy(fanDir = ok) } }, fail("fanDir"))
        h.checkHvacFanSpeedCapabilities({ cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
            val a = pick("fan", m.keys); val b = a?.let { m[it] }
            capDone(gen, "fan", cfg, a) { cp, ok -> cp.copy(fan = ok, limits = if (b != null) cp.limits.copy(fanMin = b.minValue.coerceAtLeast(1), fanMax = b.maxValue.coerceAtLeast(1)) else cp.limits) } }, fail("fan"))
        h.checkHvacTemperatureCapabilities({ cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Float>>, _: HvacTemperatureConfigArray? ->
            val a = pick("temp", m.keys); val b = a?.let { m[it] }
            capDone(gen, "temp", cfg, a) { cp, ok -> cp.copy(temp = ok, limits = if (b != null && b.minValue < b.maxValue) cp.limits.copy(tempMin = b.minValue, tempMax = b.maxValue) else cp.limits) } }, fail("temp"))
        seat.checkSeatTemperatureCapabilities({ cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
            val a = pick("seatHeat", m.keys); val b = a?.let { m[it] }
            capDone(gen, "seatHeat", cfg, a) { cp, ok -> cp.copy(seatHeat = ok, limits = if (b != null) cp.limits.copy(seatHeatMax = b.maxValue.coerceAtLeast(1)) else cp.limits) } }, fail("seatHeat"))
        seat.checkSeatVentilationCapabilities({ cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
            val a = pick("seatVent", m.keys); val b = a?.let { m[it] }
            capDone(gen, "seatVent", cfg, a) { cp, ok -> cp.copy(seatVent = ok, limits = if (b != null) cp.limits.copy(seatVentMax = b.maxValue.coerceAtLeast(1)) else cp.limits) } }, fail("seatVent"))
        wheel.checkHvacSteeringWheelHeatCapabilities({ cfg: CapabilityConfig, b: AreaBounds<Int> ->
            capDone(gen, "wheelHeat", cfg, null, needsArea = false) { cp, ok -> cp.copy(wheelHeat = ok, limits = cp.limits.copy(wheelHeatMax = b.maxValue.coerceAtLeast(1))) } }, fail("wheelHeat"))
    }

    // ----- 현재 값 읽기(주기) -----
    private fun readAll(c: com.hmgcarapp.core.connection.Conn<Session>) {
        val v = c.value.vehicle; val gen = c.gen
        val h = v.getHVAC(); val seat = v.getSeat(); val wheel = v.getSteeringWheel()
        val okAny: (String) -> (Double?) -> Unit = { k -> { x -> conn.ifCurrent(gen) { conn.onResponse(gen); observe(k, x) } } }
        val bad: (Exception) -> Unit = { e -> conn.ifCurrent(gen) { conn.onAlive(gen); conn.onFailure(gen, e) } }
        val cp = synchronized(lock) { caps }
        if (cp.ac) h.getHvacAcState(areaOf("ac"), { b -> okAny("ac")(b?.let { if (it) 1.0 else 0.0 }) }, bad)
        if (cp.recirc) h.getHvacRecirculationState(areaOf("recirc"), { b -> okAny("recirc")(b?.let { if (it) 1.0 else 0.0 }) }, bad)
        if (cp.fan) h.getHvacFanSpeed(areaOf("fan"), { x -> okAny("fan")(x?.toDouble()) }, bad)
        if (cp.temp) h.getHvacTemperature(areaOf("temp"), TemperatureUnit.CELSIUS, { x -> okAny("temp")(x?.toDouble()) }, bad)
        if (cp.seatHeat) seat.getHvacSeatTemperature(areaOf("seatHeat"), { x -> okAny("seatHeat")(x?.toDouble()) }, bad)
        if (cp.seatVent) seat.getHvacSeatVentilationState(areaOf("seatVent"), { x -> okAny("seatVent")(x?.toDouble()) }, bad)
        if (cp.wheelHeat) wheel.getHvacSteeringWheelHeatState({ x -> okAny("wheelHeat")(x?.toDouble()) }, bad)
    }

    // ----- 제어 -----
    private fun ready(): com.hmgcarapp.core.connection.Conn<Session>? = conn.readyConn()

    /** 진행 중인 보고용 쓰기 묶음(락 보호). */
    private class Batch(val kinds: List<String>, val at: Long, val report: Boolean, val cb: ((List<ItemResult>) -> Unit)?) { val results = ArrayList<ItemResult>() }
    private var batch: Batch? = null

    private fun record(b: Batch, kind: String, ok: Boolean, code: Int?) {
        var done: List<ItemResult>? = null
        synchronized(lock) {
            if (b.results.any { it.kind == kind }) return
            b.results += ItemResult(kind, ok, code)
            if (b.results.size >= b.kinds.size) { if (batch === b) batch = null; done = b.results.toList() }
        }
        if (!ok) { echo.forget(kind); HmgLog.w("Climate", "쓰기 실패 kind=$kind errorCode=${code ?: "없음"}") }
        done?.let { if (b.report) onApplyDone(it); b.cb?.invoke(it) }
    }

    /** 응답이 없는 항목을 실패(코드 없음)로 채워 묶음을 마친다. */
    private fun flushStale() {
        val b = synchronized(lock) { batch?.takeIf { clock() - it.at > APPLY_TIMEOUT_MS } } ?: return
        b.kinds.filter { k -> synchronized(lock) { b.results.none { it.kind == k } } }.forEach { record(b, it, false, 4) }
    }

    override fun apply(s: ClimateSettings, report: Boolean, onDone: ((List<ItemResult>) -> Unit)?): Int {
        val c = ready() ?: run { if (report) onApplyDone(listOf(ItemResult("conn", false, null))); onDone?.invoke(emptyList()); return 0 }
        val gen = c.gen; val v = c.value.vehicle
        val cp = synchronized(lock) { caps }
        val t = cp.filter(s)
        val h = v.getHVAC(); val seat = v.getSeat(); val wheel = v.getSteeringWheel()
        val kinds = buildList {
            if (t.ac != null) add("ac"); if (t.recirc != null) add("recirc"); if (t.fan != null) add("fan"); if (t.tempC != null) add("temp")
            if (t.fanDir != null) add("fanDir"); if (t.seatHeat != null) add("seatHeat"); if (t.seatVent != null) add("seatVent"); if (t.wheelHeat != null) add("wheelHeat")
        }
        if (kinds.isEmpty()) { if (report) onApplyDone(emptyList()); onDone?.invoke(emptyList()); return 0 }
        val b = Batch(kinds, clock(), report, onDone)
        synchronized(lock) { batch = b }
        fun ok(k: String): (Any?, Any?) -> Unit = { _, _ -> conn.ifCurrent(gen) { conn.onResponse(gen); record(b, k, true, null) } }
        fun fail(k: String): (Exception) -> Unit = { e -> conn.ifCurrent(gen, stale = { }) { conn.onAlive(gen); conn.onFailure(gen, e); record(b, k, false, HvacErrors.codeOf(e)) } }
        var n = 0
        fun guard(k: String, block: () -> Unit) { try { block(); n++ } catch (t2: Throwable) { if (t2 is CancellationException) throw t2; conn.onFailure(gen, t2); record(b, k, false, HvacErrors.codeOf(t2)) } }
        val now = clock()
        t.ac?.let { x -> echo.expect("ac", if (x) 1.0 else 0.0, now, false); guard("ac") { h.setHvacAc(areaOf("ac"), x, { a, r -> ok("ac")(a, r) }, fail("ac")) } }
        t.recirc?.let { x -> echo.expect("recirc", if (x) 1.0 else 0.0, now, !report); guard("recirc") { h.setHvacRecirculation(areaOf("recirc"), x, { a, r -> ok("recirc")(a, r) }, fail("recirc")) } }
        t.fan?.let { x -> echo.expect("fan", x.toDouble(), now, false); guard("fan") { h.setHvacFanSpeed(areaOf("fan"), x, { a, r -> ok("fan")(a, r) }, fail("fan")) } }
        t.tempC?.let { x -> echo.expect("temp", x.toDouble(), now, false); guard("temp") { h.setHvacTemperature(areaOf("temp"), x, TemperatureUnit.CELSIUS, { a, r -> ok("temp")(a, r) }, fail("temp")) } }
        t.fanDir?.let { x -> guard("fanDir") { h.setHvacFanDirection(areaOf("fanDir"), x.toSdk(), { a, r -> ok("fanDir")(a, r) }, fail("fanDir")) } }
        t.seatHeat?.let { x -> echo.expect("seatHeat", x.toDouble(), now, false); guard("seatHeat") { seat.setHvacSeatTemperature(areaOf("seatHeat"), x, { a, r -> ok("seatHeat")(a, r) }, fail("seatHeat")) } }
        t.seatVent?.let { x -> echo.expect("seatVent", x.toDouble(), now, false); guard("seatVent") { seat.setHvacSeatVentilation(areaOf("seatVent"), x, { a, r -> ok("seatVent")(a, r) }, fail("seatVent")) } }
        t.wheelHeat?.let { x -> echo.expect("wheelHeat", x.toDouble(), now, false); guard("wheelHeat") { wheel.setHvacSteeringWheelHeat(x, { r -> ok("wheelHeat")(null, r) }, fail("wheelHeat")) } }
        HmgLog.i("Climate", "공조 명령 ${n}건 전송(${kinds.joinToString(",")})")
        return n
    }

    override fun setRecirc(on: Boolean, onResult: (Boolean) -> Unit): Boolean =
        apply(ClimateSettings(recirc = on), report = false) { r -> onResult(r.isNotEmpty() && r.all { it.ok }) } > 0

    private fun FanDir.toSdk() = when (this) {
        FanDir.FACE -> FanDirectionType.FACE; FanDir.FLOOR -> FanDirectionType.FLOOR
        FanDir.FACE_FLOOR -> FanDirectionType.FACE_AND_FLOOR; FanDir.DEFROST -> FanDirectionType.DEFROST
    }

    // ----- 루프 -----
    override fun retryNow() { conn.manualRetry(); kick.trySend(Unit) }

    override suspend fun run() {
        var polled = false
        while (true) {
            val c = conn.connect()
            if (c == null) { withTimeoutOrNull(conn.retryDelayMs().coerceIn(1L, 5_000L)) { kick.receive() }; continue }
            try {
                if (c.gen != syncedGen) { synchronized(lock) { syncedGen = c.gen }; polled = false; requestCaps(c) }
                else {
                    flushStale()
                    val stalled = synchronized(lock) { pendingCaps > 0 && clock() - capsAt > CAPS_TIMEOUT_MS }
                    if (stalled) { synchronized(lock) { pendingCaps = 0 }; HmgLog.w("Climate", "capability 응답 대기 초과 -> 응답한 항목만 사용"); publish(byUs = false) }
                    // 지난 주기에 읽기 요청을 보냈는데 응답이 하나도 없었다면 무응답 1회(연속이면 ManagedConnection 이 교체)
                    val (had, readable) = synchronized(lock) { respSeen.also { respSeen = false } to (pendingCaps == 0 && caps.any) }
                    if (polled && readable && !had) conn.onSilentCycle()
                    if (readable) { readAll(c); polled = true }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                conn.onFailure(c.gen, t)
                HmgLog.w("Climate", "폴링 예외: ${t.javaClass.simpleName}")
            }
            withTimeoutOrNull(POLL_MS) { kick.receive() }
        }
    }

    override fun close() { conn.shutdown() }

    companion object {
        const val POLL_MS = 5_000L
        const val CAPS_TIMEOUT_MS = 4_000L
        const val APPLY_TIMEOUT_MS = 4_000L
        const val SILENCE_CYCLES = 3
    }
}
