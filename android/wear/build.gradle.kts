// Wear OS 워치 앱.
// 지금 단계: 제스처 판독 툴 (실시간 인식 결과 표시 + 학습 데이터 수집)
// 음성 명령(#69): 누른 채로 말하면 기기 안에서 알아듣고 명령 JSON을 만든다
// 다음 단계: MQTT로 로봇카에 명령 보내기(#42), 낙상 감지
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.watchout.wear"
    // Wear Compose 1.7 / Compose 1.13이 Android 37.2 이상으로 컴파일하기를 요구한다.
    // (워치에 설치할 수 있는 최소 버전은 아래 minSdk가 정한다)
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.watchout.wear"
        minSdk = 30 // Wear OS 3 이상 (Galaxy Watch4 이후)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":gesture"))
    implementation(project(":command")) // 명령 메시지 + 음성 명령 해석기 (#69)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
