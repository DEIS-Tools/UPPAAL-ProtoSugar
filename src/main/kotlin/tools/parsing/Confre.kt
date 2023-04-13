package tools.parsing

import java.util.*
import kotlin.Exception
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import java.lang.Character.MIN_VALUE as nullChar

// TODO: Add "tagging"-feature to grammar/Confre, such that (among other things) SyntaxRegistry can put safety-related
//  tags on generated non-terminals

// A "context free grammar"-approximately-equivalent of the "Regex" class.
// Used to check if a string matches the grammar, contains a match, or to find all matches from the string.
// Only works for LL(1) grammars. Undefined behavior otherwise.
// ---------------------------------------------------------------------------------------------------------------------
// STRING       = Any single-quote-surrounded string where inside single-quotes are escaped and with no whitespaces
// REGEX        = Any valid regex
// IDENTIFIER   = [_a-zA-Z][_a-zA-Z0-9]*
// TERMINAL     :== IDENTIFIER '=' REGEX
// NON_TERMINAL :== IDENTIFIER ':==' BODY '.'
// BODY         :== { IDENTIFIER | STRING | '(' BODY ')' | '[' BODY ']' | '{' BODY '}' } [ '|' BODY ]
class Confre(val grammar: String, rootNonTerminalOverride: String? = null) {
    private val eof: NamedTerminal = NamedTerminal(-1, "EOF", Regex(nullChar.toString()))
    private val terminals: MutableList<Terminal> = ArrayList()
    private val nonTerminals: MutableMap<String , NonTerminal> = HashMap()
    private var rootNonTerminal: String

    private val stringPattern = Regex("""'((?>(?>\\[\\'])|[^'\s\\])*)'""")

    companion object {
        val identifierPattern = Regex("""[_a-zA-Z][_a-zA-Z0-9]*""")
    }

    init {
        this.rootNonTerminal = "NON_TERMINAL"
        if (grammar.isNotBlank()) {
            populateTerminals(grammar)
            populateNonTerminals(grammar)
            validateTerminalsAndNonTerminals()
            this.rootNonTerminal = rootNonTerminalOverride ?: this.rootNonTerminal
        }
    }

    private constructor() : this("") {
        terminals.add(NamedTerminal(0, "IDENTIFIER", identifierPattern))
        terminals.add(NamedTerminal(1, "STRING", stringPattern))
        terminals.add(AnonymousTerminal(2, "("))
        terminals.add(AnonymousTerminal(3, ")"))
        terminals.add(AnonymousTerminal(4, "["))
        terminals.add(AnonymousTerminal(5, "]"))
        terminals.add(AnonymousTerminal(6, "{"))
        terminals.add(AnonymousTerminal(7, "}"))
        terminals.add(AnonymousTerminal(8, "|"))
        terminals.add(AnonymousTerminal(9, "."))

        nonTerminals["NON_TERMINAL"] = NonTerminal("NON_TERMINAL",
            Sequential(listOf(
                NonTerminalRef("BODY", nonTerminals),
                TerminalRef(9) // '.'
            ))
        )
        nonTerminals["BODY"] = NonTerminal("BODY",
            Sequential(listOf(
                Multiple( // {
                    Choice(listOf(
                    TerminalRef(0, "IDENTIFIER"), // IDENTIFIER |
                    TerminalRef(1, "STRING"), // STRING |
                    Sequential(listOf( // '(' BODY ')' |
                        TerminalRef(2), NonTerminalRef("BODY", nonTerminals), TerminalRef(3)
                    )),
                    Sequential(listOf( // '[' BODY ']' |
                        TerminalRef(4), NonTerminalRef("BODY", nonTerminals), TerminalRef(5)
                    )),
                    Sequential(listOf( // '{' BODY '}'
                        TerminalRef(6), NonTerminalRef("BODY", nonTerminals), TerminalRef(7)
                    ))
                ))
                ), // }
                Optional(
                    Sequential(listOf( // [ '|' BODY
                    TerminalRef(8), NonTerminalRef("BODY", nonTerminals)
                ))
                ) // ]
            ))
        )
    }

    // Grammar
    private fun populateTerminals(grammar: String) {
        val namedTerminalPattern = Regex("""^\s*[_a-zA-Z][_a-zA-Z0-9]*\s*=.*""")
        val anonymousTerminalPattern = stringPattern

        for (terminal in grammar.lines().filter { namedTerminalPattern.matches(it) }) {
            val parts = terminal.split('=', limit = 2).map { it.trim() }
            if (terminals.any { (it as NamedTerminal).name == parts[0] })
                throw Exception("Duplicate terminal definition with name: '${parts[0]}'")
            terminals.add(NamedTerminal(terminals.size, parts[0], Regex(parts[1])))
        }

        for (terminal in anonymousTerminalPattern.findAll(grammar).map { it.groups[1]!!.value })
            if (terminals.filterIsInstance<AnonymousTerminal>().none { it.value == terminal })
                terminals.add(AnonymousTerminal(terminals.size, terminal.replace("\\'", "'")))
    }

    private fun populateNonTerminals(grammar: String) {
        val nonTerminalStartPattern = Regex("""([_a-zA-Z][_a-zA-Z0-9]*)\s*:==""")
        val grammarParser = Confre() // Cannot be class member due to infinite instantiations

        val nonTerminalsToParse = nonTerminalStartPattern.findAll(grammar).toList()
        for (nonTerminal in nonTerminalsToParse) {
            val name = nonTerminal.groups[1]!!.value
            val startIndex = nonTerminal.range.last+1
            val parseTree = grammarParser.find(grammar, startIndex)
                            ?: throw Exception("Could not parse non-terminal '$name'")

            nonTerminals.put(name, NonTerminal(name, parseNonTerminal(parseTree))) ?: continue
            throw Exception("A non-terminal '$name' does already exist!")
        }
        rootNonTerminal = nonTerminalsToParse.first().groups[1]!!.value
    }

    private fun parseNonTerminal(parseTree: ParseTree): Grammar {
        if (parseTree !is Node || parseTree.children.size != 2 || parseTree.children[0] !is Node || parseTree.children[0]?.isNotBlank() != true)
            throw Exception("Cannot parse non-terminal body: '$parseTree'")

        return parseBody(parseTree.children[0] as Node)
    }

    private fun parseBody(node: Node): Grammar {
        val result: Grammar

        // If the "multiple" part of BODY is not blank
        if (node.children[0]?.isNotBlank() == true) {
            val sequence = ArrayList<Grammar>()
            val bodyParts = (node.children[0] as Node).children.iterator()
            while (bodyParts.hasNext()) { // BODY = Multiple(Choice(...))
                val choiceParts = (bodyParts.next() as Node).children.iterator()
                val token = (choiceParts.next() as Leaf).token!! // First element must always be a leaf
                val terminal = token.terminal
                when (terminal.id) {
                    /* Identifier */ 0 -> sequence.add(determineTerminalOrNonTerminal(token))
                    /* String */     1 -> sequence.add(TerminalRef(terminals.filterIsInstance<AnonymousTerminal>().find { it.value == token.value.drop(1).dropLast(1).replace("\\'", "'") }!!.id))
                    /* Group */      2 -> sequence.add(parseBody(choiceParts.next() as Node))
                    /* Optional */   4 -> sequence.add(Optional(parseBody(choiceParts.next() as Node)))
                    /* Multiple */   6 -> sequence.add(Multiple(parseBody(choiceParts.next() as Node)))
                    /* Other */      else -> throw Exception("Confre parser cannot dispatch unknown grammar construct: '$token'")
                }
            }
            result = if (sequence.size > 1) Sequential(sequence) else sequence[0]
        }
        else
            result = Blank()

        // If the optional "choice" part is not blank
        if (node.children[1]?.isNotBlank() == true)
        {
            // Parse the BODY node inside the Optional node
            val nextOption = parseBody((node.children[1]!! as Node).children[1] as Node)
            return if (nextOption is Choice)
                Choice(listOf(result).plus(nextOption.options))
            else
                Choice(listOf(result, nextOption))
        }

        return result
    }

    private fun determineTerminalOrNonTerminal(token: Token): Grammar
    {
        val terminal = terminals.filterIsInstance<NamedTerminal>().find { it.name == token.value }
        return if (terminal != null)
            TerminalRef(terminal.id, terminal.name)
        else
            NonTerminalRef(token.value, nonTerminals)
    }

    private fun validateTerminalsAndNonTerminals() {
        for (grammar in nonTerminals.values.map { it.grammar })
            for (nonTermRef in grammar.treeWalk().filterIsInstance<NonTerminalRef>())
                if (!nonTerminals.containsKey(nonTermRef.nonTerminalName))
                    throw Exception("Reference to unknown terminal or non-terminal: '${nonTermRef.nonTerminalName}'")
    }


    // Parsing
    private fun tokens(string: String, startIndex: Int): Sequence<Token> {
        var line = 1; var lineStart = 1
        var column = 0; var columnStart = 0 // Column is incremented at the start of each loop
        var index = -1
        var value = ""

        val chars = (string.asSequence() + nullChar + nullChar).iterator()
        var char = nullChar

        fun getToken(): Token {
            // Reverse to give anonymous terminals first priority
            val firstMatch = terminals.reversed().firstOrNull { it.matches(value) } ?: AnonymousTerminal(-1, value)
            return Token(lineStart, columnStart, column, index - value.length, value, firstMatch)
        }

        fun handleWhitespace(char: Char): Boolean {
            if (char == '\n') {
                line++
                column = 0
            }
            return char.isWhitespace()
        }

        fun nextChar() {
            index++
            column++
            char = chars.next()
        }

        return sequence {
            nextChar()
            while (char != nullChar) {
                if (handleWhitespace(char) || index < startIndex) {
                    nextChar()
                    continue
                }

                lineStart = line
                columnStart = column

                value = ""
                while (!(char.isWhitespace() || char == nullChar) && terminals.none { it.matches(value + char) }) {
                    value += char
                    nextChar()
                }
                while (char != nullChar && (terminals.any { it.matches(value + char) } || terminals.filterIsInstance<AnonymousTerminal>().any { it.value.startsWith(value + char) })) {
                    value += char
                    nextChar()
                }
                yield(getToken())
            }
            yield(Token(lineStart, columnStart, column, string.length, nullChar.toString(), eof))
        }
    }

    private fun match(string: String, startIndex: Int, allowPartialMatch: Boolean): ParseTree? { // TODO allow not run to end of string
        val tokens = BufferedIterator(tokens(string, startIndex).iterator())
        tokens.next()

        if (allowPartialMatch) {
            while (true) {
                val accepts = nonTerminals[rootNonTerminal]!!.grammar.expects(tokens.current) ?: throw Exception("") // TODO: Proper error message
                if (accepts) {
                    val tree = tryMatch(tokens)
                    if (tree != null)
                        return tree
                }
                else if (tokens.current.terminal != eof)
                    tokens.next()

                if (tokens.current.terminal == eof)
                    return null
            }
        }
        else {
            val tree = tryMatch(tokens)
            return if (tree != null && tokens.current.terminal == eof) tree
                   else null
        }
    }

    private fun tryMatch(tokens: BufferedIterator<Token>): ParseTree? {
        return try {
            matchNonTerminal(NonTerminalRef(rootNonTerminal, nonTerminals), tokens)
        } catch (ex: Exception) {
            null
        }
    }

    private fun dispatch(grammar: Grammar, tokens: BufferedIterator<Token>): ParseTree {
        return when (grammar) {
            is NonTerminalRef -> matchNonTerminal(grammar, tokens)
            is TerminalRef -> matchTerminal(grammar, tokens)
            is Sequential -> matchSequential(grammar, tokens)
            is Optional -> matchOptional(grammar, tokens)
            is Multiple -> matchMultiple(grammar, tokens)
            is Choice -> matchChoice(grammar, tokens)
            is Blank -> Leaf(grammar, null)
            else -> throw Exception("Unknown grammar type: '${grammar.javaClass.typeName}'")
        }
    }

    private fun matchNonTerminal(nonTerminal: NonTerminalRef, tokens: BufferedIterator<Token>): ParseTree {
        val res = dispatch(nonTerminals[nonTerminal.nonTerminalName]!!.grammar, tokens)
        val children = if (res.grammar is Sequential) (res as Node).children else listOf(res)
        return Node(nonTerminal, children)
    }

    private fun matchTerminal(terminal: TerminalRef, tokens: BufferedIterator<Token>): ParseTree {
        val accepts = terminal.expects(tokens.current)
        if (accepts != true)
            throw Exception("Tried to match token '${tokens.current}' with terminal '${terminals[terminal.terminalId]}'")
        val leaf = Leaf(terminal, tokens.current)
        tokens.next()
        return leaf
    }

    private fun matchSequential(sequential: Sequential, tokens: BufferedIterator<Token>): ParseTree {
        sequential.expects(tokens.current)
            ?: return Node(sequential, sequential.body.map { null })

        val children = ArrayList<ParseTree?>()
        for (subElement in sequential.body)
            children.add(dispatch(subElement, tokens))

        return Node(sequential, children)
    }

    private fun matchOptional(optional: Optional, tokens: BufferedIterator<Token>): ParseTree {
        if (optional.expects(tokens.current) != true)
            return Node(optional, listOf<ParseTree?>(null))

        val subTree = dispatch(optional.body, tokens)
        val children = if (subTree.grammar is Sequential) (subTree as Node).children else listOf(subTree)

        return Node(optional, children)
    }

    private fun matchMultiple(multiple: Multiple, tokens: BufferedIterator<Token>): ParseTree {
        val children = ArrayList<ParseTree>()

        while (multiple.expects(tokens.current) == true)
            children.add(dispatch(multiple.body, tokens))

        return Node(multiple, children)
    }

    private fun matchChoice(choice: Choice, tokens: BufferedIterator<Token>): ParseTree {
        val viableOptions = choice.options.filter { it.expects(tokens.current) == true } +
                            choice.options.filter { it.expects(tokens.current) == null }
        val iterator = viableOptions.iterator()
        val exceptions = ArrayList<Exception>()

        if (viableOptions.size > 1)
            tokens.setSnapshot()
        while (iterator.hasNext()) {
            try {
                val subTree = dispatch(iterator.next(), tokens)
                val children =
                    if (subTree.grammar is Sequential && subTree is Node) subTree.children
                    else listOf(subTree)
                if (viableOptions.size > 1)
                    tokens.clearSnapshot()
                return Node(choice, children)
            }
            catch (ex: Exception) {
                exceptions += ex
                if (viableOptions.size > 1)
                    tokens.restoreSnapshot()
            }
        }
        if (viableOptions.size > 1)
            tokens.clearSnapshot()

        if (exceptions.isEmpty())
            throw Exception("Token '${tokens.current}' could not satisfy any options in choice.")
        else if (exceptions.size == 1)
            throw exceptions[0]
        else
            throw Exception("Multiple viable choice-paths failed:\n\n" + exceptions.joinToString("\n\n") { it.toString() })
    }


    // Public
    @Suppress("MemberVisibilityCanBePrivate")
    fun matchExact(string: String): ParseTree? {
        return match(string, startIndex = 0, allowPartialMatch = false)
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun find(string: String, startIndex: Int = 0): ParseTree? {
        return match(string, startIndex, allowPartialMatch = true)
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun findAll(string: String): Sequence<ParseTree> {
        return sequence {
            var matchVal: ParseTree? = match(string, startIndex = 0, allowPartialMatch = true)
            while (matchVal != null) {
                yield(matchVal)
                matchVal = match(string, matchVal.endPosition() + 1, allowPartialMatch = true)
            }
        }
    }
}


// Grammar
abstract class Grammar {
    // true  = "This element can and will accept the token/terminal"
    // null  = "This element cannot accept the token/terminal, but skipping the element is not an error"
    // false = "This element cannot accept the token/terminal, resulting in a parse error"
    fun expects(token: Token): Boolean? = expects(token.terminal.id)
    abstract fun expects(nextTerminalID: Int): Boolean?
    abstract fun treeWalk(): Sequence<Grammar>
    abstract fun children(): Sequence<Grammar>
}

class Sequential(val body: List<Grammar>) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean? {
        for (element in body) {
            val accepts = element.expects(nextTerminalID)
            if (accepts != null)
                return accepts
        }
        return null
    }

    override fun toString() = "(${body.joinToString(" ")})"
    override fun treeWalk(): Sequence<Grammar> = sequenceOf(this).plus(body.flatMap { it.treeWalk() })
    override fun children(): Sequence<Grammar> = body.asSequence()
}

class Optional(val body: Grammar) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean?
        = if (body.expects(nextTerminalID) == true) true else null

    override fun toString() = "[$body]"
    override fun treeWalk(): Sequence<Grammar> = sequenceOf(this).plus(body.treeWalk())
    override fun children(): Sequence<Grammar> = sequenceOf(body)
}

class Multiple(val body: Grammar) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean?
        = if (body.expects(nextTerminalID) == true) true else null

    override fun toString() = "{$body}"
    override fun treeWalk(): Sequence<Grammar>  = sequenceOf(this).plus(body.treeWalk())
    override fun children(): Sequence<Grammar> = sequenceOf(body)
}

class Choice(val options: List<Grammar>) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean? {
        var result: Boolean? = false
        for (option in options) {
            val accepts = option.expects(nextTerminalID)
            if (accepts == true)
                return true
            if (accepts == null)
                result = null
        }
        return result
    }

    override fun toString() = "(${options.joinToString(separator = " | ")})"
    override fun treeWalk(): Sequence<Grammar> = sequenceOf(this).plus(options.flatMap { it.treeWalk() })
    override fun children(): Sequence<Grammar> = options.asSequence()
}

class TerminalRef(val terminalId: Int, val terminalName: String? = null) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean
        = terminalId == nextTerminalID

    override fun toString() = "Term($terminalId)"
    override fun treeWalk(): Sequence<Grammar> = sequenceOf(this)
    override fun children(): Sequence<Grammar> = sequenceOf()
}

class NonTerminalRef(val nonTerminalName: String, val nonTerminals: Map<String, NonTerminal>) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean?
        = nonTerminals[nonTerminalName]!!.grammar.expects(nextTerminalID)

    override fun toString() = "NonT($nonTerminalName)"

    override fun treeWalk(): Sequence<Grammar> = sequenceOf(this)
    override fun children(): Sequence<Grammar> = sequenceOf()
}

class Blank : Grammar() {
    override fun expects(nextTerminalID: Int) = null

    override fun toString() = "ε"
    override fun treeWalk(): Sequence<Grammar> = sequenceOf(this)
    override fun children(): Sequence<Grammar> = sequenceOf()
}


abstract class Terminal(
    val id: Int
) {
    abstract fun matches(input: String): Boolean
}

class NamedTerminal(
    id: Int,
    val name: String,
    private val pattern: Regex
) : Terminal(id) {
    override fun matches(input: String): Boolean {
        return pattern.matches(input)
    }

    override fun toString(): String {
        return "($id, $name)"
    }
}

class AnonymousTerminal(
    id: Int,
    val value: String
) : Terminal(id) {
    override fun matches(input: String): Boolean {
        return input == value
    }

    override fun toString(): String {
        return "($id, $value)"
    }
}

class NonTerminal(
    val name: String,
    val grammar: Grammar
)


// Parsing
class Token(
    val line: Int,
    val colStart: Int,
    val colEnd: Int,
    val startIndex: Int,
    val value: String,
    val terminal: Terminal
) {
    override fun toString(): String {
        return "(line = $line, columns = ($colStart, $colEnd), " +
                "startIndex = $startIndex, value = $value, terminal = $terminal)"
    }
}


interface ParseTree {
    val grammar: Grammar

    fun isNotBlank(): Boolean
    fun isBlank() = !isNotBlank()

    /** Get the line number on which this element begins **/
    fun startPosition(): Int
    /** Get the inclusive end index **/
    fun endPosition(): Int

    /** Get the inclusive start and end indices in an IntRange object **/
    val range: IntRange
        get() = IntRange(startPosition(), endPosition())

    /** Get the length of the match based on the start and end positions **/
    val length: Int
        get() = endPosition() - startPosition() + 1

    fun preOrderWalk(): Sequence<ParseTree>
    fun postOrderWalk(): Sequence<ParseTree>
    fun tokens(): Sequence<Token>

    fun asNode(): Node = this as? Node ?: throw Exception("Parse-tree is not a node")
    fun asLeaf(): Leaf = this as? Leaf ?: throw Exception("Parse-tree is not a leaf")
    fun toStringNotNull(): String
}

class Node(override val grammar: Grammar, val children: List<ParseTree?>) : ParseTree {
    override fun isNotBlank() =  children.any { it?.isNotBlank() ?: false }
    override fun startPosition(): Int = children.first { it?.isNotBlank() ?: false }!!.startPosition()
    override fun endPosition(): Int = children.last { it?.isNotBlank() ?: false }!!.endPosition()

    /** Get the inclusive start and end indices based on child indices in an IntRange object **/
    fun range(firstChild: Int, lastChild: Int): IntRange
            = IntRange(children[firstChild]!!.startPosition(), children[lastChild]!!.endPosition())

    override fun preOrderWalk(): Sequence<ParseTree> = sequence{
        yield(this@Node)
        for (child in children.filterNotNull())
            yieldAll(child.preOrderWalk())
    }
    override fun postOrderWalk(): Sequence<ParseTree> = sequence{
        for (child in children.filterNotNull())
            yieldAll(child.postOrderWalk())
        yield(this@Node)
    }

    override fun tokens(): Sequence<Token>
        = children.filterNotNull().asSequence().flatMap { it.tokens() }

    override fun toString() = children.joinToString(" ")
    override fun toStringNotNull() = children.filter { it?.isNotBlank() ?: false }.joinToString(" ") { it!!.toStringNotNull() }
}

class Leaf(override val grammar: Grammar, val token: Token?) : ParseTree {
    init {
        assert((grammar is Blank) == (token == null)) { "A leaf can only have a null-token (${token == null}) if and only if the grammer is 'Blank' (${grammar is Blank})" }
    }

    override fun isNotBlank() = grammar !is Blank
    override fun startPosition(): Int
            = token?.startIndex ?: throw Exception("Cannot get start-position of 'Blank' leaf")
    override fun endPosition(): Int
            = (token?.startIndex ?: throw Exception("Cannot get end-position of 'Blank' leaf")) + token.value.length - 1

    override fun preOrderWalk(): Sequence<ParseTree> = sequenceOf(this@Leaf)
    override fun postOrderWalk(): Sequence<ParseTree> = sequenceOf(this@Leaf)
    override fun tokens(): Sequence<Token> = token?.let { sequenceOf(it) } ?: sequenceOf()

    override fun toString() = token?.value ?: ""
    override fun toStringNotNull() = token?.value ?: ""
}


/** Iterates all nodes in a ParseTree in pre-order walk, but also supports skipping the current subtree, where the
 * current node/leaf is the root of said subtree (see 'nextAfterCurrentSubtree()'). **/
class TreeIterator(val root: ParseTree) : Iterator<ParseTree> {
    private data class Index(val subTree: ParseTree, var childIndex: Int?) {
        fun tryMoveNextNonNullChild(): Boolean {
            if (subTree is Leaf)
                return false

            val node = subTree.asNode()
            do
                childIndex = (childIndex ?: -1) + 1
            while (childIndex!! < node.children.size && null == node.children[childIndex!!])

            return childIndex!! < node.children.size
        }

        fun getCurrentChildIndex() = Index(subTree.asNode().children[childIndex!!]!!, null)
    }
    private val currentKeeper = Stack<Index>()
    private var hasStated = false


    override fun hasNext(): Boolean
        = !hasStated || hasUnvisitedChildren() || hasUnvisitedSiblingOrCousin()

    override fun next(): ParseTree {
        if (currentKeeper.empty()) {
            if (hasStated)
                throw Exception("There are no more elements")
            hasStated = true
            currentKeeper.add(Index(root, null))
            return root
        }
        return nextNonNullElement()
    }


    fun hasNextAfterCurrentSubTree(): Boolean {
        if (!hasStated)
            throw Exception("The iterator must have started before calling this method. Call 'next()' first")
        return hasUnvisitedSiblingOrCousin()
    }

    fun nextAfterCurrentSubTree(): ParseTree {
        if (currentKeeper.empty()) throw Exception("The iterator either hit the end or has not yet started")

        return nextNonNullSubtree()
    }


    private fun nextNonNullElement(): ParseTree {
        if (current() is Leaf || !hasUnvisitedChildren())
            return nextNonNullSubtree()

        val currentIndex = currentKeeper.peek()
        currentIndex.tryMoveNextNonNullChild()
        currentKeeper.add(currentIndex.getCurrentChildIndex())

        return currentKeeper.peek().subTree
    }

    private fun nextNonNullSubtree(): ParseTree {
        currentKeeper.pop()
        while (currentKeeper.isNotEmpty() && !currentKeeper.peek().tryMoveNextNonNullChild())
            currentKeeper.pop()
        if (currentKeeper.empty())
            throw Exception("There is no next non-blank subtree")

        currentKeeper.add(currentKeeper.peek().getCurrentChildIndex())
        return currentKeeper.peek().subTree
    }


    private fun hasUnvisitedChildren(): Boolean
        = (currentKeeper.peek().subTree as? Node)?.children
            ?.drop((currentKeeper.peek().childIndex ?: -1) + 1)
            ?.filterNotNull()
            ?.any()
                ?: false

    private fun hasUnvisitedSiblingOrCousin(): Boolean {
        for (index in currentKeeper.reversed().drop(1)) {
            val numVisitedChildren = (index.childIndex ?: throw Exception("This should not be possible. Very hard to explain why. Good luck and sorry if you see this.")) + 1
            if (index.subTree.asNode().children.drop(numVisitedChildren).filterNotNull().any())
                return true
        }
        return false
    }


    fun current(): ParseTree {
        if (currentKeeper.empty())
            throw Exception("The iterator either hit the end or has not yet started")
        return currentKeeper.peek().subTree
    }
}
