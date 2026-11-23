package de.protokollki.testutil

/**
 * Markiert Tests, die die echten KI-Modelle (Paket „Kompakt“) im App-Speicher brauchen und auf dem
 * Emulator mehrere Minuten laufen. Die CI trennt damit schnelle und langsame Läufe:
 *
 *  - nur schnelle: `-e notAnnotation de.protokollki.testutil.RequiresModels`
 *  - nur langsame: `-e annotation de.protokollki.testutil.RequiresModels`
 *
 * Fehlen die Modelle, werden die Tests übersprungen (Assume). Mit `-e requireModels true`
 * schlagen sie stattdessen fehl – für den Modell-Job der CI, damit ein vergessener Upload
 * nicht als „grün“ durchrutscht.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class RequiresModels
