package mapping.impl

import mapping.base.Mapper
import mapping.base.PhaseOutput
import mapping.base.QueryPhase
import tools.parsing.Confre
import tools.parsing.ConfreHelper
import tools.parsing.Leaf
import tools.parsing.ParseTree
import tools.restructuring.TextRewriter

class TxQuanMapper : Mapper() {
    override fun getPhases() = PhaseOutput(listOf(), null, TxQuanQueryPhase())

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
            
            ${ConfreHelper.expressionGrammar}
        """.trimIndent())


        override fun mapQuery(queryRewriter: TextRewriter): String
            = queryConfre.matchExact(queryRewriter.originalText)?.let { smartMap(it, queryRewriter) }
                ?: naiveMap(queryRewriter)


        private fun smartMap(queryTree: ParseTree, queryRewriter: TextRewriter): String {
            val textualQuantifierLeaves = queryTree
                .postOrderWalk()
                .filterIsInstance<Leaf>()
                .filter { isTextualQuantifier(it) }

            for (txQuan in textualQuantifierLeaves) {
                val originalVal = txQuan.token!!.value
                val replacement = textualQuantifierStrings[originalVal]!!

                queryRewriter.replace(txQuan.range, replacement)
                    .addBackMap()
                    .overrideErrorRange { txQuan.range }
                    .overrideErrorContext { originalVal }

                // AVOIDABLE requires negating the entire query
                if (txQuan.token.value == eBoxNegated) {
                    queryRewriter.insert(txQuan.endPosition() + 2, "not (")
                    queryRewriter.append(")")
                }
            }

            return queryRewriter.getRewrittenText()
        }

        private fun isTextualQuantifier(leaf: Leaf): Boolean {
            val value = leaf.token?.value ?: return false
            return textualQuantifierStrings.contains(value)
        }


        private fun naiveMap(queryRewriter: TextRewriter): String {
            val allMatches = textualQuantifierStrings.flatMap {
                Regex("(^|[^_A-Za-z0-9])" + "(${it.key})" + "([^_A-Za-z0-9]|\$)")
                    .findAll(queryRewriter.originalText)
                    .map { match -> Pair(it.value, match.groups[2]!!) }
            }.sortedBy { it.second.range.first }

            for (match in allMatches) {
                val replacement = match.first
                val toReplace = match.second
                val originalVal = toReplace.value

                queryRewriter.replace(toReplace.range, replacement)
                    .addBackMap()
                    .overrideErrorRange { toReplace.range }
                    .overrideErrorContext { originalVal }
            }

            return queryRewriter.getRewrittenText()
        }
    }
}