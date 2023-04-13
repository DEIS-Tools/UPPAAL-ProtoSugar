package mapping.impl

import tools.parsing.*
import ensureStartsWith
import mapping.*
import uppaal.messaging.*
import tools.restructuring.BackMapResult
import tools.restructuring.TextRewriter
import createOrGetRewriter
import mapping.base.*
import tools.restructuring.ActivationRule
import uppaal.messaging.UppaalMessage
import uppaal.messaging.UppaalMessageException
import uppaal.UppaalPath
import uppaal.messaging.createUppaalError
import uppaal.model.*
import offset
import product
import java.util.Stack


const val SUB_TEMPLATE_NAME_PREFIX = "__"
const val SUB_TEMPLATE_USAGE_CLAMP = "::"

class SeCompMapper : Mapper() {
    private data class LocationSummary(val id: String, val name: String?)
    private data class SubTemplateInfo(val entryLocation: LocationSummary?, val exitLocations: List<LocationSummary>, val subTemplateName: String, var isFaulty: Boolean = false)
    private data class SubTemplateUsage(val insertLocationId: String, val subTemplateName: String, val subTemInstanceName: String?, val originalLocationText: String)

    // Related to a template or partial instantiation. If the name is used on the "system"-line, how many instances of "baseTemplate" will be made?
    // baseSubTemplateUserName = 'null': not part of partial instantiation, 'not null' = part of partial instantiation
    // parameters: null-triple in list means non-free parameter.
    private data class FreeInstantiation(val baseTemplateName: String?, val parameters: List<FreeParameter?>)
    private data class FreeParameter(val lowerRange: Int, val upperRange: Int, val name: String)

    private data class InstanceSummary(val templateName: String, val stemOrUserIndex: Int)
    private data class ParentUserInfo(val parentTemplateName: String, val parentUserIndex: Int, val subTemplateIndexInParent: Int)
    private data class SubTemplateQueryMapInfo(
        val nativeSubProcessName: String, val subTemplateIndex: Int, val subTemplateInfo: SubTemplateInfo,
        val nativeParentProcessName: String, val parentInsertLocationName: String?
    )


    override fun buildPhases(): Phases {
        val freeTypedefs = HashMap<String, Pair<Int, Int>?>() // 'not null' = int range, 'null' = scalar range
        val constInts = HashMap<String, Int>()                // Maps typedefs to scalar or int ranges

        val subTemplates = HashMap<String, SubTemplateInfo>()                       // String = Sub-template name
        val baseSubTemplateUsers = HashMap<String, MutableList<SubTemplateUsage>>() // String = Any-template name
        val numSubTemplateUsers = HashMap<String, FreeInstantiation>()              // String = Any-template or partial instantiation name
        val subTemToParent = HashMap<InstanceSummary, ParentUserInfo>()             // Sub-template template name + index -> "parent template name", "parent user index", "index of the sub-template in its parent"
        val backMapOfBubbledUpProcesses = HashMap<String, String>()                 // New placeholder partial inst name -> original partial inst or root template name.
        val systemLine = LinkedHashMap<String, IntRange>()                          // system process1, ..., process_n + the ranges on which they reside in the "system declarations text".

        val subTemplateQueryMapInfo = HashMap<String, SubTemplateQueryMapInfo>()

        return Phases(
            listOf(
                IndexingPhase(freeTypedefs, constInts, subTemplates, baseSubTemplateUsers, numSubTemplateUsers, systemLine),
                ReferenceCheckingPhase(subTemplates, baseSubTemplateUsers),
                MappingPhase(subTemplates, baseSubTemplateUsers, numSubTemplateUsers, systemLine, subTemToParent, backMapOfBubbledUpProcesses)
            ),
            SeCompSimulatorPhase(subTemplates, baseSubTemplateUsers, subTemToParent, backMapOfBubbledUpProcesses, subTemplateQueryMapInfo),
            SeCompQueryPhase(backMapOfBubbledUpProcesses, subTemplates, subTemplateQueryMapInfo, numSubTemplateUsers, baseSubTemplateUsers, freeTypedefs, constInts)
        )
    }


    private class IndexingPhase(
        private val freeTypedefs: HashMap<String, Pair<Int, Int>?>,
        private val constInts: HashMap<String, Int>,
        private val subTemplates: HashMap<String, SubTemplateInfo>,
        private val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        private val numSubTemplateUsers: HashMap<String, FreeInstantiation>,
        private val systemLine: LinkedHashMap<String, IntRange>
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

        private val rewriters = HashMap<String, TextRewriter>()


        init {
            register(::indexGlobalDeclaration, listOf(Nta::class))
            register(::indexTemplate)
            register(::indexSystem)
        }


        @Suppress("UNUSED_PARAMETER")
        private fun indexGlobalDeclaration(path: UppaalPath, declaration: Declaration): List<UppaalMessage> {
            registerTypedefsAndConstants(declaration.content)
            return listOf()
        }

        /** Register a template as a "sub-template" and/or "sub-template USER". Note all information about which and
         * how many sub-templates are used and check if sub-templates are structured correctly. **/
        private fun indexTemplate(path: UppaalPath, template: Template): List<UppaalMessage> {
            val temName = template.name.content ?: return listOf(
                createUppaalError(
                path, "Template has no name.", true
            )
            )
            val errors = ArrayList<UppaalMessage>()

            // Register as sub-template
            if (temName.startsWith(SUB_TEMPLATE_NAME_PREFIX)) {
                val entryLocation = template.init?.let { init ->  // No init handled by UPPAAL
                    LocationSummary(init.ref, template.locations.find { loc -> loc.id == init.ref }!!.name?.content)
                }
                val exitLocations = template.locations
                    .filter { loc -> template.transitions.all { tran -> tran.source.ref != loc.id } }
                    .map { loc -> LocationSummary(loc.id, loc.name?.content) }

                if (exitLocations.isEmpty())
                    errors.add(
                        createUppaalError(
                        path, "A sub-template must have at least one exit location (location with no outgoing transitions)."
                    )
                    )

                if (template.transitions.none { it.source.ref == entryLocation?.id })
                    errors.add(
                        createUppaalError(
                        path, "The entry state of a sub-template must have at least one outgoing transition."
                    )
                    )

                // TODO: Relax this constraint later
                if (template.parameter != null && template.parameter!!.content.isNotBlank())
                    errors.add(
                        createUppaalError(
                        path, template.parameter!!.content, template.parameter!!.content.indices, "Sub-templates currently do not support parameters."
                    )
                    )

                subTemplates[temName] = SubTemplateInfo(entryLocation, exitLocations, temName, isFaulty = template.init?.ref == null || errors.isNotEmpty())
            }

            // Determine usage of sub-template(s)
            val subTemplateLocations = template.locations.withIndex().filter {
                it.value.name?.content?.trim()?.startsWith(SUB_TEMPLATE_USAGE_CLAMP) ?: false
                && it.value.name?.content?.trim()?.endsWith(SUB_TEMPLATE_USAGE_CLAMP) ?: false
            }
            val subTemplateUsages = ArrayList<SubTemplateUsage>()
            for (locAndIndex in subTemplateLocations) {
                // Find sub-template info
                val locNameContent = locAndIndex.value.name!!.content!!
                val nameAndInstance = locNameContent
                    .trim().drop(SUB_TEMPLATE_USAGE_CLAMP.length).dropLast(SUB_TEMPLATE_USAGE_CLAMP.length)
                    .trim().split(Regex("""\s+"""))
                    .filter { it.isNotBlank() }
                    .toMutableList()
                locAndIndex.value.name!!.content = "" // Prevent "invalid identifier" error from UPPAAL

                // Determine if sub-template inclusion is well-formed
                val locPath = path.plus(locAndIndex)
                if (nameAndInstance.size !in 1..2) {
                    errors.add(
                        createUppaalError(
                        locPath.extend(locAndIndex.value.name!!), locNameContent, "To insert a sub-template, format the location's name as: \"::[sub-template name] [instantiated name]::\" or \"::[sub-template name]::\"."
                    )
                    )
                    continue
                }

                // Determine if location is valid sub-tem insertion point
                val locId = locAndIndex.value.id
                if (template.init?.ref == locId)
                    errors.add(
                        createUppaalError(
                        locPath, "An initial/entry location cannot be a sub-template instance."
                    )
                    )
                if (template.transitions.none { it.target.ref == locId } && subTemplates.containsKey(locNameContent))
                    errors.add(
                        createUppaalError(
                        locPath, "An exit location cannot be a sub-template instance."
                    )
                    )
                if (errors.isNotEmpty())
                    continue

                // Register the sub-template's info
                val subTemplateName = nameAndInstance[0].ensureStartsWith(SUB_TEMPLATE_NAME_PREFIX)
                val subTemInstanceName = nameAndInstance.getOrNull(1)
                subTemplateUsages.add(SubTemplateUsage(locId, subTemplateName, subTemInstanceName, locNameContent))

                // Give the state the user-defined if present
                if (null != subTemInstanceName) {
                    val namePath = locPath.extend(locAndIndex.value.name!!)
                    val rewriter = rewriters.createOrGetRewriter(namePath, locNameContent)
                    rewriter.replace(locNameContent.indices, subTemInstanceName)
                        .addBackMap()
                        .overrideErrorRange { subTemInstanceName.indices.offset(locNameContent.lastIndexOf(subTemInstanceName)) }

                    locAndIndex.value.name!!.content = rewriter.getRewrittenText()
                }
            }

            // Register sub-template user and try to determine the number of instances based on "free parameters"
            if (subTemplateUsages.isNotEmpty()) {
                baseSubTemplateUsers[temName] = subTemplateUsages

                val parameters = template.parameter?.content
                if (subTemplates.containsKey(temName)) // Sub-templates can only be used in generated code
                    numSubTemplateUsers[temName] = FreeInstantiation(null, List(getParameters(parameters).size) { null })
                else if (parameters.isNullOrBlank()) // No parameters => just 1 instance
                    numSubTemplateUsers[temName] = FreeInstantiation(null, listOf())
                else {
                    val parameterValueRanges = getParameterRanges(parameters, path.extend(template.parameter!!), errors)
                    numSubTemplateUsers[temName] = FreeInstantiation(null, parameterValueRanges)
                }
            }

            // Double check sub-template faulty-ness
            if (subTemplates.containsKey(temName) && errors.isNotEmpty())
                subTemplates[temName]?.isFaulty = true

            return errors
        }

        /** Find the (transitive) structure of partial instantiations and which are actually instantiated on the system line. **/
        private fun indexSystem(path: UppaalPath, system: System): List<UppaalMessage> {
            val errors = ArrayList<UppaalMessage>()
            registerTypedefsAndConstants(system.content)

            // Register (transitive) relationships (if any) between partial instantiations and templates that use sub-templates
            for (tree in ConfreHelper.partialInstantiationConfre.findAll(system.content).map { it as Node }) {
                val baseName = tree.children[3]!!.toString()
                if (subTemplates.containsKey(baseName))
                    errors.add(
                        createUppaalError(
                        path, system.content, tree.children[3]!!.range, "Sub-templates cannot be instantiated by user-code.", true
                    )
                    )
                else if (numSubTemplateUsers.containsKey(baseName)) {
                    val parameterValueRanges =
                        if (tree.children[1]?.isNotBlank() == true) {
                            val parameters = system.content
                                .substring(tree.children[1]!!.range)
                                .drop(1).dropLast(1)
                            getParameterRanges(parameters, path, system.content, tree.children[1]!!.range, errors)
                        }
                        else listOf()

                    val partialName = tree.children[0]!!.toString()
                    numSubTemplateUsers[partialName] = FreeInstantiation(baseName, parameterValueRanges)
                }
            }

            // Find all (full) instantiations that make use of sub-templates
            val systemLineNode = ConfreHelper.systemLineConfre.find(system.content) as? Node
            if (systemLineNode != null) {
                val identNodes = listOf(systemLineNode.children[1]!!).plus(
                    (systemLineNode.children[2] as Node).children.map { (it as Node).children[1]!! }
                )
                // Loop does not handle "cannot determine total number of instances" since UPPAAL will notify about this by itself.
                for (subTemplateIdentNode in identNodes.filter { subTemplates.containsKey(it.toString()) })
                    errors.add(
                        createUppaalError(
                        path, system.content, subTemplateIdentNode, "Sub-templates cannot be instantiated by user-code.", true
                    )
                    )
                systemLine.putAll(identNodes.map { Pair(it.toString(), it.range) }.filter { numSubTemplateUsers.containsKey(it.first) })
            }

            return errors
        }


        private fun getParameterRanges(parameters: String, parameterPath: UppaalPath, errors: ArrayList<UppaalMessage>): ArrayList<FreeParameter?>
            = getParameterRanges(parameters, parameterPath, parameters, parameters.indices, errors)

        /** For each parameter in a template or partial instantiation, try to get the range of values a free instantiation
         * would produce. E.g., the parameter "const int[0,5] ID" would produce numbers in the range (0 .. 5) **/
        private fun getParameterRanges(parameters: String?, parameterPath: UppaalPath, fullText: String, parameterIndices: IntRange, errors: ArrayList<UppaalMessage>): ArrayList<FreeParameter?> {
            val parameterRanges = ArrayList<FreeParameter?>()
            for (param in getParameters(parameters).withIndex()) {
                val paramTree = freelyInstantiableParameterGrammar.matchExact(param.value) as? Node
                if (paramTree == null) {
                    parameterRanges.add(null)
                    continue
                }

                val type = paramTree.children[1]!!.toString()
                val name = paramTree.children[2]!!.toString()
                if (type.startsWith("int") || freeTypedefs[type] != null) {
                    // Get free-parameter-range from typedef or determine from type
                    val range = freeTypedefs[type] ?: getRangeFromBoundedIntType(type)
                    if (range == null)
                        parameterRanges.add(null)
                    else
                        parameterRanges.add(FreeParameter(range.first, range.second, name))
                }
                else if (type.startsWith("scalar") || (freeTypedefs.containsKey(type) && freeTypedefs[type] == null)) { // 'null' in freeTypedefs means 'is scalar'
                    errors.add(
                        createUppaalError(
                        parameterPath, fullText, parameterIndices, "Users of sub-templates cannot have scalar parameters."
                    )
                    )
                    parameterRanges.add(null)
                }
                else
                    parameterRanges.add(null)
            }
            return parameterRanges
        }

        /** If supplied the type "int[0,5]", output the range (0 .. 5). The lower and upper bounds may also be
         * given by constant variables. **/
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

        /** Extract every comma-separated parameter from a parameter-string. **/
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

        /** Since free parameters can be defined through typedefs and int-constant, these should be noted down so that
         * the mapper can use their values later when computing the number of process instances.
         * NOTE: Only int-constants with explicitly given integer values (i.e., "3" and NOT "1 + 2") are supported. **/
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
                    constInts[pair.second.children[3].toString()] = code.substring(pair.second.children[5]!!.range).toIntOrNull() ?: continue
        }


        override fun backMapModelErrors(errors: List<UppaalMessage>)
            = errors.filter { rewriters[it.path]?.backMapError(it) != BackMapResult.REQUEST_DISCARD }
    }

    private class ReferenceCheckingPhase(
        private val subTemplates: HashMap<String, SubTemplateInfo>,
        private val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>
    ) : ModelPhase() {
        init {
            register(::verifyTemplate)
        }


        /** Simply check if there are any obvious (logic) errors on a template wrt. SeComp. **/
        private fun verifyTemplate(path: UppaalPath, template: Template): List<UppaalMessage> {
            val errors = ArrayList<UppaalMessage>()
            val templateName = template.name.content ?: return listOf()
            val subTemUsages = baseSubTemplateUsers[templateName] ?: return listOf()

            for (usage in subTemUsages)
                if (usage.subTemplateName !in subTemplates.keys) {
                    val faultyLocationWithIndex = template.locations.withIndex().find { it.value.id == usage.insertLocationId }!!
                    val locationPath = path.plus(faultyLocationWithIndex)
                    errors.add(
                        createUppaalError(
                        locationPath, usage.originalLocationText, usage.originalLocationText.indices, "The template name '${usage.subTemplateName}' either does not exist or is not a sub-template.", true
                    )
                    )
                }

            return errors + checkCircularUse(path, listOf(templateName))
        }

        /** If two sub-templates (transitively) includes each other, this would result in infinite instances. This
         * function thus checks for this and reports an error if circular inclusion is detected. **/
        private fun checkCircularUse(path: UppaalPath, branch: List<String>): List<UppaalMessage> {
            if (branch.size != branch.distinct().size)
                return if (branch.last() == branch.first()) // If cycle pertains to root template
                    listOf(
                        createUppaalError(
                        path, "Cyclic sub-template usage: ${branch.joinToString(" -> ") { "'${it}'" }}.", true
                    )
                    )
                else
                    listOf() // If cycle does not pertain to root template, wait for that template's check to come

            val usages = baseSubTemplateUsers[branch.last()] ?: return listOf() // If not a sub-template user, there's nothing more to check
            return usages.flatMap { checkCircularUse(path, branch + it.subTemplateName) }
        }


        override fun backMapModelErrors(errors: List<UppaalMessage>) = errors
    }

    private class MappingPhase(
        private val subTemplates: HashMap<String, SubTemplateInfo>,
        private val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        private val numSubTemplateUsers: HashMap<String, FreeInstantiation>,
        private val systemLine: LinkedHashMap<String, IntRange>,
        private val subTemToParent: HashMap<InstanceSummary, ParentUserInfo>, // subTem:name+instance -> parentTem:name + instance + subTemIndex
        private val backMapOfBubbledUpProcesses: HashMap<String, String> // New placeholder partial inst name -> original partial inst or root template name.
    ) : ModelPhase() {
        private val totalNumInstances = HashMap<String, Int>() // Any-template name -> instance count

        private val rewriters = HashMap<String, TextRewriter>()


        init {
            register(::mapGlobalDeclaration, listOf(Nta::class))
            register(::mapTemplate)
            register(::mapSystem)
        }


        /** Adds global variables to control which sub-templates are active/inactive. **/
        private fun mapGlobalDeclaration(path: UppaalPath, declaration: Declaration): List<UppaalMessage> {
            findTotalNumbersOfInstances()

            val nextSubTemIndex = HashMap<String, Int>()
            val stateVariables = StringBuilder("\n\n")
            for (template in (baseSubTemplateUsers.keys + subTemplates.keys).distinct()) {
                if (!totalNumInstances.containsKey(template))
                    continue

                stateVariables.appendLine("// $template")
                stateVariables.appendLine("const int NUM_$template = ${totalNumInstances[template] ?: continue};")

                if (subTemplates.containsKey(template))
                    // This boolean array states whether a sub-template is "active" or not.
                    stateVariables.appendLine("bool STEM_ACTIVE_$template[NUM_$template] = { ${List(totalNumInstances[template]!!) { "false" }.joinToString()} };")

                if (baseSubTemplateUsers.containsKey(template)) {
                    val usages = baseSubTemplateUsers[template]!!

                    // The number of sub-template instances within this sub-template user.
                    stateVariables.append("const int NUM_STEM_$template = ${usages.size};")
                    stateVariables.appendLine(" // ${usages.joinToString { it.subTemplateName }}")

                    // Maps the index of a sub-template user to the list of sub-template-indices that are connected to said user
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

            val rewriter = rewriters.createOrGetRewriter(path, declaration.content)
            rewriter.append(stateVariables.toString())
            declaration.content = rewriter.getRewrittenText()

            return listOf()
        }

        /** This is used to generate unique indices for sub-templates and returns them so that index can be delegated
         * easily to one specific "sub-template USER" instance. **/
        private fun getAndIncrementNextSubTemIndex(userTemName: String, subTemName: String, subTemIndexInUser: Int, nextUserTemIndex: Int, nextSubTemIndices: HashMap<String, Int>): Int {
            val nextSubTemIndex = nextSubTemIndices[subTemName] ?: 0
            nextSubTemIndices[subTemName] = nextSubTemIndex + 1

            subTemToParent[InstanceSummary(subTemName, nextSubTemIndex)] = ParentUserInfo(userTemName, nextUserTemIndex, subTemIndexInUser)

            return nextSubTemIndex
        }

        /** For every instantiated template and/or partial instantiation, figure out the total number of instances. **/
        private fun findTotalNumbersOfInstances()
        {
            val rootTemplateNames = HashSet<String>()

            // Find the number of non-sub-template instances
            for (currentTemplateName in systemLine.map { it.key }) {
                val rootTemplateName = getRootTemplate(currentTemplateName)
                val instantiationInfo = numSubTemplateUsers[currentTemplateName] ?: continue
                if (null in instantiationInfo.parameters)
                    continue

                val totalInstancesOfCurrent = getTotalInstancesFromParams(instantiationInfo)
                totalNumInstances[currentTemplateName] = (totalNumInstances[currentTemplateName] ?: 0) + totalInstancesOfCurrent

                // Keep both the number of top-level and root templates to determine template-mappings as well as numbers of state variables in future
                if (currentTemplateName != rootTemplateName)
                    totalNumInstances[rootTemplateName] = (totalNumInstances[rootTemplateName] ?: 0) + totalInstancesOfCurrent

                rootTemplateNames.add(rootTemplateName)
            }

            // Find the total number of sub-template instances based on number of root template instances
            for (rootTemplateName in rootTemplateNames)
                for (subTemplateCount in getSubTemplateInstanceCountForOneRootTemplate(rootTemplateName))
                    totalNumInstances[subTemplateCount.key] =
                        (totalNumInstances[subTemplateCount.key] ?: 0) + (subTemplateCount.value * totalNumInstances[rootTemplateName]!!)
        }

        /** If the template 'templateName' is instantiated once, get the number of sub-template processes that would be
         * instantiated from that. **/
        private fun getSubTemplateInstanceCountForOneRootTemplate(templateName: String, subTemInstances: HashMap<String, Int> = HashMap()): HashMap<String, Int> {
            val subTemplateUsage = baseSubTemplateUsers[templateName] ?: return subTemInstances
            for (usage in subTemplateUsage) {
                subTemInstances[usage.subTemplateName] = (subTemInstances[usage.subTemplateName] ?: 0) + 1
                getSubTemplateInstanceCountForOneRootTemplate(usage.subTemplateName, subTemInstances)
            }
            return subTemInstances
        }


        private fun mapTemplate(path: UppaalPath, template: Template): List<UppaalMessage> {
            if (!totalNumInstances.containsKey(template.name.content))
                return listOf()

            val subTemplateInfo = subTemplates[template.name.content!!]
            if (subTemplateInfo != null && !subTemplateInfo.isFaulty)
                mapSubTemplate(path, template, subTemplateInfo)

            val subTemplateUsages = baseSubTemplateUsers[template.name.content!!]
            if (subTemplateUsages != null)
                mapSubTemplateUser(path, template, subTemplateUsages)

            return listOf()
        }

        /** For all entry and exit transitions, add updates and guards to register when control is given from the parent
         * template and to return control to the parent. **/
        private fun mapSubTemplate(path: UppaalPath, template: Template, subTemplateInfo: SubTemplateInfo) {
            for (entryTransition in template.transitions.withIndex().filter { it.value.source.ref == subTemplateInfo.entryLocation?.id }) {
                val transitionPath = path.plus(entryTransition)

                val guardLabel = entryTransition.value.labels.find { it.kind == "guard" }
                    ?: generateAndAddLabel("guard", template, entryTransition.value)
                val guardPath = transitionPath.extend(guardLabel, entryTransition.value.labels.indexOf(guardLabel) + 1)

                // Add activation guard
                val guardRewriter = rewriters.createOrGetRewriter(guardPath, guardLabel.content)
                if (guardLabel.content.isNotBlank()) {
                    guardRewriter.insert(0, "(")
                    guardRewriter.append(") && ")
                }
                guardRewriter.append("STEM_ACTIVE_${template.name.content!!}[STEM_INDEX]")
                guardLabel.content = guardRewriter.getRewrittenText()
            }

            for (exitTransition in template.transitions.withIndex().filter { subTemplateInfo.exitLocations.any { exit -> exit.id == it.value.target.ref } }) {
                val transitionPath = path.plus(exitTransition)

                val updateLabel = exitTransition.value.labels.find { it.kind == "assignment" }
                    ?: generateAndAddLabel("assignment", template, exitTransition.value)
                val updatePath = transitionPath.extend(updateLabel, exitTransition.value.labels.indexOf(updateLabel) + 1)

                // Add deactivation statement
                val updateRewriter = rewriters.createOrGetRewriter(updatePath, updateLabel.content)
                if (updateLabel.content.isNotBlank())
                    updateRewriter.append(", ")
                updateRewriter.append("STEM_ACTIVE_${template.name.content!!}[STEM_INDEX] = false")
                updateLabel.content = updateRewriter.getRewrittenText()

                // Since sub-templates should be reusable, all transitions going to an exit state are redirected to the start state.
                exitTransition.value.target.ref = subTemplateInfo.entryLocation!!.id
            }

            // Add "sub-template index" parameter which is used identify each instance of this template (as a sub-template)
            if (template.parameter == null)
                template.parameter = Parameter("")
            val parameterRewriter = rewriters.createOrGetRewriter(path.extend(template.parameter!!), template.parameter!!.content)
            if (template.parameter!!.content.isNotBlank())
                parameterRewriter.append(", ")
            parameterRewriter.append("const int STEM_INDEX")
            template.parameter!!.content = parameterRewriter.getRewrittenText()
        }

        /** For all locations with sub-template inclusions, add updates and guards on ingoing/outgoing transitions to
         * give/return control to/from each sub-template. **/
        private fun mapSubTemplateUser(path: UppaalPath, template: Template, subTemplateUsages: MutableList<SubTemplateUsage>) {
            for (usageAndIndex in subTemplateUsages.withIndex()) {
                val stemIndexExpr = "STEM_CONNECT_${template.name.content!!}[USER_INDEX][${usageAndIndex.index}]"
                val usage = usageAndIndex.value

                for (outgoing in template.transitions.withIndex().filter { it.value.source.ref == usage.insertLocationId }) {
                    val transitionPath = path.plus(outgoing)

                    val guardLabel = outgoing.value.labels.find { it.kind == "guard" }
                        ?: generateAndAddLabel("guard", template, outgoing.value)
                    val guardPath = transitionPath.extend(guardLabel, outgoing.value.labels.indexOf(guardLabel) + 1)

                    // Add exit guard
                    val guardRewriter = rewriters.createOrGetRewriter(guardPath, guardLabel.content)
                    if (guardLabel.content.isNotBlank()) {
                        guardRewriter.insert(0, "(")
                        guardRewriter.append(") && ")
                    }
                    guardRewriter.append("!STEM_ACTIVE_${usage.subTemplateName}[$stemIndexExpr]")
                    guardLabel.content = guardRewriter.getRewrittenText()
                }

                for (ingoing in template.transitions.withIndex().filter { it.value.target.ref == usage.insertLocationId }) {
                    val transitionPath = path.plus(ingoing)

                    val updateLabel = ingoing.value.labels.find { it.kind == "assignment" }
                        ?: generateAndAddLabel("assignment", template, ingoing.value)
                    val updatePath = transitionPath.extend(updateLabel, ingoing.value.labels.indexOf(updateLabel) + 1)

                    // Add activation statement
                    val updateRewriter = rewriters.createOrGetRewriter(updatePath, updateLabel.content)
                    if (updateLabel.content.isNotBlank())
                        updateRewriter.append(", ")
                    updateRewriter.append("STEM_ACTIVE_${usage.subTemplateName}[$stemIndexExpr] = true")
                    updateLabel.content = updateRewriter.getRewrittenText()
                }
            }

            // Add "user index" parameter which is used identify each instance of this template (as a sub-template USER)
            if (template.parameter == null)
                template.parameter = Parameter("")
            val parameterRewriter = rewriters.createOrGetRewriter(path.extend(template.parameter!!), template.parameter!!.content)
            if (template.parameter!!.content.isNotBlank())
                parameterRewriter.append(", ")
            parameterRewriter.append("const int USER_INDEX")
            template.parameter!!.content = parameterRewriter.getRewrittenText()
        }

        private fun generateAndAddLabel(kind: String, template: Template, transition: Transition): Label {
            val sourceLoc = template.locations.find { it.id == transition.source.ref }!!
            val targetLoc = template.locations.find { it.id == transition.target.ref }!!
            val label = Label(kind, (sourceLoc.x + targetLoc.x) / 2, (sourceLoc.y + targetLoc.y) / 2)
            transition.labels.add(label)
            return label
        }


        private fun mapSystem(path: UppaalPath, system: System): List<UppaalMessage> {
            val systemLineNode = ConfreHelper.systemLineConfre.find(system.content) as? Node
                ?: return listOf(createUppaalError(path, system.content, IntRange(system.content.length, system.content.length), "Missing 'system' line at end of system declarations.", true))

            val systemLineStartIndex = systemLineNode.startPosition()
            val rewriter = rewriters.createOrGetRewriter(path, system.content)

            // Map all partial instantiations
            val errors = ArrayList<UppaalMessage>()
            for (tree in ConfreHelper.partialInstantiationConfre.findAll(rewriter.originalText).map { it as Node }) {
                val lhsName = tree.children[0]!!.toString()
                val rootStartIndex = getRootStartIndex(lhsName)
                val instantiationInfo = numSubTemplateUsers[lhsName] ?: continue

                val isBaseOfOtherPartial = numSubTemplateUsers.any { it.value.baseTemplateName == lhsName }
                val isDirectlyInstantiated = systemLine.containsKey(lhsName)

                // If this is the base of another partial instantiation, add pass-through parameter for USER_INDEX at lhs and use that as input to rhs
                // Otherwise, generate actual USER_INDEX and pass to base on last parameter
                if (isBaseOfOtherPartial) {
                    // Due to the way USER/STEM indices are passed down through partial instantiations, a partial inst
                    // cannot both be a base of another partial inst and be instantiated on the system line.
                    // The solution is thus to generate a new "BaseTemplatePrime" partial instantiation in top of the
                    // base with the same parameters as the original base.
                    if (isDirectlyInstantiated)
                        bubbleUp(instantiationInfo, errors, path, rewriter, lhsName, rootStartIndex, systemLineStartIndex)

                    // Add parameters to accept a user index and argument to pass on user index down the partial instantiation chain
                    val lshHasParentheses = tree.children[1]!!.isNotBlank()
                    val lhsHasParams = lshHasParentheses && tree.children[1]!!.asNode().children[1]!!.isNotBlank()
                    val passThroughParam = (if (lhsHasParams) ", " else "") + "const int USER_INDEX"
                    val indexAfterLastLhsParameter =
                        if (lhsHasParams) tree.children[1]!!.endPosition() // Index of end-parenthesis
                        else tree.children[0]!!.endPosition() + 1 // Index right after lhs name

                    val indexAfterLastRhsParameter = (tree.children[6]!!.endPosition()) // Index of end-parenthesis
                    val rhsHasArgs = tree.children[5]!!.isNotBlank()
                    val passThroughArg = (if (rhsHasArgs) ", " else "") + "USER_INDEX"

                    if (!lshHasParentheses)
                        rewriter.insert(indexAfterLastLhsParameter, "(")
                    rewriter.insert(indexAfterLastLhsParameter, passThroughParam)
                    if (!lhsHasParams)
                        rewriter.insert(indexAfterLastLhsParameter, ")")

                    rewriter.insert(indexAfterLastRhsParameter, passThroughArg)
                }
                else {
                    // Since this partial instantiation is not a base of another, if possible, generate an expression
                    // that uses the free parameters to compute a unique id (wrt. the root template) for these instances.
                    val parameterRanges = instantiationInfo.parameters
                    val rhsHasArgs = tree.children[5]!!.isNotBlank()
                    val argumentToAdd = if (!parameterRanges.contains(null) && parameterRanges.isNotEmpty())
                        (if (rhsHasArgs) ", " else "") + generateUserIndexExpression(parameterRanges, rootStartIndex)
                    else
                        (if (rhsHasArgs) ", " else "") + rootStartIndex // Default "unique instance index"

                    val indexAfterLastRhsParameter = (tree.children[6]!!.endPosition()) // Index of end-parenthesis
                    rewriter.insert(indexAfterLastRhsParameter, argumentToAdd)
                }
            }


            // Generate partial instantiations for sub-templates (this exact code may not work if sub-templates are to support user-code parameters)
            val newSystemLineInstantiations = ArrayList<String>()
            for (subTem in subTemplates) {
                val partialName = subTem.key.drop(2)
                newSystemLineInstantiations.add(partialName)

                val isAlsoUser = baseSubTemplateUsers.containsKey(subTem.key)
                val partialInstantiation =
                    if (isAlsoUser) "$partialName(const int[0, ${totalNumInstances[subTem.key]!! - 1}] STEM_AND_USER_INDEX) = ${subTem.key}(STEM_AND_USER_INDEX, STEM_AND_USER_INDEX);\n"
                    else "$partialName(const int[0, ${totalNumInstances[subTem.key]!! - 1}] STEM_INDEX) = ${subTem.key}(STEM_INDEX);\n"

                rewriter.insert(systemLineStartIndex, partialInstantiation)
            }
            if (newSystemLineInstantiations.isNotEmpty()) {
                rewriter.insert(systemLineNode.endPosition() + 1, ", " + newSystemLineInstantiations.joinToString())
            }


            // Bubble up directly instantiated root templates
            for (instantiation in systemLine.filter { baseSubTemplateUsers.containsKey(it.key) }) {
                val lhsName = instantiation.key
                val instantiationInfo = numSubTemplateUsers[lhsName]!!
                bubbleUp(instantiationInfo, errors, path, rewriter, lhsName, getRootStartIndex(lhsName), systemLineStartIndex)
            }

            rewriter.insert(systemLineStartIndex, "\n")
            system.content = rewriter.getRewrittenText()
            return errors
        }

        private fun getRootStartIndex(lhsName: String) =
            if (lhsName !in systemLine.keys) 0 // Ignore since not instantiated anyway. Just need filler to not get UPPAAL errors
            else {
                val rootTemplateName = getRootTemplate(lhsName)
                systemLine.keys
                    .takeWhile { it != lhsName }
                    .filter { rootTemplateName == getRootTemplate(it) }
                    .sumOf { totalNumInstances[it]!! }
            }


        /** Replace an instantiation on the system line with a new instantiation that does not contain a USER/STEM-index parameter,
         * since that parameter is not "free" and thus hinders the (partial) template from being instantiated otherwise. **/
        private fun bubbleUp(
            instantiationInfo: FreeInstantiation,
            errors: ArrayList<UppaalMessage>,
            path: UppaalPath,
            textRewriter: TextRewriter,
            lhsName: String,
            nextRootIndex: Int,
            systemLineStartIndex: Int
        ) {
            if (null in instantiationInfo.parameters) {
                errors += createUppaalError(
                    path,
                    textRewriter.originalText,
                    systemLine[lhsName]!!,
                    "The SeComp mapper cannot determine the number of instances of this system based on it's parameters."
                )
                return
            }

            val lhsPrimeName = "${lhsName}__Prime"
            val lhsParameters = instantiationInfo.parameters
                .filterNotNull()
                .joinToString { "const int[${it.lowerRange}, ${it.upperRange}] ${it.name}" }
            val rhsArguments = instantiationInfo.parameters
                .filterNotNull()
                .map { it.name }
                .plus(generateUserIndexExpression(instantiationInfo.parameters, nextRootIndex))
                .joinToString()

            textRewriter.insert(systemLineStartIndex, "${lhsPrimeName}($lhsParameters) = $lhsName($rhsArguments);\n")
            textRewriter.replace(systemLine[lhsName]!!, lhsPrimeName)
                .addBackMap()
                .activateOn(ActivationRule.ACTIVATION_CONTAINS_ERROR)
                .overrideErrorRange { systemLine[lhsName]!! }

            backMapOfBubbledUpProcesses[lhsPrimeName] = lhsName
        }

        /** Given the name of a template or partial instantiation, find the bottom-most "root template". If a template
         * name is given, that name is returned. If a partial instantiation name is given, the name of the template at
         * the bottom of the possibly long chain of partial instantiations is returned. **/
        private fun getRootTemplate(templateName: String): String {
            var rootName = templateName
            var instantiationInfo = numSubTemplateUsers[rootName]!!
            while (instantiationInfo.baseTemplateName != null) {
                rootName = instantiationInfo.baseTemplateName!!
                instantiationInfo = numSubTemplateUsers[rootName]!!
            }
            return rootName
        }

        /** Based on the (free) parameters of a (partial) template/instantiation, determine the total number of processes
         * generated if put on the "system line" in "system declarations". **/
        private fun getTotalInstancesFromParams(instantiationInfo: FreeInstantiation)
            = instantiationInfo.parameters
                .map { it!!.upperRange - it.lowerRange + 1 }
                .product()

        /** Generate an expression that uses the free parameters in a partial instantiation (i.e, 'parameters') to generate
         * a unique index (with some offset 'nextRootIndex' from zero) for each process produced by the instantiation. **/
        private fun generateUserIndexExpression(
            parameters: List<FreeParameter?>,
            nextRootIndex: Int
        ): String {
            val rangeLengths = parameters.map { it!!.upperRange - it.lowerRange + 1 }
            val parameterNames = parameters.map { it!!.name }

            val rangeFlatteningTerms = parameterNames.dropLast(1).zip(rangeLengths.drop(1))
            val finalTerms = rangeFlatteningTerms.map { "${it.first}*${it.second}" }
                .plus(parameterNames.lastOrNull())
                .plus(nextRootIndex.toString())
                .filterNotNull()

            return finalTerms.joinToString(" + ")
        }


        override fun backMapModelErrors(errors: List<UppaalMessage>)
            = errors.filter { rewriters[it.path]?.backMapError(it) != BackMapResult.REQUEST_DISCARD }
    }


    private class SeCompSimulatorPhase(
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        val subTemToParent: HashMap<InstanceSummary, ParentUserInfo>,
        val backMapOfBubbledUpProcesses: HashMap<String, String>,
        val subTemplateQueryMapInfo: HashMap<String, SubTemplateQueryMapInfo>
    ) : SimulatorPhase() {
        override fun backMapInitialSystem(system: UppaalSystem) {
            val subTemInstanceCount = HashMap<Pair<String, String>, Int>() // Parent instance name, SubTemplate name -> Sub template count
            val instanceToName = HashMap<InstanceSummary, String>() // Parent

            val finishedMappings = HashSet<UppaalProcess>()
            while (finishedMappings.size != system.processes.size) {
                val nonPartialNextIndex = HashMap<String, Int>()

                for (process in system.processes) {
                    val currentTem = process.template
                    val currentIndex = nonPartialNextIndex.getOrPut(currentTem) { 0 }
                    nonPartialNextIndex[currentTem] = nonPartialNextIndex[currentTem]!! + 1
                    if (finishedMappings.contains(process))
                        continue

                    val isSubTem = subTemplates.containsKey(currentTem)
                    val isUser = baseSubTemplateUsers.containsKey(process.template)

                    if (isSubTem) {
                        val subTemId = InstanceSummary(currentTem, currentIndex)
                        val parentUserInfo = subTemToParent[subTemId]!!
                        val parent = InstanceSummary(parentUserInfo.parentTemplateName, parentUserInfo.parentUserIndex)
                        if (instanceToName.containsKey(parent)) {
                            val parentInstanceName = instanceToName[parent]!!
                            val subTemUsage = baseSubTemplateUsers[parentUserInfo.parentTemplateName]!![parentUserInfo.subTemplateIndexInParent]
                            var subTemInstanceName = subTemUsage.subTemInstanceName
                            if (null == subTemInstanceName) {
                                val nextAutoGeneratedSubTemIndex = subTemInstanceCount[Pair(parentInstanceName, currentTem)] ?: 0
                                subTemInstanceName = "${currentTem.drop(SUB_TEMPLATE_NAME_PREFIX.length)}($nextAutoGeneratedSubTemIndex)"
                                subTemInstanceCount[Pair(parentInstanceName, currentTem)] = nextAutoGeneratedSubTemIndex + 1
                            }
                            val newStemName = "${parentInstanceName}.$subTemInstanceName"
                            subTemplateQueryMapInfo[newStemName] = SubTemplateQueryMapInfo(
                                process.name, currentIndex, subTemplates[currentTem]!!,
                                parentInstanceName, subTemUsage.subTemInstanceName
                            )
                            process.name = newStemName
                            if (isUser)
                                instanceToName[subTemId] = process.name
                            finishedMappings.add(process)
                            continue
                        }
                    }
                    else if (isUser) {
                        // If a partial instantiation or root template has been "bubbled up", replace with original name.
                        val baseName = process.name.substringBefore('(')
                        if (backMapOfBubbledUpProcesses.containsKey(baseName))
                            process.name = process.name.replace(baseName, backMapOfBubbledUpProcesses[baseName]!!)

                        val userId = InstanceSummary(process.template, currentIndex)
                        instanceToName[userId] = process.name
                        finishedMappings.add(process)
                    }
                    else
                        finishedMappings.add(process)
                }
            }
        }
    }


    private class SeCompQueryPhase(
        val backMapOfBubbledUpProcesses: HashMap<String, String>,
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val subTemplateQueryMapInfo: HashMap<String, SubTemplateQueryMapInfo>,
        val numSubTemplateUsers: HashMap<String, FreeInstantiation>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        val freeTypedefs: HashMap<String, Pair<Int, Int>?>,
        val constInts: HashMap<String, Int>
    ) : QueryPhase() {
        private val baseNameToBubbledUpNames = HashMap<String, String>()

        enum class FollowType {
            NOTHING, SUBSCRIPTS, ARGUMENTS
        }

        enum class QuantifierType {
            FORALL, EXISTS, SUM
        }

        data class QuantifierContext(
            val quantifierVariable: Pair<String, QuantifierInfo>,
            val dotExpressions: ArrayList<DotExpr> = ArrayList(),
            val subContexts: ArrayList<QuantifierContext> = ArrayList()
        )
        data class QuantifierInfo(val variableRange: Pair<Int, Int>?, val quantifierType: QuantifierType)
        data class DotExpr(val parts: List<Part>, val fullRange: IntRange)
        data class Part(val identifier: String, val nameRange: IntRange, val followedBy: FollowType, val argumentsOrIndices: List<Pair<String, IntRange>>, val fullRange: IntRange)


        /** 'subTemplatePathLength' is the number of parts from the start of the corresponding dotExpr that comprise the
         * actual sub-template reference.
         * 'info' is the information related to the type of sub-template referenced. **/
        data class SubTemplateRef(val dotExprSubTemplatePathLength: Int, val info: SubTemplateInfo)


        private val queryExprConfre = Confre(ConfreHelper.expressionGrammar)


        override fun mapQuery(queryRewriter: TextRewriter) {
            // This "instantiation" of baseNameToBubbledUpNames is delayed since 'backMapOfBubbledUpProcesses' is empty until this point
            if (baseNameToBubbledUpNames.isEmpty() && backMapOfBubbledUpProcesses.isNotEmpty())
                for ((key, value) in backMapOfBubbledUpProcesses)
                    baseNameToBubbledUpNames[value] = key

            try {
                findDotExpressionContexts(queryRewriter.originalText)
                    .firstNotNullOfOrNull { mapContextWithSideEffects(queryRewriter, it) }
            }
            catch (ex: Exception) {
                throw ex
            }
        }

        private fun findDotExpressionContexts(originalQuery: String): Sequence<QuantifierContext>
            = queryExprConfre.findAll(originalQuery).map {
                fullExpr -> findDotExpressionContexts(originalQuery, fullExpr, QuantifierContext(Pair("", QuantifierInfo(Pair(0,-1), QuantifierType.FORALL))))
            }

        private fun findDotExpressionContexts(originalQuery: String, expression: ParseTree, currentContext: QuantifierContext): QuantifierContext {
            val treeIterator = TreeIterator(expression)

            treeIterator.next()
            while (true) {
                val currentNode = treeIterator.current() as? Node
                if (null == currentNode) { // Will be 'null' if a Leaf was returned
                    if (!treeIterator.hasNext())
                        break
                    treeIterator.next()
                    continue
                }

                if (isDotExprNode(currentNode))
                    currentContext.dotExpressions.add(getDotExprFromNode(originalQuery, currentNode))
                else if (isQuantifierNode(currentNode)) {
                    val quantifierType = when (val quan = currentNode.children[0]!!.toString()) {
                        "forall" -> QuantifierType.FORALL
                        "exists" -> QuantifierType.EXISTS
                        "sum" -> QuantifierType.SUM
                        else -> throw Exception("Unhandled quantifier type: '$quan'")
                    }

                    val quanVarName = currentNode.children[2]!!.toString()
                    val quanVarType = originalQuery.substring(currentNode.children[4]!!.range)

                    val quanVarRange = freeTypedefs[quanVarType] ?: getRangeFromBoundedIntType(quanVarType)
                    val subContext = QuantifierContext(Pair(quanVarName, QuantifierInfo(quanVarRange, quantifierType)))

                    currentContext.subContexts.add(findDotExpressionContexts(originalQuery, currentNode.children[6]!!, subContext))
                }
                else {
                    if (!treeIterator.hasNext())
                        break
                    treeIterator.next()
                    continue
                }

                if (!treeIterator.hasNextAfterCurrentSubTree())
                    break
                treeIterator.nextAfterCurrentSubTree()
            }

            return currentContext
        }

        private fun isDotExprNode(node: Node): Boolean {
            if (node.children.size != 2)
                return false

            val (first, second) = node.children.map { it as Node }
            if ((first.grammar as? NonTerminalRef)?.nonTerminalName != "ExtendedTerm")
                return false
            if (second.grammar !is Multiple || second.children.isEmpty())
                return false

            return true
        }

        private fun isQuantifierNode(node: Node): Boolean
            = (node.grammar as? NonTerminalRef)?.nonTerminalName == "Quantifier"

        private fun getDotExprFromNode(originalQuery: String, node: Node): DotExpr {
            val fullRange = node.range
            val terms = listOf(node.children[0]!!.asNode()).plus( // Take the "root" terms in the dot expression
                node.children[1]!!.asNode().children.map {
                    it!!.asNode().children[1]!!.asNode().children.first() as Node? // Take all (nullable) terms in the following "{'.' [ExtendedTerm]}"
                        ?: throw UppaalMessageException(
                            createUppaalError(
                            UppaalPath(), originalQuery, node.range, "Blank term in dot-expression '${originalQuery.substring(node.range)}'", ""
                        )
                        )
                })

            val parts = terms.map { partNode ->
                val baseName = partNode.children[0]!!.toString()
                val nameRange = partNode.children[0]!!.range
                val (followedBy, argumentsOrIndices) = getArgumentsOrIndices(originalQuery, partNode)
                val fullPartRange = partNode.range

                Part(baseName, nameRange, followedBy, argumentsOrIndices, fullPartRange)
            }

            return DotExpr(parts, fullRange)
        }

        private fun getArgumentsOrIndices(originalQuery: String, extendedTerm: Node): Pair<FollowType, List<Pair<String, IntRange>>> {
            if (extendedTerm.children[1]!!.isBlank())
                return Pair(FollowType.NOTHING, listOf())

            return when (val firstTokenFollowingName = extendedTerm.children[1]!!.tokens().first().value) {
                "(" -> Pair(FollowType.ARGUMENTS, getArguments(originalQuery, extendedTerm.children[1]!!.asNode().children[0]!!.asNode().children[1]!!.asNode()))
                "[" -> Pair(FollowType.SUBSCRIPTS, getIndices(originalQuery, extendedTerm.children[1]!!.asNode().children[0]!!.asNode()))
                else -> throw Exception("Unhandled character following term: '$firstTokenFollowingName'")
            }
        }

        private fun getArguments(originalQuery: String, listOfArgumentNode: Node): List<Pair<String, IntRange>> {
            if (listOfArgumentNode.isBlank())
                return listOf()

            val argumentNodes = listOf(listOfArgumentNode.children[0]!!).plus(
                listOfArgumentNode.children[1]!!.asNode().children.map { it!!.asNode().children[1]!!.asNode() }
            )

            return argumentNodes.map { arg ->
                val range = arg.range
                Pair(originalQuery.substring(range), range)
            }
        }

        private fun getIndices(originalQuery: String, subscriptListNode: Node): List<Pair<String, IntRange>>
            = subscriptListNode.children.filterNotNull()
                .map { subscript ->
                    val range = subscript.asNode().range
                    val value = originalQuery.substring(range)
                    Pair(value, range)
                }


        private fun mapContextWithSideEffects(queryRewriter: TextRewriter, context: QuantifierContext, quantifierVars: LinkedHashMap<String, QuantifierInfo> = LinkedHashMap()) {
            for (dotExpr in context.dotExpressions) {
                val firstPart = dotExpr.parts[0]
                if (firstPart.followedBy == FollowType.SUBSCRIPTS)
                    continue // If followed by [], it cannot be a process.

                // 2 parts => Just a normal "process.locationOrVariable"
                if (dotExpr.parts.size == 2) {
                    if (baseNameToBubbledUpNames.containsKey(firstPart.identifier))
                        queryRewriter.replace(firstPart.nameRange, baseNameToBubbledUpNames[firstPart.identifier]!!)
                            .addBackMap()
                            .activateOn(ActivationRule.ERROR_CONTAINS_ACTIVATION)
                            .overrideErrorRange { dotExpr.fullRange }
                            .overrideErrorMessage { it.replace(baseNameToBubbledUpNames[firstPart.identifier]!!, firstPart.identifier) }
                            .overrideErrorContext { it.replace(baseNameToBubbledUpNames[firstPart.identifier]!!, firstPart.identifier) }
                }
                else {
                    val subTemRef = getSubTemplateRef(dotExpr)
                        ?: continue

                    // Check if quantifier variables cause the dotExpr to reference more than one sub-template instance.
                    val referencesMultiple = dotExpr.parts[0].argumentsOrIndices
                        .map { argumentInfo -> argumentInfo.first }
                        .any { argument -> quantifierVars.containsKey(argument) }

                    if (referencesMultiple) {
                        queryRewriter.replace(dotExpr.fullRange, mapMultiRefDotExpr(queryRewriter.originalText, dotExpr, subTemRef, quantifierVars))
                            .addBackMap()
                            .activateOn(ActivationRule.ACTIVATION_CONTAINS_ERROR)
                            .overrideErrorRange { dotExpr.fullRange }
                    }
                    else {
                        val newDotExpr = mapDotExpr(queryRewriter.originalText, dotExpr, subTemRef)
                        val originalDotExpr = queryRewriter.originalText.substring(dotExpr.fullRange)
                        queryRewriter.replace(dotExpr.fullRange, newDotExpr)
                            .addBackMap()
                            .activateOn(ActivationRule.ACTIVATION_CONTAINS_ERROR)
                            .overrideErrorRange { dotExpr.fullRange }
                            .overrideErrorMessage { it.replace(newDotExpr, originalDotExpr) }
                            .overrideErrorContext { it.replace(newDotExpr, originalDotExpr) }
                    }
                }
            }

            for (subContext in context.subContexts) {
                val newQuantifierVars = LinkedHashMap(quantifierVars)
                // If the range of a quantifier is null, the mapper simply could not determine this range.
                // It will only become an error if the mapper requires this range for rewriting.
                newQuantifierVars[subContext.quantifierVariable.first] = subContext.quantifierVariable.second
                mapContextWithSideEffects(queryRewriter, subContext, newQuantifierVars)
            }

            // TODO: Query for "Exit" state reachability is impossible with current mapping and multiple end states
            //  since we don't know which exit state was entered.
            // "STEM_ACTIVE_${template.name.content!!}[STEM_INDEX] = false"
        }

        private fun getSubTemplateRef(dotExpr: DotExpr): SubTemplateRef? {
            val rootTemplateName = getRootTemplate(dotExpr.parts[0].identifier) ?: return null
            var currentSubTemplateUser = baseSubTemplateUsers[rootTemplateName]!!
            var currentSubTemplateName: String? = null
            var subTemplatePathLength = 1

            // Find the referenced sub-template if it exists. Cannot be first, since this is the root user.
            // Cannot be last since this is a location, variable, or function. Thus, we drop first and last.
            for (part in dotExpr.parts.drop(1).dropLast(1)) {
                if (part.followedBy != FollowType.NOTHING) // If there is a [] or (), this cannot be a sub-tem reference
                    break

                // Break if the part-identifier is not a sub-template, otherwise, get the template name of the sub-template.
                currentSubTemplateName = currentSubTemplateUser
                    .find { it.subTemInstanceName == part.identifier }
                    ?.subTemplateName
                    ?: break

                subTemplatePathLength += 1

                // If the sub-template just found is not a user, break since remaining parts cannot be a sub-template
                currentSubTemplateUser = baseSubTemplateUsers[currentSubTemplateName] ?: break
            }

            val subTemplateInfo = subTemplates[currentSubTemplateName ?: return null] ?: return null
            return SubTemplateRef(subTemplatePathLength, subTemplateInfo)
        }

        private fun mapDotExpr(originalQuery: String, dotExpr: DotExpr, subTemRef: SubTemplateRef, quantifierVarValues: HashMap<String, Int> = HashMap()): String {
            val resolvedName = resolveSubProcessName(originalQuery, dotExpr, subTemRef, quantifierVarValues)
            val subProcessInfo = subTemplateQueryMapInfo[resolvedName] ?: throw Exception("Cannot find info for process: '$resolvedName'")

            val resolvedDotExpr = "${subProcessInfo.nativeSubProcessName}." + dotExpr.parts.drop(subTemRef.dotExprSubTemplatePathLength).joinToString(".") {
                var result = it.identifier
                if (it.followedBy == FollowType.ARGUMENTS) {
                    result += it.argumentsOrIndices.joinToString(",", "(", ")")
                }
                if (it.followedBy == FollowType.SUBSCRIPTS) {
                    result += it.argumentsOrIndices.joinToString("") { sub -> "[$sub]" }
                }
                result
            }
            val lastPart = dotExpr.parts.last()
            val stemActive = "STEM_ACTIVE_${subProcessInfo.subTemplateInfo.subTemplateName}[${subProcessInfo.subTemplateIndex}]"

            // If dotExpr cannot possibly query a location
            if (dotExpr.parts.size - subTemRef.dotExprSubTemplatePathLength != 1 || lastPart.followedBy != FollowType.NOTHING)
                return resolvedDotExpr

            // If the dotExpr references the entry location of the sub-template
            if (subProcessInfo.subTemplateInfo.entryLocation!!.name == lastPart.identifier)
                return "($resolvedDotExpr && $stemActive)"

            // If the dotExpr references the/an exit location of the sub-template
            if (subProcessInfo.subTemplateInfo.exitLocations.any { loc -> loc.name == lastPart.identifier }) {
                if (subProcessInfo.subTemplateInfo.exitLocations.size != 1)
                    throw UppaalMessageException(
                        createUppaalError(
                        UppaalPath(), originalQuery, dotExpr.fullRange, "SeComp mapper currently cannot query the reachability of an exist state on a sub-template with multiple exit states"
                    )
                    )

                // If parent name has been bubbled up, the name from the process info must be rewritten
                val fullParentProcessName = subProcessInfo.nativeParentProcessName
                val parentProcessNameWithoutArgs = fullParentProcessName.substringBefore('(')
                val parentIsBubbledUp = baseNameToBubbledUpNames.containsKey(parentProcessNameWithoutArgs)
                val trueParentName = if (!parentIsBubbledUp) fullParentProcessName
                                     else fullParentProcessName.replace(parentProcessNameWithoutArgs, baseNameToBubbledUpNames[parentProcessNameWithoutArgs]!!)

                return "(${trueParentName}.${subProcessInfo.parentInsertLocationName} && !$stemActive)"
            }

            // Likely a reference to a variable or non-entry/exit location.
            return resolvedDotExpr
        }

        private fun mapMultiRefDotExpr(originalQuery: String, dotExpr: DotExpr, subTemRef: SubTemplateRef, quantifierVars: LinkedHashMap<String, QuantifierInfo>): String {
            // Find the quantifier variables that are important for determining
            val significantQuantifierVars = quantifierVars
                .filter { quanVar -> dotExpr.parts[0].argumentsOrIndices.any { arg -> arg.first == quanVar.key } }

            val unfoldedExpressions = getAllQuantifierVarValueCombinations(significantQuantifierVars)
                .map { varAlloc -> Pair(mapDotExpr(originalQuery, dotExpr, subTemRef, varAlloc), varAlloc) }

            val currentQuantifierType = quantifierVars.entries.last().value.quantifierType
            return if (currentQuantifierType == QuantifierType.SUM)
                "(${unfoldedExpressions.fold("0/-1") { acc, pair -> 
                    "${getQuantifierVarMatchExpr(pair.second)} ? ${pair.first} : ($acc)" 
                }})"
            else
                "(${unfoldedExpressions.joinToString(" && ") { pair -> 
                    "(${getQuantifierVarMatchExpr(pair.second)} imply ${pair.first})" 
                }})"
        }

        fun getQuantifierVarMatchExpr(allocation: HashMap<String, Int>)
            = allocation.entries.joinToString(" && ", "(", ")") { "${it.key} == ${it.value}" }

        private fun getAllQuantifierVarValueCombinations(significantQuantifierVars: Map<String, QuantifierInfo>): Sequence<HashMap<String, Int>> = sequence {
            val values = LinkedHashMap(significantQuantifierVars.map {
                Pair(it.key, it.value.variableRange?.first ?: throw UppaalMessageException(
                    createUppaalError(
                    UppaalPath(), "SeComp cannot resolve the value range of the quantifier variable: '${it.key}'"
                )
                )
                )
            }.toMap())

            val numCombinations = significantQuantifierVars.values.map { it.variableRange!!.second - it.variableRange.first + 1 }.product()
            repeat(numCombinations) {
                yield(HashMap(values))
                for ((key, value) in values) {
                    val valRange = significantQuantifierVars[key]!!.variableRange!!
                    if (value == valRange.second)
                        values[key] = valRange.first
                    else
                    {
                        values[key] = value + 1
                        break
                    }
                }
            }
        }

        private fun resolveSubProcessName(originalQuery: String, dotExpr: DotExpr, subTemRef: SubTemplateRef, quantifierVarValues: HashMap<String, Int> = HashMap()): String {
            val firstPart = dotExpr.parts[0]
            val resolvedBase = firstPart.identifier +
                if (dotExpr.parts[0].followedBy == FollowType.ARGUMENTS)
                    "(${firstPart.argumentsOrIndices.joinToString(",") { argument ->
                        (argument.first.toIntOrNull()
                            ?: quantifierVarValues[argument.first]
                            ?: constInts[argument.first]
                            ?: throw UppaalMessageException(
                                createUppaalError(
                                UppaalPath(), originalQuery, argument.second, "Cannot resolve integer value of argument: '${argument.first}'"
                            )
                            ))
                            .toString()
                    }})"
                else
                    ""

            val subTemplateParts = dotExpr.parts
                .take(subTemRef.dotExprSubTemplatePathLength)
                .drop(1)
                .map { it.identifier }

            return (listOf(resolvedBase) + subTemplateParts).joinToString(".")
        }


        /** If supplied the type "int[0,5]", output the range (0 .. 5). The lower and upper bounds may also be
         * given by constant variables. **/
        private fun getRangeFromBoundedIntType(type: String): Pair<Int, Int>? {
            val range = if (type.startsWith("int") && type.contains('['))
                type.substringAfter('[')
                    .dropLast(1)
                    .split(',')
                    .map { it.trim() }
                    .let { Pair(
                        it[0].toIntOrNull() ?: constInts[it[0]] ?: return null,
                        it[1].toIntOrNull() ?: constInts[it[1]] ?: return null
                    ) }
            else
                null

            if (range != null && range.second < range.first)
                throw UppaalMessageException(
                    createUppaalError(
                    UppaalPath(), "Found bounded integer with impossible range: '$type' = int[${range.first}, ${range.second}]"
                )
                )

            return range
        }

        /** Given the name of a template or partial instantiation, find the bottom-most "root template". If a template
         * name is given, that name is returned. If a partial instantiation name is given, the name of the template at
         * the bottom of the possibly long chain of partial instantiations is returned. **/
        private fun getRootTemplate(templateName: String): String? {
            var rootName = templateName
            var instantiationInfo = numSubTemplateUsers[rootName] ?: return null
            while (instantiationInfo.baseTemplateName != null) {
                rootName = instantiationInfo.baseTemplateName!!
                instantiationInfo = numSubTemplateUsers[rootName]!!
            }
            return rootName
        }
    }
}