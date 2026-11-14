package de.finanzdienste.bildschirmzeit

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Der ganze Weg auf einem nachgebauten Gerät: Androids Ereignisse,
 * Launcher und Startbildschirm, die Werte der Web-Apps aus dem
 * Zwischenspeicher – heraus kommt die Liste fürs Widget.
 */
@RunWith(RobolectricTestRunner::class)
class RechnerTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val heute = LocalDate.of(2026, 11, 14)
    private val mitternacht = heute.atStartOfDay(zone).toInstant().toEpochMilli()
    private val jetzt = um(21, 40)

    private fun um(h: Int, m: Int) = mitternacht + (h * 60 + m) * 60_000L

    @Before
    fun geraet() {
        app("com.android.chrome", "Chrome", launcher = true)
        app("com.beispiel.chat", "Chat", launcher = true)
        app("com.beispiel.dienst", "Dienst", launcher = false)
        app("com.beispiel.start", "Start", launcher = false, home = true)
    }

    private fun erlauben(modus: Int = AppOpsManager.MODE_ALLOWED) {
        val ops = kontext.getSystemService(AppOpsManager::class.java)
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), kontext.packageName, modus)
    }

    private fun app(paket: String, name: String, launcher: Boolean, home: Boolean = false) {
        val pm = shadowOf(kontext.packageManager)
        val info = ApplicationInfo().apply {
            packageName = paket
            nonLocalizedLabel = name
        }
        pm.installPackage(PackageInfo().apply { packageName = paket; applicationInfo = info })
        fun activity(klasse: String, kategorie: String) {
            val k = ComponentName(paket, klasse)
            pm.addActivityIfNotPresent(k)
            pm.addIntentFilterForActivity(k, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(kategorie) })
        }
        if (launcher) activity("$paket.Haupt", Intent.CATEGORY_LAUNCHER)
        if (home) activity("$paket.Home", Intent.CATEGORY_HOME)
    }

    private fun ereignis(typ: Int, t: Long, paket: String = "", klasse: String = "$paket.Haupt") {
        val usm = kontext.getSystemService(UsageStatsManager::class.java)
        shadowOf(usm).addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setEventType(typ).setTimeStamp(t).setPackage(paket).setClass(klasse).build(),
        )
    }

    private fun benutzt(paket: String, von: Long, bis: Long, klasse: String = "$paket.Haupt") {
        ereignis(UsageEvents.Event.ACTIVITY_RESUMED, von, paket, klasse)
        ereignis(UsageEvents.Event.ACTIVITY_PAUSED, bis, paket, klasse)
    }

    @Test
    fun chromeWirdUmDieWebAppsGekuerzt() {
        erlauben()
        // Keine zwei Ereignisse zur selben Millisekunde – der Nachbau ordnet nach Zeit.
        benutzt("com.android.chrome", um(20, 0), um(21, 0)) // 60 Min.
        benutzt("com.beispiel.chat", um(21, 1), um(21, 21)) // 20 Min.
        benutzt("com.beispiel.start", um(21, 22), um(21, 30), "com.beispiel.start.Home") // Startbildschirm
        benutzt("com.beispiel.dienst", um(21, 31), um(21, 34)) // ohne Launcher-Eintrag
        benutzt("com.android.systemui", um(21, 35), um(21, 36), "com.android.systemui.Schalter")
        ereignis(UsageEvents.Event.SCREEN_NON_INTERACTIVE, um(21, 37))
        benutzt("com.beispiel.chat", um(23, 50) - 86_400_000L, um(0, 10)) // über Mitternacht: 10 Min. zählen

        val heuteText = heute.toString()
        Speicher(kontext).zwischenspeichern(
            um(21, 30),
            listOf(
                Zeile("workout", heuteText, 30 * 60L, um(21, 0)),
                Zeile("boxenstopp", heuteText, 10 * 60L, um(20, 30)),
                Zeile("freizeit", heute.minusDays(1).toString(), 99 * 60L, um(0, 0) - 3_600_000L),
            ),
        )

        val e = Rechner.berechnen(kontext, netz = false, jetzt = jetzt)
        assertTrue(e.erlaubt)
        assertEquals(
            listOf("Chat" to 30 * 60L, "Workout" to 30 * 60L, "Chrome" to 20 * 60L, "Boxenstopp" to 10 * 60L),
            e.posten.map { it.name to it.sekunden },
        )
        assertEquals(90 * 60L, e.gesamt) // so viel wie Android: 60 Chrome + 30 Chat
        assertTrue(e.web.verbunden)
        assertFalse(e.ohneNetz) // ohne Netz gerechnet heißt hier: gar nicht gefragt
        assertEquals(setOf("workout", "boxenstopp", "freizeit"), e.web.zuletzt.keys)
    }

    @Test
    fun ohneErlaubnisNurDerHinweis() {
        erlauben(AppOpsManager.MODE_IGNORED)
        benutzt("com.android.chrome", um(20, 0), um(21, 0))
        val e = Rechner.berechnen(kontext, netz = false, jetzt = jetzt)
        assertFalse(e.erlaubt)
        assertTrue(e.posten.isEmpty())
        assertFalse(Nutzungsdaten.erlaubt(kontext))
    }

    @Test
    fun startbildschirmUndLauncherWerdenErkannt() {
        val pm = kontext.packageManager
        assertTrue("com.beispiel.chat" in Nutzungsdaten.launcherPakete(pm))
        assertFalse("com.beispiel.dienst" in Nutzungsdaten.launcherPakete(pm))
        assertTrue("com.beispiel.start/com.beispiel.start.Home" in Nutzungsdaten.homeActivities(pm))
        assertEquals("Chat", Nutzungsdaten.name(pm, "com.beispiel.chat"))
        assertEquals("de.gibt.es.nicht", Nutzungsdaten.name(pm, "de.gibt.es.nicht"))
    }
}
