package engine.mapping.txquan

import engine.mapping.Mapper
import engine.mapping.MapperError
import engine.mapping.PathNode
import engine.mapping.Phase
import engine.parsing.Confre
import engine.parsing.Leaf

class TxQuanMapper : Mapper {
    override fun getPhases(): Sequence<Phase> = sequenceOf(Phase1())

    private class Phase1 : Phase()
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

        init {
            register(::mapQuery)
        }

        private fun mapQuery(@Suppress("UNUSED_PARAMETER") path: List<PathNode>, query: Query): List<MapperError> {
            val queryTree = queryGrammar.matchExact(query.formula) ?: return listOf()
            val textualQuantifiers = queryTree
                .postOrderWalk()
                .filterIsInstance<Leaf>()
                .filter { isTextualQuantifier(it) }

            var newFormula = query.formula
            var offset = 0
            for (txQuan in textualQuantifiers)
            {
                val replacement = textualQuantifierStrings[txQuan.token!!.value]!!
                newFormula = newFormula.replaceRange(txQuan.startPosition() + offset, txQuan.endPosition()+1 + offset, replacement)
                offset += replacement.length - txQuan.length()
            }

            query.formula = newFormula
            return listOf()
        }

        private fun isTextualQuantifier(leaf: Leaf): Boolean {
            val value = leaf.token?.value ?: return false
            return textualQuantifierStrings.contains(value)
        }
    }
}