import java.security.MessageDigest

plugins {
    id("net.fabricmc.fabric-loom") version "1.16.3"
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

version = "1.0.12"
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
    implementation("net.fabricmc.fabric-api:fabric-api:0.150.0+26.1.2")
    implementation("net.fabricmc:fabric-language-kotlin:1.13.10+kotlin.2.3.20")
    implementation(files("libs/devonian-1.31.9.jar"))
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
}

tasks.test {
    useJUnitPlatform()
}

val verifyDevonian by tasks.registering {
    val dependencyJar = layout.projectDirectory.file("libs/devonian-1.31.9.jar")
    inputs.file(dependencyJar)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(dependencyJar.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "17ebb41ee38738c0e69aa03db16f40d774265880ca8a51f701b7c132f4ca31cd") {
            "Devonian dependency checksum mismatch; use the repository-supplied 1.31.9 jar."
        }
    }
}

tasks.named("compileKotlin") { dependsOn(verifyDevonian) }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
