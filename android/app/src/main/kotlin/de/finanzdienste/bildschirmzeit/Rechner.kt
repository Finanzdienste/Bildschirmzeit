package de.finanzdienste.bildschirmzeit

import android.content.Context
import android.util.Log
import org.json.JSONException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
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
    /** Nur fürs Widget: Es ist Samsungs Automatische Sperre – „Update blockiert“ (UpdateRegeln.widgetGesperrt). */
    val updateGesperrt: Boolean = false,
) {
    val gesamt: Long get() = posten.sumOf { it.sekunden }

    /** Leise Kennzeichnung im Widget: Die Web-Apps kommen aus dem Zwischenspeicher. */
    val ohneNetz: Boolean get() = web.ohneNetz && web.verbunden
}

object Rechner {
    private const val VORLAUF_MS = 8 * 60 * 60 * 1000L

    /** Gab Android vergangene Tage nicht her: so lange bis zum nächsten Versuch. */
    const val NOCHMAL_MS = 60 * 60 * 1000L

    /** bildschirmzeit_lesen – nur Tests ersetzen es (sonst gilt Netz.gesperrt: kein echtes Supabase). */
    @Volatile
    internal var webLesen: (String, LocalDate, LocalDate) -> List<Zeile> = Supabase::lesen

    /**
     * Rechnet heute (ab lokaler Mitternacht bis jetzt). `netz`: Supabase
     * fragen – sonst (und wenn das nicht klappt) die zuletzt gelesenen Werte.
     *
     * Nebenbei der Verlauf (Verlauf.kt): heute bei jeder Rechnung mit dem, was
     * ohnehin gerechnet ist; vergangene Tage nur, wenn sie fehlen – nach
     * Mitternacht einmal der Vortag, am ersten Start rückwirkend, so weit
     * Android Ereignisse hat. Der Wecker liest also sonst nur heute.
     *
     * Was ein Tag im Verlauf schon hatte, geht dabei nie verloren (nieWeniger):
     * Eine App, die Tobi deinstalliert, kennt Android danach nicht mehr – ihre
     * Zeit von heute bleibt trotzdem stehen, mit ihrem Namen.
     */
    fun berechnen(context: Context, netz: Boolean, jetzt: Long = System.currentTimeMillis()): Ergebnis {
        val zone = ZoneId.systemDefault()
        val heute = Instant.ofEpochMilli(jetzt).atZone(zone).toLocalDate()
        val mitternacht = tagesgrenzen(heute, zone).first
        val speicher = Speicher(context)
        if (!Nutzungsdaten.erlaubt(context)) return ohneErlaubnis(speicher, heute, netz, jetzt)

        val pm = context.packageManager
        val launcher = Nutzungsdaten.launcherPakete(pm)
        val home = Nutzungsdaten.homeActivities(pm)
        val namen = HashMap<String, String>()
        val name = { k: String -> namen.getOrPut(k) { Nutzungsdaten.name(pm, k) } }
        val verlauf = Verlauf(context)
        val bisher = verlauf.lesen(heute)
        val android: Map<String, Long>
        val seiten: Map<String, Long>
        try {
            // Ein paar Stunden vor Mitternacht anfangen: Eine App, die über
            // Mitternacht offen war, hat ihr „vorn“ vor Mitternacht bekommen.
            val ereignisse = Nutzungsdaten.ereignisse(context, mitternacht - VORLAUF_MS, jetzt)
            val browser = ArrayList<Intervall>()
            val roh = heuteJeApp(ereignisse, mitternacht, jetzt, launcher + bisher.schluessel(), home) { p, a, e ->
                if (p in BROWSER) browser += Intervall(p, a, e)
            }
            android = nieWeniger(roh, bisher)
            val beobachtungen = Beobachtungen(context)
            val liste = beobachtungen.lesen(mitternacht, jetzt)
            seiten = seitenZeiten(browser, liste)
            // Für Karte 4: wie die Browserzeit von heute aufgeteilt war (nur Sekunden).
            beobachtungen.aufschluesselungMerken(heute, browserZeiten(browser, liste))
            nachtragen(context, verlauf, speicher, heute, zone, jetzt, launcher, home, name)
        } catch (e: SecurityException) {
            // Gerade eben in den Einstellungen wieder abgeschaltet.
            return ohneErlaubnis(speicher, heute, netz, jetzt)
        }
        val web = webStand(speicher, heute, netz, jetzt, if (netz) webVon(heute, verlauf.webFehltAb) else heute)
        if (web.frisch) verlauf.webEintragen(web.zeilen, web.von, heute, web.daten.gelesen)
        val tag = tagAus(heute, android, web.daten.heute, web.daten.gelesen, fertig = false, seiten = seiten, name = mitAltenNamen(bisher, name))
        verlauf.schreiben(tag)
        // Gezeigt werden die Seiten zusammengefasst (beide Browser, ab 5 Minuten einzeln); im Verlauf je Browser.
        return Ergebnis(erlaubt = true, posten = webseitenZusammenfassen(tag.posten), stand = jetzt, web = web.daten)
    }

    private fun ohneErlaubnis(speicher: Speicher, heute: LocalDate, netz: Boolean, jetzt: Long) = Ergebnis(
        erlaubt = false,
        posten = emptyList(),
        stand = jetzt,
        web = webStand(speicher, heute, netz, jetzt, heute.minusDays(Verlauf.WEB_TAGE - 1)).daten,
    )

    /**
     * Vergangene Tage, die noch fehlen, aus Androids Ereignissen fertig
     * rechnen (je Tag eine Abfrage ab 8 Stunden vor Mitternacht), mit den
     * Web-Werten, die der Tag schon hat oder der Zwischenspeicher kennt.
     * Fehlen sie oder wurden sie vor dem Ende des Tages gelesen, liest die
     * nächste Netzabfrage einmal so weit zurück (Verlauf.webFehltAb). Tage, die
     * Android nicht (ganz) hat, bleiben leer: „keine Daten“ – oder, stehen sie
     * schon im Verlauf, wie sie sind (je App höchstens mehr).
     *
     * Vorgerückt wird nur bis zum letzten Tag, den Android ganz hergab, und
     * zwar nach jedem Tag (bricht Android den ersten langen Lauf ab, geht es
     * dort weiter). Gab Android die letzten Tage nicht her (eine leere Antwort
     * kurz nach dem Neustart, oder das Handy war aus), versucht es die
     * Rechnung höchstens einmal je Stunde noch einmal – der Wecker bleibt
     * billig. Älter als Androids Aufbewahrung wird nichts mehr versucht.
     */
    private fun nachtragen(
        context: Context,
        verlauf: Verlauf,
        speicher: Speicher,
        heute: LocalDate,
        zone: ZoneId,
        jetzt: Long,
        launcher: Set<String>,
        home: Set<String>,
        name: (String) -> String,
    ) {
        val tage = nachzutragen(heute, verlauf.nachgetragenBis)
        if (tage.isEmpty()) return
        val versuch = verlauf.nachtragVersuch
        if (versuch > 0L && jetzt - versuch in 0 until NOCHMAL_MS &&
            Instant.ofEpochMilli(versuch).atZone(zone).toLocalDate() == heute
        ) {
            return
        }
        val alt = speicher.zwischengespeichert()
        val altVon = speicher.zwischenspeicherVon(zone)
        // Bis hierher hat Android sicher noch alles (AOSP räumt erst Tagesdateien älter als 10 Tage weg).
        val sicher = heute.minusDays(Verlauf.ANDROID_SICHER_TAGE)
        var gedecktBis: LocalDate? = null
        for (tag in tage) {
            val (anfang, ende) = tagesgrenzen(tag, zone)
            val abfrage = Nutzungsdaten.abfragen(context, anfang - VORLAUF_MS, ende)
            val bisher = verlauf.lesen(tag)
            // Den Anfang des Tags hat Android, wenn es den Vortag eben selbst hergab – oder der
            // Tag bzw. der Vortag im Verlauf steht und Android ihn seitdem nicht weggeräumt haben kann.
            val gedeckt = tagGedeckt(
                abfrage.erstes,
                abfrage.letztes,
                anfang,
                vortagBekannt = gedecktBis == tag.minusDays(1) || (tag >= sicher && verlauf.lesen(tag.minusDays(1)) != null),
                tagBekannt = tag >= sicher && bisher != null,
            )
            if (!gedeckt && bisher == null) continue
            // Was an dem Tag schon gezählt war, zählt weiter – auch wenn die App inzwischen deinstalliert ist.
            val browser = ArrayList<Intervall>()
            val roh = heuteJeApp(abfrage.ereignisse, anfang, ende, launcher + bisher.schluessel(), home) { p, a, e ->
                if (p in BROWSER) browser += Intervall(p, a, e)
            }
            val android = nieWeniger(roh, bisher)
            val seiten = seitenZeiten(browser, Beobachtungen(context).lesen(anfang, ende))
            val (web, gelesen) = when {
                bisher != null && bisher.webGelesen > 0L -> bisher.web to bisher.webGelesen
                alt != null && altVon != null && tag >= altVon -> webFuerTag(alt.second, tag) to alt.first
                else -> emptyMap<String, Long>() to 0L
            }
            val fertig = gedeckt || bisher?.fertig == true
            verlauf.schreiben(tagAus(tag, android, web, gelesen, fertig, seiten = seiten, name = mitAltenNamen(bisher, name)))
            // Noch keine Web-Werte, oder gelesen, bevor der Tag zu Ende war: Die nächste Netzabfrage holt ihn einmal mit.
            if (gelesen < ende) {
                val fehlt = verlauf.webFehltAb
                if (fehlt == null || tag < fehlt) verlauf.webFehltAb = tag
            }
            if (gedeckt) {
                gedecktBis = tag
                verlauf.nachgetragenBis = tag
            }
        }
        // Älteres als Androids Aufbewahrung kommt nicht wieder – das nicht stündlich neu versuchen.
        val vorSicher = sicher.minusDays(1)
        if ((verlauf.nachgetragenBis ?: LocalDate.MIN) < vorSicher) verlauf.nachgetragenBis = vorSicher
        if (verlauf.nachgetragenBis != heute.minusDays(1)) verlauf.nachtragVersuch = jetzt
        verlauf.aufraeumen(heute)
        Beobachtungen(context).aufraeumen(jetzt)
    }

    /**
     * Die Web-Apps von Supabase (ab `von`, sonst die letzten acht Tage) oder aus dem Zwischenspeicher.
     * „ohne Netz“ heißt: Der letzte Netzversuch ging schief – auch bei den
     * Rechnungen dazwischen, die Supabase gar nicht fragen (Takt.plan).
     * „verbunden“ heißt weiter: in den letzten acht Tagen gemeldet.
     */
    fun webStand(speicher: Speicher, heute: LocalDate, netz: Boolean, jetzt: Long, von: LocalDate): WebStand {
        val acht = tagText(heute.minusDays(Verlauf.WEB_TAGE - 1))
        fun daten(zeilen: List<Zeile>, ohneNetz: Boolean, gelesen: Long) =
            webDaten(zeilen.filter { it.tag >= acht }, tagText(heute), ohneNetz = ohneNetz, gelesen = gelesen)
        if (netz) {
            speicher.netzVersuch = jetzt
            try {
                val kennung = speicher.kennung
                if (!speicher.angemeldet && Supabase.anmelden(kennung)) speicher.angemeldet = true
                val zeilen = webLesen(kennung, von, heute)
                speicher.zwischenspeichern(jetzt, zeilen, von)
                speicher.netzFehlt = false
                return WebStand(daten(zeilen, ohneNetz = false, gelesen = jetzt), zeilen, von, frisch = true)
            } catch (e: IOException) {
                Log.i(LOG, "Supabase nicht erreicht: ${e.javaClass.simpleName}")
            } catch (e: JSONException) {
                Log.i(LOG, "Supabase-Antwort unlesbar")
            }
            speicher.netzFehlt = true
        }
        val ohneNetz = netz || speicher.netzFehlt
        val alt = speicher.zwischengespeichert() ?: return WebStand(WebDaten.LEER.copy(ohneNetz = ohneNetz), emptyList(), heute, frisch = false)
        return WebStand(daten(alt.second, ohneNetz, alt.first), alt.second, speicher.zwischenspeicherVon() ?: heute, frisch = false)
    }

    /**
     * „Alle Daten löschen“: Supabase nimmt Anmeldung und Zahlen weg, dann eine
     * neue Kennung (gleich angemeldet, sonst beim nächsten Lauf) – und der
     * Verlauf der App ist leer und beginnt mit heute neu. Ohne Netz geht nichts
     * davon (false): Halb gelöscht wäre schlimmer. Unter der Sperre: Ein
     * laufender Lauf könnte sonst die alte Kennung wieder anmelden.
     */
    fun allesLoeschen(
        context: Context,
        jetzt: Long = System.currentTimeMillis(),
        entfernen: (String) -> Boolean = Supabase::loeschen,
    ): Boolean = Aktualisierung.allein {
        val speicher = Speicher(context)
        val ok = try {
            entfernen(speicher.kennung)
            true
        } catch (e: IOException) {
            false
        }
        if (ok) {
            Verlauf(context).loeschen(Instant.ofEpochMilli(jetzt).atZone(ZoneId.systemDefault()).toLocalDate())
            Beobachtungen(context).loeschen()
            val neu = speicher.neueKennungSetzen()
            try {
                if (Supabase.anmelden(neu)) speicher.angemeldet = true
            } catch (e: IOException) {
                // holt der nächste Lauf nach
            }
        }
        ok
    }
}

/**
 * Was ein Tag im Verlauf schon hatte, geht nicht verloren: je App das Größere
 * aus `bisher` und Androids neuer Rechnung `neu` (Sekunden je Paket, wie
 * heuteJeApp sie liefert). Chrome zählt dabei samt den drei Web-Apps (so
 * stand es vor dem Aufteilen). Nötig, weil Android die Ereignisse einer
 * deinstallierten App nicht mehr liefert (AOSP UsageStatsDatabase.onPackageRemoved,
 * IntervalStats.deobfuscateEvents) – und weil es nach einem Neustart auch
 * einmal leer antworten kann. Sonst wächst ein Tag ohnehin nur.
 */
internal fun nieWeniger(neu: Map<String, Long>, bisher: Tag?): Map<String, Long> {
    if (bisher == null) return neu
    val aus = LinkedHashMap(webApksZuChrome(neu))
    for ((k, s) in bisher.androidTeil()) aus[k] = maxOf(aus[k] ?: 0L, s)
    return aus
}

/** Die Pakete, die der Tag schon hat (zählen weiter, auch deinstalliert). */
internal fun Tag?.schluessel(): Set<String> = this?.posten?.mapTo(HashSet()) { it.schluessel }.orEmpty()

/** Kennt Android eine App nicht mehr (deinstalliert), bleibt der Name, unter dem sie an dem Tag stand. */
internal fun mitAltenNamen(bisher: Tag?, name: (String) -> String): (String) -> String {
    val bekannt = bisher?.posten?.associate { it.schluessel to it.name }.orEmpty()
    return { k -> name(k).let { n -> if (n == k) bekannt[k] ?: n else n } }
}

/** Was von Supabase kam: für heute (`daten`), die Zeilen ab `von` für den Verlauf, `frisch` = gerade eben gelesen. */
class WebStand(val daten: WebDaten, val zeilen: List<Zeile>, val von: LocalDate, val frisch: Boolean)

const val LOG = "Bildschirmzeit"
