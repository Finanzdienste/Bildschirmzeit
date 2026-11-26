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

    /** Darf die App die Anrufliste an Supabase melden (Anrufe.kt)? Tobi schaltet es in der App an und aus. */
    var anrufeTeilen: Boolean
        get() = p.getBoolean(ANRUFE_TEILEN, false)
        set(wert) {
            p.edit { putBoolean(ANRUFE_TEILEN, wert) }
        }

    /** Wann die Anrufliste zuletzt erfolgreich gemeldet wurde (ms); 0: noch nie. */
    var anrufeGemeldet: Long
        get() = p.getLong(ANRUFE_GEMELDET, 0L)
        set(wert) {
            p.edit { putLong(ANRUFE_GEMELDET, wert) }
        }

    /** „Nicht mehr teilen“ ohne Netz: Das Löschen bei Supabase steht noch aus und läuft beim nächsten Lauf. */
    var anrufeLoeschenOffen: Boolean
        get() = p.getBoolean(ANRUFE_LOESCHEN, false)
        set(wert) {
            p.edit { putBoolean(ANRUFE_LOESCHEN, wert) }
        }

    /** Darf die App Kontakte für die Nummern aus den Bewerbungen anlegen und umbenennen (Kontakte.kt)? */
    var kontakteBenennen: Boolean
        get() = p.getBoolean(KONTAKTE_BENENNEN, false)
        set(wert) {
            p.edit { putBoolean(KONTAKTE_BENENNEN, wert) }
        }

    /** Wann die Namen zuletzt bei Supabase geholt wurden (ms); 0: noch nie. */
    var kontakteGeholt: Long
        get() = p.getLong(KONTAKTE_GEHOLT, 0L)
        set(wert) {
            p.edit { putLong(KONTAKTE_GEHOLT, wert) }
        }

    /** Die zuletzt geholte Liste (Kontakte.alsJson) – für die Zeit ohne Netz. */
    var kontakteListe: String?
        get() = p.getString(KONTAKTE_LISTE, null)
        set(wert) {
            p.edit { putString(KONTAKTE_LISTE, wert) }
        }

    /** Die Liste, die zuletzt angewandt wurde: Angewandt wird nur, was sich geändert hat. */
    var kontakteAngewandt: String?
        get() = p.getString(KONTAKTE_ANGEWANDT, null)
        set(wert) {
            p.edit { putString(KONTAKTE_ANGEWANDT, wert) }
        }

    /** Die Kontakte, die die App angelegt hat: RawContact-Id → Nummer. */
    var kontakteAngelegt: Map<Long, String>
        get() = jsonMap(KONTAKTE_ANGELEGT) { o, k -> o.optString(k) }
        set(wert) {
            p.edit { putString(KONTAKTE_ANGELEGT, JSONObject().also { o -> wert.forEach { (k, v) -> o.put(k.toString(), v) } }.toString()) }
        }

    /** Was beim letzten Benennen schiefging (Android lehnt das Anlegen ab), für die Karte; null, wenn alles ging. */
    var kontakteFehler: String?
        get() = p.getString(KONTAKTE_FEHLER, null)
        set(wert) {
            p.edit { if (wert == null) remove(KONTAKTE_FEHLER) else putString(KONTAKTE_FEHLER, wert) }
        }

    /** Die Kontakte, die die App umbenannt hat: RawContact-Id → alter Name und Nummer. */
    var kontakteUmbenannt: Map<Long, Umbenannt>
        get() = jsonMap(KONTAKTE_UMBENANNT) { o, k -> o.optJSONObject(k)?.let { Umbenannt(it.optString("alt"), it.optString("nummer")) } }
        set(wert) {
            p.edit {
                putString(
                    KONTAKTE_UMBENANNT,
                    JSONObject().also { o -> wert.forEach { (k, v) -> o.put(k.toString(), JSONObject().put("alt", v.alt).put("nummer", v.nummer)) } }.toString(),
                )
            }
        }

    /** Platzhalter „Job Neu …“, die die App gelöscht hat (Kontakte.kt), damit „Nicht mehr benennen“ sie zurückholt; höchstens 200. */
    var kontakteGeloescht: List<Geloescht>
        get() = try {
            val a = JSONArray(p.getString(KONTAKTE_GELOESCHT, null) ?: return emptyList())
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optJSONArray("nummern") ?: JSONArray()
                Geloescht(o.optString("name"), (0 until n.length()).map { n.optString(it) }.filter { it.isNotEmpty() })
            }
        } catch (e: JSONException) {
            emptyList()
        }
        set(wert) {
            p.edit {
                if (wert.isEmpty()) remove(KONTAKTE_GELOESCHT)
                else putString(KONTAKTE_GELOESCHT, JSONArray().also { a -> wert.takeLast(200).forEach { g -> a.put(JSONObject().put("name", g.name).put("nummern", JSONArray(g.nummern))) } }.toString())
            }
        }

    /** Briefing beim Anruf und Nachschlagen unbekannter Nummern (Anrufer.kt)? Tobi schaltet es in der App an und aus. */
    var anruferErkennen: Boolean
        get() = p.getBoolean(ANRUFER_ERKENNEN, false)
        set(wert) {
            p.edit { putBoolean(ANRUFER_ERKENNEN, wert) }
        }

    /** Tobis Claude-Schlüssel aus der Bewerbungszentrale – geheim wie die Kennung: nie ins Log. */
    var kiSchluessel: String?
        get() = p.getString(KI_SCHLUESSEL, null)?.takeIf { KI.gueltig(it) }
        set(wert) {
            p.edit { if (wert == null) remove(KI_SCHLUESSEL) else putString(KI_SCHLUESSEL, wert) }
        }

    /** Wann Tobi in der App „Claude-Schlüssel holen“ tippte (ms): Nur kurz danach nimmt die App einen Schlüssel an. */
    var kiAngefragt: Long
        get() = p.getLong(KI_ANGEFRAGT, 0L)
        set(wert) {
            p.edit { putLong(KI_ANGEFRAGT, wert) }
        }

    /** Bis wann (ms) das Nachschlagen ruht, nachdem Claude den Schlüssel oder die Anfrage abgelehnt hat (Anrufer.PAUSE_MS). */
    var kiPause: Long
        get() = p.getLong(KI_PAUSE, 0L)
        set(wert) {
            p.edit { putLong(KI_PAUSE, wert) }
        }

    /** Warum es ruht (KI.grund), für Karte und Mitteilung; null, wenn alles geht. */
    var kiFehler: String?
        get() = p.getString(KI_FEHLER, null)
        set(wert) {
            p.edit { if (wert == null) remove(KI_FEHLER) else putString(KI_FEHLER, wert) }
        }

    /** Was zu Nummern nachgeschlagen wurde: Vergleichsnummer (Kontakte.vergleich) → Fund. */
    var nachgeschlagen: Map<String, Fund>
        get() = textMap(NACHGESCHLAGEN) { o -> Fund.aus(o) }
        set(wert) {
            p.edit { putString(NACHGESCHLAGEN, JSONObject().also { o -> wert.forEach { (k, v) -> o.put(k, v.alsJson()) } }.toString()) }
        }

    /** Vorgemerkte Nummern (+49…) → wann sie anriefen: Nachschlagen geht nur mit Netz und läuft, sobald es da ist. */
    var nachschlagOffen: Map<String, Long>
        get() = textMap(NACHSCHLAG_OFFEN) { o -> o.optLong("zeit").takeIf { it > 0 } }
        set(wert) {
            p.edit { putString(NACHSCHLAG_OFFEN, JSONObject().also { o -> wert.forEach { (k, v) -> o.put(k, JSONObject().put("zeit", v)) } }.toString()) }
        }

    /** „Tag|Anzahl“: wie oft heute schon nachgeschlagen wurde (höchstens Anrufer.JE_TAG). */
    var nachschlagHeute: String?
        get() = p.getString(NACHSCHLAG_HEUTE, null)
        set(wert) {
            p.edit { putString(NACHSCHLAG_HEUTE, wert) }
        }

    /** „Nummer|ms“ des letzten Klingelns: Android meldet es oft zweimal. */
    var zuletztGeklingelt: String?
        get() = p.getString(ZULETZT_GEKLINGELT, null)
        set(wert) {
            p.edit { putString(ZULETZT_GEKLINGELT, wert) }
        }

    private fun <T> textMap(schluessel: String, wert: (JSONObject) -> T?): Map<String, T> {
        val o = try {
            JSONObject(p.getString(schluessel, null) ?: return emptyMap())
        } catch (e: JSONException) {
            return emptyMap()
        }
        val aus = LinkedHashMap<String, T>()
        for (k in o.keys()) aus[k] = o.optJSONObject(k)?.let(wert) ?: continue
        return aus
    }

    private fun <T> jsonMap(schluessel: String, wert: (JSONObject, String) -> T?): Map<Long, T> {
        val o = try {
            JSONObject(p.getString(schluessel, null) ?: return emptyMap())
        } catch (e: JSONException) {
            return emptyMap()
        }
        val aus = LinkedHashMap<Long, T>()
        for (k in o.keys()) {
            val id = k.toLongOrNull() ?: continue
            aus[id] = wert(o, k) ?: continue
        }
        return aus
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
        const val ANRUFE_TEILEN = "anrufe_teilen"
        const val ANRUFE_GEMELDET = "anrufe_gemeldet"
        const val ANRUFE_LOESCHEN = "anrufe_loeschen"
        const val KONTAKTE_BENENNEN = "kontakte_benennen"
        const val KONTAKTE_GEHOLT = "kontakte_geholt"
        const val KONTAKTE_LISTE = "kontakte_liste"
        const val KONTAKTE_ANGEWANDT = "kontakte_angewandt"
        const val KONTAKTE_ANGELEGT = "kontakte_angelegt"
        const val KONTAKTE_UMBENANNT = "kontakte_umbenannt"
        const val KONTAKTE_FEHLER = "kontakte_fehler"
        const val KONTAKTE_GELOESCHT = "kontakte_geloescht"
        const val ANRUFER_ERKENNEN = "anrufer_erkennen"
        const val KI_SCHLUESSEL = "ki_schluessel"
        const val KI_ANGEFRAGT = "ki_angefragt"
        const val KI_PAUSE = "ki_pause"
        const val KI_FEHLER = "ki_fehler"
        const val NACHGESCHLAGEN = "nachgeschlagen"
        const val NACHSCHLAG_OFFEN = "nachschlag_offen"
        const val NACHSCHLAG_HEUTE = "nachschlag_heute"
        const val ZULETZT_GEKLINGELT = "zuletzt_geklingelt"
        val SPERRE = Any()
    }
}
