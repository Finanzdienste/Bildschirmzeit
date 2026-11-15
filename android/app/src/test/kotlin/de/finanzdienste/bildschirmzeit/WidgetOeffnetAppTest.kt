package de.finanzdienste.bildschirmzeit

import android.app.AppOpsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Beide Teile zusammen: Tobi tippt aufs Widget (gezeichnet von WidgetAnsicht,
 * wie der Startbildschirm es zeigt), und genau die Absicht, die Android dabei
 * startet, öffnet die App (MainActivity) – mit der Statistik oben bzw. mit der
 * Update-Karte. Tobi, 15.11.: „Wenn ich auf das widget tippe soll ich mir auch
 * die Wochen und Monatsnutzung nach App aufgeteilt und so anzeigen lassen
 * können“. Erfundener Verlauf (3.–14. Nov., ohne 7. und 8.), heute Sa., 14.11.,
 * 21:40.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class WidgetOeffnetAppTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val stand = ZonedDateTime.of(2026, 11, 14, 21, 40, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val heutePosten = listOf(
        Posten("boxenstopp", "Boxenstopp", 69 * 60L),
        Posten("com.beispiel.chat", "WhatsApp", 54 * 60L),
        Posten("workout", "Workout", 41 * 60L),
    )
    private val ergebnis = Ergebnis(
        erlaubt = true,
        posten = heutePosten,
        stand = stand,
        web = WebDaten(emptyMap(), mapOf("workout" to stand), ohneNetz = false, gelesen = stand),
    )

    @Before
    fun vorbereiten() {
        Netz.gesperrt = true
        try {
            WorkManager.initialize(kontext, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
        erfundenerVerlauf(kontext, heutePosten)
        // Ohne Erlaubnis schreibt die eigene Rechnung der App nichts in den Verlauf (sonst stünde dort der echte heutige Tag).
        shadowOf(kontext.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            kontext.packageName,
            AppOpsManager.MODE_IGNORED,
        )
    }

    @Test
    fun antippenOeffnetDieAppMitDerStatistik() {
        // Das Widget in Tobis Größe, über den Startbildschirm gezeichnet.
        val awm = AppWidgetManager.getInstance(kontext)
        shadowOf(awm).bindAppWidgetId(7, ComponentName(kontext, BildschirmzeitWidget::class.java))
        awm.updateAppWidgetOptions(
            7,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 400)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 201)
            },
        )
        WidgetAnsicht.zeigen(kontext, ergebnis, intArrayOf(7))
        val widget = shadowOf(awm).getViewFor(7)
        legen(widget, 400f, 201f)
        assertEquals("Heute", widget.findViewById<TextView>(R.id.titel).text.toString())
        val gerechnetVorher = Speicher(kontext).gerechnet

        val gestartet = tippen(widget)
        assertEquals(Ziel.STATISTIK, gestartet.getStringExtra(Ziel.EXTRA))

        // Genau diese Absicht startet die App.
        val steuerung = Robolectric.buildActivity(MainActivity::class.java, gestartet).setup()
        warten()
        val a = steuerung.get()
        // Die App rechnet beim Öffnen selbst und zeichnet das Widget mit – so bringt Antippen frische Zahlen.
        assertNotEquals("die App hat beim Öffnen nicht gerechnet", gerechnetVorher, Speicher(kontext).gerechnet)
        a.zeigen(ergebnis)
        a.uhrStellen { stand }
        warten()

        assertEquals(listOf(R.id.kopf_titel, R.id.statistik_karte), a.reihe().take(2))
        assertTrue("beim ersten Mal die Woche", a.findViewById<View>(R.id.reiter_woche).isSelected)
        assertEquals("9.–15. Nov.", a.text(R.id.zeitraum_titel))
        assertTrue(a.text(R.id.schnitt).startsWith("Ø je Tag "))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.balken).visibility)
        val liste = a.findViewById<ViewGroup>(R.id.app_liste)
        assertTrue(liste.childCount >= 3)
        val namen = (0 until liste.childCount).map { liste.getChildAt(it).findViewById<TextView>(R.id.name).text.toString() }
        assertTrue(namen.containsAll(listOf("Boxenstopp", "WhatsApp", "Workout")))
        assertEquals(0, a.findViewById<ScrollView>(R.id.wurzel).scrollY)

        // Tobi wählt den Monat, scrollt nach unten, geht zum Startbildschirm und tippt wieder aufs Widget:
        // Die laufende App bekommt das Ziel (onNewIntent), rechnet wieder und zeigt den gemerkten Reiter oben.
        a.findViewById<View>(R.id.reiter_monat).performClick()
        auslegen(a)
        a.findViewById<ScrollView>(R.id.wurzel).scrollTo(0, 2000)
        assertTrue(a.findViewById<ScrollView>(R.id.wurzel).scrollY > 0)
        steuerung.pause().stop()
        val gerechnetErst = Speicher(kontext).gerechnet
        Thread.sleep(5)
        steuerung.newIntent(tippen(widget))
        // Android stellt das Ziel vor onResume zu: schon dann wieder ganz oben, die Statistik zuerst.
        assertEquals(0, a.findViewById<ScrollView>(R.id.wurzel).scrollY)
        assertEquals(R.id.statistik_karte, a.reihe()[1])
        steuerung.start().resume()
        warten()
        assertNotEquals("die App hat beim Wiederkommen nicht gerechnet", gerechnetErst, Speicher(kontext).gerechnet)
        a.zeigen(ergebnis)
        warten()
        assertTrue(a.findViewById<View>(R.id.reiter_monat).isSelected)
        assertEquals("November 2026", a.text(R.id.zeitraum_titel))
        assertEquals(R.id.statistik_karte, a.reihe()[1])
    }

    @Test
    fun mitUpdateHinweisOeffnetEsDieAppBeiDerUpdateKarte() {
        val widget = aufgebaut(WidgetAnsicht.ansicht(kontext, ergebnis.copy(updateHinweis = true), 400f, 201f), 400f, 201f)
        assertEquals("Update: antippen", widget.findViewById<TextView>(R.id.titel).text.toString())
        val gestartet = tippen(widget)
        assertEquals(Ziel.UPDATE, gestartet.getStringExtra(Ziel.EXTRA))

        val steuerung = Robolectric.buildActivity(MainActivity::class.java, gestartet).setup()
        warten()
        val a = steuerung.get()
        a.zeigen(ergebnis)
        a.uhrStellen { stand }
        warten()
        // Die Update-Karte direkt unter dem Titel, zu sehen, ohne zu scrollen; die Statistik gleich danach.
        assertEquals(listOf(R.id.kopf_titel, R.id.update_karte, R.id.statistik_karte), a.reihe().take(3))
        val karte = a.findViewById<View>(R.id.update_karte)
        assertEquals(View.VISIBLE, karte.visibility)
        // „Automatische Updates“, mit „ ✓“, wenn Schritt 9 eingerichtet ist (hängt vom nachgebauten Gerät ab).
        assertTrue(a.text(R.id.update_titel).startsWith(kontext.getString(R.string.update_titel)))
        auslegen(a)
        val bildschirm = 832 * a.resources.displayMetrics.density
        assertEquals(0, a.findViewById<ScrollView>(R.id.wurzel).scrollY)
        assertTrue("Update-Karte nicht auf dem ersten Bildschirm (oben ${karte.top} px)", karte.top + karte.height / 2 < bildschirm)

        // Die App ist offen, das Update installiert sich nicht von selbst, Tobi tippt danach (ohne Hinweis) aufs Widget:
        // wieder die Statistik oben, die Update-Karte an ihrem gewohnten Platz.
        val ohne = aufgebaut(WidgetAnsicht.ansicht(kontext, ergebnis, 400f, 201f), 400f, 201f)
        steuerung.newIntent(tippen(ohne))
        warten()
        assertEquals(R.id.statistik_karte, a.reihe()[1])
        assertFalse(a.reihe().take(3).contains(R.id.update_karte))
    }

    /** Tippt aufs Widget und gibt zurück, was Android startet: genau MainActivity. */
    private fun tippen(widget: View): Intent {
        widget.findViewById<View>(android.R.id.background).performClick()
        val gestartet = shadowOf(kontext).nextStartedActivity
        assertNotNull("Antippen startet nichts", gestartet)
        assertEquals(ComponentName(kontext, MainActivity::class.java), gestartet.component)
        return gestartet
    }

    private fun MainActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun MainActivity.reihe(): List<Int> {
        val inhalt = findViewById<ViewGroup>(R.id.inhalt)
        return (0 until inhalt.childCount).map { inhalt.getChildAt(it).id }
    }

    /** Der App-Bildschirm in der Größe des S24 (384 × 832 dp) ausgelegt – sonst kann man in Robolectric nicht scrollen. */
    private fun auslegen(a: MainActivity) {
        val dichte = a.resources.displayMetrics.density
        val wurzel = a.findViewById<ScrollView>(R.id.wurzel)
        val b = (384 * dichte).roundToInt()
        val h = (832 * dichte).roundToInt()
        wurzel.measure(View.MeasureSpec.makeMeasureSpec(b, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        wurzel.layout(0, 0, b, h)
    }

    private fun warten() {
        repeat(20) {
            Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun aufgebaut(ansicht: android.widget.RemoteViews, breite: Float, hoehe: Float): View =
        ansicht.apply(kontext, FrameLayout(kontext)).also { legen(it, breite, hoehe) }

    private fun legen(view: View, breite: Float, hoehe: Float) {
        val dichte = kontext.resources.displayMetrics.density
        val b = (breite * dichte).roundToInt()
        val h = (hoehe * dichte).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(b, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, b, h)
    }
}
