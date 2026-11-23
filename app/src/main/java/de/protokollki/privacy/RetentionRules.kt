package de.protokollki.privacy

import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus

/*
 * Reine Regeln der Audio-Löschfrist (ohne android.*, per JVM-Unit-Test prüfbar).
 */

internal const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Ist eine Aufnahme vom Zeitpunkt [createdAt] zum Zeitpunkt [now] (beides Epoch-Millis) älter als
 * [days] volle Tage? `days <= 0` bedeutet „nie löschen“. Zeitstempel in der Zukunft (Uhr verstellt)
 * gelten nie als abgelaufen.
 */
fun isExpired(createdAt: Long, now: Long, days: Int): Boolean {
    if (days <= 0 || createdAt <= 0L || now < createdAt) return false
    return now - createdAt >= days * DAY_MS
}

/**
 * Soll das Audio dieser Besprechung jetzt gelöscht werden? Nur wenn die Frist abgelaufen ist, das
 * Audio noch da ist und die Besprechung fertig verarbeitet wurde. Laufende Aufnahmen/Verarbeitungen
 * und noch nicht transkribierte Aufnahmen werden nie angerührt – sonst ginge die Besprechung
 * unwiederbringlich verloren.
 */
fun shouldDeleteAudio(m: Meeting, now: Long, days: Int): Boolean =
    !m.audioDeleted &&
        m.status == MeetingStatus.DONE &&
        m.segments.isNotEmpty() &&
        isExpired(m.createdAt, now, days)
