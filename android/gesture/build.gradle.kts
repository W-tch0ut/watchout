// 제스처 인식 로직만 담은 순수 Kotlin 모듈.
// 안드로이드에 의존하지 않아서 PC(JVM)에서 바로 단위 테스트할 수 있다.
// 워치 앱(:wear)과 나중에 만들 폰 앱(:mobile)이 이 모듈을 가져다 쓴다.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
