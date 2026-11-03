// Dumps mini_qwerty_kb's TelexProcessor output for the parity corpus, so it can be
// diffed against the Vikey harness. Pure Kotlin: TelexProcessor has no Android imports.
//
//   raw<TAB>live<TAB>commit
//   live   = resolve(raw, smart = true, dict = null)   — what the IME shows while composing
//   commit = resolve(raw, smart = true, dict = loaded) — what commitBuffer() inserts
import com.miniqwerty.kb.TelexProcessor
import java.io.File

fun main(args: Array<String>) {
    val dictPath = args.getOrNull(0)
    val dict: Set<String>? = dictPath?.let { p ->
        val f = File(p)
        if (!f.exists()) { System.err.println("dict not found: $p (skipping dictionary check)"); null }
        else f.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
    }
    System.err.println("dict size: ${dict?.size ?: 0}")

    while (true) {
        val line = readlnOrNull() ?: break
        if (line.isEmpty() || line.startsWith("#")) continue
        val live = TelexProcessor.resolve(line, smart = true, dict = null)
        val commit = TelexProcessor.resolve(line, smart = true, dict = dict)
        println("$line\t$live\t$commit")
    }
}
