package mapping.impl

import mapping.mapping.Mapper
import mapping.mapping.PhaseOutput
import mapping.mapping.QueryPhase
import mapping.parsing.Confre
import mapping.parsing.ConfreHelper
import mapping.parsing.Leaf
import mapping.parsing.ParseTree
import mapping.restructuring.Rewriter
import uppaal.error.UppaalError

class TxQuanMapper : Mapper {
    override fun getPhases()
        = PhaseOutput(listOf(), null, TxQuanQueryPhase())

    private class TxQuanQueryPhase : QueryPhase()
    {
        private val aBox = "ALWAYS"
        private val eDiamond = "POSSIBLY"
        private val eBoxNegated = "AVOIDABLE"
        private val aDiamond = "EVENTUALLY"
        private val arrow = "LEADSTO"

        private val textualQuantifierStrings = hashMapOf(
            Pair(aBox,        "A[]"),
            Pair(eDiamond,    "E<>"),
            Pair(eBoxNegated, "E[]"),
            Pair(aDiamond,    "A<>"),
            Pair(arrow,       "-->")
        )

        private val queryConfre = Confre("""
            Query :== [QueryQuantifier] Expression [('-->' | '$arrow') Expression] [Subjection] .
            QueryQuantifier :== ('A[]' | '$aBox')
                         | ('E<>' | '$eDiamond')
                         | ('A<>' | '$aDiamond')
                         | 'E[]'
                         | '$eBoxNegated' .
            Subjection :== 'under' IDENT .
            
            ${ConfreHelper.queryExpressionGrammar}
        """.trimIndent())

        var rewriter = Rewriter("")


        override fun mapQuery(query: String): Pair<String, UppaalError?> {
            rewriter = Rewriter(query)
            val result = queryConfre.matchExact(query)?.let { smartMap(it) } ?: naiveMap(query)
            return Pair(result, null)
        }

        private fun smartMap(queryTree: ParseTree): String {
            val textualQuantifierLeaves = queryTree
                .postOrderWalk()
                .filterIsInstance<Leaf>()
                .filter { isTextualQuantifier(it) }

            for (txQuan in textualQuantifierLeaves) {
                val originalVal = txQuan.token!!.value
                val replacement = textualQuantifierStrings[originalVal]!!

                rewriter.replace(txQuan.range(), replacement)
                    .addBackMap()
                    .overrideErrorRange { txQuan.range() }
                    .overrideErrorContext { originalVal }

                // AVOIDABLE requires negating the entire query
                if (txQuan.token.value == eBoxNegated) {
                    rewriter.insert(txQuan.endPosition() + 2, "not (")
                    rewriter.append(")")
                }
            }

            return rewriter.getRewrittenText()
        }

        private fun naiveMap(query: String): String {
            val allMatches = textualQuantifierStrings.flatMap {
                Regex("(^|[^_A-Za-z0-9])" + "(${it.key})" + "([^_A-Za-z0-9]|\$)")
                    .findAll(query)
                    .map { match -> Pair(it.value, match.groups[2]!!) }
            }.sortedBy { it.second.range.first }

            for (match in allMatches) {
                val replacement = match.first
                val toReplace = match.second
                val originalVal = toReplace.value

                rewriter.replace(toReplace.range, replacement)
                    .addBackMap()
                    .overrideErrorRange { toReplace.range }
                    .overrideErrorContext { originalVal }
            }

            return rewriter.getRewrittenText()
        }

        private fun isTextualQuantifier(leaf: Leaf): Boolean {
            val value = leaf.token?.value ?: return false
            return textualQuantifierStrings.contains(value)
        }


        override fun backMapQueryError(error: UppaalError): UppaalError {
            rewriter.backMapError(error)
            return error
        }
    }
}