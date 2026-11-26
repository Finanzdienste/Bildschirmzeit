package de.finanzdienste.bildschirmzeit

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Was als unproduktiv zählt (Unproduktiv.kt) – Tobi, 26.11.: „Ich will dass im
 * widget bei der gesamtatundenzahl dahinter in Klammern steht "davon
 * unproduktiv:" oder so“. Rein, erfundene Zeiten.
 */
class UnproduktivTest {
    private val ff = "org.mozilla.firefox"

    @Test
    fun vorgaben() {
        assertTrue(UnproduktivRegeln.vorgabe("com.google.android.youtube"))
        assertTrue(UnproduktivRegeln.vorgabe("com.dazn"))
        assertTrue(UnproduktivRegeln.vorgabe(WEBSEITE + "youtube.com"))
        assertTrue(UnproduktivRegeln.vorgabe(SONSTIGES))
        assertTrue(UnproduktivRegeln.vorgabe("com.beispiel.spiel", ApplicationInfo.CATEGORY_GAME))
        assertTrue(UnproduktivRegeln.vorgabe("com.beispiel.serien", ApplicationInfo.CATEGORY_VIDEO))
        assertTrue(UnproduktivRegeln.vorgabe("com.beispiel.netz", ApplicationInfo.CATEGORY_SOCIAL))
        // Messenger nie von selbst – auch wenn sie sich bei Android „sozial“ nennen.
        assertFalse(UnproduktivRegeln.vorgabe("com.whatsapp", ApplicationInfo.CATEGORY_SOCIAL))
        // Firefox (Suche, kleine Seiten, ohne Namen), Chrome, Tobis Web-Apps, Arbeit, Karten: nicht.
        assertFalse(UnproduktivRegeln.vorgabe(ff, ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertFalse(UnproduktivRegeln.vorgabe(CHROME))
        assertFalse(UnproduktivRegeln.vorgabe("bewerbungszentrale"))
        assertFalse(UnproduktivRegeln.vorgabe("com.beispiel.assistent", ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertFalse(UnproduktivRegeln.vorgabe("com.beispiel.karten", ApplicationInfo.CATEGORY_MAPS))
        assertFalse(UnproduktivRegeln.vorgabe("com.beispiel.ohne"))
        assertFalse(UnproduktivRegeln.vorgabe(WEBSEITE + "wikipedia.org"))
    }

    @Test
    fun ausDenPostenDesVerlaufs() {
        val posten = listOf(
            Posten(seitenSchluessel(ff, "youtube.com"), "youtube.com", 3000L),
            // Klein: steht im Widget bei „Firefox“, zählt aber nach ihrer Domain.
            Posten(seitenSchluessel(CHROME, "reddit.com"), "reddit.com", 120L),
            // Ohne Namen (Suche, privat, Diskretes): wie „Firefox“.
            Posten(seitenSchluessel(ff, OHNE_DOMAIN), FIREFOX_NAME, 600L),
            Posten(ff, "Firefox", 300L),
            // Dating: wie „Sonstiges“.
            Posten("com.tinder", "Tinder", 900L),
            Posten("com.whatsapp", "WhatsApp", 1200L),
            Posten("workout", "Workout", 1800L),
        )
        val vorgabe = { k: String -> UnproduktivRegeln.vorgabe(k) }
        assertEquals(3000L + 120L + 900L, unproduktivSekunden(posten, vorgabe, Partnersuche::app))
        // „Firefox“ selbst als unproduktiv gestellt: dazu Firefox und die Seiten ohne Namen.
        val mitFirefox = { k: String -> k == ff || UnproduktivRegeln.vorgabe(k) }
        assertEquals(3000L + 120L + 900L + 600L + 300L, unproduktivSekunden(posten, mitFirefox, Partnersuche::app))
        // „Sonstiges“ nicht: ohne die Dating-App.
        val ohneSonstiges = { k: String -> k != SONSTIGES && UnproduktivRegeln.vorgabe(k) }
        assertEquals(3000L + 120L, unproduktivSekunden(posten, ohneSonstiges, Partnersuche::app))
    }

    /** In der Statistik: für alle Apps eine Zeile mit Anteil, für eine gewählte, ob sie zählt. */
    @Test
    fun statistik() {
        val tag = LocalDate.of(2026, 11, 24)
        val verlauf = (0L..1L).associate { d ->
            val t = tag.plusDays(d)
            t to Tag(t, sortiert(listOf(Posten("com.google.android.youtube", "YouTube", 1800L), Posten("com.beispiel.chat", "Chat", 1800L))), fertig = true)
        }
        val ist = { k: String -> UnproduktivRegeln.vorgabe(k) }
        val woche = auswerten(Zeitraum.um(Reiter.WOCHE, tag.plusDays(1)), verlauf, tag.plusDays(1), tag, unproduktiv = ist)
        assertEquals(3600L, woche.unproduktiv)
        // Tobi, 26.11.: „Will auch bei der unproduktiven Zeit den tagesschnitt wissen“ – über dieselben Tage wie „Ø je Tag“.
        assertEquals("Ø je Tag 1 Std.\ndavon unproduktiv: 1 Std. (50\u00A0%)\nØ je Tag unproduktiv: 30 Min.", schnittText(woche))
        assertEquals(1800L, woche.unproduktivSchnitt)
        // Am Tag kein Schnitt (wie oben bei der Gesamtzeit).
        val tagAllein = auswerten(Zeitraum.um(Reiter.TAG, tag), verlauf, tag.plusDays(1), tag, unproduktiv = ist)
        assertEquals("davon unproduktiv: 30 Min. (50\u00A0%)", schnittText(tagAllein))
        val yt = auswerten(Zeitraum.um(Reiter.WOCHE, tag.plusDays(1)), verlauf, tag.plusDays(1), tag, nur = "com.google.android.youtube", unproduktiv = ist)
        assertTrue(yt.nurUnproduktiv)
        assertTrue(schnittText(yt)!!, schnittText(yt)!!.endsWith("zählt als unproduktiv"))
        val chat = auswerten(Zeitraum.um(Reiter.WOCHE, tag.plusDays(1)), verlauf, tag.plusDays(1), tag, nur = "com.beispiel.chat", unproduktiv = ist)
        assertFalse(chat.nurUnproduktiv)
        assertFalse(schnittText(chat)!!, schnittText(chat)!!.contains("unproduktiv"))
        // Nichts Unproduktives: keine Zeile.
        val nichts = auswerten(Zeitraum.um(Reiter.WOCHE, tag.plusDays(1)), verlauf, tag.plusDays(1), tag)
        assertFalse(schnittText(nichts).orEmpty().contains("unproduktiv"))
    }
}
