package mapping.mapping

import uppaal.error.UppaalError

abstract class QueryPhase {
    /** Mutate a query. **/
    abstract fun mapQuery(query: String): Pair<String, UppaalError?>

    /** Figure out where in the original query a message belongs. **/
    abstract fun backMapQueryError(error: UppaalError): UppaalError
}