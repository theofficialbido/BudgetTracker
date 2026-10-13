plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

// The snapshot the phone sends and the receipt the laptop answers with, shared by the app and the helper so the two can
// never drift apart. org.json is provided by Android itself and added to the helper's jar.
dependencies {
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnit() }
