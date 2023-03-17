package mapping.interception

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import mapping.Orchestrator
import mapping.mapping.Argument
import mapping.mapping.ProcessInfo
import replaceValue
import unescapeLinebreaks
import uppaal.messaging.*
import writeAndFlush
import writeToFile
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.InputStream
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

@OptIn(ExperimentalSerializationApi::class)
class MappingInterceptor(
    private val orchestrator: Orchestrator,
    private val exceptionDumpFilePath: String,
    streams: InterceptStreams
) : Interceptor()
{
    private val toEngineInput = BufferedInputStream(streams.inInput)
    private val toEngineOutput = streams.inOutput.bufferedWriter()
    private val toGuiInput = BufferedInputStream(streams.outInput)
    private val toGuiOutput = streams.outOutput.bufferedWriter()
    private val toGuiErrorInput = streams.errInput.bufferedReader()
    private val toGuiErrorOutput = streams.errOutput.bufferedWriter()

    private var latestModelErrors: List<UppaalMessage>? = null

    private fun tryGetNextEngineInput(): JsonObject? {
        return if (toEngineInput.available() != 0)
            Json.decodeFromString<JsonObject>(getJsonString(toEngineInput, toEngineOutput) ?: return null)
        else
            null
    }
    private fun tryGetNextGuiInput(): JsonObject? {
        return if (toGuiInput.available() != 0)
            Json.decodeFromString<JsonObject>(getJsonString(toGuiInput, toGuiOutput) ?: return null)
        else
            null
    }
    private fun getJsonString(input: InputStream, output: BufferedWriter): String? {
        input.mark(1)
        var buffer = input.read().toChar()
        while (buffer != '{') {
            output.writeAndFlush(buffer) // This code should practically never be reached, so flush everytime is insignificant
            if (input.available() == 0)
                return null
            input.mark(1)
            buffer = input.read().toChar()
        }
        input.reset()

        var depth = 0
        var inString = false
        val builder = StringBuilder()
        do {
            val curr = input.read().toChar()
            builder.append(curr)
            when (curr) {
                '{' -> if (!inString) ++depth
                '}' -> if (!inString) --depth
                '"' -> inString = !inString
                '\\' -> builder.append(input.read().toChar())
            }
        } while (depth != 0)
        return builder.toString()
    }


    override fun fromGuiToEngine()
    {
        val json = tryGetNextEngineInput() ?: return
        when (json["cmd"]?.jsonPrimitive?.content) {
            "newXMLSystem3" -> interceptModelCommand(json)
            "modelCheck" -> interceptQueryCommand(json)
            else -> toEngineOutput.writeAndFlush(json.toString())
        }

        // TODO: Simulation commands
    }
    private fun interceptModelCommand(json: JsonObject)
    {
        try {
            val (mappedModel, errors) = orchestrator.mapModel(json["args"]!!.jsonPrimitive.content)
            if (errors.any { it.isUnrecoverable })
                outputModelErrorResponseToGui(orchestrator.backMapModelErrors(listOf(), errors))
            else
                outputModelCommandToEngine(mappedModel)
            latestModelErrors = errors.ifEmpty { null }
        }
        catch (ex: Exception) {
            orchestrator.clearCache()
            ex.writeToFile(exceptionDumpFilePath)
            outputMessageAsErrorToGui(
                "The uploaded model caused an exception. See details: '${Path(exceptionDumpFilePath).absolutePathString()}'", useModelErrorResponse = true)
        }
    }
    private fun interceptQueryCommand(json: JsonObject)
    {
        try {
            outputQueryCommandToEngine(orchestrator.mapQuery(json["args"]!!.jsonPrimitive.content))
        }
        catch (ex: UppaalMessageException) {
            outputQueryErrorResponseToGui(ex.uppaalMessage)
        }
        catch (ex: Exception) {
            ex.writeToFile(exceptionDumpFilePath)
            outputMessageAsErrorToGui(
                "The submitted query caused an exception. See details: '${Path(exceptionDumpFilePath).absolutePathString()}'", useModelErrorResponse = false)
        }
    }


    override fun fromEngineToGui()
    {
        val json = tryGetNextGuiInput() ?: return

        val resOk = json["res"].toString() == "ok"
        val resErr = json["res"].toString() == "error"
        val infoIsObj = json["info"] is JsonObject
        val errIsList = infoIsObj && json["info"]!!.jsonObject["errors"] is JsonArray
        val wrnIsList = infoIsObj && json["info"]!!.jsonObject["warnings"] is JsonArray
        val infoStatusE = infoIsObj && json["info"]!!.jsonObject["status"]?.toString() == "E"

        when {
            resErr && wrnIsList && errIsList -> interceptModelErrorResponse(json)
            resOk && wrnIsList && !errIsList -> interceptModelSuccessResponse(json)
            resOk && !errIsList && infoStatusE -> interceptQueryErrorResponse(json)
            else -> toGuiOutput.writeAndFlush(json.toString())

            // TODO: Simulation responses
        }
    }
    private fun interceptModelErrorResponse(json: JsonObject)
    {
        try {
            val engineErrorsAndWarnings =
                json["info"]!!.jsonObject["errors"]!!.jsonArray.map { UppaalMessage.fromJson(it.jsonObject) } +
                        json["info"]!!.jsonObject["warnings"]!!.jsonArray.map { UppaalMessage.fromJson(it.jsonObject, Severity.WARNING) }

            outputModelErrorResponseToGui(
                orchestrator.backMapModelErrors(engineErrorsAndWarnings, latestModelErrors ?: listOf())
            )
        }
        catch (ex: Exception) {
            ex.writeToFile(exceptionDumpFilePath)
            outputMessageAsErrorToGui(
                "An error response to an uploaded model caused an exception. See details: '${Path(exceptionDumpFilePath).absolutePathString()}'", useModelErrorResponse = true)
        }
        finally {
            latestModelErrors = null
        }
    }
    private fun interceptModelSuccessResponse(json: JsonObject)
    {
        try {
            if (latestModelErrors != null)
                return outputModelErrorResponseToGui(orchestrator.backMapModelErrors(listOf(), latestModelErrors!!))

            val processes = json["info"]!!.jsonObject["procs"]!!.jsonArray.map { it as JsonObject }.map { process -> ProcessInfo(
                process["name"]!!.toString(),
                process["templ"]!!.toString(),
                process["args"]!!.jsonArray.map { it as JsonObject }.map { arg -> Argument(arg["par"]!!.toString(), arg["v"]!!.toString()) }
            )}.toMutableList()
            val variables = json["info"]!!.jsonObject["vars"]!!.jsonArray.map { it.toString() }.toMutableList()
            val clocks = json["info"]!!.jsonObject["clocks"]!!.jsonArray.map { it.toString() }.toMutableList()

            orchestrator.backMapInitialSystem(processes, variables, clocks)

            toGuiOutput.write(
                json.replaceValue(listOf("info", "procs"), Json.encodeToJsonElement(processes))
                    .replaceValue(listOf("info", "vars"), Json.encodeToJsonElement(variables))
                    .replaceValue(listOf("info", "clocks"), Json.encodeToJsonElement(clocks))
                    .toString()
            )
        }
        catch (ex: Exception) {
            ex.writeToFile(exceptionDumpFilePath)
            outputMessageAsErrorToGui(
                "Engine model success response lead to exception. See details: '${Path(exceptionDumpFilePath).absolutePathString()}'", useModelErrorResponse = true)
        }
        finally {
            latestModelErrors = null
        }
    }
    private fun interceptQueryErrorResponse(json: JsonObject)
    {
        try {
            val error = json["info"]!!.jsonObject["error"]!!.jsonObject
            outputQueryErrorResponseToGui(error, orchestrator.backMapQueryError(UppaalMessage.fromJson(error)))
        }
        catch (ex: Exception) {
            ex.writeToFile(exceptionDumpFilePath)
            outputMessageAsErrorToGui(
                "Engine query error response lead to exception. See details: '${Path(exceptionDumpFilePath).absolutePathString()}'", useModelErrorResponse = false)
        }
    }


    override fun fromEngineToGuiError() {
        while (toGuiErrorInput.ready())
            toGuiErrorOutput.write(toGuiErrorInput.read())
        toGuiErrorOutput.flush()
    }


    private fun outputMessageAsErrorToGui(message: String, useModelErrorResponse: Boolean) {
        val error = createUppaalError(UppaalPath(), message, true)
        if (useModelErrorResponse)
            outputModelErrorResponseToGui(listOf(error))
        else
            outputQueryErrorResponseToGui(error)
    }

    private fun outputModelCommandToEngine(model: String)
            = toEngineOutput.writeAndFlush(buildJsonObject {
        put("cmd", "newXMLSystem3")
        put("args", model)
    }.toString().unescapeLinebreaks())
    private fun outputModelErrorResponseToGui(errors: List<UppaalMessage>)
            = toGuiOutput.writeAndFlush(buildJsonObject {
        put("res", "error")
        putJsonObject("info") {
            putJsonArray("errors") { errors.filter { !it.isWarning }.forEach { add(it.toJson()) } }
            putJsonArray("warnings") { errors.filter { it.isWarning }.forEach { add(it.toJson()) } }
        }
    }.toString().unescapeLinebreaks())

    private fun outputQueryCommandToEngine(query: String)
            = toEngineOutput.writeAndFlush(buildJsonObject {
        put("cmd", "modelCheck")
        put("args", query)
    }.toString().unescapeLinebreaks())
    private fun outputQueryErrorResponseToGui(error: UppaalMessage)
            = toGuiOutput.writeAndFlush(buildJsonObject {
        put("res", "ok")
        putJsonObject("info") {
            put("status", "E")
            put("error", error.toJson())
            put("stat", false)
            put("message", error.message)
            put("result", "")
            putJsonArray("plots") { }
            put("cyclelen", 0)
            put("trace", null)
        }
    }.toString().unescapeLinebreaks())
    private fun outputQueryErrorResponseToGui(originalJson: JsonObject, mappedError: UppaalMessage)
            = toGuiErrorOutput.writeAndFlush(
        originalJson.replaceValue(listOf("info", "error"), mappedError.toJson()).toString().unescapeLinebreaks())
}