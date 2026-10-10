plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Android only installs an APK over the installed app (keeping its data) if it has the same package, the same signing
// key and a higher versionCode. The key is the debug keystore, which stays the same on this laptop. The versionCode is
// minutes since 2026-01-01, so every build is newer than the last one without anyone remembering to bump it.
val appVersionCode = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
val appVersionName = "1.7"

android {
    namespace = "com.bido.budgetsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bido.budgetsync"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // The release build is the real app: not debuggable, no developer tooling. It is signed with the same key as the app
    // already installed, otherwise Android would refuse to update it in place and the phone's data would have to be wiped.
    // (A Google Play release would use its own upload key instead.)
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { checkReleaseBuilds = false }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

/**
 * Builds the APK and publishes it for the phone: copies it and a version.json to %LOCALAPPDATA%\BudgetSync\update,
 * where the laptop helper serves it to the app's "Check for update" button.
 */
tasks.register("publishUpdate") {
    group = "distribution"
    dependsOn("assembleRelease")
    doLast {
        val dir = File(System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA is not set"), "BudgetSync/update")
        dir.mkdirs()
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        apk.copyTo(File(dir, "app.apk"), overwrite = true)
        File(dir, "version.json").writeText("""{"versionCode":$appVersionCode,"versionName":"$appVersionName"}""")
        // a copy with a friendly name in the project folder, for installing by hand the first time
        apk.copyTo(File(rootProject.projectDir, "BudgetTracker.apk"), overwrite = true)
        println("Published Budget Tracker $appVersionName (build $appVersionCode) to $dir")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.vm)
    implementation(libs.lifecycle.runtime)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json.test)
}
