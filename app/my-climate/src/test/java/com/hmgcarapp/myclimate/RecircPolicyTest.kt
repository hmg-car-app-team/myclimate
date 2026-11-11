package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class RecircPolicyTest {
    private val T0 = 1_000_000_000L
    private fun air(g: AirGrade, at: Long = T0) = AirReading(g, at, true)
    private val MIN = 60_000L

    @Test fun badAirTurnsRecircOn() {
        val (_, d) = RecircPolicy.step(RecircState(), false, air(AirGrade.BAD), T0)
        assertTrue(d is RecircDecision.ToRecirc)
    }
    @Test fun goodOrUnknownOrStaleAirDoesNothingWhenOff() {
        assertEquals(RecircDecision.None, RecircPolicy.step(RecircState(), false, air(AirGrade.GOOD), T0).second)
        assertEquals(RecircDecision.None, RecircPolicy.step(RecircState(), false, null, T0).second)
        assertEquals(RecircDecision.None, RecircPolicy.step(RecircState(), false, air(AirGrade.VERY_BAD, T0 - RecircPolicy.AIR_FRESH_MS - 1), T0).second)
        assertTrue(RecircPolicy.step(RecircState(), false, air(AirGrade.VERY_BAD, T0 - RecircPolicy.AIR_FRESH_MS), T0).second is RecircDecision.ToRecirc)
    }
    @Test fun unknownRecircStateDoesNothing() { assertEquals(RecircDecision.None, RecircPolicy.step(RecircState(), null, air(AirGrade.BAD), T0).second) }
    @Test fun longRecircSwitchesToOutsideAtLimit() {
        val s = RecircState(onSince = T0)
        assertEquals(RecircDecision.None, RecircPolicy.step(s, true, air(AirGrade.GOOD, T0 + 10 * MIN), T0 + 10 * MIN - 1).second)
        val (n, d) = RecircPolicy.step(s, true, air(AirGrade.GOOD, T0 + 10 * MIN), T0 + 10 * MIN)
        assertTrue(d is RecircDecision.ToOutside); assertEquals(T0 + 10 * MIN + RecircPolicy.COOLDOWN_MS, n.cooldownUntil)
    }
    @Test fun badAirExtendsLimit() {
        val s = RecircState(onSince = T0)
        val now = T0 + 15 * MIN
        assertEquals(RecircDecision.None, RecircPolicy.step(s, true, air(AirGrade.BAD, now), now).second)
        assertTrue(RecircPolicy.step(s, true, air(AirGrade.BAD, T0 + 20 * MIN), T0 + 20 * MIN).second is RecircDecision.ToOutside)
    }
    @Test fun cooldownBlocksImmediateReRecirc() {
        val (n, _) = RecircPolicy.step(RecircState(onSince = T0), true, null, T0 + 10 * MIN)
        val off = n.copy(onSince = null)
        val before = T0 + 10 * MIN + RecircPolicy.COOLDOWN_MS - 1
        assertEquals(RecircDecision.None, RecircPolicy.step(off, false, air(AirGrade.BAD, before), before).second)
        val later = T0 + 10 * MIN + RecircPolicy.COOLDOWN_MS
        assertTrue(RecircPolicy.step(off, false, air(AirGrade.BAD, later), later).second is RecircDecision.ToRecirc)
    }
    @Test fun minGapPreventsDecisionSpam() {
        val (n, d) = RecircPolicy.step(RecircState(), false, air(AirGrade.BAD), T0)
        assertTrue(d is RecircDecision.ToRecirc)
        assertEquals(RecircDecision.None, RecircPolicy.step(n, false, air(AirGrade.BAD), T0 + RecircPolicy.MIN_GAP_MS - 1).second)
        assertTrue(RecircPolicy.step(n, false, air(AirGrade.BAD), T0 + RecircPolicy.MIN_GAP_MS).second is RecircDecision.ToRecirc)
    }
    @Test fun userHoldBlocksAutoSwitch() {
        val s = RecircPolicy.onObserved(RecircState(onSince = T0), false, byUs = false, now = T0)
        assertEquals(RecircDecision.None, RecircPolicy.step(s, false, air(AirGrade.BAD), T0 + RecircPolicy.HOLD_MS - 1).second)
        assertTrue(RecircPolicy.step(s, false, air(AirGrade.BAD, T0 + RecircPolicy.HOLD_MS), T0 + RecircPolicy.HOLD_MS).second is RecircDecision.ToRecirc)
    }
    @Test fun autoRecircReturnsToOutsideWhenAirImproves() {
        val s = RecircPolicy.onObserved(RecircState(), true, byUs = true, now = T0)
        assertTrue(s.autoOn)
        assertTrue(RecircPolicy.step(s, true, air(AirGrade.GOOD, T0 + MIN), T0 + MIN).second is RecircDecision.ToOutside)
        assertEquals(RecircDecision.None, RecircPolicy.step(s, true, air(AirGrade.BAD, T0 + MIN), T0 + MIN).second)
    }
    @Test fun userRecircIsNotRevertedByAirImprovement() {
        val s = RecircPolicy.onObserved(RecircState(), true, byUs = false, now = T0)
        assertFalse(s.autoOn)
        assertEquals(RecircDecision.None, RecircPolicy.step(s, true, air(AirGrade.GOOD, T0 + 11 * MIN - 1), T0 + 5 * MIN).second)
    }
    @Test fun userRecircStillCappedByMaxDuration() {
        val s = RecircPolicy.onObserved(RecircState(), true, byUs = false, now = T0)
        assertTrue(RecircPolicy.step(s, true, null, T0 + 10 * MIN).second is RecircDecision.ToOutside)
    }
    @Test fun observedTransitionsTrackSince() {
        val on = RecircPolicy.onObserved(RecircState(), true, true, T0)
        assertEquals(T0, on.onSince)
        assertEquals(T0, RecircPolicy.onObserved(on, true, true, T0 + 5).onSince)
        assertNull(RecircPolicy.onObserved(on, false, true, T0 + 9).onSince)
        assertEquals(on, RecircPolicy.onObserved(on, null, true, T0 + 9))
    }
    @Test fun minGapIsFiveMinutes() { assertEquals(5 * MIN, RecircPolicy.MIN_GAP_MS) }
    @Test fun holdIsTenMinutes() { assertEquals(10 * MIN, RecircPolicy.HOLD_MS) }

    @Test fun clockGoingBackwardsDoesNotTrigger() {
        val s = RecircState(onSince = T0)
        assertEquals(RecircDecision.None, RecircPolicy.step(s, true, null, T0 - 5 * MIN).second)
    }
}
