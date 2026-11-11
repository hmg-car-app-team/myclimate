package com.hmgcarapp.myclimate

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.myclimate.app.StateCodec
import com.hmgcarapp.myclimate.app.StateStore
import com.hmgcarapp.myclimate.app.WriteResult
import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StateCodecAndStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = Persisted(
        profiles = listOf(Profile("p1", "나", ClimateSettings(tempC = 22.5f, fan = 3, fanDir = FanDir.FLOOR, ac = true, recirc = false, seatHeat = 2, seatVent = 0, wheelHeat = 1))),
        selectedId = "p1", autoApply = false, smartRecirc = true,
        rules = listOf(PresetRule("u1", "추운 아침", minC = null, maxC = 5f, fromHour = 5, toHour = 11, weather = Weather.SNOW, patch = ClimateSettings(seatHeat = 3), source = RuleSource.LEARNED)),
        habits = listOf(HabitStat(HabitKey(HabitAction.SEAT_HEAT, TempBand.COLD, TimeSlot.MORNING), listOf(1, 2, 3), 2)),
        dismissed = mapOf("AC:HOT:DAY" to 12345L),
    )

    @Test fun roundTrip() { assertEquals(sample, StateCodec.parse(StateCodec.encode(sample).toString()).p) }
    @Test fun encodedFileHasNoLocationOrRawTimeline() {
        val t = StateCodec.encode(sample).toString().lowercase()
        listOf("lat", "lon", "location", "stt", "transcript").forEach { assertFalse(it, t.contains(it)) }
    }
    @Test fun garbageIsCorruptWithDefaults() {
        val r = StateCodec.parse("not json {"); assertTrue(r.corrupt); assertEquals(Persisted(), r.p)
    }
    @Test fun missingRulesFallBackToDefaultsButEmptyRulesStayEmpty() {
        assertEquals(DefaultRules.all, StateCodec.parse("{}").p.rules)
        assertTrue(StateCodec.parse("""{"rules":[]}""").p.rules.isEmpty())
    }
    @Test fun badValuesAreSanitized() {
        val json = """{"profiles":[{"id":"../x","name":"a"},{"id":"p2","name":"","s":{"tempC":99,"fan":-5,"seatHeat":77,"fanDir":"NOPE"}},{"id":"p2","name":"dup"}],
            "selectedId":"ghost","rules":[{"id":"r","minC":10,"maxC":5,"from":3,"to":3,"patch":{"fan":2}},{"x":1}],"habits":[{"a":"BOGUS"},{"a":"AC","b":"HOT","t":"DAY","d":[5,-1,5,3],"l":99}]}"""
        val p = StateCodec.parse(json).p
        assertEquals(listOf("p2"), p.profiles.map { it.id }); assertEquals("프로필", p.profiles.single().name)
        val s = p.profiles.single().settings; assertEquals(32f, s.tempC); assertEquals(1, s.fan); assertEquals(3, s.seatHeat); assertNull(s.fanDir)
        assertEquals("p2", p.selectedId)
        val r = p.rules.single(); assertNull(r.minC); assertNull(r.fromHour)
        assertEquals(listOf(3L, 5L), p.habits.single().days); assertEquals(8, p.habits.single().level)
    }
    @Test fun sizeCapsApply() {
        val many = (1..50).joinToString(",") { """{"id":"p$it","name":"n"}""" }
        assertEquals(Edits.MAX_PROFILES, StateCodec.parse("""{"profiles":[$many]}""").p.profiles.size)
    }

    private fun store(): Pair<StateStore, LocalJsonStore> { val ls = LocalJsonStore(tmp.newFolder()); return StateStore(ls) to ls }
    @Test fun missingFileRestoresDefaults() { val r = store().first.restore(0); assertFalse(r.corrupt); assertFalse(r.readError); assertEquals(Persisted(), r.p) }
    @Test fun writeThenRestore() {
        val (st, ls) = store(); assertEquals(WriteResult.WROTE, st.writeLatest { sample }); assertEquals(WriteResult.UNCHANGED, st.writeLatest { sample })
        assertEquals(sample, StateStore(ls).restore(0).p)
    }
    @Test fun corruptFileIsQuarantinedAndNextWriteDeletesIt() {
        val (st, ls) = store(); ls.saveText("myclimate_state", "###")
        val r = st.restore(0); assertTrue(r.corrupt); assertTrue(r.quarantined)
        val dir = tmp.root.walkTopDown().first { it.isDirectory && it != tmp.root }
        assertTrue(File(dir, "myclimate_state.json.corrupt").exists()) // 손상본 보존
        st.writeLatest { sample }
        assertFalse(File(dir, "myclimate_state.json.corrupt").exists()) // 다음 정상 저장이 지움
        assertTrue(ls.loadText("myclimate_state") != null)
    }
    @Test fun clearAllRewritesLatest() {
        val (st, ls) = store(); st.writeLatest { sample }
        assertTrue(st.clearAll { Persisted() }); assertEquals(Persisted(), StateStore(ls).restore(0).p)
    }
    @Test fun holdWritesBlocksWrites() {
        val (st, _) = store(); st.holdWrites(); assertEquals(WriteResult.HELD, st.writeLatest { sample })
    }
}
