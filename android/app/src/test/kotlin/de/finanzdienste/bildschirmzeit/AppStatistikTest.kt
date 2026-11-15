package de.finanzdienste.bildschirmzeit

import android.app.AppOpsManager
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.window.OnBackInvokedCallback
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tobi, 15.11.: „Wenn ich auf das widget tippe soll ich mir auch die Wochen
 * und Monatsnutzung nach App aufgeteilt und so anzeigen lassen können“. Die
 * Statistik oben in der App, auf einem nachgebauten Gerät mit erfundenem
 * Verlauf (3.–14. Nov., ohne 7. und 8.), heute Samstag 14.11., 21:40.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class AppStatistikTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val stand = ZonedDateTime.of(2026, 11, 14, 21, 40, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val heute = LocalDate.of(2026, 11, 14)
    private val heutePosten = listOf(Posten("workout", "Workout", 54 * 60L), Posten("com.beispiel.chat", "WhatsApp", 41 * 60L))

    @Before
    fun vorbereiten() {
        Netz.gesperrt = true
        try {
            WorkManager.initialize(kontext, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
        erfundenerVerlauf(kontext, heutePosten)
        // Ohne Erlaubnis rechnet die App selbst nichts in den Verlauf (sonst stünde dort der echte heutige Tag).
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            RuntimeEnvironment.getApplication().packageName,
            AppOpsManager.MODE_IGNORED,
        )
    }

    private fun ergebnis(erlaubt: Boolean = true) = Ergebnis(
        erlaubt = erlaubt,
        posten = if (erlaubt) heutePosten else emptyList(),
        stand = stand,
        web = WebDaten(emptyMap(), mapOf("workout" to stand), ohneNetz = false, gelesen = stand),
    )

    private fun ziel(z: String) = Intent(kontext, MainActivity::class.java).putExtra(Ziel.EXTRA, z)

    /** Die App, wie das Widget sie öffnet – mit fester Uhr und erlaubtem Zugriff. */
    private fun oeffnen(intent: Intent? = ziel(Ziel.STATISTIK)): ActivityController<MainActivity> {
        val steuerung = if (intent == null) Robolectric.buildActivity(MainActivity::class.java) else Robolectric.buildActivity(MainActivity::class.java, intent)
        steuerung.setup()
        warten()
        steuerung.get().zeigen(ergebnis())
        steuerung.get().uhrStellen { stand }
        warten()
        return steuerung
    }

    private fun MainActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun MainActivity.reihe(): List<Int> {
        val inhalt = findViewById<ViewGroup>(R.id.inhalt)
        return (0 until inhalt.childCount).map { inhalt.getChildAt(it).id }
    }

    private fun warten() {
        repeat(20) {
            Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun summe(vararg tage: Int): Long =
        Verlauf(kontext).lesen(heute.withDayOfMonth(tage.min()), heute.withDayOfMonth(tage.max())).filterKeys { it.dayOfMonth in tage }.values.sumOf { it.gesamt }

    @Test
    fun statistikObenBeimErstenMalDieWoche() {
        val a = oeffnen().get()
        assertEquals(listOf(R.id.kopf_titel, R.id.statistik_karte, R.id.erlaubnis_karte, R.id.web_karte, R.id.widget_karte, R.id.update_karte), a.reihe().take(6))
        assertTrue(a.findViewById<View>(R.id.reiter_woche).isSelected)
        assertFalse(a.findViewById<View>(R.id.reiter_tag).isSelected)
        assertEquals("9.–15. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals(dauerText(summe(9, 10, 11, 12, 13, 14)), a.text(R.id.gesamt))
        assertTrue(a.text(R.id.schnitt).startsWith("Ø je Tag "))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.balken).visibility)
        // Nicht über heute hinaus, zurück schon.
        assertFalse(a.findViewById<View>(R.id.weiter).isEnabled)
        assertTrue(a.findViewById<View>(R.id.zurueck).isEnabled)
        // Alle Apps der Woche nach Zeit, mit Anteil; die Web-Apps unter eigenem Namen.
        val liste = a.findViewById<ViewGroup>(R.id.app_liste)
        assertEquals(8, liste.childCount)
        val namen = (0 until liste.childCount).map { liste.getChildAt(it).findViewById<TextView>(R.id.name).text.toString() }
        assertTrue(namen.containsAll(listOf("Workout", "Boxenstopp", "Freizeit", "Chrome")))
        assertTrue(liste.getChildAt(0).findViewById<TextView>(R.id.anteil).text.endsWith("%"))
        assertEquals("Chrome ohne Boxenstopp, Freizeit und Workout. Stand\u00A021:40.", a.text(R.id.statistik_hinweis))
    }

    @Test
    fun gemerkterReiterUndZurueckZumJetzigenZeitraum() {
        val steuerung = oeffnen()
        val a = steuerung.get()
        a.findViewById<View>(R.id.reiter_monat).performClick()
        warten()
        assertEquals("MONAT", Speicher(kontext).reiter)
        assertEquals("November 2026", a.text(R.id.zeitraum_titel))
        assertEquals("Daten seit 3. Nov.\nKeine Daten: 7.–8. Nov.", a.text(R.id.luecken))
        assertTrue(a.text(R.id.schnitt).endsWith("· 10 von 14 Tagen mit Daten"))
        // Zurück in den Oktober: dort keine Daten, und weiter zurück geht es nicht.
        a.findViewById<View>(R.id.zurueck).performClick()
        warten()
        assertEquals("Oktober 2026", a.text(R.id.zeitraum_titel))
        assertEquals("keine Daten", a.text(R.id.gesamt))
        assertFalse(a.findViewById<View>(R.id.zurueck).isEnabled)
        assertTrue(a.findViewById<View>(R.id.weiter).isEnabled)

        // Das Widget öffnet die App wieder: der gemerkte Reiter, der jetzige Zeitraum.
        steuerung.newIntent(ziel(Ziel.STATISTIK))
        warten()
        assertEquals("November 2026", a.text(R.id.zeitraum_titel))
        // Auch eine neue App (nach dem Schließen) zeigt den Monat.
        val neu = oeffnen().get()
        assertTrue(neu.findViewById<View>(R.id.reiter_monat).isSelected)
        assertEquals("November 2026", neu.text(R.id.zeitraum_titel))
    }

    @Test
    fun ueberMitternachtOffenRueckenWocheUndTagMit() {
        val steuerung = oeffnen()
        val a = steuerung.get()
        var uhr = stand
        a.uhrStellen { uhr }
        warten()
        assertEquals("9.–15. Nov.", a.text(R.id.zeitraum_titel))
        // Montag früh, die App lag offen im Hintergrund: die neue Woche.
        uhr = stand + 34 * 3_600_000L
        steuerung.pause().resume()
        warten()
        assertEquals("16.–22. Nov.", a.text(R.id.zeitraum_titel))
        // Ein vergangener Tag, den Tobi sich ansieht, bleibt stehen.
        a.tagZeigen(LocalDate.of(2026, 11, 12))
        uhr += 24 * 3_600_000L
        steuerung.pause().resume()
        warten()
        assertEquals("Do., 12. Nov.", a.text(R.id.zeitraum_titel))
    }

    @Test
    fun wocheZurueckMitLuecke() {
        val a = oeffnen().get()
        a.findViewById<View>(R.id.zurueck).performClick()
        warten()
        assertEquals("2.–8. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals("Daten seit 3. Nov.\nKeine Daten: 7.–8. Nov.", a.text(R.id.luecken))
        assertEquals(dauerText(summe(3, 4, 5, 6)), a.text(R.id.gesamt))
        assertTrue(a.text(R.id.schnitt).endsWith("· 4 von 7 Tagen mit Daten"))
        // Vor dem 3.11. hat der Verlauf nichts: nicht weiter zurück.
        assertFalse(a.findViewById<View>(R.id.zurueck).isEnabled)
        // Vergangene Woche: kein „Stand“ von heute darunter.
        assertEquals(View.GONE, a.findViewById<View>(R.id.statistik_hinweis).visibility)
        a.findViewById<View>(R.id.weiter).performClick()
        warten()
        assertEquals("9.–15. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals(View.GONE, a.findViewById<View>(R.id.luecken).visibility)
    }

    @Test
    fun tippAufEinenBalkenZeigtDenTag() {
        val a = oeffnen().get()
        val balken = a.findViewById<Balken>(R.id.balken)
        if (balken.width == 0) {
            val inhalt = a.findViewById<ViewGroup>(R.id.wurzel).getChildAt(0)
            val breite = (384 * a.resources.displayMetrics.density).toInt()
            inhalt.measure(View.MeasureSpec.makeMeasureSpec(breite, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            inhalt.layout(0, 0, breite, inhalt.measuredHeight)
        }
        val donnerstag = LocalDate.of(2026, 11, 12)
        val x = balken.mitteVon(donnerstag)!!
        val y = balken.height / 2f
        val t = SystemClock.uptimeMillis()
        balken.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        balken.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        warten()
        assertTrue(a.findViewById<View>(R.id.reiter_tag).isSelected)
        assertEquals("Do., 12. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals(dauerText(summe(12)), a.text(R.id.gesamt))
        assertEquals(View.GONE, a.findViewById<View>(R.id.balken).visibility)
        assertEquals(Verlauf(kontext).lesen(donnerstag)!!.posten.size, a.findViewById<ViewGroup>(R.id.app_liste).childCount)
        // Gemerkt wird nur ein von Hand gewählter Reiter.
        assertEquals(null, Speicher(kontext).reiter)
        // Der Sonntag kommt erst: kein Tipp.
        assertEquals(null, balken.tagBei(balken.width - 1f))
    }

    @Test
    fun einTagOhneDatenIstNieNull() {
        val a = oeffnen().get()
        a.tagZeigen(LocalDate.of(2026, 11, 7))
        warten()
        assertEquals("Sa., 7. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals("keine Daten", a.text(R.id.gesamt))
        assertEquals(0, a.findViewById<ViewGroup>(R.id.app_liste).childCount)
        a.tagZeigen(LocalDate.of(2026, 11, 1))
        warten()
        assertEquals("keine Daten", a.text(R.id.gesamt))
        assertEquals("Daten seit 3. Nov.", a.text(R.id.luecken))
        // Heute: die Rechnung von eben, mit Stand.
        a.tagZeigen(heute)
        warten()
        assertEquals("Heute", a.text(R.id.zeitraum_titel))
        assertEquals(dauerText(95 * 60L), a.text(R.id.gesamt))
        assertEquals("Chrome ohne Boxenstopp, Freizeit und Workout. Stand\u00A021:40.", a.text(R.id.statistik_hinweis))
    }

    /**
     * Drehen oder der Wechsel hell/dunkel nach Zeitplan baut die Activity neu.
     * Der Zeitraum, den Tobi gerade ansieht – eine frühere Woche, ein Tag vom
     * Balken –, bleibt; vorher sprang die App auf die jetzige Woche zurück.
     * (Nach dem Neubau gilt die echte Uhr, deshalb ohne Jahreszahl verglichen.)
     */
    @Test
    fun neuGebautBleibtDerGewaehlteZeitraum() {
        val steuerung = oeffnen()
        steuerung.get().findViewById<View>(R.id.zurueck).performClick()
        warten()
        assertEquals("2.–8. Nov.", steuerung.get().text(R.id.zeitraum_titel))
        steuerung.recreate()
        warten()
        assertTrue(steuerung.get().text(R.id.zeitraum_titel), steuerung.get().text(R.id.zeitraum_titel).startsWith("2.–8. Nov."))
        assertTrue(steuerung.get().findViewById<View>(R.id.reiter_woche).isSelected)

        steuerung.get().tagZeigen(LocalDate.of(2026, 11, 4))
        warten()
        steuerung.recreate()
        warten()
        val a = steuerung.get()
        assertTrue(a.text(R.id.zeitraum_titel), a.text(R.id.zeitraum_titel).startsWith("Mi., 4. Nov."))
        assertTrue(a.findViewById<View>(R.id.reiter_tag).isSelected)
        // Auch nach dem Neubau führt Zurück in die Woche, aus der der Balken kam.
        assertTrue(a.zurueckZumZeitraum())
        warten()
        assertTrue(a.text(R.id.zeitraum_titel), a.text(R.id.zeitraum_titel).startsWith("2.–8. Nov."))

        // Mit Ziel „update“ geöffnet und dann gedreht: Die Update-Karte bleibt oben.
        val update = oeffnen(ziel(Ziel.UPDATE))
        update.recreate()
        warten()
        assertEquals(listOf(R.id.kopf_titel, R.id.update_karte), update.get().reihe().take(2))
    }

    private fun MainActivity.zeile(name: String): View {
        val liste = findViewById<ViewGroup>(R.id.app_liste)
        return (0 until liste.childCount).map { liste.getChildAt(it) }.first { it.findViewById<TextView>(R.id.name).text == name }
    }

    private fun wochenSumme(schluessel: String, vararg tage: Int): Long =
        Verlauf(kontext).lesen(heute.withDayOfMonth(tage.min()), heute.withDayOfMonth(tage.max()))
            .filterKeys { it.dayOfMonth in tage }.values.sumOf { t -> t.posten.filter { it.schluessel == schluessel }.sumOf { it.sekunden } }

    /**
     * Tobi, 15.11.: „Ich will auch auf ne eigene App klicken und dann deren
     * verteilung zb über die Woche sehen können“. Tipp auf eine Zeile: nur diese
     * App – ihr Name oben, ihre Zeit, Ø und Anteil, die Balken je Tag, keine
     * Liste. Reiter und ‹ › behalten sie, „Alle Apps“ führt zurück.
     */
    @Test
    fun appAntippenZeigtIhreVerteilung() {
        val a = oeffnen().get()
        a.zeile("Workout").performClick()
        warten()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.app_kopf).visibility)
        assertEquals("Workout", a.text(R.id.app_kopf_name))
        assertEquals(View.GONE, a.findViewById<View>(R.id.app_liste).visibility)
        assertEquals("9.–15. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals(dauerText(wochenSumme("workout", 9, 10, 11, 12, 13, 14)), a.text(R.id.gesamt))
        assertTrue(a.text(R.id.schnitt), a.text(R.id.schnitt).startsWith("Ø je Tag "))
        assertTrue(a.text(R.id.schnitt), a.text(R.id.schnitt).endsWith("% deiner Bildschirmzeit"))
        val balken = a.findViewById<Balken>(R.id.balken)
        assertEquals(View.VISIBLE, balken.visibility)
        // Die Balken sind die Tage dieser App (TalkBack liest sie vor).
        val mi = Verlauf(kontext).lesen(heute.withDayOfMonth(11), heute.withDayOfMonth(11)).values.single().posten.single { it.schluessel == "workout" }.sekunden
        assertTrue(balken.contentDescription.toString(), balken.contentDescription.contains("Mi ${summeText(mi)}"))

        // Monat und eine Woche zurück: weiter nur Workout.
        a.findViewById<View>(R.id.reiter_monat).performClick()
        warten()
        assertEquals("November 2026", a.text(R.id.zeitraum_titel))
        assertEquals("Workout", a.text(R.id.app_kopf_name))
        assertEquals(dauerText(wochenSumme("workout", 3, 4, 5, 6, 9, 10, 11, 12, 13, 14)), a.text(R.id.gesamt))
        a.findViewById<View>(R.id.reiter_woche).performClick()
        warten()
        a.findViewById<View>(R.id.zurueck).performClick()
        warten()
        assertEquals("2.–8. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals(dauerText(wochenSumme("workout", 3, 4, 5, 6)), a.text(R.id.gesamt))

        // „Alle Apps“: wieder die ganze Liste, kein Kopf.
        a.findViewById<View>(R.id.alle_apps).performClick()
        warten()
        assertEquals(View.GONE, a.findViewById<View>(R.id.app_kopf).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.app_liste).visibility)
        assertEquals(dauerText(summe(3, 4, 5, 6)), a.text(R.id.gesamt))
        assertFalse(a.text(R.id.schnitt).contains("deiner Bildschirmzeit"))
    }

    /** Zurück: vom Tag (Balken) in die Woche der App, dann zu allen Apps, dann schließt die App wie gewohnt. */
    @Test
    fun zurueckVonDerAppZuAllen() {
        wieAndroid16()
        val a = oeffnen().get()
        assertNotSame(a.zurueckRuf, obersterRueckruf(a))
        a.zeile("WhatsApp").performClick()
        warten()
        assertSame(a.zurueckRuf, obersterRueckruf(a))
        a.tagZeigen(LocalDate.of(2026, 11, 12))
        warten()
        assertEquals("Do., 12. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals("WhatsApp", a.text(R.id.app_kopf_name))
        obersterRueckruf(a)!!.onBackInvoked()
        warten()
        assertEquals("9.–15. Nov.", a.text(R.id.zeitraum_titel))
        assertEquals("WhatsApp", a.text(R.id.app_kopf_name))
        obersterRueckruf(a)!!.onBackInvoked()
        warten()
        assertEquals(View.GONE, a.findViewById<View>(R.id.app_kopf).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.app_liste).visibility)
        assertNotSame(a.zurueckRuf, obersterRueckruf(a))
        assertFalse(a.zurueckZumZeitraum())
    }

    /** Drehen behält die App; ein neues Antippen des Widgets zeigt wieder alle. */
    @Test
    fun appUeberstehtDrehenAberNichtDasWidget() {
        val steuerung = oeffnen()
        steuerung.get().zeile("Freizeit").performClick()
        warten()
        steuerung.recreate()
        warten()
        assertEquals("Freizeit", steuerung.get().text(R.id.app_kopf_name))
        assertEquals(View.VISIBLE, steuerung.get().findViewById<View>(R.id.app_kopf).visibility)
        steuerung.newIntent(ziel(Ziel.STATISTIK))
        warten()
        assertEquals(View.GONE, steuerung.get().findViewById<View>(R.id.app_kopf).visibility)
        assertEquals(8, steuerung.get().findViewById<ViewGroup>(R.id.app_liste).childCount)
    }

    /**
     * Zurück aus einem Tag, den ein Tipp auf einen Balken geöffnet hat, führt
     * in die Woche (bzw. den Monat) davor, statt die App zu schließen – über
     * den Rückruf, den Android ab 13 statt onBackPressed nimmt (das Manifest
     * setzt enableOnBackInvokedCallback). Sonst (kein Tag
     * vom Balken) schließt Zurück die App wie gewohnt: kein Rückruf der App.
     */
    @Test
    fun zurueckAusDemTagVomBalken() {
        wieAndroid16()
        val a = oeffnen().get()
        val ohne = obersterRueckruf(a)
        assertNotSame(a.zurueckRuf, ohne)
        a.tagZeigen(LocalDate.of(2026, 11, 12))
        warten()
        assertEquals("Do., 12. Nov.", a.text(R.id.zeitraum_titel))
        val ruf = obersterRueckruf(a)
        assertSame(a.zurueckRuf, ruf)
        ruf!!.onBackInvoked()
        warten()
        assertEquals("9.–15. Nov.", a.text(R.id.zeitraum_titel))
        assertTrue(a.findViewById<View>(R.id.reiter_woche).isSelected)
        assertNotSame(a.zurueckRuf, obersterRueckruf(a))
        // Ein von Hand gewählter Reiter Tag: Zurück schließt die App wie immer.
        a.findViewById<View>(R.id.reiter_tag).performClick()
        warten()
        assertFalse(a.zurueckZumZeitraum())
        // Monat → Balken → Zurück: wieder der Monat.
        a.findViewById<View>(R.id.reiter_monat).performClick()
        warten()
        a.tagZeigen(LocalDate.of(2026, 11, 5))
        warten()
        a.findViewById<View>(R.id.zurueck).performClick()
        warten()
        assertEquals("Mi., 4. Nov.", a.text(R.id.zeitraum_titel))
        obersterRueckruf(a)!!.onBackInvoked()
        warten()
        assertEquals("November 2026", a.text(R.id.zeitraum_titel))
        assertFalse(a.isFinishing)
    }

    /**
     * Wie auf Tobis S24 (und ab Android 13 überhaupt, weil das Manifest
     * enableOnBackInvokedCallback="true" setzt): Zurück kommt nur über
     * Rückrufe an. Robolectric schaltet das von sich aus nicht ein.
     */
    private fun wieAndroid16() {
        Class.forName("org.robolectric.shadows.ShadowWindowOnBackInvokedDispatcher")
            .getDeclaredMethod("setEnablePredictiveBack", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(null, true)
        kontext.applicationInfo.javaClass.getMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType).invoke(kontext.applicationInfo, true)
    }

    /**
     * Der oberste Rückruf für Zurück – den ruft Android 16 beim Zurück-Wischen.
     * Die Activity reicht über einen Proxy an den Dispatcher des Fensters
     * weiter (WindowOnBackInvokedDispatcher.getTopCallback; nur im Test über Reflexion).
     */
    private fun obersterRueckruf(a: MainActivity): OnBackInvokedCallback? {
        val proxy = a.onBackInvokedDispatcher
        val feld = proxy.javaClass.getDeclaredField("mActualDispatcher").apply { isAccessible = true }
        val fenster = feld.get(proxy) ?: error("Kein Dispatcher des Fensters")
        return fenster.javaClass.getMethod("getTopCallback").invoke(fenster) as OnBackInvokedCallback?
    }

    @Test
    fun ohneErlaubnisZuerstDerenKarte() {
        val a = oeffnen().get()
        a.zeigen(ergebnis(erlaubt = false))
        warten()
        assertEquals(listOf(R.id.kopf_titel, R.id.erlaubnis_karte, R.id.statistik_karte), a.reihe().take(3))
        assertEquals(kontext.getString(R.string.statistik_ohne_erlaubnis), a.text(R.id.statistik_hinweis))
        // Wieder erlaubt: die Statistik wieder oben.
        a.zeigen(ergebnis())
        warten()
        assertEquals(listOf(R.id.kopf_titel, R.id.statistik_karte, R.id.erlaubnis_karte), a.reihe().take(3))
    }

    @Test
    fun wegenEinesUpdatesStehtDieUpdateKarteOben() {
        val steuerung = oeffnen(ziel(Ziel.UPDATE))
        val a = steuerung.get()
        assertEquals(listOf(R.id.kopf_titel, R.id.update_karte, R.id.statistik_karte), a.reihe().take(3))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.update_karte).visibility)
        assertTrue(a.text(R.id.update_titel).isNotEmpty())
        // Danach über die Statistik geöffnet: die Update-Karte wieder an ihrem Platz.
        steuerung.newIntent(ziel(Ziel.STATISTIK))
        warten()
        assertEquals(R.id.statistik_karte, a.reihe()[1])
        assertEquals(R.id.update_karte, a.reihe()[5])
    }

    @Test
    fun vomStartbildschirmOhneZielBleibtAllesWieGewohnt() {
        val a = oeffnen(intent = null).get()
        assertEquals(R.id.statistik_karte, a.reihe()[1])
        assertTrue(a.findViewById<View>(R.id.reiter_woche).isSelected)
    }

    @Test
    fun loeschenSagtDassDerVerlaufMitGeht() {
        val a = oeffnen().get()
        a.findViewById<View>(R.id.loeschen).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(shadowOf(dialog).message.toString().contains("Verlauf hier in der App"))
        // Ohne Netz geht Löschen nicht – dann bleibt auch der Verlauf.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        warten()
        assertEquals(10, Verlauf(kontext).tage().size)
    }
}

/**
 * Der Wochentitel über den Jahreswechsel bei 360 dp Breite und 130 % Schrift:
 * ganz, in einer Zeile. Vorher „28. Dez. 2026 – 3. Jan. 2027“ – umgebrochen,
 * die zweite Zeile nicht zu sehen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class TitelBreiteTest {
    private val kontext = RuntimeEnvironment.getApplication()

    @org.junit.After
    fun schriftZurueck() = RuntimeEnvironment.setFontScale(1f)

    @Test
    fun jahreswechselPasstZwischenDiePfeile() {
        Netz.gesperrt = true
        try {
            WorkManager.initialize(kontext, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
        shadowOf(kontext.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), kontext.packageName, AppOpsManager.MODE_IGNORED)
        for (schrift in listOf(1f, 1.1f, 1.3f)) {
            RuntimeEnvironment.setFontScale(schrift)
            val steuerung = Robolectric.buildActivity(MainActivity::class.java, Intent(kontext, MainActivity::class.java).putExtra(Ziel.EXTRA, Ziel.STATISTIK)).setup()
            val a = steuerung.get()
            val silvester = ZonedDateTime.of(2026, 12, 30, 20, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
            a.uhrStellen { silvester }
            repeat(20) {
                Thread.sleep(30)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val titel = a.findViewById<TextView>(R.id.zeitraum_titel)
            val wo = "Schrift $schrift: ${titel.text}"
            assertEquals(wo, "28. Dez. – 3. Jan. 2027", titel.text.toString())
            assertTrue(wo, titel.width > 0 && titel.layout != null)
            assertEquals(wo, 1, titel.lineCount)
            assertEquals(wo, 0, titel.layout.getEllipsisCount(0))
            assertEquals(wo, titel.text.length, titel.layout.getLineEnd(0))
            assertTrue(wo, titel.layout.getLineWidth(0) <= titel.width - titel.totalPaddingLeft - titel.totalPaddingRight + 0.5f)
            // Bei 100 % bleibt die Schrift, wie sie war.
            if (schrift == 1f) assertEquals(wo, android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, a.resources.displayMetrics), titel.textSize, 0.5f)
            steuerung.pause().stop().destroy()
        }
    }
}
