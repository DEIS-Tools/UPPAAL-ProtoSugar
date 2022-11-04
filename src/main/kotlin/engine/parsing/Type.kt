package engine.parsing

interface Type {
    val isReference: Boolean
}

class SimpleType(
    val typeName: String,
    val range: String,
    override val isReference: Boolean
) : Type

class ChannelType(
    val parameters: List<Type>,
    override val isReference: Boolean
) : Type

class ArrayType(
    val elementType: Type,
    override val isReference: Boolean,
    val dimensions: List<String>
) : Type

class StructType(
    val fields: List<Type>,
    override val isReference: Boolean,
) : Type