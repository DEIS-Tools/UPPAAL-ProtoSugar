package mapping.base

import tools.parsing.SyntaxRegistry


data class PhaseOutput(val modelPhases: List<ModelPhase>, val simulatorPhase: SimulatorPhase?, val queryPhase: QueryPhase?)

abstract class Mapper {
    open val registerSyntax: (RegistryBuilder.() -> Unit)? = null
    protected lateinit var parserBuilder: ParserBuilder private set

    fun setRegistry(syntaxRegistry: SyntaxRegistry) {
        registerSyntax?.invoke(RegistryBuilder(this, syntaxRegistry))
        parserBuilder = ParserBuilder(this, syntaxRegistry)
    }

    abstract fun getPhases(): PhaseOutput
}

abstract class PhaseBase {
    /** Do not touch! Here be dragons! **/
    var phaseIndex = -1
}


class RegistryBuilder(private val mapper: Mapper, private val registry: SyntaxRegistry) {
    fun makeNonTerminal(name: String, grammar: String)
            = registry.makeNonTerminal(name, grammar, mapper.javaClass.kotlin)
    fun forkNonTerminal(baseNonTerminal: String, newName: String)
            = registry.forkNonTerminal(baseNonTerminal, newName, mapper.javaClass.kotlin)
    fun insertChoice(baseNonTerminal: String, choicePosition: List<Int>, nonTerminalToInsert: String)
            = registry.insertChoice(baseNonTerminal, choicePosition, nonTerminalToInsert, mapper.javaClass.kotlin)
    fun insertOptional(baseNonTerminal: String, insertPosition: List<Int>, nonTerminalToInsert: String)
            = registry.insertOptional(baseNonTerminal, insertPosition, nonTerminalToInsert, mapper.javaClass.kotlin)
    fun insertMultiple(baseNonTerminal: String, insertPosition: List<Int>, nonTerminalToInsert: String)
            = registry.insertMultiple(baseNonTerminal, insertPosition, nonTerminalToInsert, mapper.javaClass.kotlin)
}

class ParserBuilder(private val mapper: Mapper, private val registry: SyntaxRegistry) {
    fun generateParser(rootNonTerminal: String)
            = registry.generateParser(rootNonTerminal, mapper.javaClass.kotlin)
}
