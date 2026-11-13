package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.*
import org.junit.Assert.*
import org.junit.Test

class ApplyReportTest {
    @Test fun parsesErrorCodeFromSdkMessage() {
        assertEquals(2, HvacErrors.code("PropertyAsyncError{propertyId=1, areaId=5, error code: 2, vendor=0}"))
        assertEquals(5, HvacErrors.code("Error Code=5")); assertEquals(3, HvacErrors.code("error code 3"))
        assertNull(HvacErrors.code("IllegalArgumentException: area ID")); assertNull(HvacErrors.code(null))
    }
    @Test fun codeFoundThroughCauseChain() {
        assertEquals(4, HvacErrors.codeOf(RuntimeException("wrap", IllegalStateException("error code: 4"))))
        assertNull(HvacErrors.codeOf(RuntimeException("x")))
    }
    @Test fun userMessagesPerCode() {
        assertEquals("요청에 실패했어요", HvacErrors.userMessage(2))
        assertEquals("요청에 실패했어요", HvacErrors.userMessage(3)); assertEquals("요청에 실패했어요", HvacErrors.userMessage(5))
        assertEquals("요청에 실패했어요", HvacErrors.userMessage(1)); assertEquals("요청에 실패했어요", HvacErrors.userMessage(null)); assertEquals("요청에 실패했어요", HvacErrors.userMessage(99))
    }
    @Test fun partialSuccessSummary() {
        val r = List(6) { ItemResult("k$it", true, null) } + ItemResult("fan", false, 2)
        assertEquals("6건 적용 · 풍량 실패(공조 꺼짐?)", HvacErrors.summary(r))
    }
    @Test fun allSuccessAllFailAndEmpty() {
        assertEquals("2건 적용", HvacErrors.summary(listOf(ItemResult("ac", true, null), ItemResult("temp", true, null))))
        assertEquals("적용 실패 · 에어컨 실패(권한), 온도 실패(알 수 없음)", HvacErrors.summary(listOf(ItemResult("ac", false, 5), ItemResult("temp", false, null))))
        assertEquals("", HvacErrors.summary(emptyList()))
    }
    @Test fun failedWriteForgetsEchoExpectation() {
        val f = EchoFilter(1000); f.expect("fan", 3.0, 0); f.forget("fan")
        assertFalse(f.consume("fan", 3.0, 1)) // 실패한 쓰기는 이후 같은 값의 사용자 조작을 에코로 삼키지 않는다
    }
    @Test fun applyResultEventSetsNoteWithoutEffects() {
        val (s, fx) = ClimateFlow.reduce(FlowState(), FlowEvent.ApplyResult(listOf(ItemResult("fan", false, 2))), Env(0, Ctx(null, 0, null), null, 0))
        assertEquals("적용 실패 · 풍량 실패(공조 꺼짐?)", s.note); assertTrue(fx.isEmpty())
    }
}
