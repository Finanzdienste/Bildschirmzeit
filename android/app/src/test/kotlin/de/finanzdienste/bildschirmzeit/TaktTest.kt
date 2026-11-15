package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tobi, 15.11.: „Alter stand? Keinen bock immer zu aktualisieren“ – wann das
 * Widget von selbst rechnet und wann es dabei Supabase fragt (rein, ohne Gerät).
 */
class TaktTest {
    private val t = 1_794_700_000_000L
    private val minute = 60_000L

    private fun plan(anlass: Anlass, jetzt: Long, gerechnet: Long, netz: Long, hell: Boolean = true) =
        Takt.plan(anlass, jetzt, gerechnet, netz, hell)

    @Test
    fun weckerKlingeltAlleZweiMinutenOhneZuWecken() {
        assertEquals(2 * minute, Takt.WECKER_MS)
        // Android legt ungenaue Wecker bis 75 % des Abstands zusammen: 2 bis 3½ Minuten – „höchstens wenige Minuten alt“.
        assertTrue(Takt.WECKER_MS * 7 / 4 <= 4 * minute)
    }

    @Test
    fun weckerRechnetJedesMalAberNichtZweimalInDerMinute() {
        assertEquals(Takt.Plan(rechnen = true, netz = true), plan(Anlass.WECKER, t, gerechnet = 0, netz = 0))
        // Gerade angetippt: Der Wecker lässt es.
        assertFalse(plan(Anlass.WECKER, t, gerechnet = t - 30_000, netz = t - 30_000).rechnen)
        assertTrue(plan(Anlass.WECKER, t, gerechnet = t - minute, netz = t - minute).rechnen)
        assertTrue(plan(Anlass.WECKER, t, gerechnet = t - 2 * minute, netz = t - 2 * minute).rechnen)
    }

    @Test
    fun supabaseHoechstensAlleFuenfMinutenUndNurBeiHellemBildschirm() {
        // Rechnen ja, Supabase erst wieder nach 5 Minuten – dazwischen gilt der Zwischenspeicher.
        assertEquals(Takt.Plan(rechnen = true, netz = false), plan(Anlass.WECKER, t, gerechnet = t - 2 * minute, netz = t - 4 * minute))
        assertEquals(Takt.Plan(rechnen = true, netz = true), plan(Anlass.WECKER, t, gerechnet = t - 2 * minute, netz = t - 5 * minute))
        // Klingelt der Wecker bei dunklem Bildschirm (Android hat ihn zurückgehalten, dann doch zugestellt): ohne Netz.
        assertEquals(Takt.Plan(rechnen = true, netz = false), plan(Anlass.WECKER, t, gerechnet = t - 9 * minute, netz = t - 9 * minute, hell = false))
    }

    @Test
    fun antippenFragtImmerUndGroesseNie() {
        assertEquals(Takt.Plan(rechnen = true, netz = true), plan(Anlass.TIPPEN, t, gerechnet = t - 1000, netz = t - 1000))
        assertEquals(Takt.Plan(rechnen = true, netz = false), plan(Anlass.GROESSE, t, gerechnet = 0, netz = 0))
    }

    @Test
    fun workManagerNachtsHoechstensStuendlichMitNetz() {
        // Bildschirm an: wie der Wecker alle 5 Minuten.
        assertTrue(plan(Anlass.ARBEIT, t, gerechnet = t - 15 * minute, netz = t - 5 * minute).netz)
        // Bildschirm aus (das Handy liegt): gerechnet wird (Mitternacht), Supabase höchstens stündlich.
        val dunkel = plan(Anlass.ARBEIT, t, gerechnet = t - 15 * minute, netz = t - 45 * minute, hell = false)
        assertEquals(Takt.Plan(rechnen = true, netz = false), dunkel)
        assertTrue(plan(Anlass.ARBEIT, t, gerechnet = t - 15 * minute, netz = t - 60 * minute, hell = false).netz)
        assertTrue(plan(Anlass.START, t, gerechnet = 0, netz = 0, hell = false).netz)
    }

    @Test
    fun zurueckgestellteUhrZaehltAlsLangeHer() {
        assertEquals(Long.MAX_VALUE, Takt.seit(0, t))
        assertEquals(Long.MAX_VALUE, Takt.seit(t + minute, t))
        assertEquals(minute, Takt.seit(t - minute, t))
        assertEquals(Takt.Plan(rechnen = true, netz = true), plan(Anlass.WECKER, t, gerechnet = t + 3 * minute, netz = t + 3 * minute))
    }
}
