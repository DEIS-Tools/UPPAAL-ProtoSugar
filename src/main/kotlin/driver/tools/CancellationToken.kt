package driver.tools

class CancellationToken {
    private var cancellationRequested = false

    fun isCancellationRequested() = cancellationRequested
    fun requestCancellation() {
        cancellationRequested = true
    }
}