package mapping.scoping.types

import mapping.scoping.Modifier
import mapping.scoping.Type

class IntType(modifier: Modifier? = null, val range: IntRange? = null) : Type(modifier)