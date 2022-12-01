package mapping

import mapping.base.*
import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import uppaal_pojo.Nta
import uppaal_pojo.Template
import uppaal_pojo.Transition
import java.io.InputStream
import java.io.StringWriter
import java.lang.Exception

// Elements in the "XML-tree" are visited in pre-order.
class MapperEngine(private val mappers: List<Mapper>) {
    private val serializer: Serializer = Persister()

    private var modelPhases: List<ModelPhase>? = null
    private var simulatorPhases: List<SimulatorPhase>? = null
    private var queryPhases: List<QueryPhase>? = null


    fun mapModel(stream: InputStream): Pair<String, List<UppaalError>>
        = stream.bufferedReader().use { return mapModel(it.readText()) }
    fun mapModel(uppaalXml: String): Pair<String, List<UppaalError>> {
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
    private fun runModelMappers(nta: Nta): List<UppaalError> {
        val errors = ArrayList<UppaalError>()
        val newModelPhases = ArrayList<ModelPhase>()
        val newSimulatorPhases = ArrayList<SimulatorPhase>()
        val newQueryPhases = ArrayList<QueryPhase>()

        for (mapper in mappers.map { it.getPhases() }) {
            for (phase in mapper.first) {
                val newErrors = visitNta(nta, UppaalPath(nta), phase)
                errors.addAll(newErrors)
                if (newErrors.any { it.isUnrecoverable })
                    return errors
            }

            newModelPhases.addAll(mapper.first)
            if (mapper.second != null)
                newSimulatorPhases.add(mapper.second!!)
            if (mapper.third != null)
                newQueryPhases.add(mapper.third!!)
        }

        modelPhases = newModelPhases
        if (errors.size == 0) {
            simulatorPhases = newSimulatorPhases
            queryPhases = newQueryPhases
        }

        return errors
    }
    private fun visitNta(nta: Nta, path: UppaalPath, phase: ModelPhase): List<UppaalError> =
        phase.visit(path, nta).plus(phase.visit(path.plus(nta.declaration), nta.declaration))
            .plus(nta.templates.withIndex().flatMap
            { visitTemplate(it.value, path.plus(it), phase) }
            )
            .plus(phase.visit(path.plus(nta.system), nta.system))
    private fun visitTemplate(template: Template, path: UppaalPath, phase: ModelPhase): List<UppaalError> =
        phase.visit(path, template).asSequence()
            .plus(phase.visit(path.plus(template.name), template.name))
            .plus((template.parameter?.let { phase.visit(path.plus(it), it) } ?: listOf()))
            .plus(template.declaration?.let { phase.visit(path.plus(it), it) } ?: listOf())
            .plus(
                template.locations.flatMap { phase.visit(path.plus(it), it) }
            )
            .plus(
                template.branchpoint.flatMap { phase.visit(path.plus(it), it) }
            )
            .plus(
                template.transitions.withIndex().flatMap { visitTransition(it.value, path.plus(it), phase) }
            ).toList()
    private fun visitTransition(transition: Transition, path: UppaalPath, phase: ModelPhase): List<UppaalError> =
        phase.visit(path, transition)
            .plus(transition.labels.withIndex().flatMap { phase.visit(path.plus(it), it.value) })


    fun mapModelErrors(engineErrors: List<UppaalError>, mapperErrors: List<UppaalError>): List<UppaalError> {
        // Mapped in reverse order since the error is based on the last queryPhase's result
        val reversePhases = modelPhases?.reversed() ?: throw Exception("You must upload a model before you try to map errors")
        return reversePhases.fold(mapperErrors + engineErrors) { errors, mapper -> mapper.mapModelErrors(errors) }
    }
    fun mapProcesses(processes: List<ProcessInfo>) {
        // Mapped in reverse order since the error is based on the last modelPhase's result
        val reversePhases = simulatorPhases?.reversed() ?: throw Exception("You must upload a model before you try to map errors")
        reversePhases.forEach { it.mapProcesses(processes) }
    }


    fun mapQuery(query: String): Pair<String, UppaalError?> {
        var finalQuery = query
        for (phase in queryPhases ?: throw Exception("You must upload a model before you run a query"))
        {
            val result = phase.mapQuery(finalQuery)
            if (null != result.second)
                return Pair("", result.second)
            finalQuery = result.first
        }
        return Pair(finalQuery, null)
    }
    fun mapQueryError(error: UppaalError): UppaalError {
        // Mapped in reverse order since the error is based on the last queryPhase's result
        val reversePhases = queryPhases?.reversed() ?: throw Exception("You must upload a model before you run a query")
        return reversePhases.fold(error) { inner_error, phase -> phase.mapQueryError(inner_error) }
    }


    fun clearCache() {
        modelPhases = null
        simulatorPhases = null
        queryPhases = null
    }
}