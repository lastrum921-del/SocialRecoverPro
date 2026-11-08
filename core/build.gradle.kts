import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    maxHeapSize = "1g"
    // ./gradlew :core:test -Dsrp.bigTestMb=1024  -> prueba con un vídeo de 1 GiB
    systemProperty("srp.bigTestMb", System.getProperty("srp.bigTestMb") ?: "64")
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
