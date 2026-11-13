plugins {
    `kotlin-dsl`
}

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}

// Use the JVM that runs Gradle (no explicit toolchain so it works in limited environments)


gradlePlugin {
    plugins {
        register("mod-plugin") {
            id = "mod-plugin"
            implementationClass = "ModPlugin"
        }
    }
}
