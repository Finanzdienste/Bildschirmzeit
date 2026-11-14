package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Texte, Kennung, Ring-Geometrie und Versionsnummern – alles ohne Gerät. */
class TexteTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun dauerTextWieImWebModul() {
        assertEquals("unter 1 Min.", dauerText(0))
        assertEquals("unter 1 Min.", dauerText(59))
        assertEquals("unter 1 Min.", dauerText(-5))
        assertEquals("1 Min.", dauerText(60))
        assertEquals("59 Min.", dauerText(3599))
        assertEquals("1 Std.", dauerText(3600))
        assertEquals("1 Std. 5 Min.", dauerText(3900))
        assertEquals("3 Std. 12 Min.", dauerText(3 * 3600 + 12 * 60 + 59))
    }

    @Test
    fun kurzTextFuerDieZeilenImWidget() {
        assertEquals("unter 1 Min.", kurzText(30))
        assertEquals("54 Min.", kurzText(54 * 60 + 30))
        assertEquals("1:00 Std.", kurzText(3600))
        assertEquals("2:41 Std.", kurzText(2 * 3600 + 41 * 60 + 59))
        assertEquals("12:05 Std.", kurzText(12 * 3600 + 5 * 60))
    }

    @Test
    fun ringTextBrichtNachDenStundenUm() {
        assertEquals("3 Std.\n12 Min.", ringText(3 * 3600 + 12 * 60))
        assertEquals("2 Std.", ringText(7200))
        assertEquals("45 Min.", ringText(45 * 60))
    }

    @Test
    fun zeitpunktTextHeuteGesternFrueher() {
        val jetzt = ZonedDateTime.of(2026, 11, 14, 21, 45, 0, 0, berlin).toInstant().toEpochMilli()
        fun ms(tag: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, tag, h, m, 0, 0, berlin).toInstant().toEpochMilli()
        assertEquals("21:40", zeitpunktText(ms(14, 21, 40), jetzt, berlin))
        assertEquals("07:05", zeitpunktText(ms(14, 7, 5), jetzt, berlin))
        assertEquals("gestern 23:59", zeitpunktText(ms(13, 23, 59), jetzt, berlin))
        assertEquals("12.11., 08:00", zeitpunktText(ms(12, 8, 0), jetzt, berlin))
        assertEquals("21:40", uhrzeit(ms(14, 21, 40), berlin))
    }

    @Test
    fun kennungPasstZurRegelVonSupabaseUndDemWebModul() {
        val k = neueKennung()
        assertEquals(43, k.length)
        assertTrue(KENNUNG_REGEL.matches(k))
        assertNotEquals(k, neueKennung())
        // Die Regel, mit der web/bildschirmzeit.js den Link liest:
        val link = Regex("[#&]bildschirmzeit=([A-Za-z0-9_-]{32,128})(?=&|$)")
        val url = WebApp.WORKOUT.link(k)
        assertEquals(k, link.find(url.substringAfter('#').let { "#$it" })?.groupValues?.get(1))
    }

    @Test
    fun boegenFuellenDenRingMitLuecken() {
        val b = boegen(listOf(60L, 30L, 0L, 10L), listOf(1, 2, 3, 4), luecke = 4f)
        assertEquals(listOf(1, 2, 4), b.map { it.farbe })
        assertEquals(-88f, b[0].anfang, 0.001f)
        assertEquals(216f - 4f, b[0].laenge, 0.001f)
        assertEquals(360f, b.sumOf { (it.laenge + 4f).toDouble() }.toFloat(), 0.01f)
        // Jeder Bogen beginnt, wo der vorige samt Lücke endet.
        for (i in 1 until b.size) assertEquals(b[i - 1].anfang + b[i - 1].laenge + 4f, b[i].anfang, 0.001f)
    }

    @Test
    fun einWertIstEinGanzerRingKeinerIstLeer() {
        assertEquals(listOf(Bogen(-90f, 360f, 1)), boegen(listOf(0L, 50L, 0L, 0L), listOf(9, 1, 8, 7)))
        assertTrue(boegen(listOf(0L, 0L, 0L, 0L), listOf(1, 2, 3, 4)).isEmpty())
    }

    @Test
    fun winzigeWerteBleibenSichtbar() {
        val b = boegen(listOf(100_000L, 1L), listOf(1, 2), luecke = 3f, kleinster = 1.2f)
        assertEquals(1.2f, b[1].laenge, 0.001f)
    }

    @Test
    fun ringWerteTopDreiUndRest() {
        val p = listOf(Posten("a", "A", 50), Posten("b", "B", 40), Posten("c", "C", 30), Posten("d", "D", 20), Posten("e", "E", 5))
        assertEquals(listOf(50L, 40L, 30L, 25L), ringWerte(p))
        assertEquals(listOf(50L, 0L, 0L, 0L), ringWerte(p.take(1)))
        assertEquals(listOf(0L, 0L, 0L, 0L), ringWerte(emptyList()))
    }

    @Test
    fun laufnummerAusDemReleaseTag() {
        assertEquals(12, NeueVersion.laufnummer("v12"))
        assertEquals(7, NeueVersion.laufnummer(" v7 "))
        assertNull(NeueVersion.laufnummer("v1.2"))
        assertNull(NeueVersion.laufnummer("12"))
        assertNull(NeueVersion.laufnummer(""))
    }
}
