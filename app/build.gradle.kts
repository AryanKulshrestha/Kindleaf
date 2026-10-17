import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun loadEnvFile(): Properties {
    val props = Properties()
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.inputStream().use { props.load(it) }
    }
    return props
}

val env = loadEnvFile()

/** Reads a setting from .env, then the process environment (CI), trying each name in order. */
fun envValue(vararg names: String, default: String = ""): String =
    names.firstNotNullOfOrNull { name ->
        env.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }
    } ?: default

// NEXT_PUBLIC_* names are still accepted for older .env files.
val supabaseUrl = envValue("SUPABASE_URL", "NEXT_PUBLIC_SUPABASE_URL")
val supabasePublishableKey = envValue(
    "SUPABASE_PUBLISHABLE_KEY",
    "SUPABASE_ANON_KEY",
    "NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY",
)
val googleWebClientId = envValue(
    "GOOGLE_WEB_CLIENT_ID",
    default = "707306844344-v2g38dcevj2soadmsh0m0t2fc72mddmk.apps.googleusercontent.com",
)
val releaseKeystorePath = envValue("KINDLEAF_KEYSTORE_PATH")

android {
    namespace = "com.kindleaf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kindleaf"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"$supabasePublishableKey\""
        )
        // Web OAuth client ID only — never embed the Google client secret in the APK
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    signingConfigs {
        if (releaseKeystorePath.isNotBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = envValue("KINDLEAF_KEYSTORE_PASSWORD")
                keyAlias = envValue("KINDLEAF_KEY_ALIAS")
                keyPassword = envValue("KINDLEAF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKeystorePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)
    implementation(libs.razorpay.checkout)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}

tasks.register("printApkLocation") {
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk").get().asFile
        if (apkDir.exists()) {
            println("\n==================================================")
            println("🚀 YOUR APK LOCATION:")
            apkDir.walkTopDown().filter { it.extension == "apk" }.forEach { apk ->
                println("📦 APK Path: ${apk.absolutePath}")
            }
            println("==================================================\n")
        } else {
            println("\n❌ No APK built yet. Run 'Build -> Build Bundle(s) / APK(s) -> Build APK(s)' first!\n")
        }
    }
}

tasks.configureEach {
    if (name == "assembleDebug" || name == "assembleRelease") {
        finalizedBy("printApkLocation")
    }
}

