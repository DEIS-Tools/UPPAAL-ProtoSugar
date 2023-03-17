package mapping.mapping

import mapping.restructuring.BackMapResult
import mapping.restructuring.TextRewriter
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalPath
import uppaal.model.UppaalPojo
import kotlin.reflect.KType
import kotlin.reflect.typeOf

abstract class ModelPhase : PhaseBase()
{
    /** Do not touch! Here be dragons! **/
    val handlers = ArrayList<Triple<KType, List<Class<out UppaalPojo>>, Any>>()

    /** This function registers a "handler" for a certain UPPAAL element type (e.g., a "Declaration". See also the
     * "uppaal_pojo" package). In case an element can show up in multiple contexts (e.g., "Nta -> Declaration" and
     * "Nta -> Template -> Declaration"), you can supply a "path prefix" to limit when the handler applies. **/
    protected inline fun <reified T : UppaalPojo> register(noinline handler: (UppaalPath, T) -> List<UppaalMessage>, prefix: List<Class<out UppaalPojo>> = ArrayList()) {
        handlers.add(Triple(typeOf<T>(), prefix.plus(T::class.java), handler))
    }

    /** This function delegates a UPPAAL element to all compatible handlers with a matching "path prefix". **/
    inline fun <reified T : UppaalPojo> visit(path: UppaalPath, element: T): List<UppaalMessage>
            = handlers
        .filter { handler ->
            handler.first == typeOf<T>() && pathMatchesFilter(handler.second, path)
        }.flatMap { handler ->
            @Suppress("UNCHECKED_CAST")
            (handler.third as (UppaalPath, T) -> List<UppaalMessage>)(path, element)
        }

    fun pathMatchesFilter(pathFilter: List<Class<out UppaalPojo>>, path: UppaalPath): Boolean {
        if (pathFilter.isEmpty())
            return true

        return path.size >= pathFilter.size &&
                path.takeLast(pathFilter.size).zip(pathFilter).all {
                        (node, filter) -> filter.isInstance(node.element)
                }
    }


    private val rewriters = HashMap<String, TextRewriter>()

    /** Errors from the UPPAAL engine or mapper phases must be "mapped back" to the original text on which they belong.
     * Since this framework makes many rewrites to the input code, equally many "back-maps" are required to compensate. **/
    open fun backMapModelErrors(errors: List<UppaalMessage>): List<UppaalMessage>
        = errors.filter { rewriters[it.path]?.backMapError(it) != BackMapResult.REQUEST_DISCARD }

    /** Use this function to retrieve/create rewriter objects for each UPPAAL model element (i.e., UppaalPojo object).
     * The standard "backMapModelErrors" implementation should then be enough to back-map all errors. **/
    // TODO: Difference between "text" and "tree" rewriters.
    protected fun getRewriter(path: UppaalPath, originalText: String? = null): TextRewriter
        = rewriters.getOrPut(path.toString()) { TextRewriter(originalText ?: throw Exception("Original text of rewriter cannot be null")) }
}