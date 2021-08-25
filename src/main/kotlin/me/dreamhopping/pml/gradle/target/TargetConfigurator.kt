package me.dreamhopping.pml.gradle.target

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import me.dreamhopping.pml.gradle.data.minecraft.VersionJson
import me.dreamhopping.pml.gradle.mappings.LeatherMappingProvider.Companion.isLeatherAvailable
import me.dreamhopping.pml.gradle.mappings.McpMappingProvider.Companion.isMcpAvailable
import me.dreamhopping.pml.gradle.mappings.YarnMappingProvider.Companion.isYarnAvailable
import me.dreamhopping.pml.gradle.target.ArtifactVersionGenerator.buildMappedJarArtifactVersion
import me.dreamhopping.pml.gradle.tasks.download.DownloadTask
import me.dreamhopping.pml.gradle.tasks.download.assets.DownloadAssetsTask
import me.dreamhopping.pml.gradle.tasks.loader.CreateClassPathInfoTask
import me.dreamhopping.pml.gradle.tasks.map.apply.ApplyMappingsTask
import me.dreamhopping.pml.gradle.tasks.map.generate.GenerateMappingsTask
import me.dreamhopping.pml.gradle.tasks.merge.MergeTask
import me.dreamhopping.pml.gradle.tasks.run.GenRunConfigTask
import me.dreamhopping.pml.gradle.tasks.run.IRunTask
import me.dreamhopping.pml.gradle.tasks.run.RunTask
import me.dreamhopping.pml.gradle.tasks.strip.StripTask
import me.dreamhopping.pml.gradle.user.TargetData
import me.dreamhopping.pml.gradle.user.UserData
import me.dreamhopping.pml.gradle.util.*
import me.dreamhopping.pml.runtime.start.Start
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

object TargetConfigurator {
    private const val VERSION_MANIFEST_PATH = "versions/manifest.json"
    private const val VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json"
    private val readOnlyProjects = hashSetOf<Project>()

    fun configureTarget(project: Project, target: TargetData, parent: UserData, addDefaultMaps: Boolean) {
        if (parent.separateVersionJars) setUpJarTasks(project, target)
        if (parent.loader) setUpLoaderTasks(project, target)

        if (addDefaultMaps) {
            when {
                project.isLeatherAvailable(target.version) -> target.leather()
                project.isYarnAvailable(target.version) -> target.yarn()
                project.isMcpAvailable(target.version) -> target.mcp()
            }
        }

        val versionJson = project.dataFile(target.versionJsonPath)
        if (!versionJson.exists()) {
            download(project.getVersionJsonUrl(target.version, target.type), versionJson)
        }
        val version = try {
            versionJson.fromJson<VersionJson>()
        } catch (ex: JsonParseException) {
            // Version JSON is corrupted - redownload and try again.
            download(project.getVersionJsonUrl(target.version), versionJson, ignoreInitialState = true)
            versionJson.fromJson()
        }

        assert(version.id == target.version)

        val minecraftConfiguration = project.configurations.maybeCreate(target.mcConfigName)
        val minecraftLibrariesConfiguration = project.configurations.maybeCreate(target.mcLibsConfigName)
        val minecraftNativeLibrariesConfiguration = project.configurations.maybeCreate(target.mcNativeLibsConfigName)

        minecraftLibrariesConfiguration.extendsFrom(minecraftNativeLibrariesConfiguration)
        minecraftConfiguration.extendsFrom(minecraftLibrariesConfiguration)

        if (version.downloads.server == null) {
            project.dependencies.add(minecraftConfiguration.name, "net.minecraft:client:${target.version}:resources")
        } else {
            project.dependencies.add(minecraftConfiguration.name, "net.minecraft:resources:${target.version}")
        }
        refreshMcDep(project, target)

        version.libraries.forEach { lib ->
            val config = minecraftLibrariesConfiguration.takeUnless { lib.natives != null }
                ?: minecraftNativeLibrariesConfiguration

            if (lib.name.contains("java-objc-bridge")) return@forEach // macOS bruh moment
            lib.addToDependencies(project, config.name)
        }

        val downloadClientTask = project.tasks.register(target.downloadClientName, DownloadTask::class.java) {
            it.url = version.downloads.client.url
            it.sha1 = version.downloads.client.sha1
            it.output = project.repoFile("net.minecraft", "client", target.version)
        }

        val downloadServerTask = version.downloads.server?.let {
            project.tasks.register(target.downloadServerName, DownloadTask::class.java) { task ->
                task.url = it.url
                task.sha1 = it.sha1
                task.output = project.repoFile("net.minecraft", "server", target.version)
            }
        }

        val stripClientTask =
            StripTask.register(target.stripClientName, "client", target.version, project, downloadClientTask)
        val stripServerTask = downloadServerTask?.let {
            StripTask.register(target.stripServerName, "server", target.version, project, it)
        }
        val mergeClassesTask = stripServerTask?.let {
            project.tasks.register(target.mergeClassesName, MergeTask::class.java) {
                it.dependsOn(stripClientTask.name, stripServerTask.name)
                it.clientJar = stripClientTask.get().classOutput
                it.serverJar = stripServerTask.get().classOutput
                it.outputJar = project.repoFile("net.minecraft", "merged", target.version)
            }
        }
        val mergeResourcesTask = stripServerTask?.let {
            project.tasks.register(target.mergeResourcesName, MergeTask::class.java) {
                it.dependsOn(stripClientTask.name, stripServerTask.name)
                it.clientJar = stripClientTask.get().resourceOutput
                it.serverJar = stripServerTask.get().resourceOutput
                it.outputJar = project.repoFile("net.minecraft", "resources", target.version)
            }
        }

        val generateMappingsTask =
            project.tasks.register(target.generateMappingsName, GenerateMappingsTask::class.java) {
                it.mappingProviders = target.mappings
                it.outputFile = project.repoFile(
                    "net.minecraft",
                    "mapped",
                    target.buildMappedJarArtifactVersion(),
                    "mappings",
                    "json"
                )
            }

        project.tasks.register(target.reverseGenerateMappingsName, GenerateMappingsTask::class.java) {
            it.mappingProviders = target.mappings
            it.reverse = true
            it.outputFile = project.repoFile(
                "net.minecraft",
                "mapped",
                target.buildMappedJarArtifactVersion(),
                "mappings-reversed",
                "json"
            )
        }

        val deobfuscateTask = project.tasks.register(target.deobfuscateName, ApplyMappingsTask::class.java) {
            it.dependsOn(generateMappingsTask.name, mergeClassesTask?.name ?: stripClientTask.name)
            it.inputJar = mergeClassesTask?.get()?.outputJar ?: stripClientTask.get().classOutput
            it.mappings = generateMappingsTask.get().outputFile
            it.accessTransformers = target.accessTransformers
            it.outputJar = project.repoFile("net.minecraft", "mapped", target.buildMappedJarArtifactVersion())
        }

        val downloadAssetIndexTask =
            project.tasks.findByName(version.downloadAssetIndexName) as? DownloadTask ?: project.tasks.register(
                version.downloadAssetIndexName,
                DownloadTask::class.java
            ) {
                it.url = version.assetIndex.url
                it.sha1 = version.assetIndex.sha1
                it.output = project.dataFile("assets/indexes/${version.assets}.json")
            }.get()

        val downloadAssets = project.tasks.register(target.downloadAssetsName, DownloadAssetsTask::class.java) {
            it.dependsOn(downloadAssetIndexTask.name)
            it.assetIndex = downloadAssetIndexTask.output
            it.runDir = target.runDir
        }

        project.tasks.register(target.extractNativesName, Copy::class.java) { task ->
            task.from(*minecraftNativeLibrariesConfiguration.map { project.zipTree(it) }.toTypedArray())
            task.into(project.dataFile("natives/${target.version}"))
            task.include(System.mapLibraryName("*"))
        }

        val runClientTask = project.tasks.register(target.runClientName, RunTask::class.java) {
            it.group = "minecraft"
            target.setUpRunTask(version, it, true)
        }

        val runServerTask = project.tasks.register(target.runServerName, RunTask::class.java) {
            it.group = "minecraft"
            target.setUpRunTask(version, it, false)
        }

        val genRunConfigClientTask =
            project.tasks.register(target.genClientRunConfigName, GenRunConfigTask::class.java) {
                target.setUpRunTask(version, it, true)
                it.sourceSetName = target.sourceSetName
                it.select = true
                it.configName = "Minecraft ${target.version} Client"
            }

        val genRunConfigServerTask =
            project.tasks.register(target.genServerRunConfigName, GenRunConfigTask::class.java) {
                target.setUpRunTask(version, it, false)
                it.sourceSetName = target.sourceSetName
                it.select = false
                it.configName = "Minecraft ${target.version} Server"
            }

        project.tasks.register(target.genRunConfigsName) {
            it.dependsOn(genRunConfigClientTask.name, genRunConfigServerTask.name)
        }

        project.tasks.register(target.setupName) {
            it.dependsOn(deobfuscateTask.name)
            mergeResourcesTask?.apply { it.dependsOn(name) }
        }

        project.afterEvaluate {
            // We could technically do this outside of afterEvaluate, but we change the dependencies of these
            // configurations quite a bit before this point, and doing it here ensures it does not get resolved accidentally.
            readOnlyProjects.add(project) // From this point on, changes to TargetData will not do anything.

            val sourceSet = project.sourceSets.maybeCreate(target.sourceSetName)

            target.runDir.mkdirs()
            downloadAssets.configure { it.runDir = target.runDir }
            arrayOf(runClientTask, runServerTask, genRunConfigClientTask, genRunConfigServerTask).forEach { p ->
                p.configure {
                    it as IRunTask
                    it.runDir = target.runDir.absolutePath
                }
            }

            val rtVersion = javaClass.`package`.implementationVersion
            val rtArtifact = javaClass.`package`.implementationTitle

            val rtFile = project.repoFile("me.dreamhopping.pml", "gradle-runtime", rtVersion)
            val rtSourcesFile = project.repoFile("me.dreamhopping.pml", "gradle-runtime", rtVersion, "sources")

            if (!rtFile.exists()) {
                rtFile.parentFile?.mkdirs()
                javaClass.getResourceAsStream("/$rtArtifact-$rtVersion-runtime.jar")
                    .use { rtFile.outputStream().use { out -> it?.copyTo(out) ?: error("Failed to copy runtime") } }
            }
            if (!rtSourcesFile.exists()) {
                rtSourcesFile.parentFile?.mkdirs()
                javaClass.getResourceAsStream("/$rtArtifact-$rtVersion-runtime-sources.jar")
                    .use {
                        rtSourcesFile.outputStream()
                            .use { out -> it?.copyTo(out) ?: error("Failed to copy runtime-sources") }
                    }
            }

            val t = project.tasks.getByName(sourceSet.compileJavaTaskName)
            t.dependsOn(target.deobfuscateName)
            mergeResourcesTask?.let { t.dependsOn(it.name) }

            project.dependencies.add(
                sourceSet.implementationConfigurationName,
                "me.dreamhopping.pml:gradle-runtime:$rtVersion"
            )
            project.dependencies.add(sourceSet.implementationConfigurationName, parent.mainSourceSet.runtimeClasspath)

            val loaderConfig = project.configurations.maybeCreate(LOADER_CONFIG)
            val implementationConfig = project.configurations.getByName(sourceSet.implementationConfigurationName)

            loaderConfig.dependencies.forEach {
                project.dependencies.add(
                    implementationConfig.name, mapOf(
                        "group" to it.group,
                        "name" to it.name,
                        "version" to it.version,
                        "classifier" to sourceSet.name
                    )
                )
            }

            implementationConfig
                .extendsFrom(minecraftConfiguration)

            if (parent.separateVersionJars) {
                project.extensions.findByType(PublishingExtension::class.java)?.let { publishData ->
                    for (publication in publishData.publications) {
                        if (publication is MavenPublication) {
                            publication.artifact(project.tasks.getByName(sourceSet.jarTaskName))
                        }
                    }
                }
            }
        }
    }

    private fun <T> TargetData.setUpRunTask(versionJson: VersionJson, task: T, client: Boolean)
        where T : IRunTask,
              T : Task {
        val set = task.project.sourceSets.maybeCreate(sourceSetName)

        if (client) task.dependsOn(extractNativesName, downloadAssetsName)
        task.dependsOn(set.classesTaskName)
        task.args = listOf()
        if (client) {
            task.vmArgs = listOfNotNull(
                "-Djava.library.path=${task.project.dataFile("natives/$version").absolutePath}",
                "-XstartOnFirstThread".takeIf { Os.isFamily(Os.FAMILY_MAC) })
        } else {
            task.vmArgs = listOf()
        }
        task.classpath = set.runtimeClasspath
        task.runDir = runDir.path
        task.environment = mapOf(
            "PG_IS_SERVER" to (!client).toString(),
            "PG_ASSET_INDEX" to versionJson.assets,
            "PG_ASSETS_DIR" to task.project.dataFile("assets").absolutePath,
            "PG_MAIN_CLASS" to if (client) clientMainClass else serverMainClass
        )
        task.mainClass = Start::class.java.name
    }

    fun setUpGlobalTasks(project: Project, data: UserData) {
        val loaderConfig = project.configurations.maybeCreate(LOADER_CONFIG)
        val setupTask = project.tasks.register("setup") {
            it.group = "minecraft"
        }
        val genRunConfigsTask = project.tasks.register("genRunConfigs") {
            it.group = "minecraft"
        }
        project.afterEvaluate {
            project.configurations.getByName(data.mainSourceSet.implementationConfigurationName)
                .extendsFrom(loaderConfig)
            setupTask.configure { task ->
                task.dependsOn(*data.targets.map { it.setupName }.toTypedArray())
            }
            genRunConfigsTask.configure { task ->
                task.dependsOn(*data.targets.map { it.genRunConfigsName }.toTypedArray())
            }

            val sourceJar = project.tasks.findByName(data.mainSourceSet.sourcesJarTaskName) as? Jar
                ?: project.tasks.register(data.mainSourceSet.sourcesJarTaskName, Jar::class.java) {
                    it.archiveClassifier.set("sources")
                    it.group = "build"
                    it.from(data.mainSourceSet.allSource)
                }.get()

            for (target in data.targets) {
                sourceJar.from(project.sourceSets.maybeCreate(target.sourceSetName).allSource)
            }

            project.extensions.findByType(PublishingExtension::class.java)?.let { publishData ->
                for (publication in publishData.publications) {
                    if (publication is MavenPublication) {
                        publication.artifact(sourceJar)
                    }
                }
            }
        }
    }

    fun setUpJarTasks(project: Project, target: TargetData) {
        val set = project.sourceSets.maybeCreate(target.sourceSetName)

        project.tasks.register("reobf${set.jarTaskName}", ApplyMappingsTask::class.java) {
            it.group = "build"
            it.dependsOn(set.jarTaskName, target.reverseGenerateMappingsName)
            it.inputJar = (project.tasks.getByName(set.jarTaskName) as Jar).archiveFile.get().asFile
            it.accessTransformers = setOf()
            it.mappings = (project.tasks.getByName(target.reverseGenerateMappingsName) as GenerateMappingsTask).outputFile
            it.outputJar = File(it.temporaryDir, it.inputJar!!.name)
        }

        project.tasks.register(set.jarTaskName, Jar::class.java) {
            it.dependsOn(set.compileJavaTaskName, set.processResourcesTaskName)
            it.finalizedBy("reobf${set.jarTaskName}")
            it.from(set.output)
            it.from(project.sourceSets.getByName("main").output)
            it.archiveClassifier.set(set.name)
            it.group = "build"
        }

        project.tasks.getByName("assemble").dependsOn(set.jarTaskName)
    }

    @Suppress("UnstableApiUsage")
    fun setUpLoaderTasks(project: Project, target: TargetData) {
        val t = project.tasks.register(target.createClassPathInfoName, CreateClassPathInfoTask::class.java) {
            it.classpath = project.configurations.getByName(target.mcLibsConfigName)
            it.output = File(it.temporaryDir, "pml-classpath.txt")
        }

        project.afterEvaluate {
            val set = project.sourceSets.maybeCreate(target.sourceSetName)

            val task = project.tasks.getByName(set.processResourcesTaskName) as ProcessResources
            task.dependsOn(t.name)
            task.from(t.get().output)
        }
    }

    fun refreshMcDep(project: Project, target: TargetData) {
        if (project in readOnlyProjects) return
        val config = project.configurations.maybeCreate(target.mcConfigName)
        config.dependencies.removeIf {
            it.group == "net.minecraft" && it.name == "mapped"
        }
        val version = target.buildMappedJarArtifactVersion()
        project.dependencies.add(config.name, "net.minecraft:mapped:$version")
        project.tasks.findByName(target.generateMappingsName)?.let {
            it as GenerateMappingsTask
            it.outputFile = project.repoFile("net.minecraft", "mapped", version, "mappings", "json")
        }
        project.tasks.findByName(target.reverseGenerateMappingsName)?.let {
            it as GenerateMappingsTask
            it.outputFile = project.repoFile("net.minecraft", "mapped", version, "mappings-reversed", "json")
        }
        project.tasks.findByName(target.deobfuscateName)?.let {
            it as ApplyMappingsTask
            it.outputJar = project.repoFile("net.minecraft", "mapped", version)
        }
    }

    private fun Project.getVersionJsonUrl(version: String, type: String? = null): String {
        val manifestFile = dataFile(VERSION_MANIFEST_PATH)
        if (!manifestFile.exists()) manifestFile.redownloadVersionManifest()
        return try {
            manifestFile.findVersionJsonUrl(version, type)
        } catch (ex: JsonParseException) {
            null
        } ?: manifestFile.let {
            // Manifest file is corrupted or not up-to-date - redownload and try again.
            it.redownloadVersionManifest()
            it.findVersionJsonUrl(version, type)
        }
        ?: error("Invalid version $version")
    }

    private fun File.findVersionJsonUrl(version: String, releaseType: String?) =
        fromJson<JsonObject>()["versions"].asJsonArray.map { it.asJsonObject }
            .find { it["id"].asString == version && releaseType?.let { r -> it["type"].asString == r } ?: true }
            ?.let { it["url"].asString }

    private fun File.redownloadVersionManifest() = download(VERSION_MANIFEST_URL, this, ignoreInitialState = true)

    private val TargetData.versionJsonPath get() = "versions/$version/manifest.json"
    private val TargetData.sourceSetName get() = "mc$version"
    private val TargetData.mcConfigName get() = "mc$version"
    private val TargetData.mcLibsConfigName get() = "mcLibs$version"
    private val TargetData.mcNativeLibsConfigName get() = "mcNativeLibs$version"
    private val TargetData.downloadClientName get() = "downloadClient$version"
    private val TargetData.downloadServerName get() = "downloadServer$version"
    private val TargetData.stripClientName get() = "stripClient$version"
    private val TargetData.stripServerName get() = "stripServer$version"
    private val TargetData.mergeClassesName get() = "mergeClasses$version"
    private val TargetData.mergeResourcesName get() = "mergeResources$version"
    private val TargetData.generateMappingsName get() = "generateMappings$version"
    private val TargetData.reverseGenerateMappingsName get() = "reverseGenerateMappings$version"
    private val TargetData.deobfuscateName get() = "deobfuscate$version"
    private val TargetData.setupName get() = "setup$version"
    private val VersionJson.downloadAssetIndexName get() = "downloadAssetIndex$assets"
    private val TargetData.downloadAssetsName get() = "downloadAssets$version"
    private val TargetData.extractNativesName get() = "extractNatives$version"
    private val TargetData.runClientName get() = "runClient$version"
    private val TargetData.runServerName get() = "runServer$version"
    private val TargetData.createClassPathInfoName get() = "createClassPathInfo$version"
    private val TargetData.genClientRunConfigName get() = "genRunConfigClient$version"
    private val TargetData.genServerRunConfigName get() = "genRunConfigServer$version"
    private val TargetData.genRunConfigsName get() = "genRunConfigs$version"
}