package com.hmgcarapp.myclimate

import com.hmgcarapp.myclimate.domain.ClimateSettings
import com.hmgcarapp.myclimate.domain.Persisted
import com.hmgcarapp.myclimate.domain.Profile
import com.hmgcarapp.myclimate.domain.SeatOfferPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatOfferTest {
    private val p = Persisted(profiles = listOf(Profile("a", "출근", ClimateSettings()), Profile("b", "주말", ClimateSettings())), selectedId = "b")

    @Test fun offers_lastUsedProfile_onlyWhenParkedLoadedAndSeated() {
        val o = SeatOfferPolicy.offer(p, loaded = true, parked = true, occupied = true, handledThisSeating = false)
        assertEquals("b", o?.profileId)
        assertEquals("마지막으로 사용한 프로필 '주말'(으)로 맞출까요?", o?.text)
        assertNull(SeatOfferPolicy.offer(p, true, parked = false, occupied = true, handledThisSeating = false))   // 주행 중
        assertNull(SeatOfferPolicy.offer(p, loaded = false, parked = true, occupied = true, handledThisSeating = false))
    }

    @Test fun noOffer_whenSignalMissingFalseOrAlreadyHandled() {
        assertNull(SeatOfferPolicy.offer(p, true, true, occupied = null, handledThisSeating = false))   // 신호 없음/불확실
        assertNull(SeatOfferPolicy.offer(p, true, true, occupied = false, handledThisSeating = false))
        assertNull(SeatOfferPolicy.offer(p, true, true, occupied = true, handledThisSeating = true))
    }

    @Test fun noOffer_withoutValidLastUsedProfile() {
        assertNull(SeatOfferPolicy.offer(Persisted(), true, true, true, false))
        assertNull(SeatOfferPolicy.offer(p.copy(selectedId = null), true, true, true, false))
        assertNull(SeatOfferPolicy.offer(p.copy(selectedId = "zzz"), true, true, true, false))
    }

    @Test fun wording_doesNotAssertDriverIdentity() {
        val t = SeatOfferPolicy.offer(p, true, true, true, false)!!.text
        assertTrue(t.contains("마지막으로 사용한 프로필"))
        for (bad in listOf("운전자", "님", "본인", "인식")) assertFalse(t.contains(bad))
        assertNotNull(t)
    }
}
