import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources

@Suppress("unused")
abstract class ModPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("java")

        configureArchives()
        configureJava()
        configureJavaCompile()
        configureResources()
        configureJar()
    }

    private fun Project.configureArchives() {
        extensions.configure<BasePluginExtension> {
            archivesName.set(modArchivesBaseName)
        }
    }

    private fun Project.configureJava() {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
    }

    private fun Project.configureJavaCompile() {
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(javaVersion.majorVersion.toInt())
            options.compilerArgs.add("-Xlint:deprecation")
            options.compilerArgs.add("-Xlint:unchecked")
        }
    }

    private fun Project.configureResources() {
        tasks.withType<ProcessResources>().configureEach {
            val props = placeholderProps
            inputs.properties(props)
            filteringCharset = "UTF-8"
            filesMatching(listOf("fabric.mod.json", "*.mod.json")) {
                expand(props)
            }
        }
    }

    private fun Project.configureJar() {
        tasks.withType<Jar>().configureEach {
            from(rootProject.file("LICENSE")) {
                rename { originalName ->
                    "${originalName}_${modArchivesBaseName}"
                }
            }
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            manifest {
                attributes(
                    mapOf(
                        "Implementation-Title" to modName,
                        "Implementation-Version" to project.version
                    )
                )
            }
        }
    }
}
