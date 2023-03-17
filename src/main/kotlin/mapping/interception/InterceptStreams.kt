package mapping.interception

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class InterceptStreams private constructor(
    val inInput: InputStream,   // Parent -> Interceptor
    val inOutput: OutputStream,  // Interceptor -> Child
    val outInput: InputStream,  // Child -> Interceptor
    val outOutput: OutputStream, // Interceptor -> Parent
    val errInput: InputStream,  // Child -> Interceptor
    val errOutput: OutputStream  // Interceptor -> Parent
) {
    companion object {
        @JvmStatic
        fun fromProcess(process: Process, levels: Int = 1): List<InterceptStreams> {
            val streams = ArrayList<InterceptStreams>()

            var inInput = System.`in`
            var outOutput: OutputStream = System.out
            var errOutput: OutputStream = System.err

            for (i in 1 until levels) {
                val inOutput = PipedOutputStream()
                val outInput = PipedInputStream()
                val errInput = PipedInputStream()

                streams.add(InterceptStreams(
                    inInput, inOutput,
                    outInput, outOutput,
                    errInput, errOutput
                ))

                inInput = PipedInputStream(inOutput)
                outOutput = PipedOutputStream(outInput)
                errOutput = PipedOutputStream(errInput)
            }

            streams.add(InterceptStreams(
                inInput, process.outputStream,
                process.inputStream, outOutput,
                process.errorStream, errOutput
            ))

            return streams
        }
    }
}