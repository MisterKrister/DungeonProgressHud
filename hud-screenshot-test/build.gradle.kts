plugins {
    id("fabric-loom") version "1.15-SNAPSHOT"
}

version = "1.0.0"
group = "dev.krister"

base {
    archivesName.set("HudScreenshotTest")
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("fabric.mod.json") {
        expand("version" to project.version.toString())
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
