package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Webseiten in Firefox einzeln auf einem nachgebauten Gerät: der Dienst im
 * Manifest (nur Firefox, nur lesen), sein Schalter, die Beobachtungen, die
 * Karte 4 der App und die Rechnung mit Androids Ereignissen – die Gesamtzeit
 * bleibt, Firefox wird um seine Seiten gekürzt. Erfundene Zeiten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class WebseitenGeraetTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val heute = LocalDate.of(2026, 11, 14)
    private val minute = 60_000L
    private val ff = "org.mozilla.firefox"
    private val dienst = ComponentName(kontext, WebseitenDienst::class.java)

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
        app(ff, "Firefox")
        app(CHROME, "Chrome")
        app("com.beispiel.chat", "Chat")
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

    private fun benutzt(paket: String, von: Long, bis: Long) {
        val usm = shadowOf(kontext.getSystemService(UsageStatsManager::class.java))
        for ((typ, t) in listOf(UsageEvents.Event.ACTIVITY_RESUMED to von, UsageEvents.Event.ACTIVITY_PAUSED to bis)) {
            usm.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent().setEventType(typ).setTimeStamp(t).setPackage(paket).setClass("$paket.Haupt").build())
        }
    }

    private fun warten() {
        repeat(20) {
            Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun derDienstSiehtNurDieBrowserUndLiestNur() {
        val info = kontext.packageManager.getServiceInfo(dienst, PackageManager.GET_META_DATA)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, info.permission)
        assertTrue(info.exported)
        assertEquals(R.xml.webseiten_dienst, info.metaData.getInt("android.accessibilityservice"))
        val xml = kontext.resources.getXml(R.xml.webseiten_dienst)
        while (xml.next() != XmlPullParser.START_TAG) Unit
        assertEquals("accessibility-service", xml.name)
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals(BROWSER, xml.getAttributeValue(ns, "packageNames").split(',').map { it.trim() }.toSet())
        assertEquals("true", xml.getAttributeValue(ns, "canRetrieveWindowContent"))
        // Kein Tippen, keine Gesten, keine Tasten.
        for (attr in listOf("canPerformGestures", "canRequestFilterKeyEvents", "isAccessibilityTool")) assertNull(attr, xml.getAttributeValue(ns, attr))
    }

    @Test
    fun schalterAusDenEinstellungen() {
        val cr = kontext.contentResolver
        assertFalse(WebseitenStatus.eingeschaltet(kontext))
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.andere/.X:${kontext.packageName}/.WebseitenDienst")
        assertTrue(WebseitenStatus.eingeschaltet(kontext))
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "${kontext.packageName}/${WebseitenDienst::class.java.name}")
        assertTrue(WebseitenStatus.eingeschaltet(kontext))
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.andere/.X")
        assertFalse(WebseitenStatus.eingeschaltet(kontext))
    }

    @Test
    fun beobachtungenNurBeiEinemWechselUndNurAufDemHandy() {
        val b = Beobachtungen(kontext)
        assertTrue(b.merken(Beobachtung(um(20), ff, "youtube.com")))
        assertFalse("gleich geblieben", b.merken(Beobachtung(um(20, 1), ff, "youtube.com")))
        assertTrue(b.merken(Beobachtung(um(20, 2), ff, null)))
        assertTrue(b.merken(Beobachtung(um(20, 3), ALLE, null)))
        assertTrue("Dienst an/aus immer", b.merken(Beobachtung(um(20, 4), ALLE, null)))
        assertEquals(listOf("youtube.com", null, null, null), b.lesen(um(19), um(21)).map { it.domain })
        assertEquals("youtube.com", b.zuletztErkannt()?.domain)
        // Im noBackupFilesDir – nie in der Android-Sicherung.
        val datei = File(File(kontext.noBackupFilesDir, Beobachtungen.ORDNER), "beobachtungen.txt")
        assertTrue(datei.isFile)
        assertFalse("nur die Domain", datei.readText().contains("/"))
        // Nach 12 Tagen weg.
        b.aufraeumen(um(20) + BEOBACHTUNG_BEHALTEN_MS + minute * 10)
        assertTrue(b.lesen(0L, Long.MAX_VALUE).isEmpty())
        b.merken(Beobachtung(um(20), ff, "spiegel.de"))
        b.loeschen()
        assertFalse(datei.exists())
        assertNull(b.zuletztErkannt())
    }

    /** Firefox 20–21 Uhr: 40 Min. youtube.com, 20 Min. spiegel.de; Chat 10 Min. Die Gesamtzeit bleibt 70 Min. */
    @Test
    fun dieRechnungTeiltFirefoxDieSummeBleibt() {
        benutzt("com.beispiel.chat", um(8), um(8, 10))
        benutzt(ff, um(20), um(21))
        val b = Beobachtungen(kontext)
        b.merken(Beobachtung(um(19, 59), ALLE, null))
        b.merken(Beobachtung(um(20), ff, "youtube.com"))
        b.merken(Beobachtung(um(20, 40), ff, "spiegel.de"))
        val e = Rechner.berechnen(kontext, netz = false, jetzt = um(21, 30))
        assertEquals(70 * 60L, e.gesamt)
        val jeName = e.posten.associate { it.name to it.sekunden }
        assertEquals(mapOf("youtube.com" to 40 * 60L, "spiegel.de" to 20 * 60L, "Chat" to 10 * 60L), jeName)
        // Im Verlauf genauso – und die nächste Rechnung zählt nichts doppelt (nieWeniger faltet Seiten zurück).
        assertEquals(70 * 60L, Verlauf(kontext).lesen(heute)!!.gesamt)
        assertEquals(70 * 60L, Rechner.berechnen(kontext, netz = false, jetzt = um(21, 40)).gesamt)
        // Ohne Beobachtungen (Dienst aus): Firefox ganz, Summe gleich.
        b.loeschen()
        Verlauf(kontext).ordner.deleteRecursively()
        val ohne = Rechner.berechnen(kontext, netz = false, jetzt = um(21, 30))
        assertEquals(mapOf("Firefox" to 60 * 60L, "Chat" to 10 * 60L), ohne.posten.associate { it.name to it.sekunden })
    }

    /**
     * Tobi, 16.11.: „Das gleiche für Chrome. … alles unter fünf min unter sonstige
     * websiten … Also Chrome und Firefox addiert“. Firefox 20–21 Uhr (40 Min.
     * youtube.com, 15 Min. spiegel.de, 5 Min. Suche ohne Adresse), Chrome
     * 21:00–21:30 (10 Min. youtube.com, 2 Min. klein.de, dann eine Web-App),
     * Chat 10 Min.: youtube.com 50 Min., Chrome 18 (nur die Web-App), spiegel.de
     * 15, Chat 10, „Firefox“ 7 (Suche und klein.de; bis v27 „Sonstiges“) – zusammen 100.
     */
    @Test
    fun chromeUndFirefoxZusammenKleineZuFirefox() {
        benutzt("com.beispiel.chat", um(8), um(8, 10))
        benutzt(ff, um(20), um(21))
        benutzt(CHROME, um(21), um(21, 30))
        val b = Beobachtungen(kontext)
        b.merken(Beobachtung(um(20), ff, "youtube.com"))
        b.merken(Beobachtung(um(20, 40), ff, "spiegel.de"))
        // Ab 20:55 eine Google-Suche (Suchbegriffe statt Adresse): zählt zu „Firefox“.
        b.merken(Beobachtung(um(20, 55), ff, zuMerken(ff, Befund.Keine).second))
        b.merken(Beobachtung(um(21), CHROME, "youtube.com"))
        b.merken(Beobachtung(um(21, 10), CHROME, "klein.de"))
        // Ab 21:12 eine installierte Web-App (kein Web): bleibt Chrome.
        b.merken(Beobachtung(um(21, 12), CHROME, zuMerken(CHROME, Befund.KeinWeb).second))
        val e = Rechner.berechnen(kontext, netz = false, jetzt = um(21, 40))
        assertEquals(100 * 60L, e.gesamt)
        assertEquals(
            listOf("youtube.com" to 50 * 60L, "Chrome" to 18 * 60L, "spiegel.de" to 15 * 60L, "Chat" to 10 * 60L, "Firefox" to 7 * 60L),
            e.posten.map { it.name to it.sekunden },
        )
        // Für Karte 4: wie die Browserzeit aufgeteilt war (nur Sekunden).
        val z = Beobachtungen(kontext).aufschluesselung(heute)
        assertEquals(BrowserZeit(seiten = 12 * 60L, keinWeb = 18 * 60L), z[CHROME])
        assertEquals(BrowserZeit(seiten = 55 * 60L, ohneName = 5 * 60L), z[ff])
        assertTrue(Beobachtungen(kontext).aufschluesselung(heute.plusDays(1)).isEmpty())
        // Im Verlauf je Browser (für nieWeniger), auch die kleine Seite.
        val tag = Verlauf(kontext).lesen(heute)!!
        assertEquals(120L, tag.posten.single { it.schluessel == seitenSchluessel(CHROME, "klein.de") }.sekunden)
        assertEquals(100 * 60L, tag.gesamt)
    }

    @Test
    fun alleDatenLoeschenNimmtDieSeitenMit() {
        Beobachtungen(kontext).merken(Beobachtung(um(20), ff, "youtube.com"))
        assertTrue(Rechner.allesLoeschen(kontext, um(21)) { true })
        assertNull(Beobachtungen(kontext).zuletztErkannt())
    }

    @Test
    fun karteVierAusAnUndWasZuletztKam() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        a.uhrStellen { um(21, 40) }
        fun text(id: Int) = a.findViewById<TextView>(id).text.toString()
        assertEquals(kontext.getString(R.string.schritt4_titel), text(R.id.schritt4_titel))
        assertEquals("Einschalten", text(R.id.schritt4_knopf))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.schritt4_anleitung).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.schritt4_stand).visibility)
        a.findViewById<View>(R.id.schritt4_knopf).performClick()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(a).nextStartedActivity.action)

        // Eingeschaltet, aber (noch) nicht gebunden: sagt, was zu tun ist.
        Settings.Secure.putString(kontext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "${kontext.packageName}/.WebseitenDienst")
        a.schritt4Zeigen()
        assertEquals(kontext.getString(R.string.schritt4_fertig), text(R.id.schritt4_titel))
        assertEquals(kontext.getString(R.string.schritt4_haengt), text(R.id.schritt4_stand))
        assertEquals(View.GONE, a.findViewById<View>(R.id.schritt4_anleitung).visibility)

        // Läuft: erst „noch keine Webseite“, dann die zuletzt erkannte.
        val info = AccessibilityServiceInfo()
        ReflectionHelpers.setField(info, "mResolveInfo", ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = kontext.packageName; name = WebseitenDienst::class.java.name } })
        shadowOf(kontext.getSystemService(AccessibilityManager::class.java)).setEnabledAccessibilityServiceList(listOf(info))
        a.schritt4Zeigen()
        assertEquals(kontext.getString(R.string.schritt4_noch_nichts), text(R.id.schritt4_stand))
        Beobachtungen(kontext).merken(Beobachtung(um(21, 35), ff, "youtube.com"))
        a.schritt4Zeigen()
        assertEquals("Läuft. Zuletzt erkannt: youtube.com in Firefox, ${zeitpunktText(um(21, 35), um(21, 40))}; in Chrome noch keine Seite.", text(R.id.schritt4_stand))
        // Je Browser: dann auch Chrome; eine Seite ohne Namen zählt dafür nicht.
        Beobachtungen(kontext).merken(Beobachtung(um(21, 36), CHROME, "spiegel.de"))
        Beobachtungen(kontext).merken(Beobachtung(um(21, 37), CHROME, OHNE_DOMAIN))
        a.schritt4Zeigen()
        assertEquals(
            "Läuft. Zuletzt erkannt: youtube.com in Firefox, ${zeitpunktText(um(21, 35), um(21, 40))}; spiegel.de in Chrome, ${zeitpunktText(um(21, 36), um(21, 40))}.",
            text(R.id.schritt4_stand),
        )
    }
}
