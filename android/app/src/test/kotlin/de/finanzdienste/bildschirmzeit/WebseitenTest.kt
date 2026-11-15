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
        val aus = seitenAbziehen(geteilt, mapOf(yt to 2400L, kurz to 59L))
        assertEquals(mapOf(ff to 1200L, "com.beispiel.chat" to 600L, yt to 2400L), aus)
        assertEquals(geteilt.values.sum(), aus.values.sum())
        // Genau eine Minute: eigener Posten.
        assertEquals(60L, seitenAbziehen(geteilt, mapOf(kurz to 60L))[kurz])
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
}
