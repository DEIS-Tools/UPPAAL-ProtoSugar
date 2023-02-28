package mapping.mapping

data class ProcessInfo(var name: String, var template: String)

abstract class SimulatorPhase {
    /** This function allows you to edit the processes shown in the UPPAAL simulator. **/
    abstract fun mapProcesses(processes: MutableList<ProcessInfo>)

    // TODO: Map step-command
    // TODO: Back-map step-command

    // TODO: (Back-)map list of available transitions
}