package com.hmgcarapp.myclimate.vehicle

import android.content.Context
import com.hmgcarapp.core.sim.SimState
import com.hmgcarapp.core.vehicle.CarPropertyVehicleSignals
import com.hmgcarapp.core.vehicle.ReflectiveCarPropertyReader
import com.hmgcarapp.core.vehicle.SignalReading
import com.hmgcarapp.core.vehicle.VehicleSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** 외기온도(℃). core 의 CarPropertyManager 경로(ENV_OUTSIDE_TEMPERATURE)를 주기 조회하고, 시연 값이 있으면 그것을 우선한다. */
object OutsideTemp {
    /** 시연(core SimulationHub) 값 우선, 없으면 실신호. 비정상(NaN/무한/범위 밖) 값은 null. */
    fun pick(sim: SimState, real: SignalReading<Float>): Float? =
        (if (sim.enabled && sim.outsideTempC != null) sim.outsideTempC else real.value)?.takeIf { it.isFinite() && it in -60f..70f }

    fun flow(context: Context, intervalMs: Long = 15_000L, sim: () -> SimState): Flow<Float?> {
        val signals: VehicleSignals = CarPropertyVehicleSignals(ReflectiveCarPropertyReader(context.applicationContext))
        return flow { while (true) { emit(pick(sim(), signals.outsideTempCNow())); delay(intervalMs) } }.flowOn(Dispatchers.IO)
    }
}
