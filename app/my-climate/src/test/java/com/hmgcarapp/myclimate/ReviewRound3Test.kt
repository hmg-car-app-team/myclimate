package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.app.StateCodec
import com.hmgcarapp.myclimate.app.netLoadResult
import com.hmgcarapp.myclimate.data.NetPrefsData
import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class ReviewRound3Test {
    private val caps = ClimateCaps(ac = true, recirc = true, temp = true)
    private val prof = Persisted(listOf(Profile("p1", "나", ClimateSettings(tempC = 22f))), "p1", rules = emptyList())
    private fun env() = Env(1_000_000L, Ctx(20f, 12, null), null, 100)

    // 1) 사망 표시 중(connected=false)에는 자동 적용이 소진되지 않고, 복구되면 1회 적용
    @Test fun lostConnectionDoesNotConsumeAutoApply() {
        var s = FlowState(loaded = true, p = prof, parked = true)
        val (s1, f1) = ClimateFlow.reduce(s, FlowEvent.Hw(HwState(connected = false, caps = caps)), env())
        assertTrue(f1.isEmpty()); assertFalse(s1.appliedThisEpisode)
        val (s2, f2) = ClimateFlow.reduce(s1, FlowEvent.Hw(HwState(connected = true, caps = caps)), env())
        assertEquals(1, f2.count { it is FlowEffect.Apply }); assertTrue(s2.appliedThisEpisode)
    }

    // 2) 학습 규칙 이름: 12자 이내, 서로 구분, 저장 복원 뒤에도 동일
    @Test fun learnedRuleNamesFitAndAreUniqueAndSurviveRoundTrip() {
        val names = HashSet<String>()
        for (a in HabitAction.entries) for (b in TempBand.entries) for (t in TimeSlot.entries) {
            val r = HabitLearner.toRule(Suggestion(HabitKey(a, b, t), 2))
            assertTrue(r.name, r.name.length <= Edits.MAX_NAME); assertTrue(names.add(r.name))
            assertEquals(r.name, Edits.sanitizeRule(r).name)
        }
        val r = HabitLearner.toRule(Suggestion(HabitKey(HabitAction.SEAT_HEAT, TempBand.COLD, TimeSlot.MORNING), 2))
        assertEquals("추운날 아침 시트열선", r.name)
        val back = StateCodec.parse(StateCodec.encode(Persisted(rules = listOf(r))).toString()).p
        assertEquals(r, back.rules.single())
    }

    // 3) 학습 기록 지우기는 떠 있던 제안도 지운다
    @Test fun clearLearnedAlsoClearsPending() {
        val sg = Suggestion(HabitKey(HabitAction.AC, TempBand.HOT, TimeSlot.DAY), 1)
        val p = Persisted(habits = listOf(HabitStat(sg.key, listOf(1, 2, 3), 1)), dismissed = mapOf("x" to 5L))
        val (s, fx) = ClimateFlow.reduce(FlowState(loaded = true, parked = true, pending = sg, p = p), FlowEvent.LearnedCleared, env())
        assertNull(s.pending); assertTrue(s.p.habits.isEmpty()); assertTrue(s.p.dismissed.isEmpty()); assertEquals(listOf(FlowEffect.Persist), fx)
        assertEquals(p.profiles, s.p.profiles)
    }

    // 4) 재연결 뒤 첫 관측이 ON 이면 옛 onSince/autoOn 을 믿지 않는다
    @Test fun firstOnObservationOverwritesStaleState() {
        val stale = RecircState(onSince = 1L, autoOn = true, holdUntil = 0L)
        val s = RecircPolicy.onObserved(stale, true, byUs = false, now = 5_000L, first = true)
        assertEquals(5_000L, s.onSince); assertFalse(s.autoOn)
    }

    // 6) 상태 불명 표시
    @Test fun recircViewShowsDashWhenUnknown() {
        assertEquals("꺼짐", RecircView.label(false, true, true, true))
        assertEquals("켜짐 · -", RecircView.label(true, false, true, true)); assertEquals("켜짐 · -", RecircView.label(true, true, false, true)); assertEquals("켜짐 · -", RecircView.label(true, true, true, null))
        assertEquals("켜짐 · 내기", RecircView.label(true, true, true, true)); assertEquals("켜짐 · 외기", RecircView.label(true, true, true, false))
    }
}

class ReviewRound4Test {
    private fun env() = Env(1_000_000L, Ctx(20f, 12, null), null, 100)
    private val prof = Persisted(listOf(Profile("p1", "나", ClimateSettings(tempC = 22f))), "p1", rules = emptyList())

    // 1) 지원 항목 0개여도 연결됨 상태를 그대로 받고, 자동/수동 적용은 막힌다
    @Test fun connectedWithNoCapsStaysConnectedButNeverApplies() {
        val hw = HwState(connected = true, caps = ClimateCaps())
        val (s, f) = ClimateFlow.reduce(FlowState(loaded = true, p = prof, parked = true), FlowEvent.Hw(hw), env())
        assertTrue(s.hw.connected); assertTrue(f.isEmpty()); assertFalse(s.appliedThisEpisode)
        val (s2, f2) = ClimateFlow.reduce(s, FlowEvent.ApplyNow, env())
        assertTrue(f2.isEmpty()); assertNull(s2.note)
    }

    // 2) 사용자가 내기를 바꾸면 직전 스마트 문구를 비운다. 첫 관측·우리 명령의 에코·다른 note 는 건드리지 않는다
    private val caps = ClimateCaps(recirc = true)
    private fun hw(r: Boolean?) = HwState(true, caps, ClimateSettings(recirc = r))
    private val smart = FlowState(loaded = true, hw = hw(false), note = "바깥 공기가 나빠", smartNote = true)

    @Test fun userRecircChangeClearsSmartNote() {
        val s = ClimateFlow.reduce(smart, FlowEvent.Hw(hw(true), recircByUs = false), env()).first
        assertNull(s.note); assertFalse(s.smartNote)
    }
    @Test fun smartEchoFirstObservationAndOtherNotesKeepNote() {
        assertEquals("바깥 공기가 나빠", ClimateFlow.reduce(smart, FlowEvent.Hw(hw(true), recircByUs = true), env()).first.note)
        assertEquals("바깥 공기가 나빠", ClimateFlow.reduce(smart.copy(hw = HwState(true, caps, ClimateSettings())), FlowEvent.Hw(hw(true), recircByUs = false), env()).first.note)
        assertEquals("6건 적용", ClimateFlow.reduce(smart.copy(note = "6건 적용", smartNote = false), FlowEvent.Hw(hw(true), recircByUs = false), env()).first.note)
    }
    @Test fun smartNoteFlagFollowsResultEvents() {
        val ok = ClimateFlow.reduce(FlowState(), FlowEvent.RecircResult(true, "x", true), env()).first
        assertTrue(ok.smartNote)
        assertFalse(ClimateFlow.reduce(ok, FlowEvent.ApplyResult(listOf(ItemResult("ac", true, null))), env()).first.smartNote)
        assertFalse(ClimateFlow.reduce(FlowState(), FlowEvent.RecircResult(false, "x", true), env()).first.smartNote)
    }
}

class HwConnectionTest {
    @Test fun allConditionsMet() { assertTrue(HwConnection.connected(0, 1, ready = true, lost = false)) }
    @Test fun pendingCapsBlocks() { assertFalse(HwConnection.connected(1, 1, true, false)); assertFalse(HwConnection.connected(8, 1, true, false)) }
    @Test fun noSyncedGenerationBlocks() { assertFalse(HwConnection.connected(0, 0, true, false)) }
    @Test fun notReadyBlocks() { assertFalse(HwConnection.connected(0, 1, false, false)) }
    @Test fun lostBlocks() { assertFalse(HwConnection.connected(0, 1, true, true)) }
    // 지원 항목이 하나도 없어도(caps 비어도) 연결됨: 인자에 caps 가 없다는 것 자체가 계약이다
    @Test fun emptyCapsStillConnected() { assertTrue(HwConnection.connected(pendingCaps = 0, syncedGen = 3, ready = true, lost = false)) }
}

class ReadingSanityTest {
    private val lim = Limits()
    @Test fun serviceDefaultZeroTempIsRejected() { assertFalse(ReadingSanity.accept("temp", 0.0, lim)) }
    @Test fun tempBoundariesInclusive() {
        assertTrue(ReadingSanity.accept("temp", 16.0, lim)); assertTrue(ReadingSanity.accept("temp", 32.0, lim))
        assertFalse(ReadingSanity.accept("temp", 15.99, lim)); assertFalse(ReadingSanity.accept("temp", 32.01, lim)); assertTrue(ReadingSanity.accept("temp", 22.0, lim))
    }
    @Test fun capabilityLimitsOverrideDefaults() {
        val l = Limits(tempMin = 17f, tempMax = 27f, fanMin = 2, fanMax = 5)
        assertFalse(ReadingSanity.accept("temp", 16.5, l)); assertTrue(ReadingSanity.accept("temp", 27.0, l))
        assertTrue(ReadingSanity.accept("fan", 1.0, l)); assertTrue(ReadingSanity.accept("fan", 2.0, l)); assertTrue(ReadingSanity.accept("fan", 5.0, l)); assertFalse(ReadingSanity.accept("fan", 6.0, l))
    }
    @Test fun fanZeroAndNaNRejected() {
        assertTrue(ReadingSanity.accept("fan", 0.0, lim)); assertTrue(ReadingSanity.accept("fan", 3.0, lim)); assertTrue(ReadingSanity.accept("fan", 8.0, lim)); assertFalse(ReadingSanity.accept("fan", 9.0, lim))
        assertFalse(ReadingSanity.accept("temp", Double.NaN, lim)); assertFalse(ReadingSanity.accept("fan", Double.POSITIVE_INFINITY, lim)); assertFalse(ReadingSanity.accept("ac", Double.NaN, lim))
    }
    // 회귀 방지: 풍량 0(꺼짐)은 정상 읽기값이다. 읽기 하한을 lim.fanMin(쓰기용, >=1)으로 되돌리면 이 테스트가 실패한다
    @Test fun fanZeroIsAcceptedEvenWhenWriteMinIsOne() {
        assertEquals(1, Limits().fanMin)
        assertTrue(ReadingSanity.accept("fan", 0.0, Limits())); assertTrue(ReadingSanity.accept("fan", 0.0, Limits(fanMin = 1, fanMax = 8)))
        assertTrue(ReadingSanity.accept("fan", 0.0, Limits(fanMin = 3, fanMax = 8)))
        assertFalse(ReadingSanity.accept("fan", -1.0, lim)); assertFalse(ReadingSanity.accept("fan", 9.0, lim)); assertTrue(ReadingSanity.accept("fan", 8.0, lim))
        assertFalse(ReadingSanity.accept("temp", 0.0, lim)) // 온도 0.0 은 여전히 거부
    }
    @Test fun booleansAndSteppedItemsAreNotRangeChecked() {
        assertTrue(ReadingSanity.accept("ac", 0.0, lim)); assertTrue(ReadingSanity.accept("recirc", 1.0, lim)); assertTrue(ReadingSanity.accept("seatHeat", 0.0, lim))
    }
    // 거부된 값은 cur 에 반영되지 않으므로 다음 정상값이 ‘첫 관측’(prev=null)으로 취급돼 학습·hold 를 만들지 않는다
    @Test fun rejectedValueKeepsFirstObservationContract() {
        assertNull(UserDetect.turnedOn("seatHeat", null, 2.0, Origin.USER))
        val s = RecircPolicy.onObserved(RecircState(), false, byUs = false, now = 1L, first = true)
        assertEquals(0L, s.holdUntil)
    }
    @Test fun clearAllPopupMentionsSmartRecircReset() {
        assertTrue(UiTexts.CLEAR_ALL_DESC.contains("스마트 내외기 설정도 꺼짐으로 돌아가요"))
        assertTrue(UiTexts.CLEAR_ALL_DESC.contains("되돌릴 수 없어요")); assertEquals("모든 데이터를 삭제할까요?", UiTexts.CLEAR_ALL_TITLE)
        assertFalse(Persisted().smartRecirc) // 기본값 = 꺼짐: 실제 초기화 결과와 문구가 일치
    }

    // 삭제 뒤 늦게 끝난 읽기는 적용하지 않는다(재동의 선택 보존)
    @Test fun lateNetLoadSkippedAfterWipe() {
        val loaded = NetPrefsData(decided = true, consent = true, regionId = "x")
        assertNull(netLoadResult(true, loaded))
        assertEquals(loaded, netLoadResult(false, loaded))
    }
}
