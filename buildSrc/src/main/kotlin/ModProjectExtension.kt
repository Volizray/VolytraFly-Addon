import org.gradle.api.Project
import org.gradle.api.JavaVersion

val Project.modId: String
    get() = prop("mod_id")

val Project.wrapperModId: String
    get() = propStrOrNull("mod_wrapper_id") ?: "$modId-wrapper"

val Project.modName: String
    get() = prop("mod_name")

val Project.modVersion: String
    get() = prop("mod_version")

val Project.modDescription: String
    get() = prop("mod_description")

val Project.modHomepage: String
    get() = propStrOrNull("mod_homepage") ?: ""

val Project.modSources: String
    get() = propStrOrNull("mod_sources") ?: ""

val Project.modLicense: String
    get() = prop("mod_license")

val Project.modMavenGroup: String
    get() = prop("mod_maven_group")

val Project.modArchivesBaseName: String
    get() = prop("mod_archives_base_name")

val Project.mcVersion: String?
    get() = propStrOrNull("minecraft_version")

val Project.mcDependency: String
    get() = propStrOrNull("minecraft_dependency") ?: (mcVersion ?: "")

// Preprocess sets project.ext "mcVersion" to the integer node version (e.g. 260300)
val Project.mcVersionInt: Int
    get() = (findProperty("mcVersion") as? Number)?.toInt()
        ?: propStrOrNull("mcVersion")?.toIntOrNull()
        ?: parseMcVersionToNumber(mcVersion ?: "0")

val Project.fabricLoaderVersion: String
    get() = propStrOrNull("fabric_loader_version")
        ?: propStrOrNull("loader_version")
        ?: rootProject.prop("loader_version")

val Project.fabricApiVersion: String
    get() = propStrOrNull("fabric_version") ?: ""

val Project.meteorVersion: String
    get() = prop("meteor")

val Project.javaDependency: String
    get() = propStrOrNull("java_dependency") ?: ">=21"

val Project.javaVersion: JavaVersion
    get() = when {
        mcVersionInt >= 260000 -> JavaVersion.VERSION_25
        mcVersionInt >= 12005 -> JavaVersion.VERSION_21
        mcVersionInt >= 11800 -> JavaVersion.VERSION_17
        else -> JavaVersion.VERSION_21
    }

val Project.mixinJavaVersion: String
    get() = "JAVA_${javaVersion}"

private val Project.githubRunNumber: String?
    get() = System.getenv("GITHUB_RUN_NUMBER")

private val Project.isReleaseWorkflow: Boolean
    get() = System.getenv("IS_THIS_RELEASE")?.equals("true", ignoreCase = true) == true
        || System.getenv("RELEASE")?.equals("true", ignoreCase = true) == true

val Project.fullProjectVersion: String
    get() {
        val buildNumber = githubRunNumber
        return when {
            buildNumber != null && isReleaseWorkflow -> "$modVersion-beta$buildNumber"
            buildNumber != null -> "$modVersion-dev$buildNumber"
            else -> "$modVersion-local"
        }
    }

val Project.artifactVersion: String
    get() {
        val buildNumber = githubRunNumber
        val baseVersion = mcVersion ?: modVersion
        return when {
            buildNumber != null && isReleaseWorkflow -> "$baseVersion-beta$buildNumber"
            buildNumber != null -> "$baseVersion-dev$buildNumber"
            else -> "$baseVersion-local"
        }
    }

val Project.placeholderProps: Map<String, Any>
    get() = mapOf(
        "mod_id" to modId,
        "mod_wrapper_id" to wrapperModId,
        "mod_name" to modName,
        "mod_version" to fullProjectVersion,
        "mod_description" to modDescription,
        "mod_homepage" to modHomepage,
        "mod_license" to modLicense,
        "mod_sources" to modSources,
        "loader_version" to fabricLoaderVersion,
        "minecraft_dependency" to mcDependency,
        "mc_version" to (mcVersion ?: ""),
        "java_dependency" to javaDependency,
        "compatibility_level" to mixinJavaVersion
    )

fun Project.prop(name: String): String {
    val value = propStrOrNull(name)
    require(!value.isNullOrBlank()) { "Missing required property: $name (project=${project.path})" }
    return value
}

fun Project.propStrOrNull(name: String): String? {
    val local = findProperty(name)?.toString()?.takeIf { it.isNotBlank() }
    if (local != null) return local
    return rootProject.findProperty(name)?.toString()?.takeIf { it.isNotBlank() }
}
