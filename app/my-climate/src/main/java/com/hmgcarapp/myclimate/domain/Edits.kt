package com.hmgcarapp.myclimate.domain

/** 값 범위(차량 capability 를 알면 그 범위, 모르면 기본 가정). */
data class Limits(
    val tempMin: Float = 16f, val tempMax: Float = 32f,
    val fanMin: Int = 1, val fanMax: Int = 8,
    val seatHeatMax: Int = 3, val seatVentMax: Int = 3, val wheelHeatMax: Int = 3,
) {
    fun clamp(s: ClimateSettings) = ClimateSettings(
        tempC = s.tempC?.takeIf { it.isFinite() }?.let { (Math.round(it.coerceIn(tempMin, tempMax) * 2) / 2f) },
        fan = s.fan?.coerceIn(fanMin, fanMax), fanDir = s.fanDir, ac = s.ac, recirc = s.recirc,
        seatHeat = s.seatHeat?.coerceIn(0, seatHeatMax), seatVent = s.seatVent?.coerceIn(0, seatVentMax), wheelHeat = s.wheelHeat?.coerceIn(0, wheelHeatMax),
    )
    companion object { val DEFAULT = Limits() }
}

/** 프로필·규칙 CRUD 와 입력 정리(순수). */
object Edits {
    const val MAX_PROFILES = 8
    const val MAX_RULES = 20
    const val MAX_NAME = 12

    fun cleanName(raw: String, fallback: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).ifEmpty { fallback }

    fun newId(prefix: String, existing: Collection<String>): String {
        var n = existing.size + 1
        while ("$prefix$n" in existing) n++
        return "$prefix$n"
    }

    /** 새 프로필 추가(최대 [MAX_PROFILES]). 첫 프로필이면 자동 선택. 가득 차면 null. */
    fun addProfile(p: Persisted, name: String, settings: ClimateSettings): Persisted? {
        if (p.profiles.size >= MAX_PROFILES) return null
        val id = newId("p", p.profiles.map { it.id })
        val prof = Profile(id, cleanName(name, "프로필 ${p.profiles.size + 1}"), Limits.DEFAULT.clamp(settings))
        return p.copy(profiles = p.profiles + prof, selectedId = p.selectedId ?: id)
    }

    fun updateProfile(p: Persisted, id: String, f: (Profile) -> Profile): Persisted =
        p.copy(profiles = p.profiles.map { if (it.id == id) f(it).let { n -> n.copy(id = id, name = cleanName(n.name, it.name), settings = Limits.DEFAULT.clamp(n.settings)) } else it })

    fun deleteProfile(p: Persisted, id: String): Persisted {
        val left = p.profiles.filter { it.id != id }
        return p.copy(profiles = left, selectedId = if (p.selectedId == id) left.firstOrNull()?.id else p.selectedId)
    }

    fun select(p: Persisted, id: String?): Persisted = if (id == null || p.profiles.any { it.id == id }) p.copy(selectedId = id) else p

    /** 규칙 추가/교체. 시간대 조건은 from/to 가 모두 있고 서로 다를 때만 유효, 온도 구간은 min < max 일 때만(아니면 해당 조건 제거). */
    fun upsertRule(p: Persisted, r: PresetRule): Persisted? {
        val fixed = sanitizeRule(r)
        val exists = p.rules.any { it.id == fixed.id }
        if (!exists && p.rules.size >= MAX_RULES) return null
        return p.copy(rules = if (exists) p.rules.map { if (it.id == fixed.id) fixed else it } else p.rules + fixed)
    }

    fun deleteRule(p: Persisted, id: String) = p.copy(rules = p.rules.filter { it.id != id })
    fun toggleRule(p: Persisted, id: String) = p.copy(rules = p.rules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })

    fun sanitizeRule(r: PresetRule): PresetRule {
        var min = r.minC?.takeIf { it.isFinite() }; var max = r.maxC?.takeIf { it.isFinite() }
        if (min != null && max != null && min >= max) { min = null; max = null }
        var from = r.fromHour; var to = r.toHour
        if (from == null || to == null || from == to || from !in 0..23 || to !in 0..24 || (from == 0 && to == 24)) { from = null; to = null }
        return r.copy(name = cleanName(r.name, "규칙"), minC = min, maxC = max, fromHour = from, toHour = to?.let { it % 24 }, patch = Limits.DEFAULT.clamp(r.patch))
    }
}
