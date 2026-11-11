package com.hmgcarapp.myclimate.domain

/** 공조 쓰기 한 항목의 결과. [code] 는 실패 시 SDK 오류 코드(없으면 null). */
data class ItemResult(val kind: String, val ok: Boolean, val code: Int?)

/**
 * 쓰기 실패 해석(순수). 오류 코드는 AAOS CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_* 기준:
 * 0 UNKNOWN, 1 INVALID_ARG, 2 PROPERTY_NOT_AVAILABLE, 3 TRY_AGAIN, 4 TIMEOUT, 5 ACCESS_DENIED.
 * (Pleos Vehicle javadoc 에는 이 상수가 없어 AAOS 정의를 따랐다.)
 */
object HvacErrors {
    private val CODE = Regex("error\\s*code\\s*[:=]?\\s*(\\d+)", RegexOption.IGNORE_CASE)

    /** 예외 메시지(원인 사슬 포함 문자열)에서 오류 코드를 뽑는다. 없으면 null. */
    fun code(text: String?): Int? = text?.let { CODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun codeOf(t: Throwable): Int? {
        var e: Throwable? = t; var d = 0
        while (e != null && d++ < 5) { code(e.message)?.let { return it }; e = e.cause }
        return null
    }

    fun userMessage(code: Int?): String = when (code) {
        1 -> "값이 올바르지 않아요"
        2 -> "지금은 바꿀 수 없어요(공조가 꺼져 있을 수 있어요)"
        3 -> "잠시 후 다시 시도해 주세요"
        4 -> "차량 응답이 늦어요"
        5 -> "공조를 바꿀 권한이 없어요"
        else -> "알 수 없는 오류예요"
    }

    fun shortReason(code: Int?): String = when (code) { 1 -> "값 오류"; 2 -> "공조 꺼짐?"; 3 -> "재시도 필요"; 4 -> "응답 지연"; 5 -> "권한"; else -> "알 수 없음" }

    fun label(kind: String): String = when (kind) {
        "ac" -> "에어컨"; "recirc" -> "내기 순환"; "fan" -> "풍량"; "fanDir" -> "송풍 방향"; "temp" -> "온도"
        "conn" -> "차량 연결"; "seatHeat" -> "시트 열선"; "seatVent" -> "시트 통풍"; "wheelHeat" -> "핸들 열선"; else -> kind
    }

    /** 예: "6건 적용 · 풍량 실패(공조 꺼짐?)". 전부 실패면 "적용 실패 · …", 항목이 없으면 "적용할 설정이 없어요". */
    fun summary(results: List<ItemResult>): String {
        if (results.isEmpty()) return "적용할 설정이 없어요"
        val ok = results.count { it.ok }
        val fails = results.filter { !it.ok }.joinToString(", ") { "${label(it.kind)} 실패(${shortReason(it.code)})" }
        return when { fails.isEmpty() -> "${ok}건 적용"; ok == 0 -> "적용 실패 · $fails"; else -> "${ok}건 적용 · $fails" }
    }
}
