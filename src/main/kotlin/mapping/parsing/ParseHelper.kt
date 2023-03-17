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

class BufferedIterator<T>(private val iterator: Iterator<T>) {
    private var current: T? = null

    fun current(): T = current ?: throw Exception("Iteration has not yet started. Call 'next()' first")

    fun hasNext(): Boolean = iterator.hasNext()

    fun next(): T {
        current = iterator.next()
        return current!!
    }

    fun tryNext(): T? {
        return if (hasNext()) next()
            else null
    }
}