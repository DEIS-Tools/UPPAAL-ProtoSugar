package mapping.base

import kotlinx.serialization.Serializable
import tools.indexing.tree.Model
import tools.parsing.SyntaxRegistry
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalMessageException
import kotlin.reflect.KClass

@Serializable
data class Argument(var parameter: String, var value: String)
@Serializable
data class ProcessInfo(var name: String, var template: String, var arguments: List<Argument>)

abstract class SimulatorPhase : PhaseBase()
{
    final override fun configure(mapper: KClass<out Mapper>, registry: SyntaxRegistry, model: Model) {
        super.configure(mapper, registry, model)
        onConfigured()
    }


    /** This function allows you to edit the processes, variables, and clocks shown in the UPPAAL simulator. **/
    abstract fun mapInitialSystem(processes: MutableList<ProcessInfo>, variables: MutableList<String>, clocks: MutableList<String>) // TODO: Merge all these into one "System"-class or something


    // TODO: Map step-command
    // TODO: Back-map step-command

    // TODO: (Back-)map list of available transitions


    override fun report(message: UppaalMessage) =
        throw UppaalMessageException(message)

    // TODO: Back-map simulator errors
        // TODO: Standard implementations
}