package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Chrome um Tobis Web-Apps kürzen (Aufteilung.kt). */
class AufteilungTest {

    @Test
    fun webAppsKleinerAlsChromeChromeBehaeltDenRest() {
        val z = aufteilen(
            mapOf(CHROME to 3600L, "com.beispiel.chat" to 900L),
            mapOf("workout" to 1200L, "boxenstopp" to 600L),
        )
        assertEquals(mapOf("com.beispiel.chat" to 900L, "boxenstopp" to 600L, "workout" to 1200L, CHROME to 1800L), z)
        assertEquals(4500L, z.values.sum())
    }

    @Test
    fun webAppsGroesserAlsChromeWerdenAnteiligGekuerzt() {
        val z = aufteilen(mapOf(CHROME to 900L), mapOf("workout" to 1200L, "freizeit" to 600L))
        assertEquals(mapOf("freizeit" to 300L, "workout" to 600L), z)
        assertNull(z[CHROME])
    }

    @Test
    fun genauSoVielWieChromeChromeVerschwindet() {
        val z = aufteilen(mapOf(CHROME to 1000L), mapOf("workout" to 400L, "freizeit" to 600L))
        assertEquals(mapOf("workout" to 400L, "freizeit" to 600L), z)
    }

    @Test
    fun rundungErgibtGenauChromesZeit() {
        val z = aufteilen(mapOf(CHROME to 100L), mapOf("boxenstopp" to 1L, "freizeit" to 1L, "workout" to 1L).mapValues { it.value * 1000 })
        assertEquals(100L, z.values.sum())
        assertEquals(mapOf("boxenstopp" to 34L, "freizeit" to 33L, "workout" to 33L), z)
    }

    @Test
    fun ohneChromeZaehlenDieWebAppsNicht() {
        // Gemeldet, aber Android sah kein Chrome (etwa in einem anderen Browser offen).
        val z = aufteilen(mapOf("com.beispiel.karten" to 300L), mapOf("workout" to 600L))
        assertEquals(mapOf("com.beispiel.karten" to 300L), z)
    }

    @Test
    fun ohneWebAppsBleibtAllesWieBeiAndroid() {
        val android = mapOf(CHROME to 1200L, "com.beispiel.chat" to 60L)
        assertEquals(android, aufteilen(android, emptyMap()))
    }

    @Test
    fun unbekannteUndNegativeWerteZaehlenNicht() {
        val z = aufteilen(mapOf(CHROME to 1000L), mapOf("workout" to -50L, "kalender" to 500L, "freizeit" to 100L))
        assertEquals(mapOf("freizeit" to 100L, CHROME to 900L), z)
    }

    @Test
    fun gesamtzeitBleibtImmerDieVonAndroid() {
        val r = Random(7)
        repeat(500) {
            val android = mutableMapOf<String, Long>()
            if (r.nextBoolean()) android[CHROME] = r.nextLong(0, 20_000)
            repeat(r.nextInt(0, 5)) { i -> android["com.beispiel.app$i"] = r.nextLong(1, 5000) }
            val web = WebApp.entries.associate { it.schluessel to r.nextLong(0, 15_000) }
            val z = aufteilen(android, web)
            assertEquals(android.values.sum(), z.values.sum())
            assertTrue(z.values.all { it > 0 })
            val chrome = android[CHROME] ?: 0L
            for (app in WebApp.entries) assertTrue((z[app.schluessel] ?: 0L) <= (web[app.schluessel] ?: 0L))
            assertTrue((z[CHROME] ?: 0L) <= chrome)
        }
    }

    @Test
    fun installierteWebAppsZaehlenAlsChrome() {
        val z = webApksZuChrome(mapOf(CHROME to 600L, "${WEBAPK}a1b2" to 2L, "${WEBAPK}c3" to 1L, "com.beispiel.chat" to 60L))
        assertEquals(mapOf("com.beispiel.chat" to 60L, CHROME to 603L), z)
        assertEquals(mapOf("com.beispiel.x" to 5L), webApksZuChrome(mapOf("com.beispiel.x" to 5L)))
        assertEquals(mapOf(CHROME to 4L), webApksZuChrome(mapOf("${WEBAPK}nur" to 4L)))
    }

    @Test
    fun anteiligVerteiltDenRestNachGroesstemBruchteil() {
        assertEquals(listOf(2L, 1L, 0L), anteilig(listOf(5L, 3L, 1L), 3L))
        assertEquals(listOf(0L, 0L), anteilig(listOf(0L, 0L), 10L))
        assertEquals(listOf(0L, 0L), anteilig(listOf(4L, 6L), 0L))
    }

    @Test
    fun sortiertAbsteigendDannNachName() {
        val liste = sortiert(
            listOf(Posten("b", "Beta", 60), Posten("a", "alpha", 60), Posten("c", "Gamma", 600), Posten("d", "Null", 0)),
        )
        assertEquals(listOf("c", "a", "b"), liste.map { it.schluessel })
    }

    @Test
    fun webAppsHabenNameSymbolUndLink() {
        assertEquals(listOf("boxenstopp", "freizeit", "workout", "bewerbungszentrale"), WebApp.entries.map { it.schluessel })
        assertEquals("Workout", Posten("workout", "Workout", 1).webApp?.anzeigename)
        assertEquals("Bewerbungen", Posten("bewerbungszentrale", "Bewerbungszentrale", 1).webApp?.anzeigename)
        assertEquals(R.drawable.symbol_bewerbungszentrale, WebApp.BEWERBUNGSZENTRALE.symbol)
        assertNull(Posten(CHROME, "Chrome", 1).webApp)
        val k = "A".repeat(43)
        assertEquals("https://finanzdienste.github.io/Boxenstopp/#bildschirmzeit=$k", WebApp.BOXENSTOPP.link(k))
        assertEquals("https://finanzdienste.github.io/Freizeit/#bildschirmzeit=$k", WebApp.FREIZEIT.link(k))
        assertEquals("https://finanzdienste.github.io/Workout/#bildschirmzeit=$k", WebApp.WORKOUT.link(k))
        assertEquals("https://finanzdienste.github.io/Bewerbungszentrale/#bildschirmzeit=$k", WebApp.BEWERBUNGSZENTRALE.link(k))
        assertEquals("Boxenstopp, Freizeit, Workout und Bewerbungen", webAppsText())
    }

    /**
     * Tobi, 19.11.: „Hab ne zusätzliche App die noch nicht connected ist. Geht um die Bewerbungs app“.
     * Seit 21.11. läuft sie in Firefox; 22.11.: „hab in der widget app auf verbinden geklickt und dann hat
     * sich Chrome geöffnet“. Ihre Zeit kommt jetzt aus Firefox, Chrome bleibt um die anderen drei gekürzt.
     */
    @Test
    fun bewerbungszentraleWirdVonFirefoxAbgezogen() {
        assertEquals(FIREFOX, WebApp.BEWERBUNGSZENTRALE.browser)
        assertEquals(listOf(CHROME, CHROME, CHROME), listOf(WebApp.BOXENSTOPP, WebApp.FREIZEIT, WebApp.WORKOUT).map { it.browser })
        val z = aufteilen(
            mapOf(CHROME to 1800L, FIREFOX to 3000L, "com.beispiel.chat" to 60L),
            mapOf("bewerbungszentrale" to 1200L, "workout" to 600L),
        )
        assertEquals(mapOf(CHROME to 1200L, "workout" to 600L, FIREFOX to 1800L, "bewerbungszentrale" to 1200L, "com.beispiel.chat" to 60L), z)
        assertEquals(4860L, z.values.sum())
        // Mehr gemeldet, als Firefox lief: auf Firefox' Zeit gekürzt, Chrome bleibt unberührt.
        val kurz = aufteilen(mapOf(CHROME to 600L, FIREFOX to 300L), mapOf("bewerbungszentrale" to 900L))
        assertEquals(mapOf(CHROME to 600L, "bewerbungszentrale" to 300L), kurz)
        // Ohne Firefox zählt sie nicht – auch nicht von Chrome.
        assertEquals(mapOf(CHROME to 3600L), aufteilen(mapOf(CHROME to 3600L), mapOf("bewerbungszentrale" to 900L)))
        assertEquals("Chrome ohne Boxenstopp, Freizeit und Workout, Firefox ohne Bewerbungen", browserOhneText())
        assertEquals("Firefox ohne Bewerbungen", browserOhneText(FIREFOX))
        assertEquals("Boxenstopp, Freizeit und Workout", webAppsText(CHROME))
    }
}
