package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Der eigene Tagesverlauf (Verlauf.kt): speichern, laden, Web-Apps verspätet
 * neu aufteilen, aufbewahren, löschen, Tagesgrenzen mit Sommerzeit. Android hebt
 * seine Ereignisse nur rund zehn Tage auf – für Woche und Monat merkt sich die
 * App jeden Tag selbst. Erfundene Zahlen.
 */
@RunWith(RobolectricTestRunner::class)
class VerlaufTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val heute = LocalDate.of(2026, 11, 15)
    private val berlin = ZoneId.of("Europe/Berlin")
    private lateinit var verlauf: Verlauf

    @Before
    fun leer() {
        verlauf = Verlauf(kontext)
        verlauf.ordner.deleteRecursively()
    }

    private fun tag(datum: LocalDate, vararg posten: Posten, fertig: Boolean = true) = Tag(datum, sortiert(posten.toList()), fertig = fertig)

    @Test
    fun speichernUndLaden() {
        val t = Tag(
            heute.minusDays(1),
            sortiert(listOf(Posten("com.beispiel.chat", "Chat", 2460), Posten("workout", "Workout", 1200), Posten(CHROME, "Chrome", 300))),
            web = mapOf("workout" to 1200L),
            webGelesen = 1234L,
            fertig = true,
        )
        assertTrue(verlauf.schreiben(t))
        assertEquals(t, verlauf.lesen(t.datum))
        assertEquals(3960L, verlauf.lesen(t.datum)!!.gesamt)
        // Unverändert: nicht noch einmal geschrieben.
        assertFalse(verlauf.schreiben(t))
        assertTrue(verlauf.schreiben(t.copy(fertig = false)))
        assertNull(verlauf.lesen(heute))
        verlauf.schreiben(tag(heute.minusDays(5), Posten("a", "A", 60)))
        assertEquals(heute.minusDays(5), verlauf.erster())
        assertEquals(setOf(heute.minusDays(5), heute.minusDays(1)), verlauf.lesen(heute.minusDays(6), heute).keys)
        // Eine kaputte Datei ist „keine Daten“, kein Absturz.
        File(verlauf.ordner, "${heute.minusDays(2)}.json").writeText("{kaputt")
        assertNull(verlauf.lesen(heute.minusDays(2)))
    }

    @Test
    fun liegtNichtInDerSicherung() {
        // noBackupFilesDir geht nie in die Android-Sicherung.
        assertTrue(verlauf.ordner.absolutePath.startsWith(kontext.noBackupFilesDir.absolutePath))
        // Und sicherheitshalber schließen die Regeln Dateien, Datenbanken und Einstellungen aus.
        val parser = kontext.resources.getXml(R.xml.datenregeln)
        val aus = mutableListOf<String>()
        var bereich = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> bereich = parser.name
                "exclude" -> aus += "$bereich:${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
            }
        }
        for (b in listOf("cloud-backup", "device-transfer")) {
            for (d in listOf("sharedpref", "file", "database")) assertTrue("$b $d: $aus", "$b:$d:." in aus)
        }
    }

    @Test
    fun aufbewahrtRund400Tage() {
        verlauf.schreiben(tag(heute.minusDays(401), Posten("a", "A", 60)))
        verlauf.schreiben(tag(heute.minusDays(400), Posten("a", "A", 60)))
        verlauf.schreiben(tag(heute.minusDays(3), Posten("a", "A", 60)))
        verlauf.aufraeumen(heute)
        assertEquals(listOf(heute.minusDays(400), heute.minusDays(3)), verlauf.tage().sorted())
    }

    @Test
    fun loeschenUndNeuAbHeute() {
        verlauf.schreiben(tag(heute.minusDays(3), Posten("a", "A", 60)))
        verlauf.webFehltAb = heute.minusDays(9)
        verlauf.loeschen(heute)
        assertTrue(verlauf.tage().isEmpty())
        assertNull(verlauf.webFehltAb)
        // Nicht gleich wieder rückwirkend aus Androids Ereignissen: Sonst stünde nach dem Löschen alles wieder da.
        assertEquals(heute.minusDays(1), verlauf.nachgetragenBis)
        assertTrue(nachzutragen(heute, verlauf.nachgetragenBis).isEmpty())
    }

    @Test
    fun webAppsMeldenVerspaetet() {
        // Chrome lief 60 Min., Workout hatte 20 gemeldet.
        val alt = tagAus(heute.minusDays(2), mapOf(CHROME to 3600L, "com.beispiel.chat" to 600L), mapOf("workout" to 1200L), 5L, true) { "Name $it" }
        assertEquals(listOf("Name com.android.chrome" to 2400L, "Workout" to 1200L, "Name com.beispiel.chat" to 600L), alt.posten.map { it.name to it.sekunden })
        // Später: Workout 30, Boxenstopp 10 – Chrome behält 20, die Summe bleibt die von Android.
        val neu = alt.mitWeb(mapOf("workout" to 1800L, "boxenstopp" to 600L), 9L)
        assertEquals(listOf(CHROME to 1200L, "workout" to 1800L, "boxenstopp" to 600L, "com.beispiel.chat" to 600L).toSet(), neu.posten.map { it.schluessel to it.sekunden }.toSet())
        assertEquals(alt.gesamt, neu.gesamt)
        assertEquals(9L, neu.webGelesen)
        assertEquals("Name com.android.chrome", neu.posten.first { it.schluessel == CHROME }.name)
        // Mehr gemeldet, als Chrome lief: anteilig auf Chromes Zeit gekürzt, Chrome fällt weg.
        val viel = neu.mitWeb(mapOf("workout" to 3600L, "boxenstopp" to 3600L), 10L)
        assertEquals(mapOf("workout" to 1800L, "boxenstopp" to 1800L, "com.beispiel.chat" to 600L), viel.posten.associate { it.schluessel to it.sekunden })
        assertEquals(mapOf("workout" to 3600L, "boxenstopp" to 3600L), viel.web)
        // Installierte Web-Apps (WebAPK) zählen zu Chrome.
        val webapk = tagAus(heute, mapOf(CHROME to 100L, "${WEBAPK}abc" to 20L), emptyMap(), 0L, false) { it }
        assertEquals(listOf(CHROME to 120L), webapk.posten.map { it.schluessel to it.sekunden })
    }

    /**
     * Seit 21.11. läuft die Bewerbungszentrale in Firefox: Ihre Zeit kommt von dort, auch beim Neuaufteilen,
     * wenn sie verspätet meldet. Ein alter Tag, an dem sie noch aus Chrome kam, bleibt dabei, wie er war.
     */
    @Test
    fun bewerbungszentraleKommtAusFirefox() {
        val tag = tagAus(heute, mapOf(CHROME to 1800L, FIREFOX to 2400L), mapOf("bewerbungszentrale" to 900L, "workout" to 600L), 5L, false) { "Name $it" }
        assertEquals(mapOf(CHROME to 1200L, "workout" to 600L, FIREFOX to 1500L, "bewerbungszentrale" to 900L), tag.posten.associate { it.schluessel to it.sekunden })
        assertEquals(mapOf(CHROME to 1800L, FIREFOX to 2400L), tag.androidTeil())
        val spaeter = tag.mitWeb(mapOf("bewerbungszentrale" to 1200L, "workout" to 600L), 6L)
        assertEquals(mapOf(CHROME to 1200L, "workout" to 600L, FIREFOX to 1200L, "bewerbungszentrale" to 1200L), spaeter.posten.associate { it.schluessel to it.sekunden })
        assertEquals(tag.gesamt, spaeter.gesamt)
        // So gespeichert, als sie noch in Chrome lief: neu aufgeteilt bleibt der Tag gleich.
        val alt = Tag(heute.minusDays(3), sortiert(listOf(Posten(CHROME, "Chrome", 1200L), Posten("bewerbungszentrale", "Bewerbungen", 900L))), mapOf("bewerbungszentrale" to 900L), 5L, true)
        assertEquals(alt.posten.toSet(), alt.mitWeb(mapOf("bewerbungszentrale" to 900L), 6L).posten.toSet())
    }

    @Test
    fun webEintragenNurWasSichAendert() {
        val d = heute.minusDays(2)
        verlauf.schreiben(tagAus(d, mapOf(CHROME to 3600L), mapOf("workout" to 600L), 5L, true) { "Chrome" })
        verlauf.schreiben(tagAus(heute.minusDays(9), mapOf(CHROME to 3600L), emptyMap(), 0L, true) { "Chrome" })
        verlauf.webFehltAb = heute.minusDays(9)
        val zeilen = listOf(
            Zeile("workout", tagText(d), 900, 0),
            Zeile("freizeit", tagText(heute.minusDays(9)), 300, 0),
            Zeile("workout", tagText(heute), 60, 0),
        )
        // Die gewöhnliche Abfrage (heute − 7 …): Tag −2 neu, Tag −9 bleibt, und er fehlt weiter.
        verlauf.webEintragen(zeilen, heute.minusDays(7), heute, 20L)
        assertEquals(mapOf("workout" to 900L), verlauf.lesen(d)!!.web)
        assertEquals(2700L, verlauf.lesen(d)!!.posten.first { it.schluessel == CHROME }.sekunden)
        assertEquals(0L, verlauf.lesen(heute.minusDays(9))!!.webGelesen)
        assertEquals(heute.minusDays(9), verlauf.webFehltAb)
        // Dieselben Werte noch einmal: nichts geschrieben.
        val vorher = File(verlauf.ordner, "$d.json").lastModified()
        File(verlauf.ordner, "$d.json").setLastModified(1000L)
        verlauf.webEintragen(zeilen, heute.minusDays(7), heute, 30L)
        assertEquals(1000L, File(verlauf.ordner, "$d.json").lastModified())
        assertTrue(vorher > 0)
        // Die eine weitere Abfrage ab Tag −9: eingetragen, und es fehlt nichts mehr.
        verlauf.webEintragen(zeilen, heute.minusDays(9), heute, 40L)
        assertEquals(mapOf("freizeit" to 300L), verlauf.lesen(heute.minusDays(9))!!.web)
        assertEquals(40L, verlauf.lesen(heute.minusDays(9))!!.webGelesen)
        assertNull(verlauf.webFehltAb)
        // Heute selbst schreibt die Rechnung von heute, nicht das Nachtragen.
        assertNull(verlauf.lesen(heute))
    }

    @Test
    fun wasSupabaseNichtMehrHatWirdNichtWeiterGesucht() {
        // Lange ohne Netz: Der älteste Tag ohne Web-Werte ist inzwischen älter als Supabases 62 Tage.
        verlauf.webFehltAb = heute.minusDays(70)
        assertEquals(heute.minusDays(62), webVon(heute, verlauf.webFehltAb))
        verlauf.webEintragen(emptyList(), heute.minusDays(62), heute, 50L)
        // Sonst läse jede Abfrage für immer 62 Tage.
        assertNull(verlauf.webFehltAb)
        assertEquals(heute.minusDays(7), webVon(heute, verlauf.webFehltAb))
    }

    @Test
    fun welcheTageNachgetragenWerden() {
        // Erster Start: so weit zurück, wie Android Ereignisse haben kann.
        val erst = nachzutragen(heute, null)
        assertEquals(heute.minusDays(Verlauf.RUECKWIRKEND_TAGE), erst.first())
        assertEquals(heute.minusDays(1), erst.last())
        // Danach nur, was fehlt: nach Mitternacht der Vortag, sonst nichts.
        assertEquals(listOf(heute.minusDays(1)), nachzutragen(heute, heute.minusDays(2)))
        assertTrue(nachzutragen(heute, heute.minusDays(1)).isEmpty())
        // Uhr zurückgestellt: nichts.
        assertTrue(nachzutragen(heute, heute.plusDays(3)).isEmpty())
        // Lange nicht gelaufen: drei Tage.
        assertEquals(3, nachzutragen(heute, heute.minusDays(4)).size)
    }

    @Test
    fun webVonEineAnfrageFuerDenBereich() {
        assertEquals(heute.minusDays(7), webVon(heute, null))
        assertEquals(heute.minusDays(7), webVon(heute, heute.minusDays(2)))
        assertEquals(heute.minusDays(10), webVon(heute, heute.minusDays(10)))
        // Höchstens die 62 Tage, die Supabase hält und bildschirmzeit_lesen auf einmal nimmt.
        assertEquals(heute.minusDays(62), webVon(heute, heute.minusDays(90)))
        assertEquals(
            mapOf("workout" to 900L, "boxenstopp" to 60L),
            webFuerTag(
                listOf(
                    Zeile("workout", "2026-11-13", 600, 0),
                    Zeile("workout", "2026-11-13", 900, 0),
                    Zeile("boxenstopp", "2026-11-13", 60, 0),
                    Zeile("freizeit", "2026-11-12", 60, 0),
                ),
                LocalDate.of(2026, 11, 13),
            ),
        )
    }

    @Test
    fun tagesgrenzenMitSommerzeit() {
        val std = 3_600_000L
        // 29.3.2026: die Uhr springt von 2 auf 3 – 23 Stunden; 25.10.2026: von 3 auf 2 – 25 Stunden.
        for ((datum, stunden) in listOf(LocalDate.of(2026, 3, 29) to 23L, LocalDate.of(2026, 10, 25) to 25L, LocalDate.of(2026, 11, 15) to 24L)) {
            val (von, bis) = tagesgrenzen(datum, berlin)
            assertEquals("$datum", stunden * std, bis - von)
            // Eine App von Mitternacht bis Mitternacht: der ganze Tag, nicht 24 Stunden.
            val e = listOf(Ereignis(von - std, Art.VORN, "com.beispiel.video", "H"), Ereignis(bis + std, Art.PAUSIERT, "com.beispiel.video", "H"))
            val android = heuteJeApp(e, von, bis, setOf("com.beispiel.video"), emptySet())
            val t = tagAus(datum, android, emptyMap(), 0L, true) { "Video" }
            assertEquals("$datum", stunden * 3600, t.gesamt)
        }
        // Über den Jahreswechsel.
        val (von, bis) = tagesgrenzen(LocalDate.of(2026, 12, 31), berlin)
        assertEquals(tagesgrenzen(LocalDate.of(2027, 1, 1), berlin).first, bis)
        assertEquals(24 * std, bis - von)
    }

    @Test
    fun nurGanzeTageZaehlen() {
        val mitternacht = 1_000_000L
        // Android hat Ereignisse vor Mitternacht und am Tag: ganz.
        assertTrue(tagGedeckt(mitternacht - 5, mitternacht + 5, mitternacht, vortagBekannt = false, tagBekannt = false))
        // Die älteste Tagesdatei beginnt mittags: zu kurz – keine Daten statt einer falschen Zahl.
        assertFalse(tagGedeckt(mitternacht + 5, mitternacht + 9, mitternacht, vortagBekannt = false, tagBekannt = false))
        // … außer der Vortag oder der Tag selbst steht schon im Verlauf.
        assertTrue(tagGedeckt(mitternacht + 5, mitternacht + 9, mitternacht, vortagBekannt = true, tagBekannt = false))
        assertTrue(tagGedeckt(mitternacht + 5, mitternacht + 9, mitternacht, vortagBekannt = false, tagBekannt = true))
        // An dem Tag gar nichts (Handy aus, oder Android hat ihn nicht mehr): keine Daten.
        assertFalse(tagGedeckt(mitternacht - 5, mitternacht - 1, mitternacht, vortagBekannt = true, tagBekannt = true))
        assertFalse(tagGedeckt(null, null, mitternacht, vortagBekannt = true, tagBekannt = true))
    }
}
