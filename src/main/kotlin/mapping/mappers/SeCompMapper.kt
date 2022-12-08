package mapping.mappers

import ensureStartsWith
import mapping.*
import mapping.base.*
import mapping.parsing.Confre
import mapping.parsing.ConfreHelper
import mapping.parsing.Node
import mapping.rewriting.BackMapResult
import mapping.rewriting.Rewriter
import createOrGetRewriter
import mapping.rewriting.ActivationRule
import offset
import product
import uppaal_pojo.*
import java.util.Stack


const val SUB_TEMPLATE_NAME_PREFIX = "__"
const val SUB_TEMPLATE_USAGE_CLAMP = "::"

class SeCompMapper : Mapper {
    private data class SubTemplateInfo(val entryStateId: String?, val exitStateIds: List<String>, var isFaulty: Boolean = false)
    private data class SubTemplateUsage(val insertLocationId: String, val subTemplateName: String, val subTemInstanceName: String?, val originalText: String)

    // Related to a template or partial instantiation. If the name is used on the "system"-line, how many instances of "baseTemplate" will be made?
    // baseSubTemplateUserName = 'null': not part of partial instantiation, 'not null' = part of partial instantiation
    // parameters: null-triple in list means non-free parameter.
    private data class FreeInstantiation(val baseTemplateName: String?, val parameters: List<FreeParameter?>)
    private data class FreeParameter(val lowerRange: Int, val upperRange: Int, val name: String)


    override fun getPhases(): PhaseOutput {
        val subTemplates = HashMap<String, SubTemplateInfo>()                       // String = Sub-template name
        val baseSubTemplateUsers = HashMap<String, MutableList<SubTemplateUsage>>() // String = Any-template name
        val numSubTemplateUsers = HashMap<String, FreeInstantiation>()              // String = Any-template or partial instantiation name
        val subTemToParent = HashMap<Pair<String, Int>, Triple<String, Int, Int>>() // Sub-template template name + index -> parent "template name", "user index", "index of the sub-template in it's parent"
        val backMapOfBubbledUpProcesses = HashMap<String, String>()                 // New placeholder partial inst name -> original partial inst or root template name.
        val systemLine = HashMap<String, IntRange>() // system process1, process2, ..., process_n

        return PhaseOutput(
            sequenceOf(
                IndexingPhase(subTemplates, baseSubTemplateUsers, numSubTemplateUsers, systemLine),
                ReferenceCheckingPhase(subTemplates, baseSubTemplateUsers),
                MappingPhase(subTemplates, baseSubTemplateUsers, numSubTemplateUsers, systemLine, subTemToParent, backMapOfBubbledUpProcesses)
            ),
            SeCompSimulatorPhase(subTemplates, baseSubTemplateUsers, subTemToParent, backMapOfBubbledUpProcesses),
            SeCompQueryPhase()
        )
    }


    private class IndexingPhase(
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        val numSubTemplateUsers: HashMap<String, FreeInstantiation>,
        val systemLine: HashMap<String, IntRange>
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

        private val rewriters = HashMap<String, Rewriter>()


        init {
            register(::indexGlobalDeclaration, listOf(Nta::class.java))
            register(::indexTemplate)
            register(::indexSystem)
        }


        @Suppress("UNUSED_PARAMETER")
        private fun indexGlobalDeclaration(path: UppaalPath, declaration: Declaration): List<UppaalError> {
            registerTypedefsAndConstants(declaration.content)
            return listOf()
        }

        /** Register a template as a "sub-template" and/or "sub-template USER". Note all information about which and
         * how many sub-templates are used and check if sub-templates are structured correctly. **/
        private fun indexTemplate(path: UppaalPath, template: Template): List<UppaalError> {
            val temName = template.name.content ?: return listOf(createUppaalError(
                path, "Template has no name.", true
            ))
            val errors = ArrayList<UppaalError>()

            // Register as sub-template
            if (temName.startsWith(SUB_TEMPLATE_NAME_PREFIX)) {
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
                        path, template.parameter!!.content, template.parameter!!.content.indices, "Sub-templates currently do not support parameters."
                    ))

                subTemplates[temName] = SubTemplateInfo(entryId, exitIds, isFaulty = template.init?.ref == null || errors.isNotEmpty())
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
                    errors.add(createUppaalError(
                        locPath.plus(locAndIndex.value.name!!), locNameContent, "To insert a sub-template, format the location's name as: \"::[sub-template name] [instantiated name]::\" or \"::[sub-template name]::\"."
                    ))
                    continue
                }

                // Determine if location is valid sub-tem insertion point
                val locId = locAndIndex.value.id
                if (template.init?.ref == locId)
                    errors.add(createUppaalError(
                        locPath, "An initial/entry location cannot be a sub-template instance."
                    ))
                if (template.transitions.none { it.target.ref == locId } && subTemplates.containsKey(locNameContent))
                    errors.add(createUppaalError(
                        locPath, "An exit location cannot be a sub-template instance."
                    ))
                if (errors.isNotEmpty())
                    continue

                // Register the sub-template's info
                val subTemplateName = nameAndInstance[0].ensureStartsWith(SUB_TEMPLATE_NAME_PREFIX)
                val subTemInstanceName = nameAndInstance.getOrNull(1)
                subTemplateUsages.add(SubTemplateUsage(locId, subTemplateName, subTemInstanceName, locNameContent))

                // Give the state the user-defined if present
                if (null != subTemInstanceName) {
                    val namePath = locPath.plus(locAndIndex.value.name!!)
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
                    val parameterValueRanges = getParameterRanges(parameters, path.plus(template.parameter!!), errors)
                    numSubTemplateUsers[temName] = FreeInstantiation(null, parameterValueRanges)
                }
            }

            // Double check sub-template faulty-ness
            if (subTemplates.containsKey(temName) && errors.isNotEmpty())
                subTemplates[temName]?.isFaulty = true

            return errors
        }

        /** Find the (transitive) structure of partial instantiations and which are actually instantiated on the system line. **/
        private fun indexSystem(path: UppaalPath, system: System): List<UppaalError> {
            val errors = ArrayList<UppaalError>()
            registerTypedefsAndConstants(system.content)

            // Register (transitive) relationships (if any) between partial instantiations and templates that use sub-templates
            for (tree in ConfreHelper.partialInstantiationConfre.findAll(system.content).map { it as Node }) {
                val baseName = tree.children[3]!!.toString()
                if (subTemplates.containsKey(baseName))
                    errors.add(createUppaalError(
                        path, system.content, tree.children[3]!!.range(), "Sub-templates cannot be instantiated by user-code.", true
                    ))
                else if (numSubTemplateUsers.containsKey(baseName)) {
                    val parameterValueRanges =
                        if (tree.children[1]?.isNotBlank() == true) {
                            val parameters = system.content
                                .substring(tree.children[1]!!.range())
                                .drop(1).dropLast(1)
                            getParameterRanges(parameters, path, system.content, tree.children[1]!!.range(), errors)
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
                    errors.add(createUppaalError(
                        path, system.content, subTemplateIdentNode, "Sub-templates cannot be instantiated by user-code.", true
                    ))
                systemLine.putAll(identNodes.map { Pair(it.toString(), it.range()) }.filter { numSubTemplateUsers.containsKey(it.first) })
            }

            return errors
        }


        private fun getParameterRanges(parameters: String, parameterPath: UppaalPath, errors: ArrayList<UppaalError>): ArrayList<FreeParameter?>
            = getParameterRanges(parameters, parameterPath, parameters, parameters.indices, errors)

        /** For each parameter in a template or partial instantiation, try to get the range of values a free instantiation
         * would produce. E.g., the parameter "const int[0,5] ID" would produce numbers in the range (0 .. 5) **/
        private fun getParameterRanges(parameters: String?, parameterPath: UppaalPath, fullText: String, parameterIndices: IntRange, errors: ArrayList<UppaalError>): ArrayList<FreeParameter?> {
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
                    errors.add(createUppaalError(
                        parameterPath, fullText, parameterIndices, "Users of sub-templates cannot have scalar parameters."
                    ))
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
                    constInts[pair.second.children[3].toString()] = code.substring(pair.second.children[5]!!.range()).toIntOrNull() ?: continue
        }


        override fun mapModelErrors(errors: List<UppaalError>)
            = errors.filter { rewriters[it.path]?.backMapError(it) != BackMapResult.REQUEST_DISCARD }
    }

    private class ReferenceCheckingPhase(
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>
    ) : ModelPhase() {
        init {
            register(::verifyTemplate)
        }


        /** Simply check if there are any obvious (logic) errors on a template wrt. SeComp. **/
        private fun verifyTemplate(path: UppaalPath, template: Template): List<UppaalError> {
            val errors = ArrayList<UppaalError>()
            val templateName = template.name.content ?: return listOf()
            val subTemUsages = baseSubTemplateUsers[templateName] ?: return listOf()

            for (usage in subTemUsages)
                if (usage.subTemplateName !in subTemplates.keys) {
                    val faultyLocationWithIndex = template.locations.withIndex().find { it.value.id == usage.insertLocationId }!!
                    val locationPath = path.plus(faultyLocationWithIndex)
                    errors.add(createUppaalError(
                        locationPath, usage.originalText, usage.originalText.indices, "The template name '${usage.subTemplateName}' either does not exist or is not a sub-template.", true
                    ))
                }

            return errors + checkCircularUse(path, listOf(templateName))
        }

        /** If two sub-templates (transitively) includes each other, this would result in infinite instances. This
         * function thus checks for this and reports an error if circular inclusion is detected. **/
        private fun checkCircularUse(path: UppaalPath, branch: List<String>): List<UppaalError> {
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
    }

    private class MappingPhase(
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        val numSubTemplateUsers: HashMap<String, FreeInstantiation>,
        val systemLine: HashMap<String, IntRange>,
        val subTemToParent: HashMap<Pair<String, Int>, Triple<String, Int, Int>>, // subTem:name+instance -> parentTem:name + instance + subTemIndex
        val backMapOfBubbledUpProcesses: HashMap<String, String> // New placeholder partial inst name -> original partial inst or root template name.
    ) : ModelPhase() {
        val totalNumInstances = HashMap<String, Int>() // Any-template name -> instance count
        val partialToRootStartIndex = HashMap<String, Int>() // partial (or root) instance name -> start root instance index

        private val rewriters = HashMap<String, Rewriter>()


        init {
            register(::mapGlobalDeclaration, prefix = listOf(Nta::class.java))
            register(::mapTemplate)
            register(::mapSystem)
        }


        /** Adds global variables to control which sub-templates are active/inactive. **/
        private fun mapGlobalDeclaration(path: UppaalPath, declaration: Declaration): List<UppaalError> {
            findTotalNumbersOfInstances()

            val nextSubTemIndex = HashMap<String, Int>()
            val stateVariables = StringBuilder("\n\n")
            for (template in baseSubTemplateUsers.keys.plus(subTemplates.keys).distinct()) {
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

            subTemToParent[Pair(subTemName, nextSubTemIndex)] = Triple(userTemName, nextUserTemIndex, subTemIndexInUser)

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


        private fun mapTemplate(path: UppaalPath, template: Template): List<UppaalError> {
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
            for (entryTransition in template.transitions.withIndex().filter { it.value.source.ref == subTemplateInfo.entryStateId }) {
                val transitionPath = path.plus(entryTransition)

                val guardLabel = entryTransition.value.labels.find { it.kind == "guard" }
                    ?: generateAndAddLabel("guard", template, entryTransition.value)
                val guardPath = transitionPath.plus(guardLabel, entryTransition.value.labels.indexOf(guardLabel) + 1)

                // Add activation guard
                val guardRewriter = rewriters.createOrGetRewriter(guardPath, guardLabel.content)
                if (guardLabel.content.isNotBlank()) {
                    guardRewriter.insert(0, "(")
                    guardRewriter.append(") && ")
                }
                guardRewriter.append("STEM_ACTIVE_${template.name.content!!}[STEM_INDEX]")
                guardLabel.content = guardRewriter.getRewrittenText()
            }

            for (exitTransition in template.transitions.withIndex().filter { it.value.target.ref in subTemplateInfo.exitStateIds }) {
                val transitionPath = path.plus(exitTransition)

                val updateLabel = exitTransition.value.labels.find { it.kind == "assignment" }
                    ?: generateAndAddLabel("assignment", template, exitTransition.value)
                val updatePath = transitionPath.plus(updateLabel, exitTransition.value.labels.indexOf(updateLabel) + 1)

                // Add deactivation statement
                val updateRewriter = rewriters.createOrGetRewriter(updatePath, updateLabel.content)
                if (updateLabel.content.isNotBlank())
                    updateRewriter.append(", ")
                updateRewriter.append("STEM_ACTIVE_${template.name.content!!}[STEM_INDEX] = false")
                updateLabel.content = updateRewriter.getRewrittenText()

                // Since sub-templates should be reusable, all transitions going to an exit state are redirected to the start state.
                exitTransition.value.target.ref = subTemplateInfo.entryStateId!!
            }

            // Add "sub-template index" parameter which is used identify each instance of this template (as a sub-template)
            if (template.parameter == null)
                template.parameter = Parameter("")
            val parameterRewriter = rewriters.createOrGetRewriter(path.plus(template.parameter!!), template.parameter!!.content)
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
                    val guardPath = transitionPath.plus(guardLabel, outgoing.value.labels.indexOf(guardLabel) + 1)

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
                    val updatePath = transitionPath.plus(updateLabel, ingoing.value.labels.indexOf(updateLabel) + 1)

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
            val parameterRewriter = rewriters.createOrGetRewriter(path.plus(template.parameter!!), template.parameter!!.content)
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


        private fun mapSystem(path: UppaalPath, system: System): List<UppaalError> {
            val systemLineNode = ConfreHelper.systemLineConfre.find(system.content) as? Node
                ?: return listOf(createUppaalError(path, system.content, IntRange(system.content.length, system.content.length), "Missing 'system' line at end of system declarations.", true))

            val systemLineStartIndex = systemLineNode.startPosition()
            val rewriter = rewriters.createOrGetRewriter(path, system.content)

            // Map all partial instantiations
            val errors = ArrayList<UppaalError>()
            val nextRootIndices = HashMap<String, Int>() // root name -> next base index
            for (tree in ConfreHelper.partialInstantiationConfre.findAll(rewriter.originalText).map { it as Node }) {
                val lhsName = tree.children[0]!!.toString()
                val instantiationInfo = numSubTemplateUsers[lhsName] ?: continue

                val rootTemplateName = getRootTemplate(lhsName)
                val nextRootIndex = nextRootIndices.getOrPut(rootTemplateName) { 0 }

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
                        bubbleUp(instantiationInfo, errors, path, rewriter, lhsName, rootTemplateName, nextRootIndex, systemLineStartIndex, nextRootIndices)

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
                        (if (rhsHasArgs) ", " else "") + generateUserIndexExpression(parameterRanges, nextRootIndex)
                    else
                        (if (rhsHasArgs) ", " else "") + nextRootIndex // Default "unique instance index"

                    val indexAfterLastRhsParameter = (tree.children[6]!!.endPosition()) // Index of end-parenthesis
                    rewriter.insert(indexAfterLastRhsParameter, argumentToAdd)

                    // Add indexing and keep track of the next/current indices to use for the current root template
                    partialToRootStartIndex[lhsName] = nextRootIndices[rootTemplateName]!!
                    nextRootIndices[rootTemplateName] = nextRootIndices[rootTemplateName]!! + (totalNumInstances[lhsName] ?: 0)
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
                val nextRootIndex = nextRootIndices.getOrPut(lhsName) { 0 } // lhsName is already a root
                val instantiationInfo = numSubTemplateUsers[lhsName]!!
                bubbleUp(instantiationInfo, errors, path, rewriter, lhsName, lhsName, nextRootIndex, systemLineStartIndex, nextRootIndices)
            }

            rewriter.insert(systemLineStartIndex, "\n")
            system.content = rewriter.getRewrittenText()
            return errors
        }


        /** Replace an instantiation on the system line with a new instantiation that does not contain a USER/STEM-index parameter,
         * since that parameter is not "free" and thus hinders the (partial) template from being instantiated otherwise. **/
        private fun bubbleUp(
            instantiationInfo: FreeInstantiation,
            errors: ArrayList<UppaalError>,
            path: UppaalPath,
            rewriter: Rewriter,
            lhsName: String,
            rootTemplateName: String,
            nextRootIndex: Int,
            systemLineStartIndex: Int,
            nextRootIndices: HashMap<String, Int>
        ) {
            if (null in instantiationInfo.parameters) {
                errors += createUppaalError(
                    path,
                    rewriter.originalText,
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

            rewriter.insert(systemLineStartIndex, "${lhsPrimeName}($lhsParameters) = $lhsName($rhsArguments);\n")
            rewriter.replace(systemLine[lhsName]!!, lhsPrimeName)
                .addBackMap()
                .activateOn(ActivationRule.ACTIVATION_CONTAINS_ERROR)
                .overrideErrorRange { systemLine[lhsName]!! }

            partialToRootStartIndex[lhsPrimeName] = nextRootIndices[rootTemplateName]!!
            nextRootIndices[rootTemplateName] = nextRootIndices[rootTemplateName]!! + getTotalInstancesFromParams(instantiationInfo)

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


        override fun mapModelErrors(errors: List<UppaalError>)
            = errors.filter { rewriters[it.path]?.backMapError(it) != BackMapResult.REQUEST_DISCARD }
    }


    private class SeCompSimulatorPhase(
        val subTemplates: HashMap<String, SubTemplateInfo>,
        val baseSubTemplateUsers: HashMap<String, MutableList<SubTemplateUsage>>,
        val subTemToParent: HashMap<Pair<String, Int>, Triple<String, Int, Int>>,
        val backMapOfBubbledUpProcesses: HashMap<String, String>
    ) : SimulatorPhase() {
        override fun mapProcesses(processes: List<ProcessInfo>) {
            // TODO: Mappings to/from? auto-generated padding-partial-instantiation names?
            // TODO: Mappings for query phase

            val subTemInstanceCount = HashMap<Pair<String, String>, Int>() // Parent instance name, SubTemplate name -> Sub template count
            val userTemplateAndIndexToName = HashMap<Pair<String, Int>, String>() // Parent

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
                            val parentInstanceName = userTemplateAndIndexToName[parent]!!
                            val parentTem = parentUserInfo.first
                            val subTemUsageIndex = parentUserInfo.third
                            var subTemInstanceName = baseSubTemplateUsers[parentTem]!![subTemUsageIndex].subTemInstanceName
                            if (null == subTemInstanceName) {
                                val nextAutoGeneratedSubTemIndex = subTemInstanceCount[Pair(parentInstanceName, currentTem)] ?: 0
                                subTemInstanceName = "${currentTem.drop(SUB_TEMPLATE_NAME_PREFIX.length)}($nextAutoGeneratedSubTemIndex)"
                                subTemInstanceCount[Pair(parentInstanceName, currentTem)] = nextAutoGeneratedSubTemIndex + 1
                            }
                            process.name = "${parentInstanceName}.$subTemInstanceName"
                            if (isUser)
                                userTemplateAndIndexToName[subTemId] = process.name
                            finishedMappings.add(process)
                            continue
                        }
                    }
                    else if (isUser) {
                        // If a partial instantiation or root template has been "bubbled up", replace with original name.
                        val baseName = process.name.substringBefore('(')
                        if (backMapOfBubbledUpProcesses.containsKey(baseName))
                            process.name = process.name.replace(baseName, backMapOfBubbledUpProcesses[baseName]!!)

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