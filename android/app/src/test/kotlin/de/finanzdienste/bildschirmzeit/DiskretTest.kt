package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Diskret (Diskret.kt): Erotikseiten und von Tobi zusammengelegte Seiten
 * erscheinen nie mit Namen – sie zählen zu „Sonstiges“. Erfundene
 * Beispiele (außer den großen, allgemein bekannten Namen der Erkennung).
 */
class DiskretTest {
    private val ff = "org.mozilla.firefox"

    @Test
    fun erotikAmNamenErkannt() {
        for (ja in listOf("pornhub.com", "beispiel-porno.de", "xvideos.com", "beispiel.xxx", "beispiel.porn", "sexshop-beispiel.de",
            "sex-beispiel.de", "camgirl-beispiel.com", "beeg.com", "onlyfans.com", "beispiel-hentai.net")) {
            assertTrue(ja, Erotik.ist(ja))
        }
        // Keine Fehltreffer: „sex“ mitten im Wort, Nudeln, das Übliche.
        for (nein in listOf("youtube.com", "spiegel.de", "wikipedia.org", "sussex.ac.uk", "essex.gov.uk", "middlesex.ac.uk",
            "nudelrezepte.de", "finanzdienste.github.io", OHNE_DOMAIN)) {
            assertFalse(nein, Erotik.ist(nein))
        }
    }

    @Test
    fun derDienstMerktSichKeinenNamen() {
        val verborgen = { d: String -> Erotik.ist(d) || d == "meine-seite.de" }
        assertEquals(true to OHNE_DOMAIN, zuMerken(ff, Befund.Seite("beispiel-porno.de"), verborgen))
        assertEquals(true to OHNE_DOMAIN, zuMerken(CHROME, Befund.Seite("meine-seite.de"), verborgen))
        assertEquals(true to "youtube.com", zuMerken(ff, Befund.Seite("youtube.com"), verborgen))
        // Ohne Angabe wie bisher.
        assertEquals(true to "beispiel-porno.de", zuMerken(ff, Befund.Seite("beispiel-porno.de")))
    }

    /** Seit v28 zählen sie zu „Firefox“ (Tobi, 25.11.: „statt sonstiges können wir das ja einfach Firefox nennen“). */
    @Test
    fun angezeigtNurAlsFirefox() {
        val posten = listOf(
            Posten(seitenSchluessel(ff, "beispiel-porno.de"), "beispiel-porno.de", 3600L),
            Posten(seitenSchluessel(CHROME, "meine-seite.de"), "meine-seite.de", 900L),
            Posten(seitenSchluessel(ff, "youtube.com"), "youtube.com", 600L),
        )
        val gezeigt = webseitenZusammenfassen(posten, verborgen = { Erotik.ist(it) || it == "meine-seite.de" })
        assertEquals(listOf("Firefox" to 4500L, "youtube.com" to 600L), gezeigt.map { it.name to it.sekunden })
        assertEquals(ff, gezeigt.first().schluessel)
        // Auch in der Statistik, und eine verborgene lässt sich nicht als einzelne Seite wählen.
        val tag = LocalDate.of(2026, 11, 16)
        val verlauf = mapOf(tag to Tag(tag, sortiert(posten), fertig = true))
        val a = auswerten(Zeitraum.um(Reiter.TAG, tag), verlauf, tag, tag, verborgen = { Erotik.ist(it) || it == "meine-seite.de" })
        assertEquals(listOf("Firefox", "youtube.com"), a.apps.map { it.name })
        val nur = auswerten(Zeitraum.um(Reiter.TAG, tag), verlauf, tag, tag, nur = WEBSEITE + "meine-seite.de", verborgen = { it == "meine-seite.de" })
        assertEquals(0L, nur.gesamt)
    }

    @Test
    fun imVerlaufOhneNamenDieZeitBleibt() {
        val tag = Tag(
            LocalDate.of(2026, 11, 16),
            sortiert(
                listOf(
                    Posten(seitenSchluessel(ff, "beispiel-porno.de"), "beispiel-porno.de", 600L),
                    Posten(seitenSchluessel(ff, OHNE_DOMAIN), SONSTIGE_NAME, 60L),
                    Posten(seitenSchluessel(ff, "youtube.com"), "youtube.com", 300L),
                    Posten(ff, "Firefox", 100L),
                ),
            ),
            fertig = true,
        )
        val neu = ohneNamen(tag, Erotik::ist)!!
        assertEquals(
            mapOf(seitenSchluessel(ff, OHNE_DOMAIN) to 660L, seitenSchluessel(ff, "youtube.com") to 300L, ff to 100L),
            neu.posten.associate { it.schluessel to it.sekunden },
        )
        assertEquals(tag.gesamt, neu.gesamt)
        assertEquals(tag.androidTeil(), neu.androidTeil())
        assertTrue(neu.posten.none { "porno" in it.name || "porno" in it.schluessel })
        // Nichts zu tun: null.
        assertNull(ohneNamen(neu, Erotik::ist))
    }
    /** Tobi, 17.11.: Dating-Apps (und -Seiten) auch nie mit Namen – die Apps unter „Sonstiges“, die Seiten zu „Firefox“. */
    @Test
    fun datingAppsUndSeiten() {
        assertTrue(Partnersuche.app("com.tinder", "Tinder"))
        assertTrue(Partnersuche.app("de.beispiel.app", "Lovoo"))
        assertTrue(Partnersuche.app("com.beispiel.datingapp", "Beispiel"))
        assertTrue(Partnersuche.app("de.beispiel.app", "Beispiel Partnersuche"))
        assertFalse(Partnersuche.app("com.whatsapp", "WhatsApp"))
        assertFalse(Partnersuche.app("de.beispiel.hermes", "Hermes"))
        assertTrue(Partnersuche.seite("tinder.com"))
        assertTrue(Partnersuche.seite("parship.de"))
        assertTrue(Partnersuche.seite("beispiel-dating.de"))
        assertFalse(Partnersuche.seite("spiegel.de"))
        assertTrue(nieMitNamen("parship.de"))
        assertTrue(nieMitNamen("beeg.com"))
    }

    @Test
    fun appsUnterSonstiges() {
        val posten = listOf(
            Posten("com.tinder", "Tinder", 1200L),
            Posten("com.beispiel.chat", "Chat", 600L),
            Posten(seitenSchluessel(ff, "youtube.com"), "youtube.com", 400L),
        )
        val gezeigt = webseitenZusammenfassen(posten, { false }, Partnersuche::app)
        assertEquals(listOf("Sonstiges" to 1200L, "Chat" to 600L, "youtube.com" to 400L), gezeigt.map { it.name to it.sekunden })
        // Auch ohne eine einzige Seite.
        assertEquals(listOf("Sonstiges" to 1200L, "Chat" to 600L), webseitenZusammenfassen(posten.take(2), { false }, Partnersuche::app).map { it.name to it.sekunden })
        // In der Statistik, und „Sonstiges“ angetippt zählt die App mit.
        val tag = LocalDate.of(2026, 11, 16)
        val verlauf = mapOf(tag to Tag(tag, sortiert(posten), fertig = true))
        val a = auswerten(Zeitraum.um(Reiter.TAG, tag), verlauf, tag, tag, appVerborgen = Partnersuche::app)
        assertEquals(listOf("Sonstiges", "Chat", "youtube.com"), a.apps.map { it.name })
        // youtube.com (400 s) steht einzeln; in „Sonstiges“ nur die Dating-App.
        assertEquals(1200L, auswerten(Zeitraum.um(Reiter.TAG, tag), verlauf, tag, tag, nur = SONSTIGES, appVerborgen = Partnersuche::app).gesamt)
    }

    /** Die Liste bekannter Seiten steht nur als Prüfsumme im Code. */
    @Test
    fun bekannteSeitenNurAlsPruefsumme() {
        assertEquals("9c3ac886739d8082", Erotik.pruefsumme("beeg.com"))
        assertEquals(Erotik.pruefsumme("BEEG.com"), Erotik.pruefsumme("beeg.com"))
        assertTrue(Erotik.ist("beeg.com"))
        assertFalse(Erotik.ist("beispiel-ohne-alles.de"))
    }
}
