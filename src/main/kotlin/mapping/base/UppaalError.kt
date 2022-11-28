package mapping.base

import mapping.parsing.Confre
import mapping.parsing.Node
import mapping.parsing.ParseTree
import jsonFy
import unJsonFy


class UppaalError {
    @Suppress("MemberVisibilityCanBePrivate")
    val path: String
    var beginLine: Int
    var beginColumn: Int
    var endLine: Int
    var endColumn: Int
    var message: String
    var context: String
    val isUnrecoverable: Boolean
    val fromEngine: Boolean // If false -> is from mapper

    constructor(
        pathList: List<PathNode>,
        beginLine: Int,
        beginColumn: Int,
        endLine: Int,
        endColumn: Int,
        message: String,
        context: String,
        isUnrecoverable: Boolean
    ) {
        this.path = pathList.joinToString("/")
        this.beginLine = beginLine
        this.beginColumn = beginColumn
        this.endLine = endLine
        this.endColumn = endColumn
        this.message = message
        this.context = context
        this.isUnrecoverable = isUnrecoverable
        this.fromEngine = false
    }

    constructor(
        path: String,
        beginLine: Int,
        beginColumn: Int,
        endLine: Int,
        endColumn: Int,
        message: String,
        context: String,
        isUnrecoverable: Boolean
    ) {
        this.path = path
        this.beginLine = beginLine
        this.beginColumn = beginColumn
        this.endLine = endLine
        this.endColumn = endColumn
        this.message = message
        this.context = context
        this.isUnrecoverable = isUnrecoverable
        this.fromEngine = true
    }

    override fun toString(): String {
        return """{"path":"$path","begln":$beginLine,"begcol":$beginColumn,"endln":$endLine,"endcol":$endColumn,"msg":"${message.jsonFy()}","ctx":"${context.jsonFy()}"}"""
    }

    companion object {
        @JvmStatic
        private val errorGrammar = Confre("""
            INT = ([1-9][0-9]*)|0*
            STRING = "((\\.|[^\\"\n])*(")?)?
            
            Error :== '{' '"path"'   ':' STRING ','
                          '"begln"'  ':' INT    ','
                          '"begcol"' ':' INT    ','
                          '"endln"'  ':' INT    ','
                          '"endcol"' ':' INT    ','
                          '"msg"'    ':' STRING ','
                          '"ctx"'    ':' STRING
                      '}' .
        """.trimIndent())

        @JvmStatic
        private val startAndEndQuotePattern = Regex("""^.|.$""")

        @JvmStatic
        fun fromJson(json: String, isUnrecoverable: Boolean = true): UppaalError {
            val errorTree = (errorGrammar.matchExact(json) as? Node) ?: throw Exception("Could not parse UppaalError from JSON: $json")
            @Suppress("MemberVisibilityCanBePrivate")
            return UppaalError(
                errorTree.children[3]!!.toString().replace(startAndEndQuotePattern, "").unJsonFy(),
                errorTree.children[7]!!.toString().toInt(),
                errorTree.children[11]!!.toString().toInt(),
                errorTree.children[15]!!.toString().toInt(),
                errorTree.children[19]!!.toString().toInt(),
                errorTree.children[23]!!.toString().replace(startAndEndQuotePattern, "").unJsonFy(),
                errorTree.children[27]!!.toString().replace(startAndEndQuotePattern, "").unJsonFy(),
                isUnrecoverable
            )
        }
    }
}

/** This is defined alongside the "UppaalError" class to help generate the start/end line/column values needed for errors **/
fun getLinesAndColumnsFromRange(text: String, range: IntRange, rangeOffset: Int = 0): Quadruple<Int, Int, Int, Int>
{
    val trueStart = range.first + rangeOffset // Inclusive
    val trueEnd = range.last + rangeOffset // Inclusive

    var lineStart = -1
    var columnStart = -1

    var currentLine = 1
    var currentColumn = 0
    var currentIndex = -1

    val chars = text.asSequence().iterator()
    while (true)
    {
        val char = chars.next()
        ++currentIndex
        ++currentColumn

        if (currentIndex == trueStart) {
            lineStart = currentLine
            columnStart = currentColumn
        }

        if (currentIndex == trueEnd)
            return Quadruple(lineStart, columnStart, currentLine, currentColumn + 1) // Convert ot exclusive column end

        if (char == '\n') {
            ++currentLine
            currentColumn = 0
        }
    }
}


fun getRangeFromLinesAndColumns(text: String, lineStart: Int, columnStart: Int, lineEnd: Int, columnEnd: Int): IntRange
{
    var startIndex = -1
    var currentLine = 1
    var currentColumn = 0
    var currentIndex = -1

    val chars = text.asSequence().iterator()
    while (true)
    {
        val char = chars.next()
        ++currentIndex
        ++currentColumn

        if (currentLine == lineStart && currentColumn == columnStart)
            startIndex = currentIndex

        if (currentLine == lineEnd && currentColumn == columnEnd - 1)
            return IntRange(startIndex, currentIndex) // Convert to inclusive end

        if (char == '\n') {
            ++currentLine
            currentColumn = 0
        }
    }
}

fun createUppaalError(path: List<PathNode>, message: String, isUnrecoverable: Boolean = false): UppaalError
        = createUppaalError(path, "", IntRange.EMPTY, message, isUnrecoverable)

fun createUppaalError(path: List<PathNode>, code: String, node: ParseTree, message: String, isUnrecoverable: Boolean = false): UppaalError
        = createUppaalError(path, code, node.range(), message, isUnrecoverable)

fun createUppaalError(path: List<PathNode>, code: String, range: IntRange, message: String, isUnrecoverable: Boolean = false): UppaalError {
    val linesAndColumns =
        if (range != IntRange.EMPTY) getLinesAndColumnsFromRange(code, range)
        else Quadruple(0,0,0,0)
    return UppaalError(path,
        linesAndColumns.first, linesAndColumns.second,
        linesAndColumns.third, linesAndColumns.fourth,
        message, "",
        isUnrecoverable = isUnrecoverable
    )
}