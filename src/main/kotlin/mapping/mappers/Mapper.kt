package mapping.mappers

import uppaal.error.UppaalError
import uppaal.error.UppaalPath
import uppaal.model.UppaalPojo
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class ProcessInfo(var name: String, val template: String)
data class PhaseOutput(val modelPhases: Sequence<ModelPhase>, val simulatorPhase: SimulatorPhase?, val queryPhase: QueryPhase?)


interface Mapper {
    fun getPhases(): PhaseOutput
}

abstract class ModelPhase {
    /** Do not touch! Here be dragons! **/
    var phaseIndex = -1
    /** Do not touch! Here be dragons! **/
    val handlers = ArrayList<Triple<KType, List<Class<out UppaalPojo>>, Any>>()

    /** This function registers a "handler" for a certain UPPAAL element type (e.g., a "Declaration". See also the
     * "uppaal_pojo" package). In case an element can show up in multiple contexts (e.g., "Nta -> Declaration" and
     * "Nta -> Template -> Declaration"), you can supply a "path prefix" to limit when the handler applies. **/
    protected inline fun <reified T : UppaalPojo> register(noinline handler: (UppaalPath, T) -> List<UppaalError>, prefix: List<Class<out UppaalPojo>> = ArrayList()) {
        handlers.add(Triple(typeOf<T>(), prefix.plus(T::class.java), handler))
    }

    /** This function delegates a UPPAAL element to all compatible handlers with a matching "path prefix". **/
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

    /** Errors from the UPPAAL engine or mapper phases must be "mapped back" to the original text on which they belong.
     * Since this framework makes many rewrites to the input code, equally many "back-maps" are required to compensate.
     * It is recommended to use the "Rewriter" to perform all text-mutation and back-mapping. **/
    abstract fun mapModelErrors(errors: List<UppaalError>): List<UppaalError>
}

abstract class SimulatorPhase {
    /** This function allows you to change the names of the processes shown in the UPPAAL simulator. **/
    abstract fun mapProcesses(processes: List<ProcessInfo>)
}

abstract class QueryPhase {
    /** Mutate a query. **/
    abstract fun mapQuery(query: String): Pair<String, UppaalError?>

    /** Figure out where in the original query a message belongs. **/
    abstract fun mapQueryError(error: UppaalError): UppaalError
}
