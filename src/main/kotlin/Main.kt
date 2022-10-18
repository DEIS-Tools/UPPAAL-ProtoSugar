import mappers.PaChaMapper
import java.io.*
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess


const val FILE_TAG = "-file"
const val SERVER_TAG = "-server"
const val DEBUG_TAG = "-debug"

fun main(args: Array<String>)
{
    //if (args.size != 2) usage()

    when {
        FILE_TAG   == args[0] && args.size == 2 -> runMapper(File(args[1]))
        SERVER_TAG == args[0] && args.size == 2 -> runServer(args[1])
        DEBUG_TAG  == args[0] && args.size == 5 -> runDebug(File(args[1]), File(args[2]), File(args[3]), File(args[4]))
        else -> usage()
    }
}

fun runMapper(file: File)
{
    print(PaChaMapper().map(file.inputStream()))
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
    var originalModel = input.readLine();
    originalModel = originalModel.substring(originalModel.indexOf("<?"))
    while (!originalModel.endsWith("\"}") || originalModel.endsWith("\\\"}"))
        originalModel += input.read().toChar()
    originalModel = originalModel.removeSuffix("\"}")

    val mappedModel = PaChaMapper().map(originalModel.replace("\\\"", "\""))

    return "{\"cmd\":\"newXMLSystem3\",\"args\":\"${mappedModel.replace("\"", "\\\"")}\"}"
}

fun runDebug(server: File, inputFile: File, outputFile: File, errorFile: File)
{
    val process = ProcessBuilder(server.path).start()

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
    println("usage: uppaal_mapper $FILE_TAG FILE_PATH")
    println("usage: uppaal_mapper $SERVER_TAG SERVER_PATH")
    println("usage: uppaal_mapper $DEBUG_TAG SERVER_PATH STDIN_OUTPUT_PATH STDOUT_OUTPUT_PATH STDERR_OUTPUT_PATH")
    exitProcess(1)
}