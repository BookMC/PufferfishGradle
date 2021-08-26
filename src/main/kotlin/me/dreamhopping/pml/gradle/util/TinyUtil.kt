package me.dreamhopping.pml.gradle.util

import net.fabricmc.mapping.tree.TinyMappingFactory
import net.fabricmc.mapping.tree.TinyTree
import java.io.*

fun parseTree(reader: BufferedReader): TinyTree {
    return TinyMappingFactory.loadWithDetection(reader)
}

fun fromTo(stream: InputStream): Pair<String, String> {
    val header = stream.bufferedReader()
        .readLine()

    val split = header.split("\t")

    return split[1] to split[split.size - 1]
}