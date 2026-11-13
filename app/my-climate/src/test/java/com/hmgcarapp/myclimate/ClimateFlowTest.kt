package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class ClimateFlowTest {
    private val caps = ClimateCaps(ac = true, recirc = true, fan = true, fanDir = true, temp = true, seatHeat = true, seatVent = true, wheelHeat = true)
    private val hwOn = HwState(connected = true, caps = caps, current = ClimateSettings(recirc = false))
    private val coldCtx = Ctx(-3f, 7, null)
    private fun env(now: Long = 1_000_000L, ctx: Ctx = coldCtx, air: AirReading? = null, day: Long = 100) = Env(now, ctx, air, day)
    private val profile = Profile("p1", "나", ClimateSettings(tempC = 23f, fan = 3))
    private val persisted = Persisted(listOf(profile), "p1")

    private fun run(s: FlowState, vararg e: FlowEvent, env: Env = env()): Pair<FlowState, List<FlowEffect>> {
        var st = s; val fx = ArrayList<FlowEffect>()
        e.forEach { val (n, f) = ClimateFlow.reduce(st, it, env); st = n; fx += f }
        return st to fx
    }
    private fun applied(fx: List<FlowEffect>) = fx.filterIsInstance<FlowEffect.Apply>()

    @Test fun startsAsDrivingFailSafe() { assertFalse(FlowState().parked) }

    @Test fun autoAppliesOnceWhenParkedLoadedAndConnected() {
        val (s, fx) = run(FlowState(), FlowEvent.Restored(persisted), FlowEvent.Hw(hwOn), FlowEvent.DriveChanged(true))
        val a = applied(fx).single()
        assertEquals(24f, a.settings.tempC); assertEquals(3, a.settings.seatHeat); assertEquals(3, a.settings.fan) // 프로필 위에 한파 규칙(온도 24, 열선)이 덮어쓴 결과
        assertTrue(s.appliedThisEpisode)
        val (_, fx2) = run(s, FlowEvent.Hw(hwOn.copy(current = ClimateSettings(recirc = true))))
        assertTrue(applied(fx2).isEmpty())
    }
    @Test fun neverAppliesWhileDriving() {
        val (_, fx) = run(FlowState(), FlowEvent.Restored(persisted), FlowEvent.Hw(hwOn))
        assertTrue(applied(fx).isEmpty())
        val (_, fx2) = run(FlowState(), FlowEvent.Restored(persisted), FlowEvent.Hw(hwOn), FlowEvent.ApplyNow, FlowEvent.ProfileChanged)
        assertTrue(applied(fx2).isEmpty())
    }
    @Test fun waitsForRestoreAndHardware() {
        assertTrue(applied(run(FlowState(), FlowEvent.DriveChanged(true), FlowEvent.Hw(hwOn)).second).isEmpty())
        assertTrue(applied(run(FlowState(), FlowEvent.DriveChanged(true), FlowEvent.Restored(persisted)).second).isEmpty())
        assertTrue(applied(run(FlowState(), FlowEvent.DriveChanged(true), FlowEvent.Restored(persisted), FlowEvent.Hw(hwOn.copy(connected = false))).second).isEmpty())
        assertEquals(1, applied(run(FlowState(), FlowEvent.DriveChanged(true), FlowEvent.Restored(persisted), FlowEvent.Hw(hwOn)).second).size)
    }
    @Test fun capsArrivingAfterConnectionStillApply() {
        val (s, fx) = run(FlowState(), FlowEvent.Restored(persisted), FlowEvent.DriveChanged(true), FlowEvent.Hw(HwState(connected = true, caps = ClimateCaps())))
        assertTrue(applied(fx).isEmpty())
        assertEquals(1, applied(run(s, FlowEvent.Hw(hwOn)).second).size)
    }
    @Test fun autoApplyOffSkipsButManualApplyWorks() {
        val p = persisted.copy(autoApply = false)
        val (s, fx) = run(FlowState(), FlowEvent.Restored(p), FlowEvent.Hw(hwOn), FlowEvent.DriveChanged(true))
        assertTrue(applied(fx).isEmpty())
        assertEquals(1, applied(run(s, FlowEvent.ApplyNow).second).size)
    }
    @Test fun reappliesAfterDrivingThenParkingAgain() {
        val (s, _) = run(FlowState(), FlowEvent.Restored(persisted), FlowEvent.Hw(hwOn), FlowEvent.DriveChanged(true))
        val (s2, _) = run(s, FlowEvent.DriveChanged(false))
        assertFalse(s2.appliedThisEpisode)
        assertEquals(1, applied(run(s2, FlowEvent.DriveChanged(true)).second).size)
    }
    @Test fun appliesOnlyWritableAndInRange() {
        val c = ClimateCaps(seatHeat = true, limits = Limits(seatHeatMax = 2))
        val (_, fx) = run(FlowState(), FlowEvent.Restored(persisted), FlowEvent.Hw(HwState(true, c)), FlowEvent.DriveChanged(true))
        assertEquals(ClimateSettings(seatHeat = 2), applied(fx).single().settings)
    }
    @Test fun nothingToApplyEmitsNoEffect() {
        val (s, fx) = run(FlowState(), FlowEvent.Restored(Persisted(rules = emptyList())), FlowEvent.Hw(hwOn), FlowEvent.DriveChanged(true))
        assertTrue(fx.isEmpty()); assertTrue(s.appliedThisEpisode)
    }
    @Test fun profileChangeReappliesWhileParked() {
        val (s, _) = run(FlowState(), FlowEvent.Restored(persisted), FlowEvent.Hw(hwOn), FlowEvent.DriveChanged(true))
        assertEquals(1, applied(run(s, FlowEvent.ProfileChanged).second).size)
    }

    @Test fun userTurnOnIsLearnedOnlyAfterRestore() {
        assertTrue(run(FlowState(), FlowEvent.UserTurnedOn(HabitAction.SEAT_HEAT, 2)).second.isEmpty())
        val (s, fx) = run(FlowState(loaded = true), FlowEvent.UserTurnedOn(HabitAction.SEAT_HEAT, 2))
        assertEquals(listOf(FlowEffect.Persist), fx); assertEquals(1, s.p.habits.size)
        assertTrue(run(s, FlowEvent.UserTurnedOn(HabitAction.SEAT_HEAT, 2)).second.isEmpty()) // 같은 날 반복: 변화 없음
    }
    @Test fun threeDaysLeadToSuggestionOnlyWhileParkedAndAcceptCreatesRule() {
        var s = FlowState(loaded = true, p = persisted)
        for (d in 98L..100L) s = run(s, FlowEvent.UserTurnedOn(HabitAction.SEAT_HEAT, 2), env = env(day = d)).first
        val (drv, fxD) = run(s, FlowEvent.Tick)
        assertNull(drv.pending); assertTrue(fxD.none { it is FlowEffect.Speak })
        val (pk, fx) = run(s.copy(parked = true), FlowEvent.Tick)
        assertNotNull(pk.pending); assertTrue(fx.single { it is FlowEffect.Speak } is FlowEffect.Speak)
        val (acc, fa) = run(pk, FlowEvent.SuggestAccepted)
        assertNull(acc.pending); assertTrue(acc.p.rules.any { it.source == RuleSource.LEARNED }); assertTrue(FlowEffect.Persist in fa)
        assertNull(run(acc.copy(lastSuggestAtMs = null), FlowEvent.Tick).first.pending) // 이미 규칙이 있어 재제안 없음
    }
    @Test fun dismissSuppressesAndRespectsGap() {
        var s = FlowState(loaded = true, p = persisted, parked = true)
        for (d in 98L..100L) s = run(s, FlowEvent.UserTurnedOn(HabitAction.SEAT_HEAT, 2), env = env(day = d)).first
        val (pk, _) = run(s, FlowEvent.Tick)
        val (dis, _) = run(pk, FlowEvent.SuggestDismissed)
        assertNull(dis.pending); assertEquals(1, dis.p.dismissed.size)
        assertNull(run(dis.copy(lastSuggestAtMs = null), FlowEvent.Tick).first.pending)
    }
    @Test fun suggestionGapBlocksRepeatEvenWithoutDismiss() {
        var s = FlowState(loaded = true, p = persisted, parked = true)
        for (d in 98L..100L) s = run(s, FlowEvent.UserTurnedOn(HabitAction.SEAT_HEAT, 2), env = env(day = d)).first
        val (pk, _) = run(s, FlowEvent.Tick, env = env(now = 5_000_000L))
        val (drv, _) = run(pk, FlowEvent.DriveChanged(false), env = env(now = 5_000_001L))
        assertNull(drv.pending)
        val (p2, _) = run(drv.copy(parked = true), FlowEvent.Tick, env = env(now = 5_000_002L))
        assertNull(p2.pending)
        assertNotNull(run(drv.copy(parked = true), FlowEvent.Tick, env = env(now = 5_000_001L + ClimateFlow.SUGGEST_GAP_MS)).first.pending)
    }
    @Test fun suggestionAcceptWhileDrivingIsIgnored() {
        val sg = Suggestion(HabitKey(HabitAction.AC, TempBand.HOT, TimeSlot.DAY), 1)
        val (s, fx) = run(FlowState(loaded = true, pending = sg, parked = false), FlowEvent.SuggestAccepted)
        assertTrue(fx.isEmpty()); assertEquals(persisted.copy(profiles = emptyList(), selectedId = null).rules, s.p.rules)
    }

    @Test fun smartRecircRunsWhileDrivingWithVoiceOnly() {
        val bad = AirReading(AirGrade.BAD, 1_000_000L, true)
        val s0 = FlowState(loaded = true, p = persisted.copy(smartRecirc = true), hw = hwOn)
        val (s, fx) = run(s0, FlowEvent.Tick, env = env(air = bad))
        assertTrue(fx.any { it is FlowEffect.SetRecirc && it.on }); assertTrue(fx.none { it is FlowEffect.Speak }) // 안내는 쓰기 성공 뒤 실행부가 한다
        assertTrue(applied(fx).isEmpty()); assertEquals(1, fx.count { it is FlowEffect.SetRecirc })
        assertTrue(run(s, FlowEvent.Tick, env = env(now = 1_000_100L, air = bad)).second.none { it is FlowEffect.SetRecirc })
    }
    @Test fun smartRecircOffOrNoCapabilityDoesNothing() {
        val bad = AirReading(AirGrade.BAD, 1_000_000L, true)
        assertTrue(run(FlowState(loaded = true, p = persisted, hw = hwOn), FlowEvent.Tick, env = env(air = bad)).second.isEmpty())
        assertTrue(run(FlowState(loaded = true, p = persisted.copy(smartRecirc = true), hw = hwOn.copy(caps = caps.copy(recirc = false))), FlowEvent.Tick, env = env(air = bad)).second.isEmpty())
    }
    @Test fun recircObservationTracksUserVsUs() {
        val s0 = FlowState(loaded = true, hw = hwOn)
        val (byUser, _) = run(s0, FlowEvent.Hw(hwOn.copy(current = ClimateSettings(recirc = true)), recircByUs = false))
        assertNotNull(byUser.recirc.onSince); assertFalse(byUser.recirc.autoOn); assertTrue(byUser.recirc.holdUntil > 0)
        val (byUs, _) = run(s0, FlowEvent.Hw(hwOn.copy(current = ClimateSettings(recirc = true)), recircByUs = true))
        assertTrue(byUs.recirc.autoOn)
    }

    @Test fun feelKeepUndoUsesAppliedDelta() {
        val hot = Ctx(31f, 12, null)
        val p = Persisted(listOf(Profile("p1", "나", ClimateSettings(tempC = 24f))), "p1", feelKeep = true)
        val (s, fx) = run(FlowState(), FlowEvent.Restored(p), FlowEvent.Hw(hwOn), FlowEvent.DriveChanged(true), env = env(ctx = hot))
        assertEquals(-1f, s.fkDelta); val t = applied(fx).single().settings.tempC!!
        assertEquals(t + 1f, FeelKeep.undo(t, s.fkTemp, s.fkDelta))
        assertEquals(t + 2f, FeelKeep.undo(t + 2f, s.fkTemp, s.fkDelta))
    }
    @Test fun feelKeepOffKeepsCurrentAsBase() {
        val p = Persisted(listOf(Profile("p1", "나", ClimateSettings(tempC = 24f))), "p1", feelKeep = false)
        val (s, _) = run(FlowState(), FlowEvent.Restored(p), FlowEvent.Hw(hwOn), FlowEvent.DriveChanged(true), env = env(ctx = Ctx(31f, 12, null)))
        assertEquals(0f, s.fkDelta); assertNull(s.fkTemp)
        assertEquals(24f, FeelKeep.undo(24f, s.fkTemp, s.fkDelta))
    }
    @Test fun demoValueNotShownInNote() {
        val (s, _) = run(FlowState(), FlowEvent.Restored(persisted), FlowEvent.Hw(hwOn), FlowEvent.DriveChanged(true), env = env().copy(demo = true))
        assertFalse(s.note!!.contains("시연"))
        assertTrue(s.note!!.startsWith("자동 적용")) // 적용 상태 한 줄은 그대로
    }
}
