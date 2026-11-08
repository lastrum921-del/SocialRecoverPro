import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies { implementation(project(":core")) }

application {
    mainClass.set("com.socialrecover.desktop.MainKt")
    applicationName = "srp"
}

// JAR único (srp.jar) para empaquetar con jpackage como instalador de Windows (.msi / .exe).
tasks.register<Jar>("fatJar") {
    archiveFileName.set("srp.jar")
    manifest { attributes["Main-Class"] = "com.socialrecover.desktop.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
}
