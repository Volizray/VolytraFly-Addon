import org.gradle.api.Project
import org.gradle.api.initialization.Settings

object BuildToolUtils {
    fun parseMcVersionToNumber(mcVersionStr: String): Int {
        if (mcVersionStr.isBlank()) return 0
        try {
            val cleanVersion = mcVersionStr.split("-")[0]
                .replace(Regex("[^0-9.]"), "")
            val versionParts = cleanVersion.split(".")
                .filter { it.isNotEmpty() }
            val major = versionParts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = versionParts.getOrNull(2)?.toIntOrNull() ?: 0
            return major * 10000 + minor * 100 + patch
        } catch (e: Exception) {
            println("Failed to parse Minecraft version: $mcVersionStr; exception: ${e.message}")
            return 0
        }
    }

    fun formatMcVersionNumber(mcVersionInt: Int): String {
        if (mcVersionInt <= 0) return "unknown"
        val major = mcVersionInt / 10000
        val minor = (mcVersionInt % 10000) / 100
        val patch = mcVersionInt % 100
        return if (patch > 0) "$major.$minor.$patch" else "$major.$minor"
    }

    fun cleanSpecialChars(str: String): String {
        return str.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }
}

fun parseMcVersionToNumber(mcVersionStr: String): Int = BuildToolUtils.parseMcVersionToNumber(mcVersionStr)
fun formatMcVersionNumber(mcVersionInt: Int): String = BuildToolUtils.formatMcVersionNumber(mcVersionInt)
fun cleanSpecialChars(str: String): String = BuildToolUtils.cleanSpecialChars(str)

fun Settings.parseMcVersionToNumber(mcVersionStr: String): Int = BuildToolUtils.parseMcVersionToNumber(mcVersionStr)
fun Settings.formatMcVersionNumber(mcVersionInt: Int): String = BuildToolUtils.formatMcVersionNumber(mcVersionInt)

fun Project.parseMcVersionToNumber(mcVersionStr: String): Int = BuildToolUtils.parseMcVersionToNumber(mcVersionStr)
fun Project.formatMcVersionNumber(mcVersionInt: Int): String = BuildToolUtils.formatMcVersionNumber(mcVersionInt)
