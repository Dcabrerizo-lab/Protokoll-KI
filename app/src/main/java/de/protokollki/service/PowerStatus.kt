package de.protokollki.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Akku- und Energiesparstatus für Hinweise vor bzw. während der Verarbeitung. Gelesen wird der
 * Systemstatus (ACTION_BATTERY_CHANGED), damit auch `adb shell dumpsys battery set …` in den
 * Tests wirkt.
 */
object PowerStatus {
    /** Unter diesem Akkustand (ohne Ladegerät) warnt die App vor der langen Verarbeitung. */
    const val LOW_BATTERY_PERCENT = 15

    data class Battery(val percent: Int, val plugged: Boolean, val temperatureC: Float?)

    fun battery(context: Context): Battery? = try {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) null else {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (level < 0) null
            else Battery(
                percent = level * 100 / scale,
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                temperatureC = if (temp == Int.MIN_VALUE) null else temp / 10f,
            )
        }
    } catch (e: Exception) {
        null
    }

    fun powerSaveMode(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }.getOrDefault(false)

    /** Kurzer Hinweis oder null, wenn alles in Ordnung ist. */
    fun warning(context: Context): String? {
        val b = battery(context)
        if (b != null && !b.plugged && b.percent < LOW_BATTERY_PERCENT) {
            return "Akku bei ${b.percent} % ohne Ladegerät: Die Verarbeitung braucht viel Strom und kann " +
                "abbrechen, wenn sich das Handy ausschaltet. Bitte Ladegerät anschließen."
        }
        if (powerSaveMode(context)) {
            return "Energiesparmodus ist an: Die Verarbeitung dauert länger. Mit Ladegerät geht es schneller."
        }
        return null
    }
}
