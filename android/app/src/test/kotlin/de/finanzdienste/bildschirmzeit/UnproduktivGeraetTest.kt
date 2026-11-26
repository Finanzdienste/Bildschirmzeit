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
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * „Davon unproduktiv“ auf einem nachgebauten Gerät, ohne Netz: Androids
 * Kategorie der App, Tobis eigene Einstufung (bleibt gespeichert), das Widget
 * und der Knopf in der Statistik. Erfundene Apps und Zeiten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class UnproduktivGeraetTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val heute = LocalDate.of(2026, 11, 26)
    private val minute = 60_000L

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
        Unproduktiv(kontext).alleVergessen()
        val pm = shadowOf(kontext.packageManager)
        for ((paket, name, kategorie) in listOf(
            Triple("com.beispiel.spiel", "Spiel", ApplicationInfo.CATEGORY_GAME),
            Triple("com.beispiel.chat", "Chat", ApplicationInfo.CATEGORY_UNDEFINED),
        )) {
            pm.installPackage(
                PackageInfo().apply {
                    packageName = paket
                    applicationInfo = ApplicationInfo().apply { packageName = paket; nonLocalizedLabel = name; category = kategorie }
                },
            )
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
        Unproduktiv(kontext).alleVergessen()
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

    @Test
    fun androidsKategorieUndEigeneEinstufung() {
        val u = Unproduktiv(kontext)
        assertTrue("Spiel (Kategorie Spiel)", u.ist("com.beispiel.spiel"))
        assertFalse("Chat (keine Kategorie)", u.ist("com.beispiel.chat"))
        u.umstellen("com.beispiel.chat")
        u.umstellen("com.beispiel.spiel")
        // Bleibt gespeichert (eine neue Instanz liest die Datei).
        assertTrue(Unproduktiv(kontext).ist("com.beispiel.chat"))
        assertFalse(Unproduktiv(kontext).ist("com.beispiel.spiel"))
        // Zurück zur Vorgabe: Die eigene Angabe fällt weg.
        Unproduktiv(kontext).umstellen("com.beispiel.spiel")
        assertEquals(mapOf("com.beispiel.chat" to true), Unproduktiv(kontext).eigene())
        Unproduktiv(kontext).alleVergessen()
        assertFalse(Unproduktiv(kontext).ist("com.beispiel.chat"))
    }

    /** Tobis 4×2, ohne Netz: hinter der Gesamtzeit „(davon unproduktiv: 40 Min.)“. */
    @Test
    fun widgetOhneNetz() {
        benutzt("com.beispiel.spiel", um(20), um(20, 40))
        benutzt("com.beispiel.chat", um(21), um(21, 10))
        val e = Rechner.berechnen(kontext, netz = false, jetzt = um(21, 30))
        assertEquals(50 * 60L, e.gesamt)
        assertEquals(40 * 60L, e.unproduktiv)
        val v = aufgebaut(WidgetAnsicht.ansicht(kontext, e, 400f, 201f), 400f, 201f)
        val t = v.findViewById<TextView>(R.id.unproduktiv)
        assertEquals(View.VISIBLE, t.visibility)
        assertEquals("(davon unproduktiv: 40 Min.)", t.text.toString())
        assertEquals("50 Min.", v.findViewById<TextView>(R.id.gesamt).text.toString())
        assertTrue(v.contentDescription.toString(), v.contentDescription.toString().startsWith("Bildschirmzeit heute: 50 Min., davon unproduktiv 40 Min."))
    }

    /** In der Statistik den Chat als unproduktiv zählen – und zurück. */
    @Test
    fun inDerStatistikUmstellen() {
        shadowOf(kontext.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), kontext.packageName, AppOpsManager.MODE_IGNORED)
        Verlauf(kontext).schreiben(
            Tag(heute, sortiert(listOf(Posten("com.beispiel.spiel", "Spiel", 1800L), Posten("com.beispiel.chat", "Chat", 600L))), fertig = false),
        )
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.uhrStellen { um(21, 40) }
        a.findViewById<View>(R.id.reiter_tag).performClick()
        warten()
        assertTrue(a.text(R.id.schnitt), a.text(R.id.schnitt).contains("davon unproduktiv: 30 Min. (75\u00A0%)"))
        val liste = a.findViewById<ViewGroup>(R.id.app_liste)
        (0 until liste.childCount).map { liste.getChildAt(it) }.first { it.findViewById<TextView>(R.id.name).text == "Chat" }.performClick()
        warten()
        val knopf = a.findViewById<Button>(R.id.unproduktiv_umstellen)
        assertEquals(View.VISIBLE, knopf.visibility)
        assertEquals("Als unproduktiv zählen", knopf.text.toString())
        knopf.performClick()
        warten()
        assertEquals("Nicht als unproduktiv zählen", knopf.text.toString())
        assertTrue(a.text(R.id.schnitt), a.text(R.id.schnitt).endsWith("zählt als unproduktiv"))
        assertEquals(mapOf("com.beispiel.chat" to true), Unproduktiv(kontext).eigene())
        // Alle Apps: jetzt beide.
        a.findViewById<View>(R.id.alle_apps).performClick()
        warten()
        assertTrue(a.text(R.id.schnitt), a.text(R.id.schnitt).contains("davon unproduktiv: 40 Min. (100\u00A0%)"))
        assertEquals(View.GONE, knopf.visibility)
    }

    private fun MainActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    /** Die RemoteViews als echte Views, gemessen und gelegt in breite × hoehe dp. */
    private fun aufgebaut(ansicht: android.widget.RemoteViews, breite: Float, hoehe: Float): View {
        val dichte = kontext.resources.displayMetrics.density
        val b = (breite * dichte).roundToInt()
        val h = (hoehe * dichte).roundToInt()
        val view = ansicht.apply(kontext, FrameLayout(kontext))
        view.measure(View.MeasureSpec.makeMeasureSpec(b, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, b, h)
        return view
    }
}
