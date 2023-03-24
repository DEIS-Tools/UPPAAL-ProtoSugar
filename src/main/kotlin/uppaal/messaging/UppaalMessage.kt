package uppaal.messaging

import tools.parsing.Confre
import tools.parsing.Node
import tools.parsing.ParseTree
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import unJsonFy
import uppaal.UppaalPath

/** H **/
enum class Severity {
    /** This error is in fact just a warning. **/
    WARNING,

    /** This error does not hinder the mapper in writing valid UPPAAL code.
     * The mapped model is sent to the engine, but even if UPPAAL detects no errors, this error will still be output. **/
    NON_BREAKING,

    /** This error makes the mapper unable to produce valid UPPAAL code. This and all previously errors are output
     * immediately after the mapper returns. The model is not sent to the engine. The errors return immediately. **/
    UNRECOVERABLE
}

class UppaalMessage {
    var path: String
    var range: LineColumnRange
    var message: String
    var context: String
    val severity: Severity

    val isWarning: Boolean
        get() = severity == Severity.WARNING
    val isUnrecoverable: Boolean
        get() = severity == Severity.UNRECOVERABLE

    var phaseIndex: Int = Int.MAX_VALUE

    constructor(path: UppaalPath, range: LineColumnRange, message: String, context: String, isUnrecoverable: Boolean)
        : this(path.toString(), range, message, context, isUnrecoverable)

    constructor(path: String, range: LineColumnRange, message: String, context: String, isUnrecoverable: Boolean)
        : this(path, range, message, context, if (isUnrecoverable) Severity.UNRECOVERABLE else Severity.NON_BREAKING)

    constructor(path: String, range: LineColumnRange, message: String, context: String, severity: Severity) {
        this.path = path
        this.range = range
        this.message = message
        this.context = context
        this.severity = severity
    }

    override fun toString(): String {
        return Json.encodeToString(toJson())
    }
    fun toJson() = buildJsonObject {
        put("path", path)
        put("begln", range.beginLine)
        put("begcol", range.beginColumn)
        put("endln", range.endLine)
        put("endcol", range.endColumn)
        put("msg", message)
        put("ctx", context)
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
        fun fromJson(json: String, isUnrecoverable: Boolean = true): UppaalMessage {
            val errorTree = (errorGrammar.matchExact(json) as? Node) ?: throw Exception("Could not parse UppaalError from JSON: $json")
            return UppaalMessage(
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

        fun fromJson(json: JsonObject, severity: Severity = Severity.UNRECOVERABLE): UppaalMessage {
            return UppaalMessage(
                json["path"]!!.toString(),
                LineColumnRange(
                    json["begln"]!!.toString().toInt(),
                    json["begcol"]!!.toString().toInt(),
                    json["endln"]!!.toString().toInt(),
                    json["endcol"]!!.toString().toInt()
                ),
                json["path"]!!.toString(),
                json["path"]!!.toString(),
                severity
            )
        }
    }
}


fun createUppaalError(path: UppaalPath, message: String, isUnrecoverable: Boolean = false): UppaalMessage
    = createUppaalError(path, "", IntRange.EMPTY, message, "", isUnrecoverable)

fun createUppaalError(path: UppaalPath, code: String, message: String, isUnrecoverable: Boolean = false): UppaalMessage
    = createUppaalError(path, code, code.indices, message, "", isUnrecoverable)

fun createUppaalError(path: UppaalPath, code: String, node: ParseTree, message: String, isUnrecoverable: Boolean = false): UppaalMessage
    = createUppaalError(path, code, node.range, message, "", isUnrecoverable)

fun createUppaalError(path: UppaalPath, code: String, range: IntRange, message: String, isUnrecoverable: Boolean = false): UppaalMessage
    = createUppaalError(path, code, range, message, "", isUnrecoverable)

fun createUppaalError(path: UppaalPath, code: String, range: IntRange, message: String, context: String, isUnrecoverable: Boolean = false): UppaalMessage {
    val linesAndColumns =
        if (range != IntRange.EMPTY) LineColumnRange.fromIntRange(code, range)
        else LineColumnRange(1,1,1,1)
    return UppaalMessage(path, linesAndColumns, message, context, isUnrecoverable = isUnrecoverable)
}