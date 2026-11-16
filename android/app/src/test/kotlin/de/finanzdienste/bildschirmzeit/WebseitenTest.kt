package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tobi, 15.11.: „Bei Firefox bin ich viel auf Youtube. Kann man sich die
 * größten Webseiten als einzelnen Punkt auflisten lassen?“ Die reine Logik
 * dahinter (Webseiten.kt): Domain aus der Adresszeile, die Leiste im Baum von
 * Firefox (nachgebaut nach den Quellen, siehe Leiste), die Aufteilung der
 * Firefox-Zeit – und dass die Gesamtzeit dabei nie wächst oder schrumpft.
 */
class WebseitenTest {
    private val ff = "org.mozilla.firefox"
    private val min = 60_000L

    /** Ein Testknoten. */
    private data class K(
        override val id: String? = null,
        override val klasse: String? = "android.view.View",
        override val text: CharSequence? = null,
        override val beschreibung: CharSequence? = null,
        override val editierbar: Boolean = false,
        override val fokussiert: Boolean = false,
        val unten: List<K> = emptyList(),
    ) : Knoten {
        override val kinder: Int get() = unten.size

        override fun kind(i: Int): Knoten? = unten.getOrNull(i)
    }

    /** Ein großer Seiteninhalt (GeckoView), in den die Suche nicht hineingeht. */
    private fun seiteninhalt(n: Int) = K(
        id = "$ff:id/engineView",
        klasse = "android.widget.FrameLayout",
        unten = listOf(K(klasse = "android.webkit.WebView", unten = (1..n).map { K(text = "Absatz $it") })),
    )

    /** Firefox mit der neuen Leiste (Compose): unten der Inhalt, die Leiste mit Feld und Tab-Zähler. */
    private fun compose(beschreibung: String, zaehler: String = "Offene Tabs: 3. Antippen, um Tabs zu wechseln.") = K(
        klasse = "android.widget.FrameLayout",
        unten = listOf(
            seiteninhalt(2000),
            K(
                klasse = "android.view.View",
                unten = listOf(
                    K(id = "ADDRESSBAR_URL_BOX", beschreibung = beschreibung),
                    K(id = "ADDRESSBAR_TABS_COUNTER", beschreibung = zaehler),
                ),
            ),
        ),
    )

    @Test
    fun domainNurDerName() {
        assertEquals("youtube.com", domainAus("youtube.com"))
        assertEquals("youtube.com", domainAus("https://m.youtube.com/watch?v=abc&t=10"))
        assertEquals("youtube.com", domainAus("WWW.YouTube.com"))
        assertEquals("spiegel.de", domainAus("www.spiegel.de/politik/a-123.html#kommentare"))
        assertEquals("bbc.co.uk", domainAus("https://www.bbc.co.uk/news"))
        assertEquals("finanzdienste.github.io", domainAus("https://finanzdienste.github.io/Workout/"))
        assertEquals("wikipedia.org", domainAus("de.m.wikipedia.org/wiki/Hamburg"))
        assertEquals("beispiel.de", domainAus("https://nutzer:geheim@beispiel.de:8443/x"))
        assertEquals("müller.de", domainAus("müller.de"))
        assertEquals("192.168.1.1", domainAus("http://192.168.1.1/"))
        // Keine Seite: Suchtext, Firefox selbst, Lokales, kaputt.
        for (keine in listOf(null, "", "wetter hamburg", "wetter", "about:home", "about:blank", "moz-extension://x/y.html",
            "file:///sdcard/a.html", "content://media/1", "localhost", "http://[::1]/", "1.2.3", "a..b", "beispiel.1")) {
            assertNull(keine.toString(), domainAus(keine))
        }
    }

    @Test
    fun neueLeisteAusDerBeschreibung() {
        // Ohne Titel: „ youtube.com/watch?v=abc. Suchbegriff oder Adresse eingeben“ (Origin.kt, getContentDescription).
        assertEquals(Befund.Seite("youtube.com"), Leiste.lesen(compose(" youtube.com/watch?v=abc. Suchbegriff oder Adresse eingeben"), ff))
        // Mit Titel (Custom Tab): der Titel zählt nie, auch mit Punkten darin.
        assertEquals(
            Befund.Seite("spiegel.de"),
            Leiste.lesen(compose("Dr. No. Ein Film spiegel.de/kultur. Suchbegriff oder Adresse eingeben"), ff),
        )
        // Neuer Tab: nur der Hinweis – Firefox ohne Seite.
        assertEquals(Befund.Keine, Leiste.lesen(compose("Suchbegriff oder Adresse eingeben"), ff))
        assertEquals(Befund.Keine, Leiste.lesen(compose(" about:home. Suchbegriff oder Adresse eingeben"), ff))
    }

    @Test
    fun alteLeisteAusDemText() {
        val alt = K(unten = listOf(seiteninhalt(50), K(id = "$ff:id/mozac_browser_toolbar_url_view", klasse = "android.widget.TextView", text = "https://www.youtube.com/")))
        assertEquals(Befund.Seite("youtube.com"), Leiste.lesen(alt, ff))
        // Beim Tippen steht dort der Suchtext: nichts ändern.
        val tippen = K(unten = listOf(K(id = "$ff:id/mozac_browser_toolbar_url_view", text = "wetter morgen", editierbar = true, fokussiert = true)))
        assertEquals(Befund.Unbekannt, Leiste.lesen(tippen, ff))
        // Keine Leiste zu sehen (Vollbild-Video, Tab-Übersicht): das Bisherige gilt weiter.
        assertEquals(Befund.Unbekannt, Leiste.lesen(K(unten = listOf(seiteninhalt(10))), ff))
    }

    @Test
    fun privateTabsZaehlenNieAlsSeite() {
        assertEquals(Befund.Privat, Leiste.lesen(compose(" youtube.com. Suchbegriff oder Adresse eingeben", "Offene private Tabs: 2. Antippen, um Tabs zu wechseln."), ff))
        assertEquals(Befund.Privat, Leiste.lesen(compose(" youtube.com. Search or enter address", "Private Tabs Open: 2. Tap to switch tabs."), ff))
        // „nicht privat“ ist nicht privat.
        assertFalse(Leiste.privatZaehler("Geöffnete nicht private Tabs: 4. Antippen, um Tabs zu wechseln."))
        assertFalse(Leiste.privatZaehler("Non-private Tabs Open: 4. Tap to switch tabs."))
        assertTrue(Leiste.privatZaehler("Offene private Tabs: 1. Antippen, um Tabs zu wechseln."))
    }

    @Test
    fun zweiLeistenEineMitSeiteGewinnt() {
        val baum = K(unten = listOf(K(id = "ADDRESSBAR_URL_BOX", beschreibung = "Suchbegriff oder Adresse eingeben"), K(id = "ADDRESSBAR_URL_BOX", beschreibung = " youtube.com. Suchbegriff oder Adresse eingeben")))
        assertEquals(Befund.Seite("youtube.com"), Leiste.lesen(baum, ff))
    }

    @Test
    fun firefoxZeitNachBeobachtungen() {
        val iv = listOf(Intervall(ff, 0L, 30 * min))
        val b = listOf(Beobachtung(0L, ff, "youtube.com"), Beobachtung(20 * min, ff, "spiegel.de"))
        assertEquals(mapOf(seitenSchluessel(ff, "youtube.com") to 20 * 60L, seitenSchluessel(ff, "spiegel.de") to 10 * 60L), seitenZeiten(iv, b))
        // Bis zur ersten Beobachtung und nach „keine Seite“ zählt nichts (bleibt Firefox).
        val mitLuecke = listOf(Beobachtung(5 * min, ff, "youtube.com"), Beobachtung(15 * min, ff, null))
        assertEquals(mapOf(seitenSchluessel(ff, "youtube.com") to 10 * 60L), seitenZeiten(iv, mitLuecke))
        // Eine Beobachtung von vorhin gilt im nächsten Stück weiter (Vollbild-Video, keine Leiste) …
        val zweiStuecke = listOf(Intervall(ff, 0L, 10 * min), Intervall(ff, 60 * min, 70 * min))
        assertEquals(mapOf(seitenSchluessel(ff, "youtube.com") to 20 * 60L), seitenZeiten(zweiStuecke, listOf(Beobachtung(0L, ff, "youtube.com"))))
        // … aber nicht, wenn sie zu alt ist, und nicht nach „Dienst an/aus“.
        assertEquals(mapOf(seitenSchluessel(ff, "youtube.com") to 10 * 60L), seitenZeiten(zweiStuecke, listOf(Beobachtung(0L, ff, "youtube.com")), gilt = 30 * min))
        assertEquals(
            mapOf(seitenSchluessel(ff, "youtube.com") to 10 * 60L),
            seitenZeiten(zweiStuecke, listOf(Beobachtung(0L, ff, "youtube.com"), Beobachtung(30 * min, ALLE, null))),
        )
        // Ein anderer Browser und andere Apps bekommen nichts.
        assertTrue(seitenZeiten(listOf(Intervall("org.mozilla.firefox_beta", 0L, 30 * min)), b).isEmpty())
    }

    @Test
    fun firefoxWirdGekuerztDieSummeBleibt() {
        val geteilt = mapOf(ff to 3600L, "com.beispiel.chat" to 600L)
        val yt = seitenSchluessel(ff, "youtube.com")
        val kurz = seitenSchluessel(ff, "kurz.de")
        // Jede erkannte Seite wird ein Posten im Verlauf (gezeigt wird erst ab 5 Minuten einzeln, webseitenZusammenfassen).
        val aus = seitenAbziehen(geteilt, mapOf(yt to 2400L, kurz to 59L))
        assertEquals(mapOf(ff to 1141L, "com.beispiel.chat" to 600L, yt to 2400L, kurz to 59L), aus)
        assertEquals(geteilt.values.sum(), aus.values.sum())
        // 0 Sekunden: kein Posten.
        assertEquals(null, seitenAbziehen(geteilt, mapOf(kurz to 0L))[kurz])
        // Mehr Seiten als Firefox lief (nieWeniger, Uhr): anteilig gekürzt, Firefox ganz weg, Summe gleich.
        val mehr = seitenAbziehen(mapOf(ff to 100L), mapOf(yt to 150L, kurz to 150L))
        assertEquals(mapOf(yt to 50L, kurz to 50L), mehr)
        // Kein Firefox an dem Tag: keine Seiten.
        assertEquals(mapOf("com.beispiel.chat" to 600L), seitenAbziehen(mapOf("com.beispiel.chat" to 600L), mapOf(yt to 600L)))
    }

    @Test
    fun verlaufFaltetSeitenZurueckInFirefox() {
        val tag = LocalDate.of(2026, 11, 15)
        val yt = seitenSchluessel(ff, "youtube.com")
        val t = tagAus(tag, mapOf(ff to 3600L, CHROME to 900L), mapOf("workout" to 300L), 1L, fertig = false, seiten = mapOf(yt to 1500L)) { "Firefox" }
        assertEquals(listOf(ff to 2100L, yt to 1500L, CHROME to 600L, "workout" to 300L), t.posten.map { it.schluessel to it.sekunden })
        assertEquals("youtube.com", t.posten.single { it.schluessel == yt }.name)
        assertEquals(4500L, t.gesamt)
        // Androids Teil: Firefox wieder ganz, Chrome wieder samt Workout.
        assertEquals(mapOf(ff to 3600L, CHROME to 900L), t.androidTeil())
        // Neue Web-Werte ändern nur Chrome; die Seite bleibt.
        val neu = t.mitWeb(mapOf("workout" to 600L), 2L)
        assertEquals(1500L, neu.posten.single { it.schluessel == yt }.sekunden)
        assertEquals(4500L, neu.gesamt)
        // Die nächste Rechnung (nieWeniger gegen den Verlauf): Firefox wuchs auf 61 Min., nichts doppelt.
        val android = nieWeniger(mapOf(ff to 3660L, CHROME to 900L), t)
        assertEquals(3660L, android[ff])
        val weiter = tagAus(tag, android, mapOf("workout" to 300L), 1L, fertig = false, seiten = mapOf(yt to 1560L)) { "Firefox" }
        assertEquals(4560L, weiter.gesamt)
        // Ein Tag vor der Funktion (Firefox ganz) und danach Seiten: Firefox schrumpft, die Summe bleibt.
        val vorher = tagAus(tag, mapOf(ff to 3600L), emptyMap(), 0L, fertig = false) { "Firefox" }
        val nachher = tagAus(tag, nieWeniger(mapOf(ff to 3600L), vorher), emptyMap(), 0L, fertig = false, seiten = mapOf(yt to 600L)) { "Firefox" }
        assertEquals(3600L, nachher.gesamt)
        assertEquals(600L, nachher.posten.single { it.schluessel == yt }.sekunden)
    }

    @Test
    fun schluesselHinUndZurueck() {
        assertEquals(ff to "youtube.com", seiteVon(seitenSchluessel(ff, "youtube.com")))
        assertNull(seiteVon("org.mozilla.firefox"))
        assertNull(seiteVon("seite:"))
        assertNull(seiteVon("seite:org.mozilla.firefox/"))
        assertEquals("youtube.com", anzeigename(seitenSchluessel(ff, "youtube.com")))
    }

    @Test
    fun drosselLiestNichtStaendig() {
        // Nach einem Fensterwechsel gleich, Inhaltsereignisse höchstens alle 5 s, frühestens nach 1 s.
        assertEquals(5_000L, Drossel.wartezeit(jetzt = 10_000L, letzteAbfrage = 10_000L))
        assertEquals(1_000L, Drossel.wartezeit(jetzt = 20_000L, letzteAbfrage = 10_000L))
        assertEquals(2_000L, Drossel.wartezeit(jetzt = 13_000L, letzteAbfrage = 10_000L))
        assertTrue(Drossel.FENSTER_MS < Drossel.INHALT_MIN_MS)
    }
    /** Chrome (Tobi, 16.11.: „Das gleiche für Chrome“): die Adresszeile url_bar, ein EditText. */
    @Test
    fun chromeLeiste() {
        val cr = CHROME
        fun baum(vararg k: K) = K(unten = listOf(seiteninhalt(30), K(id = "$cr:id/toolbar", unten = k.toList())))
        val leiste = K(id = "$cr:id/url_bar", klasse = "android.widget.EditText", text = "youtube.com/watch?v=1", editierbar = true)
        assertEquals(Befund.Seite("youtube.com"), Leiste.lesen(baum(leiste), cr))
        // Beim Tippen (Fokus) steht dort der Suchtext.
        assertEquals(Befund.Unbekannt, Leiste.lesen(baum(leiste.copy(text = "wetter morgen", fokussiert = true)), cr))
        // Suchbegriffe statt Adresse (Chrome zeigt sie bei Google-Ergebnissen): keine Seite.
        assertEquals(Befund.Keine, Leiste.lesen(baum(leiste.copy(text = "wetter morgen")), cr))
        // Neuer Tab: das Suchfeld statt der Leiste.
        assertEquals(Befund.Keine, Leiste.lesen(baum(K(id = "$cr:id/search_box_text", text = "Suchen oder URL eingeben")), cr))
        // Inkognito: das Abzeichen in der Leiste.
        assertEquals(Befund.Privat, Leiste.lesen(baum(K(id = "$cr:id/location_bar_incognito_badge", beschreibung = "Inkognitomodus"), leiste), cr))
        assertTrue(Leiste.privatZaehler("Incognito mode"))
        // Ohne Leiste: eine installierte Web-App oder ein Custom Tab ist kein Web (bleibt beim Browser), im Browser (Vollbild) das Bisherige.
        val ohne = K(unten = listOf(seiteninhalt(30)))
        assertEquals(Befund.KeinWeb, Leiste.lesen(ohne, cr, "org.chromium.chrome.browser.webapps.SameTaskWebApkActivity"))
        assertEquals(Befund.KeinWeb, Leiste.lesen(ohne, cr, "org.chromium.chrome.browser.customtabs.CustomTabActivity"))
        assertEquals(Befund.Unbekannt, Leiste.lesen(ohne, cr, "org.chromium.chrome.browser.ChromeTabbedActivity"))
        assertEquals(Befund.Unbekannt, Leiste.lesen(ohne, ff, "org.mozilla.fenix.HomeActivity"))
    }

    /** Tobis Web-Apps zählen in Chrome über Supabase (Boxenstopp, Freizeit, Workout) – dort keine Seite. */
    @Test
    fun webAppsInChromeKeineSeite() {
        assertNull(gemerkteDomain(CHROME, "finanzdienste.github.io"))
        assertEquals("finanzdienste.github.io", gemerkteDomain(ff, "finanzdienste.github.io"))
        assertEquals("youtube.com", gemerkteDomain(CHROME, "youtube.com"))
    }

    /** Tobi, 16.11.: „… alles unter fünf min unter sonstige websiten zusammengefasst … Also Chrome und Firefox addiert“. */
    @Test
    fun zusammenfassenBeideBrowserAbFuenfMinuten() {
        val posten = listOf(
            Posten(seitenSchluessel(ff, "youtube.com"), "youtube.com", 2400L),
            Posten(seitenSchluessel(CHROME, "youtube.com"), "youtube.com", 300L),
            Posten(seitenSchluessel(ff, "kurz.de"), "kurz.de", 120L),
            Posten(seitenSchluessel(CHROME, "klein.de"), "klein.de", 100L),
            // Zusammen genau 5 Minuten: einzeln.
            Posten(seitenSchluessel(CHROME, "geteilt.de"), "geteilt.de", 200L),
            Posten(seitenSchluessel(ff, "geteilt.de"), "geteilt.de", 100L),
            Posten(ff, "Firefox", 600L),
            Posten(CHROME, "Chrome", 300L),
            Posten("com.beispiel.chat", "Chat", 900L),
        )
        val gezeigt = webseitenZusammenfassen(posten)
        assertEquals(
            listOf("youtube.com" to 2700L, "Chat" to 900L, "Firefox" to 600L, "Chrome" to 300L, "geteilt.de" to 300L, "Sonstige Webseiten" to 220L),
            gezeigt.map { it.name to it.sekunden },
        )
        assertEquals(posten.sumOf { it.sekunden }, gezeigt.sumOf { it.sekunden })
        assertEquals(WEBSEITE + "youtube.com", gezeigt.first().schluessel)
        assertEquals(SONSTIGE_WEBSEITEN, gezeigt.last().schluessel)
        // Ohne Seiten bleibt die Liste, wie sie ist.
        val ohne = listOf(Posten(ff, "Firefox", 600L))
        assertEquals(ohne, webseitenZusammenfassen(ohne))
    }

    /** In der Statistik gilt die 5-Minuten-Grenze für den Zeitraum; eine angetippte Seite zählt in jedem Zeitraum einzeln. */
    @Test
    fun statistikFasstJeZeitraumZusammen() {
        val mo = LocalDate.of(2026, 11, 9)
        fun tag(d: LocalDate, vararg p: Posten) = Tag(d, sortiert(p.toList()), fertig = true)
        val verlauf = mapOf(
            mo to tag(mo, Posten(seitenSchluessel(ff, "youtube.com"), "youtube.com", 200L), Posten(ff, "Firefox", 100L)),
            mo.plusDays(1) to tag(mo.plusDays(1), Posten(seitenSchluessel(CHROME, "youtube.com"), "youtube.com", 200L), Posten(CHROME, "Chrome", 50L)),
        )
        val woche = auswerten(Zeitraum.um(Reiter.WOCHE, mo), verlauf, mo.plusDays(1), mo)
        assertEquals(mapOf("youtube.com" to 400L, "Firefox" to 100L, "Chrome" to 50L), woche.apps.associate { it.name to it.sekunden })
        // Am Montag allein nur 200 s: „Sonstige Webseiten“.
        val montag = auswerten(Zeitraum.um(Reiter.TAG, mo), verlauf, mo.plusDays(1), mo)
        assertEquals(mapOf("Sonstige Webseiten" to 200L, "Firefox" to 100L), montag.apps.associate { it.name to it.sekunden })
        // youtube.com angetippt: an beiden Tagen ihre Zeit (Firefox und Chrome), auch am Montag allein.
        val yt = auswerten(Zeitraum.um(Reiter.WOCHE, mo), verlauf, mo.plusDays(1), mo, nur = WEBSEITE + "youtube.com")
        assertEquals(listOf(200L, 200L), yt.tage.take(2).map { it.sekunden })
        assertEquals(400L, yt.gesamt)
        assertEquals(200L, auswerten(Zeitraum.um(Reiter.TAG, mo), verlauf, mo.plusDays(1), mo, nur = WEBSEITE + "youtube.com").gesamt)
        // „Sonstige Webseiten“ angetippt: am Montag die 200 s.
        assertEquals(200L, auswerten(Zeitraum.um(Reiter.TAG, mo), verlauf, mo.plusDays(1), mo, nur = SONSTIGE_WEBSEITEN).gesamt)
    }
    /**
     * Tobi, 16.11. (Widget mit Chrome als eigenem Eintrag): „Eigentlich dürften Chrome und
     * Firefox ja garnicht mehr auftauchen oder?“ – Browserzeit ohne erkennbaren
     * Namen (neuer Tab, Suchbegriffe, privat, Inkognito) zählt zu „Sonstige
     * Webseiten“; nur Web-Apps (kein Web) bleiben beim Browser.
     */
    @Test
    fun wasDerDienstSichMerkt() {
        assertEquals(true to "youtube.com", zuMerken(ff, Befund.Seite("youtube.com")))
        assertEquals(true to OHNE_DOMAIN, zuMerken(ff, Befund.Keine))
        assertEquals(true to OHNE_DOMAIN, zuMerken(CHROME, Befund.Privat))
        assertEquals(true to null, zuMerken(CHROME, Befund.KeinWeb))
        assertEquals(true to null, zuMerken(CHROME, Befund.Seite("finanzdienste.github.io")))
        assertEquals(false to null, zuMerken(ff, Befund.Unbekannt))
        // Eine Seite ohne Namen steht nie einzeln da, auch über 5 Minuten.
        val gezeigt = webseitenZusammenfassen(
            listOf(Posten(seitenSchluessel(CHROME, OHNE_DOMAIN), "?", 900L), Posten(seitenSchluessel(ff, "youtube.com"), "youtube.com", 100L), Posten(CHROME, "Chrome", 60L)),
        )
        assertEquals(listOf("Sonstige Webseiten" to 1000L, "Chrome" to 60L), gezeigt.map { it.name to it.sekunden })
        assertEquals(SONSTIGE_NAME, anzeigename(seitenSchluessel(ff, OHNE_DOMAIN)))
    }
    /** Tobi, 16.11.: „Zwei Stunden Chrome?“ – wie die Browserzeit aufgeteilt war, für Karte 4. */
    @Test
    fun browserZeitAufgeschluesselt() {
        val iv = listOf(Intervall(CHROME, 0L, 60 * min), Intervall(ff, 0L, 5 * min))
        val b = listOf(
            Beobachtung(0L, CHROME, "youtube.com"),
            Beobachtung(10 * min, CHROME, OHNE_DOMAIN),
            Beobachtung(20 * min, CHROME, null),
            Beobachtung(30 * min, ALLE, null),
        )
        val z = browserZeiten(iv, b)
        assertEquals(BrowserZeit(seiten = 600, ohneName = 600, keinWeb = 600, nichtErfasst = 1800), z[CHROME])
        assertEquals(BrowserZeit(nichtErfasst = 300), z[ff])
        assertEquals(3600L, z.getValue(CHROME).gesamt)
        // Zusammen genau die Zeit der Intervalle, und seitenZeiten zählt dasselbe.
        assertEquals(600L + 600L, seitenZeiten(iv, b).values.sum())
        assertEquals(
            "Chrome heute 1 Std.: 10 Min. Seiten, 10 Min. ohne Namen, 10 Min. Web-Apps, 30 Min. nicht erfasst.\n" +
                "„Nicht erfasst“: Die Bedienungshilfe lief da nicht oder hat den Browser nicht erkannt.",
            aufschluesselungText(mapOf(CHROME to z.getValue(CHROME))),
        )
        assertEquals(
            "Firefox heute 5 Min.: 5 Min. nicht erfasst.\nChrome heute 1 Std.: 10 Min. Seiten, 10 Min. ohne Namen, 10 Min. Web-Apps, 30 Min. nicht erfasst.\n" +
                "„Nicht erfasst“: Die Bedienungshilfe lief da nicht oder hat den Browser nicht erkannt.",
            aufschluesselungText(z),
        )
        // Alles erkannt: keine Erklärung; unter einer Minute Browser: nichts.
        assertEquals("Chrome heute 30 Min.: 30 Min. Seiten.", aufschluesselungText(mapOf(CHROME to BrowserZeit(seiten = 1800))))
        assertNull(aufschluesselungText(mapOf(CHROME to BrowserZeit(seiten = 30))))
    }
}
