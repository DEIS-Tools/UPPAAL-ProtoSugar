import kotlinx.serialization.json.*
import uppaal.UppaalPath
import tools.restructuring.InsertOp
import tools.restructuring.TextRewriter
import java.io.BufferedWriter
import java.io.File

/** If 'this' does not start with 'prefix', return 'prefix + this'. Otherwise, return 'this' **/
fun String.ensureStartsWith(prefix: String) =
    if (this.startsWith(prefix)) this
    else prefix + this


/** Move the start and end indices of this range by 'offset'. **/
fun IntRange.offset(offset: Int) = (this.first + offset .. this.last + offset)

/** Get the number of indices contained in this IntRange **/
fun IntRange.length() = this.last - this.first + 1

/** Check if the first IntRange is contained completely inside the 'other' IntRange. **/
fun IntRange.within(other: IntRange) = this.first >= other.first && this.last <= other.last

/** Check if two IntRanges share any index. **/
fun IntRange.overlaps(other: IntRange) = this.first <= other.last && other.first <= this.last


/** Get the product of all number in a list of integers. **/
fun List<Int>.product() = this.fold(1) { acc, value -> acc * value }


/** Works similar to 'String.joinToString()'. It takes an insertion-'location', a list of string 'elements' to insert,
 * and an optional 'separator' string to insert between any two adjacent elements.
 * The output is a list of 'InsertOp' objects for each element in 'elements' (not for the 'separator' elements). **/
fun TextRewriter.joinInsert(location: Int, elements: List<String>, separator: String = ", "): Sequence<IndexedValue<InsertOp>> =
    sequence {
        val args = elements.withIndex().iterator()
        while (args.hasNext()) {
            val (index, insert) = args.next()
            yield(IndexedValue(index, this@joinInsert.insert(location, insert)))
            if (args.hasNext())
                this@joinInsert.insert(location, separator)
        }
    }


/** Create and add a new rewriter to a map of rewriters with "path-keys". Throws if path already has a rewriter. **/
fun MutableMap<String, TextRewriter>.createOrGetRewriter(path: UppaalPath, originalText: String): TextRewriter
        = this.getOrPut(path.toString()) { TextRewriter(originalText) }


/** Print exception to a file. **/
fun Exception.writeToFile(path: String)
        = File(path).printWriter().use { out -> out.println(this.stackTraceToString()) }

/** Write value to BufferedWriter and flush. **/
fun BufferedWriter.writeAndFlush(str: String) { write(str); flush() }
fun BufferedWriter.writeAndFlush(char: Char) { write(char.code); flush() }


/** Replace the value on a JsonObject for a specific key-path **/
fun JsonObject.replaceValue(key: List<String>, newValue: JsonElement): JsonObject = buildJsonObject {
    this@replaceValue.entries.forEach {
        if (it.key == key[0])
            put(it.key, if (key.size == 1) newValue else it.value.jsonObject.replaceValue(key.drop(1), newValue))
        else
            put(it.key, it.value)
    }
}

/** Replace "\r", "\n" with their actual characters. **/
fun String.unescapeLinebreaks(): String =
    if (!this.contains('\\')) this
    else this.replace("\\n", "\n")
        .replace("\\r", "\r")


/** Determine whether a string follows the official regex for identifiers **/
private val uppaalIdentRegex = Regex("""[a-zA-Z_][a-zA-Z0-9_]*""")
fun String.isValidUppaalIdent() = uppaalIdentRegex.matches(this)
