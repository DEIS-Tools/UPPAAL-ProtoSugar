package engine.mapping.secomp

import engine.mapping.*
import engine.parsing.Confre
import engine.parsing.ConfreHelper
import engine.parsing.Node
import uppaal_pojo.*
import java.util.Stack


const val SUB_TEMPLATE_NAME_PREFIX = "__"
const val SUB_TEMPLATE_USAGE_CLAMP = "::"

class SeCompMapper : Mapper {
    private data class SubTemplateInfo(val entryStateId: String?, val exitStateIds: List<String>, var isFaulty: Boolean = false)
    private data class SubTemplateUsage(val insertLocationId: String, val subTemplateName: String, val subTemInstanceName: String, val originalText: String)

    // Related to a template or partial instantiation. If the name is used on the "system"-line, how many instances of "baseTemplate" will be made?
    // baseSubTemplateUserName = 'null': not part of partial instantiation, 'not null' = part of partial instantiation
    // parameters: null-pair in list means non-free parameter.
    private data class FreeInstantiation(val baseTemplateName: String?, val parameters: List<Triple<Int, Int, String>?>)

    override fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?> {
        val subTemplates = HashMap<String, SubTemplateInfo>()                       // String = Sub-template name
        val baseSubTemplateUsers = HashMap<String, MutableList<SubTemplateUsage>>() // String = Any-template name
        val numSubTemplateUsers = HashMap<String, FreeInstantiation>()              // String = Any-template or partial instantiation name
        val systemLine = ArrayList<String>()  // system process1, process2, ..., process_n

        return Pair(
            sequenceOf(
                IndexingPhase(subTemplates, baseSubTemplateUsers, numSubTemplateUsers, systemLine),
                ReferenceCheckingPhase(subTemplates, baseSubTemplateUsers),
                MappingPhase(subTemplates, baseSubTemplateUsers, numSubTemplateUsers, systemLine)
            ),
            SeCompQueryPhase()
        )
    }

    private class IndexingPhase(
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        val numSubTemplateUsers: HashMap<String, FreeInstantiation>,
        val systemLine: ArrayList<String>
    ) : ModelPhase() {

        private val freelyInstantiableTypedefGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = [0-9]+
            
            Typedef :== 'typedef' ('int' '[' (INT | IDENT) ',' (INT | IDENT) ']' | 'scalar' '[' (INT | IDENT) ']' | IDENT) IDENT .
        """.trimIndent())

        private val freelyInstantiableParameterGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = [0-9]+
            
            Param :== ['const'] ('int' '[' (INT | IDENT) ',' (INT | IDENT) ']' | 'scalar' '[' (INT | IDENT) ']' | IDENT) IDENT .
        """.trimIndent())

        private val freeTypedefs = HashMap<String, Pair<Int, Int>?>() // 'not null' = int range, 'null' = scalar range
        private val constInts = HashMap<String, Int>() // Maps typedefs to scalar or int ranges


        init {
            register(::mapGlobalDeclaration, listOf(Nta::class.java))
            register(::mapTemplate)
            register(::mapSystem)
        }


        @Suppress("UNUSED_PARAMETER")
        private fun mapGlobalDeclaration(path: List<PathNode>, declaration: Declaration): List<UppaalError> {
            registerTypedefsAndConstants(declaration.content)
            return listOf()
        }

        private fun mapTemplate(path: List<PathNode>, template: Template): List<UppaalError> {
            val temName = template.name.content ?: return listOf(createUppaalError(
                path, "", IntRange(0,0), "Template has no name.", true
            ))

            val errors = ArrayList<UppaalError>()
            if (temName.startsWith(SUB_TEMPLATE_NAME_PREFIX))
            {
                val entryId = template.init?.ref // No init handled by UPPAAL
                val exitIds = template.locations
                    .filter { loc -> template.transitions.all { tran -> tran.source.ref != loc.id } }
                    .map { loc -> loc.id }

                if (exitIds.isEmpty())
                    errors.add(createUppaalError(
                        path, "A sub-template must have at least one exit location (location with no outgoing transitions)."
                    ))

                if (template.transitions.none { it.source.ref == entryId })
                    errors.add(createUppaalError(
                        path, "The entry state of a sub-template must have at least one outgoing transition."
                    ))

                // TODO: Relax this constraint later
                if (template.parameter != null && template.parameter!!.content.isNotBlank())
                    errors.add(createUppaalError(
                        path, template.parameter!!.content, IntRange(0,template.parameter!!.content.length - 1), "Sub-templates currently do not support parameters."
                    ))

                subTemplates[temName] = SubTemplateInfo(entryId, exitIds, isFaulty = template.init?.ref == null || errors.isNotEmpty())
            }

            // Uses sub-template(s)
            val subTemplateLocations = template.locations.withIndex().filter {
                it.value.name?.content?.trim()?.startsWith(SUB_TEMPLATE_USAGE_CLAMP) ?: false
                && it.value.name?.content?.trim()?.endsWith(SUB_TEMPLATE_USAGE_CLAMP) ?: false
            }
            val subTemplateUsages = ArrayList<SubTemplateUsage>()
            for (locAndIndex in subTemplateLocations)
            {
                val locPath = path.plus(PathNode(locAndIndex.value, locAndIndex.index))
                val locName = locAndIndex.value.name!!.content!!
                val nameAndInstance = locName
                    .trim().drop(SUB_TEMPLATE_USAGE_CLAMP.length).dropLast(SUB_TEMPLATE_USAGE_CLAMP.length)
                    .trim().split(Regex("""\s+""")).toMutableList()
                if (!nameAndInstance[0].startsWith(SUB_TEMPLATE_NAME_PREFIX))
                    nameAndInstance[0] = SUB_TEMPLATE_NAME_PREFIX + nameAndInstance[0]

                if (nameAndInstance.size != 2)
                {
                    errors.add(createUppaalError(
                        locPath.plus(PathNode(locAndIndex.value.name!!)), locName, IntRange(0, locName.length-1), "To insert a sub-template, format the location's name as: \"::[sub-template name] [instantiated name]::\"."
                    ))
                    locAndIndex.value.name!!.content = "" // Prevent "invalid identifier" error from UPPAAL
                }
                else
                {
                    val locId = locAndIndex.value.id
                    if (template.init?.ref == locId)
                        errors.add(createUppaalError(
                            locPath, "", IntRange(0,0), "The initial/entry location cannot be a sub-template instance."
                        ))
                    if (template.transitions.none { it.target.ref == locId } && subTemplates.containsKey(locName))
                        errors.add(createUppaalError(
                            locPath, "", IntRange(0,0), "The exit location cannot be a sub-template instance."
                        ))

                    if (errors.isEmpty()) {
                        subTemplateUsages.add(SubTemplateUsage(locId, nameAndInstance[0], nameAndInstance[1], locAndIndex.value.name!!.content!!))
                        locAndIndex.value.name!!.content = nameAndInstance[1]
                    }
                    else
                        locAndIndex.value.name!!.content = "" // Prevent "invalid identifier" error from UPPAAL
                }
            }

            // Register sub-template user and try to determine the number of instances based on "free parameters"
            if (subTemplateUsages.isNotEmpty())
            {
                baseSubTemplateUsers[temName] = subTemplateUsages

                val parameters = template.parameter?.content
                if (subTemplates.containsKey(temName)) // Sub-templates can only be used in generated code
                    numSubTemplateUsers[temName] = FreeInstantiation(null, List(getParameters(parameters).size) { null })
                else if (parameters.isNullOrBlank())
                    numSubTemplateUsers[temName] = FreeInstantiation(null, listOf())
                else {
                    val parameterRanges = getParameterRanges(parameters, path.plus(PathNode(template.parameter!!)), parameters, parameters.indices, errors)
                    numSubTemplateUsers[temName] = FreeInstantiation(null, parameterRanges)
                }
            }

            if (subTemplates.containsKey(temName) && errors.isNotEmpty())
                subTemplates[temName]?.isFaulty = true
            return errors
        }

        private fun mapSystem(path: List<PathNode>, system: System): List<UppaalError> {
            registerTypedefsAndConstants(system.content)

            val errors = ArrayList<UppaalError>()
            for (tree in ConfreHelper.partialInstantiationConfre.findAll(system.content).map { it as Node })
            {
                val baseName = tree.children[3]!!.toString()
                if (subTemplates.containsKey(baseName))
                    errors.add(createUppaalError(
                        path, system.content, tree.children[3]!!.range(), "Sub-templates cannot be instantiated by user-code.", true
                    ))
                else if (numSubTemplateUsers.containsKey(baseName)) {
                    val partialName = tree.children[0]!!.toString()
                    if (tree.children[1]?.isNotBlank() == true) {
                        val parameters = system.content.substring(tree.children[1]!!.range()).drop(1).dropLast(1)
                        val parameterRanges = getParameterRanges(parameters, path, system.content, tree.children[1]!!.range(), errors)
                        numSubTemplateUsers[partialName] = FreeInstantiation(baseName, parameterRanges)
                    }
                    else
                        numSubTemplateUsers[partialName] = FreeInstantiation(baseName, listOf())
                }
            }

            val systemLineNode = ConfreHelper.systemLineConfre.find(system.content) as? Node
            if (systemLineNode != null)
            {
                val identNodes = listOf(systemLineNode.children[1]!!).plus(
                    (systemLineNode.children[2] as Node).children.map { (it as Node).children[1]!! }
                )
                // Loop does not handle "cannot determine total number of instances" since UPPAAL will notify about this by itself.
                for (nameNode in identNodes.filter { subTemplates.containsKey(it.toString()) })
                    errors.add(createUppaalError(
                        path, system.content, nameNode, "Sub-templates cannot be instantiated by user-code.", true
                    ))
                systemLine.addAll(identNodes.map { it.toString() }.filter { numSubTemplateUsers.containsKey(it) })
            }

            return errors
        }


        private fun getParameterRanges(parameters: String?, parameterPath: List<PathNode>, fullText: String, trueRange: IntRange, errors: ArrayList<UppaalError>): ArrayList<Triple<Int, Int, String>?> {
            val parameterRanges = ArrayList<Triple<Int, Int, String>?>()
            for (param in getParameters(parameters).withIndex()) {
                val paramTree = freelyInstantiableParameterGrammar.matchExact(param.value) as? Node
                if (paramTree == null) {
                    parameterRanges.add(null)
                    continue
                }

                val type = paramTree.children[1].toString()
                val name = paramTree.children[2]!!.toString()
                if (type.startsWith("int") || freeTypedefs[type] != null) {
                    // Get free-parameter-range from typedef or determine from type
                    val range = freeTypedefs[type] ?: getRangeFromBoundedIntType(type)
                    if (range == null)
                        parameterRanges.add(null)
                    else
                        parameterRanges.add(Triple(range.first, range.second, name))
                }
                else if (type.startsWith("scalar") || (freeTypedefs.containsKey(type) && freeTypedefs[type] == null)) { // 'null' in freeTypedefs means 'is scalar'
                    errors.add(createUppaalError(
                        parameterPath, fullText, trueRange, "Users of sub-templates cannot have scalar parameters."
                    ))
                    parameterRanges.add(null)
                }
                else
                    parameterRanges.add(null)
            }
            return parameterRanges
        }

        private fun getRangeFromBoundedIntType(type: String): Pair<Int, Int>? {
            return type.substringAfter('[')
                .dropLast(1)
                .split(',')
                .map { it.trim() }
                .let { Pair(
                    it[0].toIntOrNull() ?: constInts[it[0]] ?: return null,
                    it[1].toIntOrNull() ?: constInts[it[1]] ?: return null
                ) }
        }

        private fun getParameters(parameters: String?): List<String> {
            if (parameters.isNullOrBlank())
                return listOf()

            val chars = parameters.iterator()
            val blockStack = Stack<Char>()
            val params = ArrayList<String>()

            var startIndex = 0
            var currentIndex = -1
            do {
                currentIndex++
                val current = chars.next()
                when (current) {
                    '(' -> blockStack.push(')')
                    '[' -> blockStack.push(']')
                    '{' -> blockStack.push('}')
                }

                if (blockStack.isEmpty() && current == ',') {
                    params.add(parameters.substring((startIndex until currentIndex))) // Until to leave ','
                    startIndex = currentIndex + 1
                }
                else if (blockStack.isNotEmpty() && current == blockStack.peek())
                    blockStack.pop()


            } while (chars.hasNext())

            params.add(parameters.substring((startIndex..currentIndex)))
            return params
        }


        private fun registerTypedefsAndConstants(code: String) {
            val constants = ConfreHelper.constIntConfre.findAll(code).map { Pair("const", it as Node) }
            val typedefs = freelyInstantiableTypedefGrammar.findAll(code).map { Pair("typedef", it as Node) }

            for (pair in constants.plus(typedefs).sortedBy { it.second.startPosition() })
                if (pair.first == "typedef") {
                    val definition = pair.second.children[1].toString()
                    val name = pair.second.children[2].toString()
                    if (freeTypedefs.containsKey(definition))
                        freeTypedefs[name] = freeTypedefs[definition]
                    else if (definition.startsWith("scalar"))
                        freeTypedefs[name] = null
                    else if (definition.startsWith("int"))
                        getRangeFromBoundedIntType(definition)?.let { freeTypedefs[name] = it }
                }
                else
                    constInts[pair.second.children[3].toString()] = code.substring(pair.second.children[5]!!.range()).toIntOrNull() ?: continue
        }


        override fun mapModelErrors(errors: List<UppaalError>) = errors // TODO: Relocate errors on location names

        override fun mapProcesses(processes: List<ProcessInfo>) { }
    }

    private class ReferenceCheckingPhase(
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>
    ) : ModelPhase() {
        init {
            register(::mapTemplate)
        }


        private fun mapTemplate(path: List<PathNode>, template: Template): List<UppaalError> {
            val errors = ArrayList<UppaalError>()
            val templateName = template.name.content ?: return listOf()
            val subTemUsages = baseSubTemplateUsers[templateName] ?: return listOf()

            for (usage in subTemUsages)
                if (usage.subTemplateName !in subTemplates.keys) {
                    val guiltyLocation = template.locations.withIndex().find { it.value.id == usage.insertLocationId }!!
                    val locationPath = path.plus(PathNode(guiltyLocation.value, guiltyLocation.index+1))
                    errors.add(createUppaalError(
                        locationPath, usage.originalText, usage.originalText.indices, "The template name '${usage.subTemplateName}' either does not exist or is not a sub-template.", true
                    ))
                }

            return errors + checkCircularUse(path, listOf(templateName))
        }

        private fun checkCircularUse(path: List<PathNode>, branch: List<String>): List<UppaalError> {
            if (branch.size != branch.distinct().size)
                return if (branch.last() == branch.first()) // If cycle pertains to root template
                    listOf(createUppaalError(
                        path, "Cyclic sub-template usage: ${branch.joinToString(" -> ") { "'${it}'" }}.", true
                    ))
                else
                    listOf() // If cycle does not pertain to root template, wait for that template's check to come

            val usages = baseSubTemplateUsers[branch.last()] ?: return listOf() // If not a sub-template user, there's nothing more to check
            return usages.flatMap { checkCircularUse(path, branch + it.subTemplateName) }
        }


        override fun mapModelErrors(errors: List<UppaalError>) = errors

        override fun mapProcesses(processes: List<ProcessInfo>) { }
    }

    private class MappingPhase(
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        val numSubTemplateUsers: HashMap<String, FreeInstantiation>,
        val systemLine: ArrayList<String>
    ) : ModelPhase() {
        val rootTemplateToPartials = HashMap<String, ArrayList<String>>() // Root template -> Partial instantiation
        val totalNumInstances = HashMap<String, Int>() // Any-template name -> instance count

        val partialToRootStartIndex = HashMap<String, Int>() // partial (or root) instance name -> start root instance index
        val subTemToParent = HashMap<Pair<String, Int>, Triple<String, Int, Int>>() // subTem:name+instance -> parentTem:name + instance + subTemIndex

        init {
            register(::mapGlobalDeclaration, prefix = listOf(Nta::class.java))
            register(::mapTemplate)
            register(::mapSystem)
        }


        @Suppress("UNUSED_PARAMETER")
        private fun mapGlobalDeclaration(path: List<PathNode>, declaration: Declaration): List<UppaalError> {
            findTotalNumbersOfInstances()

            val nextSubTemIndex = HashMap<String, Int>()
            val stateVariables = StringBuilder("\n\n")
            for (template in baseSubTemplateUsers.keys.plus(subTemplates.keys).distinct()) {
                stateVariables.appendLine("// $template")
                stateVariables.appendLine("const int NUM_$template = ${totalNumInstances[template]!!};")

                if (subTemplates.containsKey(template))
                    stateVariables.appendLine("bool STEM_ACTIVE_$template[NUM_$template] = { ${List(totalNumInstances[template]!!) { "false" }.joinToString()} }; ")

                if (baseSubTemplateUsers.containsKey(template)) {
                    val usages = baseSubTemplateUsers[template]!!
                    stateVariables.append("const int NUM_STEM_$template = ${usages.size};")
                    stateVariables.appendLine(" // ${usages.joinToString { it.subTemplateName }}")

                    stateVariables.append("const int STEM_CONNECT_$template[NUM_$template][NUM_STEM_$template] = { ")
                    stateVariables.append((0 until totalNumInstances[template]!!).joinToString { userIndex ->
                        usages.withIndex().map { usage ->
                            getAndIncrementNextSubTemIndex(
                                template,
                                usage.value.subTemplateName,
                                usage.index,
                                userIndex,
                                nextSubTemIndex
                            )
                        }.joinToString().let { "{ $it }" }
                    })
                    stateVariables.appendLine(" };")
                }
                stateVariables.appendLine()
            }

            declaration.content += stateVariables.toString()
            return listOf()
        }

        private fun getAndIncrementNextSubTemIndex(userTemName: String, subTemName: String, subTemIndexInUser: Int, nextUserTemIndex: Int, nextSubTemIndices: HashMap<String, Int>): Int {
            val nextSubTemIndex = nextSubTemIndices[subTemName] ?: 0
            nextSubTemIndices[subTemName] = nextSubTemIndex + 1

            subTemToParent[Pair(subTemName, nextSubTemIndex)] = Triple(userTemName, nextUserTemIndex, subTemIndexInUser)

            return nextSubTemIndex
        }

        private fun findTotalNumbersOfInstances()
        {
            // Find the number of non-sub-template instances
            val instantiatedTemplates = ArrayList(systemLine)
            while (instantiatedTemplates.isNotEmpty()) {
                val currentTemplateName = instantiatedTemplates.removeFirst()
                val rootTemplateName = getRootTemplate(currentTemplateName)
                val instantiationInfo = numSubTemplateUsers[currentTemplateName] ?: continue
                if (null in instantiationInfo.parameters)
                    continue

                val totalInstancesOfCurrent = instantiationInfo.parameters
                    .map { it!!.second - it.first + 1 }
                    .product()
                totalNumInstances[currentTemplateName] = (totalNumInstances[currentTemplateName] ?: 0) + totalInstancesOfCurrent

                if (currentTemplateName != rootTemplateName) {
                    // Keep both the number of top-level and root templates to determine template-mappings as well as numbers of state variables in future
                    totalNumInstances[rootTemplateName] = (totalNumInstances[rootTemplateName] ?: 0) + totalInstancesOfCurrent

                    // Track the root template of all partial instantiations to determine template-mappings in future
                    rootTemplateToPartials.getOrPut(rootTemplateName) { ArrayList() }.add(currentTemplateName)
                }
            }

            // Find the number of sub-template instances based on number of root template instances
            for (rootTemplateName in rootTemplateToPartials.keys)
                for (subTemplateCount in getSubTemplateInstanceCountForOneRootTemplate(rootTemplateName))
                    totalNumInstances[subTemplateCount.key] =
                        (totalNumInstances[subTemplateCount.key] ?: 0) + (subTemplateCount.value * totalNumInstances[rootTemplateName]!!)
        }

        private fun List<Int>.product(): Int
            = this.fold(1) { acc, value -> acc * value }

        private fun getSubTemplateInstanceCountForOneRootTemplate(templateName: String, subTemInstances: HashMap<String, Int> = HashMap()): HashMap<String, Int> {
            val subTemplateUsage = baseSubTemplateUsers[templateName] ?: return subTemInstances
            for (usage in subTemplateUsage) {
                subTemInstances[usage.subTemplateName] = (subTemInstances[usage.subTemplateName] ?: 0) + 1
                getSubTemplateInstanceCountForOneRootTemplate(usage.subTemplateName, subTemInstances)
            }
            return subTemInstances
        }


        @Suppress("UNUSED_PARAMETER")
        private fun mapTemplate(path: List<PathNode>, template: Template): List<UppaalError> {
            val subTemplateInfo = subTemplates[template.name.content!!]
            if (subTemplateInfo != null && !subTemplateInfo.isFaulty)
                mapSubTemplate(template, subTemplateInfo)

            val subTemplateUsages = baseSubTemplateUsers[template.name.content!!]
            if (subTemplateUsages != null)
                mapSubTemplateUser(template, subTemplateUsages)

            return listOf()
        }

        private fun mapSubTemplate(template: Template, subTemplateInfo: SubTemplateInfo) {
            val entryTransitions = template.transitions.filter { it.source.ref == subTemplateInfo.entryStateId }
            for (entryTransition in entryTransitions) {
                val guardLabel = entryTransition.labels.find { it.kind == "guard" }
                    ?: generateAndAddLabel("guard", template, entryTransition)
                if (guardLabel.content.isBlank())
                    guardLabel.content = "STEM_ACTIVE_${template.name.content!!}[STEM_INDEX]"
                else
                    guardLabel.content = "(${guardLabel.content}) && STEM_ACTIVE_${template.name.content!!}[STEM_INDEX]"
            }

            val exitTransitions = template.transitions.filter { it.target.ref in subTemplateInfo.exitStateIds }
            for (exitTransition in exitTransitions) {
                val updateLabel = exitTransition.labels.find { it.kind == "assignment" }
                    ?: generateAndAddLabel("assignment", template, exitTransition)
                if (updateLabel.content.isBlank())
                    updateLabel.content = "STEM_ACTIVE_${template.name.content!!}[STEM_INDEX] = false"
                else
                    updateLabel.content += ", STEM_ACTIVE_${template.name.content!!}[STEM_INDEX] = false"

                exitTransition.target.ref = subTemplateInfo.entryStateId!!
            }

            if (template.parameter == null)
                template.parameter = Parameter("")
            if (template.parameter!!.content.isBlank())
                template.parameter!!.content = "const int STEM_INDEX"
            else
                template.parameter!!.content += ", const int STEM_INDEX"
        }

        private fun mapSubTemplateUser(template: Template, subTemplateUsages: MutableList<SubTemplateUsage>) {
            for (usageAndIndex in subTemplateUsages.withIndex()) {
                val stemIndexExpr = "STEM_CONNECT_${template.name.content!!}[USER_INDEX][${usageAndIndex.index}]"
                val usage = usageAndIndex.value
                for (outgoing in template.transitions.filter { it.source.ref == usage.insertLocationId }) {
                    val guardLabel = outgoing.labels.find { it.kind == "guard" }
                        ?: generateAndAddLabel("guard", template, outgoing)
                    if (guardLabel.content.isBlank())
                        guardLabel.content = "!STEM_ACTIVE_${usage.subTemplateName}[$stemIndexExpr]"
                    else
                        guardLabel.content = "(${guardLabel.content}) && !STEM_ACTIVE_${usage.subTemplateName}[$stemIndexExpr]"
                }

                for (ingoing in template.transitions.filter { it.target.ref == usage.insertLocationId }) {
                    val updateLabel = ingoing.labels.find { it.kind == "assignment" }
                        ?: generateAndAddLabel("assignment", template, ingoing)
                    if (updateLabel.content.isBlank())
                        updateLabel.content = "STEM_ACTIVE_${usage.subTemplateName}[$stemIndexExpr] = true"
                    else
                        updateLabel.content += ", STEM_ACTIVE_${usage.subTemplateName}[$stemIndexExpr] = true"
                }
            }

            if (template.parameter == null)
                template.parameter = Parameter("")
            if (template.parameter!!.content.isBlank())
                template.parameter!!.content = "const int USER_INDEX"
            else
                template.parameter!!.content += ", const int USER_INDEX"
        }

        private fun generateAndAddLabel(kind: String, template: Template, transition: Transition): Label {
            val sourceLoc = template.locations.find { it.id == transition.source.ref }!!
            val targetLoc = template.locations.find { it.id == transition.target.ref }!!
            val label = Label(kind, (sourceLoc.x + targetLoc.x) / 2, (sourceLoc.y + targetLoc.y) / 2)
            transition.labels.add(label)
            return label
        }


        private fun mapSystem(path: List<PathNode>, system: System): List<UppaalError> {
            var newSystem = system.content
            val systemLineNode = ConfreHelper.systemLineConfre.find(system.content) as? Node
                ?: return listOf(createUppaalError(path, system.content, IntRange(system.content.length, system.content.length), "Missing 'system' line at end of system declarations.", true))

            // Map all partial instantiations
            val nextRootIndices = HashMap<String, Int>() // root name -> next base index
            var offset = 0
            for (tree in ConfreHelper.partialInstantiationConfre.findAll(system.content).map { it as Node }) {
                val partialName = tree.children[0]!!.toString()
                val instantiationInfo = numSubTemplateUsers[partialName] ?: continue

                val rootTemplateName = getRootTemplate(partialName)
                val nextRootIndex = nextRootIndices.getOrPut(rootTemplateName) { 0 }

                val isBaseOfOtherPartial = numSubTemplateUsers.any { it.value.baseTemplateName == partialName }
                val isDirectlyInstantiated = systemLine.contains(partialName)

                // If this is the base of another partial instantiation, add pass-through parameter for USER_INDEX at lhs and use that as input to rhs
                // Otherwise, generate actual USER_INDEX and pass to base on last parameter
                if (isBaseOfOtherPartial) {
                    if (isDirectlyInstantiated) {
                        return listOf(createUppaalError(
                            path, system.content, tree, "The mapper still does not support using a template or partial instantiation on the system-line while also being the base of a/another partial instantiation.", true
                        ))

                        // TODO: Create a new partial instantiation to be used in place of the current on the system line

                        // TODO: Update 'partialToRootStartIndex' and 'nextRootIndices'
                    }

                    // TODO: Insert parameter-list if none exist
                    val indexAfterLastLhsParameter = (tree.children[1]?.endPosition() ?: continue) + offset // Index of end-parenthesis
                    val lhsHasParams = tree.children[1]!!.asNode().children[1]!!.isNotBlank()
                    val passThroughParam = (if (lhsHasParams) ", " else "") + "const int USER_INDEX"

                    val indexAfterLastRhsParameter = (tree.children[6]!!.endPosition()) + offset // Index of end-parenthesis
                    val rhsHasArgs = tree.children[5]!!.isNotBlank()
                    val passThroughArg = (if (rhsHasArgs) ", " else "") + "USER_INDEX"

                    newSystem = newSystem // By inserting rhs before lhs, lsh offset still fits and updating offset can be deferred
                        .replaceRange(indexAfterLastRhsParameter, indexAfterLastRhsParameter, passThroughArg)
                        .replaceRange(indexAfterLastLhsParameter, indexAfterLastLhsParameter, passThroughParam)
                    offset += passThroughParam.length + passThroughArg.length
                    // Do NOT update 'partialToRootStartIndex' and 'nextRootIndices' here
                }
                else {
                    var argumentToAdd = ", $nextRootIndex"
                    val parameterRanges = instantiationInfo.parameters
                    if (!parameterRanges.contains(null) && parameterRanges.isNotEmpty()) {
                        val rangeLengths = parameterRanges.map { it!!.second - it.first + 1 }
                        val parameterNames = parameterRanges.map { it!!.third }

                        val rangeFlatteningTerms = parameterNames.dropLast(1).zip(rangeLengths.drop(1))
                        val finalTerms = rangeFlatteningTerms.map{ "${it.first}*${it.second}" }
                            .plus(parameterNames.last())
                            .plus(nextRootIndex.toString())

                        argumentToAdd = ", ${finalTerms.joinToString(" + ")}"
                    }

                    val indexAfterLastRhsParameter = (tree.children[6]?.endPosition() ?: continue) + offset // Index of end-parenthesis
                    newSystem = newSystem.replaceRange(indexAfterLastRhsParameter, indexAfterLastRhsParameter, argumentToAdd)

                    offset += argumentToAdd.length

                    // Add indexing
                    partialToRootStartIndex[partialName] = nextRootIndices[rootTemplateName]!!
                    nextRootIndices[rootTemplateName] = nextRootIndices[rootTemplateName]!! + (totalNumInstances[partialName] ?: 0)
                }
            }


            // Generate partial instantiations for sub-templates (this may not work if sub-templates are to support user-code parameters)
            val newInstantiations = ArrayList<String>()
            var indexBeforeSystemLine = systemLineNode.startPosition() + offset
            for (subTem in subTemplates) {
                val partialName = subTem.key.drop(2)

                val isAlsoUser = baseSubTemplateUsers.containsKey(subTem.key)
                val instantiation =
                    if (isAlsoUser) "$partialName(const int[0, ${totalNumInstances[subTem.key]!! - 1}] STEM_AND_USER_INDEX) = ${subTem.key}(STEM_AND_USER_INDEX, STEM_AND_USER_INDEX);\n"
                    else "$partialName(const int[0, ${totalNumInstances[subTem.key]!! - 1}] STEM_INDEX) = ${subTem.key}(STEM_INDEX);\n"

                newSystem = newSystem.replaceRange(indexBeforeSystemLine, indexBeforeSystemLine, instantiation)

                indexBeforeSystemLine += instantiation.length
                offset += instantiation.length

                newInstantiations.add(partialName)
            }
            newSystem = newSystem.replaceRange(indexBeforeSystemLine, indexBeforeSystemLine, "\n")
            offset += 1

            // Map system line and add padding-partial-instantiations
            // TODO: Bubble up directly instantiated root templates
            // TODO: Relax below constraint later
            if (systemLine.any { baseSubTemplateUsers.containsKey(it) })
                return listOf(createUppaalError(
                    path, system.content, systemLineNode, "The mapper does not yet support directly instantiating a template that uses sub-templates.", true
                ))

            if (newInstantiations.isNotEmpty()) {
                val indexAfterSystemLine = systemLineNode.endPosition() + offset + 1 // +1 to compensate for inclusive end position
                system.content = newSystem.replaceRange(indexAfterSystemLine, indexAfterSystemLine, ", " + newInstantiations.joinToString())
            }

            return listOf()
        }


        private fun getRootTemplate(initialName: String): String {
            var rootName = initialName
            var instantiationInfo = numSubTemplateUsers[rootName]!!
            while (instantiationInfo.baseTemplateName != null) {
                rootName = instantiationInfo.baseTemplateName!!
                instantiationInfo = numSubTemplateUsers[rootName]!!
            }
            return rootName
        }


        override fun mapModelErrors(errors: List<UppaalError>): List<UppaalError> {
            return errors
        }


        override fun mapProcesses(processes: List<ProcessInfo>) {
            // TODO: Mappings to/from? auto-generated padding-partial-instantiation names?
            // TODO: Mappings for query phase

            val userTemplateAndIndexToName = HashMap<Pair<String, Int>, String>()
            val finishedMappings = HashSet<ProcessInfo>()
            while (finishedMappings.size != processes.size) {
                val nonPartialNextIndex = HashMap<String, Int>()
                for (process in processes) {
                    val currentIndex = nonPartialNextIndex.getOrPut(process.template) { 0 }
                    val currentTem = process.template
                    nonPartialNextIndex[currentTem] = nonPartialNextIndex[currentTem]!! + 1
                    if (finishedMappings.contains(process))
                        continue

                    val isSubTem = subTemplates.containsKey(currentTem)
                    val isUser = baseSubTemplateUsers.containsKey(process.template)

                    if (isSubTem) {
                        val subTemId = Pair(currentTem, currentIndex)
                        val parentUserInfo = subTemToParent[subTemId]!!
                        val parent = Pair(parentUserInfo.first, parentUserInfo.second)
                        if (userTemplateAndIndexToName.containsKey(parent)) {
                            val parentTem = parentUserInfo.first
                            val subTemUsageIndex = parentUserInfo.third
                            val subTemInstanceName = baseSubTemplateUsers[parentTem]!![subTemUsageIndex].subTemInstanceName
                            process.name = "${userTemplateAndIndexToName[parent]!!}.$subTemInstanceName"
                            if (isUser)
                                userTemplateAndIndexToName[subTemId] = process.name
                            finishedMappings.add(process)
                            continue
                        }
                    }
                    else if (isUser) {
                        val userId = Pair(process.template, currentIndex)
                        userTemplateAndIndexToName[userId] = process.name
                        finishedMappings.add(process)
                    }
                    else
                        finishedMappings.add(process)
                }
            }
        }
    }

    private class SeCompQueryPhase : QueryPhase() {
        override fun mapQuery(query: String): Pair<String, UppaalError?> {
            // TODO: Find a way to map SubTem end-state to some other conditions, since the end state is never actually reached
            // TODO: SubTemplates wait in start state, queries to such start states should only work when SubTem is active

            // TODO: Error if user queries a sub-template process directly??? (Must/should syntax go through root template?)

            return Pair(query, null)
        }

        override fun mapQueryError(error: UppaalError): UppaalError {
            return error
        }
    }
}