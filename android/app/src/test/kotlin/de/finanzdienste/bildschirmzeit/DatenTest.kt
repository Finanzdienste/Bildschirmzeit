package de.finanzdienste.bildschirmzeit

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Was von Supabase und GitHub kommt und was im Speicher liegt. Mit
 * Robolectric, damit org.json genau Androids Fassung ist.
 */
@RunWith(RobolectricTestRunner::class)
class DatenTest {
    private val kontext = RuntimeEnvironment.getApplication()

    // So antwortet PostgREST auf bildschirmzeit_lesen (erfundene Werte).
    private val antwort = """
        [{"app":"boxenstopp","tag":"2026-11-13","sekunden":300,"aktualisiert":"2026-11-13T19:02:11.123456+00:00"},
         {"app":"workout","tag":"2026-11-14","sekunden":3240,"aktualisiert":"2026-11-14T20:40:00+00:00"},
         {"app":"boxenstopp","tag":"2026-11-14","sekunden":1680,"aktualisiert":"2026-11-14T18:15:30.5+00:00"},
         {"app":"kalender","tag":"2026-11-14","sekunden":99,"aktualisiert":"2026-11-14T18:15:30+00:00"}]
    """.trimIndent()

    @Test
    fun zeilenAusDerAntwortVonSupabase() {
        val z = zeilenAus(antwort)
        assertEquals(listOf("boxenstopp", "workout", "boxenstopp"), z.map { it.app })
        assertEquals(3240L, z[1].sekunden)
        assertEquals("2026-11-14", z[1].tag)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 20, 40, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), z[1].aktualisiert)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 19, 2, 11, 123_000_000, ZoneId.of("UTC")).toInstant().toEpochMilli(), z[0].aktualisiert)
        assertEquals(emptyList<Zeile>(), zeilenAus("[]"))
    }

    @Test(expected = IOException::class)
    fun unlesbareAntwortIstEinFehler() {
        zeilenAus("""{"message":"Kennung ungültig"}""")
    }

    @Test
    fun webDatenFuerHeute() {
        val w = webDaten(zeilenAus(antwort), "2026-11-14", ohneNetz = false, gelesen = 5L)
        assertEquals(mapOf("workout" to 3240L, "boxenstopp" to 1680L), w.heute)
        assertEquals(setOf("workout", "boxenstopp"), w.zuletzt.keys)
        assertEquals(zeitAus("2026-11-14T18:15:30.5+00:00"), w.zuletzt["boxenstopp"])
        assertTrue(w.verbunden)
        assertFalse(WebDaten.LEER.verbunden)
        // Am nächsten Tag: verbunden, aber heute noch nichts.
        val morgen = webDaten(zeilenAus(antwort), "2026-11-15", ohneNetz = false, gelesen = 5L)
        assertEquals(emptyMap<String, Long>(), morgen.heute)
        assertTrue(morgen.verbunden)
    }

    @Test
    fun zwischenspeicherHinUndZurueck() {
        val z = zeilenAus(antwort)
        assertEquals(z, zeilenAusJson(zeilenAlsJson(z)))
    }

    @Test
    fun speicherKennungBleibtUndNeueSetztZurueck() {
        val s = Speicher(kontext)
        val k = s.kennung
        assertTrue(KENNUNG_REGEL.matches(k))
        assertEquals(k, Speicher(kontext).kennung)
        s.angemeldet = true
        s.zwischenspeichern(1234L, zeilenAus(antwort))
        assertEquals(1234L, s.zwischengespeichert()?.first)
        assertEquals(3, s.zwischengespeichert()?.second?.size)

        val neu = s.neueKennungSetzen()
        assertNotEquals(k, neu)
        assertEquals(neu, Speicher(kontext).kennung)
        assertFalse(s.angemeldet)
        assertNull(s.zwischengespeichert())
    }

    @Test
    fun webStandTextFuerSchrittZwei() {
        val jetzt = ZonedDateTime.of(2026, 11, 14, 21, 45, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
        val um2140 = ZonedDateTime.of(2026, 11, 14, 21, 40, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
        val w = WebDaten(mapOf("workout" to 23 * 60L), mapOf("workout" to um2140, "freizeit" to um2140), ohneNetz = false, gelesen = jetzt)
        assertEquals("heute 23 Min., zuletzt 21:40", webStandText(w, "workout", jetzt))
        assertEquals("heute unter 1 Min., zuletzt 21:40", webStandText(w, "freizeit", jetzt))
        assertEquals("noch nicht verbunden", webStandText(w, "boxenstopp", jetzt))
        assertEquals("heute 23 Min., zuletzt 21:40 · ohne Netz", webStandText(w.copy(ohneNetz = true), "workout", jetzt))
    }

    @Test
    fun standTextImWidget() {
        val stand = ZonedDateTime.of(2026, 11, 14, 21, 40, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
        val verbunden = WebDaten(emptyMap(), mapOf("workout" to stand), ohneNetz = true, gelesen = stand)
        assertEquals("Stand 21:40", WidgetAnsicht.standText(Ergebnis(true, emptyList(), stand, verbunden.copy(ohneNetz = false))))
        assertEquals("Stand 21:40 · ohne Netz", WidgetAnsicht.standText(Ergebnis(true, emptyList(), stand, verbunden)))
        // Nie verbunden: Dann ist „ohne Netz“ egal und steht nicht da.
        assertEquals("Stand 21:40", WidgetAnsicht.standText(Ergebnis(true, emptyList(), stand, WebDaten.LEER.copy(ohneNetz = true))))
    }

    @Test
    fun neuesteVersionAusDerAntwortVonGitHub() {
        assertEquals(17, NeueVersion.ausAntwort("""{"tag_name":"v17","name":"Bildschirmzeit 1.17"}"""))
        assertNull(NeueVersion.ausAntwort("""{"message":"Not Found"}"""))
        assertNull(NeueVersion.ausAntwort("kein json"))
    }

    @Test
    fun ringZeichenGrosseZahlenKleineEinheiten() {
        val text = WidgetAnsicht.ringZeichen(3 * 3600 + 12 * 60)
        assertEquals("3 Std.\n12 Min.", text.toString())
        val spans = (text as android.text.Spanned).getSpans(0, text.length, android.text.style.RelativeSizeSpan::class.java)
        assertEquals(listOf(" Std.", " Min."), spans.map { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() })
    }
}
