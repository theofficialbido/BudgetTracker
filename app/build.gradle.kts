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
val appVersionName = "1.3"

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
    dependsOn("assembleDebug")
    doLast {
        val dir = File(System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA is not set"), "BudgetSync/update")
        dir.mkdirs()
        layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.copyTo(File(dir, "app.apk"), overwrite = true)
        File(dir, "version.json").writeText("""{"versionCode":$appVersionCode,"versionName":"$appVersionName"}""")
        println("Published version $appVersionName (build $appVersionCode) to $dir")
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
