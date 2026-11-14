package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Die Vordergrund-Rechnung aus Nutzung.kt – ohne Gerät, mit erfundenen Ereignissen. */
class NutzungTest {
    private val min = 60_000L
    private val std = 60 * min

    // „Heute“ beginnt bei 24 Std. (Mitternacht), jetzt ist 21:40.
    private val mitternacht = 24 * std
    private val jetzt = mitternacht + 21 * std + 40 * min

    private fun um(stunde: Int, minute: Int = 0) = mitternacht + stunde * std + minute * min
    private fun vorn(t: Long, paket: String, klasse: String = "Haupt") = Ereignis(t, Art.VORN, paket, klasse)
    private fun pause(t: Long, paket: String, klasse: String = "Haupt") = Ereignis(t, Art.PAUSIERT, paket, klasse)
    private fun stopp(t: Long, paket: String, klasse: String = "Haupt") = Ereignis(t, Art.GESTOPPT, paket, klasse)
    private fun global(t: Long, art: Art) = Ereignis(t, art)

    private fun rechne(vararg e: Ereignis) = vordergrundZeiten(e.toList(), mitternacht, jetzt)

    @Test
    fun eineAppEinmal() {
        assertEquals(mapOf("a" to 30 * min), rechne(vorn(um(10), "a"), pause(um(10, 30), "a")))
    }

    @Test
    fun zweiAppsNacheinanderUndZweimalDieselbe() {
        val z = rechne(
            vorn(um(8), "a"), pause(um(8, 10), "a"),
            vorn(um(8, 10), "b"), pause(um(8, 25), "b"),
            vorn(um(9), "a"), pause(um(9, 5), "a"),
        )
        assertEquals(mapOf("a" to 15 * min, "b" to 15 * min), z)
    }

    @Test
    fun zweiActivitiesDerselbenAppZaehlenEinmal() {
        // B kommt nach vorn, bevor A pausiert (so meldet Android manche Wechsel).
        val z = rechne(
            vorn(um(10), "a", "Liste"),
            vorn(um(10, 5), "a", "Detail"),
            pause(um(10, 6), "a", "Liste"),
            pause(um(10, 20), "a", "Detail"),
        )
        assertEquals(mapOf("a" to 20 * min), z)
    }

    @Test
    fun wechselInnerhalbDerAppOhneLuecke() {
        val z = rechne(
            vorn(um(10), "a", "Liste"),
            pause(um(10, 5), "a", "Liste"),
            vorn(um(10, 5), "a", "Detail"),
            stopp(um(10, 5), "a", "Liste"),
            pause(um(10, 9), "a", "Detail"),
        )
        assertEquals(mapOf("a" to 9 * min), z)
    }

    @Test
    fun doppeltesVornZaehltEinmal() {
        assertEquals(mapOf("a" to 10 * min), rechne(vorn(um(10), "a"), vorn(um(10, 2), "a"), pause(um(10, 10), "a")))
    }

    @Test
    fun ueberMitternachtZaehltAbMitternacht() {
        val z = rechne(vorn(mitternacht - 10 * min, "a"), pause(mitternacht + 15 * min, "a"))
        assertEquals(mapOf("a" to 15 * min), z)
    }

    @Test
    fun gesternZaehltNicht() {
        assertEquals(emptyMap<String, Long>(), rechne(vorn(mitternacht - 50 * min, "a"), pause(mitternacht - 20 * min, "a")))
    }

    @Test
    fun nochOffenZaehltBisJetzt() {
        assertEquals(mapOf("a" to 40 * min), rechne(vorn(um(21), "a")))
    }

    @Test
    fun ereignisseNachJetztZaehlenNicht() {
        assertEquals(mapOf("a" to 10 * min), rechne(vorn(um(21, 30), "a"), pause(um(22), "a")))
    }

    @Test
    fun bildschirmAusSchliesstAlles() {
        val z = rechne(
            vorn(um(10), "a"),
            global(um(10, 10), Art.BILDSCHIRM_AUS),
            pause(um(10, 40), "a"), // kommt manchmal erst später – zählt nicht mehr
        )
        assertEquals(mapOf("a" to 10 * min), z)
    }

    @Test
    fun sperrbildschirmSchliesstUndDanachZaehltEsNeu() {
        val z = rechne(
            vorn(um(10), "a"),
            global(um(10, 10), Art.GESPERRT),
            vorn(um(10, 30), "a"),
            pause(um(10, 35), "a"),
        )
        assertEquals(mapOf("a" to 15 * min), z)
    }

    @Test
    fun ausschaltenSchliesst() {
        assertEquals(mapOf("a" to 5 * min), rechne(vorn(um(12), "a"), global(um(12, 5), Art.AUSGESCHALTET)))
    }

    @Test
    fun startOhneEndeVerwirftDasOffene() {
        // Akku leer: kein PAUSIERT, kein AUSGESCHALTET – wie lange es lief, weiß niemand.
        val z = rechne(
            vorn(um(9), "a"),
            global(um(11), Art.GESTARTET),
            vorn(um(11, 5), "a"),
            pause(um(11, 10), "a"),
        )
        assertEquals(mapOf("a" to 5 * min), z)
    }

    @Test
    fun gestopptOhnePausiertSchliesst() {
        assertEquals(mapOf("a" to 7 * min), rechne(vorn(um(13), "a"), stopp(um(13, 7), "a")))
    }

    @Test
    fun gestopptEinerAnderenActivityLaesstOffen() {
        val z = rechne(vorn(um(13), "a", "Neu"), stopp(um(13, 1), "a", "Alt"), pause(um(13, 7), "a", "Neu"))
        assertEquals(mapOf("a" to 7 * min), z)
    }

    @Test
    fun pausiertOhneVornZaehltNicht() {
        assertEquals(emptyMap<String, Long>(), rechne(pause(um(10), "a")))
    }

    @Test
    fun geteilterBildschirmZaehltBeide() {
        val z = rechne(vorn(um(10), "a"), vorn(um(10), "b"), pause(um(10, 20), "a"), pause(um(10, 30), "b"))
        assertEquals(mapOf("a" to 20 * min, "b" to 30 * min), z)
    }

    @Test
    fun reihenfolgeDerListeEgal() {
        val z = rechne(pause(um(10, 30), "a"), vorn(um(10), "a"))
        assertEquals(mapOf("a" to 30 * min), z)
    }

    @Test
    fun heuteJeAppOhneStartbildschirmUndSystem() {
        val e = listOf(
            vorn(um(7), "launcher", "Home"), pause(um(7, 1), "launcher", "Home"),
            vorn(um(7, 1), "einstellungen", "Notfall"), pause(um(7, 2), "einstellungen", "Notfall"),
            vorn(um(7, 2), "einstellungen", "Haupt"), pause(um(7, 5), "einstellungen", "Haupt"),
            vorn(um(7, 5), SYSTEMUI, "Schalter"), pause(um(7, 6), SYSTEMUI, "Schalter"),
            vorn(um(7, 6), "dienst", "Haupt"), pause(um(7, 9), "dienst", "Haupt"),
            vorn(um(7, 9), "a", "Haupt"), pause(um(7, 21), "a", "Haupt"),
            vorn(um(7, 21), "kurz", "Haupt"), pause(um(7, 21) + 999, "kurz", "Haupt"),
        )
        val z = heuteJeApp(
            e, mitternacht, jetzt,
            launcherPakete = setOf("einstellungen", "a", "kurz", SYSTEMUI),
            homeActivities = setOf("launcher/Home", "einstellungen/Notfall"),
        )
        // Sekunden; „dienst“ hat keinen Launcher-Eintrag, „kurz“ war unter einer Sekunde.
        assertEquals(mapOf("einstellungen" to 180L, "a" to 720L), z)
    }

    @Test
    fun startbildschirmUnterbrichtNichtsAnderes() {
        // Während die HOME-Activity vorn ist, läuft nichts – danach geht es normal weiter.
        val e = listOf(
            vorn(um(9), "a"), pause(um(9, 10), "a"),
            vorn(um(9, 10), "launcher", "Home"), global(um(9, 20), Art.BILDSCHIRM_AUS),
            vorn(um(9, 30), "a"), pause(um(9, 31), "a"),
        )
        val z = heuteJeApp(e, mitternacht, jetzt, setOf("a", "launcher"), setOf("launcher/Home"))
        assertEquals(mapOf("a" to 660L), z)
    }
}
