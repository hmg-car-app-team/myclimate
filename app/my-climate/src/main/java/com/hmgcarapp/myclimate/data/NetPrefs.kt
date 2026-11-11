package com.hmgcarapp.myclimate.data

import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.core.store.LocalJsonStore
import com.hmgcarapp.core.store.StoreLoad
import org.json.JSONObject

/** 네트워크 사용 동의·선택 지역. 프로필/규칙 저장소와 분리한 별도 파일(`myclimate_net`). */
data class NetPrefsData(val decided: Boolean = false, val consent: Boolean = false, val regionId: String = Regions.DEFAULT.id)

class NetPrefs(private val store: LocalJsonStore, private val name: String = "myclimate_net") {
    /** 읽기 실패/없음/손상 = 미결정(동의 없음)으로 취급한다. 동의는 명시적으로 저장된 true 만 인정. */
    fun load(): NetPrefsData = try {
        when (val l = store.loadResult(name)) {
            is StoreLoad.Ok -> JSONObject(l.text).let {
                val consent = it.optBoolean("consent", false)
                NetPrefsData(decided = it.optBoolean("decided", false) || consent, consent = consent, regionId = Regions.byId(it.optString("region", "")).id)
            }
            else -> NetPrefsData()
        }
    } catch (t: Throwable) { HmgLog.w("Net", "설정 읽기 실패 -> 미동의: ${t.javaClass.simpleName}"); NetPrefsData() }

    /** 파일 삭제(= 미결정·미동의로 읽힘). */
    fun clear(): Boolean = try { store.delete(name) || !store.exists(name) } catch (t: Throwable) { false }

    fun save(d: NetPrefsData): Boolean = try {
        store.saveText(name, JSONObject().put("decided", d.decided).put("consent", d.consent).put("region", d.regionId).toString()); true
    } catch (t: Throwable) { HmgLog.w("Net", "설정 저장 실패: ${t.javaClass.simpleName}"); false }
}

enum class NetWrite { SAVED, FALLBACK_CLEARED, FAILED, FAILED_MAY_REAPPEAR }

/** 저장/삭제 폴백 규칙(순수). 철회 저장이 실패하면 파일 삭제로 폴백하고, 그것도 실패하면 ‘다시 켜질 수 있음’. 호출 쪽이 직렬화한다. */
class NetPrefsWriter(private val save: (NetPrefsData) -> Boolean, private val clear: () -> Boolean) {
    constructor(p: NetPrefs) : this(p::save, p::clear)

    /** ‘모든 데이터 삭제’: 파일 삭제 -> 실패하면 빈 상태(미결정·미동의) 기록 -> 그것도 실패하면 ‘다시 켜질 수 있음’. */
    fun reset(): NetWrite {
        if (clear()) return NetWrite.FALLBACK_CLEARED
        if (save(NetPrefsData())) return NetWrite.SAVED
        return NetWrite.FAILED_MAY_REAPPEAR
    }

    fun write(d: NetPrefsData): NetWrite {
        if (save(d)) return NetWrite.SAVED
        if (d.consent) return NetWrite.FAILED // 동의 저장 실패: 파일엔 이전(동의 없음 또는 이전 동의) 상태가 남는다
        return if (clear()) NetWrite.FALLBACK_CLEARED else NetWrite.FAILED_MAY_REAPPEAR
    }
}
