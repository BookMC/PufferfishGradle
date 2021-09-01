package me.dreamhopping.pml.gradle.util

fun <K, V> Map<K, V>.invert() : Map<V, K> {
    return HashMap<V, K>().apply {
        this@invert.entries.forEach {
            put(it.value, it.key)
        }
    }
}