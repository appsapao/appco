plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // The module ships into an Android app; keep the bytecode at 17 so D8
        // consumes exactly what the app module produces.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
    // Test-only: an independent verifier for the v2 signatures we produce.
    // It is NOT a runtime dependency and is not packaged into the APK.
    testImplementation(libs.apksig)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
