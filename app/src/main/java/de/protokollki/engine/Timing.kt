package de.protokollki.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Dauer der Verarbeitungsschritte im Systemprotokoll („PKREPORT schritt.N=…“). Der Test auf echten
 * Handys liest diese Zeilen aus und zeigt so, wo auf welchem Gerät die Zeit hingeht.
 * Enthält keine Inhalte der Besprechung, nur Zahlen.
 */
object Timing {
    private val n = AtomicInteger()

    fun report(step: String, text: String) {
        runCatching { android.util.Log.i("ProtokollKI", "PKREPORT schritt.${n.incrementAndGet()}=$step: $text") }
    }

    fun since(step: String, startMs: Long, extra: String = "") =
        report(step, "${(System.currentTimeMillis() - startMs) / 1000.0} s" + if (extra.isEmpty()) "" else ", $extra")
}
