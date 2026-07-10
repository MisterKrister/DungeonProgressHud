plugins {
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

version = "1.0.8"
group = "dev.krister"

base {
    archivesName.set("DungeonProgressHud")
}

val modVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", modVersion)

    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.150.0+26.1.2")
    implementation("net.fabricmc:fabric-language-kotlin:1.13.10+kotlin.2.3.20")
    implementation(files("libs/devonian-1.25.9.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
