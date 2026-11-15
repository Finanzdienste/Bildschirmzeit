package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.PowerManager
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Tobi, 15.11. (Widget zeigte „Stand 08:43“ um 08:49): „Alter stand? Keinen
 * bock immer zu aktualisieren“. Seitdem klingelt ein nicht weckender Wecker
 * alle 2 Minuten; nach Neustart und Update wird er neu gestellt. Vorher gab es
 * nur WorkManager alle 15 Minuten.
 */
@RunWith(RobolectricTestRunner::class)
class WeckerTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val wecker get() = shadowOf(kontext.getSystemService(AlarmManager::class.java))
    private val t = 1_794_700_000_000L
    private val minute = 60_000L

    @Before
    fun vorbereiten() {
        Netz.gesperrt = true
        Selbstupdate.beobachter = null
        try {
            WorkManager.initialize(kontext, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
        shadowOf(kontext.getSystemService(PowerManager::class.java)).turnScreenOn(true)
    }

    private fun widgetDa() {
        shadowOf(AppWidgetManager.getInstance(kontext)).bindAppWidgetId(7, ComponentName(kontext, BildschirmzeitWidget::class.java))
    }

    @Test
    fun einplanenStelltEinenNichtWeckendenUngenauenWecker() {
        widgetDa()
        Aktualisierung.einplanen(kontext)
        val alle = wecker.scheduledAlarms
        assertEquals(1, alle.size)
        val w = alle[0]
        // ELAPSED_REALTIME, nicht ELAPSED_REALTIME_WAKEUP: weckt das Handy nie.
        assertEquals(AlarmManager.ELAPSED_REALTIME, w.getType())
        assertEquals(Takt.WECKER_MS, w.getIntervalMs())
        assertFalse(w.isAllowWhileIdle)
        @Suppress("DEPRECATION") // Robolectric hat für den PendingIntent nur das Feld.
        val absicht = shadowOf(w.operation)
        assertTrue(absicht.isBroadcast)
        assertTrue(absicht.isImmutable)
        assertEquals(ComponentName(kontext, Hintergrund::class.java), absicht.savedIntent.component)
        assertEquals(Wecker.TAKT, absicht.savedIntent.action)
        // Kein genauer Wecker: dafür bräuchte es SCHEDULE_EXACT_ALARM.
        assertFalse(rechte().contains(Manifest.permission.SCHEDULE_EXACT_ALARM))
        assertFalse(rechte().contains("android.permission.USE_EXACT_ALARM"))
    }

    @Test
    fun ohneWidgetKeinWecker() {
        Aktualisierung.einplanen(kontext)
        assertEquals(0, wecker.scheduledAlarms.size)
        // Klingelt er noch (Widget gerade entfernt), bestellt er sich selbst ab.
        widgetDa()
        Aktualisierung.einplanen(kontext)
        assertEquals(1, wecker.scheduledAlarms.size)
        shadowOf(AppWidgetManager.getInstance(kontext)).bindAppWidgetId(7, ComponentName("de.anderes", "de.anderes.Widget"))
        assertFalse(Aktualisierung.imTakt(kontext, Anlass.WECKER, t))
        assertEquals(0, wecker.scheduledAlarms.size)
    }

    @Test
    fun nachNeustartUndUpdateWiederEingeplant() {
        widgetDa()
        for (aktion in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            Aktualisierung.abbestellen(kontext)
            assertEquals(0, wecker.scheduledAlarms.size)
            kontext.sendBroadcast(Intent(aktion).setComponent(ComponentName(kontext, Hintergrund::class.java)))
            warten { wecker.scheduledAlarms.size == 1 }
            assertEquals(aktion, 1, wecker.scheduledAlarms.size)
        }
    }

    @Test
    fun weckerRechnetUndFragtSupabaseHoechstensAlleFuenfMinuten() {
        widgetDa()
        val speicher = Speicher(kontext)
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, t))
        assertEquals(t, speicher.gerechnet)
        assertEquals(t, speicher.netzVersuch)

        // 30 Sekunden später (z. B. nach dem Antippen): nichts.
        assertFalse(Aktualisierung.imTakt(kontext, Anlass.WECKER, t + 30_000))
        // Nach 3 Minuten: neu gerechnet, Supabase aus dem Zwischenspeicher.
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, t + 3 * minute))
        assertEquals(t + 3 * minute, speicher.gerechnet)
        assertEquals(t, speicher.netzVersuch)
        // Nach 6 Minuten: wieder Supabase.
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, t + 6 * minute))
        assertEquals(t + 6 * minute, speicher.netzVersuch)

        // Bildschirm aus: gerechnet ja, gefunkt nicht.
        shadowOf(kontext.getSystemService(PowerManager::class.java)).turnScreenOn(false)
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, t + 12 * minute))
        assertEquals(t + 12 * minute, speicher.gerechnet)
        assertEquals(t + 6 * minute, speicher.netzVersuch)

        // Antippen fragt immer.
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.TIPPEN, t + 12 * minute + 1000))
        assertEquals(t + 12 * minute + 1000, speicher.netzVersuch)
    }

    @Test
    fun empfaengerNichtExportiertUndRechteDeklariert() {
        val pm = kontext.packageManager
        for (klasse in listOf(Hintergrund::class.java, UpdateStatus::class.java)) {
            assertFalse(klasse.simpleName, pm.getReceiverInfo(ComponentName(kontext, klasse), 0).exported)
        }
        assertTrue(rechte().contains(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        assertTrue(rechte().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
        assertTrue(rechte().contains(Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION))
    }

    private fun rechte(): List<String> =
        kontext.packageManager.getPackageInfo(kontext.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()

    /** Der Empfänger rechnet auf einem eigenen Faden. */
    private fun warten(fertig: () -> Boolean) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (fertig()) return
            Thread.sleep(20)
        }
    }
}
