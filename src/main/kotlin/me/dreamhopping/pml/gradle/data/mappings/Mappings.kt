package me.dreamhopping.pml.gradle.data.mappings

import java.io.Serializable

data class Mappings(
    internal var classes: LinkedHashMap<String, String>,
    internal var methods: LinkedHashMap<String, String>,
    internal var fields: LinkedHashMap<String, String>,
    internal var locals: LinkedHashMap<String, String>
) : Serializable {
    fun className(obfuscated: String, name: String) {
        classes[obfuscated] = name
    }

    fun method(
        owner: String,
        obfuscated: String,
        desc: String,
        name: String
    ) {
        methods["$owner/$obfuscated$desc"] = name
    }

    fun field(owner: String, obfuscated: String, name: String) {
        fields["$owner/$obfuscated"] = name
    }

    /**
     * The reverse function takes the current data (obf-deobf) and flips it
     * to become (deobfuscated-obfuscated). This is used to generate the "reverse" mapping
     * json which will be processed by the reobfuscation task. This should
     * allow support for built jars to be used in production environments
     * of Minecraft.
     *
     * @author ChachyDev
     * @since 3.5.0
     */
    fun reverse() {
        val reverseMethods = LinkedHashMap<String, String>()
        val reversedClasses = LinkedHashMap<String, String>()
        val reversedFields = LinkedHashMap<String, String>()

        methods.forEach {
            val nameDesc = it.key.let { key ->
                val lastIndex = key.lastIndexOf('/', key.indexOf('('))
                if (lastIndex != -1) {
                    key.substring(lastIndex + 1)
                } else {
                    key
                }
            }

            val className = it.key.removeSuffix("/$nameDesc")
            val desc = nameDesc.substring(nameDesc.indexOf('('))
            val name = nameDesc.removeSuffix(desc)
            val reverseClassName = classes[className] ?: className
            reverseMethods["$reverseClassName/${it.value}${mapDesc(desc)}"] = name
        }
        classes.forEach {
            reversedClasses[it.value] = it.key
        }
        fields.forEach {
            val owner = it.key.substring(0, it.key.lastIndexOf('/'))
            val name = it.key.substring(it.key.lastIndexOf('/') + 1)
            reversedFields["${classes[owner] ?: owner}/${it.value}"] = name
        }

        methods = reverseMethods
        classes = reversedClasses
        fields = reversedFields
    }

    private fun mapDesc(desc: String): String {
        val suffix = desc.substring(desc.lastIndexOf(')'))
        val prefix = desc.substring(desc.indexOf('('), 1)

        val parts = desc.removePrefix(prefix)
            .removeSuffix(suffix)
            .split(";")
            .map {
                val index = it.indexOf('L')
                if (index == -1) {
                    it
                } else {
                    it.substring(index + 1).let { clazz ->
                        it.replace(clazz, classes[clazz] ?: clazz)
                    }
                }
            }

        return "$prefix${parts.joinToString(";")}$suffix"
    }

    companion object {
        inline fun mappings(callback: Mappings.() -> Unit) =
            Mappings(linkedMapOf(), linkedMapOf(), linkedMapOf(), linkedMapOf()).apply(callback)
    }
}