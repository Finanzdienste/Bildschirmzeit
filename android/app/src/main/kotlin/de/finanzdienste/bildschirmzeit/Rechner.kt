package de.finanzdienste.bildschirmzeit

import android.content.Context
import android.util.Log
import org.json.JSONException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** Alles, was Widget und App über heute zeigen. */
data class Ergebnis(
    /** Nutzungsdatenzugriff erlaubt? Sonst ist `posten` leer. */
    val erlaubt: Boolean,
    /** Absteigend nach Zeit; die drei Web-Apps statt (eines Teils von) Chrome. */
    val posten: List<Posten>,
    /** Wann gerechnet wurde (ms). */
    val stand: Long,
    val web: WebDaten,
    /** Nur fürs Widget: Ein neueres Release kommt ohne Tobi nicht an (UpdateRegeln.widgetHinweis). */
    val updateHinweis: Boolean = false,
) {
    val gesamt: Long get() = posten.sumOf { it.sekunden }

    /** Leise Kennzeichnung im Widget: Die Web-Apps kommen aus dem Zwischenspeicher. */
    val ohneNetz: Boolean get() = web.ohneNetz && web.verbunden
}

object Rechner {
    private const val VORLAUF_MS = 8 * 60 * 60 * 1000L

    /**
     * Rechnet heute (ab lokaler Mitternacht bis jetzt). `netz`: Supabase
     * fragen – sonst (und wenn das nicht klappt) die zuletzt gelesenen Werte.
     */
    fun berechnen(context: Context, netz: Boolean, jetzt: Long = System.currentTimeMillis()): Ergebnis {
        val zone = ZoneId.systemDefault()
        val heute = Instant.ofEpochMilli(jetzt).atZone(zone).toLocalDate()
        val mitternacht = heute.atStartOfDay(zone).toInstant().toEpochMilli()
        val speicher = Speicher(context)
        val web = webDaten(speicher, heute, netz, jetzt)
        if (!Nutzungsdaten.erlaubt(context)) return Ergebnis(erlaubt = false, posten = emptyList(), stand = jetzt, web = web)

        val pm = context.packageManager
        // Ein paar Stunden vor Mitternacht anfangen: Eine App, die über
        // Mitternacht offen war, hat ihr „vorn“ vor Mitternacht bekommen.
        val ereignisse = try {
            Nutzungsdaten.ereignisse(context, mitternacht - VORLAUF_MS, jetzt)
        } catch (e: SecurityException) {
            // Gerade eben in den Einstellungen wieder abgeschaltet.
            return Ergebnis(erlaubt = false, posten = emptyList(), stand = jetzt, web = web)
        }
        val android = heuteJeApp(ereignisse, mitternacht, jetzt, Nutzungsdaten.launcherPakete(pm), Nutzungsdaten.homeActivities(pm))
        val posten = aufteilen(webApksZuChrome(android), web.heute).map { (schluessel, sek) ->
            Posten(schluessel, WebApp.von(schluessel)?.anzeigename ?: Nutzungsdaten.name(pm, schluessel), sek)
        }
        return Ergebnis(erlaubt = true, posten = sortiert(posten), stand = jetzt, web = web)
    }

    /**
     * Die Web-Apps von Supabase (letzte acht Tage) oder aus dem Zwischenspeicher.
     * „ohne Netz“ heißt: Der letzte Netzversuch ging schief – auch bei den
     * Rechnungen dazwischen, die Supabase gar nicht fragen (Takt.plan).
     */
    fun webDaten(speicher: Speicher, heute: java.time.LocalDate, netz: Boolean, jetzt: Long): WebDaten {
        if (netz) {
            speicher.netzVersuch = jetzt
            try {
                val kennung = speicher.kennung
                if (!speicher.angemeldet && Supabase.anmelden(kennung)) speicher.angemeldet = true
                val zeilen = Supabase.lesen(kennung, heute.minusDays(7), heute)
                speicher.zwischenspeichern(jetzt, zeilen)
                speicher.netzFehlt = false
                return webDaten(zeilen, tagText(heute), ohneNetz = false, gelesen = jetzt)
            } catch (e: IOException) {
                Log.i(LOG, "Supabase nicht erreicht: ${e.javaClass.simpleName}")
            } catch (e: JSONException) {
                Log.i(LOG, "Supabase-Antwort unlesbar")
            }
            speicher.netzFehlt = true
        }
        val ohneNetz = netz || speicher.netzFehlt
        val alt = speicher.zwischengespeichert() ?: return WebDaten.LEER.copy(ohneNetz = ohneNetz)
        return webDaten(alt.second, tagText(heute), ohneNetz = ohneNetz, gelesen = alt.first)
    }
}

const val LOG = "Bildschirmzeit"
