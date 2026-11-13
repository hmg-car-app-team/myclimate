package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.app.StateCodec
import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class FeelKeepDemoTest {
    @Test fun offsetBoundaries() {
        assertEquals(-2f, FeelKeep.offset(35f)); assertEquals(-1f, FeelKeep.offset(34.9f)); assertEquals(-1f, FeelKeep.offset(30f))
        assertEquals(0f, FeelKeep.offset(29.9f)); assertEquals(0f, FeelKeep.offset(0.1f)); assertEquals(1f, FeelKeep.offset(0f))
        assertEquals(1f, FeelKeep.offset(-9.9f)); assertEquals(2f, FeelKeep.offset(-10f))
    }
    @Test fun missingOrOutOfRangeOutsideMeansNoAdjust() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 80f, -70f).forEach { assertEquals("$it", 0f, FeelKeep.offset(it)) }
        assertFalse(FeelKeep.adjust(24f, null, true).applied)
    }
    @Test fun disabledOrNoTempMeansNoAdjust() {
        assertEquals(24f, FeelKeep.adjust(24f, 36f, false).tempC); assertFalse(FeelKeep.adjust(24f, 36f, false).applied)
        assertNull(FeelKeep.adjust(null, 36f, true).tempC); assertFalse(FeelKeep.adjust(null, 36f, true).applied)
    }
    @Test fun adjustsAndClamps() {
        assertEquals(22f, FeelKeep.adjust(24f, 36f, true).tempC); assertEquals(-2f, FeelKeep.adjust(24f, 36f, true).delta)
        assertEquals(25f, FeelKeep.adjust(24f, -3f, true).tempC)
        assertEquals(16f, FeelKeep.adjust(17f, 36f, true).tempC); assertEquals(-1f, FeelKeep.adjust(17f, 36f, true).delta)
        assertEquals(32f, FeelKeep.adjust(31f, -20f, true).tempC)
        assertFalse(FeelKeep.adjust(32f, -20f, true).applied)
        assertEquals(24f, FeelKeep.adjust(26f, 36f, true, Limits(tempMin = 24f)).tempC)
    }
    @Test fun flowAppliesAdjustedTempOnlyWhenOn() {
        fun run(on: Boolean, out: Float?): ClimateSettings? {
            val s = FlowState(
                p = Persisted(profiles = listOf(Profile("p1", "나", ClimateSettings(tempC = 24f))), selectedId = "p1", feelKeep = on, rules = emptyList()),
                loaded = true, parked = true, hw = HwState(true, ClimateCaps(temp = true)),
            )
            val (n, fx) = ClimateFlow.reduce(s, FlowEvent.ApplyNow, Env(0, Ctx(out, 12, null), null, 0))
            val a = fx.filterIsInstance<FlowEffect.Apply>().firstOrNull()
            if (a != null && on && out != null) assertTrue(n.note!!.contains("체감 유지"))
            return a?.settings
        }
        assertEquals(22f, run(true, 36f)?.tempC); assertEquals(24f, run(false, 36f)?.tempC); assertEquals(24f, run(true, null)?.tempC)
    }
    @Test fun feelKeepPersistsAndDefaultsOff() {
        assertFalse(Persisted().feelKeep); assertFalse(StateCodec.parse("{}").p.feelKeep)
        assertTrue(StateCodec.parse(StateCodec.encode(Persisted(feelKeep = true)).toString()).p.feelKeep)
    }
    @Test fun demoOptionsMapAndDefaultIsReal() {
        assertNull(DemoEnv.weatherAt(0)); assertEquals(Weather.RAIN, DemoEnv.weatherAt(2)); assertEquals(Weather.SNOW, DemoEnv.weatherAt(3))
        assertNull(DemoEnv.airAt(0)); assertEquals(AirGrade.BAD, DemoEnv.airAt(2)); assertEquals(AirGrade.VERY_BAD, DemoEnv.airAt(3))
        assertNull(DemoEnv.weatherAt(9)); assertNull(DemoEnv.airAt(-1))
        assertEquals("실제", DemoEnv.WEATHER_LABELS[0])
    }
    @Test fun demoRainDrivesRuleAndBadAirDrivesRecirc() {
        val c = Ctx(20f, 12, DemoEnv.weatherAt(2))
        assertTrue(PresetEngine.resolve(ClimateSettings(), DefaultRules.all, c).applied.any { it.id == "def_rain" })
        assertTrue(DemoEnv.airAt(2)!!.isBad)
    }
}
