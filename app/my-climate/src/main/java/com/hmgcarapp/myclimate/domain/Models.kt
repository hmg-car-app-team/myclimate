package com.hmgcarapp.myclimate.domain

/** 송풍 방향(SDK FanDirectionType 의 핵심 4종만 노출). */
enum class FanDir(val label: String) { FACE("얼굴"), FLOOR("발"), FACE_FLOOR("얼굴+발"), DEFROST("성에 제거") }
enum class Weather(val label: String) { CLEAR("맑음"), CLOUDY("흐림"), RAIN("비"), SNOW("눈") }
enum class AirGrade(val label: String) { GOOD("좋음"), NORMAL("보통"), BAD("나쁨"), VERY_BAD("매우 나쁨");
    val isBad: Boolean get() = this == BAD || this == VERY_BAD }

/**
 * 공조 설정. null = "건드리지 않음". 프로필 값이자 프리셋 규칙의 패치이자 차량에서 읽은 현재 값으로 모두 쓴다.
 * 운전석(1열 좌) 기준. 시트/핸들 단계: 0=끔, 1..max.
 */
data class ClimateSettings(
    val tempC: Float? = null,
    val fan: Int? = null,
    val fanDir: FanDir? = null,
    val ac: Boolean? = null,
    val recirc: Boolean? = null,
    val seatHeat: Int? = null,
    val seatVent: Int? = null,
    val wheelHeat: Int? = null,
) {
    val isEmpty: Boolean get() = this == ClimateSettings()

    /** [patch] 의 non-null 값으로 덮어쓴다. */
    fun overlay(patch: ClimateSettings) = ClimateSettings(
        patch.tempC ?: tempC, patch.fan ?: fan, patch.fanDir ?: fanDir, patch.ac ?: ac, patch.recirc ?: recirc,
        patch.seatHeat ?: seatHeat, patch.seatVent ?: seatVent, patch.wheelHeat ?: wheelHeat,
    )
}

data class Profile(val id: String, val name: String, val settings: ClimateSettings)

enum class RuleSource(val label: String) { DEFAULT("기본"), USER("직접"), LEARNED("학습") }

/**
 * 상황별 프리셋 규칙. 조건은 모두 AND, 비어 있는(null) 조건은 무시.
 * 외기온도 [minC, maxC) 반열린 구간, 시간대 [fromHour, toHour) 24h(from > to 면 자정 넘김).
 */
data class PresetRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val minC: Float? = null,
    val maxC: Float? = null,
    val fromHour: Int? = null,
    val toHour: Int? = null,
    val weather: Weather? = null,
    val patch: ClimateSettings = ClimateSettings(),
    val source: RuleSource = RuleSource.USER,
)

/** 규칙 평가 시점의 상황. 알 수 없는 값은 null(알 수 없는 조건은 매칭되지 않는다). */
data class Ctx(val outsideC: Float?, val hour: Int, val weather: Weather?)

enum class TempBand(val label: String, val minC: Float?, val maxC: Float?, val short: String) {
    COLD("추운 날", null, 5f, "추운날"), COOL("쌀쌀한 날", 5f, 15f, "쌀쌀한날"), MILD("선선한 날", 15f, 24f, "선선한날"), WARM("따뜻한 날", 24f, 28f, "따뜻한날"), HOT("더운 날", 28f, null, "더운날");
    companion object {
        fun of(c: Float): TempBand = entries.first { (it.minC == null || c >= it.minC) && (it.maxC == null || c < it.maxC) }
    }
}

enum class TimeSlot(val label: String, val from: Int, val to: Int) {
    NIGHT("밤", 22, 5), MORNING("아침", 5, 11), DAY("낮", 11, 17), EVENING("저녁", 17, 22);
    companion object {
        fun of(hour: Int): TimeSlot { val h = ((hour % 24) + 24) % 24; return if (h >= 22 || h < 5) NIGHT else entries.first { it != NIGHT && h >= it.from && h < it.to } }
    }
}

/** 습관 학습 대상(‘켜기’ 조작). */
enum class HabitAction(val label: String, val short: String) {
    SEAT_HEAT("시트 열선", "시트열선"), SEAT_VENT("시트 통풍", "시트통풍"), WHEEL_HEAT("핸들 열선", "핸들열선"), AC("에어컨", "에어컨"), RECIRC("내기 순환", "내기순환");
}

data class HabitKey(val action: HabitAction, val band: TempBand, val slot: TimeSlot) {
    val id: String get() = "${action.name}:${band.name}:${slot.name}"
}

/** 같은 상황에서 ‘켜기’를 한 서로 다른 날짜(epoch day, 오름차순)와 마지막으로 켠 단계. 시각·위치는 저장하지 않는다(날짜 번호만). */
data class HabitStat(val key: HabitKey, val days: List<Long>, val level: Int)

data class Suggestion(val key: HabitKey, val level: Int) {
    private val withLevel: Boolean get() = key.action == HabitAction.SEAT_HEAT || key.action == HabitAction.SEAT_VENT || key.action == HabitAction.WHEEL_HEAT
    val text: String get() = "${key.band.label} ${key.slot.label}에 ${key.action.label}${if (withLevel) " ${level}단계" else ""}을(를) 자주 켜셨어요. 이 조건에서 자동으로 켤까요?"
}

/** 로컬 저장 대상. 위치·음성은 저장하지 않는다. 습관 통계에는 관측 날짜 번호(최대 14일), 거절 목록에는 재제안 금지 만료 시각(에포크 ms)이 들어간다. */
data class Persisted(
    val profiles: List<Profile> = emptyList(),
    val selectedId: String? = null,
    val autoApply: Boolean = true,
    val smartRecirc: Boolean = false,
    val rules: List<PresetRule> = DefaultRules.all,
    val habits: List<HabitStat> = emptyList(),
    /** 거절한 제안 키 -> 이 시각(벽시계 ms) 전까지 다시 제안하지 않음. */
    val dismissed: Map<String, Long> = emptyMap(),
) {
    val selected: Profile? get() = profiles.firstOrNull { it.id == selectedId }
}

object DefaultRules {
    val all: List<PresetRule> = listOf(
        PresetRule("def_hot", "폭염: 시원하게", minC = 28f, patch = ClimateSettings(tempC = 22f, ac = true, fan = 4, seatVent = 2), source = RuleSource.DEFAULT),
        PresetRule("def_cold", "한파: 따뜻하게", maxC = 0f, patch = ClimateSettings(tempC = 24f, seatHeat = 3, wheelHeat = 2), source = RuleSource.DEFAULT),
        PresetRule("def_rain", "비/눈: 김서림 방지", weather = Weather.RAIN, patch = ClimateSettings(ac = true, recirc = false), source = RuleSource.DEFAULT),
        PresetRule("def_snow", "눈: 김서림 방지", weather = Weather.SNOW, patch = ClimateSettings(ac = true, recirc = false), source = RuleSource.DEFAULT),
        PresetRule("def_night", "심야: 약하게", fromHour = 23, toHour = 5, patch = ClimateSettings(fan = 2), source = RuleSource.DEFAULT),
    )
}
