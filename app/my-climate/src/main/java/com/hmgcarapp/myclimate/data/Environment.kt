package com.hmgcarapp.myclimate.data

import com.hmgcarapp.core.data.DataSourceInfo
import com.hmgcarapp.core.data.DataSourceKind
import com.hmgcarapp.core.data.DataSources
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.myclimate.domain.AirGrade
import com.hmgcarapp.myclimate.domain.Weather
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 날씨/대기질 소스(Repository 패턴). 키가 없으면(release 포함) Fake: 고정 ‘가공 샘플’이며 화면에 그렇게 표시한다.
 * Remote 는 골격이다: 엔드포인트·응답 스키마는 기상청/에어코리아 연동 시 확정(아래 JSON 은 단순화한 가정). 위치는 보내지 않는다(위치 이력 미저장).
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

class RemoteEnvironmentRepository(private val apiKey: String, private val baseUrl: String = "https://example.invalid/env") : EnvironmentRepository {
    private fun get(path: String): String? = try {
        val c = HttpsOnly.require("$baseUrl/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.setRequestProperty("Authorization", "Bearer $apiKey")
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    } catch (t: Throwable) { HmgLog.w("Env", "원격 조회 실패: ${t.javaClass.simpleName}"); null }

    override suspend fun weather() = get("weather")?.let(EnvParser::weather)
    override suspend fun air() = get("air")?.let(EnvParser::air)
}

object EnvironmentRepositories {
    fun create(apiKey: String): Pair<EnvironmentRepository, DataSourceInfo> {
        val sel = DataSources.select("myclimate", apiKey, FakeEnvironmentRepository()) { RemoteEnvironmentRepository(it) }
        val label = if (sel.info.kind == DataSourceKind.FAKE) "가공 샘플(날씨·대기질)" else "원격 골격(엔드포인트 미확정)"
        return sel.repo to DataSourceInfo(sel.info.kind, label)
    }
}
