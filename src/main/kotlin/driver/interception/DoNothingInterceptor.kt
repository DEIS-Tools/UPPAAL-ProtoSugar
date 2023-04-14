package driver.interception

import driver.tools.CancellationToken
import driver.tools.InterceptStreams

class DoNothingInterceptor(streams: InterceptStreams, cancellationToken: CancellationToken)
    : Interceptor(cancellationToken)
{
    private val inInput = streams.inInput
    private val inOutput = streams.inOutput
    private val outInput = streams.outInput
    private val outOutput = streams.outOutput
    private val errInput = streams.errInput
    private val errOutput = streams.errOutput

    override fun fromGuiToEngine() {
        while (inInput.available() > 0)
            inOutput.write(inInput.read())
        inOutput.flush()
    }

    override fun fromEngineToGui() {
        while (outInput.available() > 0)
            outOutput.write(outInput.read())
        outOutput.flush()
    }

    override fun fromEngineToGuiError() {
        while (errInput.available() > 0)
            errOutput.write(errInput.read())
        outOutput.flush()
    }
}