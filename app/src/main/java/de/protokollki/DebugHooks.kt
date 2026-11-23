package de.protokollki

/** Nur für automatisierte Tests in Debug-Builds (gesetzt über den DEBUG_IMPORT-Intent). */
object DebugHooks {
    /** Tagesordnung, die der nächsten importierten Besprechung vor der Verarbeitung zugewiesen wird. */
    @Volatile var pendingAgenda: List<String>? = null

    fun consumeAgenda(): List<String>? = pendingAgenda.also { pendingAgenda = null }
}
