package mapping.rewriting

import length
import mapping.base.LineColumnRange
import mapping.base.UppaalError
import offset
import overlaps
import within
import java.util.function.Predicate
import java.util.function.Function
import kotlin.Exception

/** The Rewriter performs "batch-transformations" on some 'originalText' based on a number of pre-registered operations
 * (insert, replace, append), which are, at the call of 'rewriteText', all applied to the originalText in "text-location and chronological"-order.
 * Operations may not overlap. Overlapping operations must be done over multiple phases (multiple Rewriter instances).
 * The rewriter simultaneously constructs maps to help map UPPAAL errors in the mapped text back to the 'originalText'. **/
class Rewriter(val originalText: String) {
    private var hasChanges = false
    private var rewrittenText = StringBuilder(originalText)
    private val simpleBackMaps = ArrayList<SimpleBackMap>()
    private var appendStartsAt = Int.MAX_VALUE
    private val advancedBackMaps = ArrayList<AdvancedBackMap>()

    private val insertOps = ArrayList<InsertOp>()
    private val replaceOps = ArrayList<ReplaceOp>()
    private val appendOps = ArrayList<AppendOp>()


    /** Insert 'text' at 'location', leaving the current symbol on 'location' after the inserted text.
     * Insert and replace operations may not overlap. **/
    fun insert(location: Int, newText: String): InsertOp {
        if (location !in (0 .. originalText.length))
            throw Exception("The insert location must exist within or just after the last character of '${::originalText.name}'. Allowed range = [0; ${originalText.length}].")
        if (newText == "")
            throw Exception("Cannot insert empty string.")

        val op = InsertOp(location, newText)
        throwIfOverlapsWithExisting(op)
        insertOps.add(op)
        hasChanges = true
        return op
    }

    /** Swap a range of characters defined by 'range' with 'text'.
     * Replace/replace and replace/insert operations may not overlap. **/
    fun replace(range: IntRange, newText: String): ReplaceOp {
        if (range.last < range.first)
            throw Exception("The first element of a replace-range must be less than or equal to the second. Got [${range.first}; ${range.last}].")
        if (!range.within(originalText.indices))
            throw Exception("The replace-range must exist within '${::originalText.name}' with range [0; ${originalText.length-1}]. Got [${range.first}; ${range.last}].")

        val op = ReplaceOp(range, newText)
        throwIfOverlapsWithExisting(op)
        replaceOps.add(op)
        hasChanges = true
        return op
    }

    /** Append text at end of original text. Append operations cannot overlap with other operations.
     * Insert operations at end of string are added before append operations. **/
    fun append(newText: String): AppendOp {
        val op = AppendOp(newText)
        appendOps.add(op)
        hasChanges = true
        return op
    }

    private fun throwIfOverlapsWithExisting(newInsert: InsertOp) {
        val overlap = replaceOps.find { overlaps(newInsert, it) }
            ?: return
        throw Exception("New insert operation $newInsert overlaps with the replace operation $overlap.")
    }

    private fun throwIfOverlapsWithExisting(newReplace: ReplaceOp) {
        val insertOverlaps = insertOps
            .filter { overlaps(it, newReplace) }
        val replaceOverlaps = replaceOps
            .filter { overlaps(it, newReplace) }

        val overlapMessage = insertOverlaps.plus(replaceOverlaps).joinToString("\n")
        if (overlapMessage.isNotBlank())
            throw Exception("Replace operation $newReplace overlaps with the following operations:\n\n$overlapMessage")
    }

    private fun overlaps(insert: InsertOp, replace: ReplaceOp): Boolean
        // Overlap only when "first < insert <= last" since if "first == insert", that insert happens just before the replace operation.
        // A replace operation that is one letter wide thus cannot overlap an insert.
        = (replace.range.first < insert.location) && (insert.location <= replace.range.last)

    private fun overlaps(replace1: ReplaceOp, replace2: ReplaceOp): Boolean
        = replace1.range.overlaps(replace2.range)


    /** Returns the rewritten version of 'originalText' based on the registered 'insert', 'replace', and 'append' operations. **/
    fun getRewrittenText(): String {
        if (hasChanges) recompile()
        return rewrittenText.toString()
    }

    /** Attempt to map 'error' from the current location in mapped text to the "correct" location in the original text.
     * IMPORTANT: The back-mapper ONLY operates on the latest results (text + back-maps) from 'getRewrittenText()'! This
     * safeguards it from back-mapping using text/back-maps that CANNOT possibly exist in the submitted UPPAAL model. **/
    fun backMapError(error: UppaalError): BackMapResult {
        val errorIntRange = error.range.toIntRange(rewrittenText.toString())

        // Advanced back-mapping
        val advBackMap = advancedBackMaps.filter { it.appliesTo(errorIntRange) }.maxByOrNull { it.priority }
        if (null != advBackMap)
            return advBackMap.apply(error, this)

        // Simple back mapping
        if (errorIntRange.first >= appendStartsAt)
            error.message = "FROM AUTO-GENERATED CODE: ${error.message}"
        else {
            val backMappedIntRange = simpleBackMaps.filter { it.appliesTo(errorIntRange) }
                .ifEmpty { return BackMapResult.NO_CHANGE }
                .fold(errorIntRange) { range, backMap -> backMap.apply(range, errorIntRange.first) }
            error.range = LineColumnRange.fromIntRange(originalText, backMappedIntRange)
        }
        return BackMapResult.CHANGES_APPLIED
    }

    /** Compute rewritten text and back-maps. **/
    private fun recompile() {
        rewrittenText = StringBuilder(originalText)
        simpleBackMaps.clear()
        advancedBackMaps.clear()

        var offset = 0
        for (operation in (insertOps + replaceOps + appendOps).sortedBy { it.startLocation() })
            offset += when (operation) {
                is InsertOp -> compileInsert(operation, offset)
                is ReplaceOp -> compileReplace(operation, offset)
                is AppendOp -> compileAppend(operation) // Always returns zero
                else -> throw Exception("Cannot handle unknown rewrite operation: ${operation.javaClass.name}")
            }
        appendStartsAt = originalText.length + offset

        hasChanges = false
    }

    /** Perform insert operation and generate back-maps. **/
    private fun compileInsert(insert: InsertOp, globalOffset: Int): Int {
        val insertLocation = insert.location + globalOffset
        rewrittenText.insert(insertLocation, insert.newText)

        val localOffset = insert.newText.length
        val endLocation = insertLocation + localOffset - 1 //-1 to make inclusive end
        simpleBackMaps.add(SimpleBackMap(endLocation, localOffset))
        advancedBackMaps.addAll(insert.getAdvancedBackMaps(insertLocation))

        return localOffset
    }

    /** Perform replace operation and generate back-maps. **/
    private fun compileReplace(replace: ReplaceOp, globalOffset: Int): Int {
        val replaceRange = replace.range.offset(globalOffset)
        rewrittenText.replace(replaceRange.first, replaceRange.last + 1, replace.newText)

        val localOffset = replace.newText.length - replace.range.length()
        simpleBackMaps.add(SimpleBackMap(replaceRange.last, localOffset))
        advancedBackMaps.addAll(replace.getAdvancedBackMaps(replaceRange.first))

        return localOffset
    }

    /** Perform append operation and generate back-maps. **/
    private fun compileAppend(append: AppendOp): Int {
        val appendStartIndex = rewrittenText.length
        rewrittenText.append(append.newText)

        advancedBackMaps.addAll(append.getAdvancedBackMaps(appendStartIndex))

        return 0
    }
}

enum class BackMapResult {
    CHANGES_APPLIED, NO_CHANGE, REQUEST_DISCARD
}


/** Base-class for all rewrite operations. It stores additional information to create advanced back-maps for the
 * UPPAAL errors that are generated within the new text added by the operation. **/
abstract class Operation(val newText: String) {
    private val builders = ArrayList<AdvancedBackMapBuilder>()

    fun addBackMap(): IAdvancedBackMapBuilder {
        val builder = AdvancedBackMapBuilder(this)
        builders.add(builder)
        return builder
    }
    fun getAdvancedBackMaps(currentRewriteLocation: Int)
        = builders.map { it.build(currentRewriteLocation) }

    abstract fun startLocation(): Int
}

/** All info needed for an insert operation. **/
class InsertOp(val location: Int, newText: String) : Operation(newText) {
    override fun startLocation() = location
    override fun toString() = "insert($location, \"$newText\")"
}

/** All info needed for a replace operation. **/
class ReplaceOp(val range: IntRange, newText: String) : Operation(newText) {
    override fun startLocation() = range.first
    override fun toString() = "replace([${range.first}; ${range.last}], \"$newText\")"
}

/** All info needed for an append operation. **/
class AppendOp(newText: String) : Operation(newText) {
    override fun startLocation() = Int.MAX_VALUE
    override fun toString() = "append(\"$newText\")"
}


/** Simple back-maps are automatically generated and are used to offset the location of UPPAAL errors generated on the
 * rewritten text in order to "fit into" the original text.
 * Multiple SimpleBackMaps may be applied to the same UPPAAL error. **/
class SimpleBackMap(private val inclusiveRewrittenEndLocation: Int, private val offset: Int) {
    /** Based on the in-rewritten-text range of a UPPAAL error, determine if this back-map applies to said error. **/
    fun appliesTo(range: IntRange): Boolean
        = range.last >= inclusiveRewrittenEndLocation

    fun apply(range: IntRange, inclusiveRewrittenStartLocation: Int): IntRange {
        return if (inclusiveRewrittenStartLocation > inclusiveRewrittenEndLocation)
            range.offset(-offset)
        else // If the back-map is inside an error, adjust the width of the error by only offsetting the end position
            (range.first .. range.last - offset)
    }
}

/** Advanced back-maps are created in user-code by calling 'Operation.addBackMap()' and using the returned "builder" to
 * construct a back-map with more complex rules for how to handle certain errors. See "AdvancedBackMapBuilder" for more.
 * At most one AdvancedBackMap will be applied to one UPPAAL error. The map with the highest priority-number is used (in
 * case of a tie, the first-added one is used). **/
class AdvancedBackMap(
    val priority: Int,
    private val range: IntRange,
    private val rule: ActivationRule,
    private val pathFunction: Function<String, Pair<String, Rewriter>>?,
    private val rangeFunction: Function<IntRange, IntRange>,
    private val messageFunction: Function<String, String>?,
    private val contextFunction: Function<String, String>?,
    private val discardCondition: Predicate<UppaalError>?
) {
    /** Based on the in-rewritten-text range of a UPPAAL error, determine if this back-map applies to said error. **/
    fun appliesTo(errorRange: IntRange): Boolean = rule.passesActivation(range, errorRange)

    fun apply(error: UppaalError, sourceRewriter: Rewriter): BackMapResult {
        if (discardCondition?.test(error) == true)
            return BackMapResult.REQUEST_DISCARD

        val (targetPath, targetRewriter) = pathFunction?.apply(error.path) ?: Pair(error.path, sourceRewriter)
        error.path = targetPath

        val sourceRange = error.range.toIntRange(sourceRewriter.getRewrittenText())
        val targetRange = rangeFunction.apply(sourceRange)
        if (!targetRange.within(targetRewriter.originalText.indices))
            throw Exception("Advanced back-map returned invalid range [${targetRange.first}; ${targetRange.last}]. Original text range was [${targetRewriter.originalText.indices.first}; ${targetRewriter.originalText.indices.last}].")
        error.range = LineColumnRange.fromIntRange(targetRewriter.originalText, targetRange)

        if (null != messageFunction)
            error.message = messageFunction.apply(error.message)
        if (null != contextFunction)
            error.context = contextFunction.apply(error.context)

        return BackMapResult.CHANGES_APPLIED
    }
}


/** The user-code API for an AdvancedBackMapBuilder. See implementation below for more detail. **/
interface IAdvancedBackMapBuilder {
    fun activateOn(range: IntRange, rule: ActivationRule): IAdvancedBackMapBuilder
    fun activateOn(rule: ActivationRule): IAdvancedBackMapBuilder
    fun withPriority(priority: Int): IAdvancedBackMapBuilder
    fun overrideErrorPath(pathFunction: Function<String, Pair<String, Rewriter>>): IAdvancedBackMapBuilder
    fun overrideErrorRange(rangeFunction: Function<IntRange, IntRange>): IAdvancedBackMapBuilder
    fun overrideErrorMessage(messageFunction: Function<String, String>): IAdvancedBackMapBuilder
    fun overrideErrorContext(contextFunction: Function<String, String>): IAdvancedBackMapBuilder
    fun discardError(condition: Predicate<UppaalError> = Predicate { true }): IAdvancedBackMapBuilder
}

/** The full builder interface for AdvancedBackMaps. By default, the priority is zero and the mapper activates on UPPAAL
 * errors that land exactly on the new text added by the underlying rewrite-operation.
 * User-code must always explicitly define the range-override. **/
class AdvancedBackMapBuilder(private val operation: Operation): IAdvancedBackMapBuilder {
    private var activationRange = operation.newText.indices
    private var activationRule = ActivationRule.EXACT
    private var priority = 0

    private var pathFunction: Function<String, Pair<String, Rewriter>>? = null
    private var rangeFunction: Function<IntRange, IntRange>? = null
    private var messageFunction: Function<String, String>? = null
    private var contextFunction: Function<String, String>? = null
    private var discardCondition: Predicate<UppaalError>? = null


    /** Set the text range and rule for when the back-map should activate for a UPPAAL error.
     * The range must be a range within 'newText' of the underlying rewrite-operation. The actual range in the rewritten
     * text will vary depending on other operations and is thus generated later during rewriting. **/
    override fun activateOn(range: IntRange, rule: ActivationRule): IAdvancedBackMapBuilder {
        if (!range.within(operation.newText.indices))
            throw Exception("The activation range (got [${range.first}; ${range.last}]) must be within the range of the new text (which was [${operation.newText.indices.first}; ${operation.newText.indices.last}])")
        activationRange = range
        activationRule = rule
        return this
    }
    override fun activateOn(rule: ActivationRule): IAdvancedBackMapBuilder {
        activationRule = rule
        return this
    }

    /** Set the priority of this back-map.  **/
    override fun withPriority(priority: Int): IAdvancedBackMapBuilder {
        this.priority = priority
        return this
    }

    /** The 'pathFunction' takes as input the in-rewritten-model path of the current UPPAAL error. User-code can
     * optionally use this input to generate the new in-original-model path.
     * The 'pathFunction' outputs both "the new path" AND "a rewriter for the text in the new path" **/
    override fun overrideErrorPath(pathFunction: Function<String, Pair<String, Rewriter>>): IAdvancedBackMapBuilder {
        this.pathFunction = pathFunction
        return this
    }

    /** The 'rangeFunction' takes as input the in-rewritten-text range of the current UPPAAL error. User-code can
     * optionally use this input to generate the new in-original-text range.
     * IMPORTANT: This function MUST be called at least once on all AdvancedBackMapBuilders. **/
    override fun overrideErrorRange(rangeFunction: Function<IntRange, IntRange>): IAdvancedBackMapBuilder {
        this.rangeFunction = rangeFunction
        return this
    }

    /** The 'messageFunction' takes as input the message of the current UPPAAL error. User-code may optionally use this
     * input to generate the new message text. **/
    override fun overrideErrorMessage(messageFunction: Function<String, String>): IAdvancedBackMapBuilder {
        this.messageFunction = messageFunction
        return this
    }

    /** The 'contextFunction' takes as input the context of the current UPPAAL error. User-code may optionally use this
     * input to generate the new context text. **/
    override fun overrideErrorContext(contextFunction: Function<String, String>): IAdvancedBackMapBuilder {
        this.contextFunction = contextFunction
        return this
    }

    /** Some errors may not be desirable to "let through" to the GUI. All UPPAAL errors that this AdvancedBackMap
     * applies to will be discarded if the supplied 'condition' returns 'true' (default 'condition' is always true). **/
    override fun discardError(condition: Predicate<UppaalError>): IAdvancedBackMapBuilder {
        this.discardCondition = condition
        return this
    }


    fun build(currentRewriteLocation: Int): AdvancedBackMap {
        if (null == rangeFunction)
            throw Exception("An AdvancedBackMapBuilder has no range-override on the following operation:\n\n$operation")
        return AdvancedBackMap(
            priority, activationRange.offset(currentRewriteLocation), activationRule,
            pathFunction, rangeFunction!!, messageFunction, contextFunction, discardCondition
        )
    }
}

/** Used to determine which UPPAAL errors an AdvancedBackMap should handle. **/
enum class ActivationRule {
    EXACT {
        override fun passesActivation(activationRange: IntRange, errorRange: IntRange)
            = activationRange == errorRange
    },
    ERROR_CONTAINS_ACTIVATION {
        override fun passesActivation(activationRange: IntRange, errorRange: IntRange)
            = activationRange.within(errorRange)
    },
    ACTIVATION_CONTAINS_ERROR {
        override fun passesActivation(activationRange: IntRange, errorRange: IntRange)
            = errorRange.within(activationRange)
    },
    EITHER_CONTAINS_OTHER {
        override fun passesActivation(activationRange: IntRange, errorRange: IntRange)
            = errorRange.within(activationRange) || activationRange.within(errorRange)
    },
    INTERSECTS {
        override fun passesActivation(activationRange: IntRange, errorRange: IntRange)
            = activationRange.overlaps(errorRange)
    };

    abstract fun passesActivation(activationRange: IntRange, errorRange: IntRange): Boolean
}
