package de.finanzdienste.bildschirmzeit

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/*
 * Der eigene Tagesverlauf der App – nur auf dem Handy. Android hebt seine
 * Nutzungsereignisse nur rund zehn Tage auf (AOSP UsageStatsDatabase.prune:
 * Tagesdateien älter als 10 Tage fallen weg; queryEvents liest nur diese
 * Tagesdateien), Supabase die Minuten der Web-Apps 62 Tage. Für Woche und
 * Monat merkt sich die App deshalb jeden Tag selbst: Sekunden je App nach dem
 * Abzug der Web-Apps von Chrome, mit Namen (so bleiben auch deinstallierte
 * Apps lesbar). Getestet in VerlaufTest.
 */

/** Ein Kalendertag im Verlauf. */
data class Tag(
    val datum: LocalDate,
    /** Sekunden je App nach dem Abzug der Web-Apps von Chrome, absteigend (wie im Widget). */
    val posten: List<Posten>,
    /** Was die Web-Apps für den Tag gemeldet hatten – vor dem Kürzen auf Chromes Zeit. */
    val web: Map<String, Long> = emptyMap(),
    /** Wann diese Web-Werte von Supabase gelesen wurden (ms); 0 = noch nie, dann fehlen sie noch. */
    val webGelesen: Long = 0L,
    /** Androids Teil ist nach dem Ende des Tages ganz gerechnet; sonst der Stand der letzten Rechnung an dem Tag. */
    val fertig: Boolean = false,
) {
    val gesamt: Long get() = posten.sumOf { it.sekunden }

    /**
     * Derselbe Tag mit neuen Werten der Web-Apps (sie melden auch verspätet).
     * Chromes ganze Zeit steckt in „Chrome“ und den Web-Apps zusammen
     * (aufteilen zieht nur ab), also lässt sie sich neu aufteilen; die
     * Gesamtzeit bleibt die von Android.
     */
    fun mitWeb(neu: Map<String, Long>, gelesen: Long): Tag {
        val namen = posten.associate { it.schluessel to it.name }
        // Die Seiten bleiben, wie sie sind: Firefox' Zeit ändert sich hier nicht.
        val seiten = posten.filter { seiteVon(it.schluessel) != null }.associate { it.schluessel to it.sekunden }
        val geteilt = seitenAbziehen(aufteilen(androidTeil(), neu), seiten, mindestens = 0L)
        return copy(
            posten = sortiert(geteilt.map { (k, s) -> Posten(k, anzeigename(k) ?: namen[k] ?: if (k == CHROME) "Chrome" else k, s) }),
            web = neu.filterValues { it > 0 },
            webGelesen = gelesen,
        )
    }

    /**
     * Androids Teil des Tages vor dem Aufteilen: Sekunden je Paket, Chrome
     * wieder samt den Web-Apps, jeder Browser wieder samt seinen Seiten.
     */
    fun androidTeil(): Map<String, Long> {
        val android = LinkedHashMap<String, Long>()
        var chrome = 0L
        for (p in posten) {
            val seite = seiteVon(p.schluessel)
            when {
                p.webApp != null || p.schluessel == CHROME -> chrome += p.sekunden
                seite != null -> android[seite.first] = (android[seite.first] ?: 0L) + p.sekunden
                else -> android[p.schluessel] = (android[p.schluessel] ?: 0L) + p.sekunden
            }
        }
        if (chrome > 0) android[CHROME] = chrome
        return android
    }
}

/** Der Name eines Schlüssels, der keinen vom System braucht: Web-App, Seite (die Domain) oder „Sonstige Webseiten“. */
fun anzeigename(schluessel: String): String? = WebApp.von(schluessel)?.anzeigename ?: seiteVon(schluessel)?.second?.let { if (it == OHNE_DOMAIN) SONSTIGE_NAME else it } ?: when {
    schluessel == SONSTIGE_WEBSEITEN -> SONSTIGE_NAME
    schluessel.startsWith(WEBSEITE) -> schluessel.removePrefix(WEBSEITE)
    else -> null
}

/**
 * Ein Tag aus Androids Rechnung: `android` sind Sekunden je Paket (wie
 * heuteJeApp sie liefert), `web` die Meldungen der Web-Apps für den Tag,
 * `seiten` die Sekunden je Seite und Browser (seitenZeiten). Installierte
 * Web-Apps zählen zu Chrome, Chrome wird um Tobis Web-Apps gekürzt, dann jeder
 * Browser um seine Seiten.
 */
fun tagAus(
    datum: LocalDate,
    android: Map<String, Long>,
    web: Map<String, Long>,
    webGelesen: Long,
    fertig: Boolean,
    seiten: Map<String, Long> = emptyMap(),
    name: (String) -> String,
): Tag {
    val geteilt = seitenAbziehen(aufteilen(webApksZuChrome(android), web), seiten)
    val posten = geteilt.map { (k, s) -> Posten(k, anzeigename(k) ?: name(k), s) }
    return Tag(datum, sortiert(posten), web.filterValues { it > 0 }, webGelesen, fertig)
}

/** [Mitternacht, nächste Mitternacht) in ms – Sommerzeittage haben 23 oder 25 Stunden. */
fun tagesgrenzen(datum: LocalDate, zone: ZoneId): Pair<Long, Long> =
    datum.atStartOfDay(zone).toInstant().toEpochMilli() to datum.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Deckt Android einen vergangenen Tag ganz ab? Er braucht Ereignisse an dem
 * Tag (`letztes` ≥ Mitternacht) und einen Anfang: Ereignisse schon vor
 * Mitternacht (die Abfrage beginnt 8 Stunden davor), oder Android hat den
 * Vortag bzw. den Tag selbst sicher noch (`vortagBekannt`, `tagBekannt`: eben
 * selbst hergegeben, oder im Verlauf und jünger als Androids Aufbewahrung –
 * Rechner.nachtragen). Sonst ist es der älteste Tag, den Android noch hat –
 * dessen Tagesdatei beginnt nicht um Mitternacht, er wäre zu kurz. Dann
 * lieber „keine Daten“ als eine falsche Zahl.
 */
fun tagGedeckt(erstes: Long?, letztes: Long?, mitternacht: Long, vortagBekannt: Boolean, tagBekannt: Boolean): Boolean {
    if (letztes == null || letztes < mitternacht) return false
    return (erstes != null && erstes < mitternacht) || vortagBekannt || tagBekannt
}

/** Was die Web-Apps für `tag` gemeldet haben (je App der größte Wert). */
fun webFuerTag(zeilen: List<Zeile>, tag: LocalDate): Map<String, Long> {
    val text = tagText(tag)
    val aus = HashMap<String, Long>()
    for (z in zeilen) if (z.tag == text && z.sekunden > 0) aus[z.app] = maxOf(aus[z.app] ?: 0L, z.sekunden)
    return aus
}

/** Die Tage, die Android noch nachträglich fertig rechnen soll: nach `bis` bis gestern, höchstens `rueckwirkend` zurück. */
fun nachzutragen(heute: LocalDate, bis: LocalDate?, rueckwirkend: Long = Verlauf.RUECKWIRKEND_TAGE): List<LocalDate> {
    val gestern = heute.minusDays(1)
    val fruehestens = heute.minusDays(rueckwirkend)
    var tag = if (bis == null) fruehestens else maxOf(bis.plusDays(1), fruehestens)
    val aus = ArrayList<LocalDate>()
    while (tag <= gestern) {
        aus += tag
        tag = tag.plusDays(1)
    }
    return aus
}

/**
 * Ab welchem Tag die Web-Apps zu lesen sind: sonst die letzten acht Tage, nach
 * dem Nachtragen einmal so weit zurück, wie Tage ohne Web-Werte im Verlauf
 * stehen – in derselben einen Anfrage, höchstens die 62 Tage von Supabase.
 */
fun webVon(heute: LocalDate, webFehltAb: LocalDate?): LocalDate {
    val sonst = heute.minusDays(Verlauf.WEB_TAGE - 1)
    val ab = if (webFehltAb != null && webFehltAb < sonst) webFehltAb else sonst
    return maxOf(ab, heute.minusDays(Verlauf.SUPABASE_TAGE))
}

/**
 * Der Speicher des Verlaufs: je Tag eine kleine Datei („2026-11-15.json“) in
 * noBackupFilesDir/verlauf – nie in der Android-Sicherung, nie beim Umzug aufs
 * neue Handy (wie die Kennung, datenregeln.xml). Geschrieben wird über eine
 * Zwischendatei und Umbenennen, also liest die App nie eine halbe Datei, und
 * nur, wenn sich etwas geändert hat. Dazu stand.json: bis wann Androids
 * Ereignisse nachgetragen sind und ab wann Web-Werte fehlen.
 */
class Verlauf(val ordner: File) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "verlauf"))

    companion object {
        /** So lange bleibt ein Tag im Verlauf. */
        const val AUFBEWAHREN_TAGE = 400L

        /** Am ersten Start so weit zurück, wie Android Ereignisse hat (AOSP: rund 10 Tage) – Samsung kann mehr haben. */
        const val RUECKWIRKEND_TAGE = 62L

        /**
         * So weit zurück hat Android einen Tag sicher noch ganz: AOSP räumt beim
         * Tageswechsel die Tagesdateien weg, die älter als 10 Tage sind
         * (UsageStatsDatabase.prune). Was im Verlauf steht und älter ist, kann
         * Android inzwischen angeschnitten haben – dann zählt es nicht als Beweis.
         */
        const val ANDROID_SICHER_TAGE = 9L

        /** Die gewöhnliche Abfrage der Web-Apps: heute und die sieben Tage davor. */
        const val WEB_TAGE = 8L

        /** So viele Tage hält Supabase (bildschirmzeit_melden löscht ältere, bildschirmzeit_lesen nimmt höchstens 62). */
        const val SUPABASE_TAGE = 62L

        private const val STAND = "stand.json"
        private val NAME = Regex("^\\d{4}-\\d{2}-\\d{2}\\.json$")
    }

    private fun datei(tag: LocalDate) = File(ordner, "${tagText(tag)}.json")

    fun lesen(tag: LocalDate): Tag? {
        val f = datei(tag)
        if (!f.isFile) return null
        return try {
            tagAusJson(tag, JSONObject(f.readText()))
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    /** Alle Tage von `von` bis `bis`, die im Verlauf stehen. */
    fun lesen(von: LocalDate, bis: LocalDate): Map<LocalDate, Tag> {
        val aus = LinkedHashMap<LocalDate, Tag>()
        var tag = von
        while (tag <= bis) {
            lesen(tag)?.let { aus[tag] = it }
            tag = tag.plusDays(1)
        }
        return aus
    }

    /** Schreibt den Tag, wenn er anders ist als gespeichert. true: geschrieben. */
    fun schreiben(tag: Tag): Boolean {
        val text = tagAlsJson(tag).toString()
        val f = datei(tag.datum)
        if (f.isFile && runCatching { f.readText() }.getOrNull() == text) return false
        return dateiSchreiben(f, text)
    }

    /** Der früheste Tag im Verlauf („Daten seit“) oder null. */
    fun erster(): LocalDate? = tage().minOrNull()

    /** Alle Tage, die eine Datei haben. */
    fun tage(): List<LocalDate> = ordner.list().orEmpty().filter { NAME.matches(it) }.mapNotNull {
        try {
            LocalDate.parse(it.removeSuffix(".json"))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Tage älter als AUFBEWAHREN_TAGE weg. */
    fun aufraeumen(heute: LocalDate) {
        val grenze = heute.minusDays(AUFBEWAHREN_TAGE)
        for (tag in tage()) if (tag < grenze) datei(tag).delete()
    }

    /** Bis zu welchem Tag Androids Ereignisse schon nachgetragen sind (null: noch nie – dann rückwirkend). */
    var nachgetragenBis: LocalDate?
        get() = standLesen().optString("nachgetragenBis").let(::datumOderNull)
        set(wert) = standSetzen("nachgetragenBis", wert)

    /**
     * Ab welchem Tag Tage im Verlauf stehen, deren Web-Werte fehlen oder noch
     * vor dem Ende des Tages gelesen wurden (die nächste Netzabfrage liest so
     * weit zurück, falls das älter ist als ihre gewöhnlichen acht Tage).
     */
    var webFehltAb: LocalDate?
        get() = standLesen().optString("webFehltAb").let(::datumOderNull)
        set(wert) = standSetzen("webFehltAb", wert)

    /** Wann das Nachtragen zuletzt Tage nicht bekam (ms, 0 = nie) – Rechner.nachtragen versucht es dann erst eine Stunde später wieder. */
    var nachtragVersuch: Long
        get() = standLesen().optLong("nachtragVersuch", 0L)
        set(wert) {
            val o = standLesen()
            if (wert <= 0L) o.remove("nachtragVersuch") else o.put("nachtragVersuch", wert)
            dateiSchreiben(File(ordner, STAND), o.toString())
        }

    /**
     * „Alle Daten löschen“: der ganze Verlauf weg. Danach beginnt er neu mit
     * heute – nicht gleich wieder rückwirkend aus Androids Ereignissen, sonst
     * stünde nach dem Löschen alles wieder da.
     */
    fun loeschen(heute: LocalDate) {
        ordner.listFiles()?.forEach { it.delete() }
        nachgetragenBis = heute.minusDays(1)
    }

    /**
     * Neue Meldungen der Web-Apps (`zeilen`, gelesen für `von` … `heute` um
     * `gelesen`) in die vergangenen Tage ab `von` eintragen, die schon im
     * Verlauf stehen. Geschrieben wird nur, was sich ändert. Danach fehlt
     * nichts mehr, was Supabase noch hat – Älteres als 62 Tage hat es nicht
     * mehr, das wird nicht weiter gesucht.
     */
    fun webEintragen(zeilen: List<Zeile>, von: LocalDate, heute: LocalDate, gelesen: Long) {
        if (gelesen <= 0L) return
        var tag = von
        while (tag < heute) {
            val alt = lesen(tag)
            if (alt != null && alt.webGelesen < gelesen) {
                val neu = webFuerTag(zeilen, tag)
                if (alt.webGelesen == 0L || neu != alt.web) schreiben(alt.mitWeb(neu, gelesen))
            }
            tag = tag.plusDays(1)
        }
        val fehlt = webFehltAb
        if (fehlt != null && (von <= fehlt || fehlt < heute.minusDays(SUPABASE_TAGE))) webFehltAb = null
    }

    private fun standLesen(): JSONObject = try {
        val f = File(ordner, STAND)
        if (f.isFile) JSONObject(f.readText()) else JSONObject()
    } catch (e: IOException) {
        JSONObject()
    } catch (e: JSONException) {
        JSONObject()
    }

    private fun standSetzen(schluessel: String, wert: LocalDate?) {
        val o = standLesen()
        if (wert == null) o.remove(schluessel) else o.put(schluessel, tagText(wert))
        dateiSchreiben(File(ordner, STAND), o.toString())
    }

    private fun dateiSchreiben(f: File, text: String): Boolean {
        ordner.mkdirs()
        val neu = File(ordner, f.name + ".neu")
        return try {
            neu.writeText(text)
            neu.renameTo(f) || (f.delete() && neu.renameTo(f))
        } catch (e: IOException) {
            false
        }
    }
}

private fun datumOderNull(text: String): LocalDate? = try {
    if (text.isEmpty()) null else LocalDate.parse(text)
} catch (e: DateTimeParseException) {
    null
}

fun tagAlsJson(tag: Tag): JSONObject {
    val apps = JSONArray()
    for (p in tag.posten) apps.put(JSONObject().put("k", p.schluessel).put("n", p.name).put("s", p.sekunden))
    val web = JSONObject()
    for ((k, s) in tag.web.toSortedMap()) web.put(k, s)
    return JSONObject().put("v", 1).put("fertig", tag.fertig).put("webGelesen", tag.webGelesen).put("web", web).put("apps", apps)
}

fun tagAusJson(datum: LocalDate, o: JSONObject): Tag {
    val apps = o.optJSONArray("apps") ?: JSONArray()
    val posten = (0 until apps.length()).map { i ->
        val a = apps.getJSONObject(i)
        Posten(a.getString("k"), a.optString("n", a.getString("k")), maxOf(0L, a.getLong("s")))
    }
    val w = o.optJSONObject("web") ?: JSONObject()
    val web = w.keys().asSequence().associateWith { w.getLong(it) }
    return Tag(datum, sortiert(posten), web, o.optLong("webGelesen"), o.optBoolean("fertig"))
}
