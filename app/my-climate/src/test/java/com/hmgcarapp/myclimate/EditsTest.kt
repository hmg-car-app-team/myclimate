package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class EditsTest {
    @Test fun firstProfileIsSelectedAndNamedDefault() {
        val p = Edits.addProfile(Persisted(), "  ", ClimateSettings(tempC = 22f))!!
        assertEquals(p.profiles.single().id, p.selectedId); assertEquals("프로필 1", p.profiles.single().name)
    }
    @Test fun profileLimit() {
        var p = Persisted(); repeat(Edits.MAX_PROFILES) { p = Edits.addProfile(p, "a$it", ClimateSettings())!! }
        assertNull(Edits.addProfile(p, "x", ClimateSettings())); assertEquals(Edits.MAX_PROFILES, p.profiles.map { it.id }.toSet().size)
    }
    @Test fun idsStayUniqueAfterDelete() {
        var p = Persisted(); repeat(3) { p = Edits.addProfile(p, "a", ClimateSettings())!! }
        p = Edits.deleteProfile(p, "p1"); p = Edits.addProfile(p, "b", ClimateSettings())!!
        assertEquals(p.profiles.size, p.profiles.map { it.id }.toSet().size)
    }
    @Test fun deletingSelectedMovesSelection() {
        var p = Persisted(); p = Edits.addProfile(p, "a", ClimateSettings())!!; p = Edits.addProfile(p, "b", ClimateSettings())!!
        p = Edits.deleteProfile(p, p.selectedId!!)
        assertEquals(p.profiles.first().id, p.selectedId); assertNull(Edits.deleteProfile(p, p.selectedId!!).selectedId)
    }
    @Test fun selectIgnoresUnknownId() { val p = Edits.addProfile(Persisted(), "a", ClimateSettings())!!; assertEquals(p.selectedId, Edits.select(p, "zzz").selectedId) }
    @Test fun nameIsTrimmedCollapsedAndCapped() { assertEquals("a b", Edits.cleanName("  a   b ", "x")); assertEquals(Edits.MAX_NAME, Edits.cleanName("가".repeat(40), "x").length); assertEquals("x", Edits.cleanName("", "x")) }
    @Test fun updateProfileClampsValues() {
        var p = Edits.addProfile(Persisted(), "a", ClimateSettings())!!
        p = Edits.updateProfile(p, "p1") { it.copy(settings = ClimateSettings(tempC = 99f, fan = 99, seatHeat = -4, wheelHeat = 9)) }
        val s = p.profiles.single().settings
        assertEquals(32f, s.tempC); assertEquals(8, s.fan); assertEquals(0, s.seatHeat); assertEquals(3, s.wheelHeat)
    }
    @Test fun clampRoundsTempToHalfDegreeAndDropsNaN() {
        assertEquals(21.5f, Limits.DEFAULT.clamp(ClimateSettings(tempC = 21.6f)).tempC); assertNull(Limits.DEFAULT.clamp(ClimateSettings(tempC = Float.NaN)).tempC)
    }
    @Test fun ruleSanitizeFixesInvalidConditions() {
        val r = Edits.sanitizeRule(PresetRule("a", "", minC = 10f, maxC = 5f, fromHour = 7, toHour = 7))
        assertNull(r.minC); assertNull(r.maxC); assertNull(r.fromHour); assertNull(r.toHour); assertEquals("규칙", r.name)
        assertNull(Edits.sanitizeRule(PresetRule("a", "a", fromHour = 0, toHour = 24)).fromHour)
        assertEquals(0, Edits.sanitizeRule(PresetRule("a", "a", fromHour = 22, toHour = 24)).toHour)
        assertNull(Edits.sanitizeRule(PresetRule("a", "a", fromHour = 25, toHour = 3)).fromHour)
    }
    @Test fun ruleLimitAndReplace() {
        var p = Persisted(rules = emptyList())
        repeat(Edits.MAX_RULES) { p = Edits.upsertRule(p, PresetRule("r$it", "r"))!! }
        assertNull(Edits.upsertRule(p, PresetRule("new", "n")))
        assertNotNull(Edits.upsertRule(p, PresetRule("r0", "renamed")))
        assertEquals("renamed", Edits.upsertRule(p, PresetRule("r0", "renamed"))!!.rules.first { it.id == "r0" }.name)
    }
    @Test fun toggleAndDeleteRule() {
        var p = Persisted(rules = listOf(PresetRule("a", "a")))
        p = Edits.toggleRule(p, "a"); assertFalse(p.rules.single().enabled)
        assertTrue(Edits.deleteRule(p, "a").rules.isEmpty())
    }
    @Test fun capsFilterDropsUnwritableAndClamps() {
        val caps = ClimateCaps(ac = true, seatHeat = true, limits = Limits(seatHeatMax = 2))
        val f = caps.filter(ClimateSettings(ac = true, recirc = true, seatHeat = 3, tempC = 22f))
        assertEquals(ClimateSettings(ac = true, seatHeat = 2), f)
        assertTrue(ClimateCaps().filter(ClimateSettings(ac = true)).isEmpty)
    }
}
