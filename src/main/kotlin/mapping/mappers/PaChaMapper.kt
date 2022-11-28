package mapping.mappers

import mapping.base.*
import mapping.parsing.*
import uppaal_pojo.*
import uppaal_pojo.Declaration
import java.lang.Character.MIN_VALUE as nullChar


data class PaChaInfo(val numParameters: Int, val numDimensions: Int, val parameterIndex: Int?)
class PaChaMap : HashMap<String, PaChaInfo>()

class PaChaMapper : Mapper {
    override fun getPhases(): Triple<Sequence<ModelPhase>, SimulatorPhase?, QueryPhase?>
        = Triple(sequenceOf(Phase1()), null, null)

    private class Phase1 : ModelPhase()
    {
        private val PARAMETER_TYPE_HINT = "PARAM_TYPE"

        private val backMaps = HashMap<Quadruple<Int, Int, Int, Int>, Pair<Quadruple<Int, Int, Int, Int>, String>>()
        private val paChaMaps: HashMap<String?, PaChaMap> = hashMapOf(Pair(null, PaChaMap()))


        // TODO: Fix so that normal channel parameters of Templates are tracked for type-error-handing in partial instantiations
        private val chanDeclGrammar = Confre("""
            ChanDecl :== IDENT TypeList ['&'] IDENT {Array} [';'] .
            TypeList :== '(' [Type] {',' [Type]} ')' .
            Type     :== ['&'] IDENT [TypeList] {Array} .
            
            ${ConfreHelper.expressionGrammar}
        """.trimIndent())

        private val chanUseGrammar = Confre("""
            ChanUsage :== IDENT {Array} ['(' [['meta'] [Expression]] {',' [['meta'] [Expression]]} ')'] ('!' | '?') .
            
            ${ConfreHelper.expressionGrammar}
        """.trimIndent())


        init {
            register(::registerTemplatePaChaMap)

            register(::mapDeclaration)
            register(::mapParameter)
            register(::mapTransition)
            register(::mapSystem)
        }


        private fun registerTemplatePaChaMap(path: List<PathNode>, template: Template): List<UppaalError> {
            paChaMaps[
                template.name.content ?: return listOf(UppaalError(path, 1,1,1,1, "Template has no name.", "", isUnrecoverable = true))
            ] = PaChaMap()
            return listOf()
        }


        private fun mapDeclaration(path: List<PathNode>, declaration: Declaration): List<UppaalError> {
            val parent = path.takeLast(2).first().element
            val paChaMap = paChaMaps[(parent as? Template)?.name?.content]!!

            val (newContent, errors) = mapPaChaDeclarations(path, declaration.content, paChaMap)
            declaration.content = newContent
            return errors
        }

        private fun mapParameter(path: List<PathNode>, parameter: Parameter): List<UppaalError> {
            val template = path.takeLast(2).first().element as Template
            val paChaMap = paChaMaps[template.name.content]!!

            val (newContent, errors) = mapPaChaDeclarations(path, parameter.content, paChaMap)
            parameter.content = newContent
            return errors
        }

        private fun mapTransition(path: List<PathNode>, transition: Transition): List<UppaalError> {
            val sync: Label = transition.labels.find { it.kind == "synchronisation" } ?: return listOf()
            val update: Label = transition.labels.find { it.kind == "assignment" }
                                ?: Label("assignment", sync.x, sync.y + 17)

            val template = path.takeLast(2).first().element as Template
            val templatePaChas = paChaMaps[template.name.content]!!

            val match = chanUseGrammar.matchExact(sync.content) as? Node ?: return listOf()
            if (!transition.labels.contains(update))
                transition.labels.add(update)

            val syncPath = path.plus(PathNode(sync, transition.labels.indexOf(sync) + 1))
            val updatePath = path.plus(PathNode(sync, transition.labels.indexOf(update) + 1))
            return mapEmitOrReceive(syncPath, updatePath, match, sync, update, templatePaChas)
        }

        private fun mapSystem(path: List<PathNode>, system: System): List<UppaalError> {
            val (newContentPartial, errorsFirst) = mapPaChaDeclarations(path, system.content, paChaMaps[null]!!)
            val (newContentFull, errorsSecond) = mapTemplateInstantiations(path, newContentPartial)
            system.content = newContentFull
            return errorsFirst.plus(errorsSecond)
        }


        private fun mapPaChaDeclarations(path: List<PathNode>, code: String, scope: PaChaMap, mapInPartialInstantiationIndex: Int = -1): Pair<String, List<UppaalError>> {
            val inParameterList = mapInPartialInstantiationIndex != -1 || (path.last().element is Parameter) // In "system/declaration" or in "parameter"
            val errors = ArrayList<UppaalError>()
            var offset = 0
            var newCode = code
            val partialInstantiations = ConfreHelper.partialInstantiationConfre.findAll(code).map { it as Node }.toList()

            for (chan in chanDeclGrammar.findAll(code).map { it as Node }.filter { isPaChaDecl(path, code, it, errors) }) {
                val currentPartialInstantiationIndex =
                    partialInstantiations.indexOfFirst { it.startPosition() <= chan.startPosition() && chan.startPosition() <= it.endPosition() }
                if (mapInPartialInstantiationIndex != currentPartialInstantiationIndex)
                    continue

                val forgotSemicolon = !inParameterList && checkSemicolon(path, code, chan, errors)
                val chanName = chan.children[3]!!.toString()

                // Test parameter reference
                if (inParameterList && chan.children[2]!!.isBlank())
                    errors.add(createUppaalError(path, code, chan.children[3]!!.range(), "A channel parameter of a template must be a reference.", false))

                // Parse and remove type list
                val typeListNode = chan.children[1] as Node
                val typeNodes = listOf(typeListNode.children[1] as Node)
                    .plus((typeListNode.children[2] as Node).children.map { (it as Node).children[1] as Node })
                val typeErrors = checkTypes(path, code, typeNodes, typeListNode.range())
                errors.addAll(typeErrors)

                val typesStart = typeListNode.startPosition() + offset
                val typesEnd = typeListNode.endPosition() + 1 + offset
                newCode = newCode.replaceRange(typesStart, typesEnd, " ".repeat(typesEnd - typesStart))

                // Generate parameter meta-variables
                if (typeErrors.isEmpty()) {
                    var insertPosition = chan.endPosition() + 1 + offset
                    if (forgotSemicolon) {
                        newCode = newCode.replaceRange(insertPosition, insertPosition, ";")
                        ++insertPosition
                    }

                    for (pair in typeNodes.map { it.children[0] as Node }.withIndex()) {
                        val typeName = pair.value.children[1]!!.toString()
                        val array = pair.value.children[3]!!.toString().replace(" ", "")

                        // Add the parameter variable declaration
                        val parameterVariableDecl =
                            if (inParameterList)
                                ", $typeName &__${chanName}_p${pair.index+1}${array}"
                            else
                                " meta $typeName __${chanName}_p${pair.index+1}${array};"
                        newCode = newCode.replaceRange(insertPosition, insertPosition, parameterVariableDecl)

                        // To map "unknown type" errors back to new syntax
                        val newLinesAndColumns =
                            if (inParameterList)
                                getLinesAndColumnsFromRange(newCode, IntRange(insertPosition+2, insertPosition+2 + typeName.length-1))
                            else
                                getLinesAndColumnsFromRange(newCode, IntRange(insertPosition+6, insertPosition+6 + typeName.length-1))
                        backMaps[newLinesAndColumns] = Pair(getLinesAndColumnsFromRange(code, pair.value.range()), PARAMETER_TYPE_HINT)

                        // Update
                        offset += parameterVariableDecl.length
                        insertPosition += parameterVariableDecl.length
                    }
                }

                var guiltyRange = IntRange(0, code.length-1)
                val numTypes = if (typeErrors.isEmpty()) typeNodes.size else -1 // "-1" means "mapper ignore for now"
                val numDimensions = (chan.children[4] as Node).children.size
                val parameterIndex =
                    if (inParameterList && mapInPartialInstantiationIndex == -1)
                        getParameterIndex(code.withIndex().iterator(), chan.startPosition())
                    else if (inParameterList) {
                        // Takes the parameter list without surrounding parentheses
                        guiltyRange = partialInstantiations[mapInPartialInstantiationIndex]
                            .children[1]!!.asNode().children[1]!!.range()
                        val parameterString = code.substring(guiltyRange)
                        getParameterIndex(parameterString.withIndex().iterator(), chan.startPosition() - guiltyRange.first)
                    }
                    else null
                scope[chanName] = PaChaInfo(numTypes, numDimensions, parameterIndex)

                if (inParameterList && parameterIndex == null)
                    errors.add(
                        createUppaalError(
                        path, code, guiltyRange, "Syntax error in parameters wrt. blocks: '()', '[]', and '{}'", true
                    )
                    )
            }

            return Pair(newCode, errors)
        }

        private fun isPaChaDecl(path: List<PathNode>, code: String, decl: Node, errors: ArrayList<UppaalError>): Boolean {
            val nameNode = decl.children[0]!!
            val typeListNode = decl.children[1]!!
            if (nameNode.toString() == "chan")
                return true
            else if (typeListNode.isNotBlank())
                errors.add(createUppaalError(path, code, typeListNode, "Only the 'chan' type can have a parameter-type-list.", true))

            return false
        }

        private fun checkSemicolon(path: List<PathNode>, code: String, chan: Node, errors: ArrayList<UppaalError>): Boolean {
            if (chan.children[5]!!.isBlank()) {
                errors.add(createUppaalError(path, code, chan, "Missing semicolon after channel declaration."))
                return true
            }
            return false
        }

        private fun checkTypes(path: List<PathNode>, originalCode: String, types: List<Node>, typeListRange: IntRange): List<UppaalError> {
            val errors = ArrayList<UppaalError>()
            for (type in types.map { it.children[0] as? Node })
                if (type == null || type.isBlank())
                    errors.add(createUppaalError(path, originalCode, typeListRange, "Blank type in type list of parameterised channel"))
                else {
                    if (type.children[0]?.isNotBlank() == true)
                        errors.add(createUppaalError(path, originalCode, type.children[0]!!, "A parameterized channel cannot have reference parameters."))

                    // Check if parameter-type is itself a channel (which is illegal without the "channel reference" mapper)
                    if (type.children[1]!!.toString() == "chan")
                        errors.add(createUppaalError(path, originalCode, type, "Parameterized channels do not support channel-type parameters."))
                    else if (type.children[2]?.isNotBlank() == true)
                        errors.add(createUppaalError(path, originalCode, type.children[2]!!, "Only the 'chan' type can have a parameter-type-list. Also, parameterized channels do not support channel-type parameters."))
                }
            return errors
        }

        private fun getParameterIndex(parameterStringChars: Iterator<IndexedValue<Char>>, currParamStartIndex: Int, endChar: Char = nullChar): Int? {
            val endChars = listOf(')', ']', '}')
            var localParamIndex = 0
            do {
                val next = parameterStringChars.next()
                if (next.value in endChars && next.value != endChar)
                    return null // Formatting error

                when (next.value) {
                    ',' -> localParamIndex++
                    '(' -> getParameterIndex(parameterStringChars, currParamStartIndex, ')') ?: return null
                    '[' -> getParameterIndex(parameterStringChars, currParamStartIndex, ']') ?: return null
                    '{' -> getParameterIndex(parameterStringChars, currParamStartIndex, '}') ?: return null
                    endChar -> return -1
                }
            } while (parameterStringChars.hasNext() && (next.index != currParamStartIndex || endChar != nullChar))

            if (!parameterStringChars.hasNext())
                return null // Formatting error

            return localParamIndex
        }


        private fun mapEmitOrReceive(syncPath: List<PathNode>, updatePath: List<PathNode>, match: Node, sync: Label, update: Label, scope: PaChaMap): List<UppaalError>
        {
            val errors = ArrayList<UppaalError>()
            val channelName = match.children[0]!!.toString()
            val chanInfo = scope[channelName] ?: paChaMaps[null]!![channelName]
            if (chanInfo == null && match.children[2]!!.isBlank())
                return listOf()

            val originalSync = sync.content
            if (match.children[2]!!.isNotBlank())
                sync.content = sync.content.replaceRange(match.children[2]!!.range(), "")

            val argOrParamNodes = getArgOrParamNodes(match.children[2] as Node)
            val expectedLength = chanInfo?.numParameters ?: 0
            if (argOrParamNodes.size != expectedLength)
                return errors.plus(
                    createUppaalError(
                    syncPath, originalSync, match.children[0]!!.range(), "'$channelName' expects '$expectedLength' arguments/parameters. Got '${argOrParamNodes.size}'.")
                )
            if (errors.size > 0 || expectedLength == 0)
                return errors

            val isEmit = match.children[3]!!.toString() == "!"
            if (isEmit)
            {
                val arguments = extractChanArgs(syncPath, errors, argOrParamNodes, originalSync, match.children[0]!!)
                if (arguments.any { it == null })
                    return errors

                val originalUpdate = update.content
                update.content = arguments.withIndex().joinToString(
                    separator = ", ",
                    transform = { (index, expr) -> "__${channelName}_p${index+1} = ${expr!!}" }
                )
                if (originalUpdate.isNotBlank())
                    update.content += ",\n$originalUpdate"
            }
            else
            {
                val parameters = extractChanParams(syncPath, errors, argOrParamNodes, originalSync, match.children[0]!!)
                if (parameters.any { it == null })
                    return errors

                val originalUpdate = update.content
                val (metaParams, nonMetaParams) = parameters.filterNotNull().withIndex().partition { it.value.first }

                update.content = nonMetaParams.joinToString(
                    separator = ", ",
                    transform = { (index, param) -> "${param.second} = __${channelName}_p${index+1}" }
                )

                if (originalUpdate.isNotBlank())
                {
                    var metaMappedUpdate = originalUpdate
                    val updateTree = ConfreHelper.expressionAssignmentListConfre.matchExact(metaMappedUpdate)
                        ?: return errors.plus(createUppaalError(updatePath, originalUpdate, (0..originalUpdate.length+1), "PaCha mapper could not parse this update label", true))

                    var offset = 0
                    for (leaf in updateTree.preOrderWalk().mapNotNull { it as? Leaf }.filter { it.isNotBlank() })
                    {
                        val param = metaParams.find { it.value.second == leaf.token!!.value } ?: continue
                        val range = (leaf.startPosition()+offset..leaf.endPosition()+offset)
                        val replacement = "__${channelName}_p${param.index+1}"

                        metaMappedUpdate = metaMappedUpdate.replaceRange(range, replacement)
                        offset += replacement.length - leaf.length()
                    }

                    update.content = listOf(update.content, metaMappedUpdate).filter { it.isNotBlank() }.joinToString(",\n")
                }
            }

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

        private fun extractChanArgs(syncPath: List<PathNode>, errors: ArrayList<UppaalError>, argList: List<Node>, originalSync: String, nameNode: ParseTree): List<String?>
        {
            var blankArgument = false
            val args = ArrayList<String?>()
            for (arg in argList)
                if (arg.children[0]!!.isNotBlank()) {
                    errors.add(createUppaalError(syncPath, originalSync, arg.children[0]!!, "Cannot use 'meta' when emitting on a channel. Only expressions are allowed when emitting.", true))
                    args.add(null)
                }
                else if (arg.children[1]!!.isNotBlank())
                    args.add(originalSync.substring(arg.children[1]!!.range()))
                else
                {
                    if (!blankArgument)
                        errors.add(createUppaalError(syncPath, originalSync, nameNode, "One or more blank arguments.", true))
                    blankArgument = true
                    args.add(null)
                }

            return args
        }

        private fun extractChanParams(syncPath: List<PathNode>, errors: ArrayList<UppaalError>, paramList: List<Node>, originalSync: String, nameNode: ParseTree): List<Pair<Boolean, String>?>
        {
            var blankParameter = false
            val params = ArrayList<Pair<Boolean, String>?>()
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
                    params.add(Pair(isMeta, paramName))
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


        private fun mapTemplateInstantiations(path: List<PathNode>, system: String): Pair<String, List<UppaalError>>
        {
            val globalPaChas = paChaMaps[null]!!
            val errors = ArrayList<UppaalError>()

            var newSystem = system
            var offset = 0
            var currentPartialInstantiationIndex = -1
            for (instanceTree in ConfreHelper.partialInstantiationConfre.findAll(system).map { it as Node })
            {
                ++currentPartialInstantiationIndex
                val rhsTemplateName = instanceTree.children[3]!!.toString()
                val rhsTemplatePaChas = paChaMaps[rhsTemplateName]!!

                // Compute PaChaMap for lhs of partial instantiation
                val lhsName = instanceTree.children[0]!!.toString()
                val parameterNode = instanceTree.children[1] as Node
                val lhsPaChas = PaChaMap()
                paChaMaps[lhsName] = lhsPaChas
                if (parameterNode.isNotBlank()) {
                    val (newNewSystem, newErrors) = mapPaChaDeclarations(path, newSystem, lhsPaChas, currentPartialInstantiationIndex)
                    offset += newNewSystem.length - newSystem.length
                    newSystem = newNewSystem
                    errors.addAll(newErrors)
                }

                // Find all non-null arguments that correspond (wrt. location) to a (parameterized) channel parameter.
                val rhsParamPaChas = rhsTemplatePaChas.values.filter { it.parameterIndex != null }
                if (rhsParamPaChas.isEmpty())
                    continue

                val arguments = getExpressionNodes(instanceTree.children[5] as Node)
                val argsAndParams = arguments.withIndex()
                    .map { arg -> Pair(arg.value, rhsParamPaChas.find { param -> arg.index == param.parameterIndex }) }
                    .filter { it.first != null && it.second != null }
                    .map { Pair(it.first!!, it.second!!) }

                for (argAndParam in argsAndParams)
                {
                    val argChannelName = argAndParam.first.toString().split(' ', limit = 3)[1]
                    val argPaCha = globalPaChas[argChannelName] ?: lhsPaChas[argChannelName]
                    if (argPaCha == null) {
                        errors.add(createUppaalError(path, system, argAndParam.first, "'$argChannelName' is not a parameterized channel (or channel-array).", true))
                        continue
                    }

                    val argHasSubscript = argAndParam.first.toString().split(' ', limit = 4)[2] == "["
                    val argInputNumDimensions = if (argHasSubscript) 0 else argPaCha.numDimensions

                    val paramPaCha = argAndParam.second
                    val newErrors = ArrayList<UppaalError>()
                    if (argPaCha.numParameters != paramPaCha.numParameters)
                        newErrors.add(createUppaalError(path, system, argAndParam.first, "'$argChannelName' has '${argPaCha.numParameters}' parameter(s), but '${paramPaCha.numParameters}' parameter(s) is/are required.", true))
                    if (argInputNumDimensions != paramPaCha.numDimensions)
                        newErrors.add(createUppaalError(path, system, argAndParam.first, "Argument with '$argChannelName' results in '${argInputNumDimensions}' dimensions(s), but '${paramPaCha.numDimensions}' dimensions(s) is/are required.", true))

                    errors.addAll(newErrors)
                    if (newErrors.isNotEmpty())
                        continue

                    val insertPosition = argAndParam.first.endPosition() + 1 + offset
                    val argsToInsert = List(argPaCha.numParameters) { ", __${argChannelName}_p${it+1}" }.joinToString(separator = "")
                    newSystem = newSystem.replaceRange(insertPosition, insertPosition, argsToInsert)
                    offset += argsToInsert.length
                }
            }

            return Pair(newSystem, errors)
        }

        private fun getExpressionNodes(expressionListNode: Node): List<Node?>
        {
            if (expressionListNode.isBlank())
                return listOf()
            return getOptionalNodes(expressionListNode)
        }

        private fun getParameterNodes(parameterListNode: Node): List<Node?>
        {
            if (parameterListNode.isBlank() || parameterListNode.children[1]!!.isBlank())
                return listOf()
            val trueListNode = (parameterListNode.children[1]!! as Node).children[0] as Node
            return getOptionalNodes(trueListNode)
        }

        private fun getOptionalNodes(optionalNodeListNode: Node): List<Node?> {
            val optionalNodes =
                arrayListOf(optionalNodeListNode.children[0] as Node)
                    .plus(
                        (optionalNodeListNode.children[1] as Node).children.map { (it as Node).children[1] as Node }
                    )

            return optionalNodes.map {
                if (it.isBlank()) null
                else it.children[0] as Node
            }
        }


        override fun mapModelErrors(errors: List<UppaalError>): List<UppaalError> {
            for (error in errors.filter { it.fromEngine }) {
                val linesAndColumns = Quadruple(error.beginLine, error.beginColumn, error.endLine, error.endColumn)
                val backMap = backMaps[linesAndColumns] ?: continue
                if (backMap.second == PARAMETER_TYPE_HINT) {
                    error.beginLine = backMap.first.first
                    error.beginColumn = backMap.first.second
                    error.endLine = backMap.first.third
                    error.endColumn = backMap.first.fourth
                    error.message = "Unknown type name in channel parameter."
                }
                else
                    throw Exception("Unhandled error type in PaChaMapper: ${backMap.second}")
            }
            return errors
        }
    }
}