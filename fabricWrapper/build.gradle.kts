import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("java-library")
    id("maven-publish")
    id("mod-plugin")
}

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}

group = modMavenGroup
version = fullProjectVersion

base {
    archivesName.set(modArchivesBaseName)
}

val fabricSubprojects = rootProject.subprojects.filter { it.name != "fabricWrapper" }

fabricSubprojects.forEach {
    evaluationDependsOn(":${it.name}")
}

fun rewriteJar(jarFile: File, transformer: (String, Boolean, ByteArray) -> ByteArray?) {
    val tmpFile = File(jarFile.parentFile, "${jarFile.name}.tmp")
    val seenEntries = HashSet<String>()

    JarInputStream(jarFile.inputStream().buffered()).use { input ->
        JarOutputStream(tmpFile.outputStream().buffered()).use { output ->
            var entry = input.nextJarEntry
            while (entry != null) {
                val entryName = entry.name
                if (seenEntries.add(entryName)) {
                    val bytes = if (entry.isDirectory) {
                        ByteArray(0)
                    } else {
                        val buffer = ByteArrayOutputStream()
                        input.copyTo(buffer)
                        buffer.toByteArray()
                    }

                    val transformed = transformer(entryName, entry.isDirectory, bytes)
                    if (transformed != null) {
                        val newEntry = JarEntry(entryName)
                        if (entry.time >= 0) {
                            newEntry.time = entry.time
                        }
                        output.putNextEntry(newEntry)
                        if (!entry.isDirectory) {
                            output.write(transformed)
                        }
                        output.closeEntry()
                    }
                }
                input.closeEntry()
                entry = input.nextJarEntry
            }
        }
    }

    tmpFile.copyTo(jarFile, overwrite = true)
    tmpFile.delete()
}

fun stripJarResources(jarFile: File, prefixes: Set<String>) {
    if (prefixes.isEmpty()) {
        return
    }

    rewriteJar(jarFile) { entryName, _, bytes ->
        val shouldDrop = prefixes.any { prefix ->
            entryName == prefix || entryName.startsWith(prefix)
        }
        if (shouldDrop) null else bytes
    }
}

fun collectNestedLibs(jarFile: File): Set<String> {
    val libs = LinkedHashSet<String>()
    JarInputStream(jarFile.inputStream().buffered()).use { input ->
        var entry = input.nextJarEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.startsWith("META-INF/jars/") && entry.name.endsWith(".jar")) {
                libs.add(File(entry.name).name)
            }
            entry = input.nextJarEntry
        }
    }
    return libs
}

fun removeNestedLibsFromJar(jarFile: File, libNamesToRemove: Set<String>) {
    if (libNamesToRemove.isEmpty()) return

    rewriteJar(jarFile) { entryName, _, bytes ->
        if (entryName.startsWith("META-INF/jars/") && entryName.endsWith(".jar") && File(entryName).name in libNamesToRemove) {
            return@rewriteJar null
        }
        if (entryName == "META-INF/jarjar/metadata.json" || entryName == "META-INF/jars.list") {
            // leave as-is for simplicity
            return@rewriteJar bytes
        }
        bytes
    }
}

fun optimizeWrapperSubmoduleJars(jarsDir: File, sharedResourcePrefixes: Set<String>) {
    val jarFiles = jarsDir.listFiles { file ->
        file.isFile && file.name.endsWith(".jar")
    } ?: return

    jarFiles.forEach { jarFile ->
        stripJarResources(jarFile, sharedResourcePrefixes)
    }

    val nestedLibCounts = LinkedHashMap<String, Int>()
    jarFiles.forEach { jarFile ->
        collectNestedLibs(jarFile).forEach { libName ->
            nestedLibCounts[libName] = (nestedLibCounts[libName] ?: 0) + 1
        }
    }
    val commonNestedLibs = nestedLibCounts.filter { it.value > 1 }.keys
    if (commonNestedLibs.isNotEmpty()) {
        jarFiles.forEach { jarFile ->
            removeNestedLibsFromJar(jarFile, commonNestedLibs)
        }
    }
}

tasks {
    register("collectSubModules") {
        description = "Collect JAR files from all subprojects"
        outputs.upToDateWhen { false }

        val embeddedJarsDir = layout.buildDirectory.dir("tmp/submods/META-INF/jars")
        val standaloneJarsDir = layout.buildDirectory.dir("libs/jars")

        dependsOn(fabricSubprojects.map { it.tasks.named("buildAndCollect") })

        doFirst {
            delete(embeddedJarsDir)
            delete(standaloneJarsDir)

            copy {
                from(fabricSubprojects.map { sub ->
                    sub.tasks.named("buildAndCollect").get().outputs.files
                })
                into(embeddedJarsDir)
            }

            copy {
                from(fabricSubprojects.map { sub ->
                    sub.tasks.named("buildAndCollect").get().outputs.files
                })
                into(standaloneJarsDir)
            }

            optimizeWrapperSubmoduleJars(
                embeddedJarsDir.get().asFile,
                setOf(
                    "assets/$modId/icon.png",
                    "assets/$modId/lang/"
                )
            )
        }
    }

    named<Jar>("jar") {
        outputs.upToDateWhen { false }

        from(rootProject.file("LICENSE"))
        from(layout.buildDirectory.dir("tmp/submods"))
    }

    named<ProcessResources>("processResources") {
        outputs.upToDateWhen { false }

        dependsOn("collectSubModules")

        val rootIcon = rootProject.file("src/main/resources/assets/$modId/icon.png")
        val resourcesFile = layout.projectDirectory.file("src/main/resources/assets/$wrapperModId/icon.png").asFile
        val buildIconFile = layout.buildDirectory.file("resources/main/assets/$wrapperModId/icon.png").get().asFile

        doLast {
            if (rootIcon.exists()) {
                if (!resourcesFile.exists()) {
                    println("⚠ Icon not found in subproject; copying from root project")
                    buildIconFile.parentFile.mkdirs()
                    rootIcon.copyTo(buildIconFile, overwrite = true)
                    println("✓ Icon copied successfully: ${rootIcon.name} -> ${buildIconFile.name}")
                }
            } else {
                println("⚠ Icon not found in root project; skipping icon copy")
            }
        }

        doLast {
            val jars = ArrayList<Map<String, String>>()
            val jarsDir = layout.buildDirectory.dir("tmp/submods/META-INF/jars").get().asFile

            if (jarsDir.exists() && jarsDir.isDirectory) {
                val jarFiles = jarsDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".jar") &&
                            !file.name.contains("-dev.jar") &&
                            !file.name.contains("-sources.jar") &&
                            !file.name.contains("-shadow.jar")
                }

                jarFiles?.sortedBy { it.name }?.forEach { jarFile ->
                    jars.add(mapOf("file" to "META-INF/jars/${jarFile.name}"))
                }
            }

            val minecraftVersions = mutableListOf<String>()
            fabricSubprojects.forEach { subproject ->
                try {
                    val minecraftVersion = subproject.property("minecraft_dependency") as String
                    if (minecraftVersion.isNotBlank()) {
                        minecraftVersions.add(minecraftVersion)
                        println("Collected Minecraft version: $minecraftVersion")
                    }
                } catch (e: Exception) {
                    println("⚠ Unable to get Minecraft version from subproject ${subproject.name}")
                }
            }

            val jsonFile = layout.buildDirectory.file("resources/main/fabric.mod.json").get().asFile
            if (jsonFile.exists()) {
                val slurper = JsonSlurper()

                @Suppress("UNCHECKED_CAST")
                val jsonContent = slurper.parse(jsonFile) as MutableMap<String, Any>

                jsonContent["jars"] = jars

                @Suppress("UNCHECKED_CAST")
                val depends = jsonContent["depends"] as? MutableMap<String, Any>
                depends?.put("minecraft", minecraftVersions)

                val builder = JsonBuilder(jsonContent)
                jsonFile.bufferedWriter().use { writer ->
                    writer.write(builder.toPrettyString())
                }

                println("- JAR file count: ${jars.size}")
                jars.forEach { jar ->
                    println("  - ${jar["file"]}")
                }
                println("✅ Minecraft dependencies updated to: $minecraftVersions")
            } else {
                println("Warning: generated fabric.mod.json not found: ${jsonFile.absolutePath}")
            }
        }
    }
}
