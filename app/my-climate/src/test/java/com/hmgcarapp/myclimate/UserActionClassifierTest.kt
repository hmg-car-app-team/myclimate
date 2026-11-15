package com.hmgcarapp.myclimate

import com.hmgcarapp.core.connection.ConnectionPolicy
import com.hmgcarapp.core.connection.ManagedConnection
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

class UserActionClassifierTest {
    @get:Rule val tmp = TemporaryFolder()
    private val hwOn = HwState(true, ClimateCaps(recirc = true, ac = true, seatHeat = true), ClimateSettings(recirc = false))
    private fun env(now: Long = 1_000_000L, day: Long = 100, air: AirReading? = null) = Env(now, Ctx(-3f, 7, null), air, day)

    // 1) 첫 관측/재연결 직후 값은 사용자 조작이 아니다
    @Test fun firstObservationIsNotUserAction() {
        assertNull(UserDetect.turnedOn("seatHeat", null, 2.0, Origin.USER))
        assertNull(UserDetect.turnedOn("ac", null, 1.0, Origin.USER))
        assertEquals(HabitAction.SEAT_HEAT to 2, UserDetect.turnedOn("seatHeat", 0.0, 2.0, Origin.USER))
    }
    @Test fun echoesAndUnchangedAndTurnOffAreNotUserOn() {
        assertNull(UserDetect.turnedOn("seatHeat", 0.0, 2.0, Origin.APPLY_ECHO)); assertNull(UserDetect.turnedOn("seatHeat", 0.0, 2.0, Origin.SMART_ECHO))
        assertNull(UserDetect.turnedOn("ac", 1.0, 1.0, Origin.USER)); assertNull(UserDetect.turnedOn("ac", 1.0, 0.0, Origin.USER)); assertNull(UserDetect.turnedOn("fan", 1.0, 5.0, Origin.USER))
        assertEquals(HabitAction.RECIRC to 1, UserDetect.turnedOn("recirc", 0.0, 1.0, Origin.USER))
    }

    // 2) 적용이 쓴 내기 ON 은 autoOn 이 아니라 보류(hold)
    @Test fun classifierSeparatesApplySmartAndUser() {
        val c = EchoClassifier(); c.expect("recirc", 1.0, 0, fromSmart = false); c.expect("fan", 3.0, 0, fromSmart = true)
        assertEquals(Origin.APPLY_ECHO, c.classify("recirc", 1.0, 1)); assertEquals(Origin.SMART_ECHO, c.classify("fan", 3.0, 1))
        assertEquals(Origin.USER, c.classify("recirc", 1.0, 2)); c.expect("ac", 1.0, 0, false); c.forget("ac"); assertEquals(Origin.USER, c.classify("ac", 1.0, 1))
    }
    @Test fun applyWrittenRecircOnIsHeldNotAuto() {
        val s0 = FlowState(loaded = true, hw = hwOn)
        val (s, _) = ClimateFlow.reduce(s0, FlowEvent.Hw(hwOn.copy(current = ClimateSettings(recirc = true)), recircByUs = false), env())
        assertFalse(s.recirc.autoOn); assertTrue(s.recirc.holdUntil >= 1_000_000L + RecircPolicy.HOLD_MS)
        val good = AirReading(AirGrade.GOOD, 1_000_000L, true)
        val st = s.copy(p = s.p.copy(smartRecirc = true))
        assertTrue(ClimateFlow.reduce(st, FlowEvent.Tick, env(now = 1_000_000L + 60_000L, air = good)).second.none { it is FlowEffect.SetRecirc })
    }

    // 3) 능력 확인 경로는 onAlive 만: 사망 표시를 지우지 않는다
    @Test fun onAliveKeepsDeathMarkButOnResponseClearsIt() {
        val c = ManagedConnection<Any>("t", create = { Any() }, dispose = {}, policy = ConnectionPolicy(), clock = { 0L })
        val gen = c.connect()!!.gen
        assertTrue(c.onFailure(gen, IllegalStateException("service disconnected")))
        c.onAlive(gen); assertTrue(c.isLost())
        c.onResponse(gen); assertFalse(c.isLost())
    }

    // 4) 안내는 효과로 나오지 않고, 거부돼도 재결정 간격 안에서는 반복하지 않는다
    @Test fun smartDecisionNotRepeatedWithinGapEvenIfWriteRejected() {
        val bad = AirReading(AirGrade.BAD, 1_000_000L, true)
        val s0 = FlowState(loaded = true, p = Persisted(smartRecirc = true), hw = hwOn)
        val (s1, f1) = ClimateFlow.reduce(s0, FlowEvent.Tick, env(air = bad))
        assertEquals(1, f1.count { it is FlowEffect.SetRecirc }); assertTrue(f1.none { it is FlowEffect.Speak })
        var s = s1; var count = 0
        for (i in 1..20) { val (n, f) = ClimateFlow.reduce(s, FlowEvent.Tick, env(now = 1_000_000L + i * 10_000L, air = bad.copy(atMs = 1_000_000L + i * 10_000L))); s = n; count += f.count { it is FlowEffect.SetRecirc } }
        assertEquals(0, count) // 200초 < 5분
        assertEquals(1, ClimateFlow.reduce(s, FlowEvent.Tick, env(now = 1_000_000L + RecircPolicy.MIN_GAP_MS, air = bad.copy(atMs = 1_000_000L + RecircPolicy.MIN_GAP_MS))).second.count { it is FlowEffect.SetRecirc })
    }

    // 10) 규칙이 가득 차면 제안 수락은 규칙을 바꾸지 않는다
    @Test fun acceptWithFullRulesLeavesRulesUntouched() {
        val rules = List(Edits.MAX_RULES) { PresetRule("r$it", "r") }
        val sg = Suggestion(HabitKey(HabitAction.SEAT_HEAT, TempBand.COLD, TimeSlot.MORNING), 2)
        val (s, fx) = ClimateFlow.reduce(FlowState(loaded = true, parked = true, pending = sg, p = Persisted(rules = rules)), FlowEvent.SuggestAccepted, env())
        assertEquals(rules, s.p.rules); assertNull(s.pending); assertTrue(fx.isEmpty())
    }

    // 11) 경계·리터럴
    @Test fun windowBoundaryIs14Days() {
        val h = listOf(HabitStat(HabitKey(HabitAction.SEAT_HEAT, TempBand.COLD, TimeSlot.MORNING), listOf(100, 101, 102), 2))
        val c = Ctx(-2f, 7, null)
        assertNotNull(HabitLearner.candidate(h, c, 113, emptyMap(), emptyList(), 0)) // day 100 은 113 에서 아직 창 안(> 113-14)
        assertNull(HabitLearner.candidate(h, c, 114, emptyMap(), emptyList(), 0))    // 114 에서는 100 이 빠져 2일
    }
    @Test fun literalsAreFixed() {
        assertEquals(30L * 24 * 3600_000L, HabitLearner.DISMISS_MS); assertEquals(10 * 60_000L, RecircPolicy.HOLD_MS)
        assertEquals(3, HabitLearner.MIN_DAYS); assertEquals(14, HabitLearner.WINDOW_DAYS)
    }
    @Test fun dismissPrunesExpiredEntries() {
        val sg = Suggestion(HabitKey(HabitAction.AC, TempBand.HOT, TimeSlot.DAY), 1)
        val p = Persisted(dismissed = mapOf("old" to 500L, "alive" to 9_999_999_999L))
        val (s, _) = ClimateFlow.reduce(FlowState(loaded = true, parked = true, pending = sg, p = p), FlowEvent.SuggestDismissed, env(now = 1_000L))
        assertFalse("old" in s.p.dismissed); assertTrue("alive" in s.p.dismissed); assertEquals(1_000L + HabitLearner.DISMISS_MS, s.p.dismissed[sg.key.id])
    }
    @Test fun readErrorHoldsWrites() {
        val dir = tmp.newFolder(); File(dir, "myclimate_state.json").mkdirs() // 파일 자리에 디렉터리 -> 읽기 오류
        val st = StateStore(LocalJsonStore(dir))
        val r = st.restore(0)
        assertTrue(r.readError); assertFalse(st.writable); assertEquals(WriteResult.HELD, st.writeLatest { Persisted() })
        assertEquals(Persisted(), StateCodec.parse(StateCodec.encode(Persisted()).toString()).p)
    }
}
