package me.dreamhopping.pml.gradle.util

import org.objectweb.asm.Type

fun mapDesc(descriptor: String, classes: Map<String, String>): String =
    mapType(Type.getType(descriptor), classes).descriptor

private fun mapType(type: Type, classes: Map<String, String>): Type {
    return when (type.sort) {
        Type.ARRAY -> {
            val remappedDescriptor = buildString {
                var i = 0
                do {
                    append('[')
                    i++
                } while (i < type.dimensions)
                append(mapType(type.elementType, classes).descriptor)
            }
            Type.getType(remappedDescriptor)
        }
        Type.OBJECT -> {
            val remappedInternalName = classes[type.internalName]
            if (remappedInternalName != null) Type.getObjectType(remappedInternalName) else type
        }
        Type.METHOD -> Type.getMethodType(mapMethodDesc(type.descriptor, classes))
        else -> type
    }
}

private fun mapMethodDesc(desc: String, classes: Map<String, String>): String {
    if (desc == "()V") return desc

    return buildString {
        append("(")
        Type.getArgumentTypes(desc).forEach {
            append(mapType(it, classes).descriptor)
        }

        when (val returnType = Type.getReturnType(desc)) {
            Type.VOID_TYPE -> append(")V")
            else -> {
                append(')')
                append(mapType(returnType, classes).descriptor)
            }
        }
    }
}