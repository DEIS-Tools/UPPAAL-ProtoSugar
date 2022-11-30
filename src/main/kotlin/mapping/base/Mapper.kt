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

    protected inline fun <reified T : UppaalPojo> register(noinline handler: (UppaalPath, T) -> List<UppaalError>, prefix: List<Class<out UppaalPojo>> = ArrayList()) {
        handlers.add(Triple(typeOf<T>(), prefix.plus(T::class.java), handler))
    }

    inline fun <reified T : UppaalPojo> visit(path: UppaalPath, element: T): List<UppaalError> {
        for (handler in handlers.filter { it.first == typeOf<T>() })
            if (pathMatchesFilter(handler.second, path))
                @Suppress("UNCHECKED_CAST")
                return (handler.third as (UppaalPath, T) -> List<UppaalError>)(path, element)
        return listOf()
    }

    fun pathMatchesFilter(pathFilter: List<Class<out UppaalPojo>>, path: UppaalPath)
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
