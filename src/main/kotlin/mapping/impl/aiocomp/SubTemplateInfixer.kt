package mapping.impl.aiocomp

import offset
import tools.indexing.text.*
import tools.indexing.text.types.modifiers.ReferenceType
import tools.indexing.tree.TemplateDecl
import tools.parsing.GuardedConfre
import tools.parsing.GuardedParseTree
import tools.restructuring.TextRewriter
import uppaal.model.*

class SubTemplateInfixer {
    companion object {
        private const val STD_SEP = "_\$_"

        @JvmStatic
        fun infix(info: TaTemplateInfo, index: AioCompModelIndex): Template {
            val result = flatten(info, index, emptyList(), emptyList(), "", null)
            result.name.content = "_infixed_${info.trueName}"
            result.init = info.element.init?.clone()
            result.subtemplatereferences.clear()
            result.boundarypoints.clear()

            // Trim transitions/paths that start or end in a boundary point (and thus do not actually exist)
            val locationAndBranchPointIDs = (result.locations.map { it.id } + result.branchpoints.map { it.id }).toSet()
            result.transitions.removeAll { it.source.ref !in locationAndBranchPointIDs || it.target.ref !in locationAndBranchPointIDs }

            return result
        }

        @JvmStatic
        private fun flatten(currentInfo: TaTemplateInfo, index: AioCompModelIndex, declPrefix: List<String>, args: List<GuardedParseTree>, argsText: String, subTemplateUsage: SubTemplateUsage?): Template {
            val result = currentInfo.element.clone()
            renameDeclsAndParams(index, currentInfo, result, declPrefix, args, argsText, subTemplateUsage)

            for ((subTemUsage, trueArgNode) in currentInfo.subTemplateInstances.entries.zip(result.subtemplatereferences.map { it.arguments }))
            {
                // Reparse the potentially rewritten argument-node from "result.subtemplatereferences[x]" (which is a copy of the original in "currentInfo")
                val trueArgs = trueArgNode?.content?.let { index.exprConfre.findAll(it).toList() } ?: emptyList()
                merge(
                    result,
                    flatten(subTemUsage.value.referencedInfo!!, index, declPrefix + AioCompModelIndex.trueName(subTemUsage.key.name!!.content), trueArgs, trueArgNode?.content ?: "", subTemUsage.value),
                    getBoundaryInfo(currentInfo, subTemUsage.key),
                    declPrefix,
                    index,
                    subTemUsage.key.name!!.content
                )
            }

            // TODO: Later -> store back-mapping information in AioCompModelIndex
            return result
        }

        @JvmStatic
        private fun renameDeclsAndParams(
            index: AioCompModelIndex,
            currentInfo: TaTemplateInfo,
            result: Template,
            declPrefix: List<String>,
            args: List<GuardedParseTree>,
            argsText: String,
            subTemplateUsage: SubTemplateUsage?
        ) {
            val currentScope = index.model.find<TemplateDecl>(1) { it.element == currentInfo.element }
                ?: throw Exception("Cannot find scope for template '${currentInfo.element.name.content}'")

            val declRewriter = TextRewriter(currentInfo.element.declaration?.content ?: "")
            val paramRewriter = TextRewriter(currentInfo.element.parameter?.content ?: "")

            val (params, nonParams) = currentScope.subDeclarations.filterIsInstance<FieldDecl>().partition { it is ParameterDecl }
            val namesToReplace = HashMap<String, String>()
            val namesToQualify = HashSet<String>()
            if (subTemplateUsage != null)
                subTemplateUsage.namesToReplace = namesToReplace

            // Rename/qualify parameters depending on the type of template
            for (param in params.withIndex()) {
                if ((param.value as ParameterDecl).evalType.hasMod<ReferenceType>() && currentInfo.isSubTemplate) // Only if sub-template since uppaal natively handles references at the "top-level"
                    namesToReplace[param.value.identifier] = args[param.index].toStringNotNull()
                else {
                    namesToQualify.add(param.value.identifier)
                    if (currentInfo.isSubTemplate) { // Move param to declarations (cannot be ref-param if this is true)
                        val paramDeclText = currentInfo.element.parameter!!.content.substring(param.value.parseTree.fullRange)
                        val paramArgText = argsText.substring(args[param.index].fullRange)

                        val newDecl = "$paramDeclText = $paramArgText;\n"
                        val identRange = param.value.parseTree[2]!!.fullRange.offset(-param.value.parseTree.fullRange.first)

                        declRewriter.insert(0, newDecl.replaceRange(identRange, qualify(param.value.identifier, declPrefix)))
                    }
                    else { // Directly rename parameter
                        paramRewriter.replace(
                            param.value.parseTree[2]!!.fullRange,
                            qualify(param.value.identifier, declPrefix)
                        )
                    }
                }
            }
            if (currentInfo.isSubTemplate)
                result.parameter = null
            else if (params.isNotEmpty())
                result.parameter!!.content = paramRewriter.getRewrittenText()

            // TODO: Later -> Use use scopes and resolve logic to "protect" IDENT occurrences, i.e., IDENTs from AutoArr which are actually declarations, but are still just IDENTs in an expression
            // Rename declarations in "declaration node"
            for (decl in nonParams) {
                namesToQualify.add(decl.identifier)
                when (decl) {
                    is FunctionDecl -> {
                        declRewriter.replace(decl.parseTree.findAllTerminals("IDENT").first().fullRange, qualify(decl.identifier, declPrefix))
                        for (expr in decl.parseTree.findAllNonTerminals("Expression", onlyLocal = false, onlyVisible = false))
                            rewriteIdentNodes(declRewriter, expr, namesToQualify, namesToReplace, declPrefix)
                    }
                    is VariableDecl, is TypedefDecl -> {
                        declRewriter.replace(decl.parseTree.findAllTerminals("IDENT").first().fullRange, qualify(decl.identifier, declPrefix))
                        if (decl is VariableDecl)
                            rewriteIdentNodes(declRewriter, decl.parseTree[2]!!, namesToQualify, namesToReplace, declPrefix)
                    }
                    else -> continue // TODO: Later -> Support for extensibility so that PaCha can find/mark uses in sync-label? Should probably be done in an earlier parsing phase
                }
            }
            result.declaration = Declaration(declRewriter.getRewrittenText())

            // Rename usages of renamed declarations on locations
            for (label in result.locations.asSequence().flatMap { it.labels })
                rewriteLabel(label, index.exprConfre, namesToQualify, namesToReplace, declPrefix)

            // Rename usages of renamed declarations on edges
            for (edge in result.transitions) {
                val selectIdentifiers = edge.tryGetLabel(Label.KIND_SELECT)
                    ?.let { index.selectConfre.findAll(it.content) }
                    ?.map { it.findAllTerminals("IDENT").first().leaf.token!!.value }
                    ?.toSet()
                    ?: emptySet()

                for (label in edge.labels) {
                    val confre = when (label.kind) {
                        Label.KIND_GUARD, Label.KIND_UPDATE, Label.KIND_INVARIANT -> index.exprConfre
                        Label.KIND_SELECT -> index.selectConfre
                        else -> continue // TODO: Later -> Support for extensibility so that PaCha can find/mark uses in sync-label? Should probably be done in an earlier parsing phase
                    }

                    rewriteLabel(label, confre, namesToQualify - selectIdentifiers, namesToReplace, declPrefix)
                }
            }

            // Rewrite arguments given in sub-template-reference
            for (subTemRef in result.subtemplatereferences.filter { it.arguments != null })
                rewriteLabel(subTemRef.arguments!!, index.exprConfre, namesToQualify, namesToReplace, declPrefix)

            // Map location names (does not map "usages" since this only happens in the query phase).
            for (location in result.locations)
                location.name?.let { it.content = qualify(it.content, declPrefix) }
        }

        @JvmStatic
        private fun rewriteLabel(
            labelOrName: TextUppaalPojo,
            confre: GuardedConfre,
            namesToQualify: Set<String>,
            namesToReplace: HashMap<String, String>,
            declPrefix: List<String>
        ) {
            val rewriter = TextRewriter(labelOrName.content)
            for (tree in confre.findAll(labelOrName.content)) {
                rewriteIdentNodes(rewriter, tree, namesToQualify, namesToReplace, declPrefix)
            }
            labelOrName.content = rewriter.getRewrittenText()
        }

        private fun rewriteIdentNodes(
            rewriter: TextRewriter,
            tree: GuardedParseTree,
            namesToQualify: Set<String>,
            namesToReplace: HashMap<String, String>,
            declPrefix: List<String>
        ) {
            tree.findAllTerminals("IDENT", onlyLocal = false, onlyVisible = false)
                .forEach {
                    when (it.leaf.token!!.value) {
                        in namesToQualify -> rewriter.replace(it.fullRange, qualify(it.leaf.token!!.value, declPrefix))
                        in namesToReplace -> rewriter.replace(it.fullRange, namesToReplace[it.leaf.token!!.value]!!)
                    }
                }
        }

        @JvmStatic
        private fun merge(parent: Template, flatSub: Template, boundaryInfo: List<LinkedBoundary>, declPrefix: List<String>, index: AioCompModelIndex, childInstanceName: String) {
            // TODO: Later -> Adjust coordinates of locations, transitions, etc

            parent.declaration!!.content += "\n\n// '${qualify(childInstanceName, declPrefix, ".")}' (${AioCompModelIndex.trueName(flatSub.name.content)}) below\n" + flatSub.declaration!!.content

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
                    mergeLabels(partial, newTransition, index)

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
        private fun mergeLabels(partial: Partial, newTransition: Transition, index: AioCompModelIndex) {
            val selectLabels = extractLabels(partial, Label.KIND_SELECT)
            fixSelectNameClashes(partial, selectLabels, index)
            if (selectLabels.isNotEmpty()) {
                newTransition.labels.add(
                    Label(Label.KIND_SELECT, 0, 0, selectLabels.joinToString(", ") { it.content })
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
        private fun fixSelectNameClashes(partial: Partial, selectLabels: List<Label>, index: AioCompModelIndex) {
            if (selectLabels.size <= 1)
                return // Clashes are impossible

            val partitionedSelectTrees = selectLabels.map { index.selectConfre.findAll(it.content).toList() }
            val partitionedSelectIdentifiers = partitionedSelectTrees.map {
                it.map { tree -> tree.findAllTerminals("IDENT").first().leaf.token!!.value }
            }

            val allIdentifiers = partitionedSelectIdentifiers.flatten().distinct()
            for (ident in allIdentifiers) {
                if (partitionedSelectIdentifiers.count { selects -> ident in selects } > 1) {
                    renameSelectIdent(ident, ident + STD_SEP + "enter", partial.entering, index)
                    renameSelectIdent(ident, ident + STD_SEP + "inside", partial.inside, index)
                    renameSelectIdent(ident, ident + STD_SEP + "exit", partial.exiting, index)
                }
            }
        }

        @JvmStatic
        private fun renameSelectIdent(oldIdent: String, newIdent: String, edge: Transition?, index: AioCompModelIndex) {
            if (edge == null)
                return
            for (label in edge.labels) {
                val confre = when (label.kind) {
                    Label.KIND_GUARD, Label.KIND_UPDATE, Label.KIND_INVARIANT -> index.exprConfre
                    Label.KIND_SELECT -> index.selectConfre
                    else -> continue // TODO: Later -> Support for extensibility so that PaCha can find/mark uses in sync-label? Should probably be done in an earlier parsing phase
                }

                val labelRewriter = TextRewriter(label.content)
                for (tree in confre.findAll(label.content)) {
                    tree.findAllTerminals("IDENT", onlyLocal = false, onlyVisible = false)
                        .filter { it.leaf.token!!.value == oldIdent }.forEach {
                            labelRewriter.replace(it.fullRange, newIdent)
                        }
                }
                label.content = labelRewriter.getRewrittenText()
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
        fun qualify(baseName: String, prefixes: List<String>, sep: String = STD_SEP) =
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