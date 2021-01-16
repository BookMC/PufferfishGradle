package me.dreamhopping.pml.gradle.tasks.run

import org.gradle.api.Task
import org.gradle.api.file.FileCollection

interface IRunTask : Task {
    var classpath: FileCollection?
    var mainClass: String?
    var args: List<String>?
    var vmArgs: List<String>?
    var environment: Map<String, String>?
    var runDir: String?
}