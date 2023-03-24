package mapping.base

import kotlinx.serialization.Serializable

@Serializable
data class Argument(var parameter: String, var value: String)
@Serializable
data class ProcessInfo(var name: String, var template: String, var arguments: List<Argument>)

abstract class SimulatorPhase : PhaseBase()
{
    /** This function allows you to edit the processes, variables, and clocks shown in the UPPAAL simulator. **/
    abstract fun mapInitialSystem(processes: MutableList<ProcessInfo>, variables: MutableList<String>, clocks: MutableList<String>)

    // TODO: Map step-command
    // TODO: Back-map step-command

    // TODO: (Back-)map list of available transitions

    // TODO: Back-map simulator errors
        // TODO: Standard implementations
}