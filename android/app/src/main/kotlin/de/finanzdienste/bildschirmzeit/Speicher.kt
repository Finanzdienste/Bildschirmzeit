package de.finanzdienste.bildschirmzeit

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * SharedPreferences der App. Die Kennung ist geheim: Wer sie kennt, kann
 * die Minuten lesen und löschen. Sie steht nur hier und im Verbinden-Link –
 * nie im Log.
 */
class Speicher(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("bildschirmzeit", Context.MODE_PRIVATE)

    /** Die Kennung dieses Geräts; beim ersten Mal erzeugt. */
    val kennung: String
        get() = synchronized(SPERRE) {
            p.getString(KENNUNG, null)?.takeIf { KENNUNG_REGEL.matches(it) } ?: neueKennungSetzen()
        }

    /**
     * Eine neue Kennung (nach „Alle Daten löschen“); muss erst noch angemeldet
     * werden. commit statt apply: Gleich danach liest ein anderer Faden sie.
     */
    @SuppressLint("ApplySharedPref")
    fun neueKennungSetzen(): String = synchronized(SPERRE) {
        val k = neueKennung()
        p.edit(commit = true) { putString(KENNUNG, k).putBoolean(ANGEMELDET, false).remove(ZWISCHENSPEICHER) }
        k
    }

    /** Hat Supabase die Kennung bestätigt (bildschirmzeit_anmelden)? */
    var angemeldet: Boolean
        get() = p.getBoolean(ANGEMELDET, false)
        set(wert) {
            p.edit { putBoolean(ANGEMELDET, wert) }
        }

    /** Wann das Widget zuletzt gerechnet wurde (ms) – für den Takt (Takt.kt). */
    var gerechnet: Long
        get() = p.getLong(GERECHNET, 0L)
        set(wert) {
            p.edit { putLong(GERECHNET, wert) }
        }

    /** Wann zuletzt Supabase gefragt wurde, ob mit Erfolg oder ohne (ms) – höchstens alle 5 Minuten. */
    var netzVersuch: Long
        get() = p.getLong(NETZ_VERSUCH, 0L)
        set(wert) {
            p.edit { putLong(NETZ_VERSUCH, wert) }
        }

    /**
     * Ging der letzte Netzversuch schief? Dann ist der Zwischenspeicher alt,
     * auch wenn zwischen zwei Versuchen ohne Netz gerechnet wird – das Widget
     * schreibt weiter „· ohne Netz“, bis Supabase wieder antwortet.
     */
    var netzFehlt: Boolean
        get() = p.getBoolean(NETZ_FEHLT, false)
        set(wert) {
            p.edit { putBoolean(NETZ_FEHLT, wert) }
        }

    /** Was zuletzt von Supabase kam – für die Zeit ohne Netz. `von`: der erste gelesene Tag. */
    fun zwischenspeichern(gelesen: Long, zeilen: List<Zeile>, von: LocalDate? = null) {
        val o = JSONObject().put("gelesen", gelesen).put("zeilen", zeilenAlsJson(zeilen))
        if (von != null) o.put("von", tagText(von))
        p.edit { putString(ZWISCHENSPEICHER, o.toString()) }
    }

    /** Ab welchem Tag der Zwischenspeicher gelesen ist; ohne Angabe (Fassung 1.6) die acht Tage bis zum Lesen. */
    fun zwischenspeicherVon(zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val text = p.getString(ZWISCHENSPEICHER, null) ?: return null
        return try {
            val o = JSONObject(text)
            val von = o.optString("von")
            if (von.isNotEmpty()) {
                LocalDate.parse(von)
            } else {
                Instant.ofEpochMilli(o.getLong("gelesen")).atZone(zone).toLocalDate().minusDays(Verlauf.WEB_TAGE - 1)
            }
        } catch (e: JSONException) {
            null
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Der zuletzt gewählte Reiter der Statistik (Tag, Woche, Monat); beim ersten Mal null. */
    var reiter: String?
        get() = p.getString(REITER, null)
        set(wert) {
            p.edit { putString(REITER, wert) }
        }

    /** Mit welchem Stand der Erotik-Erkennung das Gespeicherte zuletzt umgeschrieben wurde (Diskret.kt); 0 = noch nie. */
    var erotikStand: Int
        get() = p.getInt(EROTIK_STAND, 0)
        set(wert) {
            p.edit { putInt(EROTIK_STAND, wert) }
        }

    /** (gelesen, zeilen) oder null. */
    fun zwischengespeichert(): Pair<Long, List<Zeile>>? {
        val text = p.getString(ZWISCHENSPEICHER, null) ?: return null
        return try {
            val o = JSONObject(text)
            o.getLong("gelesen") to zeilenAusJson(o.optJSONArray("zeilen") ?: JSONArray())
        } catch (e: JSONException) {
            null
        }
    }

    private companion object {
        const val KENNUNG = "kennung"
        const val ANGEMELDET = "angemeldet"
        const val ZWISCHENSPEICHER = "web"
        const val GERECHNET = "gerechnet"
        const val NETZ_VERSUCH = "netz_versuch"
        const val NETZ_FEHLT = "netz_fehlt"
        const val REITER = "reiter"
        const val EROTIK_STAND = "erotikStand"
        val SPERRE = Any()
    }
}
