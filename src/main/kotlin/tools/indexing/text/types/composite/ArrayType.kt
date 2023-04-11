package tools.indexing.text.types.composite

import tools.indexing.text.types.Type

class ArrayType(val elementType: Type, dimensionSizes: List<Int>) : Type(), CompositeType