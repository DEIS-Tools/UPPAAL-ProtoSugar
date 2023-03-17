package mapping.interception

class DoNothingInterceptor(private val streams: InterceptStreams) : Interceptor()
{
    private val inInput = streams.inInput.bufferedReader()
    private val inOutput = streams.inOutput.bufferedWriter()
    private val outInput = streams.outInput.bufferedReader()
    private val outOutput = streams.outOutput.bufferedWriter()
    private val errInput = streams.errInput.bufferedReader()
    private val errOutput = streams.errOutput.bufferedWriter()

    override fun fromGuiToEngine() {
        while (inInput.ready())
            inOutput.write(inInput.read())
        inOutput.flush()
    }

    override fun fromEngineToGui() {
        while (outInput.ready())
            outOutput.write(outInput.read())
        outOutput.flush()
    }

    override fun fromEngineToGuiError() {
        while (errInput.ready())
            errOutput.write(errInput.read())
        errOutput.flush()
    }
}