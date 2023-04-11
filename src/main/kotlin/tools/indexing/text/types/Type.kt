package tools.indexing.text.types

import tools.indexing.text.types.modifiers.ModifierType


abstract class Type
{
    inline fun <reified T : ModifierType> hasMod(): Boolean = hasMod(T::class.java)
    fun <T : ModifierType> hasMod(modType: Class<T>): Boolean =
        when {
            modType.isInstance(this) -> true
            this is ModifierType -> underlyingType.hasMod(modType)
            else -> false
        }
}