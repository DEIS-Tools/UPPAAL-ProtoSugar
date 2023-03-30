package mapping

import mapping.base.*
import tools.indexing.IndexingModelWalker
import tools.parsing.SyntaxRegistry
import uppaal.messaging.UppaalMessage
import uppaal.ModelSerializer
import uppaal.messaging.UppaalMessageException
import java.io.InputStream
import java.lang.Exception
import kotlin.jvm.Throws

// Elements in the "XML-tree" are visited in pre-order.
class Orchestrator(private val mappers: List<Mapper>) {
    private val syntaxRegistry = SyntaxRegistry()

    private var modelPhases: ArrayList<ModelPhase>? = null
    private var simulatorPhases: List<SimulatorPhase>? = null
    private var queryPhases: List<QueryPhase>? = null

    val numberOfMappers = mappers.size

    init {
        for (mapper in mappers)
            mapper.registerExtensions(syntaxRegistry)
        syntaxRegistry.postValidation()
        // TODO: Configure declaration parser handlers
    }


    fun mapModel(stream: InputStream): Pair<String, List<UppaalMessage>>
        = stream.bufferedReader().use { mapModel(it.readText()) }
    fun mapModel(uppaalXml: String): Pair<String, List<UppaalMessage>> {
        val nta = ModelSerializer.deserialize(uppaalXml)
        val errors = ArrayList<UppaalMessage>()
        val newSimulatorPhases = ArrayList<SimulatorPhase>()
        val newQueryPhases = ArrayList<QueryPhase>()

        val model = IndexingModelWalker(syntaxRegistry).buildIndex(nta)

        clearCache()
        modelPhases = ArrayList()
        for (mapperPhases in mappers.map { it.buildAndConfigurePhases(syntaxRegistry, model) }) {
            for (phase in mapperPhases.modelPhases) {
                phase.phaseIndex = modelPhases!!.size
                modelPhases!!.add(phase)

                errors.addAll(phase.run(nta).onEach { it.phaseIndex = phase.phaseIndex })
                if (errors.any { it.isUnrecoverable })
                    return Pair(uppaalXml, errors)
            }

            mapperPhases.simulatorPhase?.let { it.phaseIndex = newSimulatorPhases.size; newSimulatorPhases.add(it) }
            mapperPhases.queryPhase?.let { it.phaseIndex = newQueryPhases.size; newQueryPhases.add(it) }
        }

        if (errors.size == 0) {
            simulatorPhases = newSimulatorPhases
            queryPhases = newQueryPhases
        }

        return Pair(ModelSerializer.serialize(nta), errors)
    }

    /** Back-map errors from a model onto the original model text/structure. Phases are reversed since "the latest mapping must
     * be undone first". An error is only affected by a phase is "error.phaseIndex > phase.phaseIndex", thus the filter. **/
    fun backMapModelErrors(engineErrors: List<UppaalMessage>, mapperErrors: List<UppaalMessage>): List<UppaalMessage> {
        val reversePhases = modelPhases?.reversed() ?: throw Exception("You must upload a model before you try to map errors")
        return reversePhases.fold(mapperErrors + engineErrors) { errors, phase ->
            val (applicableErrors, delayedErrors) = errors.partition { it.phaseIndex > phase.phaseIndex }
            phase.backMapModelErrors(applicableErrors) + delayedErrors
        }.sortedBy { it.phaseIndex }
    }


    /** Back-map the "initial system state" returned from the engine to the "structure" of the original model. This is
     * necessary since processes/templates, variables, and clocks may have been rewritten. **/
    fun backMapInitialSystem(processes: MutableList<ProcessInfo>, variables: MutableList<String>, clocks: MutableList<String>)
        = (simulatorPhases?.reversed() ?: throw Exception("You must upload a model before you try to map errors"))
            .forEach { it.mapInitialSystem(processes, variables, clocks) }

    // TODO: New simulator mappings


    /** Rewrite a query based on registered query phases. **/
    @Throws(UppaalMessageException::class)
    fun mapQuery(query: String): String
        = (queryPhases ?: throw Exception("You must upload a model before you run a query"))
            .fold(query) { currentQuery, phase -> phase.mapQuery(currentQuery) }

    /** Back-map an error from a query onto the original query text. Phases are reversed since "the latest mapping must
     * be undone first". An error is only affected by a phase is "error.phaseIndex > phase.phaseIndex", thus the filter. **/
    fun backMapQueryError(error: UppaalMessage): UppaalMessage
        = (queryPhases?.reversed() ?: throw Exception("You must upload a model before you run a query"))
            .filter { phase -> error.phaseIndex > phase.phaseIndex }
            .fold(error) { foldedError, phase -> phase.backMapQueryError(foldedError) }


    fun clearCache() {
        modelPhases = null
        simulatorPhases = null
        queryPhases = null
    }
}