@file:Suppress("UnstableApiUsage")

plugins {
    id("mod-plugin")
    id("maven-publish")
    id("net.fabricmc.fabric-loom")
    id("com.replaymod.preprocess")
}

version = artifactVersion
group = modMavenGroup

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net") { name = "Fabric" }
    maven("https://maven.meteordev.org/releases") { name = "Meteor" }
    maven("https://maven.meteordev.org/snapshots") { name = "MeteorSnapshots" }
    maven("https://jitpack.io") { name = "Jitpack" }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("meteordevelopment:meteor-client:$meteorVersion")
}

loom {
    val commonVmArgs = listOf(
        "-Dmixin.debug.export=true",
        "-Dmixin.debug.verbose=true"
    )
    runs {
        named("client") {
            generateRunConfig = true
            jvmArguments.addAll(commonVmArgs)
            programArguments.addAll(listOf("--width", "1280", "--height", "720", "--username", "VolytraTest"))
            runDirectory = file("../../run/client")
        }
    }
}

tasks {
    // Same as printer build.fabric.gradle.kts: collect jar → root build/libs/$modVersion
    register<Copy>("buildAndCollect") {
        group = "build"
        val collectedJarDir = rootProject.layout.buildDirectory.dir("libs/$modVersion")
        from(jar.map { it.archiveFile })
        into(collectedJarDir)
        dependsOn("build")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = modId
            version = modVersion
        }
    }
    repositories {
        mavenLocal()
        maven {
            url = uri("$rootDir/publish")
        }
    }
}
