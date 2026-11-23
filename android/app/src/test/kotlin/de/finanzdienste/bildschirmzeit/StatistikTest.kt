package de.finanzdienste.bildschirmzeit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tobi, 15.11.: „Wenn ich auf das widget tippe soll ich mir auch die Wochen
 * und Monatsnutzung nach App aufgeteilt und so anzeigen lassen können“. Die
 * Zeiträume, Titel und Zahlen der Statistik – ohne Gerät, erfundene Zahlen.
 */
class StatistikTest {
    private val heute = LocalDate.of(2026, 11, 15) // ein Sonntag

    private fun tag(datum: LocalDate, vararg posten: Pair<String, Long>) =
        Tag(datum, sortiert(posten.map { (k, s) -> Posten(k, k.substringAfterLast('.').replaceFirstChar { it.uppercase() }, s) }), fertig = true)

    /** Tobi, 23.11.: „Woche bedeutet ja nicht Montag bis Sonntag sondern letzte sieben tage“. */
    @Test
    fun wocheSindDieLetztenSiebenTage() {
        val montag = LocalDate.of(2026, 11, 23)
        val w = Zeitraum.um(Reiter.WOCHE, montag)
        assertEquals(LocalDate.of(2026, 11, 17), w.von)
        assertEquals(montag, w.bis)
        assertEquals(7, w.tage.size)
        assertEquals("17.–23. Nov.", zeitraumTitel(w, montag))
        // Davor die sieben Tage davor, über den Monatswechsel.
        assertEquals("10.–16. Nov.", zeitraumTitel(w.vorher(), montag))
        val vorher = w.vorher().vorher().vorher()
        assertEquals(LocalDate.of(2026, 10, 27), vorher.von)
        assertEquals("27. Okt. – 2. Nov.", zeitraumTitel(vorher, montag))
        // Über den Jahreswechsel: 27.12.2026 bis 2.1.2027.
        val neujahr = Zeitraum.um(Reiter.WOCHE, LocalDate.of(2027, 1, 2))
        assertEquals(LocalDate.of(2026, 12, 27), neujahr.von)
        assertEquals("27. Dez. – 2. Jan. 2027", zeitraumTitel(neujahr, montag))
        assertEquals("27. Dez. – 2. Jan. 2027", zeitraumTitel(neujahr, LocalDate.of(2027, 1, 2)))
        assertEquals("Keine Daten: 30. Dez. – 2. Jan. 2027", "Keine Daten: " + spanne(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2), heute))
        // Eine Woche aus dem Vorjahr nennt das Jahr.
        assertEquals("6.–12. Nov. 2025", zeitraumTitel(Zeitraum.um(Reiter.WOCHE, LocalDate.of(2025, 11, 12)), heute))
        // Vor: höchstens bis heute – dann sind es wieder die letzten sieben Tage.
        val alt = Zeitraum.um(Reiter.WOCHE, LocalDate.of(2026, 11, 19))
        assertEquals(w, alt.nachher(montag))
        assertFalse(w.hatNachher(montag))
    }

    @Test
    fun wochenMitSommerzeitHabenSiebenTage() {
        // Die Uhr springt am 29.3. vor und am 25.10. zurück – die Woche zählt Kalendertage.
        for (tag in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            val w = Zeitraum.um(Reiter.WOCHE, tag)
            assertEquals(7, w.tage.size)
            assertEquals(tag, w.bis)
        }
        assertEquals("19.–25. März", zeitraumTitel(Zeitraum.um(Reiter.WOCHE, LocalDate.of(2026, 3, 25)), heute))
    }

    @Test
    fun monatUndTag() {
        // Tobi, 23.11.: „Monat genauso. Ist nicht November sondern die letzten x tage“ – die letzten 30 Tage.
        val m = Zeitraum.um(Reiter.MONAT, heute)
        assertEquals(LocalDate.of(2026, 10, 17), m.von)
        assertEquals(heute, m.bis)
        assertEquals(30, m.tage.size)
        assertEquals("17. Okt. – 15. Nov.", zeitraumTitel(m, heute))
        assertEquals("17. Sept. – 16. Okt.", zeitraumTitel(m.vorher(), heute))
        assertEquals("7. Nov. – 6. Dez. 2025", zeitraumTitel(Zeitraum.um(Reiter.MONAT, LocalDate.of(2026, 1, 5)).vorher(), heute))
        assertEquals(30, Zeitraum.um(Reiter.MONAT, LocalDate.of(2028, 3, 10)).tage.size)
        // Vor: höchstens bis heute – dann sind es wieder die letzten 30 Tage.
        assertEquals(m, Zeitraum.um(Reiter.MONAT, LocalDate.of(2026, 11, 1)).nachher(heute))

        assertEquals("Heute", zeitraumTitel(Zeitraum.um(Reiter.TAG, heute), heute))
        assertEquals("Gestern", zeitraumTitel(Zeitraum.um(Reiter.TAG, heute.minusDays(1)), heute))
        assertEquals("Fr., 13. Nov.", zeitraumTitel(Zeitraum.um(Reiter.TAG, heute.minusDays(2)), heute))
        assertEquals("Mi., 12. Nov. 2025", zeitraumTitel(Zeitraum.um(Reiter.TAG, LocalDate.of(2025, 11, 12)), heute))
        assertEquals("Do., 1. Jan.", zeitraumTitel(Zeitraum.um(Reiter.TAG, LocalDate.of(2026, 1, 1)), heute))
    }

    @Test
    fun vorUndZurueckNichtUeberHeuteHinaus() {
        for (r in Reiter.entries) {
            val jetzt = Zeitraum.um(r, heute)
            assertFalse("$r: nicht über heute hinaus", jetzt.hatNachher(heute))
            assertTrue(r.name, jetzt.vorher().hatNachher(heute))
            assertEquals(jetzt, jetzt.vorher().nachher())
        }
        // Die Woche bis heute ist die letzte.
        val mittwoch = LocalDate.of(2026, 11, 11)
        assertFalse(Zeitraum.um(Reiter.WOCHE, mittwoch).hatNachher(mittwoch))
        assertFalse(Zeitraum.um(Reiter.MONAT, mittwoch).hatNachher(mittwoch))
        // Zurück nur, solange der Verlauf vorher noch etwas hat.
        val woche = Zeitraum.um(Reiter.WOCHE, heute)
        assertTrue(woche.hatVorher(LocalDate.of(2026, 11, 3)))
        assertFalse(woche.hatVorher(LocalDate.of(2026, 11, 9)))
        assertFalse(woche.hatVorher(null))
        assertEquals(Reiter.WOCHE, Reiter.von(null))
        assertEquals(Reiter.MONAT, Reiter.von("MONAT"))
        assertEquals(Reiter.WOCHE, Reiter.von("Quatsch"))
    }

    @Test
    fun summeUndDurchschnittNurUeberTageMitDaten() {
        val w = Zeitraum(Reiter.WOCHE, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15)) // heute Donnerstag, Fr bis So kommen erst
        val donnerstag = LocalDate.of(2026, 11, 12)
        val verlauf = mapOf(
            w.von to tag(w.von, "com.beispiel.chat" to 3600L, "workout" to 1800L),
            // Dienstag: keine Daten (Handy aus).
            w.von.plusDays(2) to tag(w.von.plusDays(2), "com.beispiel.chat" to 1200L),
            donnerstag to tag(donnerstag, "com.beispiel.karten" to 600L, "workout" to 600L),
        )
        val a = auswerten(w, verlauf, donnerstag, datenSeit = LocalDate.of(2026, 10, 1))
        assertEquals(7800L, a.gesamt)
        assertEquals(3, a.belegt)
        assertEquals(4, a.vergangen)
        assertEquals(2600L, a.schnitt)
        assertEquals("Ø je Tag 43 Min. · 3 von 4 Tagen mit Daten", schnittText(a))
        // Keine Daten heißt null, nie 0; Freitag bis Sonntag kommen erst.
        assertEquals(listOf(5400L, null, 1200L, 1200L, null, null, null), a.tage.map { it.sekunden })
        assertEquals(listOf(false, false, false, false, true, true, true), a.tage.map { it.zukunft })
        assertEquals(listOf(w.von.plusDays(1)), a.luecken)
        assertEquals("Keine Daten: 10. Nov.", lueckenText(a))
        // Nach Zeit, Web-Apps unter eigenem Namen.
        assertEquals(listOf("Chat" to 4800L, "Workout" to 2400L, "Karten" to 600L), a.apps.map { it.name to it.sekunden })
        assertEquals("1 Std. 20 Min.", dauerText(a.apps[0].sekunden))
        assertEquals("62 %", anteilText(a.apps[0].sekunden, a.gesamt))
        assertEquals("2 Std. 10 Min.", gesamtText(a))
    }

    @Test
    fun ohneDatenStehtKeineDaten() {
        val w = Zeitraum.um(Reiter.WOCHE, heute)
        val a = auswerten(w, emptyMap(), heute, datenSeit = null)
        assertEquals("keine Daten", gesamtText(a))
        assertNull(a.schnitt)
        assertNull(schnittText(a))
        assertTrue(a.tage.all { it.sekunden == null })
        // Ein Tag mit Daten, aber ohne Apps (kurz nach Mitternacht), ist 0 – nicht „keine Daten“,
        // und auch nicht „unter 1 Min.“ (darunter steht „Heute noch nichts.“; das Widget sagt „0 Min.“).
        val t = Zeitraum.um(Reiter.TAG, heute)
        val leer = auswerten(t, mapOf(heute to Tag(heute, emptyList())), heute, heute)
        assertEquals("0 Min.", gesamtText(leer))
        assertEquals(1, leer.belegt)
        val kurz = auswerten(t, mapOf(heute to Tag(heute, listOf(Posten("com.beispiel.chat", "Chat", 30)))), heute, heute)
        assertEquals("unter 1 Min.", gesamtText(kurz))
        // TalkBack am Diagramm genauso: „0 Min.“, „keine Daten“.
        assertEquals("So 0 Min. (heute), Mo keine Daten", Balken.beschreibung(listOf(Tageswert(heute, 0L), Tageswert(heute.plusDays(1), null)), heute))
    }

    @Test
    fun datenSeitUndLueckenImMonat() {
        val m = Zeitraum.um(Reiter.MONAT, heute)
        val seit = LocalDate.of(2026, 11, 3)
        val verlauf = (3..15).filter { it !in listOf(7, 8, 11) }.associate { d ->
            val datum = LocalDate.of(2026, 11, d)
            datum to tag(datum, "com.beispiel.chat" to d * 600L)
        }
        val a = auswerten(m, verlauf, heute, seit)
        assertEquals("Daten seit 3. Nov.\nKeine Daten: 7.–8. Nov., 11. Nov.", lueckenText(a))
        assertEquals(10, a.belegt)
        assertEquals("ab dem 3.11.: die Tage davor zählen nicht als fehlend", 13, a.vergangen)
        assertEquals("Ø je Tag 1 Std. 31 Min. · 10 von 13 Tagen mit Daten", schnittText(a))
        // Ein Tag vor dem Beginn des Verlaufs: „keine Daten“ und seit wann es welche gibt.
        val tag = auswerten(Zeitraum.um(Reiter.TAG, LocalDate.of(2026, 11, 1)), verlauf, heute, seit)
        assertEquals("keine Daten", gesamtText(tag))
        assertEquals("Daten seit 3. Nov.", lueckenText(tag))
        // Beginnt der Verlauf vor dem Zeitraum, steht dazu nichts.
        assertEquals("Keine Daten: 11. Nov.", lueckenText(auswerten(Zeitraum.um(Reiter.WOCHE, heute), verlauf, heute, seit)))
    }

    @Test
    fun namenVomJuengstenTagUndAnteile() {
        val w = Zeitraum.um(Reiter.WOCHE, heute)
        val verlauf = mapOf(
            w.von to Tag(w.von, listOf(Posten("com.beispiel.alt", "Alter Name", 300L))),
            w.von.plusDays(1) to Tag(w.von.plusDays(1), listOf(Posten("com.beispiel.alt", "Neuer Name", 300L), Posten("boxenstopp", "boxenstopp", 5L))),
        )
        val a = auswerten(w, verlauf, heute, w.von)
        assertEquals(listOf("Neuer Name", "Boxenstopp"), a.apps.map { it.name })
        assertEquals("99 %", anteilText(600L, 605L))
        assertEquals("<1 %", anteilText(1L, 605L))
        assertEquals("", anteilText(1L, 0L))
    }

    @Test
    fun achseUndBedienungshilfe() {
        assertEquals("30 Min.", Balken.achsenText(1800))
        assertEquals("1 Std.", Balken.achsenText(3600))
        assertEquals("1:30 Std.", Balken.achsenText(5400))
        val w = listOf(
            Tageswert(LocalDate.of(2026, 11, 9), 3900L),
            Tageswert(LocalDate.of(2026, 11, 10), null),
            Tageswert(LocalDate.of(2026, 11, 11), 60L),
            Tageswert(LocalDate.of(2026, 11, 12), null, zukunft = true),
        )
        assertEquals("Mo 1 Std. 5 Min., Di keine Daten, Mi 1 Min. (heute)", Balken.beschreibung(w, LocalDate.of(2026, 11, 11)))
    }
    /**
     * Tobi, 15.11.: „Ich will auch auf ne eigene App klicken und dann deren
     * verteilung zb über die Woche sehen können“. Eine App über die Woche:
     * ihre Zeit je Tag, 0 an einem Tag mit Daten ohne sie, „keine Daten“ bleibt.
     */
    @Test
    fun eineAppUeberDieWoche() {
        val mo = LocalDate.of(2026, 11, 9)
        val verlauf = mapOf(
            mo to tag(mo, "org.mozilla.firefox" to 3600L, "com.beispiel.chat" to 1200L),
            mo.plusDays(1) to tag(mo.plusDays(1), "com.beispiel.chat" to 600L),
            // Mi ohne Daten
            mo.plusDays(3) to tag(mo.plusDays(3), "org.mozilla.firefox" to 1800L, "com.beispiel.chat" to 600L),
        )
        val a = auswerten(Zeitraum(Reiter.WOCHE, mo, mo.plusDays(6)), verlauf, mo.plusDays(3), mo, nur = "org.mozilla.firefox")
        assertEquals(listOf(3600L, 0L, null, 1800L, null, null, null), a.tage.map { it.sekunden })
        assertEquals(listOf(false, false, false, false, true, true, true), a.tage.map { it.zukunft })
        assertEquals(listOf("org.mozilla.firefox"), a.apps.map { it.schluessel })
        assertEquals(5400L, a.gesamt)
        assertEquals(7800L, a.alle)
        // Ø über die drei Tage mit Daten (auch den ohne Firefox), Anteil an allem.
        assertEquals(1800L, a.schnitt)
        assertEquals("Ø je Tag 30 Min. · 3 von 4 Tagen mit Daten\n69\u00A0% deiner Bildschirmzeit", schnittText(a))
        assertEquals("1 Std. 30 Min.", gesamtText(a))
        // Ohne Auswahl wie bisher: alle Apps, Summe je Tag.
        val alle = auswerten(Zeitraum(Reiter.WOCHE, mo, mo.plusDays(6)), verlauf, mo.plusDays(3), mo)
        assertEquals(listOf(4800L, 600L, null, 2400L, null, null, null), alle.tage.map { it.sekunden })
        assertEquals("Ø je Tag 43 Min. · 3 von 4 Tagen mit Daten", schnittText(alle))
    }

    /** Eine App, die im Zeitraum gar nicht lief: „0 Min.“, kein Anteil; am Tag nur der Anteil. */
    @Test
    fun eineAppOhneZeitUndAmTag() {
        val mo = LocalDate.of(2026, 11, 9)
        val verlauf = mapOf(mo to tag(mo, "com.beispiel.chat" to 1200L, "org.mozilla.firefox" to 300L))
        val leer = auswerten(Zeitraum(Reiter.WOCHE, mo, mo.plusDays(6)), verlauf, mo, mo, nur = "com.beispiel.karten")
        assertTrue(leer.apps.isEmpty())
        assertEquals("0 Min.", gesamtText(leer))
        assertEquals("Ø je Tag 0 Min.", schnittText(leer))
        val amTag = auswerten(Zeitraum.um(Reiter.TAG, mo), verlauf, mo, mo, nur = "org.mozilla.firefox")
        assertEquals("5 Min.", gesamtText(amTag))
        assertEquals("20\u00A0% deiner Bildschirmzeit", schnittText(amTag))
        assertNull(schnittText(auswerten(Zeitraum.um(Reiter.TAG, mo), verlauf, mo, mo)))
    }
}
