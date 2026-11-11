package com.hmgcarapp.myclimate.app

import com.hmgcarapp.myclimate.domain.AirGrade
import com.hmgcarapp.myclimate.domain.Ctx
import com.hmgcarapp.myclimate.domain.FlowState
import com.hmgcarapp.myclimate.domain.Weather

data class UiState(
    val fs: FlowState = FlowState(),
    val ctx: Ctx = Ctx(null, 12, null),
    val air: AirGrade? = null,
    val weather: Weather? = null,
    /** 환경 값 출처 배지(실시간/마지막 값/기본 샘플/시연 값). */
    val envBadge: String = "기본 샘플",
    val airNote: String? = null,
    val regionId: String = "seoul",
    /** 날씨·대기질 네트워크 사용 동의 상태(저장된 설정을 읽기 전에는 netLoaded=false). */
    val netLoaded: Boolean = false,
    val netDecided: Boolean = false,
    val netConsent: Boolean = false,
    val netPromptSnoozed: Boolean = false,
    val debuggable: Boolean = false,
    val loading: Boolean = true,
    /** 시연 값(외기온도/시간/날씨/대기질)을 쓰는 중인지. */
    val simActive: Boolean = false,
    /** 사용자가 고른 시연용 날씨/대기질(null = 실제/기본 값). */
    val demoWeather: Weather? = null,
    val demoAir: AirGrade? = null,
    val saveHeld: Boolean = false,
    val corruptKept: Boolean = false,
    val toast: String? = null,
    /** 착석 + 정차 시 '마지막으로 사용한 프로필' 확인 제안(자동 적용 아님). */
    val seatOffer: com.hmgcarapp.myclimate.domain.SeatOffer? = null,
)
