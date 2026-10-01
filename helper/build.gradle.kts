plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.poi.ooxml)
    implementation(libs.jackson)
    implementation(libs.jmdns)
    implementation(libs.slf4j.nop)
    testImplementation(libs.kotlin.test)
}

application { mainClass.set("com.bido.budgetsync.helper.MainKt") }

// One runnable jar: java -jar helper/build/libs/budget-sync-helper.jar
tasks.jar {
    archiveFileName.set("budget-sync-helper.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.bido.budgetsync.helper.MainKt", "Multi-Release" to "true")
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.test { useJUnit() }
