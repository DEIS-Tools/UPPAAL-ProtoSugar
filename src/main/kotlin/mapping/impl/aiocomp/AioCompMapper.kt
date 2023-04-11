package mapping.impl.aiocomp

import mapping.base.Mapper
import mapping.base.ModelPhase
import mapping.base.Phases
import tools.indexing.text.EvaluableDecl
import tools.indexing.text.ParameterDecl
import tools.indexing.text.types.modifiers.ConstType
import tools.indexing.text.types.modifiers.ReferenceType
import tools.indexing.tree.TemplateDecl
import tools.parsing.Confre
import tools.parsing.GuardedParseTree
import tools.parsing.ParseTree
import uppaal.UppaalPath
import uppaal.messaging.UppaalMessage
import uppaal.messaging.createUppaalError
import uppaal.model.*

class AioCompMapper : Mapper() {
    override fun buildPhases(): Phases {
        val index = AioCompModelIndex()

        return Phases(
            listOf(ModelIndexing(index), ReferenceValidation(index), InfixMapping(index)),
            null,
            null
        )
    }


    inner class ModelIndexing(val index: AioCompModelIndex) : ModelPhase() {
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


    inner class ReferenceValidation(val index: AioCompModelIndex) : ModelPhase() {
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


    inner class InfixMapping(val index: AioCompModelIndex) : ModelPhase() {
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
                    nta.templates.add(newTemplate)
                    trueToInfixedNames[rootInfo.trueName] = newTemplate.name.content
                }

            // TODO: What to do about sub-templates(-users)? Remove them (must map errors from infix to original) or keep them (must remove non-native concepts in a guaranteed, non-disruptive manner)
            nta.templates.removeAll(index.taTemplates.values.filter { it.subTemOrUser }.map { it.element }) // Remove all for now
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
}