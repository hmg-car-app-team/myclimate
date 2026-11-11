package com.hmgcarapp.myclimate.domain

/** 조작 기록 -> 습관 학습 -> 제안(순수, 날짜 주입). */
object HabitLearner {
    const val MIN_DAYS = 3
    const val WINDOW_DAYS = 14
    const val MAX_STATS = 40
    const val DISMISS_MS = 30L * 24 * 3600_000L

    fun keyOf(action: HabitAction, c: Ctx): HabitKey? = c.outsideC?.takeIf { it.isFinite() }?.let { HabitKey(action, TempBand.of(it), TimeSlot.of(c.hour)) }

    /** ‘켜기’ 조작 한 건 기록. 외기온도를 모르면 상황을 분류할 수 없어 기록하지 않는다. 같은 날 반복은 1일로 센다. */
    fun record(habits: List<HabitStat>, action: HabitAction, level: Int, c: Ctx, epochDay: Long): List<HabitStat> {
        val key = keyOf(action, c) ?: return habits
        val old = habits.firstOrNull { it.key == key }
        val days = ((old?.days.orEmpty() + epochDay).distinct().sorted()).filter { it > epochDay - WINDOW_DAYS }
        val updated = HabitStat(key, days, level)
        val rest = habits.filter { it.key != key }
        return (rest + updated).sortedByDescending { it.days.lastOrNull() ?: Long.MIN_VALUE }.take(MAX_STATS)
    }

    private fun distinctRecent(s: HabitStat, epochDay: Long) = s.days.count { it > epochDay - WINDOW_DAYS && it <= epochDay }

    /** 지금 상황과 같은 상황에서 [MIN_DAYS]일 이상 반복된 습관 중 아직 제안/규칙화되지 않은 첫 번째. */
    fun candidate(habits: List<HabitStat>, c: Ctx, epochDay: Long, dismissed: Map<String, Long>, rules: List<PresetRule>, nowMs: Long): Suggestion? {
        if (rules.size >= Edits.MAX_RULES) return null // 규칙이 가득 차면 제안 자체를 만들지 않는다
        val now = HabitKey(HabitAction.AC, TempBand.of(c.outsideC?.takeIf { it.isFinite() } ?: return null), TimeSlot.of(c.hour))
        return habits.filter { it.key.band == now.band && it.key.slot == now.slot && it.level > 0 && distinctRecent(it, epochDay) >= MIN_DAYS }
            .sortedByDescending { distinctRecent(it, epochDay) }
            .firstOrNull { s -> (dismissed[s.key.id] ?: 0L) <= nowMs && rules.none { it.id == ruleId(s.key) } }
            ?.let { Suggestion(it.key, it.level) }
    }

    fun ruleId(k: HabitKey) = "learned:${k.id}"

    /** 수락한 제안을 규칙으로 만든다(온도 구간 + 시간대 조건, 해당 조작만 패치). */
    fun toRule(s: Suggestion): PresetRule {
        val k = s.key
        val patch = when (k.action) {
            HabitAction.SEAT_HEAT -> ClimateSettings(seatHeat = s.level)
            HabitAction.SEAT_VENT -> ClimateSettings(seatVent = s.level)
            HabitAction.WHEEL_HEAT -> ClimateSettings(wheelHeat = s.level)
            HabitAction.AC -> ClimateSettings(ac = true)
            HabitAction.RECIRC -> ClimateSettings(recirc = true)
        }
        return PresetRule(
            id = ruleId(k), name = "${k.band.short} ${k.slot.label} ${k.action.short}".take(Edits.MAX_NAME), // 12자 이내: 저장 복원(cleanName) 뒤에도 같은 이름
            minC = k.band.minC, maxC = k.band.maxC, fromHour = k.slot.from, toHour = k.slot.to, patch = patch, source = RuleSource.LEARNED,
        )
    }
}
