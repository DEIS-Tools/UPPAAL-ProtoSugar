package mapping.interception.mapping

import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.lang.Exception

enum class Match { NO_MATCH, PARTIAL_MATCH, FULL_MATCH }

abstract class InterceptHandler
{
    abstract val prefix: String

    fun canHandle() : Match {
        return Match.FULL_MATCH
    }

    fun handle(inputBuffer: String, inputStream: BufferedReader): String? =
        if (inputBuffer == prefix)
            doHandle(Json.parseToJsonElement(readFullJson(inputBuffer, inputStream)).jsonObject)
        else
            null

    private fun readFullJson(inputBuffer: String, inputStream: BufferedReader): String {
        var depth = inputBuffer.count { it == '{' }
        val builder = StringBuilder(inputBuffer)
        while (depth != 0) {
            val char = inputStream.read()
            builder.append(char)
            when (char.toChar()) {
                '\\' -> builder.append(inputStream.read())
                '{' -> ++depth
                '}' -> --depth
            }
        }
        return builder.toString()
    }

    protected abstract fun doHandle(json: JsonObject): String
    abstract fun formatExceptionDumpString(exception: Exception)
}