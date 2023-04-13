package mapping.impl.aiocomp

import mapping.base.*
import tools.indexing.text.EvaluableDecl
import tools.indexing.text.ParameterDecl
import tools.indexing.text.types.modifiers.ConstType
import tools.indexing.text.types.modifiers.ReferenceType
import tools.indexing.tree.TemplateDecl
import tools.parsing.Confre
import tools.parsing.GuardedParseTree
import tools.parsing.ParseTree
import tools.restructuring.ActivationRule
import tools.restructuring.TextRewriter
import uppaal.UppaalPath
import uppaal.messaging.UppaalMessage
import uppaal.messaging.createUppaalError
import uppaal.model.*

class AioCompMapper : Mapper() {
    override fun buildPhases(): Phases {
        val modelIndex = AioCompModelIndex()
        val systemIndex = AioCompSystemIndex(modelIndex)

        return Phases(
            listOf(ModelIndexing(modelIndex), ReferenceValidation(modelIndex), InfixMapping(modelIndex)),
            SimulatorMapping(modelIndex, systemIndex),
            QueryMapping(modelIndex, systemIndex)
        )
    }


    private inner class ModelIndexing(private val index: AioCompModelIndex) : ModelPhase() {
        val argListConfre by lazy { generateParser("ArgList") }

        override fun onConfigured() {
            index.model = model
            index.exprConfre = generateParser("Expression")
            index.selectConfre = generateParser("Select")

            register(::indexTemplate)
        }


        private fun indexTemplate(path: UppaalPath, template: Template) {
            val info = index.register(template, path)
            val errors = ArrayList<UppaalMessage>()

            if (info.isRedefinition)
                errors.add(redefinitionError(path, info))
            if (info.subTemOrUser && info.trueName.isBlank())
                errors.add(blankSubTemplateUserError(path))

            if (info.isSubTemplate) {
                if (template.init != null)
                    errors.add(initError(path))

                // Check that direction of edges follow direction of boundary points
                val bndInfo = info.boundaryInfo!!
                for (elem in template.transitions.withIndex()) {
                    val bndEdgeInfo = BoundaryEdgeInfo()

                    if (elem.value.source.ref in bndInfo.validExitIDs)
                        errors.add(leavingLocalExitError(path + elem))
                    else if (elem.value.source.ref in bndInfo.validEntryIDs)
                        bndEdgeInfo.sourceType = EndType.LOCAL_BOUNDARY

                    if (elem.value.target.ref in bndInfo.validEntryIDs)
                        errors.add(enteringLocalEntryError(path + elem))
                    else if (elem.value.target.ref in bndInfo.validExitIDs)
                        bndEdgeInfo.targetType = EndType.LOCAL_BOUNDARY

                    if (bndEdgeInfo.isOnBoundary)
                        info.boundaryEdges[elem.value] = bndEdgeInfo
                }

                if (bndInfo.entries.isEmpty())
                    errors.add(needEntryPointError(path))

                for (blank in bndInfo.blanks)
                    errors.add(blankBoundaryPointNameError(path + blank))

                for (duplicateElem in bndInfo.duplicatesByName.values)
                    for (duplicate in duplicateElem.drop(1))
                        errors.add(duplicateBoundaryPointError(path, template, duplicate))
            }
            else if (template.boundarypoints.isNotEmpty())
                errors.add(boundaryPointExistenceError(path))

            for (refElem in template.subtemplatereferences.withIndex()) {
                val ref = refElem.value
                info.subTemplateInstances[ref] = SubTemplateUsage()

                if (ref.name?.content.isNullOrBlank())
                    errors.add(blankSubTemplateReferenceNameError(path + refElem)) // TODO: Allow blank sub-template reference names

                if (ref.subtemplatename?.content.isNullOrBlank())
                    errors.add(blankSubTemplateNameInSubTemplateReferenceError(path + refElem))

                // TODO: Warning -> No entry-boundarypoint on sub-template reference

                val argsTree = argListConfre.matchExact(ref.arguments?.content ?: continue)
                if (argsTree == null) {
                    errors.add(argumentsSyntaxError(path, refElem))
                    continue
                }
                val expressions = extractExpressions(argsTree)
                if (expressions == null) {
                    errors.add(blankArgumentError(path, refElem))
                    continue
                }

                info.subTemplateInstances[ref]!!.arguments = expressions
            }

            info.hasPassedIndexing = errors.isEmpty()
            reportAll(errors)
        }

        private fun extractExpressions(argsTree: GuardedParseTree): List<GuardedParseTree>? {
            val expressions = ArrayList<GuardedParseTree>()
            expressions.add(argsTree[0]!!.findFirstNonTerminal("Expression") ?: return null)
            for (child in argsTree[1]!!.children)
                expressions.add(child!![1]!!.findFirstNonTerminal("Expression") ?: return null)
            return expressions
        }


        private fun blankSubTemplateUserError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "The name of a sub-template(-user) cannot be blank", isUnrecoverable = true)

        private fun blankBoundaryPointNameError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "The name of a boundary point cannot be blank", isUnrecoverable = true)

        private fun blankSubTemplateReferenceNameError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "The name of a sub-template reference cannot be blank (yet; feature pending)", isUnrecoverable = true)

        private fun blankSubTemplateNameInSubTemplateReferenceError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "The 'sub-template name' in a sub-template reference cannot be blank", isUnrecoverable = true)

        private fun redefinitionError(path: UppaalPath, info: TaTemplateInfo): UppaalMessage =
            createUppaalError(path, "Redeclaration of a (sub-)template name: '${info.element.name.content}'", isUnrecoverable = true)

        private fun initError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "A sub-template cannot have an init-location", isUnrecoverable = true)

        private fun boundaryPointExistenceError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "A normal template cannot have boundary points", isUnrecoverable = true)

        private fun leavingLocalExitError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "An edge cannot leave an exit boundary location that is local (not on a sub-template reference)", isUnrecoverable = true)

        private fun enteringLocalEntryError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "An edge cannot enter an entry boundary location that is local (not on a sub-template reference)", isUnrecoverable = true)

        private fun needEntryPointError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "A sub-template must have at least one entry point", isUnrecoverable = true)

        private fun duplicateBoundaryPointError(templatePath: UppaalPath, template: Template, duplicate: BoundaryPoint): UppaalMessage {
            val index = template.boundarypoints.indexOf(duplicate)
            return createUppaalError(templatePath.extend(duplicate, index), "Redeclaration of local boundary point '${duplicate.name!!.content}'", isUnrecoverable = true)
        }

        private fun argumentsSyntaxError(templatePath: UppaalPath, subTemplateReference: IndexedValue<SubTemplateReference>): UppaalMessage {
            val fullPath = templatePath.plus(subTemplateReference).extend(subTemplateReference.value.arguments!!)
            val content = subTemplateReference.value.arguments!!.content
            return createUppaalError(fullPath, content, content.indices, "Syntax error in argument list of sub-template reference (best it can say for now...)", isUnrecoverable = true)
        }

        private fun blankArgumentError(templatePath: UppaalPath, subTemplateReference: IndexedValue<SubTemplateReference>): UppaalMessage {
            val fullPath = templatePath.plus(subTemplateReference).extend(subTemplateReference.value.arguments!!)
            val content = subTemplateReference.value.arguments!!.content
            return createUppaalError(fullPath, content, content.indices, "One or more blank arguments in sub-template reference", isUnrecoverable = true)
        }
    }


    private inner class ReferenceValidation(private val index: AioCompModelIndex) : ModelPhase() {
        private val partialInstConfre by lazy { generateParser("PartialInst") }
        private val systemLineConfre by lazy { generateParser("SystemLine") }


        override fun onConfigured() {
            register(::validateTemplate)
            register(::validateBoundaryPaths)
            register(::validateInstantiations)
        }


        private fun validateTemplate(path: UppaalPath, template: Template) {
            val errors = ArrayList<UppaalMessage>()
            val info = index[template] ?: return

            val referencedBndPointIDsToDirs = HashMap<String, String>()

            // Check that sub-template references and boundary point "references" are valid
            val parentScope = index.model.find<TemplateDecl>(1) { it.element == template }!!
            for (subTemRefElement in info.element.subtemplatereferences.withIndex()) {
                val subTemRef = subTemRefElement.value
                if (subTemRef.subtemplatename == null) {
                    errors.add(noReferenceError(path + subTemRefElement, subTemRefIsProblem = true))
                    continue
                }

                // Check that reference references a sub-template
                val refInfo = index[subTemRef.subtemplatename!!.content]
                if (refInfo == null || !refInfo.isSubTemplate) {
                    errors.add(invalidReferenceError((path + subTemRefElement).extend(subTemRef.subtemplatename!!), subTemRef.subtemplatename!!.content, null))
                    continue
                }
                val subTemUsage = info.subTemplateInstances[subTemRef]!!
                subTemUsage.referencedInfo = refInfo

                // Check parameter and argument correctness (only check const- vs. ref-ness and nothing deeper)
                val refScope = index.model.find<TemplateDecl>(1) { it.element == refInfo.element }!!
                val params = refScope.findAll<ParameterDecl>(1).toList()
                val args = subTemUsage.arguments
                if (args.size != params.size)
                    errors.add(unexpectedArgumentCountError((path + subTemRefElement), subTemRef.arguments, args.size, params.size))
                else
                    for ((param, arg) in params.zip(args))
                        if (param.evalType.hasMod<ReferenceType>()) {
                            val argIdent = arg.toStringNotNull()
                            if (!Confre.identifierPattern.matches(argIdent)) // TODO: Allow array(-subscript) expressions to be given as a ref parameter
                                errors.add(refParamMustHaveReferencableArgumentError((path + subTemRefElement).extend(subTemRef.arguments!!), subTemRef.arguments!!.content, arg))
                            else if (parentScope.find<EvaluableDecl>(1) { it.identifier == argIdent }?.evalType?.hasMod<ConstType>() == true)
                                errors.add(constArgumentToRefParameterError((path + subTemRefElement).extend(subTemRef.arguments!!), subTemRef.arguments!!.content, arg))
                        }


                // Check if boundary point references are valid
                for (bndElement in subTemRef.boundarypoints.withIndex()) {
                    val bndPoint = bndElement.value
                    val bndName = bndPoint.name?.content
                    if (bndName == null) {
                        errors.add(noReferenceError(path + subTemRefElement + bndElement, subTemRefIsProblem = false))
                        continue
                    }

                    val bndDir = refInfo.boundaryInfo!!.getSafeDirection(bndName)
                    if (bndDir != null) {
                        if (bndDir != bndPoint.kind)
                            errors.add(conflictingBoundaryDirectionError(path + subTemRefElement + bndElement))
                        else {
                            referencedBndPointIDsToDirs[bndPoint.id] = bndDir
                            LinkedBoundary(
                                bndElement.value, refInfo.boundaryInfo.nameToBoundaryPoint[bndName]!!,
                                subTemRefElement.value,
                                info, refInfo
                            )
                        }
                    }
                    else
                        errors.add(invalidReferenceError(path + subTemRefElement + bndElement, bndName, refInfo.trueName))
                }
            }

            // Check that edges correctly enter/leave a reference
            if (referencedBndPointIDsToDirs.isNotEmpty())
                for (edge in template.transitions.withIndex()) {
                    val bndEdgeInfo = info.boundaryEdges[edge.value] ?: BoundaryEdgeInfo()

                    val sourceDir = referencedBndPointIDsToDirs[edge.value.source.ref]
                    if (sourceDir != null) {
                        if (sourceDir == BoundaryPoint.ENTRY)
                            errors.add(leavingReferencedEntryError(path + edge))
                        else
                            bndEdgeInfo.sourceType = EndType.REFERENCED_BOUNDARY
                    }

                    val targetDir = referencedBndPointIDsToDirs[edge.value.target.ref]
                    if (targetDir != null) {
                        if (targetDir == BoundaryPoint.EXIT)
                            errors.add(enteringReferencedExitError(path + edge))
                        else
                            bndEdgeInfo.targetType = EndType.REFERENCED_BOUNDARY
                    }

                    if (bndEdgeInfo.isOnBoundary)
                        info.boundaryEdges[edge.value] = bndEdgeInfo
                }

            info.hasPassedReferenceCheck = errors.isEmpty()
            reportAll(errors)
        }

        private fun validateBoundaryPaths(unused1: UppaalPath, unused2: System) {
            // No cyclic referencing of sub-templates
            val referenceCycles = index.getCircularReferences()
            for (refCycle in referenceCycles)
                report(cyclicReferencesError(refCycle.value, refCycle.key))

            // No circular or incomplete boundary paths
            for (bndPath in index.getBoundaryPaths()) {
                when (bndPath.type) {
                    PathType.INCOMPLETE -> report(incompleteBoundaryPathError(bndPath.path))
                    PathType.CYCLIC -> report(cyclicBoundaryPathError(bndPath.path))
                    PathType.COMPLETE -> {
                        val syncEdges = bndPath.path.mapNotNull { it.edge.tryGetLabel(Label.KIND_SYNC) }
                        if (syncEdges.size > 1)
                            report(multipleSyncOnBoundaryPathError(bndPath.path, syncEdges))
                    }
                }
                if (bndPath.type != PathType.COMPLETE)
                    bndPath.path.forEach { it.templateInfo.hasPassedCycleCheck = false }
            }
        }

        /** Ensure user-code does not instantiate sub-templates **/
        private fun validateInstantiations(path: UppaalPath, system: System) {
            for (partialInst in partialInstConfre.findAll(system.content)) {
                if (partialInst[3]!!.toString() in index.subTemplates)
                    report(userCodeInstantiatesSubTemplateError(path, system.content, partialInst[3]!!.tree))

                if (AioCompModelIndex.isSubTemplate(partialInst[0]!!.toString()))
                    report(partialInstantiationIsSubTemplateError(path, system.content, partialInst[0]!!.tree))
            }

            for (fullInst in systemLineConfre.findAll(system.content)) {
                val identNodes = fullInst[2]!!.children.map { it!![1]!![0] } + fullInst[1]!![0]
                for (identNode in identNodes.filterNotNull())
                    if (AioCompModelIndex.trueName(identNode.toString()) in index.subTemplates)
                        report(userCodeInstantiatesSubTemplateError(path, system.content, identNode.tree))
            }
        }


        private fun noReferenceError(path: UppaalPath, subTemRefIsProblem: Boolean): UppaalMessage =
            if (subTemRefIsProblem)
                createUppaalError(path, "A sub-template reference must reference some sub-template name", isUnrecoverable = true) // TODO: More correct message
            else
                createUppaalError(path, "A boundary point on sub-template reference must have a non-blank name", isUnrecoverable = true)

        private fun invalidReferenceError(path: UppaalPath, name: String, subTemName: String?): UppaalMessage =
            if (subTemName == null)
                createUppaalError(path, "The name '$name' does not reference a sub-template", isUnrecoverable = true)
            else
                createUppaalError(path, "The name '$name' does not reference a boundary point on sub-template '$subTemName'", isUnrecoverable = true)

        private fun conflictingBoundaryDirectionError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "A boundary point on a sub-template reference must have the same direction as the boundary point it references", isUnrecoverable = true)

        private fun leavingReferencedEntryError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "An edge cannot leave an entry boundary location that is on a sub-template reference", isUnrecoverable = true)

        private fun enteringReferencedExitError(path: UppaalPath): UppaalMessage =
            createUppaalError(path, "An edge cannot enter an exit boundary location that is on a sub-template reference", isUnrecoverable = true)

        private fun cyclicReferencesError(uppaalPath: UppaalPath, referencePath: LinkedHashSet<TaTemplateInfo>): UppaalMessage {
            val refPathString = referencePath.joinToString(" -> ") { it.trueName }
            return createUppaalError(uppaalPath, "Detected cyclic inclusion of sub-templates: '$refPathString'", isUnrecoverable = true)
        }

        private fun incompleteBoundaryPathError(boundaryPath: List<BoundaryPathNode>): UppaalMessage {
            // TODO: Proper message and path
            return createUppaalError(boundaryPath.first().templateInfo.path, "Incomplete path starting in entry to a reference to '${boundaryPath.first().templateInfo.trueName}'", isUnrecoverable = true)
        }

        private fun cyclicBoundaryPathError(boundaryPath: List<BoundaryPathNode>): UppaalMessage {
            // TODO: Proper message and path
            return createUppaalError(boundaryPath.first().templateInfo.path, "Cyclic path starting in entry to a reference to '${boundaryPath.first().templateInfo.trueName}'", isUnrecoverable = true)
        }

        private fun multipleSyncOnBoundaryPathError(boundaryPath: List<BoundaryPathNode>, syncEdges: List<Label>): UppaalMessage {
            // TODO: Proper message and path
            return createUppaalError(
                boundaryPath.first().templateInfo.path,
                "Multiple 'sync'-labels (${syncEdges.joinToString { "'${it.content}'" }}) on path starting in entry to a reference to '${boundaryPath.first().templateInfo.trueName}'",
                isUnrecoverable = true
            )
        }

        private fun userCodeInstantiatesSubTemplateError(path: UppaalPath, code: String, node: ParseTree): UppaalMessage =
            createUppaalError(path, code, node, "User-code cannot instantiate a sub-template", isUnrecoverable = true)

        private fun partialInstantiationIsSubTemplateError(path: UppaalPath, code: String, node: ParseTree): UppaalMessage =
            createUppaalError(path, code, node, "A partial instantiation cannot be a sub-template", isUnrecoverable = true)

        private fun unexpectedArgumentCountError(subTemRefPath: UppaalPath, arguments: Arguments?, argCount: Int, paramCount: Int): UppaalMessage {
            return if (arguments == null)
                createUppaalError(subTemRefPath, "A sub-template reference expected '$paramCount' arguments, but got '$argCount'", isUnrecoverable = true)
            else
                createUppaalError(subTemRefPath.extend(arguments), arguments.content, arguments.content.indices, "A sub-template reference expected '$paramCount' arguments, but got '$argCount'", isUnrecoverable = true)
        }

        private fun refParamMustHaveReferencableArgumentError(path: UppaalPath, code: String, arg: GuardedParseTree): UppaalMessage =
            createUppaalError(path, code, arg.tree, "An argument given to a reference parameter must be referencable (i.e., simply an identifier. Note that subscripted arrays are currently not supported)", isUnrecoverable = true)

        private fun constArgumentToRefParameterError(path: UppaalPath, code: String, arg: GuardedParseTree): UppaalMessage =
            createUppaalError(path, code, arg.tree, "An argument given to a reference parameter cannot be constant since const-ness will be lost. Switch to a constant parameter instead.", isUnrecoverable = true)
    }


    private inner class InfixMapping(private val index: AioCompModelIndex) : ModelPhase() {
        private val partialInstConfre by lazy { generateParser("PartialInst") }
        private val systemLineConfre by lazy { generateParser("SystemLine") }
        private val trueToInfixedNames = HashMap<String, String>()


        override fun onConfigured() {
            register(::mapNta)
            register(::mapSystemLine)
        }


        private fun mapNta(path: UppaalPath, nta: Nta) {
            for (rootInfo in index.rootSubTemUsers.values)
                if (rootInfo.canBeMapped){
                    val newTemplate = SubTemplateInfixer.infix(rootInfo, index)
                    trueToInfixedNames[rootInfo.trueName] = newTemplate.name.content
                    rootInfo.infixedName = newTemplate.name.content

                    // Replace template with its infixed version while maintaining the original index (so errors are easier to back-map)
                    nta.templates.add(nta.templates.indexOf(rootInfo.element), newTemplate)
                    nta.templates.remove(rootInfo.element)
                }

            // Clear non-native elements from (sub-)templates so that UPPAAL engine can still parse and error-check these.
            var locationOffset = 0 // This is used to prevent "overlapping locations" warnings
            for (template in nta.templates) {
                val idsOfRemovedElements = template.subtemplatereferences
                    .flatMap { subTemRef -> subTemRef.boundarypoints.map { it.id } }
                    .plus(template.boundarypoints.map { it.id })

                // Replace non-native with native objects to preserve edges to UPPAAL engine can detect errors
                for (id in idsOfRemovedElements)
                    template.locations.add(Location(id, locationOffset++ * 17, locationOffset++ * 17, null, ArrayList(), null, null))
                template.subtemplatereferences.clear()
                template.boundarypoints.clear()

                // Make initial location so UPPAAL engine does not complain about that
                if (template.init == null) {
                    val initId = "initID" + nta.templates.indexOf(template)
                    template.locations.add(Location(initId, locationOffset++ * 17, locationOffset++ * 17, null, ArrayList(), null, null))
                    template.init = Init(initId)
                }
            }
        }

        /** Replaces top-level sub-template user-names with "infix names" and make back-map to original system configuration **/
        private fun mapSystemLine(path: UppaalPath, system: System) {
            // TODO: Data-structure to make system-back-map (AioCompSystemIndex?)

            val rewriter = getRewriter(path, system.content)

            for (partialInst in partialInstConfre.findAll(system.content)) {
                val infixName = trueToInfixedNames[AioCompModelIndex.trueName(partialInst[3]!!.toString())] ?: continue
                rewriter.replace(partialInst[3]!!.fullRange, infixName)
            }

            for (fullInst in systemLineConfre.findAll(rewriter.originalText)) {
                val identNodes = fullInst[2]!!.children.map { it!![1]!![0] } + fullInst[1]!![0]
                for (identNode in identNodes.filterNotNull()) {
                    val infixName = trueToInfixedNames[AioCompModelIndex.trueName(identNode.toString())] ?: continue
                    rewriter.replace(identNode.fullRange, infixName)

                    // TODO: Make system-index for name/simulation/etc. back-mapping
                    //  - But not for now
                }
            }

            system.content = rewriter.getRewrittenText()
        }
    }


    private inner class SimulatorMapping(private val modelIndex: AioCompModelIndex, private val systemIndex: AioCompSystemIndex) : SimulatorPhase() {
        override fun backMapInitialSystem(system: UppaalSystem) {
            for (process in system.processes) {
                val templateInfo = systemIndex.tryRegister(process) ?: continue
                process.template = templateInfo.trueName

                // TODO: Insert sub-processes for every infixed process
            }
        }
    }


    private inner class QueryMapping(private val modelIndex: AioCompModelIndex, private val systemIndex: AioCompSystemIndex) : QueryPhase() {
        val queryConfre by lazy { generateParser("Query") }
        val expressionConfre by lazy { generateParser("Expression") }

        override fun mapQuery(queryRewriter: TextRewriter) {
            val parseTrees = queryConfre.findAll(queryRewriter.originalText).toList().ifEmpty {
                expressionConfre.findAll(queryRewriter.originalText).toList()
            }

            for (tree in parseTrees) {
                val extendedTerms = tree.findAllNonTerminals("ExtendedTerm", onlyLocal = false, onlyVisible = false)
                for (term in extendedTerms.filter { !it[2]!!.isBlank })
                    rewriteExtendedTerm(term, queryRewriter)
            }
        }

        private fun rewriteExtendedTerm(tree: GuardedParseTree, queryRewriter: TextRewriter) {
            var currentInfo = systemIndex.baseProcessNameToTemplateInfo[tree[0]!!.toStringNotNull()] ?: return
            var currentExtendedTerm = tree
            val prefixes = mutableListOf<String>()

            var currentSubTemUsage: SubTemplateUsage? = null
            while (true) {
                if (currentExtendedTerm[2]!![1]!!.isBlank)
                    return
                if (!currentExtendedTerm[2]!![1]!![0]!![1]!!.isBlank) // Cannot be followed by [] or ()
                    break

                val nextExtendedTerm = currentExtendedTerm[2]!![1]!![0]!!
                val nextIdent = nextExtendedTerm[0]!!.toStringNotNull()
                currentSubTemUsage = currentInfo.subTemplateInstances.entries
                    .find { it.key.name!!.content == nextIdent }
                    ?.value
                    ?: break

                currentInfo = currentSubTemUsage.referencedInfo!!
                currentExtendedTerm = nextExtendedTerm
                prefixes += nextIdent
            }
            currentExtendedTerm = currentExtendedTerm[2]!![1]!![0]!!

            val startReplaceIndex = tree[2]!![1]!!.fullRange.first
            val endReplaceIndex = currentExtendedTerm[0]!!.fullRange.last
            val replaceRange = startReplaceIndex .. endReplaceIndex

            val fieldIdent = currentExtendedTerm[0]!!.toStringNotNull()
            val mappedFieldName = currentSubTemUsage?.namesToReplace?.get(fieldIdent)
                ?: SubTemplateInfixer.qualify(fieldIdent, prefixes)

            queryRewriter.replace(replaceRange, mappedFieldName)
                .addBackMap()
                .activateOn(ActivationRule.ERROR_CONTAINS_ACTIVATION)
                .overrideErrorRange { tree.fullRange.first.. endReplaceIndex }
                .overrideErrorMessage { it.replace(mappedFieldName, fieldIdent) }
                .overrideErrorContext { it.replace(mappedFieldName, queryRewriter.originalText.substring(replaceRange)) }
        }
    }
}