pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SocialRecoverPro"
include(":core") // motor de cifrado, copias y recuperación (Kotlin puro, sin Android)
include(":app")  // aplicación Android (Jetpack Compose)
include(":desktop") // Windows / escritorio: motor + línea de comandos + tarea programada
