package me.dreamhopping.pml.gradle.tasks.map.apply

import org.gradle.api.tasks.*
import org.gradle.jvm.tasks.Jar
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

@CacheableTask
abstract class ApplyMappingsJarTask : Jar() {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    var inputJar: File? = null

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var accessTransformers: Set<File>? = null

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    var mappings: File? = null

    @Inject
    abstract fun getWorkerExecutor(): WorkerExecutor

    @TaskAction
    fun apply() {
        getWorkerExecutor().noIsolation().submit(ApplyMappingsAction::class.java) {
            it.inputJar.set(inputJar)
            it.accessTransformers.set(accessTransformers)
            it.mappings.set(mappings)
            it.outputJar.set(archiveFile.get())
        }
    }
}