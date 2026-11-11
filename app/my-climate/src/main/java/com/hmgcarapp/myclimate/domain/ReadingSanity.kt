package com.hmgcarapp.myclimate.domain

/**
 * 읽기값 타당성(순수). 차량 공조 서비스가 초기화되기 전에는 SDK 가 기본값(예: 온도 0.0)을 실제 읽기 값처럼 돌려줄 수 있다.
 * 온도·풍량이 capability 범위(없으면 [Limits.DEFAULT]) 밖이면 ‘아직 읽지 못함’(null)으로 취급해 상태에 반영하지 않는다.
 * 불리언(에어컨·내기)은 검증할 방법이 없어 건드리지 않는다.
 */
object ReadingSanity {
    /** 이 값을 상태에 반영해도 되는가. 온도·풍량만 범위를 검사하고 나머지 항목은 항상 true. */
    fun accept(kind: String, value: Double, lim: Limits): Boolean = when (kind) {
        "temp" -> value.isFinite() && value >= lim.tempMin && value <= lim.tempMax
        // 읽기 하한은 0(꺼짐 허용): lim.fanMin 은 쓰기·스테퍼용(>=1)이라 읽기에 쓰면 정상적인 풍량 0 이 버려진다
        "fan" -> value.isFinite() && value >= 0.0 && value <= lim.fanMax
        else -> value.isFinite()
    }
}

/** 문구(화면과 테스트가 같은 값을 쓴다). */
object UiTexts {
    const val CLEAR_ALL_TITLE = "모든 데이터를 삭제할까요?"
    const val CLEAR_ALL_DESC = "프로필·규칙·학습 기록이 모두 지워지고 기본 규칙으로 돌아가요. 스마트 내외기 설정도 꺼짐으로 돌아가요. 인터넷 사용 동의와 선택 지역도 처음 상태로 돌아가요. 되돌릴 수 없어요."
}
