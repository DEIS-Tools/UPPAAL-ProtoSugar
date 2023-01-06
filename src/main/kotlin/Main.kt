import mapping.mappers.*
import mapping.parsing.Confre
import mapping.parsing.Node
import mapping.Orchestrator
import mapping.rewriting.Rewriter
import uppaal.error.UppaalError
import uppaal.error.UppaalPath
import uppaal.error.createUppaalError
import java.io.*
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.system.exitProcess


private const val CRASH_DETAILS_FILE_PATH = "ProtoSugar-CrashDetails.txt"

private const val FILE_TAG = "-file"
private const val OUTPUT_TAG = "-output"
private const val SERVER_TAG = "-server"
private const val DEBUG_TAG = "-debug"
private const val MAPPERS_TAG = "-mappers"

private val availableMappers = mapOf(
    Pair("PaCha", PaChaMapper()),
    Pair("AutoArr", AutoArrMapper()),
    Pair("TxQuan", TxQuanMapper()),
    Pair("SeComp", SeCompMapper())
)
private lateinit var orchestrator: Orchestrator

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

private const val modelCmdPrefix = "{\"cmd\":\"newXMLSystem3\",\"args\":\"" // Continues until: "}
private const val queryCmdPrefix = "{\"cmd\":\"modelCheck\",\"args\":\""    // Continues until: "}

private const val queryErrorResponsePrefix = "{\"res\":\"ok\",\"info\":{\"status\":\"E\",\"error\":" // Continues until: }}
private const val modelErrorResponsePrefix = "{\"res\":\"error\",\"info\":{\"errors\":"              // Continues until: ]}}
private const val modelSuccessResponsePrefix = "{\"res\":\"ok\",\"info\":{\"warnings\":"             // Continues until: ]}}


fun main(args: Array<String>)
{
    val tags = getTags(args)
    orchestrator = Orchestrator(tags[MAPPERS_TAG]?.map { availableMappers[it]!! } ?: listOf())

    when {
        tags.containsKey(FILE_TAG) -> runOnFile(File(tags[FILE_TAG]!![0]), tags[OUTPUT_TAG]?.let { File(it[0]) })
        tags.containsKey(SERVER_TAG) -> tryRunOnServer(tags[SERVER_TAG]!![0])
        tags.containsKey(DEBUG_TAG) -> runDebug(
            tags[DEBUG_TAG]!![0],
            File(tags[DEBUG_TAG]!![1]),
            File(tags[DEBUG_TAG]!![2]),
            File(tags[DEBUG_TAG]!![3])
        )
        else -> usage()
    }
}

private fun getTags(args: Array<String>): Map<String, List<String>>
{
    val tags = HashMap<String, List<String>>()

    val argItr = args.iterator()
    while (argItr.hasNext())
        when (argItr.next()) {
            FILE_TAG -> tags[FILE_TAG] = getParams(argItr, 1)
            OUTPUT_TAG -> tags[OUTPUT_TAG] = getParams(argItr, 1)
            SERVER_TAG -> tags[SERVER_TAG] = getParams(argItr, 1)
            DEBUG_TAG -> tags[DEBUG_TAG] = getParams(argItr, 4)
            MAPPERS_TAG -> tags[MAPPERS_TAG] = getParams(argItr, 0)
            else -> usage()
        }

    val modeTags = listOf(FILE_TAG, SERVER_TAG, DEBUG_TAG)
    if (tags.keys.count { it in modeTags } != 1)
        usage()
    if (tags.values.any { list -> list.any { arg -> arg.startsWith('-') } })
        usage()

    return tags
}

private fun getParams(argItr: Iterator<String>, count: Int): List<String>
{
    if (count <= 0)
        return argItr.asSequence().toList()

    val params = ArrayList<String>(count)
    repeat(count) {
        if (!argItr.hasNext()) usage()
        params.add(argItr.next())
        if (params.last().startsWith('-')) usage()
    }
    return params
}


private fun runOnFile(inputFile: File, outputFile: File?)
{
    val modelResult = orchestrator.mapModel(inputFile.inputStream())
    if (modelResult.second.isNotEmpty()) {
        print("There were errors:\n")
        print(modelResult.second.joinToString("\n"))
    }
    else if (outputFile == null)
        print(modelResult.first)
    else
        outputFile.outputStream().bufferedWriter().use {
            it.write(modelResult.first)
            it.flush()
            it.close()
        }
}


private fun tryRunOnServer(server: String)
{
    try
    { runServer(server) }
    catch (ex: Exception)
    { writeException(ex) }
}

private fun runServer(server: String)
{
    val params = server.split(',').map { it.trim() }.toTypedArray()
    val process = ProcessBuilder(*params)
        .redirectError(Redirect.INHERIT)
        .start()

    val toEngineInput = System.`in`.bufferedReader(StandardCharsets.UTF_8)           // GUI -> ProtoSugar
    val toEngineOutput = process.outputStream.bufferedWriter(StandardCharsets.UTF_8) // ProtoSugar -> Engine

    val toGuiInput = process.inputStream.bufferedReader(StandardCharsets.UTF_8) // Engine -> ProtoSugar
    val toGuiOutput = System.out.bufferedWriter(StandardCharsets.UTF_8)         // ProtoSugar -> GUI

    var latestModelErrors: List<UppaalError>? = null
    var toEngineBuffer = ""
    var toGuiBuffer = ""

    while (true) {
        /* FROM GUI TO ENGINE */
        while (toEngineInput.ready()) {
            val ch = toEngineInput.read()
            if (toEngineBuffer.isEmpty() && ch.toChar() != '{') {
                toEngineOutput.write(ch)
                toEngineOutput.flush()
                continue
            }

            toEngineBuffer += ch.toChar()
            if (toEngineBuffer == modelCmdPrefix) {
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
                    writeException(ex)
                    handleServerModeException(toGuiOutput, "GUI model input lead to exception. See details: '${Path(CRASH_DETAILS_FILE_PATH).absolutePathString()}'", useModelErrorResponse = true)
                }
                finally {
                    toEngineBuffer = ""
                }
            }
            else if (toEngineBuffer == queryCmdPrefix) {
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
                    writeException(ex)
                    handleServerModeException(toGuiOutput, "GUI query input lead to exception. See details: '${Path(CRASH_DETAILS_FILE_PATH).absolutePathString()}'", useModelErrorResponse = false)
                }
                finally {
                    toEngineBuffer = ""
                }
            }
            else if (!modelCmdPrefix.startsWith(toEngineBuffer) && !queryCmdPrefix.startsWith(toEngineBuffer)) {
                toEngineOutput.write(toEngineBuffer)
                toEngineOutput.flush()
                toEngineBuffer = ""
            }
        }

        /* FROM ENGINE TO GUI */
        while (toGuiInput.ready()) {
            val ch = toGuiInput.read()

            if (toGuiBuffer.isEmpty() && ch.toChar() != '{') {
                toGuiOutput.write(ch)
                toGuiOutput.flush()
                continue
            }

            toGuiBuffer += ch.toChar()
            if (toGuiBuffer == queryErrorResponsePrefix) {
                try {
                    val queryError = interceptQueryErrorResponse(toGuiInput)
                    toGuiOutput.write(generateQueryErrorResponse(queryError))
                    toGuiOutput.flush()
                }
                catch (ex: Exception) {
                    writeException(ex)
                    handleServerModeException(toGuiOutput, "Engine query error response lead to exception. See details: '${Path(CRASH_DETAILS_FILE_PATH).absolutePathString()}'", useModelErrorResponse = false)
                }
                finally {
                    toGuiBuffer = ""
                }
            }
            else if (toGuiBuffer == modelErrorResponsePrefix) {
                try {
                    val finalErrors = interceptModelErrorResponse(toGuiInput, latestModelErrors ?: listOf())
                    toGuiOutput.write(generateModelErrorResponse(finalErrors))
                    toGuiOutput.flush()
                }
                catch (ex: Exception) {
                    writeException(ex)
                    handleServerModeException(toGuiOutput, "Engine model error response lead to exception. See details: '${Path(CRASH_DETAILS_FILE_PATH).absolutePathString()}'", useModelErrorResponse = true)
                }
                finally {
                    toGuiBuffer = ""
                    latestModelErrors = null
                }
            }
            else if (toGuiBuffer == modelSuccessResponsePrefix) {
                try {
                    if (latestModelErrors == null) {
                        val newResponse = interceptModelSuccessResponse(toGuiInput, false)
                        toGuiOutput.write(newResponse)
                    }
                    else {
                        interceptModelSuccessResponse(toGuiInput, true) // Yes. Ignore output
                        val mappedErrors = orchestrator.mapModelErrors(listOf(), latestModelErrors)
                        toGuiOutput.write(generateModelErrorResponse(mappedErrors))
                    }
                    toGuiOutput.flush()
                }
                catch (ex: Exception) {
                    writeException(ex)
                    handleServerModeException(toGuiOutput, "Engine model success response lead to exception. See details: '${Path(CRASH_DETAILS_FILE_PATH).absolutePathString()}'", useModelErrorResponse = true)
                }
                finally {
                    toGuiBuffer = ""
                    latestModelErrors = null
                }
            }
            else if (!queryErrorResponsePrefix.startsWith(toGuiBuffer)
                     && !modelErrorResponsePrefix.startsWith(toGuiBuffer)
                     && !modelSuccessResponsePrefix.startsWith(toGuiBuffer))
            {
                toGuiOutput.write(toGuiBuffer)
                toGuiOutput.flush()
                toGuiBuffer = ""
            }
        }

        Thread.sleep(100) // An attempt to make the program NOT hog an entire CPU core due to constant polling
    }
}

private fun handleServerModeException(toGuiOutput: BufferedWriter, message: String, useModelErrorResponse: Boolean) {
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
    val successResponse = StringBuilder(modelSuccessResponsePrefix)
    while (!successResponse.endsWith("]}}")) // Marks end after warning-list and two object ends
        successResponse.append(input.read().toChar())

    // Map process names
    if (!suppressMapping) {
        var successString = successResponse.toString()

        val processListNode = processListConfre.find(successString, successString.indexOf("\"procs\":"))!!.asNode()
        val processNodes = listOf(processListNode.children[2]!!.asNode()).plus(
            processListNode.children[3]!!.asNode().children.map { it!!.asNode().children[1]!!.asNode() }
        )
        val namesAndTemplates = processNodes.map { ProcessInfo(
            it.children[2]!!.asLeaf().token!!.value.drop(1).dropLast(1),
            it.children[5]!!.asLeaf().token!!.value.drop(1).dropLast(1)
        ) }
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


private fun writeException(ex: Exception)
    = File(CRASH_DETAILS_FILE_PATH).printWriter().use { out -> out.println("$ex\n${ex.stackTraceToString()}") }


private fun runDebug(server: String, inputFile: File, outputFile: File, errorFile: File)
{
    val params = server.split(',').toTypedArray()
    val process = ProcessBuilder(*params).start()

    val outInput = process.inputStream.reader(StandardCharsets.UTF_8)
    val outFileOutput = outputFile.outputStream().bufferedWriter(StandardCharsets.UTF_8)

    val errInput = process.errorStream.reader(StandardCharsets.UTF_8)
    val errFileOutput = errorFile.outputStream().bufferedWriter(StandardCharsets.UTF_8)

    val inInput = System.`in`.reader(StandardCharsets.UTF_8)
    val inOutput = process.outputWriter(StandardCharsets.UTF_8)
    val inFileOutput = inputFile.bufferedWriter(StandardCharsets.UTF_8)

    while (true)
    {
        if (inInput.ready())
        {
            while (inInput.ready())
            {
                val ch = inInput.read()
                inOutput.write(ch)
                inFileOutput.write(ch)
            }
            inOutput.flush()
            inFileOutput.flush()
        }
        if (outInput.ready())
        {
            while (outInput.ready())
            {
                val ch = outInput.read()
                outFileOutput.write(ch)
                print(ch.toChar())
            }
            outFileOutput.flush()
        }
        if (errInput.ready())
        {
            while (errInput.ready())
            {
                val ch = errInput.read()
                errFileOutput.write(ch)
                System.err.print(ch.toChar())
            }
            errFileOutput.flush()
        }
    }
}


private fun usage()
{
    println("usage: ProtoSugar MODE MAPPERS")
    println("MODE: $FILE_TAG INPUT_XML_FILE_PATH [$OUTPUT_TAG OUTPUT_XML_FILE_PATH]")
    println("    | $SERVER_TAG SERVER_RUN_CMD")
    println("    | $DEBUG_TAG SERVER_RUN_CMD STDIN_OUTPUT_PATH STDOUT_OUTPUT_PATH STDERR_OUTPUT_PATH")
    println("MAPPERS: $MAPPERS_TAG { MAPPER }")
    println("MAPPER: ${availableMappers.keys.joinToString(" | ", transform = { "'$it'" })}")
    exitProcess(1)
}
