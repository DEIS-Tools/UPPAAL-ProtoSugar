package mapping.base

import tools.restructuring.TextRewriter
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalMessageException

abstract class QueryPhase : PhaseBase()
{
    private var rewriter = TextRewriter("")

    /** Rewrite/map a query. **/
    @Throws(UppaalMessageException::class)
    open fun mapQuery(query: String): String {
        rewriter = TextRewriter(query)
        return mapQuery(rewriter)
    }
    @Throws(UppaalMessageException::class)
    abstract fun mapQuery(queryRewriter: TextRewriter): String

    /** Figure out where in the original query a message belongs. **/
    open fun backMapQueryError(error: UppaalMessage): UppaalMessage {
        return backMapQueryError(error, rewriter)
    }
    open fun backMapQueryError(error: UppaalMessage, queryRewriter: TextRewriter): UppaalMessage {
        rewriter.backMapError(error)
        return error
    }
}