import java.security.MessageDigest
import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }

val localSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val kakaoNativeAppKey = localSettings.getProperty("kakao.nativeAppKey", "").trim()
require(kakaoNativeAppKey.isEmpty() || Regex("[a-fA-F0-9]{32}").matches(kakaoNativeAppKey)) {
    "local.properties의 kakao.nativeAppKey 형식을 확인해 주세요."
}

// Official k2-fsa release binary (Supertonic voice). Fetched once into app/libs, which Git ignores.
val sherpaOnnxVersion = "1.12.34"
val sherpaOnnxSha256 = "9042637b206ef2dd98ea3a2fe28ffaa7e8767557848d94b5e25c268db188535b"
val sherpaOnnxAar = file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    }
    return digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
}
val fetchSherpaOnnx = tasks.register("fetchSherpaOnnx") {
    inputs.property("sha256", sherpaOnnxSha256)
    outputs.file(sherpaOnnxAar)
    doLast {
        if (sherpaOnnxAar.isFile && sha256(sherpaOnnxAar) == sherpaOnnxSha256) return@doLast
        val part = File(sherpaOnnxAar.path + ".part")
        sherpaOnnxAar.parentFile.mkdirs()
        val source = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
        try { uri(source).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "Mozilla/5.0 TeslaNaviBuild")
            }.getInputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        } catch (error: java.io.IOException) {
            // Some networks reject JVM HTTPS requests (HTTP 406). Use the system TLS client,
            // then apply exactly the same checksum validation before accepting the download.
            logger.lifecycle("JVM 다운로드 실패 · 시스템 curl로 공식 배포 파일을 다시 받습니다.")
            val curl = if (System.getProperty("os.name").startsWith("Windows")) "curl.exe" else "curl"
            val process = try {
                ProcessBuilder(curl, "--location", "--fail", "--silent", "--show-error", "--max-time", "180",
                    "--output", part.absolutePath, source).inheritIO().start()
            } catch (missing: java.io.IOException) {
                throw GradleException("음성 라이브러리를 받지 못했습니다. curl을 설치하거나 공식 AAR을 app/libs에 저장해 주세요.", missing)
            }
            if (process.waitFor() != 0) { part.delete(); throw GradleException("음성 라이브러리 다운로드 실패", error) }
        }
        if (sha256(part) != sherpaOnnxSha256) { part.delete(); throw GradleException("sherpa-onnx AAR 체크섬이 일치하지 않습니다.") }
        if (!part.renameTo(sherpaOnnxAar)) throw GradleException("sherpa-onnx AAR을 저장하지 못했습니다.")
    }
}
android {
    namespace = "dev.teslanavi"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.teslanavi"
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "0.5.18"
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
    implementation(files(sherpaOnnxAar).builtBy(fetchSherpaOnnx))
    implementation("org.apache.commons:commons-compress:1.27.1")
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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
