package mapping.base

import uppaal_pojo.*
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class ProcessInfo(var name: String, val template: String)


interface Mapper {
    fun getPhases(): Triple<Sequence<ModelPhase>, SimulatorPhase?, QueryPhase?>
}

abstract class ModelPhase {
    val handlers = ArrayList<Triple<KType, List<Class<out UppaalPojo>>, Any>>()

    protected inline fun <reified T : UppaalPojo> register(noinline handler: (List<PathNode>, T) -> List<UppaalError>, prefix: List<Class<out UppaalPojo>> = ArrayList()) {
        handlers.add(Triple(typeOf<T>(), prefix.plus(T::class.java), handler))
    }

    inline fun <reified T : UppaalPojo> visit(path: List<PathNode>, element: T): List<UppaalError> {
        for (handler in handlers.filter { it.first == typeOf<T>() })
            if (pathMatchesFilter(handler.second, path))
                @Suppress("UNCHECKED_CAST")
                return (handler.third as (List<PathNode>, T) -> List<UppaalError>)(path, element)
        return listOf()
    }

    fun pathMatchesFilter(pathFilter: List<Class<out UppaalPojo>>, path: List<PathNode>)
        = path.size >= pathFilter.size
            && path.takeLast(pathFilter.size).zip(pathFilter).all {
                (node, filter) -> filter.isInstance(node.element)
            }

    abstract fun mapModelErrors(errors: List<UppaalError>): List<UppaalError>
}

abstract class SimulatorPhase {
    abstract fun mapProcesses(processes: List<ProcessInfo>)
}

abstract class QueryPhase {
    abstract fun mapQuery(query: String): Pair<String, UppaalError?>
    abstract fun mapQueryError(error: UppaalError): UppaalError
}


class PathNode(val element: UppaalPojo, @Suppress("MemberVisibilityCanBePrivate") val index: Int? = null) {
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

