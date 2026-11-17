import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Tek surum kaynagi: proje kokundeki VERSION.txt (ornek: 1.0.43)
val appVersionName: String = rootProject.file("VERSION.txt").readText().trim().ifEmpty { "1.0.0" }
val appVersionCode: Int = run {
    val parts = appVersionName.split('.').map { it.toLongOrNull() }
    require(parts.size == 3 && parts.all { it != null && it >= 0L }) {
        "VERSION.txt gecersiz: '$appVersionName' (beklenen: 1.0.43)"
    }
    val (major, minor, patch) = parts.filterNotNull()
    require(major in 0L..2100L && minor < 1000L && patch < 1000L) {
        "VERSION.txt icinde major 0..2100, minor ve patch 0..999 araliginda olmali"
    }
    val code = major * 1_000_000L + minor * 1_000L + patch
    require(code in 1L..2_100_000_000L) { "VERSION.txt versionCode sinirini asiyor" }
    code.toInt()
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProp(name: String) = localProps.getProperty(name)?.trim().orEmpty()
val releaseStore = localProp("RELEASE_STORE_FILE")
val hasReleaseKey = releaseStore.isNotEmpty() &&
    localProp("RELEASE_STORE_PASSWORD").isNotEmpty() &&
    localProp("RELEASE_KEY_ALIAS").isNotEmpty() &&
    localProp("RELEASE_KEY_PASSWORD").isNotEmpty() &&
    rootProject.file(releaseStore).isFile

android {
    namespace = "com.makay.livetv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.makay.livetv"
        // TvView / TvContract / DvbDevice API'leri API 26'da mevcut; TIF
        // davranisi API 29+ cihazlarda stabilize oldugu icin taban 26 tutuldu.
        minSdk = 26
        // DTVKit's TV input stack targets older Android TV app behavior.
        // Keep the target at API 33 while still compiling against the latest SDK.
        targetSdk = 33
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"makaydestek\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"makay-livetv\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = localProp("RELEASE_STORE_PASSWORD")
                keyAlias = localProp("RELEASE_KEY_ALIAS")
                keyPassword = localProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        warningsAsErrors = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "**/kotlin-tooling-metadata.json"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    splits {
        abi {
            isEnable = false
        }
    }
}

// Teslimat yalnizca proje/APK. Ara kopyalar APK/ disinda birakilmaz.
fun deleteDeliveryArtifactsIn(dir: java.io.File) {
    if (!dir.exists()) return
    dir.walkTopDown().forEach { f ->
        if (!f.isFile) return@forEach
        val n = f.name.lowercase()
        if (n.endsWith(".apk") || n.endsWith(".apk.sha256") || n.endsWith(".aab")) {
            f.delete()
        }
    }
}

fun deleteStrayDeliveryApks(keepDir: java.io.File) {
    val root = rootProject.layout.projectDirectory.asFile
    // NOT: build/outputs alti APK'lar silinmez. packageRelease gorevi bu dosyayi
    // cikti olarak tanimlar; silinince Gradle gorevi UP-TO-DATE sayar ve sonraki
    // derlemede APK yeniden uretilmez ("APK bulunamadi" hatasi olusur).
    // build/ dizini gecicidir (.gitignore'lu); teslimat yalnizca APK/ klasorundedir.
    deleteDeliveryArtifactsIn(root.resolve("app/release"))
    deleteDeliveryArtifactsIn(root.resolve("release"))
    root.listFiles()?.forEach { f ->
        if (!f.isFile) return@forEach
        val n = f.name.lowercase()
        if (n.endsWith(".apk") || n.endsWith(".apk.sha256") || n.endsWith(".aab")) {
            f.delete()
        }
    }
    check(keepDir.exists())
}

tasks.register("publishReleaseApk") {
    group = "distribution"
    description = "Release APK + SHA-256 + R8 mapping'i yalnizca APK/ klasorune yazar"
    doFirst {
        check(hasReleaseKey) {
            "publishReleaseApk: uretim keystore zorunlu (debug imzali yayin engellendi)."
        }
    }
    doLast {
        val src = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(src.exists()) { "APK bulunamadi: ${src.absolutePath}" }
        val apkDir = rootProject.layout.projectDirectory.dir("APK").asFile
        apkDir.mkdirs()
        val ver = rootProject.file("VERSION.txt").readText().trim()
        val named = apkDir.resolve("MakayLiveTV_v$ver.apk")
        val shaFile = apkDir.resolve("MakayLiveTV_v$ver.apk.sha256")
        val staging = apkDir.resolve(".staging-app-release.apk")

        src.copyTo(staging, overwrite = true)
        apkDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != staging.name) f.delete()
        }
        staging.copyTo(named, overwrite = true)
        staging.delete()

        val mappingSrc = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
        val mappingOut = apkDir.resolve("MakayLiveTV_v$ver-mapping.txt")
        if (mappingSrc.exists()) {
            mappingSrc.copyTo(mappingOut, overwrite = true)
        } else {
            mappingOut.delete()
        }

        val digest = MessageDigest.getInstance("SHA-256")
        named.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
        check(hex.length == 64) { "SHA-256 olusturulamadi: $hex" }
        shaFile.writeText("$hex  ${named.name}\n", Charsets.US_ASCII)

        deleteStrayDeliveryApks(apkDir)

        check(named.exists()) { "Teslim APK APK/ klasorunde yok: ${named.absolutePath}" }
        println("Published: ${named.absolutePath}")
        println("SHA-256  : ${shaFile.absolutePath}")
        println("Version  : $ver (versionCode=$appVersionCode)")
        println("Fingerprint: $hex")
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        doFirst {
            check(hasReleaseKey) {
                "assembleRelease: uretim keystore zorunlu. local.properties icinde RELEASE_* alanlarini doldurun."
            }
        }
        finalizedBy("publishReleaseApk")
    }
    tasks.findByName("packageRelease")?.doFirst {
        check(hasReleaseKey) {
            "packageRelease: uretim keystore zorunlu (debug imza fallback kaldirildi)."
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    testImplementation("junit:junit:4.13.2")
}
