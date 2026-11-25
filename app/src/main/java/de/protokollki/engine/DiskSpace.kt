package de.protokollki.engine

import java.io.File

/** Speicherplatz prüfen und "Speicher voll" erkennen – ohne android.*, per JVM-Unit-Test prüfbar. */
object DiskSpace {
    const val FULL_MESSAGE =
        "Der Speicher des Handys ist voll. Bitte Platz schaffen (z. B. Videos oder alte Besprechungen " +
            "löschen) und erneut versuchen."

    /** Unter dieser Grenze startet keine neue Aufnahme (reicht für gut 25 min Audio). */
    const val MIN_FREE_TO_START = 50L * 1024 * 1024

    /** Unter dieser Grenze wird eine laufende Aufnahme gespeichert und beendet. */
    const val MIN_FREE_WHILE_RECORDING = 20L * 1024 * 1024

    /** Freier Platz für die App in [dir] (0 bei Fehler). */
    fun freeBytes(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    /** true, wenn [e] (oder eine Ursache) "kein Platz mehr auf dem Gerät" bedeutet (ENOSPC/EDQUOT). */
    fun isDiskFull(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 10) {
            if (t is DiskFullException) return true
            val m = t.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) ||
                m.contains("EDQUOT") || m.contains("Disk quota exceeded", ignoreCase = true)
            ) return true
            t = t.cause
        }
        return false
    }

    /** Lesbare Größe, z. B. "1,2 GB" oder "350 MB". */
    fun format(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.1f GB".format(java.util.Locale.GERMANY, bytes / 1e9)
        else "${bytes / 1_000_000L} MB"
}

/** Speicher voll – mit anzeigbarer deutscher Meldung. */
class DiskFullException : java.io.IOException(DiskSpace.FULL_MESSAGE)
