package com.hmgcarapp.myclimate.domain

/** 차량 쪽에서 쓸 수 있는 기능/범위(capability 체크 결과). 쓰기 가능 여부와 범위. */
data class ClimateCaps(
    val ac: Boolean = false, val recirc: Boolean = false, val fan: Boolean = false, val fanDir: Boolean = false, val temp: Boolean = false,
    val seatHeat: Boolean = false, val seatVent: Boolean = false, val wheelHeat: Boolean = false,
    val limits: Limits = Limits.DEFAULT,
) {
    val any: Boolean get() = ac || recirc || fan || fanDir || temp || seatHeat || seatVent || wheelHeat

    /** 쓰기 불가 항목은 null 로 걸러내고 범위를 맞춘다. */
    fun filter(s: ClimateSettings): ClimateSettings = limits.clamp(s).let {
        ClimateSettings(
            tempC = it.tempC.takeIf { temp }, fan = it.fan.takeIf { fan }, fanDir = it.fanDir.takeIf { fanDir }, ac = it.ac.takeIf { ac },
            recirc = it.recirc.takeIf { recirc }, seatHeat = it.seatHeat.takeIf { seatHeat }, seatVent = it.seatVent.takeIf { seatVent }, wheelHeat = it.wheelHeat.takeIf { wheelHeat },
        )
    }
}

data class HwState(val connected: Boolean = false, val caps: ClimateCaps = ClimateCaps(), val current: ClimateSettings = ClimateSettings(), val error: String? = null)

/** 이벤트 처리에 쓰는 환경(시간·상황·대기질을 주입). */
data class Env(val nowMs: Long, val ctx: Ctx, val air: AirReading?, val epochDay: Long, /** 시연 날씨/대기질 선택이 규칙 판단에 쓰였는지. */ val demo: Boolean = false)

data class FlowState(
    val p: Persisted = Persisted(),
    /** 저장 상태 복원 전에는 적용·학습·제안을 하지 않는다. */
    val loaded: Boolean = false,
    /** 기본 false(=주행으로 간주, fail-safe). */
    val parked: Boolean = false,
    val hw: HwState = HwState(),
    /** 이번 정차 구간에서 자동 적용을 이미 했는지. */
    val appliedThisEpisode: Boolean = false,
    val pending: Suggestion? = null,
    val lastSuggestAtMs: Long? = null,
    val recirc: RecircState = RecircState(),
    /** 마지막 자동 적용/전환의 한 줄 기록(화면 표시용). */
    val note: String? = null,
    /** [note] 가 스마트 내외기 성공 문구인지(사용자가 내기를 바꾸면 모순되므로 비운다). */
    val smartNote: Boolean = false,
    /** 마지막 적용에서 체감 유지 보정이 들어간 경우의 보정 후 목표 온도와 보정량(없으면 null/0). 프로필 기준 온도 복원에 쓴다. */
    val fkTemp: Float? = null,
    val fkDelta: Float = 0f,
)

sealed interface FlowEvent {
    data class Restored(val p: Persisted) : FlowEvent
    data class DriveChanged(val parked: Boolean) : FlowEvent
    /** 차량 연결/capability/현재 값 갱신. [recircByUs]: 내기 상태 변화가 우리 명령의 결과인지. */
    data class Hw(val hw: HwState, val recircByUs: Boolean = false) : FlowEvent
    /** 사용자가 직접 한 ‘켜기’ 조작(에코는 제외하고 보냄). */
    data class UserTurnedOn(val action: HabitAction, val level: Int) : FlowEvent
    /** 주기 평가(상황·대기질 갱신 포함). */
    data object Tick : FlowEvent
    /** 학습 기록 지우기: 습관·거절 목록과 떠 있던 제안도 함께. */
    data object LearnedCleared : FlowEvent
    /** 쓰기 결과 요약(항목별 성공/실패). */
    data class ApplyResult(val results: List<ItemResult>) : FlowEvent
    /** 스마트 내외기 쓰기 결과: 성공이면 안내 문구를 기록, 실패면 정정 문구로 바꾼다. */
    data class RecircResult(val ok: Boolean, val reason: String, val on: Boolean) : FlowEvent
    /** 사용자가 ‘지금 적용’(정차 가드는 컨트롤러가 확인하고, 여기서도 한 번 더 막는다). */
    data object ApplyNow : FlowEvent
    data object ProfileChanged : FlowEvent
    data object SuggestAccepted : FlowEvent
    data object SuggestDismissed : FlowEvent
}

sealed interface FlowEffect {
    data class Apply(val settings: ClimateSettings, val reason: String) : FlowEffect
    /** 스마트 내외기 전환 명령. 안내(Speak)는 쓰기 성공 뒤 실행부가 한다([reason] 이 안내 문구). */
    data class SetRecirc(val on: Boolean, val reason: String) : FlowEffect
    data class Speak(val text: String) : FlowEffect
    /** p 가 바뀌었으니 저장하라. */
    data object Persist : FlowEffect
}

/**
 * 이벤트 -> (새 상태, 효과). 순수: 시간·상황은 [Env] 로 주입. 컨트롤러는 효과만 실행한다.
 * 규칙: 사용자 조작성 이벤트(ApplyNow·제안 수락/거절)와 자동 적용은 정차 중에만. 스마트 내외기 전환은 주행 중에도 하지만 음성 한 줄 안내만 한다.
 */
object ClimateFlow {
    const val SUGGEST_GAP_MS = 6 * 3600_000L

    fun reduce(s: FlowState, e: FlowEvent, env: Env): Pair<FlowState, List<FlowEffect>> = when (e) {
        is FlowEvent.Restored -> tryAutoApply(s.copy(p = e.p, loaded = true), env)
        is FlowEvent.DriveChanged -> {
            val drivingNow = !e.parked
            var n = s.copy(parked = e.parked, pending = if (drivingNow) null else s.pending, lastSuggestAtMs = if (drivingNow && s.pending != null) env.nowMs else s.lastSuggestAtMs)
            if (drivingNow) n = n.copy(appliedThisEpisode = false)
            if (e.parked && !s.parked) tryAutoApply(n.copy(appliedThisEpisode = false), env) else n to emptyList()
        }
        is FlowEvent.Hw -> hw(s, e, env)
        is FlowEvent.UserTurnedOn -> {
            if (!s.loaded) s to emptyList()
            else {
                val h = HabitLearner.record(s.p.habits, e.action, e.level, env.ctx, env.epochDay)
                if (h == s.p.habits) s to emptyList() else s.copy(p = s.p.copy(habits = h)) to listOf(FlowEffect.Persist)
            }
        }
        FlowEvent.LearnedCleared -> s.copy(p = s.p.copy(habits = emptyList(), dismissed = emptyMap()), pending = null) to listOf(FlowEffect.Persist)
        FlowEvent.Tick -> tick(s, env)
        is FlowEvent.RecircResult -> s.copy(smartNote = e.ok, note = if (e.ok) e.reason else if (e.on) "내기로 바꾸지 못했어요" else "외기로 바꾸지 못했어요") to emptyList()
        is FlowEvent.ApplyResult -> s.copy(note = HvacErrors.summary(e.results), smartNote = false) to emptyList()
        FlowEvent.ApplyNow -> if (!s.loaded || !s.parked) s to emptyList() else apply(s, env, "수동 적용", force = true)
        FlowEvent.ProfileChanged -> if (s.parked) apply(s.copy(appliedThisEpisode = false), env, "프로필 변경") else s to emptyList()
        FlowEvent.SuggestAccepted -> {
            val sg = s.pending
            if (sg == null || !s.parked) s to emptyList()
            else {
                val rule = HabitLearner.toRule(sg)
                val has = s.p.rules.any { it.id == rule.id }
                // 규칙이 가득 차면 수락해도 추가하지 않는다(오래된 규칙을 조용히 지우지 않음)
                if (has || s.p.rules.size >= Edits.MAX_RULES) s.copy(pending = null, lastSuggestAtMs = env.nowMs) to emptyList()
                else s.copy(p = s.p.copy(rules = s.p.rules + rule), pending = null, lastSuggestAtMs = env.nowMs) to listOf(FlowEffect.Persist)
            }
        }
        FlowEvent.SuggestDismissed -> {
            val sg = s.pending
            if (sg == null || !s.parked) s to emptyList()
            else s.copy(p = s.p.copy(dismissed = pruneDismissed(s.p.dismissed + (sg.key.id to env.nowMs + HabitLearner.DISMISS_MS), env.nowMs)), pending = null, lastSuggestAtMs = env.nowMs) to listOf(FlowEffect.Persist)
        }
    }

    private fun pruneDismissed(m: Map<String, Long>, now: Long) = m.filterValues { it > now }.entries.sortedByDescending { it.value }.take(40).associate { it.key to it.value }

    private fun hw(s: FlowState, e: FlowEvent.Hw, env: Env): Pair<FlowState, List<FlowEffect>> {
        val prevRecirc = s.hw.current.recirc
        val newRecirc = e.hw.current.recirc
        val rs = if (prevRecirc != newRecirc && newRecirc != null) RecircPolicy.onObserved(s.recirc, newRecirc, e.recircByUs, env.nowMs, first = prevRecirc == null) else s.recirc
        // 사용자가 내기를 직접 바꿨다면(첫 관측·우리 명령의 에코 제외) 직전 스마트 문구는 더 이상 현재 상태와 맞지 않는다
        val userChanged = prevRecirc != null && newRecirc != null && prevRecirc != newRecirc && !e.recircByUs
        val n = s.copy(hw = e.hw, recirc = rs).let { if (userChanged && it.smartNote) it.copy(note = null, smartNote = false) else it }
        // 연결/capability 가 준비되면 이번 정차 구간의 자동 적용(구간당 1회: appliedThisEpisode 가 막는다)
        return tryAutoApply(n, env)
    }

    private fun tryAutoApply(s: FlowState, env: Env): Pair<FlowState, List<FlowEffect>> =
        if (!s.loaded || !s.parked || s.appliedThisEpisode || !s.p.autoApply || !s.hw.connected || !s.hw.caps.any) s to emptyList() else apply(s, env, "자동 적용")

    private fun apply(s: FlowState, env: Env, reason: String, force: Boolean = false): Pair<FlowState, List<FlowEffect>> {
        if (!s.parked || !s.hw.connected || !s.hw.caps.any) return s to emptyList()
        val base = s.p.selected?.settings ?: ClimateSettings()
        val r = PresetEngine.resolve(base, s.p.rules, env.ctx)
        val fk = FeelKeep.adjust(r.settings.tempC, env.ctx.outsideC, s.p.feelKeep, s.hw.caps.limits)
        val target = s.hw.caps.filter(if (fk.applied) r.settings.copy(tempC = fk.tempC) else r.settings)
        if (target.isEmpty) return s.copy(appliedThisEpisode = true, note = null, smartNote = false) to emptyList()
        val why = buildString {
            append(reason)
            s.p.selected?.let { append(" · ${it.name}") }
            if (r.applied.isNotEmpty()) append(" · 규칙 ${r.applied.joinToString(",") { it.name }}")
            if (fk.applied && target.tempC != null) append(" · 체감 유지 ${FeelKeep.label(fk.delta)}")
        }
        return s.copy(appliedThisEpisode = true, note = why, smartNote = false, fkTemp = if (fk.applied && target.tempC != null) target.tempC else null, fkDelta = if (fk.applied && target.tempC != null) fk.delta else 0f) to listOf(FlowEffect.Apply(target, why))
    }

    private fun tick(s: FlowState, env: Env): Pair<FlowState, List<FlowEffect>> {
        val fx = ArrayList<FlowEffect>()
        var n = s
        if (s.loaded && s.p.smartRecirc && s.hw.connected && s.hw.caps.recirc) {
            val (rs, d) = RecircPolicy.step(s.recirc, s.hw.current.recirc, env.air, env.nowMs)
            n = n.copy(recirc = rs)
            when (d) {
                is RecircDecision.ToRecirc -> { fx += FlowEffect.SetRecirc(true, d.reason) }
                is RecircDecision.ToOutside -> { fx += FlowEffect.SetRecirc(false, d.reason) }
                RecircDecision.None -> Unit
            }
        }
        if (s.loaded && s.parked && s.pending == null && (s.lastSuggestAtMs == null || env.nowMs - s.lastSuggestAtMs >= SUGGEST_GAP_MS)) {
            HabitLearner.candidate(s.p.habits, env.ctx, env.epochDay, s.p.dismissed, s.p.rules, env.nowMs)?.let {
                n = n.copy(pending = it, lastSuggestAtMs = env.nowMs); fx += FlowEffect.Speak(it.text)
            }
        }
        return n to fx
    }
}

/** 차량 연결 판정(순수). 지원 항목(caps) 유무와는 무관하다: 지원 항목이 0개여도 연결은 연결이다. */
object HwConnection {
    fun connected(pendingCaps: Int, syncedGen: Int, ready: Boolean, lost: Boolean): Boolean =
        pendingCaps == 0 && syncedGen != 0 && ready && !lost
}
