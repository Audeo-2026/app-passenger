import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.cocoapods)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// ---------- Audeo 서버 설정: local.properties(UTF-8) → 공통 코드의 AudeoConfig ----------
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
}

fun config(key: String, env: String, default: String = ""): String = localProps.getProperty(key) ?: System.getenv(env) ?: default

val generatedConfigDir = layout.buildDirectory.dir("generated/audeoConfig/commonMain/kotlin")
val generateAudeoConfig by tasks.registering {
    val values = mapOf(
        "BASE_URL" to config("audeo.api.baseUrl", "AUDEO_API_BASE_URL", "https://audeo.mosslab.dev/api/v1/"),
        "INVITE_CODE" to config("audeo.inviteCode", "AUDEO_INVITE_CODE"),
        "DEFAULT_NAME" to config("audeo.passenger.name", "AUDEO_PASSENGER_NAME"),
        "DEFAULT_SHORT_NAME" to config("audeo.passenger.shortName", "AUDEO_PASSENGER_SHORT_NAME"),
        "DEFAULT_PHONE" to config("audeo.passenger.phone", "AUDEO_PASSENGER_PHONE"),
    )
    inputs.properties(values)
    val out = generatedConfigDir
    outputs.dir(out)
    doLast {
        val body = values.entries.joinToString("\n") { (k, v) -> "    const val $k: String = \"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        val file = out.get().file("com/audeo/phone/AudeoConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.audeo.phone\n\n/** local.properties에서 만든 설정(빌드할 때 생성). */\ninternal object AudeoConfig {\n$body\n}\n")
    }
}

kotlin {
    // Prefs·DeviceLocation·CallAudioRoute는 expect object로 플랫폼마다 구현합니다.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        summary = "Audeo 탑승자 앱 공통 모듈"
        homepage = "https://audeo.mosslab.dev"
        version = "0.1.0"
        ios.deploymentTarget = "15.0"
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        // webrtc-kmp는 iOS에서 WebRTC SDK를 직접 붙여야 합니다.
        pod("WebRTC-SDK") {
            version = "125.6422.05"
            moduleName = "WebRTC"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAudeoConfig.map { generatedConfigDir.get() })
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                implementation(libs.webrtc.kmp)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

compose.resources {
    packageOfResClass = "com.audeo.phone.resources"
}

android {
    namespace = "com.audeo.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.audeo.phone"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}
