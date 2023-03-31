package mapping.base

import tools.indexing.tree.Model
import tools.parsing.SyntaxRegistry
import tools.restructuring.BackMapResult
import tools.restructuring.TextRewriter
import uppaal.messaging.UppaalMessage
import uppaal.UppaalPath
import uppaal.model.Nta
import uppaal.model.UppaalPojo
import uppaal.walkers.ModularModelWalker
import kotlin.reflect.KClass

abstract class ModelPhase : PhaseBase()
{
    private val walker = ModularModelWalker()
    private val messages = ArrayList<UppaalMessage>()
    private val rewriters = HashMap<String, TextRewriter>()


    final override fun configure(mapper: KClass<out Mapper>, registry: SyntaxRegistry, model: Model /* TODO: Scheduler */) {
        super.configure(mapper, registry, model)
        onConfigured()
    }


    /** This function registers a "handler" for a certain UPPAAL element type (e.g., a "Declaration". See also the
     * "uppaal_pojo" package). In case an element can show up in multiple contexts (e.g., "Nta -> Declaration" and
     * "Nta -> Template -> Declaration"), you can supply a "path filter" to limit when the handler applies. **/
    protected inline fun <reified T : UppaalPojo>
        register(noinline handler: (UppaalPath, T) -> Unit, filter: List<KClass<out UppaalPojo>> = ArrayList())
            = register(handler, T::class, filter)
    protected fun <T : UppaalPojo>
        register(handler: (UppaalPath, T) -> Unit, elementClass: KClass<T>, filter: List<KClass<out UppaalPojo>>)
            = walker.register(handler, elementClass, filter)

    fun run(nta: Nta): List<UppaalMessage> {
        messages.clear()
        walker.doWalk(nta)
        return messages
    }

    override fun report(message: UppaalMessage) {
        messages.add(message)
    }
    fun reportAll(messages: List<UppaalMessage>) {
        this.messages.addAll(messages)
    }


    /** Use this function to retrieve/create rewriter objects for each UPPAAL model element (i.e., UppaalPojo object).
     * The standard "backMapModelErrors" implementation should then be enough to back-map all errors. **/
    // TODO: Difference between "text" and "tree" rewriters.
    protected fun getRewriter(path: UppaalPath, originalText: String? = null): TextRewriter
        = rewriters.getOrPut(path.toString()) { TextRewriter(originalText ?: throw Exception("Original text of rewriter cannot be null")) }

    /** Errors from the UPPAAL engine or mapper phases must be "mapped back" to the original text on which they belong.
     * Since this framework makes many rewrites to the input code, equally many "back-maps" are required to compensate. **/
    open fun backMapModelErrors(errors: List<UppaalMessage>): List<UppaalMessage>
        = errors.filter { rewriters[it.path]?.backMapError(it) != BackMapResult.REQUEST_DISCARD }
}