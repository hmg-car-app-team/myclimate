package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.AreaPicker
import org.junit.Assert.*
import org.junit.Test

class AreaPickerTest {
    private val L = "ROW_1_LEFT"; private val R = "ROW_1_RIGHT"; private val L2 = "ROW_2_LEFT"; private val R2 = "ROW_2_RIGHT"
    private fun pick(vararg a: List<String>) = AreaPicker.pickDriverArea(a.toList())

    @Test fun emptyListIsUnsupported() { assertNull(AreaPicker.pickDriverArea(emptyList())) }
    @Test fun singleDriverArea() { assertEquals(0, pick(listOf(L))) }
    @Test fun bundledAreaLikeObserved117() { assertEquals(0, pick(listOf(L, R, L2, R2))) }
    @Test fun rowPairLikeObserved5() { assertEquals(0, pick(listOf(L, R))) }
    @Test fun passengerOnlyAreaDoesNotMatch() { assertNull(pick(listOf(R), listOf(R, R2))) }
    @Test fun driverOnlyBeatsBundle() { assertEquals(1, pick(listOf(L, R, L2, R2), listOf(L))) }
    @Test fun smallestContainingBundleWins() { assertEquals(2, pick(listOf(R), listOf(L, R, L2, R2), listOf(L, R))) }
    @Test fun tieKeepsFirst() { assertEquals(0, pick(listOf(L, R), listOf(L, L2))) }
    @Test fun skipsNonDriverBeforeMatch() { assertEquals(1, pick(listOf(R), listOf(L, R))) }
    @Test fun emptyAreaValuesIgnored() { assertEquals(1, pick(emptyList(), listOf(L))) }
}
