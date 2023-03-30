package uppaal.walkers

import uppaal.UppaalPath
import uppaal.model.*

/** Base class for pre-order-walking through all nodes in a UPPAAL-model-tree while supplying a UppaalPath object for
 * each visited element. **/
abstract class ModelWalkerBase
{
    protected open fun visitUppaalPojo(path: UppaalPath, uppaalPojo: UppaalPojo) { }
    protected open fun stepInto(path: UppaalPath, uppaalPojo: UppaalPojo) { }
    protected open fun stepOut(path: UppaalPath, uppaalPojo: UppaalPojo) { }


    fun doWalk(nta: Nta) {
        visitSingle(UppaalPath(), nta, this::visitNta)
    }


    private fun visitNta(path: UppaalPath, nta: Nta) {
        visitUppaalPojo(path, nta)

        visitSingle(path, nta.declaration)
        visitAll(path, nta.templates, this::visitTemplate)
        visitSingle(path, nta.system)
    }

    private fun visitTemplate(path: UppaalPath, template: Template) {
        visitUppaalPojo(path, template)

        visitSingle(path, template.name)
        visitNullableSingle(path, template.parameter)
        visitNullableSingle(path, template.declaration)
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

        visitNullableSingle(path, subTemplateReference.name)
        visitNullableSingle(path, subTemplateReference.subtemplatename)
        visitAll(path, subTemplateReference.boundarypoints)
    }


    private inline fun <T : UppaalPojo> visitAll(basePath: UppaalPath, elements: List<T>, function: (UppaalPath, T) -> Unit = this::visitUppaalPojo) {
        elements.withIndex().forEach {
            val newPath = basePath + it
            stepInto(newPath, it.value)
            function(newPath, it.value)
            stepOut(newPath, it.value)
        }
    }

    private inline fun <T : UppaalPojo> visitNullableSingle(basePath: UppaalPath, element: T?, function: (UppaalPath, T) -> Unit = this::visitUppaalPojo) {
        element?.let { visitSingle(basePath, element, function) }
    }

    private inline fun <T : UppaalPojo> visitSingle(basePath: UppaalPath, element: T, function: (UppaalPath, T) -> Unit = this::visitUppaalPojo) {
        val newPath = basePath.extend(element)
        stepInto(newPath, element)
        function(newPath, element)
        stepOut(newPath, element)
    }
}