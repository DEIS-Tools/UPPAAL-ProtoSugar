package mapping.interception.mapping

import jsonFy
import mapping.Orchestrator
import mapping.interception.InterceptStreams
import mapping.interception.Interceptor
import mapping.mapping.ProcessInfo
import mapping.parsing.Confre
import mapping.parsing.Node
import offset
import unJsonFy
import uppaal.error.UppaalError
import uppaal.error.UppaalPath
import uppaal.error.createUppaalError
import writeToFile
import java.io.BufferedReader
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString


private const val MODEL_CMD_PREFIX = "{\"cmd\":\"newXMLSystem3\",\"args\":\""               // Continues until: "}
private const val MODEL_ERROR_RESPONSE_PREFIX = "{\"res\":\"error\",\"info\":{\"errors\":"  // Continues until: ]}}
private const val MODEL_SUCCESS_RESPONSE_PREFIX = "{\"res\":\"ok\",\"info\":{\"warnings\":" // Continues until: ]}}

private const val QUERY_CMD_PREFIX = "{\"cmd\":\"modelCheck\",\"args\":\""                              // Continues until: "}
private const val QUERY_ERROR_RESPONSE_PREFIX = "{\"res\":\"ok\",\"info\":{\"status\":\"E\",\"error\":" // Continues until: }}

private val errorListConfre = Confre("""
            INT = ([1-9][0-9]*)|0*
            STRING = "((\\.|[^\\"\n])*(")?)?
            
            ErrorList :== '[' Error { ',' Error } ']' .
            Error :== '{' '"path"'   ':' STRING ','
                          '"begln"'  ':' INT    ','
                          '"begcol"' ':' INT    ','
                          '"endln"'  ':' INT    ','
                          '"endcol"' ':' INT    ','
                          '"msg"'    ':' STRING ','
                          '"ctx"'    ':' STRING
                      '}' .
        """.trimIndent())

private val processListConfre = Confre("""
            STRING = "((\\.|[^\\"\n])*(")?)?
            
            ProcList :== '"procs":' '[' Proc {',' Proc} ']' .
            Proc     :== '{' '"name":' STRING ',' '"templ":' STRING ',' ArgList '}' .
            ArgList  :== '"args":' '[' [Arg {',' Arg}] ']' .
            Arg      :== '{' '"par":' STRING ',' '"v":' STRING '}' .
        """.trimIndent())


// TODO: Ensure that intercepted responses are not overwritten incorrectly. E.g., errors and warnings now show simultaneously


class MappingInterceptor(
    private val orchestrator: Orchestrator,
    private val exceptionDumpFilePath: String,
    streams: InterceptStreams
) : Interceptor()
{
    private val toEngineInput = streams.inInput
    private val toEngineOutput = streams.inOutput
    private val toGuiInput = streams.outInput
    private val toGuiOutput = streams.outOutput
    private val toGuiErrorInput = streams.errInput
    private val toGuiErrorOutput = streams.errOutput

    private var latestModelErrors: List<UppaalError>? = null
    private var toEngineBuffer = ""
    private var toGuiBuffer = ""


    override fun fromGuiToEngine()
    {
        while (toEngineInput.ready()) {
            val ch = toEngineInput.read()
            if (toEngineBuffer.isEmpty() && ch.toChar() != '{') {
                toEngineOutput.write(ch)
                toEngineOutput.flush()
                continue
            }

            toEngineBuffer += ch.toChar()
            if (toEngineBuffer == MODEL_CMD_PREFIX) {
                try {
                    latestModelErrors = null
                    val modelResult = interceptModelCmd(toEngineInput)
                    if (modelResult.second.any { it.isUnrecoverable }) {
                        val mappedErrors = orchestrator.mapModelErrors(listOf(), modelResult.second)
                        toGuiOutput.write(generateModelErrorResponse(mappedErrors))
                        toGuiOutput.flush()
                    }
                    else {
                        toEngineOutput.write(generateModelCommand(modelResult.first))
                        toEngineOutput.flush()
                        latestModelErrors = modelResult.second.ifEmpty { null }
                    }
                }
                catch (ex: Exception) {
                    orchestrator.clearCache()
                    ex.writeToFile(exceptionDumpFilePath)
                    outputExceptionAsUppaalError("GUI model input lead to exception. See details: '${
                        Path(
                            exceptionDumpFilePath
                        ).absolutePathString()}'", useModelErrorResponse = true)
                }
                finally {
                    toEngineBuffer = ""
                }
            }
            else if (toEngineBuffer == QUERY_CMD_PREFIX) {
                try {
                    val queryResult = interceptQueryCmd(toEngineInput)
                    if (null != queryResult.second) {
                        toGuiOutput.write(generateQueryErrorResponse(queryResult.second!!))
                        toGuiOutput.flush()
                    }
                    else {
                        toEngineOutput.write(generateQueryCommand(queryResult.first))
                        toEngineOutput.flush()
                    }
                }
                catch (ex: Exception) {
                    ex.writeToFile(exceptionDumpFilePath)
                    outputExceptionAsUppaalError("GUI query input lead to exception. See details: '${
                        Path(
                            exceptionDumpFilePath
                        ).absolutePathString()}'", useModelErrorResponse = false)
                }
                finally {
                    toEngineBuffer = ""
                }
            }
            else if (!MODEL_CMD_PREFIX.startsWith(toEngineBuffer) && !QUERY_CMD_PREFIX.startsWith(toEngineBuffer)) {
                toEngineOutput.write(toEngineBuffer)
                toEngineOutput.flush()
                toEngineBuffer = ""
            }
        }
    }

    override fun fromEngineToGui()
    {
        while (toGuiInput.ready()) {
            val ch = toGuiInput.read()

            if (toGuiBuffer.isEmpty() && ch.toChar() != '{') {
                toGuiOutput.write(ch)
                toGuiOutput.flush()
                continue
            }

            toGuiBuffer += ch.toChar()
            if (toGuiBuffer == QUERY_ERROR_RESPONSE_PREFIX) {
                try {
                    val queryError = interceptQueryErrorResponse(toGuiInput)
                    toGuiOutput.write(generateQueryErrorResponse(queryError))
                    toGuiOutput.flush()
                }
                catch (ex: Exception) {
                    ex.writeToFile(exceptionDumpFilePath)
                    outputExceptionAsUppaalError("Engine query error response lead to exception. See details: '${
                        Path(
                            exceptionDumpFilePath
                        ).absolutePathString()}'", useModelErrorResponse = false)
                }
                finally {
                    toGuiBuffer = ""
                }
            }
            else if (toGuiBuffer == MODEL_ERROR_RESPONSE_PREFIX) {
                try {
                    val finalErrors = interceptModelErrorResponse(toGuiInput, latestModelErrors ?: listOf())
                    toGuiOutput.write(generateModelErrorResponse(finalErrors))
                    toGuiOutput.flush()
                }
                catch (ex: Exception) {
                    ex.writeToFile(exceptionDumpFilePath)
                    outputExceptionAsUppaalError("Engine model error response lead to exception. See details: '${
                        Path(
                            exceptionDumpFilePath
                        ).absolutePathString()}'", useModelErrorResponse = true)
                }
                finally {
                    toGuiBuffer = ""
                    latestModelErrors = null
                }
            }
            else if (toGuiBuffer == MODEL_SUCCESS_RESPONSE_PREFIX) {
                try {
                    if (latestModelErrors == null) {
                        val newResponse = interceptModelSuccessResponse(toGuiInput, false)
                        toGuiOutput.write(newResponse)
                    }
                    else {
                        interceptModelSuccessResponse(toGuiInput, true) // Yes. Ignore output
                        val mappedErrors = orchestrator.mapModelErrors(listOf(), latestModelErrors!!)
                        toGuiOutput.write(generateModelErrorResponse(mappedErrors))
                    }
                    toGuiOutput.flush()
                }
                catch (ex: Exception) {
                    ex.writeToFile(exceptionDumpFilePath)
                    outputExceptionAsUppaalError("Engine model success response lead to exception. See details: '${
                        Path(
                            exceptionDumpFilePath
                        ).absolutePathString()}'", useModelErrorResponse = true)
                }
                finally {
                    toGuiBuffer = ""
                    latestModelErrors = null
                }
            }
            else if (!QUERY_ERROR_RESPONSE_PREFIX.startsWith(toGuiBuffer)
                && !MODEL_ERROR_RESPONSE_PREFIX.startsWith(toGuiBuffer)
                && !MODEL_SUCCESS_RESPONSE_PREFIX.startsWith(toGuiBuffer))
            {
                toGuiOutput.write(toGuiBuffer)
                toGuiOutput.flush()
                toGuiBuffer = ""
            }
        }
    }

    override fun fromEngineToGuiError() {
        while (toGuiErrorInput.ready())
            toGuiErrorOutput.write(toGuiErrorInput.read())
        toGuiErrorOutput.flush()
    }


    private fun outputExceptionAsUppaalError(message: String, useModelErrorResponse: Boolean) {
        val error = createUppaalError(UppaalPath(), message, true)
        if (useModelErrorResponse)
            toGuiOutput.write(generateModelErrorResponse(listOf(error)))
        else
            toGuiOutput.write(generateQueryErrorResponse(error))
        toGuiOutput.flush()
    }


    private fun interceptModelCmd(input: BufferedReader): Pair<String, List<UppaalError>>
    {
        val originalModel = StringBuilder()
        while (!originalModel.endsWith("\"}") || originalModel.endsWith("\\\"}"))
            originalModel.append(input.read().toChar())

        return orchestrator.mapModel(
            originalModel.toString().removeSuffix("\"}").unJsonFy()
        )
    }
    private fun generateModelCommand(model: String): String
            = "{\"cmd\":\"newXMLSystem3\",\"args\":\"${model.jsonFy()}\"}"

    private fun interceptModelErrorResponse(input: BufferedReader, mapperErrors: List<UppaalError>): List<UppaalError>
    {
        val errors = StringBuilder()
        while (!errors.endsWith("}]") && !errors.endsWith("\\}]")) // '}]' marks end of error list
            errors.append(input.read().toChar())

        // Throw away rest, including "warnings", since this is just ignored when errors are present
        val throwaway = StringBuilder()
        while (!throwaway.endsWith("]}}")) // ']}}' marks the end after warning-list and two object ends
            throwaway.append(input.read().toChar())

        val errorsTree = errorListConfre.matchExact(errors.toString()) as Node
        val errorJsonList =
            listOf(errorsTree.children[1].toString()) // First error
                .plus( // For each child in multiple, for the second child in each of these, get string.
                    (errorsTree.children[2] as Node).children.map { (it as Node).children[1].toString() }
                )

        val engineErrors = errorJsonList.map { UppaalError.fromJson(it) }
        return orchestrator.mapModelErrors(engineErrors, mapperErrors)
    }
    private fun generateModelErrorResponse(errors: List<UppaalError>): String
            = "{\"res\":\"error\",\"info\":{\"errors\":[${errors.joinToString(",")}],\"warnings\":[]}}"

    private fun interceptModelSuccessResponse(input: BufferedReader, suppressMapping: Boolean): String
    {
        val successResponse = StringBuilder(MODEL_SUCCESS_RESPONSE_PREFIX)
        while (!successResponse.endsWith("]}}")) // Marks end after warning-list and two object ends
            successResponse.append(input.read().toChar())

        // Map process names
        if (!suppressMapping) {
            var successString = successResponse.toString()

            val processListNode = processListConfre.find(successString, successString.indexOf("\"procs\":"))!!.asNode()
            val processNodes = listOf(processListNode.children[2]!!.asNode()).plus(
                processListNode.children[3]!!.asNode().children.map { it!!.asNode().children[1]!!.asNode() }
            )
            val namesAndTemplates = processNodes.map {
                ProcessInfo(
                    it.children[2]!!.asLeaf().token!!.value.drop(1).dropLast(1),
                    it.children[5]!!.asLeaf().token!!.value.drop(1).dropLast(1)
                )
            }.toMutableList()
            orchestrator.mapProcesses(namesAndTemplates)

            var offset = 0
            for (processNode in processNodes.withIndex()) {
                val nameRange = processNode.value.children[2]!!.range().offset(offset)
                val oldName = processNode.value.children[2]!!.asLeaf().token!!.value
                val newName = "\"${namesAndTemplates[processNode.index].name}\""

                successString = successString.replaceRange(nameRange, newName)
                offset += newName.length - oldName.length
            }
            return successString
        }

        return ""
    }

    private fun interceptQueryCmd(input: BufferedReader): Pair<String, UppaalError?>
    {
        val query = StringBuilder()
        while (!query.endsWith("\"}") || query.endsWith("\\\"}"))
            query.append(input.read().toChar())

        val queryString = query.toString().removeSuffix("\"}").unJsonFy()
        val result = orchestrator.mapQuery(queryString)
        return Pair(result.first.jsonFy(), result.second)
    }
    private fun generateQueryCommand(query: String): String
            = "{\"cmd\":\"modelCheck\",\"args\":\"${query.jsonFy()}\"}"

    private fun interceptQueryErrorResponse(input: BufferedReader): UppaalError
    {
        val error = StringBuilder()
        while (!error.endsWith("\"}") || error.endsWith("\\\"}"))
            error.append(input.read().toChar())

        val throwaway = StringBuilder()
        while (!throwaway.endsWith("}}"))
            throwaway.append(input.read().toChar())

        return orchestrator.backMapQueryError(UppaalError.fromJson(error.toString()))
    }
    private fun generateQueryErrorResponse(error: UppaalError): String
            = "{\"res\":\"ok\",\"info\":{\"status\":\"E\",\"error\":$error,\"stat\":false,\"message\":\"${error.message.jsonFy()}\",\"result\":\"\",\"plots\":[],\"cyclelen\":0,\"trace\":null}}"
}