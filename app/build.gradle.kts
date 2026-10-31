import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }

val localSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val kakaoNativeAppKey = localSettings.getProperty("kakao.nativeAppKey", "").trim()
require(kakaoNativeAppKey.isEmpty() || Regex("[a-fA-F0-9]{32}").matches(kakaoNativeAppKey)) {
    "local.properties의 kakao.nativeAppKey 형식을 확인해 주세요."
}

android {
    namespace = "dev.teslanavi"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.teslanavi"
        minSdk = 26
        targetSdk = 34
        versionCode = 50
        versionName = "0.5.33"
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoNativeAppKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":protocol"))
    implementation("com.kakaomobility.knsdk:knsdk:1.12.8-hotfix03")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.7.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}

// AGP/UTP uninstalls the target app after connected tests, erasing data and Android Keystore keys.
// Use assembleDebugAndroidTest + adb install -r + am instrument on a personal device instead.
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    doFirst {
        check(providers.gradleProperty("tesla.disposableTestDevice").orNull == "true") {
            "connected Android tests remove the app and its data. Use only a disposable test device with -Ptesla.disposableTestDevice=true."
        }
    }
}
