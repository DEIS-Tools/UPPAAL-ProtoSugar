import kotlinx.coroutines.*
import mapping.Orchestrator
import mapping.impl.AutoArrMapper
import mapping.impl.PaChaMapper
import mapping.impl.SeCompMapper
import mapping.impl.TxQuanMapper
import mapping.interception.*
import mapping.interception.MappingInterceptor
import java.io.*
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.system.exitProcess

const val STD_EXCEPTION_DUMP_PATH = "ProtoSugar-CrashDetails.txt"

private const val FILE_MODE_TAG = "-file"
private const val OUTPUT_TAG = "-output"

private const val SERVER_MODE_TAG = "-server"
private const val STREAM_DUMP_DIR_TAG = "-stream-dump-dir"

private const val WITH_MAPPERS_TAG = "-with-mappers"
private const val EXCEPTION_DUMP_PATH_TAG = "-exception-dump-dir"

private val availableMappers = mapOf(
    Pair("PaCha") { PaChaMapper() },
    Pair("AutoArr") { AutoArrMapper() },
    Pair("TxQuan") { TxQuanMapper() },
    Pair("SeComp") { SeCompMapper() }
)

private lateinit var tags: Map<String, List<String>>
private lateinit var orchestrator: Orchestrator


fun main(args: Array<String>)
{
    tags = parseTags(args)
    orchestrator = Orchestrator(activeMappers())

    try {
        when {
            tags.containsKey(FILE_MODE_TAG) -> runFileMode(fileModeInput(), fileModeOutput())
            tags.containsKey(SERVER_MODE_TAG) -> runServer(serverModeLaunchCommand(), serverModeStreamDumpDir())
            else -> usage()
        }
    }
    catch (ex: Exception)
    {
        ex.writeToFile(exceptionDumpPath())
        println(ex)
    }
}

private fun parseTags(args: Array<String>): Map<String, List<String>> {
    val tags = HashMap<String, MutableList<String>>()

    val argItr = args.iterator()
    while (argItr.hasNext()) {
        val tag = argItr.next()
        if (argItr.hasNext())
            tags.getOrPut(tag) { mutableListOf() }.addAll(parseValue(tag, argItr.next()))
        else
            usage()
    }

    val modeTags = listOf(FILE_MODE_TAG, SERVER_MODE_TAG)
    if (tags.keys.count { it in modeTags } != 1) {
        println("Use one of the following tags exactly once: ${modeTags.joinToString { "'$it'" }}")
        usage()
    }

    return tags
}
private fun parseValue(tag: String, value: String): List<String>
    = when (tag) {
        WITH_MAPPERS_TAG -> value.split(',').map { it.trim() }
        else -> listOf(value)
    }

private fun fileModeInput() = File(tags[FILE_MODE_TAG]!!.single()).inputStream()
private fun fileModeOutput() = (tags[OUTPUT_TAG]?.let { File(it.single()).outputStream() } ?: System.out).bufferedWriter()
private fun serverModeLaunchCommand() = tags[SERVER_MODE_TAG]!!.single()
private fun serverModeStreamDumpDir() = tags[STREAM_DUMP_DIR_TAG]?.single()
private fun exceptionDumpPath() = tags[EXCEPTION_DUMP_PATH_TAG]?.single() ?: STD_EXCEPTION_DUMP_PATH
private fun activeMappers() = tags[WITH_MAPPERS_TAG]?.map { availableMappers[it]?.invoke() ?: throw Exception("Invalid mapper name '$it'") } ?: listOf()


private fun runFileMode(input: InputStream, output: BufferedWriter) {
    val result = orchestrator.mapModel(input)
    if (result.second.isNotEmpty())
        output.write("There were errors:\n" + result.second.joinToString("\n"))
    else
        output.write(result.first)
    output.flush()
}

private fun runServer(server: String, streamDumpDir: String?) {
    val params = server.split(',').map { it.trim() }.toTypedArray()
    val process = ProcessBuilder(*params).start()

    val doMapping = orchestrator.numberOfMappers > 0
    val doStreamDump = streamDumpDir != null

    runInterceptors(doMapping, doStreamDump, streamDumpDir, process)
}

fun runInterceptors(doMapping: Boolean, doStreamDump: Boolean, streamDumpDir: String?, process: Process) = runBlocking {
    val interceptors =
        if (doMapping && doStreamDump) {
            val streamList = InterceptStreams.fromProcess(process, 3)
            listOf(
                async(Dispatchers.IO) { DataDumpInterceptor(
                    streamList[0],
                    Path(streamDumpDir!!, "guiToProtoSugar.txt").pathString,
                    Path(streamDumpDir, "protoSugarToGuiOutput.txt").pathString,
                    Path(streamDumpDir, "protoSugarToGuiError.txt").pathString
                ).run() },
                async(Dispatchers.IO) { MappingInterceptor(orchestrator, STD_EXCEPTION_DUMP_PATH, streamList[1]).run() },
                async(Dispatchers.IO) { DataDumpInterceptor(
                    streamList[2],
                    Path(streamDumpDir!!, "protoSugarToEngine.txt").pathString,
                    Path(streamDumpDir, "engineToProtoSugarOutput.txt").pathString,
                    Path(streamDumpDir, "engineToProtoSugarError.txt").pathString
                ).run() }
            )
        }
        else if (doMapping)
            listOf(async(Dispatchers.IO) {
                MappingInterceptor(orchestrator, STD_EXCEPTION_DUMP_PATH, InterceptStreams.fromProcess(process).single()).run()
            })
        else if (doStreamDump)
            listOf(async(Dispatchers.IO) { DataDumpInterceptor(
                InterceptStreams.fromProcess(process)[0],
                Path(streamDumpDir!!, "input.txt").pathString,
                Path(streamDumpDir, "output.txt").pathString,
                Path(streamDumpDir, "error.txt").pathString
            ).run() })
        else
            listOf(async(Dispatchers.IO) { DoNothingInterceptor(InterceptStreams.fromProcess(process).single()).run() })

    try { awaitAll(*interceptors.toTypedArray()) }
    catch (ex: Exception)
    { ex.writeToFile(exceptionDumpPath()) }
}


private fun usage()
{
    println("usage: ProtoSugar MODE { $WITH_MAPPERS_TAG MAPPERS } [$EXCEPTION_DUMP_PATH_TAG EXCEPTION_DUMP_PATH]")
    println("MODE: $FILE_MODE_TAG INPUT_XML_FILE [$OUTPUT_TAG OUTPUT_XML_FILE]")
    println("    | $SERVER_MODE_TAG SERVER_RUN_CMD [$STREAM_DUMP_DIR_TAG STREAM_DUMP_DIR]")
    println("MAPPERS: ${availableMappers.keys.joinToString(" | ", transform = { "'$it'" })} (can be a comma separated list)")
    exitProcess(1)
}
