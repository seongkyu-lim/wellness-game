import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** gradle property(-P, ~/.gradle/gradle.properties, android/gradle.properties) 값. 없으면 빈 문자열. */
fun prop(name: String): String = (findProperty(name) as String?)?.trim().orEmpty()

/** BuildConfig 문자열 리터럴로 쓸 수 있게 따옴표·역슬래시를 이스케이프한다. */
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseBaseUrl = prop("API_BASE_URL")
// 값이 있으면 어떤 빌드든 https인지 바로 확인한다. 값이 없을 때는 release 빌드에서만 실패시킨다(아래 검증 태스크).
require(releaseBaseUrl.isEmpty() || releaseBaseUrl.startsWith("https://")) {
    "API_BASE_URL must use https:// (release builds never talk to cleartext servers): $releaseBaseUrl"
}
val kakaoAppKey = prop("KAKAO_NATIVE_APP_KEY")

android {
    namespace = "io.github.seongkyulim.wellnessgame"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.seongkyulim.wellnessgame"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", quoted(prop("GOOGLE_SERVER_CLIENT_ID")))
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", quoted(kakaoAppKey))
        buildConfigField("String", "NAVER_CLIENT_ID", quoted(prop("NAVER_CLIENT_ID")))
        buildConfigField("String", "NAVER_CLIENT_SECRET", quoted(prop("NAVER_CLIENT_SECRET")))
        buildConfigField("String", "NAVER_CLIENT_NAME", quoted(prop("NAVER_CLIENT_NAME")))
        // 카카오톡 로그인 복귀용 스킴(kakao{앱 키}). 키가 없으면 의미 없는 기본값으로 둔다.
        manifestPlaceholders["kakaoScheme"] = if (kakaoAppKey.isEmpty()) "kakaounset" else "kakao$kakaoAppKey"
    }

    buildTypes {
        debug {
            // 에뮬레이터에서 호스트 PC의 localhost. cleartext는 src/debug의 network security config로만 허용한다.
            buildConfigField("String", "API_BASE_URL", quoted("http://10.0.2.2:8080/"))
        }
        release {
            buildConfigField("String", "API_BASE_URL", quoted(releaseBaseUrl.trimEnd('/') + "/"))
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

/** release 빌드는 서버 주소를 반드시 받는다. 기본값으로 조용히 빌드되지 않게 preReleaseBuild 앞에서 막는다. */
val validateReleaseConfig by tasks.registering {
    val baseUrl = releaseBaseUrl
    doLast {
        if (baseUrl.isEmpty()) {
            throw GradleException("API_BASE_URL is required for release builds: ./gradlew -PAPI_BASE_URL=https://... assembleRelease")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseConfig) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 테스트의 runTest·advanceUntilIdle 등은 실험 API다. 앱 코드에는 적용하지 않는다.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
    .matching { it.name.contains("UnitTest") }
    .configureEach { compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.connect)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.kakao.user)
    implementation(libs.naver.oauth)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
