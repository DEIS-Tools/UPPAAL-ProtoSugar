package tools.parsing

import mapping.base.Mapper
import tools.restructuring.TextRewriter
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.reflect.KClass

data class AddTerminal(val name: String, val pattern: String)
data class AddNonTerminal(val name: String, val grammar: String, val sourceMapper: KClass<out Mapper>? = null) {
    init {
        assert(grammar.endsWith('.')) { "Non-terminal grammar must end with a 'dot'" }
    }
}
data class ForkNonTerminal(val baseNonTerminal: String, val newName: String, val sourceMapper: KClass<out Mapper>)

data class ExtendNonTerminal(val extendedNonTerminal: String, val position: List<Int>, val nonTerminalToInsert: String, val type: ExtensionGrammarType, val sourceMapper: KClass<out Mapper>)
enum class ExtensionGrammarType { CHOICE, OPTIONAL, MULTIPLE }

class SyntaxRegistry {
    companion object {
        const val UNSAFE_PREFIX = "_Unsafe_"
    }

    private val terminals = mutableListOf(
        AddTerminal("IDENT",  """[_a-zA-Z$][_a-zA-Z0-9$]*"""),
        AddTerminal("NUMBER", """[0-9]+(\.[0-9]+)?"""),
        AddTerminal("BOOL",   """true|false""")
    )

    private val nonTerminals = mutableListOf(
        AddNonTerminal("Declaration",   "VarOrFunction | Typedef ."),
        AddNonTerminal("VarOrFunction", "Type IDENT (Subscripts ['=' (Expression | ArrayInit)] ';' | '(' ParamList ')' Body) ."),
        AddNonTerminal("Typedef",       "'typedef' Type IDENT ';' ."),

        AddNonTerminal("ArrayInit",     "'{' [ArrayInitTerm] {',' [ArrayInitTerm]} '}' ."),
        AddNonTerminal("ArrayInitTerm", "ArrayInit | Expression ."),
        AddNonTerminal("Subscripts",    "{'[' [Expression] ']'} ."),

        AddNonTerminal("Body",          "'{' {Statement} '}' ."),
        AddNonTerminal("Statement",     "Body | ['return'] [Expression] ';' ."), // TODO
        AddNonTerminal("If",            " Body ."),
        AddNonTerminal("While",         " Body ."), // TODO
        AddNonTerminal("For",           " Body ."),

        AddNonTerminal("ParamList",     "[Param] {',' [Param]} ."),
        AddNonTerminal("Param",         "Type ['&'] IDENT Subscripts"),
        AddNonTerminal("ArgList",       "[Expression] {',' [Expression]} ."),

        AddNonTerminal("Expression",    "[Unary] ('(' Expression ')' | ExtendedTerm {'.' [ExtendedTerm]} | Quantifier) [(Binary|Assignment) Expression] ."),
        AddNonTerminal("ExtendedTerm",  "Term [Subscripts | '(' ArgList ')'] ."),
        AddNonTerminal("Term",          "IDENT | NUMBER | BOOL | 'deadlock' ."),
        AddNonTerminal("Quantifier",    "('forall' | 'exists' | 'sum') '(' IDENT ':' Type ')' Expression ."),
        AddNonTerminal("Unary",         "'+' | '-' | '!' | 'not' ."),
        AddNonTerminal("Binary",        "'<' | '<=' | '==' | '!=' | '>=' | '>' | '+' | '-' | '*' | '/' | '%' | '&' | '|' | '^' | '<<' | '>>' | '&&' | '||' | '<?' | '>?' | 'or' | 'and' | 'imply' ."),
        AddNonTerminal("Assignment",    "'=' | ':=' | '+=' | '-=' | '*=' | '/=' | '%=' | '|=' | '&=' | '^=' | '<<=' | '>>=' ."),

        AddNonTerminal("Type",          "['const'|'meta'] (NonStruct | Struct) ."), // TODO  "|'&'" and "[Subscripts]" <- CompactType
        AddNonTerminal("NonStruct",     "IDENT ['[' [Expression] [',' [Expression]] ']'] ."), // TODO   "| '(' [CompactType] {',' [CompactType]} ')'"
        AddNonTerminal("Struct",        "'struct' '{' StructField {StructField} '}' ."),
        AddNonTerminal("StructField",   "Type IDENT Subscripts {',' IDENT Subscripts} ';' ."),

        AddNonTerminal("PartialInst",  "IDENT ['(' ParamList ')'] '=' IDENT '(' ArgList ')' ';' ."),
        AddNonTerminal("SystemLine",   "'system' [IDENT] {',' [IDENT]} ';'."),

        //AddNonTerminal("", ""),
        //TODO: Queries
    )

    private val forkedNonTerminals = mutableListOf<ForkNonTerminal>()
    private val nonTerminalExtensions = mutableListOf<ExtendNonTerminal>()

    private val blockMarkers = mapOf('(' to ')', '[' to ']', '{' to '}')


    fun makeNonTerminal(name: String, grammar: String, sourceMapper: KClass<out Mapper>) {
        validateNonTerminalName(name)
        nonTerminals.find { it.name == name }?.let {
            if (it.sourceMapper == null)
                throw Exception("The non-terminal '$name' already exists in the base grammar")
            else
                throw Exception("The non-terminal '$name' is already added by the mapper '${it.sourceMapper.qualifiedName}'")
        }

        nonTerminals.add(AddNonTerminal(name, grammar, sourceMapper))
    }

    fun forkNonTerminal(baseNonTerminal: String, newName: String, sourceMapper: KClass<out Mapper>) { // TODO: Include/Exclude extensions made by specific/all other mappers to base (currently include all)
        validateNonTerminalName(baseNonTerminal)
        validateNonTerminalName(newName)
        nonTerminals.find { it.name == newName }?.let {
            if (it.sourceMapper == null)
                throw Exception("A non-terminal with name '$newName' already exists in the base grammar")
            else
                throw Exception("A non-terminal with name '$newName' is already added by the mapper '${it.sourceMapper.qualifiedName}'")
        }

        forkedNonTerminals.find { it.newName == newName }?.let {
            throw Exception("A non-terminal-fork with name '$newName' is already added by the mapper '${it.sourceMapper.qualifiedName}'")
        }

        forkedNonTerminals.add(ForkNonTerminal(baseNonTerminal, newName, sourceMapper))
    }

    fun insertChoice(baseNonTerminal: String, choicePosition: List<Int>, nonTerminalToInsert: String, sourceMapper: KClass<out Mapper>) {
        validateNonTerminalName(nonTerminalToInsert)
        nonTerminalExtensions.add(
            ExtendNonTerminal(baseNonTerminal, choicePosition, nonTerminalToInsert, ExtensionGrammarType.CHOICE, sourceMapper)
        )
    }

    fun insertOptional(baseNonTerminal: String, insertPosition: List<Int>, nonTerminalToInsert: String, sourceMapper: KClass<out Mapper>) {
        validateNonTerminalName(nonTerminalToInsert)
        nonTerminalExtensions.add(
            ExtendNonTerminal(baseNonTerminal, insertPosition, nonTerminalToInsert, ExtensionGrammarType.OPTIONAL, sourceMapper)
        )
    }

    fun insertMultiple(baseNonTerminal: String, insertPosition: List<Int>, nonTerminalToInsert: String, sourceMapper: KClass<out Mapper>) {
        validateNonTerminalName(nonTerminalToInsert)
        nonTerminalExtensions.add(
            ExtendNonTerminal(baseNonTerminal, insertPosition, nonTerminalToInsert, ExtensionGrammarType.MULTIPLE, sourceMapper)
        )
    }


    private fun validateNonTerminalName(nonTerminal: String) {
        if (!Confre.identifierPattern.matches(nonTerminal))
            throw Exception("Invalid non-terminal name: $nonTerminal")
    }

    fun postValidation() {
        for (ext in nonTerminalExtensions) {
            if ((nonTerminals.map { it.name } + forkedNonTerminals.map { it.baseNonTerminal }).none { it == ext.extendedNonTerminal })
                throw Exception("Extension '$ext' does not apply to any existing (forked) non-terminal")
        }
    }


    fun generateParser(rootNonTerminal: String, mapperType: KClass<out Mapper>): GuardedConfre {
        if (rootNonTerminal.startsWith(UNSAFE_PREFIX))
            throw Exception("Root non-terminal cannot be unsafe")

        val safeNonTerminals = LinkedHashMap<String, Pair<String, String>>() // trueName -> (safeName, extendedGrammar)

        for (nonTerminal in nonTerminals)
            safeNonTerminals[nonTerminal.name] = makeSafeAndExtend(mapperType, nonTerminal)

        for (fork in forkedNonTerminals) {
            val baseNonTerminal = nonTerminals.find { it.name == fork.baseNonTerminal }
                ?: throw Exception("Attempted to make fork '${fork.newName}' of non-existent non-terminal '${fork.baseNonTerminal}'")

            if (baseNonTerminal.sourceMapper != null) // TODO: Maybe allow this later?
                throw Exception("Attempted to make fork '${fork.newName}' of non-standard non-terminal '${baseNonTerminal.name}' from mapper '${baseNonTerminal.sourceMapper.qualifiedName}'")

            val forkedNonTerminal = AddNonTerminal(fork.newName, baseNonTerminal.grammar, fork.sourceMapper)
            safeNonTerminals[forkedNonTerminal.name] =
                makeSafeAndExtend(mapperType, forkedNonTerminal, baseExtensions = nonTerminalExtensions.filter { it.extendedNonTerminal == fork.baseNonTerminal })
        }

        val grammarLines = terminals.map { "${it.name} = ${it.pattern}" } +
                safeNonTerminals.map { "${it.value.first} :== ${it.value.second}" }

        if (safeNonTerminals.values.none { it.first == rootNonTerminal })
            throw Exception("No non-terminal '$rootNonTerminal' exists and thus cannot be the root")

        return GuardedConfre(Confre(grammarLines.joinToString("\n"), rootNonTerminal))
    }

    private fun makeSafeAndExtend(
        mapperType: KClass<out Mapper>,
        nonTerminal: AddNonTerminal,
        baseExtensions: List<ExtendNonTerminal> = listOf()
    ): Pair<String, String> {
        val safeName = getSafeName(nonTerminal.name, nonTerminal.sourceMapper, mapperType)
        val extendedGrammar = TextRewriter(nonTerminal.grammar)

        for (extension in baseExtensions + nonTerminalExtensions.filter { it.extendedNonTerminal == nonTerminal.name }) {
            val insertIndex = getInsertIndex(nonTerminal.name, extendedGrammar.originalText, extension.position, extension.type == ExtensionGrammarType.CHOICE)
                ?: throw Exception("Invalid extension ($extension) or base non-terminal grammar ($nonTerminal)")
            if ((nonTerminal.sourceMapper ?: extension.sourceMapper) != extension.sourceMapper) // TODO: Maybe allow this later
                throw Exception("You can currently only make extensions to base non-terminals and forks from your own mapper")

            val insertText =
                when (extension.type) {
                    ExtensionGrammarType.CHOICE -> " | ${getSafeName(extension.nonTerminalToInsert, extension.sourceMapper, mapperType)}"
                    ExtensionGrammarType.OPTIONAL -> "[${getSafeName(extension.nonTerminalToInsert, extension.sourceMapper, mapperType)}] "
                    ExtensionGrammarType.MULTIPLE -> "{${getSafeName(extension.nonTerminalToInsert, extension.sourceMapper, mapperType)}} "
                }

            extendedGrammar.insert(insertIndex, insertText)
        }

        return Pair(safeName, extendedGrammar.getRewrittenText().replace(Regex("""\s\s+"""), " "))
    }

    private fun getSafeName(nonTerminal: String, sourceMapper: KClass<out Mapper>?, mapperType: KClass<out Mapper>)
        = if (mapperType == (sourceMapper ?: mapperType))
            nonTerminal
        else
            UNSAFE_PREFIX + nonTerminal

    private fun getInsertIndex(nonTerminal: String, grammar: String, position: List<Int>, isChoiceInsert: Boolean = false): Int? {
        if (position.isEmpty())
            return if (isChoiceInsert) grammar.lastIndexOf('.')
                   else throw Exception("Position cannot be empty when 'isChoiceInsert == false'")

        val chars = BufferedIterator(grammar.withIndex().iterator())
        chars.next()
        val targetPos = BufferedIterator(position.iterator())
        targetPos.next()

        val currentDepthAndBlock = Stack<Char>()
        var currentTargetDepth = 0
        var currentPos = 0
        var goalInCurrentBlock = false

        while (true) {
            val (index, char) = chars.current()

            // Reached goal
            if (!goalInCurrentBlock && !char.isWhitespace() && currentPos == targetPos.current() && !targetPos.hasNext())
                if (!isChoiceInsert) // If inserting a choice, find the end of the currently selected block
                    return index
                else if (!blockMarkers.containsKey(char)) // If not a block: exception // TODO: Maybe allow this later
                    throw Exception("Only a block can be extended with a new option/choice. The designated element on index '$index' (char == '$char') in non-terminal '$nonTerminal' is not a block")
                else
                    goalInCurrentBlock = true

            // Handle whitespace & option operator
            if (char.isWhitespace() || char == '|')
                chars.tryNext() ?: return null

            // Handle terminal
            else if (char == '\'') {
                do {
                    val (_, throwaway) = chars.tryNext() ?: return null
                    if (throwaway == '\\')
                        chars.tryNext() ?: return null
                } while (throwaway != '\'')
                chars.tryNext() ?: return null

                if (currentDepthAndBlock.size == currentTargetDepth)
                    currentPos++
            }

            // Handle non-terminal
            else if (char.isLetterOrDigit() || char == '_') {
                do {
                    val (_, throwaway) = chars.tryNext() ?: return null
                } while (throwaway.isLetterOrDigit() || throwaway == '_')

                if (currentDepthAndBlock.size == currentTargetDepth)
                    currentPos++
            }

            // Enter block
            else if (blockMarkers.containsKey(char)) {
                if (currentPos == targetPos.current() && targetPos.hasNext()) {
                    targetPos.next()
                    currentTargetDepth++
                    currentPos = 0
                }
                currentDepthAndBlock.push(char)
                chars.tryNext() ?: return null
            }

            // Exit a block
            else if (blockMarkers.containsValue(char)) {
                if (currentDepthAndBlock.isEmpty())
                    throw Exception("Block-end '$char' at index '$index' in non-terminal '$nonTerminal' does not close any block")
                if (blockMarkers[currentDepthAndBlock.peek()] != char)
                    throw Exception("Block-end '$char' at index '$index' in non-terminal '$nonTerminal' does not close the previously opened block: '${currentDepthAndBlock.peek()}'")

                currentDepthAndBlock.pop()
                if (isChoiceInsert && currentDepthAndBlock.size == currentTargetDepth)
                    return if (currentPos == targetPos.current()) index // If this block is at the expected location
                           else null

                if (currentDepthAndBlock.size < currentTargetDepth)
                    return null // Left block where we expected to find the target

                else if (currentDepthAndBlock.size == currentTargetDepth)
                    currentPos++ // We are continuing the search after this block

                chars.tryNext() ?: return null
            }

            // Handle end of grammar
            else if (char == '.')
                return null

            // All else
            else
                throw Exception("Unhandled case: '($index, $char)'. Should this happen?")
        }
    }
}


@Suppress("MemberVisibilityCanBePrivate")
class GuardedConfre(val confre: Confre) {
    // To cache certain safety results that never change
    private val localSafetyPredictionCache = mutableMapOf<Grammar, Safety>()
    val grammar get() = confre.grammar

    fun matchExact(string: String): GuardedParseTree?
            = confre.matchExact(string)?.let { GuardedParseTree(it, localSafetyPredictionCache) }

    fun find(string: String, startIndex: Int = 0): GuardedParseTree?
            = confre.find(string, startIndex)?.let { GuardedParseTree(it, localSafetyPredictionCache) }

    fun findAll(string: String): Sequence<GuardedParseTree>
            = confre.findAll(string).map { GuardedParseTree(it, localSafetyPredictionCache) }
}

enum class Safety { SAFE, PARTIAL, UNSAFE }

@Suppress("MemberVisibilityCanBePrivate")
class GuardedParseTree(private val parseTree: ParseTree, private val localSafetyPredictionCache: MutableMap<Grammar, Safety>) {
    private val visibleIndices: List<Int> by lazy { node.children.withIndex().filter { predictLocalSafety(it.value) != Safety.UNSAFE }.map { it.index } }
    private val guardedChildren = hashMapOf<ParseTree, GuardedParseTree>()

    val isNonTerminal get() = parseTree.grammar is NonTerminalRef
    val isTerminal get() = parseTree.grammar is TerminalRef
    val isBlank get() = parseTree.isBlank()

    val isNode get() = parseTree is Node
    val isLeaf get() = parseTree is Leaf

    val node get() = parseTree.asNode()
    val leaf get() = parseTree.asLeaf()
    val tree get() = parseTree

    val localVisiblySafe get() = getSafety(onlyLocal = true, onlyVisible = true) == Safety.SAFE
    val localFullySafe get() = getSafety(onlyLocal = true, onlyVisible = false) == Safety.SAFE
    val globalVisiblySafe get() = getSafety(onlyLocal = false, onlyVisible = true) == Safety.SAFE
    val globalFullySafe get() = getSafety(onlyLocal = false, onlyVisible = false) == Safety.SAFE

    val indices get() = visibleIndices.indices
    val size get() = visibleIndices.size
    val visibleRange get() // TODO: Does not account for hidden branches below 1st layer
            = IntRange(
        visibleIndices.map { getVisibleChild(it) }.first { it?.isNotBlank() ?: false }!!.startPosition(),
        visibleIndices.map { getVisibleChild(it) }.first { it?.isNotBlank() ?: false }!!.endPosition()
    )
    val fullRange get() = parseTree.range
    fun range(startIndex: Int, endIndex: Int) // TODO: Does not account for hidden branches below 1st layer
            = IntRange(getVisibleChild(startIndex)!!.startPosition(), getVisibleChild(endIndex)!!.endPosition())

    val children get() = visibleIndices.map { get(it) }

    override fun toString(): String = parseTree.toString()
    fun toStringNotNull(): String = parseTree.toStringNotNull()

    operator fun get(visibleIndex: Int): GuardedParseTree? {
        val child = getVisibleChild(visibleIndex)
        return guardedChildren.getOrPut(child ?: return null) {
            if (getSafety(onlyLocal = true, onlyVisible = true, visibleIndex) != Safety.UNSAFE)
                GuardedParseTree(child, localSafetyPredictionCache)
            else
                throw Exception("The child at visible index '$visibleIndex' is unsafe")
        }
    }
    fun getUnguarded(visibleIndex: Int): ParseTree? = getVisibleChild(visibleIndex)
    fun findLocal(nonTerminalName: String): GuardedParseTree? {
        assert(getNonTerminalSafety(nonTerminalName) == Safety.SAFE) { "Cannot search for an unsafe non-terminal" }
        return findNonTerminal(nonTerminalName)
    }
    fun findGlobal(nonTerminalName: String): GuardedParseTree? {
        TODO("Just todo")
    }


    fun getSafety(onlyLocal: Boolean, onlyVisible: Boolean): Safety
            = checkSafety(onlyLocal, onlyVisible)
    fun getSafety(onlyLocal: Boolean, onlyVisible: Boolean, childIndex: Int): Safety
            = getSafety(onlyLocal, onlyVisible, childIndex, childIndex)
    fun getSafety(onlyLocal: Boolean, onlyVisible: Boolean, startIndex: Int, endIndex: Int): Safety {
        checkRanges(startIndex, endIndex, onlyVisible)
        return checkSafety(
            onlyLocal,
            onlyVisible,
            visibleIndices[startIndex],
            visibleIndices[endIndex]
        )
    }


    private fun checkSafety(
        onlyLocal: Boolean,
        onlyVisible: Boolean,
        startIndex: Int = if (onlyVisible) visibleIndices.first() else 0,
        endIndex: Int = if (onlyVisible) visibleIndices.last() else node.children.lastIndex
    ): Safety {
        var unsafe = false
        var safe = false
        for ((internalIndex, internalChild) in node.children.withIndex().filter { it.index in startIndex..endIndex }) {
            if (internalIndex in visibleIndices) {
                val child = guardedChildren[internalChild] ?:
                        if (internalChild == null) null
                        else GuardedParseTree(internalChild, localSafetyPredictionCache)

                if (child == null || child.isTerminal || child.isBlank) {
                    safe = true
                    continue
                }
                else if (child.isNonTerminal) {
                    val safety = getNonTerminalSafety(child)
                    val isUnsafe = safety == Safety.UNSAFE

                    // If non-local is enabled && child is only locally safe, it might actually be partial, thus continue analysis below
                    if (onlyLocal || isUnsafe) {
                        when (isUnsafe) {
                            true -> unsafe = true
                            false -> safe = true
                        }
                        continue
                    }
                }

                when (child.checkSafety(onlyLocal, onlyVisible)) {
                    Safety.PARTIAL -> return Safety.PARTIAL // If any child is partial, all is partial.
                    Safety.UNSAFE -> unsafe = true
                    Safety.SAFE -> safe = true
                }
            }
            else if (!onlyVisible) {
                if (internalChild == null || internalChild.preOrderWalk().all { it.grammar !is NonTerminalRef })
                    safe = true
                else
                    unsafe = true
            }

            if (safe && unsafe)
                return Safety.PARTIAL
        }

        // If partial has not been returned earlier, it can only be safe or unsafe
        return if (unsafe) Safety.UNSAFE
            else Safety.SAFE // If not unsafe, the range is safe even if "safe == null" in case a child was null
    }

    private fun checkRanges(startIndex: Int, endIndex: Int, onlyVisible: Boolean) {
        assert(startIndex <= endIndex) { "The 'startIndex' ($startIndex) must be no greater than the 'endIndex' ($endIndex)" }
        if (onlyVisible) {
            assert(startIndex in visibleIndices.indices) { "The 'startIndex' ($startIndex) must be within the visible range '${visibleIndices.indices}'" }
            assert(endIndex in visibleIndices.indices) { "The 'endIndex' ($endIndex) must be within the visible range '${visibleIndices.indices}'" }
        }
        else {
            assert(startIndex in node.children.indices) { "The 'startIndex' ($startIndex) must be within the full range '${node.children.indices}'" }
            assert(endIndex in node.children.indices) { "The 'endIndex' ($endIndex) must be within the full range '${node.children.indices}'" }
        }
    }

    private fun findNonTerminal(nonTerminalName: String): GuardedParseTree? {
        for (internalChild in visibleIndices.map { node.children[it] }.filterIsInstance<Node>()) {
            if (internalChild.grammar is NonTerminalRef) {
                if (internalChild.grammar.nonTerminalName == nonTerminalName)
                    return getGuarded(internalChild)
            } else {
                return getGuarded(internalChild).findLocal(nonTerminalName)
                    ?: continue
            }
        }

        return null
    }

    private fun findTerminal(terminalName: String): GuardedParseTree? {
        for (internalChild in visibleIndices.map { node.children[it] }.filterIsInstance<Node>()) {
            if (internalChild.grammar is TerminalRef) {
                if (internalChild.grammar.terminalName == terminalName)
                    return getGuarded(internalChild)
            } else {
                return getGuarded(internalChild).findLocal(terminalName)
                    ?: continue
            }
        }

        return null
    }

    private fun predictLocalSafety(parseTree: ParseTree?): Safety {
        val grammar = parseTree?.grammar ?: return Safety.SAFE
        return localSafetyPredictionCache[grammar] ?: predictLocalSafety(grammar)
    }
    private fun predictLocalSafety(grammar: Grammar): Safety {
        return when (grammar) {
            is TerminalRef, is Blank -> Safety.SAFE
            is NonTerminalRef -> getNonTerminalSafety(grammar.nonTerminalName)
            else -> {
                var unsafe = 0
                var safe = 0
                for (safety in grammar.children().map { predictLocalSafety(it) })
                    when (safety) {
                        Safety.PARTIAL -> return Safety.PARTIAL
                        Safety.SAFE -> ++safe
                        Safety.UNSAFE -> ++unsafe
                    }

                when {
                    unsafe == 0 && safe > 0 -> Safety.SAFE
                    unsafe > 0 && safe > 0 -> Safety.PARTIAL
                    unsafe > 0 && safe == 0 -> Safety.UNSAFE
                    else -> throw Exception("Cannot determine safety of grammar:\n${grammar}")
                }
            }
        }
    }

    private fun getVisibleChild(index: Int): ParseTree? {
        return if (index in visibleIndices.indices)
            node.children[visibleIndices[index]]
        else
            throw Exception("The index '$index' was not within the range of visible indices '${visibleIndices.indices}'")
    }

    private fun getGuarded(parseTree: ParseTree): GuardedParseTree
        = guardedChildren.getOrPut(parseTree) { GuardedParseTree(parseTree, localSafetyPredictionCache) }

    private fun getNonTerminalSafety(guardedParseTree: GuardedParseTree): Safety
            = getNonTerminalSafety((guardedParseTree.parseTree.grammar as NonTerminalRef).nonTerminalName)
    private fun getNonTerminalSafety(nonTerminalName: String): Safety {
        assert(Confre.identifierPattern.matches(nonTerminalName)) { "'$nonTerminalName' is not a valid non-terminal name" }
        return when (nonTerminalName.startsWith(SyntaxRegistry.UNSAFE_PREFIX)) {
            true -> Safety.UNSAFE
            false -> Safety.SAFE
        }
    }
}