package mapping.interception

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream

class InterceptStreams private constructor(
    val inInput: BufferedReader,   // Parent -> Interceptor
    val inOutput: BufferedWriter,  // Interceptor -> Child
    val outInput: BufferedReader,  // Child -> Interceptor
    val outOutput: BufferedWriter, // Interceptor -> Parent
    val errInput: BufferedReader,  // Child -> Interceptor
    val errOutput: BufferedWriter  // Interceptor -> Parent
) {
    companion object {
        @JvmStatic
        fun fromProcess(process: Process, levels: Int = 1): List<InterceptStreams> {
            val streams = ArrayList<InterceptStreams>()

            var inInput = System.`in`
            var outInput = process.inputStream
            var errInput = process.errorStream

            for (i in 1 until levels) {
                val inOutput = PipedOutputStream()
                val outOutput = PipedOutputStream()
                val errOutput = PipedOutputStream()

                streams.add(InterceptStreams(
                    inInput.bufferedReader(), inOutput.bufferedWriter(),
                    outInput.bufferedReader(), outOutput.bufferedWriter(),
                    errInput.bufferedReader(), errOutput.bufferedWriter()
                ))

                inInput = PipedInputStream(inOutput)
                outInput = PipedInputStream(outOutput)
                errInput = PipedInputStream(errOutput)
            }

            streams.add(InterceptStreams(
                inInput.bufferedReader(), process.outputStream.bufferedWriter(),
                outInput.bufferedReader(), System.out.bufferedWriter(),
                errInput.bufferedReader(), System.err.bufferedWriter()
            ))

            return streams
        }
    }
}