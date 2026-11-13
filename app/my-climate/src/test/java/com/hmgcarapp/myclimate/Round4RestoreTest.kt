package com.hmgcarapp.myclimate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 4차: 설정의 '학습 기록 지우기' 버튼은 확인 팝업을 거쳐 clearLearned 를 호출한다. */
class Round4RestoreTest {
    @Test fun clearLearnedButtonWiredWithConfirm() {
        val ui = File("src/main/java/com/hmgcarapp/myclimate/ui/Screens.kt").readText()
        assertEquals(1, Regex("""c\.clearLearned\(\)""").findAll(ui).count())
        assertTrue(ui.contains("학습 기록 지우기") && ui.contains("clear_learned"))
        assertTrue("버튼은 바로 지우지 않고 팝업을 연다", ui.contains("onClick = { askLearned = true }"))
    }
}
