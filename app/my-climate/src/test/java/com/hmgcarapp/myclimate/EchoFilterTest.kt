package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.EchoFilter
import org.junit.Assert.*
import org.junit.Test

class EchoFilterTest {
    @Test fun matchingEventWithinWindowIsEchoOnce() {
        val f = EchoFilter(1000); f.expect("ac", 1.0, 0)
        assertTrue(f.consume("ac", 1.0, 999)); assertFalse(f.consume("ac", 1.0, 999))
    }
    @Test fun expiredOrDifferentValueIsUserAction() {
        val f = EchoFilter(1000); f.expect("ac", 1.0, 0); f.expect("seatHeat", 2.0, 0)
        assertFalse(f.consume("ac", 1.0, 1001)); assertFalse(f.consume("seatHeat", 3.0, 10)); assertFalse(f.consume("fan", 2.0, 10))
    }
    @Test fun newExpectationReplacesOldOfSameKind() {
        val f = EchoFilter(1000); f.expect("temp", 20.0, 0); f.expect("temp", 22.0, 0)
        assertFalse(f.consume("temp", 20.0, 1)); assertTrue(f.consume("temp", 22.1, 1))
    }
}
