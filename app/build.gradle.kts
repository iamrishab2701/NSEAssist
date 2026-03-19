import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.appdistribution)
}

val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"

// Read signing credentials from local.properties (gitignored — never committed)
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())

android {
    namespace = "com.nseassist"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nseassist"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile     = rootProject.file(localProps.getProperty("KEYSTORE_FILE", "keystore/nseassist-release.jks"))
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias      = localProps.getProperty("KEY_ALIAS", "nseassist")
            keyPassword   = localProps.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig   = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            firebaseAppDistribution {
                releaseNotes = """v${appVersionName} — What's new:

• Pivot Points (R2/S2) — full pivot level set now shown in Stock Detail and sent to AI for target/stop guidance
• India VIX in AI analysis — both single-stock and batch AI prompts now include live VIX with danger labels; AI told to prefer NO_TRADE when VIX ≥ 20
• Prediction accuracy overhaul (6 improvements):
  - Pivot-anchored predicted high/low — R1/S1/CPP used as natural intraday boundaries instead of raw regression
  - VIX-adjusted ATR multiplier — predicted range widens on high-fear days (1.4× when VIX ≥ 20)
  - Candlestick direction adjustment — strong bullish/bearish patterns override regression direction
  - ORB-based range tightening — predicted low floored at ORB high on confirmed breakouts
  - Signal alignment boost — confidence increases when VWAP, RSI, MACD, Supertrend, ADX all agree
  - ADX-adjusted confidence — strong trend boosts confidence; choppy market reduces it
• PREDICT log tag — each stock now logs raw vs refined prediction with exact reasons (e.g. candle:bull→up+8, align:4/6+7, adx:trend+5)"""
                groups       = "testers"
            }
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

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "nseassist-${variant.buildType.name}-v${variant.versionName}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coroutines.android)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
