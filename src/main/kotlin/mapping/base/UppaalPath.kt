package mapping.base

import uppaal_pojo.*
import uppaal_pojo.System

class UppaalPath private constructor(path: List<PathNode>): ArrayList<PathNode>(path) {
    constructor() : this(listOf())
    constructor(baseElement: Nta) : this(listOf(PathNode(baseElement)))

    // TODO: Replace these with one function that automatically verifies the path and automatically finds the index
    fun plus(element: UppaalPojo, index: Int? = null) = UppaalPath(plus(PathNode(element, index)))
    fun plus(element: IndexedValue<UppaalPojo>) = UppaalPath(plus(PathNode(element.value, element.index + 1))) // +1 since UPPAAL paths are 1-indexed

    override fun toString() =
        if (isEmpty()) ""
        else '/' + joinToString("/")
}

data class PathNode(val element: UppaalPojo, @Suppress("MemberVisibilityCanBePrivate") val index: Int? = null) {
    override fun toString(): String {
        return when (element) {
            is Nta -> "nta"
            is Name ->  "name"
            is Parameter -> "parameter"
            is Declaration -> "declaration"
            is System -> "system"
            is Template -> "template[${index ?: throw Exception("PathNode with Template has 'index == null'")}]"
            is Transition -> "transition[${index ?: throw Exception("PathNode with Transition has 'index == null'")}]"
            is Location ->  "location[${index ?: throw Exception("PathNode with Location has 'index == null'")}]"
            is Label ->  "label[${index ?: throw Exception("PathNode with Label has 'index == null'")}]"
            else -> throw Exception("PathNode cannot print unhandled UppaalPojo '${element::class.java.typeName}'")
        }
    }
}