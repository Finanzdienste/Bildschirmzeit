package de.finanzdienste.bildschirmzeit

import de.finanzdienste.bildschirmzeit.WidgetRaster.Form
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * Tobi, 15.11.: „Was ich will ist die gesamtzzeit alles Apps zu sehen und
 * Vllt so die top 8 oder so mit der größten nutzungszeit. Je nachdem wieviel
 * hin passt.“ – wie viele Apps in welche Größe passen (rein, in dp, ohne
 * Gerät). Ob die Views das dann auch so legen, prüft VorschauTest.
 */
class WidgetRasterTest {

    private data class Erwartet(val form: Form, val spalten: Int, val zeilen: Int, val kompakt: Boolean)

    @Test
    fun tobisWidgetZeigtAchtInZweiSpaltenMitGrosserSchrift() {
        val r = WidgetRaster.fuer(400f, 201f)
        assertEquals(Erwartet(Form.BREIT, spalten = 2, zeilen = 4, kompakt = false), Erwartet(r.form, r.spalten, r.zeilen, r.kompakt))
        assertEquals(8, r.plaetze)
        assertTrue(r.titel && r.stand)
    }

    /** Die Größen, an die wir denken (S24: 4×2 = 400 × 201 dp; andere Raster kleiner). */
    @Test
    fun dieGroessenDesStartbildschirms() {
        val erwartet = mapOf(
            (352f to 176f) to Erwartet(Form.BREIT, 2, 3, kompakt = true), // 4×2 auf einem 4×5-Raster: sechs
            (300f to 176f) to Erwartet(Form.BREIT, 1, 3, kompakt = false), // zwei Spalten wären zu schmal für „Boxenstopp“
            (264f to 176f) to Erwartet(Form.SCHMAL, 1, 3, kompakt = false),
            (352f to 150f) to Erwartet(Form.FLACH, 1, 5, kompakt = true),
            (400f to 100f) to Erwartet(Form.FLACH, 1, 3, kompakt = true), // 4×1: Kopf links, drei Apps rechts
            (352f to 100f) to Erwartet(Form.FLACH, 1, 3, kompakt = true),
            (700f to 150f) to Erwartet(Form.FLACH, 2, 4, kompakt = false), // 4×2 quer
            (352f to 80f) to Erwartet(Form.BREIT, 1, 0, kompakt = false), // nur der Kopf
            (170f to 170f) to Erwartet(Form.SCHMAL, 1, 3, kompakt = true), // 2×2
            (200f to 201f) to Erwartet(Form.SCHMAL, 1, 4, kompakt = true), // 2×2 bei Tobi: kompakt vier statt groß drei
            (352f to 290f) to Erwartet(Form.BREIT, 1, 8, kompakt = true), // 4×3: acht untereinander
            (400f to 300f) to Erwartet(Form.BREIT, 1, 8, kompakt = false),
            (170f to 290f) to Erwartet(Form.SCHMAL, 1, 8, kompakt = true), // 2×3
            (170f to 100f) to Erwartet(Form.SCHMAL, 1, 0, kompakt = true), // 2×1: nur der Kopf
        )
        for ((groesse, e) in erwartet) {
            val r = WidgetRaster.fuer(groesse.first, groesse.second)
            assertEquals("${groesse.first} × ${groesse.second}", e, Erwartet(r.form, r.spalten, r.zeilen, r.kompakt))
            assertTrue(r.titel && r.stand)
        }
    }

    /**
     * Über alle Größen von 2×1 bis weit über 4×4, auch mit größerer Schrift:
     * höchstens 8, nie mehr Zeilen, als ganz hineinpassen (mit dem Kopf und dem
     * Stand), und keine Zeile weniger, als passen würde.
     */
    @Test
    fun nieMehrAlsAchtNieEineHalbeZeileUndKeineZuwenig() {
        var geprueft = 0
        for (schrift in listOf(1f, 1.15f, 1.3f)) {
            var breite = 110f
            while (breite <= 800f) {
                var hoehe = 40f
                while (hoehe <= 460f) {
                    pruefen(breite, hoehe, schrift)
                    geprueft++
                    hoehe += 3f
                }
                breite += 3f
            }
        }
        assertTrue(geprueft > 30_000)
    }

    private fun pruefen(breite: Float, hoehe: Float, schrift: Float) {
        val wo = "$breite × $hoehe, Schrift $schrift"
        val r = WidgetRaster.fuer(breite, hoehe, schrift)
        assertTrue(wo, r.plaetze in 0..WidgetRaster.MAX)
        assertTrue(wo, r.spalten in 1..2)
        val innenB = breite - 2 * WidgetRaster.RAND_SEITE
        val innenH = hoehe - 2 * WidgetRaster.RAND_OBEN - WidgetRaster.RESERVE
        val oben = if (innenB >= WidgetRaster.KOPF_BREIT * schrift) Form.BREIT else Form.SCHMAL
        if (!r.stand || !r.titel) {
            // Nur flacher als jedes Raster: Der ganze Kopf passte wirklich nicht, und ohne „Heute“ auch nicht die zwei Zeilen.
            assertTrue(wo, innenH < WidgetRaster.kopfHoehe(oben, schrift))
            assertEquals(wo, 0, r.zeilen)
            if (!r.titel) assertTrue(wo, innenH < (WidgetRaster.TITEL + WidgetRaster.GESAMT) * schrift) else assertEquals(wo, Form.SCHMAL, r.form)
            return
        }
        val kopf = WidgetRaster.kopfHoehe(r.form, schrift)
        val zh = WidgetRaster.zeilenHoehe(r.kompakt, schrift)
        val platz = if (r.form == Form.FLACH) innenH else innenH - kopf - WidgetRaster.KOPF_ABSTAND
        val listeB = if (r.form == Form.FLACH) innenB - WidgetRaster.KOPF_SEITE * schrift - WidgetRaster.KOPF_SEITE_ABSTAND else innenB
        // Der Kopf passt ganz, die Liste darunter (bzw. daneben) auch.
        assertTrue(wo, kopf <= innenH)
        if (r.zeilen > 0) assertTrue("$wo: ${r}", r.listenHoehe(schrift) <= platz + 0.01f)
        assertTrue(wo, r.abstand >= WidgetRaster.ABSTAND_MIN && r.abstand <= WidgetRaster.ABSTAND_MAX)
        // Zwei Spalten nur, wenn beide breit genug sind.
        val mindestens = (if (r.kompakt) WidgetRaster.SPALTE_KOMPAKT else WidgetRaster.SPALTE) * schrift
        if (r.spalten == 2) assertTrue(wo, (listeB - WidgetRaster.LUECKE) / 2 >= mindestens)
        // Keine Zeile zu wenig: Unter der Obergrenze passte eine weitere nicht mehr.
        val obergrenze = ceil(WidgetRaster.MAX.toFloat() / r.spalten).toInt()
        if (r.zeilen < obergrenze) {
            assertTrue("$wo: noch eine Zeile hätte gepasst ($r)", (r.zeilen + 1) * zh + r.zeilen * WidgetRaster.ABSTAND_MIN > platz)
        }
        // Breit nur, wenn „Update: antippen“ und „Stand 21:40 · ohne Netz“ nebeneinander passen.
        if (r.form == Form.BREIT) assertTrue(wo, innenB >= WidgetRaster.KOPF_BREIT * schrift)
    }

    /** Größere Schrift (Einstellungen → Anzeige): weniger Apps in Tobis Widget, aber nichts abgeschnitten (VorschauTest). */
    @Test
    fun groessereSchriftHeisstWenigerZeilen() {
        val normal = WidgetRaster.fuer(400f, 201f, 1f)
        val gross = WidgetRaster.fuer(400f, 201f, 1.3f)
        assertTrue(gross.plaetze < normal.plaetze)
        assertTrue(gross.plaetze >= 3)
    }

    /** Je größer das Widget, desto mehr (nie weniger) Apps. */
    @Test
    fun groesserZeigtNieWeniger() {
        for (b in listOf(170f, 264f, 300f, 352f, 400f)) {
            var vorher = 0
            var h = 60f
            while (h <= 400f) {
                val n = WidgetRaster.fuer(b, h).plaetze
                assertTrue("$b × $h: $n < $vorher", n >= vorher)
                vorher = n
                h += 1f
            }
        }
        for (h in listOf(100f, 150f, 176f, 201f, 290f)) {
            var vorher = 0
            var b = 110f
            while (b <= 800f) {
                val n = WidgetRaster.fuer(b, h).plaetze
                assertTrue("$b × $h: $n < $vorher", n >= vorher)
                vorher = n
                b += 1f
            }
        }
        assertFalse(WidgetRaster.fuer(400f, 201f).kompakt)
    }
}
