package mapping.interception

class DoNothingInterceptor(private val streams: InterceptStreams) : Interceptor()
{
    override fun fromGuiToEngine() {
        while (streams.inInput.ready())
            streams.inOutput.write(streams.inInput.read())
        streams.inOutput.flush()
    }

    override fun fromEngineToGui() {
        while (streams.outInput.ready())
            streams.outOutput.write(streams.outInput.read())
        streams.outOutput.flush()
    }

    override fun fromEngineToGuiError() {
        while (streams.errInput.ready())
            streams.errOutput.write(streams.errInput.read())
        streams.errOutput.flush()
    }
}