import engine.MapperEngine
import engine.mapping.autoarr.AutoArrMapper
import engine.mapping.pacha.PaChaMapper
import engine.mapping.secomp.SeCompMapper
import engine.mapping.txquan.TxQuanMapper
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

fun main(args: Array<String>)
{
    val tags = getTags(args)
    engine = MapperEngine(tags[MAPPERS_TAG]?.map { mappers[it]!! } ?: listOf())

    when {
        tags.containsKey(FILE_TAG) -> runMapper(File(tags[FILE_TAG]!![0]), tags[OUTPUT_TAG]?.let { File(it[0]) })
        tags.containsKey(SERVER_TAG) -> runServer(tags[SERVER_TAG]!![0])
        tags.containsKey(DEBUG_TAG) -> runDebug(tags[DEBUG_TAG]!![0], File(tags[DEBUG_TAG]!![1]), File(tags[DEBUG_TAG]!![2]), File(tags[DEBUG_TAG]!![3]))
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

fun runMapper(inputFile: File, outputFile: File?)
{
    val result = engine.map(inputFile.inputStream())
    if (outputFile == null)
        print(result)
    else
        outputFile.outputStream().bufferedWriter().use {
            it.write(result)
            it.flush()
            it.close()
        }
}

fun runServer(server: String)
{
    val params = server.split(',').toTypedArray()
    val process = ProcessBuilder(*params)
        .redirectOutput(Redirect.INHERIT)
        .redirectError(Redirect.INHERIT)
        .start()

    val input = System.`in`.bufferedReader(StandardCharsets.UTF_8)
    val output = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)

    var buffer = ""
    val match = "{\"cmd\":\"newXMLSystem3\""
    while (true)
    {
        // TODO: Intercept errors from UPPAAL engine and append own errors

        val ch = input.read()
        if (buffer.isEmpty() && ch.toChar() != '{')
        {
            output.write(ch)
            output.flush()
            continue
        }

        buffer += ch.toChar()
        if (buffer == match)
        {
            output.write(interceptXmlCmd(input))
            output.flush()
            buffer = ""
        }
        else if (!match.startsWith(buffer))
        {
            output.write(buffer)
            output.flush()
            buffer = ""
        }
    }
}

fun interceptXmlCmd(input: BufferedReader): String
{
    var originalModel = input.readLine()
    originalModel = originalModel.substring(originalModel.indexOf("<?"))
    while (!originalModel.endsWith("\"}") || originalModel.endsWith("\\\"}"))
        originalModel += input.read().toChar()
    originalModel = originalModel.removeSuffix("\"}")

    val mappedModel = engine.map(originalModel.replace("\\\"", "\""))

    return "{\"cmd\":\"newXMLSystem3\",\"args\":\"${mappedModel.replace("\"", "\\\"")}\"}"
}

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