package me.dreamhopping.pml.gradle.util

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer

val Project.sourceSets: SourceSetContainer get() = extensions.getByType(SourceSetContainer::class.java)
