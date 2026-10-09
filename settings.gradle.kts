pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "carlink2"
// :app        — com.enigy.carlink2, the Play-distributed display app (all UI + protocol)
// :bridge     — android.car.usb.handler, the sideloaded USB owner + cluster icon provider
// :bridge-api — AIDL contract shared by both
include(":app")
include(":bridge")
include(":bridge-api")
