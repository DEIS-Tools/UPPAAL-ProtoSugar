package interception

abstract class Interceptor
{
    fun run(delay: Long = 25L)
    {
        while (true) {
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