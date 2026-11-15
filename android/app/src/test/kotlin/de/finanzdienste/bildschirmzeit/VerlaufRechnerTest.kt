package de.finanzdienste.bildschirmzeit

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.PowerManager
import android.os.Process
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * Der Verlauf auf einem nachgebauten Gerät: Androids Ereignisse der letzten
 * Tage (der älteste angeschnitten, wie Androids älteste Tagesdatei), die Web-Apps
 * von einem nachgebauten Supabase. Was es kostet: Der Wecker liest nur heute,
 * nach Mitternacht einmal den Vortag; Supabase einmal weiter zurück, sonst acht Tage.
 */
@RunWith(RobolectricTestRunner::class)
class VerlaufRechnerTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val heute = LocalDate.of(2026, 11, 14)
    private val minute = 60_000L
    private val vorlauf = 8 * 60 * minute
    private val anfragen = mutableListOf<Pair<LocalDate, LocalDate>>()
    private var zeilen = listOf<Zeile>()
    private val abfragen = mutableListOf<Pair<Long, Long>>()
    private lateinit var verlauf: Verlauf

    private fun mitternacht(tag: LocalDate) = tag.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun um(tag: LocalDate, h: Int, m: Int = 0) = mitternacht(tag) + (h * 60 + m) * minute

    @Before
    fun geraet() {
        Netz.gesperrt = true
        try {
            WorkManager.initialize(kontext, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
        verlauf = Verlauf(kontext)
        verlauf.ordner.deleteRecursively()
        app(CHROME, "Chrome")
        app("com.beispiel.chat", "Chat")
        app("com.beispiel.weg", "Weg")
        shadowOf(kontext.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), kontext.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOf(AppWidgetManager.getInstance(kontext)).bindAppWidgetId(7, ComponentName(kontext, BildschirmzeitWidget::class.java))
        shadowOf(kontext.getSystemService(PowerManager::class.java)).turnScreenOn(true)
        val speicher = Speicher(kontext)
        speicher.kennung // erfunden, nur in diesem Test
        speicher.angemeldet = true
        Rechner.webLesen = { _, von, bis ->
            anfragen += von to bis
            zeilen.filter { it.tag >= tagText(von) && it.tag <= tagText(bis) }
        }
        Nutzungsdaten.mitschrift = abfragen
    }

    @After
    fun aufraeumen() {
        Rechner.webLesen = Supabase::lesen
        Nutzungsdaten.mitschrift = null
    }

    private fun app(paket: String, name: String) {
        val pm = shadowOf(kontext.packageManager)
        val info = ApplicationInfo().apply {
            packageName = paket
            nonLocalizedLabel = name
        }
        pm.installPackage(PackageInfo().apply { packageName = paket; applicationInfo = info })
        val k = ComponentName(paket, "$paket.Haupt")
        pm.addActivityIfNotPresent(k)
        pm.addIntentFilterForActivity(k, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    private fun ereignis(typ: Int, t: Long, paket: String) {
        shadowOf(kontext.getSystemService(UsageStatsManager::class.java)).addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent().setEventType(typ).setTimeStamp(t).setPackage(paket).setClass("$paket.Haupt").build(),
        )
    }

    private fun benutzt(paket: String, von: Long, bis: Long) {
        ereignis(UsageEvents.Event.ACTIVITY_RESUMED, von, paket)
        ereignis(UsageEvents.Event.ACTIVITY_PAUSED, bis, paket)
    }

    /**
     * Android hat Ereignisse ab dem 4.11. mittags (die älteste Tagesdatei); an
     * jedem Tag Chat um 12 (30 Min.) und Chrome um 21 Uhr (60 Min.). Heute,
     * Samstag 14.11., Chat ab 8 Uhr (20 Min.).
     */
    private fun androidHatZehnTage() {
        for (n in 10 downTo 1) {
            val d = heute.minusDays(n.toLong())
            benutzt("com.beispiel.chat", um(d, 12), um(d, 12, 30))
            benutzt(CHROME, um(d, 21), um(d, 22))
        }
        benutzt("com.beispiel.chat", um(heute, 8), um(heute, 8, 20))
    }

    private fun wecker(jetzt: Long) = assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, jetzt))

    @Test
    fun ersterStartRueckwirkendSoWeitAndroidEreignisseHat() {
        androidHatZehnTage()
        zeilen = listOf(
            Zeile("workout", tagText(heute.minusDays(2)), 600, 0),
            Zeile("freizeit", tagText(heute.minusDays(9)), 300, 0),
            Zeile("workout", tagText(heute), 120, 0),
        )
        wecker(um(heute, 9))

        // Der 4.11. ist angeschnitten (keine Ereignisse vor seiner Mitternacht): keine Daten statt zu wenig.
        assertNull(verlauf.lesen(heute.minusDays(10)))
        assertEquals(heute.minusDays(9), verlauf.erster())
        assertEquals((0L..9L).map { heute.minusDays(it) }.toSet(), verlauf.tage().toSet())
        for (n in 1L..9L) {
            val t = verlauf.lesen(heute.minusDays(n))!!
            assertTrue("$n fertig", t.fertig)
            assertEquals("$n", 90 * 60L, t.gesamt)
        }
        val heuteTag = verlauf.lesen(heute)!!
        assertFalse(heuteTag.fertig)
        assertEquals(20 * 60L, heuteTag.gesamt)

        // Eine Anfrage an Supabase für den ganzen Bereich – ab dem ältesten Tag ohne Web-Werte.
        assertEquals(listOf(heute.minusDays(9) to heute), anfragen)
        assertNull(verlauf.webFehltAb)
        val vorgestern = verlauf.lesen(heute.minusDays(2))!!
        assertEquals(mapOf("workout" to 600L, CHROME to 3000L, "com.beispiel.chat" to 1800L), vorgestern.posten.associate { it.schluessel to it.sekunden })
        assertEquals(mapOf("freizeit" to 300L, CHROME to 3300L, "com.beispiel.chat" to 1800L), verlauf.lesen(heute.minusDays(9))!!.posten.associate { it.schluessel to it.sekunden })
        assertEquals(heute.minusDays(1), verlauf.nachgetragenBis)
    }

    @Test
    fun derWeckerLiestNurHeute() {
        androidHatZehnTage()
        wecker(um(heute, 9))
        assertTrue("erster Start: rückwirkend", abfragen.size > 10)

        // Danach bei jedem Klingeln eine Abfrage, und zwar ab 8 Stunden vor heute Mitternacht.
        for (m in listOf(3L, 6L, 9L, 12L)) {
            abfragen.clear()
            wecker(um(heute, 9) + m * minute)
            assertEquals("+$m Min.", listOf((mitternacht(heute) - vorlauf) to (um(heute, 9) + m * minute)), abfragen)
        }
        // Supabase dabei nur die gewöhnlichen acht Tage (nach 6 und 12 Minuten).
        assertEquals(listOf(heute.minusDays(9), heute.minusDays(7), heute.minusDays(7)), anfragen.map { it.first })

        // Nach Mitternacht: einmal der Vortag ganz, danach wieder nur heute.
        val morgen = heute.plusDays(1)
        benutzt("com.beispiel.chat", um(heute, 23, 50), um(heute, 23, 58))
        abfragen.clear()
        wecker(um(morgen, 0, 5))
        assertEquals(
            setOf((mitternacht(morgen) - vorlauf) to um(morgen, 0, 5), (mitternacht(heute) - vorlauf) to mitternacht(morgen)),
            abfragen.toSet(),
        )
        val gestern = verlauf.lesen(heute)!!
        assertTrue(gestern.fertig)
        assertEquals(28 * 60L, gestern.gesamt)
        // Supabase dabei nur die gewöhnlichen acht Tage – der Vortag ist darin (Verlauf.webFehltAb kostet nichts extra).
        assertEquals(morgen.minusDays(7), anfragen.last().first)
        assertNull(verlauf.webFehltAb)
        abfragen.clear()
        wecker(um(morgen, 0, 8))
        assertEquals(listOf((mitternacht(morgen) - vorlauf) to um(morgen, 0, 8)), abfragen)
    }

    @Test
    fun verspaeteteMeldungenWerdenNachgetragen() {
        androidHatZehnTage()
        zeilen = listOf(Zeile("workout", tagText(heute.minusDays(2)), 600, 0))
        wecker(um(heute, 9))
        // Workout meldet für vorgestern nach (die Web-App war offline): beim nächsten Netz neu aufgeteilt.
        zeilen = listOf(Zeile("workout", tagText(heute.minusDays(2)), 1500, 0), Zeile("boxenstopp", tagText(heute.minusDays(6)), 60, 0))
        wecker(um(heute, 9, 6))
        assertEquals(heute.minusDays(7), anfragen.last().first)
        val t = verlauf.lesen(heute.minusDays(2))!!
        assertEquals(mapOf("workout" to 1500L, CHROME to 2100L, "com.beispiel.chat" to 1800L), t.posten.associate { it.schluessel to it.sekunden })
        assertEquals(90 * 60L, t.gesamt)
        assertEquals(60L, verlauf.lesen(heute.minusDays(6))!!.web["boxenstopp"])
    }

    @Test
    fun ohneNetzKommenDieWebWerteSpaeter() {
        androidHatZehnTage()
        Rechner.webLesen = { _, _, _ -> throw java.io.IOException("kein Netz") }
        wecker(um(heute, 9))
        // Android ist nachgetragen, die Web-Werte fehlen noch – vermerkt.
        assertEquals(heute.minusDays(9), verlauf.webFehltAb)
        assertEquals(0L, verlauf.lesen(heute.minusDays(3))!!.webGelesen)
        // Wieder Netz: eine Anfrage ab dem 5.11., alles eingetragen.
        Rechner.webLesen = { _, von, bis ->
            anfragen += von to bis
            listOf(Zeile("workout", tagText(heute.minusDays(3)), 900, 0))
        }
        wecker(um(heute, 9, 6))
        assertEquals(listOf(heute.minusDays(9) to heute), anfragen)
        assertEquals(mapOf("workout" to 900L), verlauf.lesen(heute.minusDays(3))!!.web)
        assertNull(verlauf.webFehltAb)
    }

    /**
     * Am Abend deinstalliert – und wie in AOSP liefert Android ihre Ereignisse
     * danach nicht mehr (UsageStatsDatabase.onPackageRemoved,
     * IntervalStats.deobfuscateEvents: Pakete ohne Token fallen weg). Der
     * Wecker rechnet wie immer gleich danach: Ihre Zeit bleibt im Tag, mit
     * Namen, auch nach Mitternacht. Vorher fiel sie beim nächsten Wecker heraus.
     */
    @Test
    fun deinstallierteAppBleibtLesbar() {
        androidHatZehnTage()
        benutzt("com.beispiel.weg", um(heute, 10), um(heute, 10, 15))
        wecker(um(heute, 11))
        assertEquals("Weg", verlauf.lesen(heute)!!.posten.first { it.schluessel == "com.beispiel.weg" }.name)
        shadowOf(kontext.packageManager).removeActivity(ComponentName("com.beispiel.weg", "com.beispiel.weg.Haupt"))
        shadowOf(kontext.packageManager).removePackage("com.beispiel.weg")
        assertFalse("com.beispiel.weg" in Nutzungsdaten.launcherPakete(kontext.packageManager))
        ShadowUsageStatsManager.reset()
        androidHatZehnTage()

        wecker(um(heute, 20, 2))
        val abends = verlauf.lesen(heute)!!
        assertEquals(15 * 60L, abends.posten.first { it.schluessel == "com.beispiel.weg" }.sekunden)
        assertEquals("Weg", abends.posten.first { it.schluessel == "com.beispiel.weg" }.name)
        assertEquals(35 * 60L, abends.gesamt)

        wecker(um(heute.plusDays(1), 0, 5))
        val gestern = verlauf.lesen(heute)!!
        assertTrue(gestern.fertig)
        assertEquals(15 * 60L, gestern.posten.first { it.schluessel == "com.beispiel.weg" }.sekunden)
        assertEquals("Weg", gestern.posten.first { it.schluessel == "com.beispiel.weg" }.name)
        assertEquals(35 * 60L, gestern.gesamt)
    }

    /**
     * Zehn Tage nicht gerechnet (Widget weg, App nicht geöffnet): Android hat
     * den 4.11. inzwischen weggeräumt bis auf den Nachmittag (die älteste
     * Tagesdatei beginnt um 15 Uhr). Der Verlauf kennt ihn schon fast ganz (am
     * Abend gerechnet). Er wird nicht durch den angeschnittenen Rest ersetzt –
     * was Android vom Abend noch dazu hat, kommt hinzu, und er bleibt „nicht
     * fertig“. Vorher stand danach nur der Rest da, als fertig.
     */
    @Test
    fun angeschnittenerTagUeberschreibtNichtDenBekannten() {
        val d10 = heute.minusDays(10)
        verlauf.schreiben(tagAus(d10, mapOf("com.beispiel.chat" to 1800L, CHROME to 3600L), emptyMap(), um(d10, 20), fertig = false) { if (it == CHROME) "Chrome" else "Chat" })
        verlauf.nachgetragenBis = d10.minusDays(1)
        ereignis(UsageEvents.Event.SCREEN_INTERACTIVE, um(d10, 15), "android")
        benutzt(CHROME, um(d10, 21), um(d10, 22))
        benutzt("com.beispiel.weg", um(d10, 22, 30), um(d10, 23))
        for (n in 9 downTo 1) benutzt("com.beispiel.chat", um(heute.minusDays(n.toLong()), 12), um(heute.minusDays(n.toLong()), 12, 30))
        wecker(um(heute, 9))
        val t = verlauf.lesen(d10)!!
        assertEquals(mapOf("com.beispiel.chat" to 1800L, CHROME to 3600L, "com.beispiel.weg" to 1800L), t.posten.associate { it.schluessel to it.sekunden })
        assertFalse(t.fertig)
        for (n in 1L..9L) assertEquals("−$n", 30 * 60L, verlauf.lesen(heute.minusDays(n))!!.gesamt)
        assertEquals(heute.minusDays(1), verlauf.nachgetragenBis)
    }

    /**
     * Wie oben, aber nur der 3.11. steht im Verlauf: Dass der Vortag bekannt
     * ist, beweist nach zehn Tagen nicht mehr, dass Android den 4.11. ganz hat.
     * Er bleibt „keine Daten“ statt einer zu kleinen Zahl.
     */
    @Test
    fun angeschnittenerTagMitBekanntemVortagBleibtOhneDaten() {
        val d10 = heute.minusDays(10)
        val d11 = heute.minusDays(11)
        verlauf.schreiben(tagAus(d11, mapOf("com.beispiel.chat" to 1800L), emptyMap(), um(d11, 20), fertig = false) { "Chat" })
        verlauf.nachgetragenBis = d11.minusDays(1)
        ereignis(UsageEvents.Event.SCREEN_INTERACTIVE, um(d10, 15), "android")
        benutzt(CHROME, um(d10, 21), um(d10, 22))
        for (n in 9 downTo 1) benutzt("com.beispiel.chat", um(heute.minusDays(n.toLong()), 12), um(heute.minusDays(n.toLong()), 12, 30))
        wecker(um(heute, 9))
        assertNull(verlauf.lesen(d10))
        assertEquals(30 * 60L, verlauf.lesen(heute.minusDays(9))!!.gesamt)
    }

    /**
     * Android antwortet einmal leer – etwa direkt nach einem Neustart kurz
     * nach Mitternacht, bevor es den Nutzer geladen hat. Der Vortag bleibt
     * dann nicht für immer beim Stand vor Mitternacht: Die Rechnung versucht
     * es nach einer Stunde noch einmal (dazwischen liest der Wecker nur heute).
     */
    @Test
    fun leereAntwortNachMitternachtWirdSpaeterWiederholt() {
        val morgen = heute.plusDays(1)
        fun android() {
            androidHatZehnTage()
            benutzt("com.beispiel.weg", um(heute, 23, 52), um(heute, 23, 59))
        }
        android()
        wecker(um(heute, 23, 50))
        assertEquals(20 * 60L, verlauf.lesen(heute)!!.gesamt)
        ShadowUsageStatsManager.reset()
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.START, um(morgen, 0, 5)))
        android()
        abfragen.clear()
        wecker(um(morgen, 0, 10))
        assertEquals(listOf((mitternacht(morgen) - vorlauf) to um(morgen, 0, 10)), abfragen)
        assertFalse(verlauf.lesen(heute)!!.fertig)
        abfragen.clear()
        wecker(um(morgen, 1, 6))
        assertEquals(2, abfragen.size)
        val t = verlauf.lesen(heute)!!
        assertTrue(t.fertig)
        assertEquals(27 * 60L, t.gesamt)
        assertEquals(heute, verlauf.nachgetragenBis)
    }

    /**
     * Die Web-Werte von heute las die App um 9 Uhr, danach hatte sie neun
     * Tage kein Netz, die Web-Apps meldeten aber weiter. Der Tag gilt nicht als
     * fertig, nur weil er vor seinem Ende einmal gelesen wurde: Die erste
     * Netzabfrage danach holt ihn mit (eine Anfrage). Vorher blieb Workouts
     * Abend für immer in Chrome.
     */
    @Test
    fun webWerteVorTagesendeGelesenWerdenNochGeholt() {
        androidHatZehnTage()
        for (n in 0L..9L) benutzt(CHROME, um(heute.plusDays(n), 21), um(heute.plusDays(n), 22))
        wecker(um(heute, 9))
        Rechner.webLesen = { _, _, _ -> throw java.io.IOException("kein Netz") }
        wecker(um(heute, 23, 50))
        for (n in 1L..9L) wecker(um(heute.plusDays(n), 8))
        Rechner.webLesen = { _, von, bis ->
            anfragen += von to bis
            listOf(Zeile("workout", tagText(heute), 1800, 0)).filter { it.tag >= tagText(von) && it.tag <= tagText(bis) }
        }
        anfragen.clear()
        wecker(um(heute.plusDays(9), 8, 30))
        assertEquals(listOf(heute to heute.plusDays(9)), anfragen)
        val t = verlauf.lesen(heute)!!
        assertEquals(mapOf("workout" to 1800L), t.web)
        assertEquals(mapOf("workout" to 1800L, CHROME to 1800L, "com.beispiel.chat" to 1200L), t.posten.associate { it.schluessel to it.sekunden })
        assertNull(verlauf.webFehltAb)
    }

    @Test
    fun allesLoeschenLeertDenVerlauf() {
        androidHatZehnTage()
        wecker(um(heute, 9))
        assertEquals(10, verlauf.tage().size)
        // Ohne Netz: nichts gelöscht, auch der Verlauf nicht.
        assertFalse(Rechner.allesLoeschen(kontext, um(heute, 9, 1)) { throw java.io.IOException("kein Netz") })
        assertEquals(10, verlauf.tage().size)
        val alteKennung = Speicher(kontext).kennung
        assertTrue(Rechner.allesLoeschen(kontext, um(heute, 9, 2)) { true })
        assertTrue(verlauf.tage().isEmpty())
        assertTrue(alteKennung != Speicher(kontext).kennung)
        // Und er kommt nicht gleich rückwirkend wieder: Die nächste Rechnung schreibt nur heute.
        abfragen.clear()
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.TIPPEN, um(heute, 9, 3)))
        assertEquals(listOf(heute), verlauf.tage())
        assertTrue(abfragen.all { it.first == mitternacht(heute) - vorlauf })
    }
}
