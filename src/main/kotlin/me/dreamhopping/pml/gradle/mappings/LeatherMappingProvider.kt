package me.dreamhopping.pml.gradle.mappings

import me.dreamhopping.pml.gradle.data.mappings.Mappings
import me.dreamhopping.pml.gradle.data.mappings.leather.LeatherMappings
import me.dreamhopping.pml.gradle.util.*
import org.gradle.api.Project
import java.io.File
import java.util.zip.ZipFile

class LeatherMappingProvider(
    private var version: String?,
    private val project: Project,
    private val minecraftVersion: String
) : MappingProvider {
    override val id get() = "leather-$version"
    override val mappings = Mappings.mappings { }
        get() {
            if (!haveMappingsBeenLoaded) load(project, minecraftVersion)
            return field
        }
    override val mixinMappings: Mappings = Mappings.mappings { }
        get() {
            if (!haveMixinMappingsBeenLoaded) loadMixin(project, minecraftVersion)
            return field
        }

    private var haveMixinMappingsBeenLoaded = false
    private var haveMappingsBeenLoaded = false

    init {
        version = getVersion(project, minecraftVersion)
    }

    private fun load(project: Project, minecraftVersion: String) {
        val version = getVersion(project, minecraftVersion)

        val path = buildMavenPath("org.bookmc", "leather", version)
        val mapZip = File(project.repoDir, path)
        download("https://metadata.bookmc.org/v1/mappings/$minecraftVersion/$version/download", mapZip)

        haveMappingsBeenLoaded = true
        ZipFile(mapZip).use { zip ->
            val entry = zip.getEntry("mappings/mapping.tiny")
            zip.getInputStream(entry).bufferedReader().use { reader ->
                for (line in reader.lines().skip(1)) {
                    val parts = line.split(" ", "\t")
                    when (parts[0]) {
                        "CLASS" -> mappings.classes[parts[1]] = parts[2]
                        "METHOD" -> mappings.methods["${parts[1]}/${parts[3]}${parts[2]}"] = parts[4]
                        "FIELD" -> mappings.fields["${parts[1]}/${parts[3]}"] = parts[4]
                    }
                }
            }
        }
    }

    private fun loadMixin(project: Project, minecraftVersion: String) {
        val version = getVersion(project, minecraftVersion)

        val path = buildMavenPath("org.bookmc", "leather", version)
        val mapZip = File(project.repoDir, path)
        download("https://metadata.bookmc.org/v1/mappings/$minecraftVersion/$version/download", mapZip)

        haveMixinMappingsBeenLoaded = true
        ZipFile(mapZip).use { zip ->
            val entry = zip.getEntry("mappings/mapping.tiny")
            val (from, to) = zip.getInputStream(entry).use { fromTo(it) }

            zip.getInputStream(entry).bufferedReader().use { reader ->
                val tree = parseTree(reader)

                for (clazz in tree.classes) {
                    val fromClass = clazz.getName(from)
                    val toClass = clazz.getName(to)

                    mixinMappings.classes[toClass] = fromClass

                    for (method in clazz.methods) {
                        mixinMappings.methods["$toClass/${method.getName(to)} ${method.getDescriptor(to)}"] = "$fromClass/${method.getName(from)} ${method.getDescriptor(from)}"
                    }
                    for (field in clazz.fields) {
                        mixinMappings.methods["$toClass/${field.getName(to)} ${field.getDescriptor(to)}"] = "$fromClass/${field.getName(from)} ${field.getDescriptor(from)}"
                    }
                }
            }
        }
    }

    private fun getVersion(project: Project, minecraftVersion: String) =
        version ?: project.getLatestVersion(minecraftVersion) ?: error("No Leather versions for $minecraftVersion")

    companion object {
        private val String.versionsUrl get() = "https://metadata.bookmc.org/v1/mappings/$this"

        fun Project.isLeatherAvailable(mcVersion: String) = getLatestVersion(mcVersion) != null

        private fun Project.getLatestVersion(mcVersion: String): String? {
            val versionsFile = dataFile("mappings/leather/$mcVersion.json")

            return versionsFile.getLatestVersion(mcVersion) ?: versionsFile.let {
                it.downloadVersionInfo(mcVersion)
                it.toLatestVersion()
            }
        }

        private fun File.getLatestVersion(mcVersion: String): String? {
            if (!exists()) downloadVersionInfo(mcVersion)
            return toLatestVersion()
        }

        private fun File.toLatestVersion() = fromJson<LeatherMappings>().latest?.version

        private fun File.downloadVersionInfo(mcVersion: String) {
            download(mcVersion.versionsUrl, this, ignoreInitialState = true)
        }
    }
}