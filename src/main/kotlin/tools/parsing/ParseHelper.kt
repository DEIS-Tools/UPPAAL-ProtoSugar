package tools.parsing

import java.util.*

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
    private var currentIndex = 0
    private var snapshots = Stack<Int>()
    private val buffer = mutableListOf<T?>(null)

    val current get() = buffer[currentIndex] ?: throw Exception("Iteration has not yet started. Call 'next()' first")

    fun hasNext(): Boolean = iterator.hasNext()

    fun next(): T {
        if (snapshots.isNotEmpty()) {
            currentIndex++
            if (currentIndex == buffer.size)
                buffer += iterator.next()
        }
        else
            buffer[0] = iterator.next()

        return buffer[currentIndex]!!
    }

    fun tryNext(): T? {
        return if (hasNext()) next()
            else null
    }


    fun setSnapshot() {
        if (buffer[currentIndex] == null)
            throw Exception("Iteration has not yet started. Call 'next()' first")
        snapshots.push(currentIndex)
    }

    fun restoreSnapshot() {
        currentIndex = 0
    }

    fun clearSnapshot() {
        if (snapshots.isEmpty())
            throw Exception("There are no active snapshots")

        currentIndex = snapshots.pop()
        if (snapshots.isEmpty()) {
            val temp = buffer.last()
            buffer.clear()
            buffer += temp
        }
    }
}