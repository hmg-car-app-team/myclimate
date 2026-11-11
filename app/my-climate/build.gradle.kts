import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// API 키: local.properties(`myclimate.apiKey`) -> 환경변수 MYCLIMATE_API_KEY -> 빈 문자열(= Fake 사용). 코드/Git 에 넣지 않는다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val apiKey: String = (localProps.getProperty("myclimate.apiKey") ?: System.getenv("MYCLIMATE_API_KEY") ?: "").trim()

android {
    namespace = "com.hmgcarapp.myclimate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hmgcarapp.myclimate"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // release 빌드에는 API 키를 절대 주입하지 않는다(APK 에서 추출 가능). debug 에서만 로컬 키 주입. 빈 문자열 = Fake.
    buildTypes {
        debug { buildConfigField("String", "API_KEY", "\"${apiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"") }
        release { buildConfigField("String", "API_KEY", "\"\"") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
