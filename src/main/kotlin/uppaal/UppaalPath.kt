package uppaal

import uppaal.model.*
import uppaal.model.System

class UppaalPath private constructor(path: List<PathNode>): ArrayList<PathNode>(path) {
    constructor() : this(listOf())
    constructor(baseElement: Nta) : this(listOf(PathNode(baseElement)))

    fun extend(element: UppaalPojo, index: Int? = null) = UppaalPath(plus(PathNode(element, index)))
    operator fun plus(element: IndexedValue<UppaalPojo>) = UppaalPath(plus(PathNode(element.value, element.index + 1))) // +1 since UPPAAL paths are 1-indexed
    operator fun plus(element: UppaalPojo) = UppaalPath(plus(PathNode(element)))

    override fun toString() =
        if (isEmpty()) ""
        else '/' + joinToString("/")
}

data class PathNode(val element: UppaalPojo, @Suppress("MemberVisibilityCanBePrivate") val index: Int? = null) {
    override fun toString(): String {
        return when (element) {
            is Nta -> "nta"
            is Arguments -> "arguments"
            is SubTemplateName -> "subtemplatename"
            is Name -> "name"
            is Parameter -> "parameter"
            is Declaration -> "declaration"
            is System -> "system"
            is Template -> "template[${index ?: throw Exception("PathNode with Template has 'index == null'")}]"
            is Transition -> "transition[${index ?: throw Exception("PathNode with Transition has 'index == null'")}]"
            is Location -> "location[${index ?: throw Exception("PathNode with Location has 'index == null'")}]"
            is Branchpoint -> "branchpoint[${index ?: throw Exception("PathNode with BranchPoint has 'index == null'")}]"
            is BoundaryPoint -> "boundarypoint[${index ?: throw Exception("PathNode with BoundaryPoint has 'index == null'")}]"
            is SubTemplateReference -> "subtemplatereference[${index ?: throw Exception("PathNode with SubTemplateReference has 'index == null'")}]"
            is Label ->  "label[${index ?: throw Exception("PathNode with Label has 'index == null'")}]"
            else -> throw Exception("PathNode cannot print unhandled UppaalPojo '${element::class.java.typeName}'")
        }
    }
}