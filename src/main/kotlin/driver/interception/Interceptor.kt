package driver.interception

import driver.tools.CancellationToken

abstract class Interceptor(private var cancellationToken: CancellationToken)
{
    fun run(delay: Long = 25L)
    {
        while (!cancellationToken.isCancellationRequested()) {
            fromGuiToEngine()
            Thread.sleep(delay)

            fromEngineToGui()
            fromEngineToGuiError()
            Thread.sleep(delay)
        }
    }

    protected abstract fun fromGuiToEngine()
    protected abstract fun fromEngineToGui()
    protected abstract fun fromEngineToGuiError()
}