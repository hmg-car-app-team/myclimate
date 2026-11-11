package com.hmgcarapp.myclimate

import com.hmgcarapp.core.sim.SimState
import com.hmgcarapp.core.vehicle.SignalReading
import com.hmgcarapp.myclimate.app.SimField
import com.hmgcarapp.myclimate.app.SimInput
import com.hmgcarapp.myclimate.data.EnvParser
import com.hmgcarapp.myclimate.data.FakeEnvironmentRepository
import com.hmgcarapp.myclimate.data.HttpsOnly
import com.hmgcarapp.myclimate.domain.*
import com.hmgcarapp.myclimate.vehicle.OutsideTemp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EnvAndSimTest {
    @Test fun pm25Grades() {
        assertEquals(AirGrade.GOOD, EnvParser.grade(15)); assertEquals(AirGrade.NORMAL, EnvParser.grade(16)); assertEquals(AirGrade.NORMAL, EnvParser.grade(35))
        assertEquals(AirGrade.BAD, EnvParser.grade(36)); assertEquals(AirGrade.BAD, EnvParser.grade(75)); assertEquals(AirGrade.VERY_BAD, EnvParser.grade(76)); assertNull(EnvParser.grade(-1))
    }
    @Test fun parsersReturnNullOnBadInput() {
        assertEquals(Weather.RAIN, EnvParser.weather("""{"sky":"RAIN"}""")); assertNull(EnvParser.weather("""{"sky":"HAIL"}""")); assertNull(EnvParser.weather("garbage"))
        assertEquals(AirGrade.BAD, EnvParser.air("""{"pm25":40}""")); assertNull(EnvParser.air("""{"pm10":40}"""))
    }
    @Test fun httpsOnly() {
        HttpsOnly.require("https://a.example/x")
        assertThrows(IllegalArgumentException::class.java) { HttpsOnly.require("http://a.example") }
    }
    @Test fun fakeRepositoryIsDeterministic() = runBlocking {
        val f = FakeEnvironmentRepository(Weather.SNOW, AirGrade.BAD); assertEquals(Weather.SNOW, f.weather()); assertEquals(AirGrade.BAD, f.air())
    }
    @Test fun outsideTempPrefersEnabledSimThenReal() {
        val real = SignalReading.ok(12f)
        assertEquals(12f, OutsideTemp.pick(SimState(), real)); assertEquals(12f, OutsideTemp.pick(SimState(enabled = false, outsideTempC = 3f), real))
        assertEquals(3f, OutsideTemp.pick(SimState(enabled = true, outsideTempC = 3f), real)); assertEquals(12f, OutsideTemp.pick(SimState(enabled = true), real))
    }
    @Test fun outsideTempRejectsInvalid() {
        assertNull(OutsideTemp.pick(SimState(), SignalReading.ok(Float.NaN))); assertNull(OutsideTemp.pick(SimState(), SignalReading.ok(200f)))
        assertNull(OutsideTemp.pick(SimState(), SignalReading.unavailable("x"))); assertEquals(-60f, OutsideTemp.pick(SimState(), SignalReading.ok(-60f)))
    }
    @Test fun simInputRanges() {
        assertEquals(SimField.Set(-3f), SimInput.outside("-3")); assertEquals(SimField.Ignore, SimInput.outside("99")); assertEquals(SimField.Clear, SimInput.outside("none"))
        assertEquals(SimField.Set(23), SimInput.hour(23)); assertEquals(SimField.Ignore, SimInput.hour(24)); assertEquals(SimField.Ignore, SimInput.hour("x"))
        assertEquals(SimField.Set(Weather.RAIN), SimInput.weather("rain")); assertEquals(SimField.Ignore, SimInput.weather("x")); assertEquals(SimField.Set(AirGrade.VERY_BAD), SimInput.air("VERY_BAD"))
    }
    @Test fun simObserveParsing() {
        assertEquals(HabitAction.SEAT_HEAT to 2, SimInput.observe("seat_heat:2")); assertNull(SimInput.observe("seat_heat:0")); assertNull(SimInput.observe("seat_heat")); assertNull(SimInput.observe("zzz:1")); assertNull(SimInput.observe(null))
    }
}
