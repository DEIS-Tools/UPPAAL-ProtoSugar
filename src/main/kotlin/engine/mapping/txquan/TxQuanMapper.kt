package engine.mapping.txquan

import engine.mapping.*
import engine.parsing.Confre
import engine.parsing.Leaf

class TxQuanMapper : Mapper {
    override fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?>
        = Pair(sequenceOf(), TxQuanQueryPhase())

    private class TxQuanQueryPhase : QueryPhase()
    {
        private val aBox = "INVARIABLY"
        private val eDiamond = "POSSIBLY"
        private val eBox = "SUBINVARIABLY"
        private val aDiamond = "EVENTUALLY"
        private val arrow = "LEADSTO"

        private val textualQuantifierStrings = hashMapOf(
            Pair(aBox,     "A[]"),
            Pair(eDiamond, "E<>"),
            Pair(eBox,     "E[]"), // Alternatives: "POTENTIALLY ALWAYS"
            Pair(aDiamond, "A<>"),
            Pair(arrow,    "-->")
        )

        private val queryGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = ([1-9][0-9]*)|0*
            BOOL = true|false
            
            Query :== [Quantifier] Expression [('-->' | '$arrow') Expression] [Subjection] .
            Quantifier :== ('A[]' | '$aBox')
                         | ('E<>' | '$eDiamond')
                         | ('E[]' | '$eBox')
                         | ('A<>' | '$aDiamond') .
            Subjection :== 'under' IDENT 
            Expression :== [Unary] (Term  ['[' Expression ']'] | '(' Expression ')') [Binary Expression].
            Term       :== IDENT ['(' INT {',' INT} ')']['.' IDENT] | INT | BOOL .
            Unary      :== '+' | '-' | '!' | 'not' .
            Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>'
                         | '+' | '-' | '*' | '/' | '%' | '&'
                         | '|' | '^' | '<<' | '>>' | '&&' | '||'
                         | '<?' | '>?' | 'or' | 'and' | 'imply' .
        """.trimIndent())


        override fun mapQuery(query: String): Pair<String, UppaalError?> {
            val queryTree = queryGrammar.matchExact(query) ?: return Pair(naiveMap(query), null)

            val textualQuantifiers = queryTree
                .postOrderWalk()
                .filterIsInstance<Leaf>()
                .filter { isTextualQuantifier(it) }

            var newQuery = query
            var offset = 0
            for (txQuan in textualQuantifiers)
            {
                val replacement = textualQuantifierStrings[txQuan.token!!.value]!!
                newQuery = newQuery.replaceRange(txQuan.startPosition() + offset, txQuan.endPosition()+1 + offset, replacement)
                offset += replacement.length - txQuan.length()
            }

            return Pair(newQuery, null)
        }

        private fun naiveMap(query: String): String
        {
            var newQuery = query
            for (kvp in textualQuantifierStrings)
                newQuery = newQuery.replace(kvp.key, kvp.value)
            return newQuery
        }

        private fun isTextualQuantifier(leaf: Leaf): Boolean {
            val value = leaf.token?.value ?: return false
            return textualQuantifierStrings.contains(value)
        }


        override fun mapQueryError(error: UppaalError): UppaalError {
            TODO("Not yet implemented")
        }
    }
}