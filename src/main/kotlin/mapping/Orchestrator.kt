package mapping

import mapping.mapping.*
import mapping.parsing.SyntaxRegistry
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalPath
import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import uppaal.messaging.UppaalMessageException
import uppaal.model.Nta
import uppaal.model.Template
import uppaal.model.Transition
import java.io.InputStream
import java.io.StringWriter
import java.lang.Exception
import kotlin.jvm.Throws

// Elements in the "XML-tree" are visited in pre-order.
class Orchestrator(private val mappers: List<Mapper>) {
    private val serializer: Serializer = Persister()
    private val syntaxRegistry = SyntaxRegistry()

    private var modelPhases: ArrayList<ModelPhase>? = null
    private var simulatorPhases: List<SimulatorPhase>? = null
    private var queryPhases: List<QueryPhase>? = null

    val numberOfMappers = mappers.size

    init {
        for (mapper in mappers)
            mapper.setRegistry(syntaxRegistry)
        syntaxRegistry.postValidation()
    }


    fun mapModel(stream: InputStream): Pair<String, List<UppaalMessage>>
        = stream.bufferedReader().use { return mapModel(it.readText()) }
    fun mapModel(uppaalXml: String): Pair<String, List<UppaalMessage>> {
        val beforeNtaText = uppaalXml.substringBefore("<nta>")
        val ntaText = uppaalXml.substring(uppaalXml.indexOf("<nta>"))

        val nta = serializer.read(Nta::class.java, ntaText)
        val errors = runModelMappers(nta)
        if (errors.any { it.isUnrecoverable })
            return Pair(uppaalXml, errors)

        StringWriter().use {
            serializer.write(nta, it)
            val newModel = beforeNtaText + it.buffer.toString()
            return Pair(newModel, errors)
        }
    }
    private fun runModelMappers(nta: Nta): List<UppaalMessage> {
        val errors = ArrayList<UppaalMessage>()

        modelPhases = ArrayList()
        val newSimulatorPhases = ArrayList<SimulatorPhase>()
        val newQueryPhases = ArrayList<QueryPhase>()

        for (mapperPhases in mappers.map { it.getPhases() }) {
            for (phase in mapperPhases.modelPhases) {
                phase.phaseIndex = modelPhases!!.size
                errors.addAll(visitNta(nta, UppaalPath(nta), phase).onEach { it.phaseIndex = phase.phaseIndex })
                if (errors.any { it.isUnrecoverable })
                    return errors
                modelPhases!!.add(phase)
            }

            mapperPhases.simulatorPhase?.let { it.phaseIndex = newSimulatorPhases.size; newSimulatorPhases.add(it) }
            mapperPhases.queryPhase?.let { it.phaseIndex = newQueryPhases.size; newQueryPhases.add(it) }
        }

        if (errors.size == 0) {
            simulatorPhases = newSimulatorPhases
            queryPhases = newQueryPhases
        }

        return errors
    }
    private fun visitNta(nta: Nta, path: UppaalPath, phase: ModelPhase): List<UppaalMessage> =
        phase.visit(path, nta).plus(phase.visit(path.plus(nta.declaration), nta.declaration))
            .plus(
                nta.templates.withIndex().flatMap { visitTemplate(it.value, path.plus(it), phase) }
            )
            .plus(phase.visit(path.plus(nta.system), nta.system))
    private fun visitTemplate(template: Template, path: UppaalPath, phase: ModelPhase): List<UppaalMessage> =
        phase.visit(path, template).asSequence()
            .plus(phase.visit(path.plus(template.name), template.name))
            .plus((template.parameter?.let { phase.visit(path.plus(it), it) } ?: listOf()))
            .plus(template.declaration?.let { phase.visit(path.plus(it), it) } ?: listOf())
            .plus(
                template.locations.withIndex().flatMap { phase.visit(path.plus(it), it.value) }
            )
            .plus(
                template.branchpoints.withIndex().flatMap { phase.visit(path.plus(it), it.value) }
            )
            .plus(
                template.boundarypoints.withIndex().flatMap { phase.visit(path.plus(it), it.value) }
            )
            .plus(
                template.subtemplatereferences.withIndex().flatMap { phase.visit(path.plus(it), it.value) } // TODO: Maybe visit boundary-points of reference?
            )
            .plus(
                template.transitions.withIndex().flatMap { visitTransition(it.value, path.plus(it), phase) }
            ).toList()
    private fun visitTransition(transition: Transition, path: UppaalPath, phase: ModelPhase): List<UppaalMessage> =
        phase.visit(path, transition)
            .plus(transition.labels.withIndex().flatMap { phase.visit(path.plus(it), it.value) })

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