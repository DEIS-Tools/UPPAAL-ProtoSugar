package mapping.base

import mapping.parsing.Confre
import mapping.parsing.Node
import mapping.parsing.ParseTree
import jsonFy
import unJsonFy


class UppaalError {
    @Suppress("MemberVisibilityCanBePrivate")
    val path: String
    var range: LineColumnRange
    var message: String
    var context: String
    val isUnrecoverable: Boolean
    val fromEngine: Boolean // If false -> is from mapper

    constructor(path: UppaalPath, range: LineColumnRange, message: String, context: String, isUnrecoverable: Boolean) {
        this.path = path.toString()
        this.range = range
        this.message = message
        this.context = context
        this.isUnrecoverable = isUnrecoverable

        this.fromEngine = false
    }

    constructor(path: String, range: LineColumnRange, message: String, context: String, isUnrecoverable: Boolean) {
        this.path = path
        this.range = range
        this.message = message
        this.context = context
        this.isUnrecoverable = isUnrecoverable

        this.fromEngine = true
    }

    override fun toString(): String {
        return """{"path":"$path","begln":${range.beginLine},"begcol":${range.beginColumn},"endln":${range.endLine},"endcol":${range.endColumn},"msg":"${message.jsonFy()}","ctx":"${context.jsonFy()}"}"""
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
        fun fromJson(json: String, isUnrecoverable: Boolean = true): UppaalError {
            val errorTree = (errorGrammar.matchExact(json) as? Node) ?: throw Exception("Could not parse UppaalError from JSON: $json")
            @Suppress("MemberVisibilityCanBePrivate")
            return UppaalError(
                errorTree.children[3]!!.toString().drop(1).dropLast(1).unJsonFy(),
                LineColumnRange(
                    errorTree.children[7]!!.toString().toInt(),
                    errorTree.children[11]!!.toString().toInt(),
                    errorTree.children[15]!!.toString().toInt(),
                    errorTree.children[19]!!.toString().toInt()
                ),
                errorTree.children[23]!!.toString().drop(1).dropLast(1).unJsonFy(),
                errorTree.children[27]!!.toString().drop(1).dropLast(1).unJsonFy(),
                isUnrecoverable
            )
        }
    }
}

fun createUppaalError(path: UppaalPath, message: String, isUnrecoverable: Boolean = false): UppaalError
    = createUppaalError(path, "", IntRange.EMPTY, message, isUnrecoverable)

fun createUppaalError(path: UppaalPath, code: String, node: ParseTree, message: String, isUnrecoverable: Boolean = false): UppaalError
    = createUppaalError(path, code, node.range(), message, isUnrecoverable)

fun createUppaalError(path: UppaalPath, code: String, range: IntRange, message: String, isUnrecoverable: Boolean = false): UppaalError {
    val linesAndColumns =
        if (range != IntRange.EMPTY) LineColumnRange.fromIntRange(code, range)
        else LineColumnRange(1,1,1,1)
    return UppaalError(path, linesAndColumns, message, "", isUnrecoverable = isUnrecoverable)
}