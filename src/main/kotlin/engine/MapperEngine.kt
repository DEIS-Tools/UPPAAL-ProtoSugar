package engine

import engine.mapping.*
import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import uppaal_pojo.Nta
import uppaal_pojo.Template
import uppaal_pojo.Transition
import java.io.InputStream
import java.io.StringWriter
import java.lang.Error
import java.lang.Exception

// Elements in the "XML-tree" are visited in post-order.
class MapperEngine(private val mappers: List<Mapper>) {
    private val serializer: Serializer = Persister()

    private var queryPhases: List<QueryPhase>? = null

    fun mapModel(stream: InputStream): Pair<String, List<UppaalError>> = stream.bufferedReader().use { return mapModel(it.readText()) }
    @Suppress("MemberVisibilityCanBePrivate")
    fun mapModel(uppaalXml: String): Pair<String, List<UppaalError>> {
        val beforeNtaText = uppaalXml.substringBefore("<nta>")
        val ntaText = uppaalXml.substring(uppaalXml.indexOf("<nta>"))

        val nta = serializer.read(Nta::class.java, ntaText)
        val errors = runModelMappers(nta)
        if (errors.isNotEmpty())
            return Pair("", errors)

        StringWriter().use {
            serializer.write(nta, it)
            val newModel = beforeNtaText + it.buffer.toString();
            return Pair(newModel, listOf())
        }
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
        for (phase in queryPhases?.reversed() ?: throw Exception("You must upload a model before you run a query"))
            phase.mapQueryError(error)
        return error
    }


    private fun runModelMappers(nta: Nta): List<UppaalError> {
        val errors = ArrayList<UppaalError>()
        val newQueryPhases = ArrayList<QueryPhase>()

        for (mapper in mappers.map { it.getPhases() }) {
            for (phase in mapper.first) {
                val newErrors = visitNta(nta, listOf(PathNode(nta)), phase)
                errors.addAll(newErrors)
                if (newErrors.any { it.isUnrecoverable })
                    return errors
            }
            if (mapper.second != null)
                newQueryPhases.add(mapper.second!!)
        }
        if (errors.size == 0)
            queryPhases = newQueryPhases

        return errors
    }

    private fun visitNta(nta: Nta, path: List<PathNode>, phase: ModelPhase): List<UppaalError> =
        phase.visit(path.plus(PathNode(nta.declaration)), nta.declaration)
            .plus(nta.templates.withIndex().flatMap
                { (index, template) -> visitTemplate(template, path.plus(PathNode(template, index+1)), phase) }
            )
            .plus(phase.visit(path.plus(PathNode(nta.system)), nta.system))
            .plus(phase.visit(path, nta))

    private fun visitTemplate(template: Template, path: List<PathNode>, phase: ModelPhase): List<UppaalError> =
        (template.parameter?.let { phase.visit(path.plus(PathNode(it)), it) } ?: listOf())
            .plus((template.declaration?.let { phase.visit(path.plus(PathNode(it)), it) } ?: listOf()))
            .plus(template.transitions?.let {
                    it.withIndex().flatMap { (index, transition) -> visitTransition(transition, path.plus(PathNode(transition, index+1)), phase) }
                } ?: listOf()
            )
            // TODO Visit states?
            .plus(phase.visit(path, template))

    private fun visitTransition(transition: Transition, path: List<PathNode>, phase: ModelPhase): List<UppaalError> =
        phase.visit(path, transition)
    // TODO Visit labels?
}