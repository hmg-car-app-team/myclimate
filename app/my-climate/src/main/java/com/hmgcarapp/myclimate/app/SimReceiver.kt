package com.hmgcarapp.myclimate.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.myclimate.domain.AirGrade
import com.hmgcarapp.myclimate.domain.HabitAction
import com.hmgcarapp.myclimate.domain.Weather

/** 시연 입력 파서(순수). 잘못된 값은 Ignore. */
sealed interface SimField<out T> {
    data class Set<T>(val value: T) : SimField<T>
    data object Clear : SimField<Nothing>
    data object Ignore : SimField<Nothing>
}

object SimInput {
    private fun <T> parse(raw: String?, f: (String) -> T?): SimField<T> =
        if (raw == null) SimField.Ignore else if (raw.lowercase() in setOf("none", "clear", "off")) SimField.Clear else f(raw)?.let { SimField.Set(it) } ?: SimField.Ignore

    fun outside(raw: Any?): SimField<Float> = parse(raw?.toString()) { it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v in -60f..70f } }
    fun hour(raw: Any?): SimField<Int> = parse(raw?.toString()) { it.toIntOrNull()?.takeIf { v -> v in 0..23 } }
    fun weather(raw: String?): SimField<Weather> = parse(raw) { s -> Weather.entries.firstOrNull { it.name.equals(s, true) } }
    fun air(raw: String?): SimField<AirGrade> = parse(raw) { s -> AirGrade.entries.firstOrNull { it.name.equals(s, true) } }

    /** `seat_heat:2` 형식. */
    fun observe(raw: String?): Pair<HabitAction, Int>? {
        val (k, v) = (raw ?: return null).split(":").takeIf { it.size == 2 } ?: return null
        val level = v.toIntOrNull()?.takeIf { it in 1..8 } ?: return null
        val a = when (k.lowercase()) { "seat_heat" -> HabitAction.SEAT_HEAT; "seat_vent" -> HabitAction.SEAT_VENT; "wheel_heat" -> HabitAction.WHEEL_HEAT; "ac" -> HabitAction.AC; "recirc" -> HabitAction.RECIRC; else -> return null }
        return a to level
    }
}

/**
 * adb 시연 주입 수신기(debuggable 전용, DUMP 권한 보유 발신자만; release 에서는 등록하지 않는다).
 * 예: adb shell am broadcast -a com.hmgcarapp.myclimate.SIM --ef outside -3 --ei hour 8 --es weather RAIN --es air BAD
 *     --es observe seat_heat:2 / --ez tick true / --ez smart true / --ei recircAge 11 / --es drive DRIVING
 * 모든 값은 메모리 전용 ‘시뮬레이션’이며 화면에 시연 표시가 뜬다.
 */
internal object SimReceiver {
    const val ACTION = "com.hmgcarapp.myclimate.SIM"
    const val PERMISSION = "android.permission.DUMP"

    private fun <T> SimField<T>.into(key: String, set: (T?) -> Unit) {
        when (this) { is SimField.Set -> set(value); SimField.Clear -> set(null); SimField.Ignore -> HmgLog.w("MyClimate", "SIM 무시: $key (형식 또는 범위 오류)") }
    }

    fun register(context: Context, c: AppController, monoClock: () -> Long): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val ex = i.extras
                if (i.hasExtra("outside")) SimInput.outside(ex?.get("outside")).into("outside", c::simOutside)
                if (i.hasExtra("hour")) SimInput.hour(ex?.get("hour")).into("hour", c::simHour)
                if (i.hasExtra("weather")) SimInput.weather(i.getStringExtra("weather")).into("weather", c::simWeather)
                if (i.hasExtra("air")) SimInput.air(i.getStringExtra("air")).into("air", c::simAir)
                if (i.hasExtra("drive")) c.setDemoDrive(if (i.getStringExtra("drive") == "DRIVING") "DRIVING" else null)
                if (i.hasExtra("smart")) c.simSmart(i.getBooleanExtra("smart", false))
                if (i.hasExtra("recircAge")) (ex?.get("recircAge")?.toString()?.toIntOrNull())?.takeIf { m -> m in 0..600 }?.let(c::simRecircAge) ?: HmgLog.w("MyClimate", "SIM 무시: recircAge")
                if (i.hasExtra("observe")) SimInput.observe(i.getStringExtra("observe"))?.let { (a, l) -> c.simUserTurnedOn(a, l) } ?: HmgLog.w("MyClimate", "SIM 무시: observe")
                if (i.getBooleanExtra("accept", false)) c.acceptSuggestion()
                if (i.getBooleanExtra("dismiss", false)) c.dismissSuggestion()
                if (i.getBooleanExtra("tick", false)) c.simTick()
                HmgLog.i("MyClimate", "SIM 수신: ${ex?.keySet()}")
            }
        }
        val f = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, PERMISSION, null, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f, PERMISSION, null)
        return r
    }
}
