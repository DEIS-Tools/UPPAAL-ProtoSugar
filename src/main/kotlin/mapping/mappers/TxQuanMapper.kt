package mapping.mappers

import mapping.base.*
import mapping.parsing.Confre
import mapping.parsing.Leaf
import mapping.parsing.ParseTree

class TxQuanMapper : Mapper {
    override fun getPhases(): Triple<Sequence<ModelPhase>, SimulatorPhase?, QueryPhase?>
        = Triple(sequenceOf(), null, TxQuanQueryPhase())

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

        private val queryGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = [0-9]+
            BOOL = true|false
            
            Query :== [Quantifier] Expression [('-->' | '$arrow') Expression] [Subjection] .
            Quantifier :== ('A[]' | '$aBox')
                         | ('E<>' | '$eDiamond')
                         | ('A<>' | '$aDiamond')
                         | 'E[]'
                         | '$eBoxNegated' .
            Subjection :== 'under' IDENT .
            
            Expression :== [Unary] ('(' Expression ')' | (Term [{Array} | '(' [Expression {',' Expression}] ')'])) ['.' IDENT] [(Binary|Assignment) Expression] .
            Term       :== IDENT | INT | BOOL .
            Unary      :== '+' | '-' | '!' | 'not' .
            Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>' | '+' | '-' | '*' | '/' | '%' | '&'
                         | '|' | '^' | '<<' | '>>' | '&&' | '||' | '<?' | '>?' | 'or' | 'and' | 'imply' .
            Assignment :== '=' | ':=' | '+=' | '-=' | '*=' | '/=' | '%=' | '|=' | '&=' | '^=' | '<<=' | '>>=' .
            Array  :== '[' [Expression] ']' .
        """.trimIndent())

        // newRange (inclusive), originalValue, originalRange (inclusive)
        private val backMapToOriginalValue = ArrayList<Triple<IntRange, String, IntRange>>()
        private var latestQueryInput = ""
        private var latestQueryOutput = ""


        override fun mapQuery(query: String): Pair<String, UppaalError?> {
            backMapToOriginalValue.clear()
            latestQueryOutput =
                queryGrammar.matchExact(query)?.let { smartMap(query, it) }
                ?: naiveMap(query)
            return Pair(latestQueryOutput, null)
        }

        private fun smartMap(query: String, queryTree: ParseTree): String {
            val textualQuantifierLeaves = queryTree
                .postOrderWalk()
                .filterIsInstance<Leaf>()
                .filter { isTextualQuantifier(it) }

            var newQuery = query
            var offset = 0
            for (txQuan in textualQuantifierLeaves) {
                val replacement = textualQuantifierStrings[txQuan.token!!.value]!!
                newQuery = newQuery.replaceRange(txQuan.startPosition() + offset, txQuan.endPosition() + 1 + offset, replacement)
                registerBackMap(IntRange(txQuan.startPosition(), txQuan.endPosition()), txQuan.token.value, replacement, offset)
                offset += replacement.length - txQuan.length()

                // AVOIDABLE requires negating the entire query
                if (txQuan.token.value == eBoxNegated) {
                    val insertPos = txQuan.endPosition() + 1 + offset + 1  // +1 twice to skip the space after E[] as well
                    newQuery = newQuery.replaceRange(insertPos, insertPos, "not (")
                    newQuery += ')'
                }
            }

            return newQuery
        }

        private fun naiveMap(query: String): String {
            var offset = 0
            var newQuery = query

            val allMatches = textualQuantifierStrings.flatMap {
                Regex("(^|[^_A-Za-z0-9])" + "(${it.key})" + "([^_A-Za-z0-9]|\$)").findAll(newQuery).map { match -> Pair(it.value, match.groups[2]!!) }
            }.sortedBy { it.second.range.first }

            for (match in allMatches)
            {
                val replacement = match.first
                val toReplace = match.second
                newQuery = newQuery.replaceRange(
                    toReplace.range.first + offset,
                    toReplace.range.last + 1 + offset,
                    replacement)
                registerBackMap(toReplace.range, toReplace.value, replacement, offset)
                offset += replacement.length - toReplace.value.length
            }

            return newQuery
        }

        private fun isTextualQuantifier(leaf: Leaf): Boolean {
            val value = leaf.token?.value ?: return false
            return textualQuantifierStrings.contains(value)
        }

        private fun registerBackMap(oldRange: IntRange, oldValue: String, newValue: String, offset: Int) {
            val newStart = oldRange.first + offset
            val newEnd = newStart + newValue.length - 1 // -1 for inclusive
            val newRange = IntRange(newStart, newEnd)

            backMapToOriginalValue.add(
                Triple(newRange, oldValue, oldRange)
            )
        }


        override fun mapQueryError(error: UppaalError): UppaalError {
            val errorRange = error.range.toIntRange(latestQueryOutput)

            // If an error relates to a mapped element
            val backMap = backMapToOriginalValue.find { it.first.first == errorRange.first && it.first.last == errorRange.last }
            if (null != backMap) {
                error.range = LineColumnRange.fromIntRange(latestQueryInput, backMap.third)
                error.context = backMap.second
            }

            return error
        }
    }
}