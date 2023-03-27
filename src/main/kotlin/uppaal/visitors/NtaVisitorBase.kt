package uppaal.visitors

import uppaal.UppaalPath
import uppaal.model.*

/** Base class for visiting all nodes in a UPPAAL-model-tree while supplying a UppaalPath object for each visited element.
 * In order to produce this path, visitation can only start in the Nta/root-element. **/
abstract class NtaVisitorBase
{
    fun visit(nta: Nta) {
        val path = UppaalPath(nta)
        visitUppaalPojo(path, nta)
        visitUppaalPojo(path.extend(nta.declaration), nta.declaration)
        nta.templates.withIndex().forEach { visitTemplate(path + it, it.value) }
        visitUppaalPojo(path.extend(nta.system), nta.system)
    }

    protected open fun visitUppaalPojo(path: UppaalPath, uppaalPojo: UppaalPojo) { }


    private fun visitTemplate(path: UppaalPath, template: Template) {
        visitUppaalPojo(path, template)
        visitUppaalPojo(path.extend(template.name), template.name)
        visitIfNotNull(path, template.parameter)
        visitIfNotNull(path, template.declaration)
        visitAll(path, template.locations)
        visitAll(path, template.branchpoints)
        visitAll(path, template.boundarypoints)
        visitAll(path, template.subtemplatereferences, this::visitSubTemplateReference)
        visitAll(path, template.transitions, this::visitTransition)
    }

    private fun visitTransition(path: UppaalPath, transition: Transition) {
        visitUppaalPojo(path, transition)
        visitAll(path, transition.labels)
    }

    private fun visitSubTemplateReference(path: UppaalPath, subTemplateReference: SubTemplateReference) {
        visitUppaalPojo(path, subTemplateReference)
        visitIfNotNull(path, subTemplateReference.name)
        visitIfNotNull(path, subTemplateReference.subtemplatename)
        visitAll(path, subTemplateReference.boundarypoints)
    }


    private inline fun <T : UppaalPojo> visitAll(basePath: UppaalPath, elements: List<T>, function: (UppaalPath, T) -> Unit = this::visitUppaalPojo) {
        elements.withIndex().forEach { function(basePath + it, it.value) }
    }

    private inline fun <T : UppaalPojo> visitIfNotNull(basePath: UppaalPath, element: T?, function: (UppaalPath, T) -> Unit = this::visitUppaalPojo) {
        element?.let { function(basePath.extend(it), it) }
    }
}