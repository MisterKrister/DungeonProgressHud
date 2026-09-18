pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "net.fabricmc.fabric-loom") {
                useModule("net.fabricmc:fabric-loom:${requested.version}")
            }
        }
    }
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "DungeonProgressHud"
