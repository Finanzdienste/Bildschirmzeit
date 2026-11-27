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
        // Tobi, 27.11.: „WhatsApp ist unproduktiv.“ – die App und WhatsApp Web.
        assertTrue(UnproduktivRegeln.vorgabe("com.whatsapp"))
        assertTrue(UnproduktivRegeln.vorgabe("com.whatsapp", ApplicationInfo.CATEGORY_SOCIAL))
        assertTrue(UnproduktivRegeln.vorgabe(WEBSEITE + domainAus("https://web.whatsapp.com/")!!))
        // Andere Messenger nie von selbst – auch wenn sie sich bei Android „sozial“ nennen.
        assertFalse(UnproduktivRegeln.vorgabe("org.thoughtcrime.securesms", ApplicationInfo.CATEGORY_SOCIAL))
        assertFalse(UnproduktivRegeln.vorgabe("org.telegram.messenger", ApplicationInfo.CATEGORY_SOCIAL))
        assertFalse(UnproduktivRegeln.vorgabe("com.whatsapp.w4b", ApplicationInfo.CATEGORY_SOCIAL))
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
            Posten("org.thoughtcrime.securesms", "Signal", 1200L),
            Posten("workout", "Workout", 1800L),
        )
        val vorgabe = { k: String -> UnproduktivRegeln.vorgabe(k) }
        assertEquals(3000L + 120L + 900L, unproduktivSekunden(posten, vorgabe, Partnersuche::app))
        // WhatsApp zählt mit, auch über WhatsApp Web in Firefox.
        val mitWhatsApp = posten + Posten("com.whatsapp", "WhatsApp", 1500L) + Posten(seitenSchluessel(ff, "whatsapp.com"), "whatsapp.com", 60L)
        assertEquals(3000L + 120L + 900L + 1500L + 60L, unproduktivSekunden(mitWhatsApp, vorgabe, Partnersuche::app))
        // „Firefox“ selbst als unproduktiv gestellt: dazu Firefox und die Seiten ohne Namen.
        val mitFirefox = { k: String -> k == ff || UnproduktivRegeln.vorgabe(k) }
        assertEquals(3000L + 120L + 900L + 600L + 300L, unproduktivSekunden(posten, mitFirefox, Partnersuche::app))
        // „Sonstiges“ nicht: ohne die Dating-App.
        val ohneSonstiges = { k: String -> k != SONSTIGES && UnproduktivRegeln.vorgabe(k) }
        assertEquals(3000L + 120L, unproduktivSekunden(posten, ohneSonstiges, Partnersuche::app))
    }

    /**
     * Tobi, 27.11., auf die Frage, ob die diskreten Seiten als unproduktiv zählen
     * sollen: „Jo“. Sie zählen wie „Sonstiges“, stehen aber weiter nur bei
     * „Firefox“ und nie mit Namen – auch nicht ab 5 Minuten. Suche und private
     * Tabs (OHNE_DOMAIN) zählen nicht. Gegen v34 rot.
     */
    @Test
    fun diskreteSeitenZaehlenWieSonstiges() {
        val posten = listOf(
            Posten(seitenSchluessel(ff, DISKRET_DOMAIN), FIREFOX_NAME, 900L),
            Posten(seitenSchluessel(CHROME, DISKRET_DOMAIN), FIREFOX_NAME, 300L),
            Posten(seitenSchluessel(ff, OHNE_DOMAIN), FIREFOX_NAME, 600L),
            Posten(ff, "Firefox", 200L),
        )
        val vorgabe = { k: String -> UnproduktivRegeln.vorgabe(k) }
        assertEquals(1200L, unproduktivSekunden(posten, vorgabe))
        // „Sonstiges“ in der Statistik als nicht unproduktiv gestellt: die diskreten Seiten auch nicht.
        assertEquals(0L, unproduktivSekunden(posten, { k -> k != SONSTIGES && UnproduktivRegeln.vorgabe(k) }))
        // Angezeigt nur als „Firefox“.
        assertEquals(listOf(FIREFOX_NAME to 2000L), webseitenZusammenfassen(posten).map { it.name to it.sekunden })
        assertTrue(einzelneDomains(posten.associate { it.schluessel to it.sekunden }).isEmpty())
        // In der Statistik genauso: ein Eintrag „Firefox“, davon unproduktiv die diskreten 20 Minuten.
        val tag = LocalDate.of(2026, 11, 27)
        val verlauf = mapOf(tag to Tag(tag, sortiert(posten), fertig = true))
        val a = auswerten(Zeitraum.um(Reiter.TAG, tag), verlauf, tag, tag, unproduktiv = vorgabe)
        assertEquals(listOf(FIREFOX_NAME), a.apps.map { it.name })
        assertEquals(1200L, a.unproduktiv)
    }

    /**
     * Tobi, 27.11.: „Meinte ich nicht ich will oben so Buttons wo ich zwischen
     * produktiv und unproduktiv und so wechseln kann? Also in der Ansicht dass
     * mir zb nur produktive angezeigt werden“ – in der Statistik der App je
     * Ansicht nur ihre Apps, ihre Zeit je Tag und ihr Ø, dahinter „von …“.
     * „Firefox“ steht mit seinem Teil in beiden. Gegen v35 rot.
     */
    @Test
    fun statistikNurProduktivOderUnproduktiv() {
        val tag = LocalDate.of(2026, 11, 24)
        val heute = tag.plusDays(1)
        val verlauf = (0L..1L).associate { d ->
            val t = tag.plusDays(d)
            t to Tag(
                t,
                sortiert(
                    listOf(
                        Posten("com.google.android.youtube", "YouTube", 1800L),
                        Posten("com.beispiel.chat", "Chat", 3600L),
                        // „Firefox“ mit beiden Teilen: eine diskrete Seite (unproduktiv) und eine Suche (nicht).
                        Posten(seitenSchluessel(ff, DISKRET_DOMAIN), FIREFOX_NAME, 600L),
                        Posten(seitenSchluessel(ff, OHNE_DOMAIN), FIREFOX_NAME, 300L),
                    ),
                ),
                fertig = true,
            )
        }
        val ist = { k: String -> UnproduktivRegeln.vorgabe(k) }
        fun woche(f: WidgetFilter, nur: String? = null) =
            auswerten(Zeitraum.um(Reiter.WOCHE, heute), verlauf, heute, tag, nur = nur, unproduktiv = ist, filter = f)
        val alle = woche(WidgetFilter.ALLE)
        assertEquals(12600L, alle.gesamt)
        val un = woche(WidgetFilter.UNPRODUKTIV)
        assertEquals(listOf("YouTube" to 3600L, "Firefox" to 1200L), un.apps.map { it.name to it.sekunden })
        assertEquals(12600L, un.alle)
        assertEquals(listOf(2400L, 2400L), un.tage.mapNotNull { it.sekunden })
        assertEquals("von 3 Std. 30 Min. (38\u00A0%)\nØ je Tag unproduktiv: 40 Min.", schnittText(un))
        val pro = woche(WidgetFilter.PRODUKTIV)
        assertEquals(listOf("Chat" to 7200L, "Firefox" to 600L), pro.apps.map { it.name to it.sekunden })
        assertEquals(listOf(3900L, 3900L), pro.tage.mapNotNull { it.sekunden })
        assertEquals("von 3 Std. 30 Min. (62\u00A0%)\nØ je Tag produktiv: 1 Std. 5 Min.", schnittText(pro))
        // Zusammen wieder alles – und „davon unproduktiv“ unter „Alle“ ist dieselbe Zahl.
        assertEquals(alle.gesamt, un.gesamt + pro.gesamt)
        assertEquals(un.gesamt, alle.unproduktiv)
        assertFalse(schnittText(un)!!.contains("davon"))
        // Eine App aus der Ansicht gewählt: nur ihr Teil („Firefox“ unproduktiv: die diskrete Seite).
        val nurFirefox = woche(WidgetFilter.UNPRODUKTIV, nur = ff)
        assertEquals(listOf(600L, 600L), nurFirefox.tage.mapNotNull { it.sekunden })
        assertEquals("Ø je Tag 10 Min.\n10\u00A0% deiner Bildschirmzeit", schnittText(nurFirefox))
        // Nichts davon im Zeitraum: keine Apps, „von …“ mit 0 % (nicht „<1 %“).
        val nurChat = mapOf(heute to Tag(heute, sortiert(listOf(Posten("com.beispiel.chat", "Chat", 600L))), fertig = true))
        val leer = auswerten(Zeitraum.um(Reiter.TAG, heute), nurChat, heute, heute, unproduktiv = ist, filter = WidgetFilter.UNPRODUKTIV)
        assertTrue(leer.apps.isEmpty())
        assertEquals(listOf(0L), leer.tage.mapNotNull { it.sekunden })
        assertEquals("von 10 Min. (0\u00A0%)", schnittText(leer))
        // Ein Satz statt einer leeren Liste – heute, an einem anderen Tag, in Woche und Monat; nie unter „Alle“.
        assertEquals("Heute noch nichts Unproduktives.", filterLeerText(leer))
        val gestern = auswerten(Zeitraum.um(Reiter.TAG, tag), verlauf, heute, tag, unproduktiv = { false }, filter = WidgetFilter.UNPRODUKTIV)
        assertEquals("An diesem Tag nichts Unproduktives.", filterLeerText(gestern))
        val wocheLeer = auswerten(Zeitraum.um(Reiter.WOCHE, heute), verlauf, heute, tag, unproduktiv = { true }, filter = WidgetFilter.PRODUKTIV)
        assertEquals("In diesem Zeitraum nichts Produktives.", filterLeerText(wocheLeer))
        assertEquals(null, filterLeerText(pro))
        assertEquals(null, filterLeerText(auswerten(Zeitraum.um(Reiter.TAG, heute), nurChat, heute, heute, unproduktiv = ist)))
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
