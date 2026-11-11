package com.hmgcarapp.myclimate.domain

/** 상황별 프리셋 규칙 평가(순수). */
object PresetEngine {
    class Resolved(val settings: ClimateSettings, val applied: List<PresetRule>)

    fun matches(r: PresetRule, c: Ctx): Boolean {
        if (!r.enabled) return false
        if (r.minC != null || r.maxC != null) {
            val t = c.outsideC?.takeIf { it.isFinite() } ?: return false
            if (r.minC != null && t < r.minC) return false
            if (r.maxC != null && t >= r.maxC) return false
        }
        if (r.fromHour != null && r.toHour != null) {
            val h = ((c.hour % 24) + 24) % 24
            val inside = if (r.fromHour <= r.toHour) h >= r.fromHour && h < r.toHour else h >= r.fromHour || h < r.toHour
            if (!inside) return false
        }
        if (r.weather != null && r.weather != c.weather) return false
        return true
    }

    /** 조건 종류 수(온도·시간대·날씨 각 1). 더 구체적인 규칙이 덜 구체적인 규칙을 덮어쓴다. */
    fun specificity(r: PresetRule): Int =
        (if (r.minC != null || r.maxC != null) 1 else 0) + (if (r.fromHour != null && r.toHour != null) 1 else 0) + (if (r.weather != null) 1 else 0)

    /** [base](프로필 값) 위에 맞는 규칙 패치를 구체성 오름차순(동률은 목록 순)으로 덮어쓴다. */
    fun resolve(base: ClimateSettings, rules: List<PresetRule>, c: Ctx): Resolved {
        val hit = rules.filter { matches(it, c) }.sortedBy { specificity(it) } // sortedBy 는 안정 정렬
        return Resolved(hit.fold(base) { acc, r -> acc.overlay(r.patch) }, hit)
    }
}
