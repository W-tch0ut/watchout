// 폰 앱 (#49): Firebase 로그인(구글, 이메일·비밀번호) + 보호자 연락처 등록·수정.
// #50: 환자↔보호자 초대 코드 연결, 응급 알림 목록(사진·움직임·확인), 보호자 번호로 문자, 119 시뮬레이션.
// 다음 단계: 앱이 꺼져 있어도 오는 푸시(FCM).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// google-services.json(Firebase 설정 파일)은 비밀 정보라 Git에 올리지 않는다 (.gitignore).
// 파일이 있을 때만 Firebase 설정 플러그인을 켠다. 그래서 파일이 없는 CI에서도 빌드·테스트가 된다.
// (단, 파일 없이 만든 앱은 실행하면 로그인이 되지 않는다. docs/mobile-login.md 참고)
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.watchout.mobile"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.watchout.mobile"
        minSdk = 26 // Android 8.0 이상
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 구글 로그인에 쓰는 "웹 클라이언트 ID"는 google-services 플러그인이 자동으로 만들어 준다.
        // 파일이 없을 때는 빈 값을 넣어 컴파일만 되게 한다.
        if (!hasGoogleServices) {
            resValue("string", "default_web_client_id", "")
        }
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
        resValues = true
    }

    testOptions {
        // 안드로이드 클래스를 부르면 예외 대신 기본값(0, null)을 돌려준다.
        unitTests.isReturnDefaultValues = true
    }
}

// 단위 테스트는 JUnit 5(Jupiter)로 실행한다. (:gesture 모듈과 같은 방식)
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Firebase: 버전은 BOM 하나로 맞춘다
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    // Firebase의 Task를 코루틴(await)으로 기다리기
    implementation(libs.kotlinx.coroutines.play.services)

    // 구글 로그인: Credential Manager (Android 권장 방식)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    // 워치와 같은 메시지 규약(WatchLink)·로봇카 찾기(CarScanner) (#42 데모)
    implementation(project(":gesture"))
    // 워치와 로봇카 선택·상태를 주고받는다 (Wear OS Data Layer)
    implementation(libs.play.services.wearable)
    // 로봇카와 MQTT로 통신한다 (폰이 로봇카와 직접 연결하고, 워치 명령은 폰을 거쳐 간다)
    implementation(libs.paho.mqtt)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
