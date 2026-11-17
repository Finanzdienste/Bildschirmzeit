package de.finanzdienste.bildschirmzeit

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowUsageStatsManager
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Diskret auf einem nachgebauten Gerät: Erotikseiten und von Tobi
 * zusammengelegte Seiten nie mit Namen – weder im Widget noch in der
 * Statistik, und auch nicht mehr im Gespeicherten. Erfundene Seiten und Zeiten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DiskretGeraetTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val heute = LocalDate.of(2026, 11, 14)
    private val minute = 60_000L
    private val ff = "org.mozilla.firefox"

    private fun um(h: Int, m: Int = 0) = heute.atStartOfDay(zone).toInstant().toEpochMilli() + (h * 60 + m) * minute

    @Before
    fun geraet() {
        Netz.gesperrt = true
        try {
            WorkManager.initialize(kontext, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
        Verlauf(kontext).ordner.deleteRecursively()
        Beobachtungen(kontext).loeschen()
        Verborgen(kontext).alleZeigen()
        val pm = shadowOf(kontext.packageManager)
        for ((paket, name) in listOf(ff to "Firefox", "com.beispiel.chat" to "Chat")) {
            pm.installPackage(PackageInfo().apply { packageName = paket; applicationInfo = ApplicationInfo().apply { packageName = paket; nonLocalizedLabel = name } })
            val k = ComponentName(paket, "$paket.Haupt")
            pm.addActivityIfNotPresent(k)
            pm.addIntentFilterForActivity(k, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
        shadowOf(kontext.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), kontext.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOf(AppWidgetManager.getInstance(kontext)).bindAppWidgetId(7, ComponentName(kontext, BildschirmzeitWidget::class.java))
        shadowOf(kontext.getSystemService(PowerManager::class.java)).turnScreenOn(true)
        Speicher(kontext).angemeldet = true
        Rechner.webLesen = { _, _, _ -> emptyList() }
    }

    @After
    fun aufraeumen() {
        Rechner.webLesen = Supabase::lesen
        Beobachtungen(kontext).loeschen()
        Verborgen(kontext).alleZeigen()
    }

    private fun benutzt(paket: String, von: Long, bis: Long) {
        val usm = shadowOf(kontext.getSystemService(UsageStatsManager::class.java))
        for ((typ, t) in listOf(UsageEvents.Event.ACTIVITY_RESUMED to von, UsageEvents.Event.ACTIVITY_PAUSED to bis)) {
            usm.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent().setEventType(typ).setTimeStamp(t).setPackage(paket).setClass("$paket.Haupt").build())
        }
    }

    private fun warten() {
        repeat(25) {
            Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun beobachtungenText() = File(File(kontext.noBackupFilesDir, Beobachtungen.ORDNER), "beobachtungen.txt").readText()

    /** Beim ersten Lauf nach dem Update schreibt die Rechnung Gespeichertes von Erotikseiten um; das Widget zeigt nur „Sonstige“. */
    @Test
    fun nachDemUpdateOhneNamen() {
        benutzt(ff, um(20), um(21))
        // So hatte es eine ältere Fassung gespeichert: mit Namen.
        val b = Beobachtungen(kontext)
        b.merken(Beobachtung(um(20), ff, "beispiel-porno.de"))
        b.merken(Beobachtung(um(20, 30), ff, "youtube.com"))
        val gestern = heute.minusDays(1)
        Verlauf(kontext).schreiben(Tag(gestern, sortiert(listOf(Posten(seitenSchluessel(ff, "beispiel-porno.de"), "beispiel-porno.de", 900L), Posten(ff, "Firefox", 60L))), fertig = true))
        Speicher(kontext).erotikStand = 0

        val e = Rechner.berechnen(kontext, netz = false, jetzt = um(21, 30))
        // Gleich lang: nach Name (sortiert).
        assertEquals(listOf("Sonstige Webseiten" to 30 * 60L, "youtube.com" to 30 * 60L), e.posten.map { it.name to it.sekunden })
        assertEquals(Erotik.STAND, Speicher(kontext).erotikStand)
        assertFalse(beobachtungenText().contains("porno"))
        val alt = Verlauf(kontext).lesen(gestern)!!
        assertEquals(960L, alt.gesamt)
        assertTrue(alt.posten.none { "porno" in it.schluessel || "porno" in it.name })
        assertTrue(Verlauf(kontext).lesen(heute)!!.posten.none { "porno" in it.schluessel })
    }

    /** In der Statistik eine Seite antippen → „Unter „Sonstige“ zusammenfassen“ → sie steht nie mehr mit Namen da. */
    @Test
    fun eineSeiteSelbstZusammenfassen() {
        // Ohne Erlaubnis rechnet die App selbst nichts in den Verlauf (sonst mit der echten Uhr) – wie in AppStatistikTest.
        shadowOf(kontext.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), kontext.packageName, AppOpsManager.MODE_IGNORED)
        Settings.Secure.putString(kontext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "${kontext.packageName}/.WebseitenDienst")
        Verlauf(kontext).schreiben(
            Tag(heute, sortiert(listOf(Posten(seitenSchluessel(ff, "meine-seite.de"), "meine-seite.de", 1800L), Posten("com.beispiel.chat", "Chat", 600L))), fertig = false),
        )
        Beobachtungen(kontext).merken(Beobachtung(um(20), ff, "meine-seite.de"))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.uhrStellen { um(21, 40) }
        a.findViewById<View>(R.id.reiter_tag).performClick()
        warten()
        val liste = a.findViewById<ViewGroup>(R.id.app_liste)
        fun namen() = (0 until liste.childCount).map { liste.getChildAt(it).findViewById<TextView>(R.id.name).text.toString() }
        assertTrue(namen().toString(), "meine-seite.de" in namen())
        (0 until liste.childCount).map { liste.getChildAt(it) }.first { it.findViewById<TextView>(R.id.name).text == "meine-seite.de" }.performClick()
        warten()
        val knopf = a.findViewById<View>(R.id.seite_zusammenfassen)
        assertEquals(View.VISIBLE, knopf.visibility)
        knopf.performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        warten()
        assertEquals(setOf("meine-seite.de"), Verborgen(kontext).eigene())
        assertTrue(namen().toString(), "meine-seite.de" !in namen() && "Sonstige Webseiten" in namen())
        assertFalse(beobachtungenText().contains("meine-seite"))
        assertTrue(Verlauf(kontext).lesen(heute)!!.posten.none { "meine-seite" in it.schluessel })
        // Karte 4 sagt es und kann es zurücknehmen (für die Zukunft).
        a.schritt4Zeigen()
        assertTrue(a.findViewById<TextView>(R.id.schritt4_diskret).text.toString().endsWith("Von dir zusammengefasst: 1."))
        val zeigen = a.findViewById<View>(R.id.schritt4_wieder_zeigen)
        assertEquals(View.VISIBLE, zeigen.visibility)
        zeigen.performClick()
        warten()
        assertTrue(Verborgen(kontext).eigene().isEmpty())
        assertEquals(View.GONE, zeigen.visibility)
        // „Sonstige Webseiten“ selbst hat keinen solchen Knopf.
        (0 until liste.childCount).map { liste.getChildAt(it) }.first { it.findViewById<TextView>(R.id.name).text == "Sonstige Webseiten" }.performClick()
        warten()
        assertEquals(View.GONE, a.findViewById<View>(R.id.seite_zusammenfassen).visibility)
    }

    @Test
    fun alleDatenLoeschenNimmtDieEigeneListeMit() {
        Verborgen(kontext).hinzufuegen("meine-seite.de")
        assertTrue(Rechner.allesLoeschen(kontext, um(21)) { true })
        assertTrue(Verborgen(kontext).eigene().isEmpty())
    }
}
