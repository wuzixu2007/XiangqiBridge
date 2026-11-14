package local.chessbridge

import android.content.Context
import android.util.Log
import java.io.File

internal class PhoneEngine(private val context: Context) {
    @Volatile private var process: Process? = null
    private var input: java.io.BufferedWriter? = null
    private var output: java.io.BufferedReader? = null

    fun choose(board: Board): String? {
        if (process?.isAlive != true) start()
        command("position fen ${board.fen()}")
        command("go movetime 450")
        return expect("bestmove").split(' ').getOrNull(1)?.takeUnless { it == "(none)" || it == "0000" }
    }

    private fun start() {
        val weights = File(context.filesDir, "pikafish-v1.nnue")
        if (!weights.isFile) {
            val temporary = File(context.filesDir, "pikafish.nnue.tmp")
            context.assets.open("pikafish.nnue").use { source ->
                temporary.outputStream().use { source.copyTo(it) }
            }
            check(temporary.renameTo(weights)) { "无法保存 Pikafish 权重" }
        }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libpikafish.so")
        val child = ProcessBuilder(binary.absolutePath).directory(context.filesDir)
            .redirectErrorStream(true).start()
        process = child
        input = child.outputStream.bufferedWriter()
        output = child.inputStream.bufferedReader()
        command("uci")
        expect("uciok")
        command("setoption name EvalFile value ${weights.absolutePath}")
        command("setoption name Threads value 2")
        command("setoption name Hash value 32")
        command("isready")
        expect("readyok")
        Log.i("ChessBridge", "Android Pikafish ready")
    }

    private fun command(line: String) {
        val writer = checkNotNull(input)
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    private fun expect(prefix: String): String {
        while (true) {
            val line = output?.readLine() ?: error("Pikafish 进程已退出")
            if (line == prefix || line.startsWith("$prefix ")) return line
            if (line.contains("ERROR", ignoreCase = true)) error(line.take(160))
        }
    }

    fun close() { process?.destroyForcibly() }
}
