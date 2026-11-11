package com.hmgcarapp.myclimate.domain

/**
 * 우리가 낸 명령의 결과로 돌아오는 리스너 이벤트(에코)와 사용자의 직접 조작을 구분한다(순수, 시간 주입).
 * 명령을 보낼 때 [expect], 리스너 이벤트가 오면 [consume]: 기대값과 같으면 에코로 보고 소비한다.
 */
class EchoFilter(private val windowMs: Long = 4_000L) {
    private data class Exp(val kind: String, val value: Double, val until: Long)
    private val list = ArrayList<Exp>()

    @Synchronized fun expect(kind: String, value: Double, now: Long) { list.removeAll { it.until < now || it.kind == kind }; list += Exp(kind, value, now + windowMs) }

    /** 쓰기가 실패하면 그 항목의 기대값을 버린다(실패한 명령이 뒤의 실제 사용자 조작을 에코로 삼키지 않게). */
    @Synchronized fun forget(kind: String) { list.removeAll { it.kind == kind } }

    @Synchronized fun consume(kind: String, value: Double, now: Long): Boolean {
        list.removeAll { it.until < now }
        val i = list.indexOfFirst { it.kind == kind && kotlin.math.abs(it.value - value) < 0.26 }
        if (i < 0) return false
        list.removeAt(i); return true
    }
}

enum class Origin { USER, APPLY_ECHO, SMART_ECHO }

/**
 * 관찰된 값의 출처 분류(순수). 프로필/규칙 적용이 쓴 값은 [Origin.APPLY_ECHO], 스마트 내외기 setRecirc 가 쓴 값만 [Origin.SMART_ECHO],
 * 그 밖은 사용자 조작. 내기 순환의 autoOn 표시는 SMART_ECHO 에만 붙고 APPLY_ECHO 는 보류(hold) 취급이다.
 */
class EchoClassifier(windowMs: Long = 4_000L) {
    private val apply = EchoFilter(windowMs)
    private val smart = EchoFilter(windowMs)
    fun expect(kind: String, value: Double, now: Long, fromSmart: Boolean) { (if (fromSmart) smart else apply).expect(kind, value, now) }
    fun forget(kind: String) { apply.forget(kind); smart.forget(kind) }
    fun classify(kind: String, value: Double, now: Long): Origin = when {
        smart.consume(kind, value, now) -> Origin.SMART_ECHO
        apply.consume(kind, value, now) -> Origin.APPLY_ECHO
        else -> Origin.USER
    }
}

/** 관찰 값이 ‘사용자가 켠 조작’인지(순수). 이전 관측이 없으면(첫 값·재연결 직후) 사용자 조작으로 보지 않는다. */
object UserDetect {
    fun turnedOn(kind: String, prev: Double?, value: Double, origin: Origin): Pair<HabitAction, Int>? {
        if (origin != Origin.USER || prev == null || prev == value) return null
        return when (kind) {
            "ac" -> if (value > 0.5) HabitAction.AC to 1 else null
            "recirc" -> if (value > 0.5) HabitAction.RECIRC to 1 else null
            // 단계 항목은 꺼짐(0)에서 켜짐(>=1)으로 바뀔 때만 ‘켜기’ (3->2 같은 하향·단계 변경은 아님)
            "seatHeat" -> if (prev < 0.5 && value >= 1) HabitAction.SEAT_HEAT to value.toInt() else null
            "seatVent" -> if (prev < 0.5 && value >= 1) HabitAction.SEAT_VENT to value.toInt() else null
            "wheelHeat" -> if (prev < 0.5 && value >= 1) HabitAction.WHEEL_HEAT to value.toInt() else null
            else -> null
        }
    }
}
