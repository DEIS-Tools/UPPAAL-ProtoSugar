package mapping.base

import tools.indexing.tree.Model
import tools.parsing.SyntaxRegistry
import uppaal.messaging.UppaalMessage
import kotlin.reflect.KClass


data class Phases(val modelPhases: List<ModelPhase>, val simulatorPhase: SimulatorPhase?, val queryPhase: QueryPhase?)

abstract class Mapper {
    protected open val registerSyntax: (RegistryBuilder.() -> Unit)? = null

    fun registerExtensions(syntaxRegistry: SyntaxRegistry) {
        registerSyntax?.invoke(RegistryBuilder(this, syntaxRegistry))
        // TODO: configure "declaration registry"?
    }

    fun buildAndConfigurePhases(registry: SyntaxRegistry, model: Model): Phases {
        return buildPhases().apply {
            modelPhases.forEach { it.configure(this@Mapper::class, registry, model) }
            simulatorPhase?.configure(this@Mapper::class, registry, model)
            queryPhase?.configure(this@Mapper::class, registry, model)
        }
    }

    protected abstract fun buildPhases(): Phases
}

abstract class PhaseBase {
    /** Do not touch! Here be dragons! **/
    var phaseIndex = -1

    private var configured = false
    private lateinit var mapper: KClass<out Mapper>
    private lateinit var registry: SyntaxRegistry
    protected lateinit var model: Model

    open fun configure(mapper: KClass<out Mapper>, registry: SyntaxRegistry, model: Model) {
        if (configured) throw Exception("Can only configure once")
        this.mapper = mapper
        this.registry = registry
        this.model = model
        configured = true
    }

    protected open fun onConfigured() { }

    protected abstract fun report(message: UppaalMessage)

    protected fun generateParser(rootNonTerminal: String) =
        registry.generateParser(rootNonTerminal, mapper)
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
