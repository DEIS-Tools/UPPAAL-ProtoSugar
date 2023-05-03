package tools.parsing

import java.util.*

class BufferedIterator<T>(private val iterator: Iterator<T>) {
    private var trueIndex = -1
    private var bufferIndex = 0
    private var snapshots = Stack<Pair<Int, Int>>() // Buffer and true index
    private val buffer = mutableListOf<T?>(null)

    val current get() = buffer[bufferIndex] ?: throw Exception("Iteration has not yet started. Call 'next()' first")
    val currentIndex get() = trueIndex

    fun hasNext(): Boolean = iterator.hasNext()

    fun next(): T {
        trueIndex++
        if (buffer.size > 1 || snapshots.isNotEmpty()) {
            bufferIndex++
            if (bufferIndex == buffer.size) {
                if (snapshots.isEmpty()) {
                    buffer.clear()
                    bufferIndex = 0
                }
                buffer += iterator.next()
            }
        }
        else
            buffer[0] = iterator.next()

        return buffer[bufferIndex]!!
    }

    fun tryNext(): T? {
        return if (hasNext()) next()
            else null
    }

    fun nextWhile(predicate: (T) -> Boolean): T? {
        while (hasNext()) {
            if (!predicate(next()))
                return current
        }
        return null
    }


    fun setSnapshot() {
        snapshots.push(Pair(bufferIndex, trueIndex))
    }

    fun restoreSnapshot(clear: Boolean = false) {
        val snapshot = snapshots.peek()
        bufferIndex = snapshot.first
        trueIndex = snapshot.second
        if (clear)
            clearSnapshot()
    }

    fun clearSnapshot() {
        if (snapshots.isEmpty())
            throw Exception("There are no active snapshots")
        snapshots.pop()
        if (snapshots.isEmpty() && bufferIndex == buffer.lastIndex) {
            val temp = buffer.last()
            buffer.clear()
            buffer += temp
            bufferIndex = 0
        }
    }
}