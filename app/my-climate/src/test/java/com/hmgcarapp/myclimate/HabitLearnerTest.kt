package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class HabitLearnerTest {
    private val cold = Ctx(-2f, 7, null)
    private fun rec(h: List<HabitStat>, day: Long, a: HabitAction = HabitAction.SEAT_HEAT, lvl: Int = 2, c: Ctx = cold) = HabitLearner.record(h, a, lvl, c, day)

    @Test fun sameDayRepeatsCountOnce() {
        var h = emptyList<HabitStat>(); repeat(5) { h = rec(h, 100) }
        assertEquals(listOf(100L), h.single().days)
    }
    @Test fun unknownOutsideTempIsNotRecorded() { assertTrue(rec(emptyList(), 1, c = Ctx(null, 7, null)).isEmpty()) }
    @Test fun suggestsOnlyAfterThreeDistinctDays() {
        var h = rec(rec(emptyList(), 100), 101)
        assertNull(HabitLearner.candidate(h, cold, 101, emptyMap(), emptyList(), 0))
        h = rec(h, 102)
        val s = HabitLearner.candidate(h, cold, 102, emptyMap(), emptyList(), 0)!!
        assertEquals(HabitAction.SEAT_HEAT, s.key.action); assertEquals(TempBand.COLD, s.key.band); assertEquals(TimeSlot.MORNING, s.key.slot); assertEquals(2, s.level)
    }
    @Test fun noSuggestionInDifferentSituation() {
        val h = rec(rec(rec(emptyList(), 1), 2), 3)
        assertNull(HabitLearner.candidate(h, Ctx(20f, 7, null), 3, emptyMap(), emptyList(), 0))
        assertNull(HabitLearner.candidate(h, Ctx(-2f, 15, null), 3, emptyMap(), emptyList(), 0))
        assertNull(HabitLearner.candidate(h, Ctx(null, 7, null), 3, emptyMap(), emptyList(), 0))
    }
    @Test fun oldDaysFallOutOfWindow() {
        val h = rec(rec(rec(emptyList(), 1), 2), 3)
        assertNull(HabitLearner.candidate(h, cold, 3L + HabitLearner.WINDOW_DAYS, emptyMap(), emptyList(), 0))
        assertNotNull(HabitLearner.candidate(h, cold, 3L + HabitLearner.WINDOW_DAYS - 12, emptyMap(), emptyList(), 0))
    }
    @Test fun recordPrunesOldDays() {
        val h = rec(rec(emptyList(), 1), 1L + HabitLearner.WINDOW_DAYS)
        assertEquals(listOf(1L + HabitLearner.WINDOW_DAYS), h.single().days)
    }
    @Test fun dismissedUntilExpiryAndExistingRuleBlock() {
        val h = rec(rec(rec(emptyList(), 1), 2), 3)
        val key = HabitKey(HabitAction.SEAT_HEAT, TempBand.COLD, TimeSlot.MORNING)
        assertNull(HabitLearner.candidate(h, cold, 3, mapOf(key.id to 1000L), emptyList(), 999))
        assertNotNull(HabitLearner.candidate(h, cold, 3, mapOf(key.id to 1000L), emptyList(), 1000))
        assertNull(HabitLearner.candidate(h, cold, 3, emptyMap(), listOf(HabitLearner.toRule(Suggestion(key, 2))), 0))
    }
    @Test fun statsAreCapped() {
        var h = emptyList<HabitStat>()
        var day = 1L
        for (b in TempBand.entries) for (s in TimeSlot.entries) for (a in HabitAction.entries) { h = HabitLearner.record(h, a, 1, Ctx(b.minC ?: -10f, s.from, null), day++) }
        assertTrue(h.size <= HabitLearner.MAX_STATS)
    }
    @Test fun acceptedSuggestionBecomesMatchingRule() {
        val r = HabitLearner.toRule(Suggestion(HabitKey(HabitAction.SEAT_HEAT, TempBand.COLD, TimeSlot.MORNING), 2))
        assertEquals(RuleSource.LEARNED, r.source); assertEquals(2, r.patch.seatHeat)
        assertTrue(PresetEngine.matches(r, Ctx(-5f, 6, null))); assertFalse(PresetEngine.matches(r, Ctx(5f, 6, null))); assertFalse(PresetEngine.matches(r, Ctx(-5f, 12, null)))
    }
    @Test fun suggestionTextMentionsLevelOnlyForLeveledActions() {
        assertTrue(Suggestion(HabitKey(HabitAction.SEAT_HEAT, TempBand.COLD, TimeSlot.MORNING), 2).text.contains("2단계"))
        assertFalse(Suggestion(HabitKey(HabitAction.AC, TempBand.HOT, TimeSlot.DAY), 1).text.contains("단계"))
    }
}
