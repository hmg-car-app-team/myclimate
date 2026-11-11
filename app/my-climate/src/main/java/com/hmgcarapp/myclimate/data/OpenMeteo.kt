package com.hmgcarapp.myclimate.data

import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.myclimate.domain.AirGrade
import com.hmgcarapp.myclimate.domain.Weather
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.util.Locale

/** 선택 가능한 지역. 외부로 나가는 위치는 이 대표 좌표뿐이다(기기 위치·식별자 없음). */
data class Region(val id: String, val label: String, val lat: Double, val lon: Double)

object Regions {
    val ALL: List<Region> = listOf(
        Region("seoul", "서울", 37.5665, 126.9780),
        Region("busan", "부산", 35.1796, 129.0756),
        Region("incheon", "인천", 37.4563, 126.7052),
        Region("daegu", "대구", 35.8714, 128.6014),
        Region("daejeon", "대전", 36.3504, 127.3845),
        Region("gwangju", "광주", 35.1595, 126.8526),
        Region("ulsan", "울산", 35.5384, 129.3114),
        Region("sejong", "세종", 36.4801, 127.2890),
        Region("suwon", "수원", 37.2636, 127.0286),
        Region("jeju", "제주", 33.4996, 126.5312),
    )
    val DEFAULT: Region = ALL.first()
    fun byId(id: String?): Region = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/** HTTP GET 추상화(테스트에서 가짜로 교체). 성공(200)이면 본문, 그 밖의 모든 경우 예외. 백그라운드 스레드에서만 부른다. */
fun interface HttpGet { fun get(url: String): String }

/** 요청 헤더 고정값. 기기·앱 식별 정보(Android 기본 User-Agent 의 모델·빌드 등)와 쿠키를 보내지 않는다. */
object RequestHeaders {
    const val USER_AGENT = "MyClimate"
    val FIXED: Map<String, String> = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
}

/** 표준 라이브러리 구현: https 고정, 연결 5초·읽기 15초, 재시도 없음, 리다이렉트 안 따름, 응답 크기 상한. */
object UrlConnectionHttp : HttpGet {
    const val CONNECT_MS = 5_000
    const val READ_MS = 15_000
    const val MAX_BYTES = 64 * 1024

    override fun get(url: String): String {
        val c = HttpsOnly.require(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_MS; c.readTimeout = READ_MS; c.instanceFollowRedirects = false; c.requestMethod = "GET"
            RequestHeaders.FIXED.forEach { (k, v) -> c.setRequestProperty(k, v) }
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { readLimited(InputStreamReader(it, Charsets.UTF_8), MAX_BYTES) }
        } finally { c.disconnect() }
    }

    internal fun readLimited(r: java.io.Reader, max: Int): String {
        val sb = StringBuilder(); val buf = CharArray(2048)
        while (true) {
            val n = r.read(buf); if (n < 0) break
            sb.append(buf, 0, n); check(sb.length <= max) { "응답이 너무 큼" }
        }
        return sb.toString()
    }
}

/** Open-Meteo 응답 해석(순수 함수). 오류 응답·형식 오류·필드 누락은 null. */
object OpenMeteoParser {
    private val SNOW_CODES = setOf(71, 72, 73, 75, 77, 85, 86)
    private val RAIN_CODES = setOf(51, 52, 53, 54, 55, 56, 57, 61, 62, 63, 64, 65, 66, 67, 80, 81, 82, 95, 96, 97, 98, 99)
    private val CLOUDY_CODES = setOf(2, 3, 45, 48)
    private val CLEAR_CODES = setOf(0, 1)

    /** 눈 우선 -> 비(진눈깨비·어는 비 포함) -> 흐림 -> 맑음. 그 외/누락은 null. */
    fun weatherOf(code: Int?, snowfall: Double?, rain: Double?, showers: Double?): Weather? = when {
        (snowfall ?: 0.0) > 0.0 || code in SNOW_CODES -> Weather.SNOW
        code in RAIN_CODES || (rain ?: 0.0) + (showers ?: 0.0) > 0.0 -> Weather.RAIN
        code in CLOUDY_CODES -> Weather.CLOUDY
        code in CLEAR_CODES -> Weather.CLEAR
        else -> null
    }

    /** 환경부 4단계. PM10 30/80/150, PM2.5 15/35/75 ㎍/㎥(반올림한 정수 비교). 두 항목 중 더 나쁜 등급. 둘 다 없으면 null. */
    fun gradeOf(pm10: Double?, pm25: Double?): AirGrade? {
        val g10 = pm10?.let { band(it, 30, 80, 150) }
        val g25 = pm25?.let { band(it, 15, 35, 75) }
        return listOfNotNull(g10, g25).maxByOrNull { it.ordinal }
    }

    private fun band(v: Double, good: Int, normal: Int, bad: Int): AirGrade? {
        if (v.isNaN() || v < 0.0) return null
        val r = Math.round(v)
        return when { r <= good -> AirGrade.GOOD; r <= normal -> AirGrade.NORMAL; r <= bad -> AirGrade.BAD; else -> AirGrade.VERY_BAD }
    }

    private fun current(json: String): JSONObject? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("error", false)) return null
        return o.optJSONObject("current")
    }

    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k, Double.NaN).takeIf { !it.isNaN() } else null

    fun weather(json: String): Weather? {
        val c = current(json) ?: return null
        return weatherOf(c.num("weather_code")?.toInt(), c.num("snowfall"), c.num("rain"), c.num("showers"))
    }

    fun air(json: String): AirGrade? {
        val c = current(json) ?: return null
        return gradeOf(c.num("pm10"), c.num("pm2_5"))
    }
}

/** 선택 지역 대표 좌표로 Open-Meteo 날씨·대기질을 받는다. 실패는 모두 null(다음 주기에 다시 시도). */
class OpenMeteoEnvironmentRepository(private val region: Region, private val http: HttpGet = UrlConnectionHttp) : EnvironmentRepository {
    private fun coords() = String.format(Locale.US, "latitude=%.4f&longitude=%.4f", region.lat, region.lon)

    private fun fetch(base: String, current: String): String? = try {
        http.get("$base?${coords()}&current=$current&timezone=Asia%2FSeoul")
    } catch (t: Throwable) { HmgLog.w("Env", "Open-Meteo 조회 실패: ${t.javaClass.simpleName}"); null }

    override suspend fun weather(): Weather? = fetch(WEATHER_URL, "weather_code,rain,showers,snowfall")?.let(OpenMeteoParser::weather)
    override suspend fun air(): AirGrade? = fetch(AIR_URL, "pm10,pm2_5")?.let(OpenMeteoParser::air)

    companion object {
        const val WEATHER_URL = "https://api.open-meteo.com/v1/forecast"
        const val AIR_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"
    }
}
