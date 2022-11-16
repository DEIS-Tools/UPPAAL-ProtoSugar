package engine.mapping

import engine.mapping.pacha.PaChaMap
import engine.parsing.Confre
import engine.parsing.Node
import engine.parsing.ParseTree
import uppaal_pojo.*
import kotlin.reflect.KType
import kotlin.reflect.typeOf

interface Mapper {
    fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?>
}

abstract class ModelPhase {
    val handlers = ArrayList<Triple<KType, List<Class<out UppaalPojo>>, Any>>()

    protected inline fun <reified T : UppaalPojo> register(noinline handler: (List<PathNode>, T) -> List<UppaalError>, prefix: List<Class<out UppaalPojo>> = ArrayList()) {
        handlers.add(Triple(typeOf<T>(), prefix.plus(T::class.java), handler))
    }

    inline fun <reified T : UppaalPojo> visit(path: List<PathNode>, element: T): List<UppaalError> {
        for (handler in handlers.filter { it.first == typeOf<T>() })
            if (pathMatchesFilter(handler.second, path))
                @Suppress("UNCHECKED_CAST")
                return (handler.third as (List<PathNode>, T) -> List<UppaalError>)(path, element)
        return listOf()
    }

    fun pathMatchesFilter(pathFilter: List<Class<out UppaalPojo>>, path: List<PathNode>)
        = path.size >= pathFilter.size
            && path.takeLast(pathFilter.size).zip(pathFilter).all {
                (node, filter) -> filter.isInstance(node.element)
            }

    abstract fun mapModelErrors(errors: List<UppaalError>): List<UppaalError>
}

abstract class QueryPhase {
    abstract fun mapQuery(query: String): Pair<String, UppaalError?>
    abstract fun mapQueryError(error: UppaalError): UppaalError
}

@Suppress("MemberVisibilityCanBePrivate")
class PathNode(val element: UppaalPojo, val index: Int? = null) {
    override fun toString(): String {
        return when (element) {
            is Nta -> "nta"
            is Declaration -> "declaration"
            is System -> "system"
            is Parameter -> "parameter"
            is Template -> "template[${index ?: throw Exception("PathNode with Template has 'index == null'")}]"
            is Transition -> "transition[${index ?: throw Exception("PathNode with Transition has 'index == null'")}]"
            is Label ->  "label[${index ?: throw Exception("PathNode with Label has 'index == null'")}]"
            else -> throw Exception("PathNode cannot print unhandled UppaalPojo '${element::class.java.typeName}'")
        }
    }
}

class UppaalError {
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
        return """{"path":"$path","begln":$beginLine,"begcol":$beginColumn,"endln":$endLine,"endcol":$endColumn,"msg":"$message","ctx":"$context"}"""
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
            return UppaalError(
                errorTree.children[3]!!.toString().replace(startAndEndQuotePattern, ""),
                errorTree.children[7]!!.toString().toInt(),
                errorTree.children[11]!!.toString().toInt(),
                errorTree.children[15]!!.toString().toInt(),
                errorTree.children[19]!!.toString().toInt(),
                errorTree.children[23]!!.toString().replace(startAndEndQuotePattern, ""),
                errorTree.children[27]!!.toString().replace(startAndEndQuotePattern, ""),
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

fun createUppaalError(path: List<PathNode>, code: String, node: ParseTree, message: String, isUnrecoverable: Boolean = false): UppaalError
    = createUppaalError(path, code, node.range(), message, isUnrecoverable)

fun createUppaalError(path: List<PathNode>, code: String, range: IntRange, message: String, isUnrecoverable: Boolean = false): UppaalError {
    val linesAndColumns = getLinesAndColumnsFromRange(code, range)
    return UppaalError(path,
        linesAndColumns.first, linesAndColumns.second,
        linesAndColumns.third, linesAndColumns.fourth,
        message, "",
        isUnrecoverable = isUnrecoverable
    )
}

data class Quadruple<A,B,C,D>(var first: A, var second: B, var third: C, var fourth: D) {
    override fun toString(): String = "($first, $second, $third, $fourth)"
}