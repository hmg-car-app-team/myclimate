package com.hmgcarapp.myclimate.domain

/**
 * 체감 온도 유지(외기온 기반 보정, 순수). 일사량·실제 체감 센서는 쓰지 않는다: 외기온이 극단일 때만 목표 온도를 ±1~2℃ 보정한다.
 * 외기온이 없거나 유효 범위 밖이면 보정하지 않는다(fail-safe). 보정 후 값은 차량 허용 범위로 맞춘다.
 */
object FeelKeep {
    const val HOT_NOTE = "30℃ 이상이거나 0℃ 이하"
    /** 목표 온도를 [delta]℃ 바꿔 [tempC] 가 됐다. delta 는 범위 보정 후 실제 변화량(0 이면 보정 없음). */
    data class Adjusted(val tempC: Float?, val delta: Float) { val applied: Boolean get() = delta != 0f }

    /** 외기온 -> 보정량: 매우 더움(35 이상) -2, 더움(30 이상) -1, 추움(0 이하) +1, 매우 추움(-10 이하) +2. 값 없음/범위 밖(-60..70 밖)은 0. */
    fun offset(outsideC: Float?): Float {
        val t = outsideC?.takeIf { it.isFinite() && it in -60f..70f } ?: return 0f
        return when {
            t >= 35f -> -2f
            t >= 30f -> -1f
            t <= -10f -> 2f
            t <= 0f -> 1f
            else -> 0f
        }
    }

    fun adjust(tempC: Float?, outsideC: Float?, enabled: Boolean, limits: Limits = Limits.DEFAULT): Adjusted {
        if (tempC == null || !tempC.isFinite() || !enabled) return Adjusted(tempC, 0f)
        val off = offset(outsideC)
        if (off == 0f) return Adjusted(tempC, 0f)
        val out = limits.clamp(ClimateSettings(tempC = tempC + off)).tempC ?: return Adjusted(tempC, 0f)
        return Adjusted(out, out - tempC)
    }

    /** 현재 차량 온도가 마지막 보정 적용값 그대로면 보정량만큼 되돌린 기준 온도, 아니면(사용자가 바꿨거나 보정 없음) 현재 값 그대로. */
    fun undo(current: Float?, appliedTemp: Float?, delta: Float): Float? =
        if (current != null && appliedTemp != null && delta != 0f && current == appliedTemp) current - delta else current

    fun label(delta: Float): String = String.format(java.util.Locale.KOREA, "%+.1f", delta).removeSuffix(".0") + "℃"
}

/** 시연용 날씨/대기질 선택지(0번 = 실제/기본 값). 메모리 전용이며 저장하지 않는다. */
object DemoEnv {
    val WEATHER: List<Weather?> = listOf(null, Weather.CLEAR, Weather.RAIN, Weather.SNOW)
    val AIR: List<AirGrade?> = listOf(null, AirGrade.GOOD, AirGrade.BAD, AirGrade.VERY_BAD)
    val WEATHER_LABELS: List<String> = WEATHER.map { it?.label ?: "실제" }
    val AIR_LABELS: List<String> = AIR.map { it?.label ?: "실제" }
    fun weatherAt(i: Int): Weather? = WEATHER.getOrNull(i)
    fun airAt(i: Int): AirGrade? = AIR.getOrNull(i)
}
