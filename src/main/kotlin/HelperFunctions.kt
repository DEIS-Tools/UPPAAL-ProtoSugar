import mapping.base.UppaalPath
import mapping.rewriting.InsertOp
import mapping.rewriting.Rewriter

/** Escape all letters in terms of Json. (Does not handle syntax errors.) **/
fun String.jsonFy() = this.replace("\\", "\\\\").replace("\"", "\\\"")

/** Un-escape all characters in terms of Json. (Does not handle syntax errors.) **/
fun String.unJsonFy() = this.replace("\\\"", "\"").replace("\\\\", "\\")

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
fun List<Int>.product(): Int
    = this.fold(1) { acc, value -> acc * value }


/** Works similar to 'String.joinToString()'. It takes an insertion-'location', a list of string 'elements' to insert,
 * and an optional 'separator' string to insert between any two adjacent elements.
 * The output is a list of 'InsertOp' objects for each element in 'elements' (not for the 'separator' elements). **/
fun Rewriter.joinInsert(location: Int, elements: List<String>, separator: String = ", "): Sequence<IndexedValue<InsertOp>> =
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
fun MutableMap<String, Rewriter>.createOrGetRewriter(path: UppaalPath, originalText: String): Rewriter
    = this.getOrPut(path.toString()) { Rewriter(originalText) }