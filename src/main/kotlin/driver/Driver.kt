package driver

import driver.interception.*
import driver.tools.CancellationToken
import driver.tools.GuiStreams
import driver.tools.InterceptStreams
import kotlinx.coroutines.*
import mapping.Orchestrator
import java.io.BufferedWriter
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.system.exitProcess


class Driver(private val activeMappers: List<String>) {
    fun runMapper(input: InputStream, output: BufferedWriter) {
        val orchestrator = Orchestrator(activeMappers)
        val result = orchestrator.mapModel(input)
        if (result.second.isNotEmpty())
            output.write("There were errors:\n" + result.second.joinToString("\n"))
        else
            output.write(result.first)
        output.flush()
    }

    fun runLocalServer(serverCmd: String, exceptionDumpDir: String, streamDumpDir: String?) {
        val guiStreams = GuiStreams(System.`in`, System.out, System.err)
        runServer(guiStreams, serverCmd, exceptionDumpDir, streamDumpDir)
    }

    @Suppress("DeferredResultUnused")
    fun runSocketServer(serverCmd: String, port: Int, exceptionDumpDir: String) {
        Orchestrator(activeMappers) // Test that the mappers are valid
        println("Listening on port '$port'")
        runBlocking {
            async(Dispatchers.IO) {
                val server = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                while (true) {
                    val clientSocket = server.accept()
                    val guiStreams = GuiStreams(clientSocket.getInputStream(), clientSocket.getOutputStream(), System.err)
                    async {
                        println("Got connection: ${clientSocket.remoteSocketAddress}")
                        try {
                            runServer(guiStreams, serverCmd, exceptionDumpDir, null)
                        }
                        catch (ex: Exception) {
                            println(ex)
                        }
                        println("Lost connection: ${clientSocket.remoteSocketAddress}")
                    }
                }
            }

            withContext(Dispatchers.IO) {
                while (true)
                    if (System.`in`.read() == 'e'.code)
                        exitProcess(0)
            }
        }
    }


    private fun runServer(guiStreams: GuiStreams, serverCmd: String, exceptionDumpDir: String, streamDumpDir: String?) {
        val cancellationToken = CancellationToken()
        val params = serverCmd.split(',').map { it.trim() }.toTypedArray()
        val serverProcess = ProcessBuilder(*params).start()
        serverProcess.onExit().thenAccept {
            cancellationToken.requestCancellation()
        }

        val orchestrator = Orchestrator(activeMappers)
        val doMapping = orchestrator.numberOfMappers > 0
        val doStreamDump = streamDumpDir != null

        runBlocking {
            val interceptors = when {
                doMapping && doStreamDump -> runMapAndDump(guiStreams, serverProcess, exceptionDumpDir, streamDumpDir!!, cancellationToken, orchestrator)
                doMapping                 -> runMapNoDump(guiStreams, serverProcess, exceptionDumpDir, cancellationToken, orchestrator)
                doStreamDump              -> runDumpNoMap(guiStreams, serverProcess, streamDumpDir, cancellationToken)
                else                      -> runNative(guiStreams, serverProcess, cancellationToken)
            }
            awaitAll(*interceptors.toTypedArray())
        }
    }

    private fun CoroutineScope.runMapAndDump(
        guiStreams: GuiStreams,
        serverProcess: Process,
        exceptionDumpDir: String,
        streamDumpDir: String,
        cancellationToken: CancellationToken,
        orchestrator: Orchestrator
    ): List<Deferred<Unit>> {
        val streamList = InterceptStreams.from(guiStreams, serverProcess, 3)
        return listOf(
            async(Dispatchers.IO) {
                DataDumpInterceptor(
                    streamList[0],
                    Path(streamDumpDir, "guiToProtoSugar.txt").pathString,
                    Path(streamDumpDir, "protoSugarToGuiOutput.txt").pathString,
                    Path(streamDumpDir, "protoSugarToGuiError.txt").pathString,
                    cancellationToken
                ).run()
            },
            async(Dispatchers.IO) { MappingInterceptor(orchestrator, exceptionDumpDir, streamList[1], cancellationToken).run() },
            async(Dispatchers.IO) {
                DataDumpInterceptor(
                    streamList[2],
                    Path(streamDumpDir, "protoSugarToEngine.txt").pathString,
                    Path(streamDumpDir, "engineToProtoSugarOutput.txt").pathString,
                    Path(streamDumpDir, "engineToProtoSugarError.txt").pathString,
                    cancellationToken
                ).run()
            }
        )
    }

    private fun CoroutineScope.runMapNoDump(
        guiStreams: GuiStreams,
        serverProcess: Process,
        exceptionDumpDir: String,
        cancellationToken: CancellationToken,
        orchestrator: Orchestrator
    ): List<Deferred<Unit>> {
        val interceptStreams = InterceptStreams.from(guiStreams, serverProcess).single()
        return listOf(
            async(Dispatchers.IO) {
                MappingInterceptor(orchestrator, exceptionDumpDir, interceptStreams, cancellationToken).run()
            }
        )
    }

    private fun CoroutineScope.runDumpNoMap(
        guiStreams: GuiStreams,
        serverProcess: Process,
        streamDumpDir: String?,
        cancellationToken: CancellationToken
    ): List<Deferred<Unit>> {
        val interceptStreams = InterceptStreams.from(guiStreams, serverProcess).single()
        return listOf(
            async(Dispatchers.IO) {
                DataDumpInterceptor(
                    interceptStreams,
                    Path(streamDumpDir!!, "input.txt").pathString,
                    Path(streamDumpDir, "output.txt").pathString,
                    Path(streamDumpDir, "error.txt").pathString,
                    cancellationToken
                ).run()
            }
        )
    }

    private fun CoroutineScope.runNative(
        guiStreams: GuiStreams,
        serverProcess: Process,
        cancellationToken: CancellationToken
    ): List<Deferred<Unit>> {
        val interceptStreams = InterceptStreams.from(guiStreams, serverProcess).single()
        return listOf(
            async(Dispatchers.IO) {
                DoNothingInterceptor(interceptStreams, cancellationToken).run()
            }
        )
    }
}