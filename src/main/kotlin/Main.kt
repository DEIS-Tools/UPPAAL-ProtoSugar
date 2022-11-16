import engine.MapperEngine
import engine.mapping.Quadruple
import engine.mapping.UppaalError
import engine.mapping.autoarr.AutoArrMapper
import engine.mapping.pacha.PaChaMapper
import engine.mapping.secomp.SeCompMapper
import engine.mapping.txquan.TxQuanMapper
import engine.parsing.Confre
import engine.parsing.Node
import java.io.*
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess


const val FILE_TAG = "-file"
const val OUTPUT_TAG = "-output"
const val SERVER_TAG = "-server"
const val DEBUG_TAG = "-debug"
const val MAPPERS_TAG = "-mappers"

val mappers = mapOf(
    Pair("PaCha",   PaChaMapper()),
    Pair("AutoArr", AutoArrMapper()),
    Pair("TxQuan", TxQuanMapper()),
    Pair("SeComp", SeCompMapper()),
    //Pair("ChRef", null),
    //Pair("Hiera", null)
)
lateinit var engine: MapperEngine

val errorListGrammar = Confre("""
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

fun main(args: Array<String>)
{
    val tags = getTags(args)
    engine = MapperEngine(tags[MAPPERS_TAG]?.map { mappers[it]!! } ?: listOf())

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

fun getTags(args: Array<String>): Map<String, List<String>>
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

fun getParams(argItr: Iterator<String>, count: Int): List<String>
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


fun runOnFile(inputFile: File, outputFile: File?)
{
    val modelResult = engine.mapModel(inputFile.inputStream())
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


fun tryRunOnServer(server: String)
{
    try { runServer(server) }
    catch (ex: Exception) {
        File("ProtoSugar-CrashDetails.txt").printWriter().use { out -> out.println(ex.toString()) }
    }
}

fun runServer(server: String)
{
    val params = server.split(',').toTypedArray()
    val process = ProcessBuilder(*params)
        .redirectError(Redirect.INHERIT)
        .start()

    val toEngineInput = System.`in`.bufferedReader(StandardCharsets.UTF_8)
    val toEngineOutput = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)

    val toGuiInput = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
    val toGuiOutput = System.out.bufferedWriter(StandardCharsets.UTF_8)

    var latestModelErrors: List<UppaalError>? = null

    var toEngineBuffer = ""
    val modelCmdPrefix = "{\"cmd\":\"newXMLSystem3\",\"args\":\""
    val queryCmdPrefix = "{\"cmd\":\"modelCheck\",\"args\":\""

    var toGuiBuffer = ""
    val queryErrorResponsePrefix = "{\"res\":\"ok\",\"info\":{\"status\":\"E\",\"error\":" // Continues until: "trace":null}}
    val modelErrorResponsePrefix = "{\"res\":\"error\",\"info\":{\"errors\":" // Continues until: ]}}
    val modelSuccessResponsePrefix = "{\"res\":\"ok\",\"info\":{\"warnings\":" // Continues until: ]}}

    while (true)
    {
        while (toEngineInput.ready())
        {
            val ch = toEngineInput.read()
            if (toEngineBuffer.isEmpty() && ch.toChar() != '{')
            {
                toEngineOutput.write(ch)
                toEngineOutput.flush()
                continue
            }

            toEngineBuffer += ch.toChar()
            if (toEngineBuffer == modelCmdPrefix)
            {
                val modelResult = interceptModelCmd(toEngineInput)
                @Suppress("LiftReturnOrAssignment")
                if (modelResult.second.any { it.isUnrecoverable }) {
                    toGuiOutput.write(generateModelErrorResponse(modelResult.second))
                    toGuiOutput.flush()
                    latestModelErrors = null
                }
                else {
                    toEngineOutput.write(generateModelCommand(modelResult.first))
                    toEngineOutput.flush()
                    latestModelErrors = modelResult.second.ifEmpty { null }
                }
                toEngineBuffer = ""
            }
            else if (toEngineBuffer == queryCmdPrefix)
            {
                val queryResult = interceptQueryCmd(toEngineInput)
                if (null != queryResult.second) {
                    toGuiOutput.write(generateQueryErrorResponse(queryResult.second!!))
                    toGuiOutput.flush()
                }
                else {
                    toEngineOutput.write(generateQueryCommand(queryResult.first))
                    toEngineOutput.flush()
                }
                toEngineBuffer = ""
            }
            else if (!modelCmdPrefix.startsWith(toEngineBuffer) && !queryCmdPrefix.startsWith(toEngineBuffer))
            {
                toEngineOutput.write(toEngineBuffer)
                toEngineOutput.flush()
                toEngineBuffer = ""
            }
        }

        while (toGuiInput.ready())
        {
            val ch = toGuiInput.read()

            if (toGuiBuffer.isEmpty() && ch.toChar() != '{') {
                toGuiOutput.write(ch)
                toGuiOutput.flush()
                continue
            }

            toGuiBuffer += ch.toChar()
            if (toGuiBuffer == queryErrorResponsePrefix)
            {
                val queryError = interceptQueryErrorResponse(toGuiInput)
                toGuiOutput.write(generateQueryErrorResponse(queryError))
                toGuiOutput.flush()
                toGuiBuffer = ""
            }
            else if (toGuiBuffer == modelErrorResponsePrefix)
            {
                val finalErrors = interceptModelErrorResponse(toGuiInput, latestModelErrors ?: listOf())
                toGuiOutput.write(generateModelErrorResponse(finalErrors))
                toGuiOutput.flush()
                toGuiBuffer = ""
                latestModelErrors = null
            }
            else if (toGuiBuffer == modelSuccessResponsePrefix)
            {
                if (latestModelErrors == null)
                    toGuiOutput.write(toGuiBuffer)
                else {
                    interceptModelSuccessResponse(toGuiInput)
                    toGuiOutput.write(generateModelErrorResponse(latestModelErrors))
                }
                toGuiOutput.flush()
                toGuiBuffer = ""
                latestModelErrors = null
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

fun interceptModelCmd(input: BufferedReader): Pair<String, List<UppaalError>>
{
    var originalModel = ""
    while (!originalModel.endsWith("\"}") || originalModel.endsWith("\\\"}"))
        originalModel += input.read().toChar()
    originalModel = originalModel.removeSuffix("\"}")

    return engine.mapModel(originalModel.replace("\\\"", "\""))
}
fun generateModelCommand(model: String): String
    = "{\"cmd\":\"newXMLSystem3\",\"args\":\"${model.replace("\"", "\\\"")}\"}"
fun interceptModelErrorResponse(input: BufferedReader, mapperErrors: List<UppaalError>): List<UppaalError>
{
    var errors = ""
    while (!errors.endsWith("}]") && !errors.endsWith("\\}]")) // '}]' marks end of error list
        errors += input.read().toChar()

    var throwaway = ""
    while (!throwaway.endsWith("]}}")) // ']}}' marks the end after warning-list and two object ends
        throwaway += input.read().toChar()

    val errorsTree = errorListGrammar.matchExact(errors) as Node
    val errorJsonList =
        listOf(errorsTree.children[1].toString()) // First error
            .plus( // For each child in multiple, for the second child in each of these, get string.
                (errorsTree.children[2] as Node).children.map { (it as Node).children[1].toString() }
            )

    val engineErrors = errorJsonList.map { UppaalError.fromJson(it) }
    return engine.mapModelErrors(engineErrors, mapperErrors)
}
fun interceptModelSuccessResponse(input: BufferedReader)
{
    var throwaway = ""
    while (!throwaway.endsWith("]}}")) // Marks end after warning-list and two object ends
        throwaway += input.read().toChar()
}
fun generateModelErrorResponse(errors: List<UppaalError>): String
    = "{\"res\":\"error\",\"info\":{\"errors\":[${errors.joinToString(",")}],\"warnings\":[]}}"

fun interceptQueryCmd(input: BufferedReader): Pair<String, UppaalError?>
{
    var query = ""
    while (!query.endsWith("\"}") || query.endsWith("\\\"}"))
        query += input.read().toChar()
    query = query.removeSuffix("\"}")

    val result = engine.mapQuery(query.replace("\\\"", "\""))
    val escapedQuery = result.first.replace("\"", "\\\"")

    return Pair(escapedQuery, result.second)
}
fun generateQueryCommand(query: String): String
    = "{\"cmd\":\"modelCheck\",\"args\":\"${query.replace("\"", "\\\"")}\"}"
fun interceptQueryErrorResponse(input: BufferedReader): UppaalError
{
    var error = ""
    while (!error.endsWith("\"}") || error.endsWith("\\\"}"))
        error += input.read().toChar()

    var throwaway = ""
    while (!throwaway.endsWith("\"trace\":null}}"))
        throwaway += input.read().toChar()

    return engine.mapQueryError(UppaalError.fromJson(error))
}
fun generateQueryErrorResponse(error: UppaalError): String
    = "{\"res\":\"ok\",\"info\":{\"status\":\"E\",\"error\":$error,\"stat\":false,\"message\":\"${error.message}\",\"result\":\"\",\"plots\":[],\"cyclelen\":0,\"trace\":null}}"


fun runDebug(server: String, inputFile: File, outputFile: File, errorFile: File)
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


fun usage()
{
    println("usage: uppaal_mapper MODE MAPPERS")
    println("MODE: $FILE_TAG INPUT_XML_FILE_PATH [$OUTPUT_TAG OUTPUT_XML_FILE_PATH]")
    println("    | $SERVER_TAG SERVER_RUN_CMD")
    println("    | $DEBUG_TAG SERVER_RUN_CMD STDIN_OUTPUT_PATH STDOUT_OUTPUT_PATH STDERR_OUTPUT_PATH")
    println("MAPPERS: $MAPPERS_TAG { MAPPER }")
    println("MAPPER: ${mappers.keys.joinToString(" | ", transform = { "'$it'" })}")
    exitProcess(1)
}