pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net") { name = "Fabric" }
        maven("https://jitpack.io") { name = "Jitpack" }
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.replaymod.preprocess" -> {
                    useModule("com.github.Fallen-Breath:preprocessor:${requested.version}")
                }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val versions = listOf(
    "1.21.11",
    "26.1",
    "26.2",
    "26.3"
)
val mainProjectVersion = file("versions/mainProject").readText().trim()

val preprocessParentChain = mapOf(
    "1.21.11" to "26.1",
    "26.1" to "26.2",
    "26.2" to "26.3"
)

fun expandWithPreprocessParents(requested: List<String>): List<String> {
    val expanded = linkedSetOf<String>()
    requested.forEach { version ->
        var current: String? = version
        while (current != null && expanded.add(current)) {
            current = preprocessParentChain[current]
        }
    }
    return expanded.toList()
}

val requestedVersions = System.getenv("TARGET_MC_VERSIONS")
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.distinct()

val taskRequestedVersions = if (requestedVersions.isNullOrEmpty()) {
    gradle.startParameter.taskNames
        .mapNotNull { taskName ->
            Regex("^:?([^:]+):").find(taskName)?.groupValues?.get(1)
        }
        .filter { it in versions }
        .distinct()
} else {
    emptyList()
}

val effectiveRequestedVersions = when {
    !requestedVersions.isNullOrEmpty() -> requestedVersions
    taskRequestedVersions.isNotEmpty() -> taskRequestedVersions
    else -> null
}

val selectedVersions = when {
    effectiveRequestedVersions == null -> versions
    else -> {
        val unknownVersions = effectiveRequestedVersions - versions.toSet()
        require(unknownVersions.isEmpty()) {
            "Unknown target Minecraft versions: ${unknownVersions.joinToString(", ")}"
        }
        expandWithPreprocessParents(effectiveRequestedVersions).let { expanded ->
            if (mainProjectVersion in expanded) expanded else expanded + mainProjectVersion
        }
    }
}

for (version in selectedVersions) {
    include(":$version")
    project(":$version").apply {
        projectDir = file("versions/$version")
        buildFileName = if (parseMcVersionToNumber(version) > 260000) {
            "../../build.fabric.gradle.kts"
        } else {
            "../../build.fabric.remap.gradle.kts"
        }
    }
}

include(":fabricWrapper")

fun parseMcVersionToNumber(mcVersionStr: String): Int {
    val cleanVersion = mcVersionStr.split("-")[0]
        .replace(Regex("[^0-9.]"), "")
    val versionParts = cleanVersion.split(".")
        .filter { it.isNotEmpty() }
    val major = versionParts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = versionParts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10000 + minor * 100 + patch
}
