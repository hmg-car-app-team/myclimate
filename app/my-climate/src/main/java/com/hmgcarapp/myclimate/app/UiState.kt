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
    val dataLabel: String = "가공 샘플(날씨·대기질)",
    val debuggable: Boolean = false,
    val loading: Boolean = true,
    /** 시연 값(외기온도/시간/날씨/대기질)을 쓰는 중인지. */
    val simActive: Boolean = false,
    val saveHeld: Boolean = false,
    val corruptKept: Boolean = false,
    val toast: String? = null,
)
