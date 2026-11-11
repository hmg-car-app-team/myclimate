package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class PresetEngineTest {
    private fun ctx(t: Float? = 10f, h: Int = 12, w: Weather? = null) = Ctx(t, h, w)

    @Test fun tempRangeIsHalfOpen() {
        val r = PresetRule("a", "a", minC = 5f, maxC = 15f)
        assertFalse(PresetEngine.matches(r, ctx(4.99f)))
        assertTrue(PresetEngine.matches(r, ctx(5f)))
        assertTrue(PresetEngine.matches(r, ctx(14.99f)))
        assertFalse(PresetEngine.matches(r, ctx(15f)))
    }
    @Test fun unknownTempNeverMatchesTempRule() {
        assertFalse(PresetEngine.matches(PresetRule("a", "a", minC = 0f), ctx(null)))
        assertFalse(PresetEngine.matches(PresetRule("a", "a", maxC = 0f), ctx(Float.NaN)))
    }
    @Test fun hoursWrapMidnight() {
        val r = PresetRule("a", "a", fromHour = 22, toHour = 5)
        assertTrue(PresetEngine.matches(r, ctx(h = 22))); assertTrue(PresetEngine.matches(r, ctx(h = 0))); assertTrue(PresetEngine.matches(r, ctx(h = 4)))
        assertFalse(PresetEngine.matches(r, ctx(h = 5))); assertFalse(PresetEngine.matches(r, ctx(h = 21)))
    }
    @Test fun hoursSameDayRange() {
        val r = PresetRule("a", "a", fromHour = 9, toHour = 17)
        assertFalse(PresetEngine.matches(r, ctx(h = 8))); assertTrue(PresetEngine.matches(r, ctx(h = 9))); assertTrue(PresetEngine.matches(r, ctx(h = 16))); assertFalse(PresetEngine.matches(r, ctx(h = 17)))
    }
    @Test fun weatherUnknownDoesNotMatch() {
        val r = PresetRule("a", "a", weather = Weather.RAIN)
        assertFalse(PresetEngine.matches(r, ctx(w = null))); assertFalse(PresetEngine.matches(r, ctx(w = Weather.CLEAR))); assertTrue(PresetEngine.matches(r, ctx(w = Weather.RAIN)))
    }
    @Test fun disabledRuleNeverMatches() { assertFalse(PresetEngine.matches(PresetRule("a", "a", enabled = false), ctx())) }
    @Test fun ruleWithoutConditionAlwaysMatches() { assertTrue(PresetEngine.matches(PresetRule("a", "a"), ctx(null))) }

    @Test fun moreSpecificRuleOverridesLessSpecific() {
        val general = PresetRule("g", "g", patch = ClimateSettings(fan = 2, seatHeat = 1))
        val specific = PresetRule("s", "s", maxC = 5f, weather = Weather.SNOW, patch = ClimateSettings(fan = 6))
        val r = PresetEngine.resolve(ClimateSettings(tempC = 22f), listOf(specific, general), ctx(0f, w = Weather.SNOW))
        assertEquals(6, r.settings.fan); assertEquals(1, r.settings.seatHeat); assertEquals(22f, r.settings.tempC)
        assertEquals(listOf("g", "s"), r.applied.map { it.id })
    }
    @Test fun equalSpecificityKeepsListOrder() {
        val a = PresetRule("a", "a", weather = Weather.RAIN, patch = ClimateSettings(fan = 1))
        val b = PresetRule("b", "b", maxC = 100f, patch = ClimateSettings(fan = 2))
        assertEquals(2, PresetEngine.resolve(ClimateSettings(), listOf(a, b), ctx(1f, w = Weather.RAIN)).settings.fan)
    }
    @Test fun noMatchKeepsBase() {
        val base = ClimateSettings(tempC = 21f)
        assertEquals(base, PresetEngine.resolve(base, DefaultRules.all, ctx(20f, 12, Weather.CLEAR)).settings)
    }
    @Test fun defaultHotAndColdRules() {
        assertEquals(true, PresetEngine.resolve(ClimateSettings(), DefaultRules.all, ctx(30f)).settings.ac)
        assertEquals(3, PresetEngine.resolve(ClimateSettings(), DefaultRules.all, ctx(-1f)).settings.seatHeat)
    }
    @Test fun bandsAndSlotsPartitionTheirRanges() {
        listOf(-30f, 0f, 4.99f, 5f, 14.99f, 15f, 23.99f, 24f, 27.99f, 28f, 50f).forEach { TempBand.of(it) }
        assertEquals(TempBand.COOL, TempBand.of(5f)); assertEquals(TempBand.COLD, TempBand.of(4.99f)); assertEquals(TempBand.HOT, TempBand.of(28f))
        (0..23).forEach { h -> val s = TimeSlot.of(h); assertTrue(h.toString(), s in TimeSlot.entries) }
        assertEquals(TimeSlot.NIGHT, TimeSlot.of(22)); assertEquals(TimeSlot.NIGHT, TimeSlot.of(4)); assertEquals(TimeSlot.MORNING, TimeSlot.of(5)); assertEquals(TimeSlot.DAY, TimeSlot.of(11))
        assertEquals(TimeSlot.EVENING, TimeSlot.of(17)); assertEquals(TimeSlot.EVENING, TimeSlot.of(21)); assertEquals(TimeSlot.NIGHT, TimeSlot.of(-1))
    }
    @Test fun overlayKeepsNullsAndTakesPatch() {
        val r = ClimateSettings(tempC = 20f, fan = 3).overlay(ClimateSettings(fan = 5, ac = true))
        assertEquals(ClimateSettings(tempC = 20f, fan = 5, ac = true), r)
    }
}
