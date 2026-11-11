package com.hmgcarapp.myclimate.domain

/** 착석 기반 프로필 확인 제안. 운전자를 식별하지 않는다: 마지막으로 선택·사용한 프로필을 보여 줄 뿐이다. */
data class SeatOffer(val profileId: String, val profileName: String) {
    val text: String get() = "마지막으로 사용한 프로필 '$profileName'(으)로 맞출까요?"
}

object SeatOfferPolicy {
    /**
     * 제안 여부. 모두 만족해야 한다: 정차 중, 저장 상태 복원 완료, 착석 신호가 명시적 true, 이번 착석에서 아직 안 물어봄,
     * 마지막 사용(선택) 프로필이 실제로 존재. 신호가 null(미지원·미확인)이면 제안하지 않는다. 적용은 항상 사용자 확인 뒤.
     */
    fun offer(p: Persisted, loaded: Boolean, parked: Boolean, occupied: Boolean?, handledThisSeating: Boolean): SeatOffer? {
        if (!loaded || !parked || occupied != true || handledThisSeating) return null
        val sel = p.selected ?: return null
        return SeatOffer(sel.id, sel.name)
    }
}
