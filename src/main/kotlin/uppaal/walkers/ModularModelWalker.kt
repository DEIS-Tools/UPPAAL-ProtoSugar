package uppaal.walkers

import uppaal.UppaalPath
import uppaal.model.UppaalPojo
import kotlin.reflect.KClass

class ModularModelWalker : ModelWalkerBase() {
    private data class Handler(private val filter: List<KClass<out UppaalPojo>>, val function: Any) {
        /** Check whether a UppaalPath matches the filter, in which case the handler may fire on the visited element. **/
        fun canHandle(path: UppaalPath) =
            filter.isEmpty() ||
                (path.size > filter.size &&
                    path.takeLast(filter.size + 1).zip(filter).all { // The last element of "path" is dropped by "zip", which we want
                        (node, kClass) -> kClass.isInstance(node.element)
                    })
    }

    private val handlers = HashMap<KClass<out UppaalPojo>, MutableList<Handler>>()

    /** Register a handler which will be activated upon visiting a UppaalPojo of type KClass<T> and matches the filter.
     *  The filter defines the types/classes which the immediate parents of a visited element must have for the handler to fire. **/
    fun <T : UppaalPojo> register(
        handler: (UppaalPath, T) -> Unit,
        elementClass: KClass<T>,
        filter: List<KClass<out UppaalPojo>>)
    {
        // "elementClass" is not inferred from generics since "T::class" requires inlining this function and making the "handlers"-map public.
        handlers.getOrPut(elementClass) { mutableListOf() }
            .add(Handler(filter, handler))
    }

    @Suppress("UNCHECKED_CAST")
    override fun visitUppaalPojo(path: UppaalPath, uppaalPojo: UppaalPojo) {
        handlers[uppaalPojo.javaClass.kotlin]
            ?.filter { it.canHandle(path) }
            ?.forEach { (it.function as (UppaalPath, UppaalPojo) -> Unit)(path, uppaalPojo) }
    }
}