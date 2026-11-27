// Wear OS 워치 앱.
// 지금 단계: 제스처 판독 툴 (실시간 인식 결과 표시 + 학습 데이터 수집)
// + 데모(#42): 백그라운드 제스처·낙상 감지 (로봇카 연결·제스처 등록 조작은 폰 앱, Data Layer로 주고받는다)
// + 음성 명령(#69): 누른 채로 말하면 기기 안에서 알아듣고 명령 JSON을 만든다 → 폰을 거쳐 로봇카로
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
        // 폰 앱(:mobile)과 같은 applicationId여야 Wear OS Data Layer로 서로 보인다 (서명도 같아야 한다).
        // 코드 패키지(namespace)는 com.watchout.wear 그대로다. 폰 앱은 Firebase에 com.watchout.mobile로 등록돼 있어서 워치를 맞춘다.
        applicationId = "com.watchout.mobile"
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

    // 폰 앱과 설정·상태를 주고받는다 (Wear OS Data Layer)
    implementation(libs.play.services.wearable)
}
