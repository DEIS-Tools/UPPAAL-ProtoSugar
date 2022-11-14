package engine.mapping.txquan

import engine.mapping.*
import engine.parsing.Confre
import engine.parsing.Leaf

class TxQuanMapper : Mapper {
    override fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?>
        = Pair(sequenceOf(), TxQuanQueryPhase())

    private class TxQuanQueryPhase : QueryPhase()
    {
        private val aBox = "ALWAYS"
        private val eDiamond = "POSSIBLY"
        private val eBox = "E[]" // Alternatives: "POTENTIALLY ALWAYS"
        private val aDiamond = "EVENTUALLY"
        private val arrow = "LEADSTO"

        private val textualQuantifierStrings = hashMapOf(
            Pair(aBox,     "A[]"),
            Pair(eDiamond, "E<>"),
            Pair(eBox,     "E[]"),
            Pair(aDiamond, "A<>"),
            Pair(arrow,    "-->")
        )

        private val queryGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = -?[0-9]+
            BOOL = true|false
            
            Query :== [Quantifier] Expression [('-->' | '$arrow') Expression] [Subjection] .
            Quantifier :== ('A[]' | '$aBox')
                         | ('E<>' | '$eDiamond')
                         | ('E[]' | '$eBox')
                         | ('A<>' | '$aDiamond') .
            Subjection :== 'under' IDENT .
            Expression :== [Unary] (Term  ['[' Expression ']'] | '(' Expression ')') [Binary Expression] .
            Term       :== IDENT ['(' INT {',' INT} ')']['.' IDENT] | INT | BOOL .
            Unary      :== '+' | '-' | '!' | 'not' .
            Binary     :== '<' | '<=' | '==' | '!=' | '>=' | '>'
                         | '+' | '-' | '*' | '/' | '%' | '&'
                         | '|' | '^' | '<<' | '>>' | '&&' | '||'
                         | '<?' | '>?' | 'or' | 'and' | 'imply' .
        """.trimIndent())

        // newRange (inclusive), originalValue, originalRange (inclusive)
        private val backMapToOriginalValue = ArrayList<Triple<IntRange, String, IntRange>>()
        private var latestQueryInput = ""
        private var latestQueryOutput = ""


        override fun mapQuery(query: String): Pair<String, UppaalError?> {
            latestQueryInput = query
            backMapToOriginalValue.clear()
            val queryTree = queryGrammar.matchExact(query)
            if (null == queryTree) {
                latestQueryOutput = naiveMap(query)
                return Pair(latestQueryOutput, null)
            }

            val textualQuantifiers = queryTree
                .postOrderWalk()
                .filterIsInstance<Leaf>()
                .filter { isTextualQuantifier(it) }

            var newQuery = query
            var offset = 0
            for (txQuan in textualQuantifiers)
            {
                val replacement = textualQuantifierStrings[txQuan.token!!.value]!!
                newQuery = newQuery.replaceRange(txQuan.startPosition() + offset, txQuan.endPosition() + 1 + offset, replacement)
                registerBackMap(IntRange(txQuan.startPosition(), txQuan.endPosition()), txQuan.token.value, replacement, offset)
                offset += replacement.length - txQuan.length()
            }

            latestQueryOutput = newQuery
            return Pair(latestQueryOutput, null)
        }

        private fun naiveMap(query: String): String
        {
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

        private fun registerBackMap(oldRange: IntRange, oldValue: String, newValue: String, offset: Int)
        {
            val newStart = oldRange.first + offset
            val newEnd = newStart + newValue.length - 1 // -1 for inclusive
            val newRange = IntRange(newStart, newEnd)

            backMapToOriginalValue.add(
                Triple(newRange, oldValue, oldRange)
            )
        }


        override fun mapQueryError(error: UppaalError): UppaalError {
            val errorRange = getRangeFromLinesAndColumns(
                latestQueryOutput,
                error.beginLine, error.beginColumn,
                error.endLine, error.endColumn
            )

            // If an error relates to a mapped element
            val backMap = backMapToOriginalValue.find { it.first.first == errorRange.first && it.first.last == errorRange.last }
            if (null != backMap) {
                val backMappedErrorLocation = getLinesAndColumnsFromRange(latestQueryInput, backMap.third, 0)
                error.beginLine = backMappedErrorLocation.first
                error.beginColumn = backMappedErrorLocation.second
                error.endLine = backMappedErrorLocation.third
                error.endColumn = backMappedErrorLocation.fourth

                error.context = backMap.second
            }

            return error
        }
    }
}