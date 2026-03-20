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

• Smarter price predictions — prediction range now anchors to live price during market hours, so targets reflect today's actual price action
• Adaptive prediction bands — bands automatically widen for stocks moving more than usual, reducing false range violations
• Weighted signal scoring — VWAP, MACD, and Supertrend now count double in the confidence calculation; stronger signals carry more weight
• NIFTY conflict penalty — if a stock's predicted direction contradicts NIFTY's market trend, confidence is penalised to avoid false signals
• ORB breakout boost — confirmed breakout above ORB high or breakdown below ORB low now adds confidence instead of just adjusting the range
• Confidence cap — maximum confidence capped at 88% to keep predictions honest; no more unrealistically high scores on strong-trend days
• Faster NIFTY cache warm-up — market condition is now ready before your first scan, so the penalty works from the very first prediction
• Fix: Upstox VWAP and volume on closed market days — when market is closed or it's a weekend, Upstox returns 0 for VWAP and volume; the app now correctly falls back to calculated VWAP and Yahoo's volume instead of using the invalid 0 values"""
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
