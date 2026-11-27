// WatchOut Android 프로젝트
// - :gesture  제스처 인식 로직 (순수 Kotlin, 안드로이드 없이 PC에서 테스트 가능)
// - :wear     Wear OS 워치 앱 (지금은 제스처 판독·데이터 수집 툴)
// - :mobile   폰 앱 (로그인, 보호자 연락처 등록·수정) — #49
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "watchout"
include(":gesture", ":wear", ":mobile")
