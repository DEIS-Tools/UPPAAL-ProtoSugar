package mapping.mapping

data class PhaseOutput(val modelPhases: List<ModelPhase>, val simulatorPhase: SimulatorPhase?, val queryPhase: QueryPhase?)

interface Mapper {
    fun getPhases(): PhaseOutput

    // TODO: Functionality to add new syntax to a common parsing library so that all mappers can understand the syntax
    //  added by all other mappers.
}


