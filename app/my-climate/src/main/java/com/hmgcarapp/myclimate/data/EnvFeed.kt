package com.hmgcarapp.myclimate.data

import com.hmgcarapp.myclimate.domain.AirGrade
import com.hmgcarapp.myclimate.domain.AirReading
import com.hmgcarapp.myclimate.domain.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 화면·규칙이 쓰는 환경 값과 출처 표기. */
data class EnvView(val weather: Weather, val air: AirReading, val badge: String, val source: EnvSource)

enum class EnvSource { DEMO, LIVE, LAST, SAMPLE }

/**
 * 실시간 값 보관·폴백 결정(순수 로직 + 주입된 소스). 우선순위: 시연 선택 > 실시간 > 마지막 성공값 > 기본 샘플.
 * - 동의([setConsent]) 전에는 외부 호출을 하지 않는다. 철회하거나 지역을 바꾸면 이전 값을 버린다.
 * - 호출 실패는 마지막 성공값을 지우지 않는다. 성공 후 [LIVE_FRESH_MS] 가 지나면 ‘마지막 값’으로 표시한다.
 * - 기본 샘플: 맑음 / 보통(대기질은 sample=true 라 내기순환 자동 전환에 쓰이지 않는다).
 * 메인 스레드에서 쓰고 읽는다. 네트워크 호출만 IO 디스패처에서 한다.
 */
class EnvFeed(
    private val repoFor: (Region) -> EnvironmentRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private data class Stamped<T>(val v: T, val atMs: Long)

    @Volatile var consent: Boolean = false; private set
    @Volatile var region: Region = Regions.DEFAULT; private set
    @Volatile private var generation = 0
    private var weather: Stamped<Weather>? = null
    private var air: Stamped<AirGrade>? = null

    fun setConsent(on: Boolean) { if (consent != on) { consent = on; reset() } }
    fun setRegion(r: Region) { if (region != r) { region = r; reset() } }
    /** 삭제 시 처음 상태로: 동의 없음·기본 지역·받은 값 폐기. 진행 중 호출 결과는 세대 번호로 버려진다. */
    fun resetAll() { consent = false; region = Regions.DEFAULT; reset() }
    private fun reset() { generation++; weather = null; air = null }

    /** 동의했을 때만 호출한다. 네트워크를 썼으면 true. */
    suspend fun refresh(): Boolean {
        if (!consent) return false
        val gen = generation
        val repo = repoFor(region)
        val (w, a) = withContext(Dispatchers.IO) {
            val w0 = runCatching { repo.weather() }.getOrNull()
            // 블로킹 호출엔 취소점이 없으므로 두 호출 사이에서 철회·지역 변경을 다시 확인한다
            val a0 = if (consent && gen == generation) runCatching { repo.air() }.getOrNull() else null
            w0 to a0
        }
        if (!consent || gen != generation) return true // 도중에 철회/지역 변경: 결과를 버린다
        val now = clock()
        if (w != null) weather = Stamped(w, now)
        if (a != null) air = Stamped(a, now)
        return true
    }

    private fun sourceOf(s: Stamped<*>?, now: Long) = when {
        s == null -> EnvSource.SAMPLE
        now - s.atMs <= LIVE_FRESH_MS -> EnvSource.LIVE
        else -> EnvSource.LAST
    }

    fun view(simWeather: Weather?, simAir: AirGrade?): EnvView {
        val now = clock()
        val w = weather; val a = air
        val wSrc = sourceOf(w, now); val aSrc = sourceOf(a, now)
        val weatherNow = simWeather ?: w?.v ?: SAMPLE_WEATHER
        val airNow = simAir?.let { AirReading(it, now, sample = true) }
            ?: a?.let { AirReading(it.v, it.atMs, sample = false) }
            ?: AirReading(SAMPLE_AIR, now, sample = true, placeholder = true)
        val demo = simWeather != null || simAir != null
        val worst = if (wSrc.ordinal >= aSrc.ordinal) wSrc else aSrc // LIVE < LAST < SAMPLE (DEMO 는 별도)
        val source = if (demo) EnvSource.DEMO else worst
        val badge = when (source) {
            EnvSource.DEMO -> "시연 값"
            else -> if (wSrc != aSrc) "날씨 ${itemLabel(wSrc, w)} · 대기질 ${itemLabel(aSrc, a)}" else when (source) {
                EnvSource.LIVE -> "실시간 · Open-Meteo · 선택 지역(${region.label})"
                EnvSource.LAST -> "마지막 값 · ${hhmm(listOfNotNull(w?.atMs, a?.atMs).min())}"
                else -> "기본 샘플"
            }
        }
        return EnvView(weatherNow, airNow, badge, source)
    }

    private fun itemLabel(src: EnvSource, s: Stamped<*>?) = when (src) {
        EnvSource.LIVE -> "실시간"
        EnvSource.LAST -> "마지막 값 ${hhmm(s!!.atMs)}"
        else -> "샘플"
    }

    private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).apply { timeZone = zone }.format(ms)

    companion object {
        /** 10분 주기에서 한 번 실패해도 ‘실시간’으로 본다(2주기 이상 비면 ‘마지막 값’). */
        const val LIVE_FRESH_MS = 25 * 60_000L
        val SAMPLE_WEATHER = Weather.CLEAR
        val SAMPLE_AIR = AirGrade.NORMAL
    }
}
