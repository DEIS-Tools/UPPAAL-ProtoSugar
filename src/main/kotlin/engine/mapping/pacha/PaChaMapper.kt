package engine.mapping.pacha

import engine.mapping.*
import engine.parsing.Confre
import engine.parsing.Node
import engine.parsing.ParseTree
import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import uppaal_pojo.*
import java.io.InputStream
import java.io.StringWriter
import java.util.LinkedList


data class PaChaInfo(val numParameters: Int, val numDimensions: Int)
class PaChaMap : HashMap<String, PaChaInfo>()

class PaChaMapper : Mapper {
    override fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?>
        = Pair(sequenceOf(Phase1()), null)

    private class Phase1 : ModelPhase()
    {
        private val PARAMETER_TYPE_HINT = "PARAM_TYPE"
        private val backMaps = HashMap<Quadruple<Int, Int, Int, Int>, Pair<Quadruple<Int, Int, Int, Int>, String>>()
        private val paChaMaps: HashMap<String?, PaChaMap> = hashMapOf(Pair(null, PaChaMap()))


        private val chanDeclGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = -?[0-9]+

            ChanDecl :== IDENT TypeList ['&'] IDENT {Array} [';'] .
            Type     :== ['&'] IDENT [TypeList] {Array} .
            TypeList :== '(' [Type] {',' [Type]} ')' .
            Array    :== '[' [INT | IDENT] ']' .
        """.trimIndent())

        private val paChaUseGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*

            ChanDecl :== IDENT '(' [['meta'] IDENT] {',' [['meta'] IDENT]} ')' ('!' | '?') .
        """.trimIndent())


        init {
            register(::registerTemplatePaChaMap)

            register(::mapDeclaration)
            register(::mapParameter)
            // map transitions
            register(::mapSystem)
        }


        private fun registerTemplatePaChaMap(path: List<PathNode>, template: Template): List<UppaalError> {
            paChaMaps[
                template.name ?: return listOf(UppaalError(path, 1,1,1,1, "Template has no name.", "", isUnrecoverable = true))
            ] = PaChaMap()
            return listOf()
        }


        private fun mapDeclaration(path: List<PathNode>, declaration: Declaration): List<UppaalError> {
            val parent = path.takeLast(2).first().element
            val paChaMap = paChaMaps[(parent as? Template)?.name]!!

            val (newContent, errors) = mapPaChaDeclarations(path, declaration.content, paChaMap)
            declaration.content = newContent
            return errors
        }

        private fun mapSystem(path: List<PathNode>, system: System): List<UppaalError> {
            val (newContent, errors) = mapPaChaDeclarations(path, system.content, paChaMaps[null]!!)
            system.content = newContent
            return errors
        }

        private fun mapParameter(path: List<PathNode>, parameter: Parameter): List<UppaalError> {
            val template = path.takeLast(2).first().element as Template
            val paChaMap = paChaMaps[template.name]!!

            val (newContent, errors) = mapPaChaDeclarations(path, parameter.content, paChaMap)
            parameter.content = newContent
            return errors
        }


        private fun mapPaChaDeclarations(path: List<PathNode>, code: String, scope: PaChaMap): Pair<String, List<UppaalError>> {
            val inDeclarationOrSystem = path.last().element !is Parameter // In "system/declaration" or in "parameter"
            val errors = ArrayList<UppaalError>()
            var offset = 0
            var newCode = code
            for (chan in chanDeclGrammar.findAll(code).map { it as Node }.filter { isPaChaDecl(path, code, it, errors) }) {
                val forgotSemicolon = inDeclarationOrSystem && checkSemicolon(path, code, chan, errors)
                val chanName = chan.children[3]!!.toString()

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
                            if (inDeclarationOrSystem)
                                " meta $typeName __${chanName}_p${pair.index+1}${array};"
                            else
                                ", $typeName __${chanName}_p${pair.index+1}${array}"
                        newCode = newCode.replaceRange(insertPosition, insertPosition, parameterVariableDecl)

                        // To map "unknown type" errors back to new syntax
                        val newLinesAndColumns =
                            if (inDeclarationOrSystem)
                                getLinesAndColumnsFromRange(newCode, IntRange(insertPosition+6, insertPosition+6 + typeName.length-1))
                            else
                                getLinesAndColumnsFromRange(newCode, IntRange(insertPosition+2, insertPosition+2 + typeName.length-1))
                        backMaps[newLinesAndColumns] = Pair(getLinesAndColumnsFromRange(code, pair.value.range()), PARAMETER_TYPE_HINT)

                        // Update
                        offset += parameterVariableDecl.length
                        insertPosition += parameterVariableDecl.length
                    }
                }

                val numTypes = if (typeErrors.isEmpty()) typeNodes.size else -1 // "-1" means "mapper ignore for now"
                val numDimensions = (chan.children[4] as Node).children.size
                scope[chanName] = PaChaInfo(numTypes, numDimensions)
            }

            return Pair(newCode, errors)
        }

        private fun isPaChaDecl(path: List<PathNode>, code: String, decl: Node, errors: ArrayList<UppaalError>): Boolean {
            val nameNode = decl.children[0]!!
            val typeListNode = decl.children[1]!!
            if (nameNode.toString() == "chan")
                return true
            else if (typeListNode.isNotBlank())
                errors.add(createUppaalError(path, code, typeListNode, "Only the 'chan' type can have a parameter-type-list."))

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


class PaChaMapperOld {
    private val serializer: Serializer = Persister()

    private val chanDeclPattern = Regex("""chan\s*(\(\s*[^,()\s]+(?>\s*,\s*[^,()\s]+)*\s*\))(\s+|\s*&\s*)([^;&,()\[\]\s]+)\s*(\[\s*[^,;\[\]]+\])?;?""")
    private val typeInsideListPattern = Regex("""[^,()\s]+""")
    private val paramShoutPattern = Regex("""([^,()\[\]\s]+)\s*(?>\((.*)\))\s*(\[\s*[^,()\[\]]*\])?\s*!""")
    private val paramListenPattern = Regex("""([^,()\[\]\s]+)\s*(?>\(([^\(\)]*)\))\s*(\[\s*[^,()\[\]]*\])?\s*\?""")

    fun map(stream: InputStream): String
    {
        stream.bufferedReader().use { return map(it.readText()) }
    }

    fun map(uppaalXml: String): String
    {
        val beforeNtaElement = uppaalXml.substringBefore("<nta>")
        val ntaElement = uppaalXml.substring(uppaalXml.indexOf("<nta>"))

        val convertedNta: Nta = convertNta(serializer.read(Nta::class.java, ntaElement))

        StringWriter().use {
            serializer.write(convertedNta, it)
            return beforeNtaElement + it.buffer.toString()
        }
    }

    private fun convertNta(nta: Nta): Nta
    {
        //val result = MapperEngine().parseNta(nta);
        val globalPaChas = PaChaMap()
        val templatePaChaMaps: HashMap<String, PaChaMap> = HashMap()

        nta.declaration.content = mapDeclaration(nta.declaration.content, globalPaChas)
        convertTemplates(nta.templates, globalPaChas, templatePaChaMaps)
        nta.system.content = mapSystem(nta.system.content, globalPaChas, templatePaChaMaps)

        return nta
    }

    private fun mapDeclaration(declarations: String, paChas: PaChaMap): String
    {
        var newDeclarations = declarations

        for (match in chanDeclPattern.findAll(declarations))
        {
            val hasAmp = match.groups[2]!!.value.contains('&')
            val chanName = match.groups[3]!!.value
            val params = LinkedList<String>()

            var newDecl = "chan ${if (hasAmp) "&" else ""}${chanName}${match.groups[4]?.value ?: ""};" // &ch[size]
            for (type in typeInsideListPattern.findAll(match.groups[1]!!.value).map { it.value })
            {
                val baseType = type.substringBefore('[')
                val arrayType = if (baseType.length != type.length) type.substring(baseType.length) else ""

                newDecl += " meta $baseType ${chanName}_p${params.size+1}${arrayType};"

                //params.add(ChannelParameter(baseType, arrayType))
            }

            //paChas[chanName] = params
            newDeclarations = newDeclarations.replaceFirst(match.value, newDecl)
        }

        return newDeclarations
    }

    private fun convertTemplates(templates: List<Template>, globalPaChas: PaChaMap, templatePaChaMaps: HashMap<String, PaChaMap>)
    {
        for (template in templates)
        {
            val templatePaChas = PaChaMap()

            if (null != template.parameter) template.parameter!!.content= mapParameters(template.parameter!!.content, templatePaChas)
            if (null != template.declaration) template.declaration!!.content= mapDeclaration(template.declaration!!.content, templatePaChas)
            if (null != template.transitions)
                for (transition in template.transitions!!)
                    convertTransition(transition, globalPaChas, templatePaChas)

            templatePaChaMaps[template.name ?: ""] = templatePaChas
        }
    }

    private fun mapParameters(parameters: String, templatePaChas: PaChaMap): String
    {
        var newParameters = parameters

        for (match in chanDeclPattern.findAll(parameters))
        {
            val parameterList = match.groups[1]!!.value
            val hasAmp = match.groups[2]!!.value.contains('&')
            val chanName = match.groups[3]!!.value
            val arrayPart = match.groups[4]?.value ?: ""
            val params = LinkedList<String>()

            var newParam = "chan ${if (hasAmp) "&" else ""}${chanName}${arrayPart}"
            for (type in typeInsideListPattern.findAll(parameterList).map { it.value })
            {
                val baseType = type.substringBefore('[')
                val arrayType = if (baseType.length != type.length) type.substring(baseType.length) else ""

                newParam += ", $baseType &${chanName}_p${params.size+1}${arrayType}"

                //params.add(ChannelParameter(baseType, arrayType))
            }

            //templatePaChas[chanName] = params
            newParameters = newParameters.replaceFirst(match.value, newParam)
        }

        return newParameters
    }

    private fun convertTransition(transition: Transition, globalPaChas: PaChaMap, templatePaChas: PaChaMap)
    {
        val sync: Label = transition.labels?.find { it.kind == "synchronisation" } ?: return
        val update: Label = transition.labels!!.find { it.kind == "assignment" }
            ?: Label("assignment", sync.x, sync.y + 17)
                .let { label -> transition.labels!!.add(label); label }

        convertShout(sync, update, globalPaChas, templatePaChas)
        convertListen(sync, update, globalPaChas, templatePaChas)
    }

    @Throws(Exception::class)
    private fun convertShout(sync: Label, update: Label, globalPaChas: PaChaMap, templatePaChas: PaChaMap)
    {
        val shout = paramShoutPattern.find(sync.content) ?: return
        val chanName = shout.groups[1]!!.value
        val params = shout.groups[2]!!.value
        val subscript = shout.groups[3]?.value ?: ""
        val originalUpdate = update.content

        // If there is not an equal amount of '(' and ')', this is formatted incorrectly
        if (sync.content.count { it == '(' } != sync.content.count { it == ')' })
            return

        val expressions = extractExpressions(params).toMutableList()
        //if (expressions.size != (templatePaChas[chanName]?.size ?: globalPaChas[chanName]?.size ?: -1))
        //    throw Exception("Channel '$chanName' used with wrong number of parameters in '${shout.groups[0]!!.value}'")

        sync.content = "$chanName$subscript!"
        update.content = expressions.withIndex().joinToString(
            separator = ", ",
            transform = { (index, expr) -> "${chanName}_p${index+1} = $expr" }
        )

        if (update.content.isNotBlank() && originalUpdate.isNotBlank())
            update.content += ", $originalUpdate"
        else if (update.content.isBlank() && originalUpdate.isNotBlank())
            update.content = originalUpdate
    }

    @Throws(Exception::class)
    private fun extractExpressions(params: String): Sequence<String>
    {
        return sequence {
            var startIndex = 0
            var nestingDepth = 0
            for ((index, ch) in params.withIndex())
                if (ch == '(') ++nestingDepth
                else if (ch == ')') --nestingDepth
                else if (nestingDepth < 0) throw Exception("the parentheses in '$params' are messed up!")
                else if (nestingDepth == 0 && ch == ',') {
                    yield(params.substring(startIndex, index).trim())
                    startIndex = index+1
                }
            yield(params.substring(startIndex, params.length).trim())
        }
    }

    @Throws(Exception::class)
    private fun convertListen(sync: Label, update: Label, globalPaChas: PaChaMap, templatePaChas: PaChaMap)
    {
        val listen = paramListenPattern.find(sync.content) ?: return
        val chanName = listen.groups[1]!!.value
        val params = listen.groups[2]!!.value
        val subscript = listen.groups[3]?.value ?: ""
        val originalUpdate = update.content

        val varPattern = Regex("""(meta\s+)?[a-zA-Z_]([a-zA-Z0-9_])*""")
        val variables = params.split(',').map { it.trim() }
        val malformedVars = variables.filter { !varPattern.matches(it) }
        if (malformedVars.isNotEmpty())
            throw Exception("Malformed parameters in '${listen.groups[0]!!.value}'. See: '${malformedVars.joinToString(", ")}'")
        //if (variables.size != (templatePaChas[chanName]?.size ?: globalPaChas[chanName]?.size ?: -1))
        //    throw Exception("Channel '$chanName' used with wrong number of parameters in '${listen.groups[0]!!.value}'")

        val (metaVars, nonMetaVars) = variables.withIndex().partition { it.value.startsWith("meta") }
        var metaMappedUpdate = originalUpdate
        for ((index, varNameWithMeta) in metaVars)
            metaMappedUpdate = metaMappedUpdate.replace(varNameWithMeta.replace("""meta\s+""".toRegex(), ""), "${chanName}_p${index + 1}")

        sync.content = "$chanName$subscript?"
        update.content = nonMetaVars.joinToString(
            separator = ", ",
            transform = { (index, varName) -> "$varName = ${chanName}_p${index+1}" }
        )

        if (update.content.isNotBlank() && metaMappedUpdate.isNotBlank())
            update.content += ", $metaMappedUpdate"
        else if (update.content.isBlank() && metaMappedUpdate.isNotBlank())
            update.content = metaMappedUpdate
    }

    private fun mapSystem(system: String, globalPaChas: PaChaMap, templatePaChaMaps: HashMap<String, PaChaMap>): String
    {
        val localPaChas = PaChaMap()
        var newSystem = mapDeclaration(system, localPaChas)

        for ((template, params, oldInstantiation) in extractParameterizedProcessInstantiations(newSystem, templatePaChaMaps.keys))
        {
            val newParams = LinkedList<String>()
            for (expr in extractExpressions(params))
            {
                newParams.add(expr)
                val possibleChanName = expr.substringBefore('[') // expr.replace(Regex("""\[\s*[^,\[\]]+\]"""), "") // Remove subscripts
                //val paCha: List<String> = localPaChas[possibleChanName] ?: globalPaChas[possibleChanName] ?: continue
                //for ((index, _) in paCha.withIndex())
                //    newParams.add("${possibleChanName}_p${index + 1}")
            }

            val newInstantiation = "$template(${newParams.joinToString(", ")})"
            newSystem = newSystem.replace(oldInstantiation, newInstantiation)
        }

        return newSystem
    }

    // Returns Triple(template name , parameter list, full match)
    private fun extractParameterizedProcessInstantiations(system: String, templateNames: Set<String>): Set<Triple<String, String, String>>
    {
        val instantiations = HashSet<Triple<String, String, String>>()
        val processPattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\s*\(((?>[^:()]|\([^:()]*\))*)\)""")
        for (match in processPattern.findAll(system.replace(Regex("""gantt\s*\{[^\}]*\}"""), "")))
            if (templateNames.contains(match.groups[1]!!.value))
                instantiations.add(Triple(match.groups[1]!!.value, match.groups[2]!!.value, match.groups[0]!!.value))
        return instantiations
    }
}