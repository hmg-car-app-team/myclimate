package com.hmgcarapp.myclimate.data

import com.hmgcarapp.myclimate.domain.AirGrade
import com.hmgcarapp.myclimate.domain.Weather
import org.json.JSONObject
import java.net.URL

/**
 * 날씨/대기질 소스(Repository 패턴). 실제 소스는 Open-Meteo(키 없음, [OpenMeteoEnvironmentRepository]).
 * Fake 는 테스트/기본 샘플 값 자리표시용이다. 기기 위치는 쓰지 않는다: 보내는 것은 사용자가 고른 지역의 대표 좌표뿐.
 */
interface EnvironmentRepository {
    suspend fun weather(): Weather?
    suspend fun air(): AirGrade?
}

class FakeEnvironmentRepository(private val w: Weather? = Weather.CLEAR, private val a: AirGrade? = AirGrade.NORMAL) : EnvironmentRepository {
    override suspend fun weather() = w
    override suspend fun air() = a
}

object HttpsOnly {
    fun require(url: String): URL { require(url.startsWith("https://")) { "HTTPS 만 허용" }; return URL(url) }
}

/** 단순화한 스키마 가정: {"sky":"CLEAR|CLOUDY|RAIN|SNOW","pm25":int}. 형식이 다르면 null(알 수 없음). */
object EnvParser {
    fun weather(json: String): Weather? = runCatching { Weather.valueOf(JSONObject(json).getString("sky")) }.getOrNull()
    fun air(json: String): AirGrade? = runCatching { grade(JSONObject(json).getInt("pm25")) }.getOrNull()
    /** 환경부 PM2.5 24h 기준(좋음 0-15, 보통 16-35, 나쁨 36-75, 매우 나쁨 76+). */
    fun grade(pm25: Int): AirGrade? = when { pm25 < 0 -> null; pm25 <= 15 -> AirGrade.GOOD; pm25 <= 35 -> AirGrade.NORMAL; pm25 <= 75 -> AirGrade.BAD; else -> AirGrade.VERY_BAD }
}

object EnvironmentRepositories {
    /** 키 없는 Open-Meteo 소스. 호출 시점의 선택 지역 대표 좌표만 보낸다(동의 확인은 [EnvFeed] 가 한다). */
    fun create(region: Region, http: HttpGet = UrlConnectionHttp): EnvironmentRepository = OpenMeteoEnvironmentRepository(region, http)
}
