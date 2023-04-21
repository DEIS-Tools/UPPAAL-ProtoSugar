import driver.Driver
import mapping.Orchestrator
import java.io.*
import kotlin.system.exitProcess

const val STD_EXCEPTION_DUMP_PATH = "ProtoSugar-CrashDetails.txt"

private const val STDIN_MODE_TAG = "-map"
private const val FILE_MODE_TAG = "-file"
private const val OUTPUT_TAG = "-output"

private const val SERVER_MODE_TAG = "-server"
private const val STREAM_DUMP_DIR_TAG = "-stream-dump-dir"

private const val SOCKET_SERVER_MODE_TAG = "-socket-server"
private const val PORT_TAG = "-port"

private const val WITH_MAPPERS_TAG = "-with-mappers"
private const val EXCEPTION_DUMP_PATH_TAG = "-exception-dump-path"

private lateinit var tags: Map<String, List<String>>


fun main(args: Array<String>)
{
    tags = parseTags(args)
    try {
        val driver = Driver(activeMappers())
        when {
            tags.containsKey(STDIN_MODE_TAG) -> driver.runMapper(System.`in`, fileModeOutput())
            tags.containsKey(FILE_MODE_TAG) -> driver.runMapper(fileModeInput(), fileModeOutput())
            tags.containsKey(SERVER_MODE_TAG) -> driver.runLocalServer(serverModeLaunchCommand(), exceptionDumpPath(), serverModeStreamDumpDir())
            tags.containsKey(SOCKET_SERVER_MODE_TAG) -> driver.runSocketServer(socketServerModeLaunchCommand(), socketServerPort(), exceptionDumpPath())
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

    return tags
}
private fun parseValue(tag: String, value: String): List<String>
    = when (tag) {
        WITH_MAPPERS_TAG -> value.split(',').map { it.trim() }
        else -> listOf(value)
    }

private fun activeMappers() = tags[WITH_MAPPERS_TAG] ?: listOf()
private fun fileModeInput() = File(tags[FILE_MODE_TAG]!!.single()).inputStream()
private fun fileModeOutput() = (tags[OUTPUT_TAG]?.let { File(it.single()).outputStream() } ?: System.out).bufferedWriter()
private fun serverModeLaunchCommand() = tags[SERVER_MODE_TAG]!!.single()
private fun serverModeStreamDumpDir() = tags[STREAM_DUMP_DIR_TAG]?.single()
private fun exceptionDumpPath() = tags[EXCEPTION_DUMP_PATH_TAG]?.single() ?: STD_EXCEPTION_DUMP_PATH
private fun socketServerModeLaunchCommand() = tags[SOCKET_SERVER_MODE_TAG]!!.single()
private fun socketServerPort() = ((tags[PORT_TAG]?.single() ?: "2350").toUShortOrNull() ?: throw Exception("'${tags[PORT_TAG]!!}' is not a valid port number")).toInt()


private fun usage()
{
    println("usage: ProtoSugar MODE { $WITH_MAPPERS_TAG MAPPERS } [$EXCEPTION_DUMP_PATH_TAG EXCEPTION_DUMP_PATH]")
    println("MODE: $STDIN_MODE_TAG [$OUTPUT_TAG OUTPUT_XML_FILE]                            # This requires you to pipe the model xml into stdin")
    println("    | $FILE_MODE_TAG INPUT_XML_FILE [$OUTPUT_TAG OUTPUT_XML_FILE]")
    println("    | $SERVER_MODE_TAG SERVER_RUN_CMD [$STREAM_DUMP_DIR_TAG STREAM_DUMP_DIR]")
    println("    | $SOCKET_SERVER_MODE_TAG SERVER_RUN_CMD [$PORT_TAG LISTEN_PORT]")
    println("MAPPERS: ${Orchestrator.availableMappers.keys.joinToString(" | ", transform = { "'$it'" })} (can be a comma separated list)")
    exitProcess(1)
}
