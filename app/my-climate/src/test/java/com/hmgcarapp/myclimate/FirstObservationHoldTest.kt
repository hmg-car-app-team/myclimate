package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class FirstObservationHoldTest {
    private val T0 = 1_000_000L
    private fun env(now: Long = T0, air: AirReading? = null) = Env(now, Ctx(-3f, 7, null), air, 100)
    private val caps = ClimateCaps(recirc = true)
    private fun hw(r: Boolean?) = HwState(true, caps, ClimateSettings(recirc = r))
    private val bad = AirReading(AirGrade.BAD, T0, true)

    // 1) 첫 관측은 보류 없음
    @Test fun firstObservationOffSetsNoHold() {
        val s = RecircPolicy.onObserved(RecircState(), false, byUs = false, now = T0, first = true)
        assertEquals(0L, s.holdUntil)
        assertTrue(RecircPolicy.step(s, false, bad, T0).second is RecircDecision.ToRecirc)
    }
    @Test fun firstObservationOnSetsSinceButNoHoldNorAuto() {
        val s = RecircPolicy.onObserved(RecircState(), true, byUs = false, now = T0, first = true)
        assertEquals(T0, s.onSince); assertEquals(0L, s.holdUntil); assertFalse(s.autoOn)
    }
    @Test fun realUserChangeStillHoldsTenMinutes() {
        val s = RecircPolicy.onObserved(RecircState(), false, byUs = false, now = T0, first = false)
        assertEquals(T0 + RecircPolicy.HOLD_MS, s.holdUntil)
        assertEquals(RecircDecision.None, RecircPolicy.step(s, false, bad, T0 + RecircPolicy.HOLD_MS - 1).second)
    }
    @Test fun flowTreatsNullPreviousAsFirstAndKnownPreviousAsChange() {
        val first = ClimateFlow.reduce(FlowState(loaded = true, hw = HwState(true, caps, ClimateSettings())), FlowEvent.Hw(hw(false)), env()).first
        assertEquals(0L, first.recirc.holdUntil)
        val changed = ClimateFlow.reduce(FlowState(loaded = true, hw = hw(true)), FlowEvent.Hw(hw(false)), env()).first
        assertTrue(changed.recirc.holdUntil > 0)
    }
    @Test fun smartSwitchAfterReconnectWithBadAirIsNotHeld() {
        var s = FlowState(loaded = true, p = Persisted(smartRecirc = true), hw = hw(false))
        s = ClimateFlow.reduce(s, FlowEvent.Hw(HwState(false)), env()).first // 재연결: 값 초기화
        s = ClimateFlow.reduce(s, FlowEvent.Hw(hw(false)), env()).first
        assertTrue(ClimateFlow.reduce(s, FlowEvent.Tick, env(air = bad)).second.any { it is FlowEffect.SetRecirc && it.on })
    }

    // 2) 쓰기 실패 시 note 정정
    @Test fun tickDoesNotWriteNoteAndFailureCorrectsIt() {
        val s0 = FlowState(loaded = true, p = Persisted(smartRecirc = true), hw = hw(false))
        val (s1, fx) = ClimateFlow.reduce(s0, FlowEvent.Tick, env(air = bad))
        assertTrue(fx.any { it is FlowEffect.SetRecirc }); assertNull(s1.note)
        assertEquals("내기로 바꾸지 못했어요", ClimateFlow.reduce(s1, FlowEvent.RecircResult(false, "x", true), env()).first.note)
        assertEquals("외기로 바꾸지 못했어요", ClimateFlow.reduce(s1, FlowEvent.RecircResult(false, "x", false), env()).first.note)
        assertEquals("바깥 공기가 나빠", ClimateFlow.reduce(s1, FlowEvent.RecircResult(true, "바깥 공기가 나빠", true), env()).first.note)
    }

    // 3) 규칙이 가득 차면 제안 자체가 없다
    @Test fun noCandidateWhenRulesFull() {
        val c = Ctx(-2f, 7, null)
        var h = emptyList<HabitStat>(); for (d in 1L..3L) h = HabitLearner.record(h, HabitAction.SEAT_HEAT, 2, c, d)
        assertNotNull(HabitLearner.candidate(h, c, 3, emptyMap(), emptyList(), 0))
        assertNull(HabitLearner.candidate(h, c, 3, emptyMap(), List(Edits.MAX_RULES) { PresetRule("r$it", "r") }, 0))
    }

    // 4) 하향·단계 변경은 켜기 아님
    @Test fun stepDownOrChangeIsNotTurnOn() {
        assertNull(UserDetect.turnedOn("seatHeat", 3.0, 2.0, Origin.USER)); assertNull(UserDetect.turnedOn("seatVent", 1.0, 3.0, Origin.USER)); assertNull(UserDetect.turnedOn("wheelHeat", 2.0, 1.0, Origin.USER))
        assertEquals(HabitAction.WHEEL_HEAT to 2, UserDetect.turnedOn("wheelHeat", 0.0, 2.0, Origin.USER))
        assertEquals(HabitAction.SEAT_VENT to 1, UserDetect.turnedOn("seatVent", 0.0, 1.0, Origin.USER))
    }
}
