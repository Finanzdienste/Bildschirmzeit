package de.finanzdienste.bildschirmzeit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri

/*
 * Einstieg bildschirmzeit://firefox?url=<Adresse, mit encodeURIComponent
 * kodiert> für die Bewerbungszentrale (deren README „Links in einem richtigen
 * Firefox-Tab“): Aus der in Firefox installierten App öffnet Firefox Links
 * nur im kleinen Fenster, und einen Intent auf sich selbst verwirft es still.
 * Eine andere App darf Firefox aber ganz normal öffnen – das ist diese hier:
 * ACTION_VIEW im Paket org.mozilla.firefox, neuer Task, keine
 * Custom-Tab-Extras; ohne Firefox derselbe Intent ohne Paket. Nur http und
 * https. Keine eigene Oberfläche (Theme.NoDisplay), danach sofort zu – auch
 * ohne Netz (es geht nur ein Intent auf dem Handy hinaus).
 */

/** Die unsichtbare Aktivität hinter dem Einstieg (Intent-Filter mit VIEW, DEFAULT, BROWSABLE im Manifest). */
class FirefoxOeffnen : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nur beim ersten Start, nicht nach einem Neubau (der käme ohnehin nicht: noHistory, sofort zu).
        if (savedInstanceState == null) FirefoxEinstieg.oeffnen(this, intent?.data)
        finish()
    }
}

/** Die Regeln des Einstiegs – rein bis auf startActivity, getestet in FirefoxEinstiegTest. */
object FirefoxEinstieg {
    const val PAKET = "org.mozilla.firefox"
    const val SCHEMA = "bildschirmzeit"
    const val ZIEL = "firefox"

    /** Die Adresse aus bildschirmzeit://firefox?url=… – nur http und https mit Host, sonst null. */
    fun adresse(daten: Uri?): Uri? {
        if (daten == null || !daten.scheme.equals(SCHEMA, ignoreCase = true) || !daten.host.equals(ZIEL, ignoreCase = true)) return null
        val url = try {
            daten.getQueryParameter("url")
        } catch (e: UnsupportedOperationException) {
            null
        }?.trim()
        if (url.isNullOrEmpty()) return null
        val ziel = url.toUri()
        val schema = ziel.scheme?.lowercase()
        if ((schema != "http" && schema != "https") || ziel.host.isNullOrEmpty()) return null
        return ziel
    }

    /** Erst Firefox selbst, dann (ohne Firefox) derselbe Intent ohne Paket – beide im neuen Task, ohne Extras. */
    fun absichten(ziel: Uri): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW, ziel).setPackage(PAKET).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Intent.ACTION_VIEW, ziel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** Öffnet die Adresse; false, wenn sie nicht taugt oder nichts sie öffnen kann. */
    fun oeffnen(context: Context, daten: Uri?): Boolean {
        val ziel = adresse(daten) ?: return false
        for (absicht in absichten(ziel)) {
            try {
                context.startActivity(absicht)
                return true
            } catch (e: ActivityNotFoundException) {
                // ohne Firefox: der nächste Versuch ohne Paket
            } catch (e: SecurityException) {
                // dann eben der nächste Versuch
            }
        }
        return false
    }
}
