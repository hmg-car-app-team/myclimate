package com.hmgcarapp.myclimate

import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.myclimate.data.*
import com.hmgcarapp.myclimate.domain.AirGrade
import com.hmgcarapp.myclimate.domain.Weather
import com.hmgcarapp.myclimate.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.nio.file.Files
import java.util.TimeZone

class OpenMeteoTest {
    // ---- 날씨코드 매핑 ----
    @Test fun weatherCodeMapping() {
        fun w(code: Int?, snow: Double? = null, rain: Double? = null, sh: Double? = null) = OpenMeteoParser.weatherOf(code, snow, rain, sh)
        listOf(0, 1).forEach { assertEquals(Weather.CLEAR, w(it)) }
        listOf(2, 3, 45, 48).forEach { assertEquals(Weather.CLOUDY, w(it)) }
        listOf(51, 55, 56, 57, 61, 65, 66, 67, 80, 82, 95, 99).forEach { assertEquals("code $it", Weather.RAIN, w(it)) }
        listOf(71, 73, 75, 77, 85, 86).forEach { assertEquals("code $it", Weather.SNOW, w(it)) }
        assertNull(w(4)); assertNull(w(null)); assertNull(w(50)); assertNull(w(68))
        assertEquals(Weather.SNOW, w(61, snow = 0.1)); assertEquals(Weather.SNOW, w(null, snow = 0.2)) // 눈 우선
        assertEquals(Weather.RAIN, w(3, rain = 0.1)); assertEquals(Weather.RAIN, w(0, sh = 0.3))
        assertEquals(Weather.CLEAR, w(0, snow = 0.0, rain = 0.0, sh = 0.0))
    }

    // ---- 대기질 등급 경계 ----
    @Test fun airGradeBoundaries() {
        fun g(p10: Double?, p25: Double?) = OpenMeteoParser.gradeOf(p10, p25)
        assertEquals(AirGrade.GOOD, g(30.0, null)); assertEquals(AirGrade.NORMAL, g(31.0, null)); assertEquals(AirGrade.NORMAL, g(80.0, null))
        assertEquals(AirGrade.BAD, g(81.0, null)); assertEquals(AirGrade.BAD, g(150.0, null)); assertEquals(AirGrade.VERY_BAD, g(151.0, null))
        assertEquals(AirGrade.GOOD, g(null, 15.0)); assertEquals(AirGrade.NORMAL, g(null, 16.0)); assertEquals(AirGrade.NORMAL, g(null, 35.0))
        assertEquals(AirGrade.BAD, g(null, 36.0)); assertEquals(AirGrade.BAD, g(null, 75.0)); assertEquals(AirGrade.VERY_BAD, g(null, 76.0))
        assertEquals(AirGrade.GOOD, g(30.4, null)); assertEquals(AirGrade.NORMAL, g(30.5, null)) // 반올림 후 정수 비교
        assertEquals(AirGrade.BAD, g(20.0, 40.0)); assertEquals(AirGrade.VERY_BAD, g(160.0, 10.0)) // 더 나쁜 등급
        assertEquals(AirGrade.GOOD, g(10.0, 5.0)); assertNull(g(null, null)); assertNull(g(-1.0, null)); assertNull(g(Double.NaN, Double.NaN))
    }

    // ---- JSON 파싱 ----
    @Test fun parseWeather() {
        assertEquals(Weather.RAIN, OpenMeteoParser.weather("""{"current":{"time":"t","weather_code":61,"rain":0.4,"showers":0.0,"snowfall":0.0}}"""))
        assertEquals(Weather.SNOW, OpenMeteoParser.weather("""{"current":{"weather_code":71}}""")) // 누락된 선택 필드는 허용
        assertEquals(Weather.CLOUDY, OpenMeteoParser.weather("""{"current":{"weather_code":3,"snowfall":null,"rain":null}}"""))
        assertNull(OpenMeteoParser.weather("""{"current":{"rain":0.0}}""")) // weather_code 누락 + 강수 없음
        assertNull(OpenMeteoParser.weather("""{"latitude":1}""")); assertNull(OpenMeteoParser.weather("garbage")); assertNull(OpenMeteoParser.weather(""))
        assertNull(OpenMeteoParser.weather("""{"error":true,"reason":"x"}"""))
        assertNull(OpenMeteoParser.weather("""{"error":true,"current":{"weather_code":0}}"""))
    }
    @Test fun parseAir() {
        assertEquals(AirGrade.NORMAL, OpenMeteoParser.air("""{"current":{"time":"t","pm10":46.9,"pm2_5":27.4}}"""))
        assertEquals(AirGrade.BAD, OpenMeteoParser.air("""{"current":{"pm10":90,"pm2_5":null}}""")) // 한쪽 누락
        assertNull(OpenMeteoParser.air("""{"current":{"pm10":null,"pm2_5":null}}""")); assertNull(OpenMeteoParser.air("""{"current":{}}"""))
        assertNull(OpenMeteoParser.air("""{"error":true,"reason":"No data is available for this location"}""")); assertNull(OpenMeteoParser.air("not json"))
    }

    // ---- 저장소: URL·실패 ----
    private class Fake(val body: (String) -> String) : HttpGet { val urls = mutableListOf<String>(); override fun get(url: String): String { urls += url; return body(url) } }

    @Test fun repositoryBuildsHttpsUrlsWithRegionCoordsOnly() = runBlocking {
        val h = Fake { if ("air-quality" in it) """{"current":{"pm10":10,"pm2_5":5}}""" else """{"current":{"weather_code":0}}""" }
        val r = OpenMeteoEnvironmentRepository(Regions.byId("busan"), h)
        assertEquals(Weather.CLEAR, r.weather()); assertEquals(AirGrade.GOOD, r.air())
        assertEquals(2, h.urls.size)
        h.urls.forEach { assertTrue(it.startsWith("https://")); assertTrue(it, "latitude=35.1796&longitude=129.0756" in it); assertFalse(it, Regex("key|token|id=", RegexOption.IGNORE_CASE).containsMatchIn(it.substringAfter('?'))) }
        assertTrue(h.urls[0].startsWith(OpenMeteoEnvironmentRepository.WEATHER_URL)); assertTrue(h.urls[1].startsWith(OpenMeteoEnvironmentRepository.AIR_URL))
    }
    @Test fun repositoryFailuresAreNull() = runBlocking {
        val r = OpenMeteoEnvironmentRepository(Regions.DEFAULT, Fake { throw java.io.IOException("x") })
        assertNull(r.weather()); assertNull(r.air())
        assertNull(OpenMeteoEnvironmentRepository(Regions.DEFAULT, Fake { """{"error":true}""" }).weather())
    }
    @Test fun httpRejectsPlainAndOversize() {
        assertThrows(IllegalArgumentException::class.java) { UrlConnectionHttp.get("http://api.open-meteo.com/v1/forecast") }
        assertEquals("abc", UrlConnectionHttp.readLimited(StringReader("abc"), 10))
        assertThrows(IllegalStateException::class.java) { UrlConnectionHttp.readLimited(StringReader("x".repeat(5000)), 100) }
        assertEquals(5_000, UrlConnectionHttp.CONNECT_MS); assertEquals(15_000, UrlConnectionHttp.READ_MS)
    }
    @Test fun regionsAreTenFixed() {
        assertEquals(10, Regions.ALL.size); assertEquals("seoul", Regions.DEFAULT.id); assertEquals(Regions.DEFAULT, Regions.byId("nope"))
        assertEquals(Regions.ALL.size, Regions.ALL.map { it.id }.toSet().size)
    }

    // ---- 폴백·캐시·동의 (EnvFeed) ----
    private class Src(var w: Weather? = Weather.RAIN, var a: AirGrade? = AirGrade.BAD) : EnvironmentRepository {
        var calls = 0
        override suspend fun weather(): Weather? { calls++; return w }
        override suspend fun air(): AirGrade? { calls++; return a }
    }
    private var now = 1_000_000_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private fun feed(src: Src, onRegion: (Region) -> Unit = {}) = EnvFeed({ onRegion(it); src }, { now }, utc)

    @Test fun noCallBeforeConsent() = runBlocking {
        val s = Src(); val f = feed(s)
        assertFalse(f.refresh()); assertEquals(0, s.calls)
        val v = f.view(null, null)
        assertEquals(EnvSource.SAMPLE, v.source); assertEquals("기본 샘플", v.badge); assertEquals(Weather.CLEAR, v.weather); assertTrue(v.air.sample)
    }
    @Test fun liveAfterConsent() = runBlocking {
        val s = Src(); val f = feed(s); f.setConsent(true)
        assertTrue(f.refresh()); assertEquals(2, s.calls)
        val v = f.view(null, null)
        assertEquals(EnvSource.LIVE, v.source); assertEquals(Weather.RAIN, v.weather); assertEquals(AirGrade.BAD, v.air.grade); assertFalse(v.air.sample)
        assertTrue(v.badge.startsWith("실시간 · Open-Meteo · 선택 지역")); assertTrue(v.badge.contains("서울"))
    }
    @Test fun demoBeatsLiveBeatsLastBeatsSample() = runBlocking {
        val s = Src(); val f = feed(s); f.setConsent(true); f.refresh()
        var v = f.view(Weather.SNOW, AirGrade.GOOD)
        assertEquals(EnvSource.DEMO, v.source); assertEquals("시연 값", v.badge); assertEquals(Weather.SNOW, v.weather); assertEquals(AirGrade.GOOD, v.air.grade); assertTrue(v.air.sample)
        v = f.view(Weather.SNOW, null) // 날씨만 시연: 대기질은 실시간 값 그대로
        assertEquals(Weather.SNOW, v.weather); assertEquals(AirGrade.BAD, v.air.grade); assertFalse(v.air.sample)
        assertEquals(EnvSource.LIVE, f.view(null, null).source)
        now += 26 * 60_000L // 두 주기 넘게 성공 없음 -> 마지막 값
        v = f.view(null, null)
        assertEquals(EnvSource.LAST, v.source); assertEquals(Weather.RAIN, v.weather); assertEquals(AirGrade.BAD, v.air.grade); assertFalse(v.air.sample)
        assertEquals("마지막 값 · ${String.format("%02d:%02d", (1_000_000_000L / 3_600_000) % 24, (1_000_000_000L / 60_000) % 60)}", v.badge)
        assertEquals(EnvSource.DEMO, f.view(Weather.CLEAR, null).source) // 마지막 값보다 시연이 우선
        assertEquals(EnvSource.SAMPLE, feed(Src()).view(null, null).source) // 한 번도 못 받음
    }
    @Test fun failureKeepsLastSuccess() = runBlocking {
        val s = Src(); val f = feed(s); f.setConsent(true); f.refresh()
        s.w = null; s.a = null; now += 10 * 60_000L
        f.refresh()
        var v = f.view(null, null)
        assertEquals(Weather.RAIN, v.weather); assertEquals(AirGrade.BAD, v.air.grade); assertEquals(EnvSource.LIVE, v.source) // 10분 이내 실패: 값 유지
        assertEquals(1_000_000_000L, v.air.atMs) // 대기질 시각은 갱신되지 않아 신선도 규칙이 오래된 값을 막는다
        now += 20 * 60_000L; f.refresh(); v = f.view(null, null)
        assertEquals(EnvSource.LAST, v.source); assertEquals(Weather.RAIN, v.weather)
        s.w = Weather.CLEAR; f.refresh() // 부분 성공: 날씨만 갱신, 대기질은 이전 값 유지
        v = f.view(null, null); assertEquals(Weather.CLEAR, v.weather); assertEquals(AirGrade.BAD, v.air.grade); assertEquals(EnvSource.LAST, v.source)
    }
    @Test fun throwingRepositoryKeepsLastSuccess() = runBlocking {
        var boom = false
        val f = EnvFeed({ object : EnvironmentRepository {
            override suspend fun weather(): Weather? { if (boom) throw RuntimeException("x"); return Weather.SNOW }
            override suspend fun air(): AirGrade? { if (boom) throw RuntimeException("x"); return AirGrade.GOOD }
        } }, { now }, utc)
        f.setConsent(true); f.refresh(); boom = true; f.refresh()
        assertEquals(Weather.SNOW, f.view(null, null).weather)
    }
    @Test fun revokeAndRegionChangeDropValuesAndStopCalls() = runBlocking {
        val s = Src(); val seen = mutableListOf<String>(); val f = feed(s) { seen += it.id }
        f.setConsent(true); f.refresh(); assertEquals(EnvSource.LIVE, f.view(null, null).source)
        f.setRegion(Regions.byId("jeju")); assertEquals(EnvSource.SAMPLE, f.view(null, null).source)
        f.refresh(); assertEquals("jeju", seen.last()); assertTrue(f.view(null, null).badge.contains("제주"))
        val before = s.calls
        f.setConsent(false); assertEquals(EnvSource.SAMPLE, f.view(null, null).source)
        assertFalse(f.refresh()); assertEquals(before, s.calls)
    }

    // ---- 동의 저장 ----
    @Test fun netPrefsRoundTripAndDefaults() {
        val dir = Files.createTempDirectory("np").toFile()
        val p = NetPrefs(LocalJsonStore(dir))
        assertEquals(NetPrefsData(), p.load()); assertFalse(p.load().consent); assertFalse(p.load().decided)
        assertTrue(p.save(NetPrefsData(decided = true, consent = true, regionId = "busan")))
        assertEquals(NetPrefsData(true, true, "busan"), p.load())
        p.save(NetPrefsData(decided = true, consent = false, regionId = "jeju")); assertEquals(NetPrefsData(true, false, "jeju"), p.load())
        java.io.File(dir, "myclimate_net.json").writeText("{broken"); assertEquals(NetPrefsData(), p.load()) // 손상 = 미동의
        java.io.File(dir, "myclimate_net.json").writeText("""{"decided":true,"consent":true,"region":"zzz"}"""); assertEquals("seoul", p.load().regionId)
        assertFalse(java.io.File(dir, "myclimate_state.json").exists()) // 상태 저장 파일과 분리
    }

    // ---- 동의 철회 경계 ----
    @Test fun midwayRevokeSkipsSecondCall() = runBlocking {
        lateinit var f: EnvFeed
        val h = HttpGet { url -> urls += url; f.setConsent(false); """{"current":{"weather_code":0}}""" }
        f = EnvFeed({ OpenMeteoEnvironmentRepository(it, h) }, { now }, utc)
        f.setConsent(true); f.refresh()
        assertEquals(1, urls.size); assertTrue(WEATHER in urls[0]); assertEquals(EnvSource.SAMPLE, f.view(null, null).source)
    }
    @Test fun midwayRegionChangeSkipsSecondCall() = runBlocking {
        lateinit var f: EnvFeed
        val h = HttpGet { url -> urls += url; f.setRegion(Regions.byId("jeju")); """{"current":{"weather_code":0}}""" }
        f = EnvFeed({ OpenMeteoEnvironmentRepository(it, h) }, { now }, utc)
        f.setConsent(true); f.refresh()
        assertEquals(1, urls.size); assertTrue("latitude=37.5665" in urls[0])
    }
    private val urls = mutableListOf<String>()
    private val WEATHER = OpenMeteoEnvironmentRepository.WEATHER_URL

    @Test fun userAgentHasNoDeviceInfoAndNoCookie() {
        val h = RequestHeaders.FIXED
        assertEquals("MyClimate", h["User-Agent"]); assertEquals(setOf("User-Agent", "Accept"), h.keys)
        assertFalse(Regex("Dalvik|Android|Linux|Build/", RegexOption.IGNORE_CASE).containsMatchIn(RequestHeaders.USER_AGENT))
        assertTrue(h.keys.none { it.equals("Cookie", true) })
    }

    @Test fun partialSuccessBadgeIsPerItem() = runBlocking {
        val s = Src(a = null); val f = feed(s); f.setConsent(true); f.refresh()
        val v = f.view(null, null)
        assertEquals("날씨 실시간 · 대기질 샘플", v.badge); assertEquals(EnvSource.SAMPLE, v.source)
        val f2 = feed(Src(w = null)); f2.setConsent(true); f2.refresh()
        assertEquals("날씨 샘플 · 대기질 실시간", f2.view(null, null).badge)
    }

    @Test fun defaultSampleAirNeverDrivesRecircButDemoDoes() {
        val f = feed(Src()); // 동의 없음 -> 기본 샘플
        val sample = f.view(null, null).air
        assertTrue(sample.sample); assertTrue(sample.placeholder); assertFalse(RecircPolicy.airFresh(sample, now))
        // 실시간 나쁨으로 켜진 내기순환(autoOn) -> 철회 -> 기본 샘플(보통): 외기 안내 없음
        val on = RecircState(onSince = now - 60_000L, autoOn = true)
        assertEquals(RecircDecision.None, RecircPolicy.step(on, true, sample, now).second)
        // 시연 값(좋음)은 기존처럼 외기 전환을 시연
        val demo = f.view(null, AirGrade.GOOD).air
        assertTrue(demo.sample); assertFalse(demo.placeholder)
        assertTrue(RecircPolicy.step(on, true, demo, now).second is RecircDecision.ToOutside)
        val bad = f.view(null, AirGrade.BAD).air
        assertTrue(RecircPolicy.step(RecircState(), false, bad, now).second is RecircDecision.ToRecirc)
    }

    @Test fun netWriterFallbacks() {
        val on = NetPrefsData(true, true, "seoul"); val off = NetPrefsData(true, false, "seoul")
        assertEquals(NetWrite.SAVED, NetPrefsWriter({ true }, { false }).write(off))
        assertEquals(NetWrite.FAILED, NetPrefsWriter({ false }, { true }).write(on))
        assertEquals(NetWrite.FALLBACK_CLEARED, NetPrefsWriter({ false }, { true }).write(off))
        assertEquals(NetWrite.FAILED_MAY_REAPPEAR, NetPrefsWriter({ false }, { false }).write(off))
        val dir = Files.createTempDirectory("np2").toFile(); val p = NetPrefs(LocalJsonStore(dir))
        assertTrue(p.clear()); p.save(on); assertTrue(p.clear()); assertEquals(NetPrefsData(), p.load())
    }

    @Test fun clearAllResetsConsentAndStopsCalls() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val f = EnvFeed({ object : EnvironmentRepository {
            override suspend fun weather(): Weather? = Weather.RAIN.also { calls++ }
            override suspend fun air(): AirGrade? = AirGrade.BAD.also { calls++ }
        } }, { now }, utc)
        f.setRegion(Regions.byId("busan")); f.setConsent(true); f.refresh()
        assertEquals(2, calls)
        f.resetAll()
        assertFalse(f.consent); assertEquals(Regions.DEFAULT, f.region)
        assertEquals("기본 샘플", f.view(null, null).badge)
        assertFalse(f.refresh()); assertEquals(2, calls)
    }

    @Test fun netResetFallbacksAndFile() {
        assertEquals(NetWrite.FALLBACK_CLEARED, NetPrefsWriter({ false }, { true }).reset())
        assertEquals(NetWrite.SAVED, NetPrefsWriter({ true }, { false }).reset())
        assertEquals(NetWrite.FAILED_MAY_REAPPEAR, NetPrefsWriter({ false }, { false }).reset())
        val dir = Files.createTempDirectory("np3").toFile(); val p = NetPrefs(LocalJsonStore(dir))
        p.save(NetPrefsData(true, true, "busan")); assertEquals(NetWrite.FALLBACK_CLEARED, NetPrefsWriter(p).reset())
        assertEquals(NetPrefsData(), p.load())
    }

    @Test fun deleteTextMentionsConsent() {
        assertTrue(com.hmgcarapp.myclimate.domain.UiTexts.CLEAR_ALL_DESC.contains("인터넷 사용 동의와 선택 지역도 처음 상태로 돌아가요"))
    }
}
