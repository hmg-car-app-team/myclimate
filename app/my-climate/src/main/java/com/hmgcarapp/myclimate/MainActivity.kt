package com.hmgcarapp.myclimate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.CorePermissions
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.myclimate.app.AppController
import com.hmgcarapp.myclimate.ui.MyClimateApp
import com.hmgcarapp.pleos.designsystem.restriction.PleosUxRestrictionsProvider
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme

class MainActivity : ComponentActivity() {
    private var controller: AppController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HmgLog.i("myclimate", "onCreate (API 키 ${if (BuildConfig.API_KEY.isEmpty()) "없음 -> Fake" else "있음 -> Remote 골격"})")
        // 위치 런타임 권한은 쓰지 않지만 core 위치 소스가 선언 권한을 요구할 수 있어 선언된 것만 요청한다
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val toRequest = CorePermissions.missing(this).filter { it in declared }
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), 1)
        val c = AppController(this, CarSignals.create(this), BuildConfig.API_KEY)
        controller = c
        setContent { PleosTheme { PleosUxRestrictionsProvider { MyClimateApp(c) } } }
    }

    override fun onStart() { super.onStart(); controller?.onAppStart() }
    override fun onStop() { controller?.saveNow(); super.onStop() }
    override fun onDestroy() { controller?.release(); controller = null; super.onDestroy() }
}
