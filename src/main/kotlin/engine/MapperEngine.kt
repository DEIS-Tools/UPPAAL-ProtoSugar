package engine

import engine.mapping.Mapper
import engine.mapping.Phase
import engine.mapping.MapperError
import engine.mapping.PathNode
import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import uppaal_pojo.Nta
import uppaal_pojo.Template
import uppaal_pojo.Transition
import java.io.InputStream
import java.io.StringWriter

// Elements in the "XML-tree" are visited in post-order.
class MapperEngine(private val mappers: List<Mapper>) {
    private val serializer: Serializer = Persister()

    fun map(stream: InputStream): String = stream.bufferedReader().use { return map(it.readText()) }

    @Suppress("MemberVisibilityCanBePrivate")
    fun map(uppaalXml: String): String {
        val beforeNtaText = uppaalXml.substringBefore("<nta>")
        val ntaText = uppaalXml.substring(uppaalXml.indexOf("<nta>"))

        val nta = serializer.read(Nta::class.java, ntaText)
        val errors = runMappers(nta)
        // TODO: Output errors
        // TODO: Error severity

        StringWriter().use {
            serializer.write(nta, it)
            return beforeNtaText + it.buffer.toString()
        }
    }

    private fun runMappers(nta: Nta): List<MapperError> {
        // For each mapper -> for each mapper.phase -> phase
        val errors = ArrayList<MapperError>()
        for (mapper in mappers)
            for (phase in mapper.getPhases())
                errors.addAll(visitNta(nta, listOf(PathNode(nta)), phase)) // TODO Break after first error(s)? (or depending on error severity)
        return errors
    }

    private fun visitNta(nta: Nta, path: List<PathNode>, phase: Phase): List<MapperError> =
        phase.visit(path.plus(PathNode(nta.declaration)), nta.declaration)
            .plus(nta.templates.withIndex().flatMap
                { (index, template) -> visitTemplate(template, path.plus(PathNode(template, index+1)), phase) }
            )
            .plus(phase.visit(path.plus(PathNode(nta.system)), nta.system))
            .plus(phase.visit(path, nta))

    private fun visitTemplate(template: Template, path: List<PathNode>, phase: Phase): List<MapperError> =
        (template.parameter?.let { phase.visit(path.plus(PathNode(it)), it) } ?: listOf())
            .plus((template.declaration?.let { phase.visit(path.plus(PathNode(it)), it) } ?: listOf()))
            .plus(template.transitions?.let {
                    it.withIndex().flatMap { (index, transition) -> visitTransition(transition, path.plus(PathNode(transition, index+1)), phase) }
                } ?: listOf()
            )
            // TODO Visit states?
            .plus(phase.visit(path, template))

    private fun visitTransition(transition: Transition, path: List<PathNode>, phase: Phase): List<MapperError> =
        phase.visit(path, transition)
    // TODO Visit labels?
}