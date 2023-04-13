package mapping.base

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tools.indexing.tree.Model
import tools.parsing.SyntaxRegistry
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalMessageException
import kotlin.reflect.KClass

@Serializable
data class Argument(
    @SerialName("par")
    var parameter: String,
    @SerialName("v")
    var value: String
)
@Serializable
data class UppaalProcess(
    @SerialName("name")
    var name: String,
    @SerialName("templ")
    var template: String,
    @SerialName("args")
    var arguments: List<Argument>
)

data class UppaalSystem(
    val processes: MutableList<UppaalProcess>,
    val variables: MutableList<String>,
    val clocks: MutableList<String>
)

abstract class SimulatorPhase : PhaseBase()
{
    final override fun configure(mapper: KClass<out Mapper>, registry: SyntaxRegistry, model: Model) {
        super.configure(mapper, registry, model)
        onConfigured()
    }


    /** This function allows you to edit the processes, variables, and clocks shown in the UPPAAL simulator. **/
    abstract fun backMapInitialSystem(system: UppaalSystem)


    // TODO: Map step-command
    // TODO: Back-map step-command

    // TODO: (Back-)map list of available transitions


    override fun report(message: UppaalMessage) =
        throw UppaalMessageException(message)

    // TODO: Back-map simulator errors
        // TODO: Standard implementations
}