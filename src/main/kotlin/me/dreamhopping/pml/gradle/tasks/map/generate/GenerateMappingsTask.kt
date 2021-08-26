package me.dreamhopping.pml.gradle.tasks.map.generate

import me.dreamhopping.pml.gradle.mappings.MappingProvider
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

@CacheableTask
abstract class GenerateMappingsTask : DefaultTask() {
    @Internal
    var mappingProviders: List<MappingProvider>? = null

    @Input
    fun getMappings() = mappingProviders?.map { it.mappings }

    @Input
    fun getMappingsMixin() = mappingProviders?.map { it.mixinMappings }

    @OutputFile
    var outputFile: File? = null

    @Input
    var reverse: Boolean = false

    @Input
    var mixin: Boolean = false

    @Inject
    abstract fun getWorkerExecutor(): WorkerExecutor

    @TaskAction
    fun generate() {
        getWorkerExecutor().noIsolation().submit(GenerateMappingsAction::class.java) { params ->
            params.output.set(outputFile)
            params.mappings.set(if (mixin) getMappingsMixin() else getMappings())
            params.reverse.set(reverse)
        }
    }
}