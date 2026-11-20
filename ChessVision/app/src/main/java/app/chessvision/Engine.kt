package app.chessvision

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import kotlin.math.min

/** Talks UCI to a Stockfish binary shipped as jniLibs/arm64-v8a/libstockfish.so */
class Engine(ctx: Context) {
    private val p = ProcessBuilder(ctx.applicationInfo.nativeLibraryDir + "/libstockfish.so").redirectErrorStream(true).start()
    private val w: BufferedWriter = p.outputStream.bufferedWriter()
    private val r: BufferedReader = p.inputStream.bufferedReader()

    init {
        send("uci"); until("uciok")
        send("setoption name Threads value ${min(4, Runtime.getRuntime().availableProcessors())}")
        send("setoption name Hash value 64")
        send("isready"); until("readyok")
    }

    private fun send(s: String) { w.write(s + "\n"); w.flush() }
    private fun until(s: String) { while (true) if (r.readLine()?.startsWith(s) != false) return }

    @Synchronized
    fun best(fen: String, ms: Int = 800): String? {
        send("position fen $fen"); send("go movetime $ms")
        while (true) {
            val l = r.readLine() ?: return null
            if (l.startsWith("bestmove")) return l.split(" ").getOrNull(1)?.takeIf { it.length >= 4 }
        }
    }

    fun close() { try { send("quit") } catch (_: Exception) {}; p.destroy() }
}
