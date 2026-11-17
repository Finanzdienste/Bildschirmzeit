package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Diskret (Diskret.kt): Erotikseiten und von Tobi zusammengelegte Seiten
 * erscheinen nie mit Namen – sie zählen zu „Sonstige Webseiten“. Erfundene
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

    @Test
    fun angezeigtNurAlsSonstige() {
        val posten = listOf(
            Posten(seitenSchluessel(ff, "beispiel-porno.de"), "beispiel-porno.de", 3600L),
            Posten(seitenSchluessel(CHROME, "meine-seite.de"), "meine-seite.de", 900L),
            Posten(seitenSchluessel(ff, "youtube.com"), "youtube.com", 600L),
        )
        val gezeigt = webseitenZusammenfassen(posten) { Erotik.ist(it) || it == "meine-seite.de" }
        assertEquals(listOf("Sonstige Webseiten" to 4500L, "youtube.com" to 600L), gezeigt.map { it.name to it.sekunden })
        // Auch in der Statistik, und eine verborgene lässt sich nicht als einzelne Seite wählen.
        val tag = LocalDate.of(2026, 11, 16)
        val verlauf = mapOf(tag to Tag(tag, sortiert(posten), fertig = true))
        val a = auswerten(Zeitraum.um(Reiter.TAG, tag), verlauf, tag, tag, verborgen = { Erotik.ist(it) || it == "meine-seite.de" })
        assertEquals(listOf("Sonstige Webseiten", "youtube.com"), a.apps.map { it.name })
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
}
