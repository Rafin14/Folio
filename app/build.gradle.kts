import java.util.Properties
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.cert.X509Certificate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}
val privateSigningFile = rootProject.file("keystore.properties")
val unsignedRelease = providers.gradleProperty("folio.unsignedRelease").orNull == "true"
val privateSigning = Properties()
var privateSigningReadFailed = false
if (privateSigningFile.isFile) {
    try { privateSigningFile.inputStream().use(privateSigning::load) }
    catch (_: Exception) { privateSigningReadFailed = true }
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val signingComplete = signingKeys.all { !privateSigning.getProperty(it).isNullOrBlank() }
val validatePrivateReleaseSigning = tasks.register("validatePrivateReleaseSigning") {
    group = "verification"
    description = "Validate private local release credentials without printing them."
    doLast {
        fun invalid(reason: String): Nothing = throw GradleException("Release signing: $reason See docs/RELEASE_SIGNING.md. Debug builds remain available.")
        if (!privateSigningFile.isFile) invalid("Missing root keystore.properties.")
        if (privateSigningReadFailed) invalid("Cannot read keystore.properties.")
        if (!signingComplete || signingKeys.any { privateSigning.getProperty(it).startsWith("<") }) invalid("Required signing properties are missing or still placeholders.")
        val path = File(privateSigning.getProperty("storeFile"))
        if (!path.isAbsolute || !path.isFile) invalid("storeFile must identify an existing absolute keystore path outside the repository.")
        if (path.canonicalFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) invalid("Keep the release keystore outside the repository.")
        val storePassword = privateSigning.getProperty("storePassword").toCharArray()
        val keyPassword = privateSigning.getProperty("keyPassword").toCharArray()
        try {
            val store = try { KeyStore.getInstance(path, storePassword) }
            catch (_: Exception) { invalid("Cannot open the keystore. Check its format and store password locally.") }
            val alias = privateSigning.getProperty("keyAlias")
            if (!store.isKeyEntry(alias)) invalid("The configured alias has no private-key entry.")
            val key = try { store.getKey(alias, keyPassword) }
            catch (_: Exception) { invalid("Cannot unlock the signing key. Check its key password locally.") }
            if (key !is PrivateKey) invalid("The configured entry is not a private signing key.")
            val certificate = store.getCertificate(alias) as? X509Certificate ?: invalid("A signing certificate is required.")
            try { certificate.checkValidity() } catch (_: Exception) { invalid("The signing certificate is not currently valid.") }
            if (alias.equals("androiddebugkey", true) || certificate.subjectX500Principal.name.contains("CN=Android Debug", true)) invalid("A debug key cannot be used for release signing.")
            val sha1 = MessageDigest.getInstance("SHA-1").digest(certificate.encoded).joinToString(":") { "%02X".format(it.toInt() and 0xff) }
            if (sha1 != "93:3B:CF:4C:3B:98:75:80:5B:F9:A8:DF:05:74:1F:79:DD:85:50:75") invalid("The signing certificate does not match the required Folio Android OAuth identity. Use the original matching keystore; generating another key cannot reproduce it.")
        } finally { storePassword.fill('\u0000'); keyPassword.fill('\u0000') }
    }
}
tasks.configureEach {
    if (!unsignedRelease && (name == "validateSigningRelease" || name.contains("Release") && listOf("package", "sign", "bundle", "assemble").any(name::startsWith))) {
        dependsOn(validatePrivateReleaseSigning)
    }
}
android {
    namespace = "dev.folio.scanner"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.folio.scanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        val webClient = local.getProperty("folio.google.webClientId", "").trim()
            .takeIf { it.matches(Regex("[0-9]+-[A-Za-z0-9]+\\.apps\\.googleusercontent\\.com")) }.orEmpty()
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClient\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "onnx" }
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    splits {
        abi {
            isEnable = true
            reset()
            if (providers.gradleProperty("folio.releaseArmOnly").orNull == "true") {
                include("armeabi-v7a", "arm64-v8a")
                isUniversalApk = true
            } else {
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                isUniversalApk = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (providers.gradleProperty("folio.releaseArmOnly").orNull == "true") {
        // Split selection alone does not filter dependency libraries in the universal APK.
        packaging { jniLibs.excludes += setOf("**/x86/**", "**/x86_64/**") }
    }
    signingConfigs {
        create("privateRelease") {
            if (signingComplete && !privateSigningReadFailed) {
                storeFile = File(privateSigning.getProperty("storeFile"))
                storePassword = privateSigning.getProperty("storePassword")
                keyAlias = privateSigning.getProperty("keyAlias")
                keyPassword = privateSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = if (unsignedRelease) null else signingConfigs.getByName("privateRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.opencv:opencv:4.14.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("com.itextpdf.android:kernel-android:9.8.0")
    implementation("io.legere:pdfiumandroid:2.0.3")
    implementation("com.itextpdf.android:bouncy-castle-adapter-android:9.8.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    testImplementation("org.json:json:20260814")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
