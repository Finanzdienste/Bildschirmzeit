package de.finanzdienste.bildschirmzeit

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Der Einstieg bildschirmzeit://firefox?url=… für die Bewerbungszentrale
 * (FirefoxEinstieg.kt; deren README „Links in einem richtigen Firefox-Tab“):
 * Die Adresse geht als ACTION_VIEW an Firefox, im neuen Task und ohne Extras,
 * ohne Firefox an den Browser, den Android wählt; nur http und https; keine
 * Oberfläche. Ohne Netz (es geht nur ein Intent auf dem Handy hinaus).
 * Erfundene Adressen.
 */
@RunWith(RobolectricTestRunner::class)
class FirefoxEinstiegTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val anzeige = "https://www.beispiel-jobs.de/stelle/123?quelle=radar&ort=Hamburg%20Mitte&x=a+b#bewerben"

    /** So baut die Bewerbungszentrale den Link (encodeURIComponent; Uri.encode kodiert dieselben Zeichen). */
    private fun link(url: String) = Uri.parse("${FirefoxEinstieg.SCHEMA}://${FirefoxEinstieg.ZIEL}?url=${Uri.encode(url)}")

    private fun browser(paket: String) {
        val pm = shadowOf(kontext.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = paket; applicationInfo = ApplicationInfo().apply { packageName = paket } })
        val k = ComponentName(paket, "$paket.Links")
        pm.addActivityIfNotPresent(k)
        pm.addIntentFilterForActivity(
            k,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
                addDataScheme("https")
            },
        )
    }

    @Before
    fun ohneNetz() {
        Netz.gesperrt = true
        // Wie auf dem Handy: Was keine App öffnen kann, wirft ActivityNotFoundException.
        shadowOf(kontext).checkActivities(true)
    }

    @After
    fun zurueck() = shadowOf(kontext).checkActivities(false)

    @Test
    fun nurHttpUndHttps() {
        assertEquals(Uri.parse(anzeige), FirefoxEinstieg.adresse(link(anzeige)))
        assertEquals(anzeige, FirefoxEinstieg.adresse(link(anzeige)).toString())
        assertEquals("http://beispiel.de/", FirefoxEinstieg.adresse(link("http://beispiel.de/")).toString())
        assertEquals("HTTPS://Beispiel.de/a", FirefoxEinstieg.adresse(link("HTTPS://Beispiel.de/a")).toString())
        for (schlecht in listOf(
            "javascript:alert(1)",
            "file:///sdcard/Download/x.html",
            "intent://x#Intent;scheme=https;package=org.mozilla.firefox;end",
            "fenix://open?url=https%3A%2F%2Fbeispiel.de",
            "content://beispiel/datei",
            "data:text/html,hallo",
            "bildschirmzeit://firefox?url=https%3A%2F%2Fbeispiel.de",
            "https://",
            "/stelle/123",
            "",
        )) {
            assertNull(schlecht, FirefoxEinstieg.adresse(link(schlecht)))
        }
        assertNull(FirefoxEinstieg.adresse(Uri.parse("bildschirmzeit://firefox")))
        assertNull(FirefoxEinstieg.adresse(Uri.parse("bildschirmzeit://ki?url=${Uri.encode(anzeige)}")))
        assertNull(FirefoxEinstieg.adresse(Uri.parse("https://beispiel.de/?url=${Uri.encode(anzeige)}")))
        assertNull(FirefoxEinstieg.adresse(null))
    }

    /** Firefox findet den Einstieg nur mit VIEW, DEFAULT und BROWSABLE (BROWSABLE hängt es an jeden Link). */
    @Test
    fun firefoxFindetDenEinstieg() {
        val treffer = kontext.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, link(anzeige)).addCategory(Intent.CATEGORY_BROWSABLE), 0)
        assertEquals(listOf(FirefoxOeffnen::class.java.name), treffer.map { it.activityInfo.name })
    }

    @Test
    fun oeffnetInFirefoxOhneOberflaeche() {
        browser(FirefoxEinstieg.PAKET)
        browser("com.beispiel.browser")
        val steuerung = Robolectric.buildActivity(FirefoxOeffnen::class.java, Intent(Intent.ACTION_VIEW, link(anzeige)).addCategory(Intent.CATEGORY_BROWSABLE)).create()
        val gestartet = shadowOf(steuerung.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, gestartet.action)
        assertEquals(anzeige, gestartet.dataString)
        assertEquals(FirefoxEinstieg.PAKET, gestartet.`package`)
        assertTrue("neuer Task", gestartet.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // Keine Custom-Tab-Extras (sonst wird es wieder das kleine Fenster) – gar keine.
        assertTrue(gestartet.extras?.keySet().orEmpty().toString(), gestartet.extras == null || gestartet.extras!!.isEmpty)
        assertTrue("sofort zu", steuerung.get().isFinishing)
        assertNull(shadowOf(steuerung.get()).nextStartedActivity)
    }

    @Test
    fun ohneFirefoxDerBrowserVonAndroid() {
        browser("com.beispiel.browser")
        val steuerung = Robolectric.buildActivity(FirefoxOeffnen::class.java, Intent(Intent.ACTION_VIEW, link(anzeige))).create()
        val gestartet = shadowOf(steuerung.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, gestartet.action)
        assertEquals(anzeige, gestartet.dataString)
        assertNull(gestartet.`package`)
        assertTrue(gestartet.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(steuerung.get().isFinishing)
    }

    @Test
    fun fremdeAdresseOeffnetNichts() {
        browser(FirefoxEinstieg.PAKET)
        for (schlecht in listOf("javascript:alert(1)", "file:///sdcard/x.html", "intent://x#Intent;scheme=https;end")) {
            val steuerung = Robolectric.buildActivity(FirefoxOeffnen::class.java, Intent(Intent.ACTION_VIEW, link(schlecht))).create()
            assertNull(schlecht, shadowOf(steuerung.get()).nextStartedActivity)
            assertTrue(steuerung.get().isFinishing)
        }
    }

    /** Gar kein Browser: nichts geht auf, die App stürzt nicht ab und ist trotzdem gleich zu. */
    @Test
    fun ohneBrowserKeinAbsturz() {
        val ohne = Robolectric.buildActivity(FirefoxOeffnen::class.java, Intent(Intent.ACTION_VIEW, link("https://beispiel.de/"))).create()
        assertNull(shadowOf(ohne.get()).nextStartedActivity)
        assertTrue(ohne.get().isFinishing)
    }
}
