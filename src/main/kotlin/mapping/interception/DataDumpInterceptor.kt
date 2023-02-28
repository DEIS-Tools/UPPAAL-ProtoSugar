package mapping.interception

import java.io.BufferedWriter
import java.io.File

class DataDumpInterceptor(
    streams: InterceptStreams,
    inputStreamFile: String,
    outputStreamFile: String,
    errorStreamFile: String
) : Interceptor()
{
    private val inInput = streams.inInput
    private val inOutput = streams.inOutput
    private val outInput = streams.outInput
    private val outOutput = streams.outOutput
    private val errInput = streams.errInput
    private val errOutput = streams.errOutput

    private val toChildFile: BufferedWriter = File(inputStreamFile).outputStream().bufferedWriter()
    private val toParentFile: BufferedWriter = File(outputStreamFile).outputStream().bufferedWriter()
    private val toParentErrorFile: BufferedWriter = File(errorStreamFile).outputStream().bufferedWriter()


    override fun fromGuiToEngine() {
        while (inInput.ready()) {
            val ch = inInput.read()
            toChildFile.write(ch)
            inOutput.write(ch)
        }
        toChildFile.flush()
        inOutput.flush()
    }

    override fun fromEngineToGui() {
        while (outInput.ready()) {
            val ch = outInput.read()
            toParentFile.write(ch)
            outOutput.write(ch)
        }
        toParentFile.flush()
        outOutput.flush()
    }

    override fun fromEngineToGuiError() {
        while (errInput.ready()) {
            val ch = errInput.read()
            toParentErrorFile.write(ch)
            errOutput.write(ch)
        }
        toParentErrorFile.flush()
        errOutput.flush()
    }
}