package engine.parsing

import java.lang.Exception
import java.lang.Character.MIN_VALUE as nullChar

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
class Confre(grammar: String) {
    private val eof: NamedTerminal = NamedTerminal(-1, "EOF", Regex(nullChar.toString()))
    private val terminals: MutableList<Terminal> = ArrayList()
    private val nonTerminals: MutableMap<String , NonTerminal> = HashMap()
    private var rootNonTerminal: String

    private val stringPattern = Regex("""'((?>(?>\\[\\'])|[^'\s\\])*)'""")

    init {
        this.rootNonTerminal = "NON_TERMINAL"
        if (grammar.isNotBlank()) {
            populateTerminals(grammar)
            populateNonTerminals(grammar)

            // TODO: Validate that all references terminals / non-terminals exist
        }
    }

    private constructor() : this("") {
        terminals.add(NamedTerminal(0, "IDENTIFIER", Regex("""[_a-zA-Z][_a-zA-Z0-9]*""")))
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
                    TerminalRef(0), // IDENTIFIER |
                    TerminalRef(1), // STRING |
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

            nonTerminals[name] = NonTerminal(name, parseNonTerminal(parseTree))
        }
        rootNonTerminal = nonTerminalsToParse.first().groups[1]!!.value
    }

    private fun parseNonTerminal(parseTree: ParseTree): Grammar {
        if (parseTree !is Node || parseTree.children.size != 2 || parseTree.children[0] !is Node || parseTree.children[0]?.notBlank() != true)
            throw Exception("Cannot parse non-terminal body: '$parseTree'")

        return parseBody(parseTree.children[0] as Node)
    }

    private fun parseBody(node: Node): Grammar {
        val result: Grammar

        // If the "multiple" part of BODY is not blank
        if (node.children[0]?.notBlank() == true) {
            val sequence = ArrayList<Grammar>()
            val bodyParts = (node.children[0] as Node).children.iterator()
            while (bodyParts.hasNext()) { // BODY = Multiple(Choice(...))
                val choiceParts = (bodyParts.next() as Node).children.iterator()
                val token = (choiceParts.next() as Leaf).token!! // First element must always be a leaf
                val terminal = token.terminal
                when (terminal.id) {
                    /* Identifier */ 0 -> sequence.add(determineTerminalOrNonTerminal(token))
                    /* String */     1 -> sequence.add(TerminalRef(terminals.filterIsInstance<AnonymousTerminal>().find { it.value == token.value.trim('\'').replace("\\'", "'") }!!.id))
                    /* Group */      2 -> sequence.add(parseBody(choiceParts.next() as Node))
                    /* Optional */   4 -> sequence.add(Optional(parseBody(choiceParts.next() as Node)))
                    /* Multiple */   6 -> sequence.add(Multiple(parseBody(choiceParts.next() as Node)))
                    /* Other */      else -> throw Exception("") // TODO Finish here
                }
            }
            result = if (sequence.size > 1) Sequential(sequence) else sequence[0]
        }
        else
            result = Blank()

        // If the optional "choice" part is not blank
        if (node.children[1]?.notBlank() == true)
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
        return if (null != terminal)
            TerminalRef(terminal.id)
        else
            NonTerminalRef(token.value, nonTerminals)
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
                while (!char.isWhitespace() && terminals.none { it.matches(value + char) }) {
                    value += char
                    nextChar()
                }
                while (!char.isWhitespace() && terminals.any { it.matches(value + char) }) {
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
                val accepts = nonTerminals[rootNonTerminal]!!.grammar.expects(tokens.current()) ?: throw Exception("")
                if (accepts) {
                    val tree = tryMatch(tokens)
                    if (tree != null)
                        return tree
                }
                else tokens.next()
                if (tokens.current().terminal == eof)
                    return null
            }
        }
        else {
            val tree = tryMatch(tokens)
            return if (tree != null && tokens.current().terminal == eof) tree
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
        val accepts = terminal.expects(tokens.current())
        if (accepts != true)
            throw Exception("Tried to match token '${tokens.current()}' with terminal '${terminals[terminal.terminalId]}'")
        val leaf = Leaf(terminal, tokens.current())
        tokens.next()
        return leaf
    }

    private fun matchSequential(sequential: Sequential, tokens: BufferedIterator<Token>): ParseTree {
        val accepts = sequential.expects(tokens.current())
        if (accepts == false)
            throw Exception("") // TODO Finish it
        if (accepts == null)
            return Node(sequential, sequential.body.map { null })

        val children = ArrayList<ParseTree?>()
        for (subElement in sequential.body)
            children.add(dispatch(subElement, tokens))

        return Node(sequential, children)
    }

    private fun matchOptional(optional: Optional, tokens: BufferedIterator<Token>): ParseTree {
        if (optional.expects(tokens.current()) != true)
            return Node(optional, listOf<ParseTree?>(null))

        val subTree = dispatch(optional.body, tokens)
        val children = if (subTree.grammar is Sequential) (subTree as Node).children else listOf(subTree)

        return Node(optional, children)
    }

    private fun matchMultiple(multiple: Multiple, tokens: BufferedIterator<Token>): ParseTree {
        val children = ArrayList<ParseTree>()

        while (multiple.expects(tokens.current()) == true)
            children.add(dispatch(multiple.body, tokens))

        return Node(multiple, children)
    }

    private fun matchChoice(choice: Choice, tokens: BufferedIterator<Token>): ParseTree {
        val accepts = choice.expects(tokens.current())
        if (accepts == false)
            throw Exception("") // TODO Finish it

        val subTree = dispatch(choice.options.first { it.expects(tokens.current()) == accepts }, tokens)
        val children = if (subTree.grammar is Sequential && subTree is Node) subTree.children
                       else listOf(subTree)

        return Node(choice, children)
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
                matchVal = match(string, matchVal.endPosition(), allowPartialMatch = true)
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

    override fun toString(): String {
        return "Sequential(${body.joinToString(separator = " ")})"
    }
}

class Optional(val body: Grammar) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean?
        = if (body.expects(nextTerminalID) == true) true else null

    override fun toString(): String {
        return "Optional($body)"
    }
}

class Multiple(val body: Grammar) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean?
        = if (body.expects(nextTerminalID) == true) true else null

    override fun toString(): String {
        return "Multiple($body)"
    }
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

    override fun toString(): String {
        return "Choice(${options.joinToString(separator = " | ")})"
    }
}

class TerminalRef(val terminalId: Int) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean
        = terminalId == nextTerminalID

    override fun toString(): String {
        return "TerminalRef($terminalId)"
    }
}

class NonTerminalRef(val nonTerminalName: String, private val nonTerminals: Map<String, NonTerminal>) : Grammar() {
    override fun expects(nextTerminalID: Int): Boolean?
        = nonTerminals[nonTerminalName]!!.grammar.expects(nextTerminalID)

    override fun toString(): String {
        return "NonTerminalRef($nonTerminalName)"
    }
}

class Blank : Grammar() {
    override fun expects(nextTerminalID: Int) = null

    override fun toString(): String {
        return "Blank()"
    }
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

    fun notBlank(): Boolean
    /** Get the line number on which this element begins **/
    fun startPosition(): Int
    /** Get the inclusive end index **/
    fun endPosition(): Int
    /** Get the length of the match based on the start and end positions **/
    fun length(): Int = endPosition() - startPosition() + 1

    fun preOrderWalk(): Sequence<ParseTree>
    fun postOrderWalk(): Sequence<ParseTree>
}

class Node(override val grammar: Grammar, val children: List<ParseTree?>) : ParseTree {
    override fun notBlank() =  children.any { it?.notBlank() ?: false }
    override fun startPosition(): Int = children.first { it?.notBlank() ?: false }!!.startPosition()
    override fun endPosition(): Int = children.last { it?.notBlank() ?: false }!!.endPosition()

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

    override fun toString() = children.joinToString(" ")
}

class Leaf(override val grammar: Grammar, val token: Token?) : ParseTree {
    init {
        assert((grammar is Blank) == (token == null))
    }

    override fun notBlank() = grammar !is Blank
    override fun startPosition(): Int = token!!.startIndex
    override fun endPosition(): Int = token!!.startIndex + token.value.length - 1

    override fun preOrderWalk(): Sequence<ParseTree> = sequenceOf(this@Leaf)
    override fun postOrderWalk(): Sequence<ParseTree> = sequenceOf(this@Leaf)

    override fun toString() = token?.value ?: ""
}

class BufferedIterator<T>(private val iterator: Iterator<T>) {
    private var current: T? = null

    fun current(): T = current ?: throw Exception("Iteration has not yet started. Call 'next()' first")

    fun hasNext(): Boolean = iterator.hasNext()

    fun next(): T {
        current = iterator.next()
        return current!!
    }
}
