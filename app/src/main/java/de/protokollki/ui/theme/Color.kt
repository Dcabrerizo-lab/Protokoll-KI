package de.protokollki.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

// ---------------------------------------------------------------- Markenfarben

val Indigo10 = Color(0xFF110C4A)
val Indigo20 = Color(0xFF221A8F)
val Indigo30 = Color(0xFF3329C4)
val Indigo40 = Color(0xFF4F46E5)
val Indigo80 = Color(0xFFC3C0FF)
val Indigo90 = Color(0xFFE3E1FF)

val Violet40 = Color(0xFF6D4AC9)
val Violet80 = Color(0xFFD0BCFF)
val Violet90 = Color(0xFFEADDFF)
val Violet20 = Color(0xFF381E72)
val Violet30 = Color(0xFF4F378B)

val Coral40 = Color(0xFFC2410C)
val Coral80 = Color(0xFFFFB693)
val Coral90 = Color(0xFFFFDBCB)
val Coral20 = Color(0xFF5B1A00)
val Coral30 = Color(0xFF7F2A00)

val RecordRed = Color(0xFFEF4444)
val RecordRedDark = Color(0xFFB91C1C)
val SuccessGreen = Color(0xFF16A34A)
val SuccessGreenDark = Color(0xFF4ADE80)
val WarningAmber = Color(0xFFD97706)

/** Hintergrund des Aufnahme-Bildschirms (immer dunkel, wirkt fokussiert). */
val RecordingBgTop = Color(0xFF1B1745)
val RecordingBgBottom = Color(0xFF0B0A1A)

/** Verlauf für den großen Aufnahme-Knopf. */
val RecordGradientStart = Color(0xFF6366F1)
val RecordGradientEnd = Color(0xFF8B5CF6)

// ---------------------------------------------------------------- Sprecherfarben

/** 8 gut unterscheidbare Farben, auf allen mit weißer Schrift gut lesbar. */
val SpeakerPalette = listOf(
    Color(0xFF4F46E5), // Indigo
    Color(0xFF0F766E), // Petrol
    Color(0xFFC2410C), // Orange
    Color(0xFFBE185D), // Magenta
    Color(0xFF0369A1), // Blau
    Color(0xFF7E22CE), // Violett
    Color(0xFF15803D), // Grün
    Color(0xFFA16207), // Ocker
)

fun speakerColor(id: Int): Color = SpeakerPalette[abs(id) % SpeakerPalette.size]
