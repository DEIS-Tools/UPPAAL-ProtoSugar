package driver.tools

import java.io.InputStream
import java.io.OutputStream

data class GuiStreams(val input: InputStream, val output: OutputStream, val error: OutputStream)