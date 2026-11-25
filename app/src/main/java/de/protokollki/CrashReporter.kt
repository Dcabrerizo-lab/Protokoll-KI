package de.protokollki

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Absturzberichte, die das Gerät nie von selbst verlassen.
 *
 * - Java/Kotlin-Abstürze: eigener UncaughtExceptionHandler schreibt einen Bericht in den privaten
 *   App-Speicher (crash/), danach übernimmt der normale Android-Handler.
 * - Abstürze im nativen Code (KI, Spracherkennung), ANR und vom System beendete Prozesse:
 *   Android meldet sie beim nächsten Start über ApplicationExitInfo (ab Android 11).
 *
 * Inhalt: Zeit, App-Version, Gerät, Android-Version, Fehlerstelle. Keine Aufnahmen, Transkripte
 * oder Protokolle. Gesendet wird nur, wenn die Person es beim nächsten Start auswählt; dann öffnet
 * sich die E-Mail-App mit dem sichtbaren Text, den sie vor dem Senden prüfen kann.
 */
object CrashReporter {
    /** Support-Adresse für Berichte; leer = E-Mail-App ohne Empfänger öffnen. */
    const val SUPPORT_EMAIL = ""

    private const val PREFS = "crash"
    private const val KEY_LAST_EXIT = "last_exit_ts"
    private const val MAX_TRACE = 12_000

    private fun dir(context: Context) = File(context.filesDir, "crash").apply { mkdirs() }
    private fun pending(context: Context) = File(dir(context), "pending.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTraceToString().take(MAX_TRACE)
                pending(app).writeText(header(app, "Absturz (Thread ${thread.name})") + trace)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Bericht vom letzten Lauf, falls einer vorliegt (eigener Handler oder System-Meldung).
     * Gibt null zurück, wenn der letzte Lauf normal endete.
     */
    fun takePending(context: Context): String? {
        val f = pending(context)
        if (f.exists()) {
            val text = runCatching { f.readText() }.getOrNull()
            runCatching { f.renameTo(File(dir(context), "last.txt")) }
            markExitsSeen(context)
            return text
        }
        return systemExit(context)
    }

    /** Letzter gespeicherter Bericht (für „Absturzbericht senden“ in den Einstellungen). */
    fun latest(context: Context): String? =
        listOf(pending(context), File(dir(context), "last.txt")).firstOrNull { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }

    fun shareLatest(context: Context) {
        val text = latest(context)
            ?: (header(context, "Kein Absturz gespeichert – Rückmeldung") + "Bitte beschreiben Sie das Problem:\n")
        share(context, text)
    }

    fun share(context: Context, report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            if (SUPPORT_EMAIL.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Protokoll KI – Fehlerbericht")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, "Bericht senden").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun header(context: Context, kind: String): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date())
        return buildString {
            appendLine("Protokoll KI – $kind")
            appendLine("Zeit: $time")
            appendLine("Version: $version")
            appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL} (${soc()})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
        }
    }

    private fun soc(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unbekannt"

    private fun markExitsSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_EXIT, System.currentTimeMillis()).apply()
    }

    /** Native Abstürze und ANR des letzten Laufs (Android 11+). Vom System beendete Läufe zählen nicht. */
    private fun systemExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }
            .getOrDefault(emptyList())
        val fresh = exits.filter { it.timestamp > seen }
        prefs.edit().putLong(KEY_LAST_EXIT, maxOf(seen, exits.maxOfOrNull { it.timestamp } ?: 0L)).apply()
        // Erster Start nach Installation: alte Einträge nicht melden
        if (seen == 0L) return null
        val bad = fresh.firstOrNull {
            it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                it.reason == ApplicationExitInfo.REASON_ANR ||
                it.reason == ApplicationExitInfo.REASON_CRASH
        } ?: return null
        val kind = when (bad.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Absturz im KI-/Audio-Teil (nativ)"
            ApplicationExitInfo.REASON_ANR -> "App reagierte nicht (ANR)"
            else -> "Absturz"
        }
        val detail = buildString {
            appendLine("Prozess: ${bad.processName}")
            appendLine("Beschreibung: ${bad.description ?: "-"}")
            appendLine("Speicher (PSS): ${bad.pss / 1024} MB, RSS: ${bad.rss / 1024} MB")
            if (bad.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    bad.traceInputStream?.bufferedReader()?.use { r ->
                        appendLine()
                        append(r.readText().take(MAX_TRACE))
                    }
                }
            }
        }
        val text = header(context, kind) + detail
        runCatching { File(dir(context), "last.txt").writeText(text) }
        return text
    }
}
