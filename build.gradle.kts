import java.security.MessageDigest

plugins {
    id("net.fabricmc.fabric-loom") version "1.16.3"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

version = "1.0.16-noamm.1"
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

repositories {
    maven("https://maven.teamresourceful.com/repository/maven-public/")
    maven("https://api.modrinth.com/maven")
}

dependencies {
    minecraft("com.mojang:minecraft:26.1.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.155.2+26.1.2")
    implementation("net.fabricmc:fabric-language-kotlin:1.13.13+kotlin.2.4.10")
    implementation(files("libs/NoammAddons-1.2.7-26.1.2-legit.jar"))
    implementation("tech.thatgravyboat:skyblock-api:4.2.19") {
        exclude(group = "me.djtheredstoner", module = "DevAuth-fabric")
        capabilities {
            requireCapability("tech.thatgravyboat:skyblock-api-26.1")
        }
    }
    include("tech.thatgravyboat:skyblock-api:4.2.19") {
        capabilities {
            requireCapability("tech.thatgravyboat:skyblock-api-26.1")
        }
    }
    testImplementation(kotlin("test"))
    testImplementation("io.github.classgraph:classgraph:4.8.195")
}

tasks.test {
    useJUnitPlatform()
}

val verifyNoammAddons by tasks.registering {
    val dependencyJar = layout.projectDirectory.file("libs/NoammAddons-1.2.7-26.1.2-legit.jar")
    inputs.file(dependencyJar)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(dependencyJar.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "2e4b9d8ce5219cbad39f8fb66e43b739746f907c250357f5523b13c3d48499e1") {
            "NoammAddons dependency checksum mismatch; use the repository-supplied 1.2.7 legit jar."
        }
    }
}

tasks.named("compileKotlin") { dependsOn(verifyNoammAddons) }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
