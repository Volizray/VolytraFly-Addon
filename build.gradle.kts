plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)

    // Meteor
    implementation(libs.meteor.client)
    // Meteor supplies this at runtime; Minecraft's injected interfaces also need it at compile time.
    compileOnly("net.fabricmc.fabric-api:fabric-resource-loader-v1:2.0.9+d871b99e4c")
}

tasks {
    val flightRegressionTest by registering(Exec::class) {
        group = "verification"
        description = "Runs headless regression checks for movement protection and deferred actions."
        inputs.file("tests/regression.py")
        inputs.file("src/main/java/com/volytrafly/modules/movement/volytrafly/VolytraFly.java")
        commandLine("python3", "tests/regression.py")
    }

    check {
        dependsOn(flightRegressionTest)
    }

    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        archiveClassifier = "mc${libs.versions.minecraft.get()}"
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
