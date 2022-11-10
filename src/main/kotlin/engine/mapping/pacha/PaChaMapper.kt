package engine.mapping.pacha

import engine.mapping.*
import engine.parsing.Confre
import engine.parsing.Node
import org.simpleframework.xml.Serializer
import org.simpleframework.xml.core.Persister
import uppaal_pojo.*
import java.io.InputStream
import java.io.StringWriter
import java.util.LinkedList


class PaChaMap : HashMap<String, LinkedList<String>>()

class PaChaMapper : Mapper {
    override fun getPhases(): Pair<Sequence<ModelPhase>, QueryPhase?>
        = Pair(sequenceOf(Phase1()), null)

    private class Phase1 : ModelPhase()
    {
        val globalPaChas = PaChaMap()
        val templatePaChaMaps: HashMap<String, PaChaMap> = HashMap()

        private val chanDeclGrammar = Confre("""
            IDENT = [_a-zA-Z][_a-zA-Z0-9]*
            INT = -?[0-9]+

            ChanDecl :== 'chan' '(' Type {',' Type} ')' ['&'] IDENT {Array} [';'] .
            Type     :== ['&'] ('chan' ['(' Type ')'] | 'int' | 'bool') {Array} .
            Array    :== '[' (INT | IDENT) ']' .
        """.trimIndent())

        init {
            register(::mapDeclaration)
            //register(::mapSystem)
        }

        private fun mapDeclaration(path: List<PathNode>, declaration: Declaration): List<UppaalError> {
            val (newContent, errors) = mapParameterizedChannel(declaration.content, true, globalPaChas) // TODO FIX last param
            declaration.content = newContent
            return errors
        }


        private fun mapParameterizedChannel(code: String, inDeclaration: Boolean, scope: PaChaMap): Pair<String, List<UppaalError>> {
            val errors = ArrayList<UppaalError>()
            var offset = 0
            var newCode = code
            for (chan in chanDeclGrammar.findAll(code).map { it as Node }) {
                if (!chan.children[8]!!.notBlank() && inDeclaration)
                    continue // TODO: Forgot Semicolon

                val typesStart = chan.children[1]!!.startPosition() + offset
                val typesEnd = chan.children[4]!!.endPosition() + 1 + offset
                val typesLength = typesEnd - typesStart + 1
                newCode = newCode.replaceRange(typesStart, typesEnd, " ".repeat(typesLength))

                for (type in listOf(chan.children[2]).plus((chan.children[3] as Node).children)) {

                }

                println("YO!")

                // offset += replacement.length - defaultValueNode.length()
            }

            return Pair(newCode, errors)
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

            paChas[chanName] = params
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

            templatePaChas[chanName] = params
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
        if (expressions.size != (templatePaChas[chanName]?.size ?: globalPaChas[chanName]?.size ?: -1))
            throw Exception("Channel '$chanName' used with wrong number of parameters in '${shout.groups[0]!!.value}'")

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
        if (variables.size != (templatePaChas[chanName]?.size ?: globalPaChas[chanName]?.size ?: -1))
            throw Exception("Channel '$chanName' used with wrong number of parameters in '${listen.groups[0]!!.value}'")

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
                val paCha: List<String> = localPaChas[possibleChanName] ?: globalPaChas[possibleChanName] ?: continue
                for ((index, _) in paCha.withIndex())
                    newParams.add("${possibleChanName}_p${index + 1}")
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