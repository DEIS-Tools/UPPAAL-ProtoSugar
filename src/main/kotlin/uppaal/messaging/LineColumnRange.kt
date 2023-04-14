package uppaal.messaging

import java.lang.Exception

/** A "UPPAAL style" range with start/end line/column values for some text. **/
data class LineColumnRange(var beginLine: Int, var beginColumn: Int, var endLine: Int, var endColumn: Int) {
    override fun toString(): String = "($beginLine, $beginColumn, $endLine, $endColumn)"

    /** Get an index-range for some text based on this LineColumnRange. **/
    fun toIntRange(text: String): IntRange {
        var startIndex = -1
        var currentLine = 1
        var currentColumn = 0
        var currentIndex = -1

        val chars = text.asSequence().iterator()
        while (chars.hasNext()) {
            val char = chars.next()
            ++currentIndex
            ++currentColumn

            if (currentLine == beginLine && currentColumn == beginColumn)
                startIndex = currentIndex

            if ((currentLine == endLine && currentColumn == endColumn - 1) || currentLine > endLine)
                return IntRange(startIndex, currentIndex) // -1 to convert to inclusive end

            if (char == '\n') {
                ++currentLine
                currentColumn = 0
            }
        }

        // A last resort to not fail.
        if (startIndex != -1)
            return IntRange(startIndex, currentIndex)
        if (text == "")
            return IntRange.EMPTY

        return IntRange(text.indices.last, text.indices.last)
    }

    companion object {
        /** Get the UPPAAL range for some text based on an index range of the same text. **/
        @JvmStatic
        fun fromIntRange(text: String, range: IntRange): LineColumnRange {
            val trueStart = range.first // Inclusive
            val trueEnd = range.last // Inclusive

            var lineStart = -1
            var columnStart = -1

            var currentLine = 1
            var currentColumn = 0
            var currentIndex = -1

            val chars = text.asSequence().iterator()
            while (true) {
                val char = chars.next()
                ++currentIndex
                ++currentColumn

                if (currentIndex == trueStart) {
                    lineStart = currentLine
                    columnStart = currentColumn
                }

                if (currentIndex == trueEnd)
                    return LineColumnRange(lineStart, columnStart, currentLine, currentColumn + 1) // Convert ot exclusive column end

                if (char == '\n') {
                    ++currentLine
                    currentColumn = 0
                }
            }
        }
    }
}
