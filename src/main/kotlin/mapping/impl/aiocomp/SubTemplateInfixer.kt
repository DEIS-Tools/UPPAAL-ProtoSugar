package mapping.impl.aiocomp

import tools.indexing.text.*
import tools.indexing.tree.TemplateDecl
import tools.restructuring.TextRewriter
import uppaal.model.*

class SubTemplateInfixer {
    companion object {
        private const val STD_SEP = "_\$_"

        @JvmStatic
        fun infix(info: TaTemplateInfo, index: AioCompModelIndex): Template {
            val result = flatten(info, index, emptyList())
            result.name.content = "_infixed_${info.trueName}"
            result.init = info.element.init?.clone()
            result.parameter = info.element.parameter?.clone()
            result.subtemplatereferences.clear()
            result.boundarypoints.clear()

            // Trim transitions/paths that start or end in a boundary point (and thus do not actually exist)
            val locationAndBranchPointIDs = (result.locations.map { it.id } + result.branchpoints.map { it.id }).toSet()
            result.transitions.removeAll { it.source.ref !in locationAndBranchPointIDs || it.target.ref !in locationAndBranchPointIDs }

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

            renameDeclsAndParams(index, currentInfo, result, declPrefix)

            for ((flatSub, boundaryInfo) in flatSubs) {
                val instanceName = boundaryInfo.getOrNull(0)?.subTemplateReference?.name?.content ?: ""
                merge(result, flatSub, boundaryInfo, declPrefix)
            }

            // TODO: Later -> store back-mapping information in AioCompModelIndex
            return result
        }

        @JvmStatic
        private fun renameDeclsAndParams(
            index: AioCompModelIndex,
            currentInfo: TaTemplateInfo,
            result: Template,
            declPrefix: List<String>
        ) {
            // TODO: Later -> map parameter names and usages

            val currentScope = index.model.find<TemplateDecl> { it.element == currentInfo.element } ?: throw Exception("Should have scope, but no?") // TODO: Proper message
            val declRewriter = TextRewriter(currentInfo.element.declaration?.content ?: "")
            val namesToRewrite = HashSet<String>()

            // TODO: In order for scalar typedefs to remain "the same" between all instances of a sub-template, move the typedefs (w/ scalars) to the scope just above (global without nesting) with some unique name.
            // TODO: Use use scopes and resolve logic to "protect" IDENT occurrences, i.e., IDENTs from AutoArr which are actually declarations, but are still just IDENTs in an expression
            for (decl in currentScope.subDeclarations.filterIsInstance<FieldDecl>().filter { it !is ParameterDecl }) {
                namesToRewrite.add(decl.identifier)
                when (decl) {
                    is FunctionDecl -> throw Exception("Not supported yet")
                    is VariableDecl, is TypedefDecl -> {
                        declRewriter.replace(decl.parseTree.findAllTerminals("IDENT").first().fullRange, qualify(decl.identifier, declPrefix))
                        if (decl is VariableDecl)
                            decl.parseTree[2]!!.findAllTerminals("IDENT", onlyLocal = false, onlyVisible = false).filter { it.leaf.token!!.value in namesToRewrite }.forEach {
                                declRewriter.replace(it.fullRange, qualify(it.leaf.token!!.value, declPrefix))
                            }
                    }
                }
            }
            for (label in result.locations.asSequence().flatMap { it.labels } + result.transitions.asSequence().flatMap { it.labels }) // TODO: Rewrite arguments given in sub-template-reference
            {
                // TODO: Transition scopes to protect against wrongful rewrite (goes for select label decls and PaCha (meta) decls)
                val labelRewriter = TextRewriter(label.content)
                val confre = when (label.kind) {
                    Label.KIND_GUARD, Label.KIND_UPDATE, Label.KIND_INVARIANT -> index.exprConfre
                    else -> continue // TODO: Support for select.
                                     // TODO: Support for extensibility so that PaCha can find uses in sync-label
                }

                for (tree in confre.findAll(label.content)) {
                    tree.findAllTerminals("IDENT", onlyLocal = false, onlyVisible = false).filter { it.leaf.token!!.value in namesToRewrite }.forEach {
                        labelRewriter.replace(it.fullRange, qualify(it.leaf.token!!.value, declPrefix))
                    }
                }

                label.content = labelRewriter.getRewrittenText()
            }

            result.declaration = Declaration(declRewriter.getRewrittenText())

            // TODO: Rewrite usages outside "declaration node" (edges, etc.)
            //  HOWEVER! If the scope shadows the name, such as in an edge with a select statement, do NOT overwrite the name

            for (location in result.locations) // TODO: Is there a need to map location-name-usages???
                location.name?.let { it.content = qualify(it.content, declPrefix) }
        }

        @JvmStatic
        private fun merge(parent: Template, flatSub: Template, boundaryInfo: List<LinkedBoundary>, declPrefix: List<String>) {
            // TODO: Later -> Adjust coordinates of locations, transitions, etc
            // TODO: Later -> map/move/merge parameters/arguments

            parent.declaration!!.content += "\n\n// '${qualify(flatSub.name.content, declPrefix, ".")}' (${AioCompModelIndex.trueName(flatSub.name.content)}) below\n" + flatSub.declaration!!.content

            // Merge edges between boundary points
            for (path in getPartialBoundaryPaths(parent, flatSub, boundaryInfo)) {
                flatSub.transitions.remove(path.inside)
                if (path.isEmpty)
                    continue
                parent.transitions.removeAll(path.enteringEdges)
                parent.transitions.removeAll(path.exitingEdges)

                for (partial in path.getAllCombinations()) {
                    val newTransition = Transition()

                    determineIdOfSourceAndTarget(partial, newTransition, declPrefix)
                    mergeLabels(partial, newTransition)

                    parent.transitions.add(newTransition)
                }
            }

            // Move rest of locations and edges + adjust IDs
            for (location in flatSub.locations) {
                parent.locations.add(location)
                location.id = qualify(location.id, declPrefix, ".")
            }
            for (edge in flatSub.transitions) {
                parent.transitions.add(edge)
                edge.id = qualify(edge.id, declPrefix, ".")
                edge.source.ref = qualify(edge.source.ref, declPrefix, ".")
                edge.target.ref = qualify(edge.target.ref, declPrefix, ".")
            }
        }

        @JvmStatic
        private fun determineIdOfSourceAndTarget(
            partial: Partial,
            newTransition: Transition,
            declPrefix: List<String>
        ) {
            if (partial.entering != null) {
                newTransition.id = partial.entering.id
                newTransition.source = partial.entering.source.clone()
                if (partial.exiting != null)
                    newTransition.target = partial.exiting.target.clone()
                else {
                    newTransition.target = partial.inside.target.clone()
                    newTransition.target.ref = qualify(newTransition.target.ref, declPrefix, ".") // Only qualify inside sub-template
                }
            } else {
                newTransition.id = partial.exiting!!.id
                newTransition.source = partial.inside.source.clone()
                newTransition.target = partial.exiting.target.clone()
                newTransition.source.ref = qualify(newTransition.source.ref, declPrefix, ".") // Only qualify inside sub-template
            }
        }

        @JvmStatic
        private fun mergeLabels(partial: Partial, newTransition: Transition) {
            val selectLabels = extractLabels(partial, Label.KIND_SELECT)
            if (selectLabels.isNotEmpty()) {
                // TODO: Automatically fix name-clashes

                newTransition.labels.add(
                    Label(Label.KIND_UPDATE, 0, 0, selectLabels.joinToString("\n") { it.content })
                )
            }

            val guardLabels = extractLabels(partial, Label.KIND_GUARD)
            if (guardLabels.isNotEmpty()){
                newTransition.labels.add(
                    Label(Label.KIND_GUARD, 0, 0, guardLabels.joinToString(" && ") { "(${it.content})" })
                )
            }

            extractLabels(partial, Label.KIND_SYNC).singleOrNull()?.let {
                newTransition.labels.add(it.clone())
            }

            val updateLabels = extractLabels(partial, Label.KIND_UPDATE)
            if (updateLabels.isNotEmpty()) {
                newTransition.labels.add(
                    Label(Label.KIND_UPDATE, 0, 0, updateLabels.joinToString(", ") { it.content })
                )
            }
        }

        @JvmStatic
        private fun extractLabels(partial: Partial, labelKind: String): List<Label> {
            return listOfNotNull(
                partial.entering?.labels?.find { it.kind == labelKind },
                partial.inside.labels.find { it.kind == labelKind },
                partial.exiting?.labels?.find { it.kind == labelKind }
            ).filter { it.content.isNotBlank() }
        }


        @JvmStatic
        private fun qualify(baseName: String, prefixes: List<String>, sep: String = STD_SEP) =
            sep + (prefixes + baseName).joinToString(sep)

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
                }
            }
        }

        private data class Partial(val entering: Transition?, val inside: Transition, val exiting: Transition?)
    }
}