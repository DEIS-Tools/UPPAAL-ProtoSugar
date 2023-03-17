package mapping.impl

import createOrGetRewriter
import joinInsert
import mapping.mapping.Mapper
import mapping.mapping.ModelPhase
import mapping.mapping.PhaseOutput
import mapping.parsing.*
import mapping.restructuring.ActivationRule
import mapping.restructuring.BackMapResult
import mapping.restructuring.TextRewriter
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalPath
import uppaal.messaging.createUppaalError
import uppaal.model.*


data class PaChaInfo(val numParameters: Int, val numDimensions: Int, val parameterIndex: Int?)
class PaChaMap : HashMap<String, PaChaInfo>()

class PaChaMapper : Mapper() {
    // TODO: Fix so that normal channel parameters of Templates are tracked for type-error-handing in partial instantiations
    private val chanDeclGrammar = Confre("""
            ChanDecl   :== IDENT TypeList ['&'] IDENT {Subscript} [';'] .
            TypeList   :== '(' [SimpleType] {',' [SimpleType]} ')' .
            SimpleType :== ['&'] IDENT [TypeList] {Subscript} .
            
            ${ConfreHelper.expressionGrammar}
        """.trimIndent())

    private val chanUseGrammar = Confre("""
            ChanUsage :== IDENT {Subscript} ['(' [['meta'] [Expression]] {',' [['meta'] [Expression]]} ')'] ('!' | '?') .
            
            ${ConfreHelper.expressionGrammar}
        """.trimIndent())


    override fun getPhases(): PhaseOutput
        = PhaseOutput(listOf(Phase1()), null, null)


    private inner class Phase1 : ModelPhase()
    {
        private val paChaMaps: HashMap<String?, PaChaMap> = hashMapOf(Pair(null, PaChaMap())) // 'null' = global scope
        private val rewriters = HashMap<String, TextRewriter>()


        init {
            register(::registerTemplatePaChaMap)

            register(::mapDeclaration)
            register(::mapParameter)
            register(::mapTransition)
            register(::mapSystem)
        }


        private fun registerTemplatePaChaMap(path: UppaalPath, template: Template): List<UppaalMessage> {
            val templateName = template.name.content // TODO Null or blank instead
                ?: return listOf(createUppaalError(path, "Template has no name.", isUnrecoverable = true))
            paChaMaps[templateName] = PaChaMap()
            return listOf()
        }


        private fun mapDeclaration(path: UppaalPath, declaration: Declaration): List<UppaalMessage> {
            val parent = path.takeLast(2).first().element
            val paChaMap = paChaMaps[(parent as? Template)?.name?.content]!! // 'null' = global scope

            val (newContent, errors) = mapPaChaDeclarations(path, declaration.content, paChaMap)
            declaration.content = newContent
            return errors
        }

        private fun mapParameter(path: UppaalPath, parameter: Parameter): List<UppaalMessage> {
            val template = path.takeLast(2).first().element as Template
            val paChaMap = paChaMaps[template.name.content]!!

            val (newContent, errors) = mapPaChaDeclarations(path, parameter.content, paChaMap)
            parameter.content = newContent
            return errors
        }

        private fun mapTransition(path: UppaalPath, transition: Transition): List<UppaalMessage> {
            val sync: Label = transition.labels.find { it.kind == "synchronisation" } ?: return listOf()
            val update: Label = transition.labels.find { it.kind == "assignment" }
                                ?: Label("assignment", sync.x, sync.y + 17)

            val template = path.takeLast(2).first().element as Template
            val templatePaChas = paChaMaps[template.name.content]!!

            val match = chanUseGrammar.matchExact(sync.content) as? Node ?: return listOf()
            if (!transition.labels.contains(update))
                transition.labels.add(update)

            val syncPath = path.plus(sync, transition.labels.indexOf(sync) + 1)
            val updatePath = path.plus(update, transition.labels.indexOf(update) + 1)
            return mapEmitOrReceive(syncPath, updatePath, match, sync, update, templatePaChas)
        }

        private fun mapSystem(path: UppaalPath, system: System): List<UppaalMessage> {
            val rewriter = rewriters.createOrGetRewriter(path, system.content)
            val errorsFirst = mapPaChaDeclarations(path, rewriter, paChaMaps[null]!!)
            val (newContent, errorsSecond) = mapTemplateInstantiations(path, rewriter)
            system.content = newContent
            return errorsFirst + errorsSecond
        }


        private fun mapPaChaDeclarations(path: UppaalPath, code: String, scope: PaChaMap, mapInPartialInstantiationIndex: Int = -1): Pair<String, List<UppaalMessage>> {
            val rewriter = rewriters.createOrGetRewriter(path, code)
            val errors = mapPaChaDeclarations(path, rewriter, scope, mapInPartialInstantiationIndex)
            return Pair(rewriter.getRewrittenText(), errors)
        }

        private fun mapPaChaDeclarations(path: UppaalPath, textRewriter: TextRewriter, scope: PaChaMap, mapInPartialInstantiationIndex: Int = -1): List<UppaalMessage> {
            val originalCode = textRewriter.originalText

            val inParameterList = mapInPartialInstantiationIndex != -1 || (path.last().element is Parameter) // In "system/declaration" or in "parameter" (or partial instantiation)
            val partialInstantiations = ConfreHelper.partialInstantiationConfre.findAll(originalCode).map { it as Node }.toList()

            val errors = ArrayList<UppaalMessage>()
            for (chan in chanDeclGrammar.findAll(originalCode).map { it as Node }.filter { isPaChaDecl(path, originalCode, it, errors) }) {
                val currentPartialInstantiationIndex = partialInstantiations.indexOfFirst { chan.startPosition() in it.range }
                if (mapInPartialInstantiationIndex != currentPartialInstantiationIndex)
                    continue

                val forgotSemicolon = !inParameterList && checkSemicolon(path, originalCode, chan, errors)
                val chanName = chan.children[3]!!.toString()

                // Test parameter reference
                if (inParameterList && chan.children[2]!!.isBlank())
                    errors.add(createUppaalError(path, originalCode, chan.children[3]!!.range, "A channel parameter (of a template or partial instantiation) must be a reference.", false))

                // Parse and remove type list
                val typeListNode = chan.children[1] as Node
                val typeNodes = listOf(typeListNode.children[1]!!.asNode()) + (typeListNode.children[2]!!.asNode()).children.map { it!!.asNode().children[1]!!.asNode() }
                val typeErrors = checkTypes(path, originalCode, typeNodes, typeListNode.range)
                errors.addAll(typeErrors)
                textRewriter.replace(typeListNode.range, "")

                // Generate parameter meta-variables
                if (typeErrors.isEmpty()) {
                    val justAfterChan = chan.endPosition() + 1
                    if (forgotSemicolon)
                        textRewriter.insert(justAfterChan, ";")

                    for (pair in typeNodes.map { it.children[0] as Node }.withIndex()) {
                        val typeName = pair.value.children[1]!!.toString()
                        val array = if (pair.value.children[3]!!.isNotBlank()) originalCode.substring(pair.value.children[3]!!.range)
                                    else ""

                        // Add the parameter variable declaration
                        val parameterVariableDecl =
                            if (inParameterList)
                                ", $typeName &__${chanName}_p${pair.index+1}${array}"
                            else
                                "\nmeta $typeName __${chanName}_p${pair.index+1}${array};"

                        textRewriter.insert(justAfterChan, parameterVariableDecl)
                            .addBackMap()
                            .activateOn(parameterVariableDecl.indices, ActivationRule.ACTIVATION_CONTAINS_ERROR)
                            .overrideErrorRange { pair.value.range }
                    }
                }

                // Store information about the current channel variable (or parameter)
                var parameterListRange = IntRange(0, originalCode.length-1)
                val parameterIndex =
                    if (inParameterList && mapInPartialInstantiationIndex == -1)
                        ParseHelper.getParameterIndex(originalCode.withIndex().iterator(), chan.startPosition())
                    else if (inParameterList) {
                        // Takes the parameter list without surrounding parentheses
                        parameterListRange = partialInstantiations[mapInPartialInstantiationIndex].children[1]!!.asNode().children[1]!!.range
                        val parameterString = originalCode.substring(parameterListRange)
                        ParseHelper.getParameterIndex(parameterString.withIndex().iterator(), chan.startPosition() - parameterListRange.first)
                    }
                    else null
                val numTypes =
                    if (typeErrors.isEmpty()) typeNodes.size
                    else -1 // "-1" means "mapper should not map this because of errors"
                val numDimensions = (chan.children[4] as Node).children.size
                scope[chanName] = PaChaInfo(numTypes, numDimensions, parameterIndex)

                if (inParameterList && parameterIndex == null)
                    errors.add(
                        createUppaalError(
                        path, originalCode, parameterListRange, "Syntax error in parameters wrt. blocks: '()', '[]', and '{}'", true
                    )
                    )
            }

            return errors
        }


        private fun isPaChaDecl(path: UppaalPath, code: String, decl: Node, errors: ArrayList<UppaalMessage>): Boolean {
            val nameNode = decl.children[0]!!
            val typeListNode = decl.children[1]!!
            if (nameNode.toString() == "chan")
                return true
            else if (typeListNode.isNotBlank())
                errors.add(createUppaalError(path, code, typeListNode, "Only the 'chan' type can have a parameter-type-list.", true))

            return false
        }

        private fun checkSemicolon(path: UppaalPath, code: String, chan: Node, errors: ArrayList<UppaalMessage>): Boolean {
            if (chan.children[5]!!.isBlank()) {
                errors.add(createUppaalError(path, code, chan, "Missing semicolon after channel declaration."))
                return true
            }
            return false
        }

        private fun checkTypes(path: UppaalPath, originalCode: String, types: List<Node>, typeListRange: IntRange): List<UppaalMessage> {
            val errors = ArrayList<UppaalMessage>()
            for (type in types.map { it.children[0] as? Node })
                if (type == null || type.isBlank())
                    errors.add(createUppaalError(path, originalCode, typeListRange, "Blank type in type list of parameterised channel"))
                else {
                    if (type.children[0]?.isNotBlank() == true)
                        errors.add(createUppaalError(path, originalCode, type.children[0]!!, "A parameterized channel cannot have reference parameters."))

                    // Check if parameter-type is itself a channel (which is illegal for now)
                    if (type.children[1]!!.toString() == "chan")
                        errors.add(createUppaalError(path, originalCode, type, "Parameterized channels do not support channel-type parameters."))
                    else if (type.children[2]?.isNotBlank() == true)
                        errors.add(createUppaalError(path, originalCode, type.children[2]!!, "Only the 'chan' type can have a parameter-type-list. Also, parameterized channels do not support channel-type parameters."))
                }
            return errors
        }


        private fun mapEmitOrReceive(syncPath: UppaalPath, updatePath: UppaalPath, match: Node, sync: Label, update: Label, scope: PaChaMap): List<UppaalMessage>
        {
            val syncRewriter = rewriters.createOrGetRewriter(syncPath, sync.content)
            val updateRewriter = rewriters.createOrGetRewriter(updatePath, update.content)

            val channelName = match.children[0]!!.toString()
            val chanInfo = scope[channelName] ?: paChaMaps[null]!![channelName]
            if (chanInfo == null && match.children[2]!!.isBlank())
                return listOf()

            if (match.children[2]!!.isNotBlank()) {
                syncRewriter.replace(match.children[2]!!.range, "")
                sync.content = syncRewriter.getRewrittenText()
            }

            val argOrParamNodes = getArgOrParamNodes(match.children[2] as Node)
            val expectedLength = chanInfo?.numParameters ?: 0
            if (argOrParamNodes.size != expectedLength)
                return listOf(createUppaalError(syncPath, syncRewriter.originalText, match.children[0]!!.range, "Channel '$channelName' expects '$expectedLength' argument(s)/parameter(s). Got '${argOrParamNodes.size}'."))
            if (expectedLength == 0)
                return listOf()

            val errors = ArrayList<UppaalMessage>()
            val isEmit = match.children[3]!!.toString() == "!"
            if (isEmit) {
                val arguments = extractChanArgs(syncPath, errors, argOrParamNodes, syncRewriter.originalText, match.children[0]!!)
                if (arguments.any { it == null })
                    return errors

                // Insert updates of the global parameter-variables
                val insertList = arguments.withIndex().map { "__${channelName}_p${it.index+1} = ${it.value!!.first}" }
                updateRewriter.joinInsert(0, insertList).forEach { insertOp ->
                    insertOp.value.addBackMap()
                        .activateOn(ActivationRule.ACTIVATION_CONTAINS_ERROR)
                        .overrideErrorPath { Pair(syncPath.toString(), syncRewriter) }
                        .overrideErrorRange { arguments[insertOp.index]!!.second }
                }
                if (insertList.isNotEmpty() && updateRewriter.originalText.isNotBlank())
                    updateRewriter.insert(0, ",\n")
            }
            else { // Otherwise, it's a "Listen"
                val parameters = extractChanParams(syncPath, errors, argOrParamNodes, syncRewriter.originalText, match.children[0]!!)
                if (parameters.any { it == null })
                    return errors

                val (metaParams, nonMetaParams) = parameters.filterNotNull().withIndex().partition { it.value.first }

                // Update local non-meta variables
                val insertList = nonMetaParams.map { "${it.value.second} = __${channelName}_p${it.index+1}" }
                updateRewriter.joinInsert(0, insertList).forEach { insertOp ->
                    insertOp.value.addBackMap()
                        .activateOn(ActivationRule.ACTIVATION_CONTAINS_ERROR)
                        .overrideErrorPath { Pair(syncPath.toString(), syncRewriter) }
                        .overrideErrorRange { nonMetaParams[insertOp.index].value.third }
                }

                // Replace meta-variables with global parameter-variables.
                if (updateRewriter.originalText.isNotBlank()) {
                    if (insertList.isNotEmpty())
                        updateRewriter.insert(0, ",\n")
                    val updateTree = ConfreHelper.expressionAssignmentListConfre.matchExact(updateRewriter.originalText)
                        ?: return errors + createUppaalError(
                            updatePath, updateRewriter.originalText, updateRewriter.originalText.indices, "PaCha mapper could not parse this update label", true
                        )

                    for (leaf in updateTree.preOrderWalk().mapNotNull { it as? Leaf }.filter { it.isNotBlank() }) {
                        val param = metaParams.find { it.value.second == leaf.token!!.value }
                            ?: continue
                        updateRewriter.replace(leaf.range, "__${channelName}_p${param.index+1}")
                    }
                }
            }

            update.content = updateRewriter.getRewrittenText()
            return errors
        }

        private fun getArgOrParamNodes(argListNode: Node): List<Node>
        {
            if (argListNode.isBlank())
                return listOf()

            val args = listOf(argListNode.children[1] as Node)
                .plus((argListNode.children[2] as Node).children.map {
                    (it as Node).children[1] as Node
                })

            if (args[0].isBlank() && args.size == 1)
                return listOf()

            return args
        }

        private fun extractChanArgs(syncPath: UppaalPath, errors: ArrayList<UppaalMessage>, argList: List<Node>, originalSync: String, nameNode: ParseTree): List<Pair<String, IntRange>?>
        {
            var foundBlankArgument = false
            val args = ArrayList<Pair<String, IntRange>?>()
            for (arg in argList)
                if (arg.children[0]!!.isNotBlank()) {
                    errors.add(createUppaalError(syncPath, originalSync, arg.children[0]!!, "Cannot use 'meta' when emitting on a channel. Only expressions are allowed when emitting.", true))
                    args.add(null)
                }
                else if (arg.children[1]!!.isNotBlank())
                    args.add(Pair(originalSync.substring(arg.children[1]!!.range), arg.children[1]!!.range))
                else
                {
                    if (!foundBlankArgument)
                        errors.add(createUppaalError(syncPath, originalSync, nameNode, "One or more blank arguments.", true))
                    foundBlankArgument = true
                    args.add(null)
                }

            return args
        }

        private fun extractChanParams(syncPath: UppaalPath, errors: ArrayList<UppaalMessage>, paramList: List<Node>, originalSync: String, nameNode: ParseTree): List<Triple<Boolean, String, IntRange>?>
        {
            var blankParameter = false
            val params = ArrayList<Triple<Boolean, String, IntRange>?>()
            for (param in paramList)
                if (param.children[1]!!.isNotBlank())
                {
                    val nonBlankExprChildren = param.children[1]!!.preOrderWalk().filter { it.isNotBlank() && it is Leaf }.toList()
                    if (nonBlankExprChildren.size > 1) {
                        errors.add(createUppaalError(syncPath, originalSync, param.children[1]!!, "When listening on a channel, only local variables or meta variables are allowed.", true))
                        params.add(null)
                        continue
                    }

                    val paramName = nonBlankExprChildren[0].toString()
                    val namePattern = Regex("""[a-zA-Z_][a-zA-Z0-9_]*""")
                    if (!namePattern.matches(paramName)) {
                        errors.add(createUppaalError(syncPath, originalSync, nameNode, "When listening on a channel, only local variables or meta variables are allowed.", true))
                        params.add(null)
                        continue
                    }

                    val isMeta = param.children[0]!!.isNotBlank()
                    params.add(Triple(isMeta, paramName, param.range))
                }
                else
                {
                    if (!blankParameter)
                        errors.add(createUppaalError(syncPath, originalSync, nameNode, "One or more blank parameters.", true))
                    blankParameter = true
                    params.add(null)
                }

            return params
        }


        private fun mapTemplateInstantiations(path: UppaalPath, textRewriter: TextRewriter): Pair<String, List<UppaalMessage>>
        {
            val globalPaChas = paChaMaps[null]!!

            val errors = ArrayList<UppaalMessage>()
            for ((partialInstIndex, partialInstTree) in ConfreHelper.partialInstantiationConfre.findAll(textRewriter.originalText).map { it as Node }.withIndex())
            {
                // Compute PaChaMap for lhs of partial instantiation
                val lhsName = partialInstTree.children[0]!!.toString()
                val parameterNode = partialInstTree.children[1] as Node
                val lhsPaChas = PaChaMap()
                paChaMaps[lhsName] = lhsPaChas
                if (parameterNode.isNotBlank())
                    errors += mapPaChaDeclarations(path, textRewriter, lhsPaChas, partialInstIndex)

                // Find all (parameterized) channel parameters of rhs.
                val rhsTemplateName = partialInstTree.children[3]!!.toString()
                val rhsParamPaChas = paChaMaps[rhsTemplateName]!!.values.filter { it.parameterIndex != null }
                if (rhsParamPaChas.isEmpty())
                    continue

                // Find all (non-null) arguments that correspond (wrt. location) to a lhs channel parameter.
                val arguments = getExpressionNodes(partialInstTree.children[5] as Node)
                val argsAndParams = arguments.withIndex()
                    .map { arg -> Pair(arg.value, rhsParamPaChas.find { param -> arg.index == param.parameterIndex }) }
                    .filter { it.first != null && it.second != null }
                    .map { Pair(it.first!!, it.second!!) }

                // Unfold parameter-variables for each PaCha-variable.
                for ((fullArgument, paramPaChaInfo) in argsAndParams) {
                    val argChannelName = fullArgument.toString().split(' ', limit = 3)[1]
                    val argPaChaInfo = globalPaChas[argChannelName] ?: lhsPaChas[argChannelName]
                    if (argPaChaInfo == null) {
                        errors += createUppaalError(path, textRewriter.originalText, fullArgument, "'$argChannelName' is not a parameterized channel(-array).", true)
                        continue
                    }

                    val argHasSubscript = fullArgument.toString().split(' ', limit = 4)[2] == "["
                    val argInputNumDimensions = if (argHasSubscript) 0 else argPaChaInfo.numDimensions

                    val newErrors = ArrayList<UppaalMessage>()
                    if (argPaChaInfo.numParameters != paramPaChaInfo.numParameters)
                        newErrors += createUppaalError(path, textRewriter.originalText, fullArgument, "Argument with '$argChannelName' has '${argPaChaInfo.numParameters}' parameter(s), but the formal parameter requires '${paramPaChaInfo.numParameters}' parameter(s).", true)
                    if (argInputNumDimensions != paramPaChaInfo.numDimensions)
                        newErrors += createUppaalError(path, textRewriter.originalText, fullArgument, "Argument with '$argChannelName' results in '${argInputNumDimensions}' dimensions(s), but the formal parameter requires '${paramPaChaInfo.numDimensions}' dimensions(s).", true)
                    if (errors.addAll(newErrors))
                        continue

                    // Rewrite happens here
                    val justAfterArgument = fullArgument.endPosition() + 1
                    val argsToInsert = List(argPaChaInfo.numParameters) { "__${argChannelName}_p${it+1}" }

                    textRewriter.insert(justAfterArgument, ", ")
                    textRewriter.joinInsert(justAfterArgument, argsToInsert).forEach { operation ->
                        operation.value.addBackMap()
                            .activateOn(ActivationRule.ACTIVATION_CONTAINS_ERROR)
                            .overrideErrorRange { fullArgument.range }
                            .overrideErrorMessage { message -> "From PaCha auto generated arguments: $message" }
                    }
                }
            }

            return Pair(textRewriter.getRewrittenText(), errors)
        }

        private fun getExpressionNodes(expressionListNode: Node): List<Node?>
        {
            if (expressionListNode.isBlank())
                return listOf()

            val optionalNodes = arrayListOf(expressionListNode.children[0] as Node) +
                    (expressionListNode.children[1] as Node).children.map { (it as Node).children[1] as Node }

            return optionalNodes.map {
                if (it.isBlank()) null
                else it.children[0] as Node
            }
        }


        override fun backMapModelErrors(errors: List<UppaalMessage>)
            = errors.filter { rewriters[it.path]?.backMapError(it) != BackMapResult.REQUEST_DISCARD }
    }
}