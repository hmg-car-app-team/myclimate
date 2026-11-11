package com.hmgcarapp.myclimate.app

import com.hmgcarapp.myclimate.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** 저장 JSON <-> [Persisted]. 값은 모두 정리(sanitize)해서 읽는다. 위치·음성·조작 시각열은 저장하지 않는다(습관 날짜 번호와 거절 만료 시각만). */
object StateCodec {
    const val VERSION = 1
    class Parsed(val p: Persisted, val corrupt: Boolean)

    private fun <E : Enum<E>> enumOf(values: Array<E>, name: String?): E? = values.firstOrNull { it.name == name }

    fun encodeSettings(s: ClimateSettings) = JSONObject().apply {
        s.tempC?.let { put("tempC", it.toDouble()) }; s.fan?.let { put("fan", it) }; s.fanDir?.let { put("fanDir", it.name) }
        s.ac?.let { put("ac", it) }; s.recirc?.let { put("recirc", it) }
        s.seatHeat?.let { put("seatHeat", it) }; s.seatVent?.let { put("seatVent", it) }; s.wheelHeat?.let { put("wheelHeat", it) }
    }

    fun decodeSettings(o: JSONObject?): ClimateSettings {
        if (o == null) return ClimateSettings()
        fun i(k: String) = if (o.has(k) && !o.isNull(k)) o.optInt(k, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
        return Limits.DEFAULT.clamp(ClimateSettings(
            tempC = if (o.has("tempC")) o.optDouble("tempC", Double.NaN).toFloat().takeIf { it.isFinite() } else null,
            fan = i("fan"), fanDir = enumOf(FanDir.entries.toTypedArray(), o.optString("fanDir", "")),
            ac = if (o.has("ac")) o.optBoolean("ac") else null, recirc = if (o.has("recirc")) o.optBoolean("recirc") else null,
            seatHeat = i("seatHeat"), seatVent = i("seatVent"), wheelHeat = i("wheelHeat"),
        ))
    }

    fun encode(p: Persisted): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("selectedId", p.selectedId ?: JSONObject.NULL)
        put("autoApply", p.autoApply); put("smartRecirc", p.smartRecirc); put("feelKeep", p.feelKeep)
        put("profiles", JSONArray().also { a -> p.profiles.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("s", encodeSettings(it.settings))) } })
        put("rules", JSONArray().also { a -> p.rules.forEach { r ->
            a.put(JSONObject().put("id", r.id).put("name", r.name).put("enabled", r.enabled).put("source", r.source.name).put("patch", encodeSettings(r.patch)).also { o ->
                r.minC?.let { o.put("minC", it.toDouble()) }; r.maxC?.let { o.put("maxC", it.toDouble()) }
                r.fromHour?.let { o.put("from", it) }; r.toHour?.let { o.put("to", it) }; r.weather?.let { o.put("weather", it.name) }
            })
        } })
        put("habits", JSONArray().also { a -> p.habits.forEach { h ->
            a.put(JSONObject().put("a", h.key.action.name).put("b", h.key.band.name).put("t", h.key.slot.name).put("l", h.level).put("d", JSONArray(h.days)))
        } })
        put("dismissed", JSONObject().also { o -> p.dismissed.forEach { (k, v) -> o.put(k, v) } })
    }

    fun parse(text: String): Parsed {
        val o = try { JSONObject(text) } catch (_: Throwable) { return Parsed(Persisted(), corrupt = true) }
        return try { Parsed(decode(o), corrupt = false) } catch (_: Throwable) { Parsed(Persisted(), corrupt = true) }
    }

    private fun decode(o: JSONObject): Persisted {
        val profiles = ArrayList<Profile>()
        o.optJSONArray("profiles")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                val id = x.optString("id", "").takeIf { it.matches(Regex("[A-Za-z0-9_]{1,16}")) && profiles.none { p -> p.id == it } } ?: continue
                if (profiles.size >= Edits.MAX_PROFILES) break
                profiles += Profile(id, Edits.cleanName(x.optString("name", ""), "프로필"), decodeSettings(x.optJSONObject("s")))
            }
        }
        val rules = ArrayList<PresetRule>()
        val ra = o.optJSONArray("rules")
        if (ra == null) rules += DefaultRules.all
        else for (i in 0 until ra.length()) {
            val x = ra.optJSONObject(i) ?: continue
            val id = x.optString("id", "").takeIf { it.isNotEmpty() && it.length <= 60 && rules.none { r -> r.id == it } } ?: continue
            if (rules.size >= Edits.MAX_RULES) break
            fun f(k: String) = if (x.has(k)) x.optDouble(k, Double.NaN).toFloat().takeIf { it.isFinite() } else null
            fun h(k: String) = if (x.has(k)) x.optInt(k, -1).takeIf { it >= 0 } else null
            rules += Edits.sanitizeRule(PresetRule(
                id, x.optString("name", ""), x.optBoolean("enabled", true), f("minC"), f("maxC"), h("from"), h("to"),
                enumOf(Weather.entries.toTypedArray(), x.optString("weather", "")), decodeSettings(x.optJSONObject("patch")),
                enumOf(RuleSource.entries.toTypedArray(), x.optString("source", "")) ?: RuleSource.USER,
            ))
        }
        val habits = ArrayList<HabitStat>()
        o.optJSONArray("habits")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                val act = enumOf(HabitAction.entries.toTypedArray(), x.optString("a", "")) ?: continue
                val band = enumOf(TempBand.entries.toTypedArray(), x.optString("b", "")) ?: continue
                val slot = enumOf(TimeSlot.entries.toTypedArray(), x.optString("t", "")) ?: continue
                val days = ArrayList<Long>(); x.optJSONArray("d")?.let { d -> for (j in 0 until d.length()) days += d.optLong(j, -1L).takeIf { it >= 0 } ?: continue }
                if (habits.size >= HabitLearner.MAX_STATS) break
                habits += HabitStat(HabitKey(act, band, slot), days.distinct().sorted().takeLast(HabitLearner.WINDOW_DAYS), x.optInt("l", 1).coerceIn(0, 8))
            }
        }
        val dismissed = HashMap<String, Long>()
        o.optJSONObject("dismissed")?.let { d -> d.keys().forEach { k -> if (dismissed.size < 40) dismissed[k] = d.optLong(k, 0L) } }
        val sel = o.optString("selectedId", "").takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id
        return Persisted(profiles, sel, o.optBoolean("autoApply", true), o.optBoolean("smartRecirc", false), o.optBoolean("feelKeep", false), rules, habits, dismissed)
    }
}
