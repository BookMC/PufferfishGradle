package me.dreamhopping.pml.gradle.mappings

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import me.dreamhopping.pml.gradle.data.mappings.Mappings
import me.dreamhopping.pml.gradle.util.*
import org.gradle.api.Project
import java.io.File
import java.util.zip.ZipFile

class McpMappingProvider(
    private var channel: String?,
    private var version: String?,
    private val project: Project,
    private val minecraftVersion: String
) : MappingProvider {
    override val id get() = "mcp-${channel}_$version"
    override val mappings = Mappings.mappings { }
        get() {
            if (!haveMappingsBeenLoaded) load(project, minecraftVersion)
            return field
        }
    override val mixinMappings = Mappings.mappings { }
        get() {
            if (!haveMixinMappingsBeenLoaded) loadMixin(project, minecraftVersion)
            return field
        }

    private var haveMappingsBeenLoaded = false
    private var haveMixinMappingsBeenLoaded = false

    init {
        val (ch, verInfo) = getMappingVersions(project, minecraftVersion)
        channel = ch
        version = verInfo.first
    }

    private fun load(project: Project, minecraftVersion: String) {
        val (channel, versionInfo) = getMappingVersions(project, minecraftVersion)
        val (version, mcVersion) = versionInfo

        val csvZip = project.repoFile(MCP_GROUP, "mcp_$channel", "$version-$mcVersion", extension = "zip")
        download(
            "https://maven.minecraftforge.net/${
                buildMavenPath(
                    MCP_GROUP,
                    "mcp_$channel",
                    "$version-$mcVersion",
                    extension = "zip"
                )
            }",
            csvZip
        )

        val parts = mcVersion.split(".")
        val is13 = parts.size < 2 || parts[1].toIntOrNull()?.let { it < 13 } != true

        haveMappingsBeenLoaded = true

        if (!is13) {
            loadSrg(project, mcVersion, csvZip)
        } else {
            loadMcpConfig(project, mcVersion, csvZip)
        }
    }

    private fun loadSrg(project: Project, mcVersion: String, csv: File) {
        val (fields, methodInfo) = csv.loadCsvData()
        val (methods, _) = methodInfo

        val path = buildMavenPath(MCP_GROUP, "mcp", mcVersion, "srg", "zip")
        val srgZip = File(project.repoDir, path)
        download("https://maven.minecraftforge.net/$path", srgZip)

        ZipFile(srgZip).use { zip ->
            zip.getInputStream(zip.getEntry("joined.srg")).bufferedReader().use { reader ->
                for (line in reader.lines()) {
                    val parts = line.split(" ")
                    when (parts[0]) {
                        "CL:" -> mappings.classes[parts[1]] = parts[2]
                        "FD:" -> mappings.fields[parts[1]] = parts[2].extractName(fields)
                        "MD:" -> {
                            // val owner = parts[1].substringBeforeLast('/')
                            val srgName = parts[3].extractName(emptyMap())
                            val deobfName = methods[srgName] ?: srgName
                            mappings.methods["${parts[1]}${parts[2]}"] = deobfName
                        }
                    }
                }
            }
        }
    }

    private fun loadMcpConfig(project: Project, mcVersion: String, csv: File) {
        val (fields, methodInfo) = csv.loadCsvData()
        val (methods, _) = methodInfo

        val path = buildMavenPath(MCP_GROUP, "mcp_config", mcVersion, extension = "zip")
        val mcpConfigZip = File(project.repoDir, path)
        download("https://maven.minecraftforge.net/$path", mcpConfigZip)

        ZipFile(mcpConfigZip).use { zip ->
            zip.getInputStream(zip.getEntry("config/joined.tsrg")).bufferedReader().use { reader ->
                var currentClass = ""
                for (line in reader.lines()) {
                    if (line.startsWith('\t') || line.startsWith(' ')) {
                        val parts = line.trim().split(" ")
                        if (parts.size == 3) {
                            mappings.methods["$currentClass/${parts[0]}${parts[1]}"] = methods[parts[2]] ?: parts[2]
                        } else {
                            mappings.fields["$currentClass/${parts[0]}"] = fields[parts[1]] ?: parts[1]
                        }
                    } else {
                        val parts = line.split(" ")
                        currentClass = parts[0]
                        mappings.classes[parts[0]] = parts[1]
                    }
                }
            }
        }
    }

    private fun loadMixin(project: Project, minecraftVersion: String) {
        val (channel, versionInfo) = getMappingVersions(project, minecraftVersion)
        val (version, mcVersion) = versionInfo

        val csvZip = project.repoFile(MCP_GROUP, "mcp_$channel", "$version-$mcVersion", extension = "zip")
        download(
            "https://maven.minecraftforge.net/${
                buildMavenPath(
                    MCP_GROUP,
                    "mcp_$channel",
                    "$version-$mcVersion",
                    extension = "zip"
                )
            }",
            csvZip
        )

        val parts = mcVersion.split(".")
        val is13 = parts.size < 2 || parts[1].toIntOrNull()?.let { it < 13 } != true

        haveMixinMappingsBeenLoaded = true

        if (!is13) {
            loadSrgMixin(project, mcVersion)
        } else {
            loadMcpConfigMixin(project, mcVersion, csvZip)
        }
    }

    private fun loadSrgMixin(project: Project, mcVersion: String) {
        val path = buildMavenPath(MCP_GROUP, "mcp", mcVersion, "srg", "zip")
        val srgZip = File(project.repoDir, path)
        download("https://maven.minecraftforge.net/$path", srgZip)

        ZipFile(srgZip).use { zip ->
            zip.getInputStream(zip.getEntry("joined.srg")).bufferedReader().use { reader ->
                for (line in reader.lines()) {
                    val parts = line.split(" ")
                    when (parts[0]) {
                        "CL:" -> mappings.classes[parts[1]] = parts[2]
                        "FD:" -> mappings.fields[parts[1]] = parts[2]
                        "MD:" -> mappings.methods["${parts[1]}${parts[2]}"] = parts[3] + " " + parts[4]
                    }
                }
            }
        }
    }

    private fun loadMcpConfigMixin(project: Project, mcVersion: String, csv: File) {
        val (fields, methodInfo) = csv.loadCsvData()
        val (methods, _) = methodInfo

        val path = buildMavenPath(MCP_GROUP, "mcp_config", mcVersion, extension = "zip")
        val mcpConfigZip = File(project.repoDir, path)
        download("https://maven.minecraftforge.net/$path", mcpConfigZip)

        ZipFile(mcpConfigZip).use { zip ->
            zip.getInputStream(zip.getEntry("config/joined.tsrg")).bufferedReader().use { reader ->
                var currentClass = ""
                for (line in reader.lines()) {
                    if (line.startsWith('\t') || line.startsWith(' ')) {
                        val parts = line.trim().split(" ")
                        if (parts.size == 3) {
                            mappings.methods["$currentClass/${parts[0]}${parts[1]}"] = methods[parts[2]] ?: parts[2]
                        } else {
                            mappings.fields["$currentClass/${parts[0]}"] = "$currentClass/${parts[1]}"
                        }
                    } else {
                        val parts = line.split(" ")
                        currentClass = parts[0]
                        mappings.classes[parts[0]] = parts[1]
                    }
                }
            }
        }
    }


    private fun String.extractName(csv: Map<String, String>) = substringAfterLast('/').let { csv[it] ?: it }

    private fun File.loadCsvData() =
        ZipFile(this).use { it.loadCsv("fields.csv") to (it.loadCsv("methods.csv") to it.loadCsv("params.csv")) }

    private fun ZipFile.loadCsv(name: String) = getEntry(name).let { entry ->
        hashMapOf<String, String>().also {
            getInputStream(entry).bufferedReader().use { reader ->
                for (line in reader.lines().skip(1)) {
                    val parts = line.split(",")
                    it[parts[0]] = parts[1]
                }
            }
        }
    }

    private fun getMappingVersions(project: Project, minecraftVersion: String): Pair<String, Pair<String, String>> {
        channel?.let { ch ->
            version?.let { ver ->
                return ch to (ver to findMcVersion(project, ch, ver))
            }
        }

        val version =
            project.getVersionInfo(minecraftVersion) ?: error("No MCP mappings for Minecraft $minecraftVersion")

        val (ch, ver) = version.getVersionInfo("stable") ?: version.getVersionInfo("snapshot")
        ?: error("No MCP mappings for Minecraft $minecraftVersion")
        return ch to (ver to minecraftVersion)
    }

    private fun findMcVersion(
        project: Project,
        channel: String,
        version: String,
        throwImmediately: Boolean = false
    ): String {
        val versionFile = project.dataFile("mappings/mcp.json")
        val versionObj = versionFile.getVersionJson()

        versionObj.entrySet().forEach { (mcVer, versions) ->
            versions as JsonObject
            if (!versions.has(channel)) return@forEach
            versions.getAsJsonArray(channel).forEach { ver ->
                if (ver.asString == version) return mcVer
            }
        }

        if (!throwImmediately) {
            versionFile.downloadVersionJson()
            findMcVersion(project, channel, version, true)
        }

        error("Invalid MCP version ${channel}_$version")
    }

    companion object {
        private const val VERSIONS_URL = "http://export.mcpbot.bspk.rs/versions.json"
        private const val MCP_GROUP = "de.oceanlabs.mcp"
        private var alreadyRetried = false

        fun Project.isMcpAvailable(version: String) =
            getVersionInfo(version)?.let { (it.getVersionInfo("stable") ?: it.getVersionInfo("snapshot")) != null }
                ?: false

        private fun Project.getVersionInfo(mcVersion: String): JsonObject? {
            val versionFile = project.dataFile("mappings/mcp.json")
            val versionObj = versionFile.getVersionJson()

            return (versionObj[mcVersion] ?: versionFile.takeIf { !alreadyRetried }?.let {
                it.downloadVersionJson()
                alreadyRetried = true
                it.toVersionJson()[mcVersion]
            }) as JsonObject?
        }

        private fun File.getVersionJson(): JsonObject {
            if (!exists()) downloadVersionJson()
            return toVersionJson()
        }

        private fun JsonObject.getVersionInfo(channel: String) =
            getAsJsonArray(channel)?.takeIf { it.size() > 0 }?.let { channel to it[0].asString }

        private fun File.toVersionJson() = try {
            fromJson<JsonObject>()
        } catch (e: JsonParseException) {
            downloadVersionJson()
            fromJson<JsonObject>()
        }

        private fun File.downloadVersionJson() {
            download(VERSIONS_URL, this, ignoreInitialState = true)
        }
    }
}