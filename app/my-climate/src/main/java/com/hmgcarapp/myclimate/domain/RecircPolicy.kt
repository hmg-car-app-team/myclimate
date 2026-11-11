package com.hmgcarapp.myclimate.domain

/** [sample]=실측 아님(시연 값 또는 기본 샘플). [placeholder]=시연도 실측도 아닌 기본 샘플로, 자동 전환의 근거가 되지 않는다. */
data class AirReading(val grade: AirGrade, val atMs: Long, val sample: Boolean, val placeholder: Boolean = false)

data class RecircState(
    /** 내기 순환이 켜진 것을 처음 본 시각(꺼짐=null). */
    val onSince: Long? = null,
    /** 이번 내기 켜짐이 앱의 자동 전환 때문인지. */
    val autoOn: Boolean = false,
    /** 사용자가 직접 바꾼 뒤 자동 전환을 보류하는 시각. */
    val holdUntil: Long = 0L,
    val cooldownUntil: Long = 0L,
    val lastDecisionAt: Long = Long.MIN_VALUE / 2,
)

/** 스마트 내외기 상태 표시(순수). 미연결·미지원이면 상태 불명 ‘-’. */
object RecircView {
    fun label(smartOn: Boolean, connected: Boolean, supported: Boolean, recirc: Boolean?): String {
        if (!smartOn) return "꺼짐"
        val st = if (!connected || !supported || recirc == null) "-" else if (recirc) "내기" else "외기"
        return "켜짐 · $st"
    }
}

sealed interface RecircDecision {
    data object None : RecircDecision
    data class ToRecirc(val reason: String) : RecircDecision
    data class ToOutside(val reason: String) : RecircDecision
}

/** 스마트 내외기 전환 정책(순수). 시간은 모두 주입된 같은 시계(ms). */
object RecircPolicy {
    const val MAX_MS = 10 * 60_000L
    const val MAX_BAD_AIR_MS = 20 * 60_000L
    const val HOLD_MS = 10 * 60_000L
    const val COOLDOWN_MS = 5 * 60_000L
    const val AIR_FRESH_MS = 30 * 60_000L
    /** 결정 직후 재결정 최소 간격(쓰기가 거부돼도 안내·명령이 반복되지 않게 COOLDOWN 수준). */
    const val MIN_GAP_MS = 5 * 60_000L

    /** 차량에서 내기 상태 변화를 관찰했을 때(변화가 있을 때만 호출). [byUs]=우리가 내린 명령의 결과. */
    fun onObserved(s: RecircState, on: Boolean?, byUs: Boolean, now: Long, first: Boolean = false): RecircState = when {
        // 첫 관측(이전 관측 없음·재연결 직후)은 사용자 변경이 아니므로 보류(hold)를 걸지 않는다
        first && on == true -> s.copy(onSince = now, autoOn = false) // 재연결 전의 옛 onSince/autoOn 은 믿지 않는다
        first && on == false -> s.copy(onSince = null, autoOn = false)
        else -> onObservedChange(s, on, byUs, now)
    }

    private fun onObservedChange(s: RecircState, on: Boolean?, byUs: Boolean, now: Long): RecircState = when (on) {
        null -> s
        true -> if (s.onSince != null) s else s.copy(onSince = now, autoOn = byUs, holdUntil = if (byUs) s.holdUntil else now + HOLD_MS)
        false -> s.copy(onSince = null, autoOn = false, holdUntil = if (byUs) s.holdUntil else now + HOLD_MS)
    }

    fun airFresh(a: AirReading?, now: Long) = a != null && !a.placeholder && now >= a.atMs && now - a.atMs <= AIR_FRESH_MS

    fun step(s: RecircState, recircOn: Boolean?, air: AirReading?, now: Long): Pair<RecircState, RecircDecision> {
        if (recircOn == null) return s to RecircDecision.None
        if (now - s.lastDecisionAt < MIN_GAP_MS && now >= s.lastDecisionAt) return s to RecircDecision.None
        val fresh = airFresh(air, now)
        val bad = fresh && air!!.grade.isBad
        if (recircOn) {
            val since = s.onSince ?: now
            val limit = if (bad) MAX_BAD_AIR_MS else MAX_MS
            if (now - since >= limit)
                return s.copy(cooldownUntil = now + COOLDOWN_MS, lastDecisionAt = now) to RecircDecision.ToOutside("내기 순환이 오래돼 외기로 바꿀게요")
            if (s.autoOn && fresh && !bad && now >= s.holdUntil)
                return s.copy(lastDecisionAt = now) to RecircDecision.ToOutside("바깥 공기가 나아져 외기로 바꿀게요")
        } else if (bad && now >= s.holdUntil && now >= s.cooldownUntil) {
            return s.copy(lastDecisionAt = now) to RecircDecision.ToRecirc("바깥 공기가 나빠 내기 순환으로 바꿀게요")
        }
        return s to RecircDecision.None
    }
}
