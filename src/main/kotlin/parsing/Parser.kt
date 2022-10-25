package parsing

import uppaal_pojo.Nta
import java.lang.Exception
import kotlin.collections.ArrayList

class Parser {
    // (?>const\s*)?(int(\[[^\]]\])?|chan)\s*(\((?>\s*(?>[^,()]|\([^()]*\))*,?\s*)*\))?(\s+|\s*&\s*)([^;&,(){}\[\]=\s]+)\s*((?>\[\s*(?>[^;\[\]]|\[[^;\[\]]+\])+\]\s*)*)(=\s*([^;]*))?;?
    // ^(?>(?>const|meta)\s*)?([a-zA-Z][^()\s&]*)\s*(\((?>\s*(?>[^,()]|\([^()]*\))*,?\s*)*\))?(\s+|\s*&\s*)([^;&,(){}\[\]=\s]+)\s*((?>\[\s*(?>[^;\[\]]|\[[^;\[\]]+\])+\]\s*)*)(=\s*([^;]*))?

    private val chanDeclPattern = Regex("""chan\s*(\((?>\s*(?>[^,()]|\([^()]*\))*,?\s*)*\))?(\s+|\s*&\s*)([^;&,()\[\]\s]+)\s*((?>\[\s*(?>[^;\[\]]|\[[^;\[\]]+\])+\]\s*)*)\s*(;|,|${'$'})""") // https://regex101.com/r/DEMVV6/1
    private val intDeclPattern = Regex("""(?>const\s*)int(\[[^\]]\])?\s*(\s+|\s*&\s*)([^;&,(){}\[\]=\s]+)\s*((?>\[\s*(?>[^;\[\]]|\[[^;\[\]]+\])+\]\s*)*)(?>=\s*([^;]*));?""") // https://regex101.com/r/MSXXOk/1

    private val arrayDimensionPattern = Regex("""\[(\s*(?>[^;\[\]]|\[[^;\[\]]+\])+)\]""") // Taken from the above regex
    private val typeInsideListPattern = Regex("""(?>[^,()]+|\([^()]*\))*""") // https://regex101.com/r/SXLc2i/1
    private val paramShoutPattern = Regex("""([^,()\[\]\s]+)\s*(?>\((.*)\))\s*(\[\s*[^,()\[\]]*\])?\s*!""")
    private val paramListenPattern = Regex("""([^,()\[\]\s]+)\s*(?>\(([^\(\)]*)\))\s*(\[\s*[^,()\[\]]*\])?\s*\?""")

    fun parseNta(nta: Nta): GlobalScope {
        val globalScope = GlobalScope(nta)

        parseDeclarations(nta.declarations, globalScope)

        return globalScope
    }


    /// Perhaps make basic parser architecture but allow mappers to inject specific parsing code into the parser to allow extracting and mapping specific elements.


    private fun parseDeclarations(declarations: String, scope: Scope)
    {
        val chanDecls = chanDeclPattern.findAll(declarations).toList()
        val intDecls = intDeclPattern.findAll(declarations).toList()

        for (match in chanDeclPattern.findAll(declarations))
        {
            val parameterList = match.groups[1]?.value?.trim('(', ')')
            val isReference = match.groups[2]!!.value.contains('&')
            val identifier = match.groups[3]!!.value
            val dimensionList = match.groups[4]?.value ?: ""

            val parameters = ArrayList<String>()
            if (null != parameterList)
                for ((index, parameterType) in typeInsideListPattern.findAll(parameterList).map { it.value }.withIndex())
                {
                    if (parameterType.isBlank())
                        throw Exception("Channel '${match.value}' has blank parameter type on location '${index+1}'")

                    val paramBaseType = parameterType.substringBefore('[')
                    val paramDimensions = arrayDimensionPattern.findAll(paramBaseType).map { it.value }.toList()

                    //parameters.add(ChannelParameter(paramBaseType, paramDimensions))
                }
            val dimensions = arrayDimensionPattern.findAll(dimensionList).map { it.value }.toList()

            //scope.declarations.add(ChannelDeclaration(parameters, isReference, identifier, dimensions))
        }
    }
}