// 플러그인 버전은 gradle/libs.versions.toml 에서 한 곳에 관리한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
