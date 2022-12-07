package mapping.parsing

class ParseHelper {
    companion object {
        @JvmStatic
        fun getParameterIndex(parameterStringChars: Iterator<IndexedValue<Char>>, currParamStartIndex: Int, endChar: Char = Character.MIN_VALUE): Int? {
            val endChars = listOf(')', ']', '}')
            var localParamIndex = 0
            do {
                val next = parameterStringChars.next()
                if (next.value in endChars && next.value != endChar)
                    return null // Formatting error

                when (next.value) {
                    ',' -> localParamIndex++
                    '(' -> getParameterIndex(parameterStringChars, currParamStartIndex, ')') ?: return null
                    '[' -> getParameterIndex(parameterStringChars, currParamStartIndex, ']') ?: return null
                    '{' -> getParameterIndex(parameterStringChars, currParamStartIndex, '}') ?: return null
                    endChar -> return -1
                }
            } while (parameterStringChars.hasNext() && (next.index != currParamStartIndex || endChar != Character.MIN_VALUE))

            if (!parameterStringChars.hasNext())
                return null // Formatting error

            return localParamIndex
        }
    }
}