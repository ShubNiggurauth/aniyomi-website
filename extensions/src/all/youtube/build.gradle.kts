import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val extName = "YouTube"
val extClass = ".YouTube"
val extVersionCode = 1
val libVersion = "14"
val isNsfw = false

android {
    namespace = "eu.kanade.tachiyomi.animeextension"
    compileSdk = 34

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            res.srcDirs("res")
        }
    }

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.all.youtube"
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        // Aniyomi reads the extensions-lib version from the major part of versionName.
        versionName = "$libVersion.$extVersionCode"
        manifestPlaceholders += mapOf(
            "appName" to "Aniyomi: $extName",
            "extClass" to extClass,
            "nsfw" to if (isNsfw) 1 else 0,
        )
    }

    signingConfigs {
        // Optional release signing, configured through environment variables in CI.
        val storePath = System.getenv("SIGNING_KEY_PATH")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                keyAlias = System.getenv("ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        aidl = false
        renderScript = false
        resValues = false
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // Extensions are sideloaded into Aniyomi, not published on Google Play.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "GradleDependency", "NewerVersionAvailable")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

base {
    archivesName.set("aniyomi-all.youtube-v$libVersion.$extVersionCode")
}

dependencies {
    compileOnly(libs.bundles.common)
}
