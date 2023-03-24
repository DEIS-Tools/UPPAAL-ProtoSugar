package mapping.impl.aiocomp

import uppaal.model.SubTemplateReference
import uppaal.model.Template
import uppaal.model.Transition

class SubTemplateInfixer {
    companion object {
        private const val SEP = "_\$_"

        @JvmStatic
        fun infix(info: TaTemplateInfo, index: AioCompModelIndex): Template {
            val result = flatten(info, index, emptyList(), 0).first
            result.name.content = "_infixed_${info.trueName}"
            result.init = info.element.init?.clone()
            result.parameter = info.element.parameter?.clone()
            result.declaration = info.element.declaration?.clone()
            result.subtemplatereferences.clear()
            result.boundarypoints.clear()
            return result
        }

        @JvmStatic
        private fun flatten(currentInfo: TaTemplateInfo, index: AioCompModelIndex, declPrefix: List<String>): Template {
            val flatSubs = currentInfo.usedSubTemplates.map {
                Pair(
                    flatten(it.value, index, declPrefix + it.key.name!!.content),
                    getBoundaryInfo(currentInfo, it.key)
                )
            }
            val result = currentInfo.element.clone()

            // TODO: Later -> map declaration and parameter names and usages thereof
            for (location in result.locations)
                location.name?.let { it.content = qualify(it.content, declPrefix) } // TODO: Is there a need to map location-name-usages???

            for ((flatSub, boundaryInfo) in flatSubs) {
                val instanceName = boundaryInfo.getOrNull(0)?.subTemplateReference?.name?.content ?: ""
                merge(result, flatSub, boundaryInfo, declPrefix + instanceName)
            }


            // TODO: Later -> store back-mapping information somewhere
            return result
        }

        @JvmStatic
        private fun merge(parent: Template, flatSub: Template, boundaryInfo: List<LinkedBoundary>, declPrefix: List<String>) {
            // TODO: Later -> Adjust coordinates of locations, transitions, etc

            // TODO: Later -> map/move/merge parameters/arguments
            // TODO: Later -> move declarations

            // TODO: Merge edges
            for (path in getPartialBoundaryPaths(parent, flatSub, boundaryInfo)) {
                flatSub.transitions.remove(path.inside)
                if (path.isEmpty)
                    continue
                parent.transitions.removeAll(path.enteringEdges)
                parent.transitions.removeAll(path.exitingEdges)

                for (partial in path.getAllCombinations()) {
                    val newTransition = Transition()

                    // TODO: Be able to detect and delete incomplete paths.

                    determineIdSourceAndTarget(partial, newTransition, partial.entering, partial.exiting, declPrefix)

                    // TODO: Merge labels

                    parent.transitions.add(newTransition)
                }
            }

            // Move rest of locations and edges + adjust IDs
            for (location in flatSub.locations) {
                parent.locations.add(location)
                location.id = qualify(location.id, declPrefix)
            }
            for (edge in flatSub.transitions) {
                parent.transitions.add(edge)
                edge.id = qualify(edge.id, declPrefix)
                edge.source.ref = qualify(edge.source.ref, declPrefix)
                edge.target.ref = qualify(edge.target.ref, declPrefix)
            }
        }

        private fun determineIdSourceAndTarget(
            partial: Partial,
            newTransition: Transition,
            entering: Transition?,
            exiting: Transition?,
            declPrefix: List<String>
        ) {
            if (partial.entering != null) {
                newTransition.id = entering.id
                newTransition.source = entering.source
                if (partial.exiting != null)
                    newTransition.target = exiting.target
                else {
                    newTransition.target = partial.inside.target
                    newTransition.target.ref = qualify(newTransition.target.ref, declPrefix)
                }
            } else {
                newTransition.id = partial.exiting!!.id
                newTransition.source = partial.inside.source
                newTransition.target = exiting.target
                newTransition.source.ref = qualify(newTransition.source.ref, declPrefix)
            }
        }

        @JvmStatic
        private fun qualify(baseName: String, prefixes: List<String>) =
            SEP + (prefixes + baseName).joinToString(SEP)

        @JvmStatic
        private fun getBoundaryInfo(currentInfo: TaTemplateInfo, subTemplateReference: SubTemplateReference): List<LinkedBoundary>
            = currentInfo.linkedBoundaryFromReferencerID.values.filter { it.subTemplateReference == subTemplateReference }

        @JvmStatic
        private fun getPartialBoundaryPaths(outside: Template, inside: Template, boundaryInfo: List<LinkedBoundary>): List<PartialPath>
        {
            val (entryLinks, exitLinks) = boundaryInfo.partition { it.isEntry }
            val partialPaths = inside.transitions.map { edge -> PartialPath(
                edge,
                entryLinks.filter { link -> link.referencedBoundaryPoint.id == edge.source.ref }.map { it.referencingBoundaryPoint.id }.toSet(),
                exitLinks.filter { link -> link.referencedBoundaryPoint.id == edge.target.ref }.map { it.referencingBoundaryPoint.id }.toSet()
            )}.filter { it.referencingEntryIDs.isNotEmpty() || it.referencingExitIDs.isNotEmpty() }

            for (outsideEdge in outside.transitions) {
                for (path in partialPaths.filter { outsideEdge.target.ref in it.referencingEntryIDs })
                    path.enteringEdges.add(outsideEdge)
                for (path in partialPaths.filter { outsideEdge.source.ref in it.referencingExitIDs })
                    path.exitingEdges.add(outsideEdge)
            }

            return partialPaths
        }


        private data class PartialPath(val inside: Transition, val referencingEntryIDs: Set<String>, val referencingExitIDs: Set<String>) {
            val enteringEdges = ArrayList<Transition>()
            val exitingEdges = ArrayList<Transition>()

            val hasEntries get() = enteringEdges.isNotEmpty()
            val hasExits get() = exitingEdges.isNotEmpty()
            val isEmpty get() = enteringEdges.isEmpty() && exitingEdges.isEmpty()

            fun getAllCombinations(): Sequence<Partial> = sequence {
                when {
                    hasEntries && hasExits ->
                        for (entering in enteringEdges)
                            for (exiting in exitingEdges)
                                yield(Partial(entering, inside, exiting))

                    hasEntries && !hasExits ->
                        for (entering in enteringEdges)
                            yield(Partial(entering, inside, null))

                    !hasEntries && hasExits ->
                        for (exiting in exitingEdges)
                            yield(Partial(null, inside, exiting))

                    else -> throw Exception("Invalid operation")
                }
            }
        }

        private data class Partial(val entering: Transition?, val inside: Transition, val exiting: Transition?)
    }
}