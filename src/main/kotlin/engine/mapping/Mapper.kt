package engine.mapping

import uppaal_pojo.*
import kotlin.reflect.KType
import kotlin.reflect.typeOf


interface Mapper {
    fun getPhases(): Sequence<Phase>
    // TODO: Error mapper (to map native errors back to original syntax)
}

abstract class Phase {
    val handlers = ArrayList<Triple<KType, List<Class<out UppaalPojo>>, Any>>()

    protected inline fun <reified T : UppaalPojo> register(noinline handler: (T) -> (List<Error>), prefix: List<Class<out UppaalPojo>> = ArrayList()) {
        handlers.add(Triple(typeOf<T>(), prefix.plus(T::class.java), handler))
    }

    inline fun <reified T : UppaalPojo> visit(path: List<PathNode>, element: T): List<Error> {
        for (handler in handlers.filter { it.first == typeOf<T>() })
            if (pathMatchesFilter(handler.second, path))
                @Suppress("UNCHECKED_CAST")
                return (handler.third as (T) -> (List<Error>))(element)
        return listOf()
    }

    fun pathMatchesFilter(pathFilter: List<Class<out UppaalPojo>>, path: List<PathNode>)
        = path.size >= pathFilter.size
            && path.takeLast(pathFilter.size).zip(pathFilter).all {
                (node, filter) -> filter.isInstance(node.element)
            }
}

// TODO: Add severity
class Error(pathList: List<PathNode>,
            val beginLine: Int, val beginColumn: Int,
            val endLine: Int, val endColumn: Int,
            val message: String, val context: String) {
    val path: String = pathList.joinToString("/")
    override fun toString(): String {
        return """{"errors":[{"path":"$path","begln":$beginLine,"begcol":$beginColumn,"endln":$endLine,"endcol":$endColumn,"msg":"$message","ctx":"$context"}"""
    }
}

@Suppress("MemberVisibilityCanBePrivate")
class PathNode(val element: UppaalPojo, val index: Int? = null) {
    override fun toString(): String {
        return when (element) {
            is Nta -> "nta"
            is Declaration -> "declaration"
            is System -> "system"
            is Parameter -> "parameter"
            is Template -> "template[${index ?: throw Exception("PathNode with Template has 'index == null'")}]"
            is Transition -> "transition[${index ?: throw Exception("PathNode with Transition has 'index == null'")}]"
            else -> throw Exception("PathNode cannot print unhandled UppaalPojo '${element::class.java.typeName}'")
        }
    }
}