package mapping.base

import tools.indexing.tree.Model
import tools.parsing.SyntaxRegistry
import tools.restructuring.TextRewriter
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalMessageException
import kotlin.reflect.KClass

abstract class QueryPhase : PhaseBase()
{
    private var rewriter = TextRewriter("")


    final override fun configure(mapper: KClass<out Mapper>, registry: SyntaxRegistry, model: Model) {
        super.configure(mapper, registry, model)
        onConfigured()
    }


    /** Rewrite/map a query. **/
    @Throws(UppaalMessageException::class)
    open fun mapQuery(query: String): String {
        rewriter = TextRewriter(query)
        mapQuery(rewriter)
        return rewriter.getRewrittenText()
    }

    @Throws(UppaalMessageException::class)
    abstract fun mapQuery(queryRewriter: TextRewriter)


    override fun report(message: UppaalMessage) =
        throw UppaalMessageException(message)


    /** Figure out where in the original query a message belongs. **/
    open fun backMapQueryError(error: UppaalMessage): UppaalMessage
            = backMapQueryError(error, rewriter)

    open fun backMapQueryError(error: UppaalMessage, queryRewriter: TextRewriter): UppaalMessage
            = error.apply { rewriter.backMapError(this) }
}