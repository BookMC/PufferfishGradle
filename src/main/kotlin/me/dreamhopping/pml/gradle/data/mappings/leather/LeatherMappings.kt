package me.dreamhopping.pml.gradle.data.mappings.leather

data class LeatherMappings(val latest: Version?) {
    data class Version(val version: String)
}