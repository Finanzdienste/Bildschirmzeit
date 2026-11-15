package de.finanzdienste.bildschirmzeit

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Der App-Bildschirm mit erfundenen Zahlen, ganz heruntergescrollt als ein
 * Bild: docs/vorschau-app-hell.png und -dunkel.png. Das Netz ist gesperrt –
 * kein Test spricht mit dem echten Supabase oder GitHub.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class AppVorschauTest {
    private companion object {
        const val VORSCHAU_VERSION = "1.22"
    }

    private val stand = ZonedDateTime.of(2026, 11, 14, 21, 40, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    // Bleibt gesperrt (auch nach dem Test): Die Fäden der Activity könnten nachlaufen.
    @Before
    fun netzSperren() {
        Netz.gesperrt = true
        // In Robolectric startet androidx.startup nicht von selbst.
        try {
            WorkManager.initialize(RuntimeEnvironment.getApplication(), Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
    }

    private fun beispiel() = Ergebnis(
        erlaubt = true,
        posten = listOf(
            Posten("workout", "Workout", 54 * 60L),
            Posten("com.beispiel.chat", "WhatsApp", 41 * 60L),
            Posten("boxenstopp", "Boxenstopp", 28 * 60L),
            Posten("com.android.chrome", "Chrome", 26 * 60L),
            Posten("com.beispiel.karten", "Karten", 21 * 60L),
            Posten("freizeit", "Freizeit", 14 * 60L),
            Posten("com.beispiel.kamera", "Kamera", 8 * 60L),
        ),
        stand = stand,
        web = WebDaten(
            heute = mapOf("workout" to 54 * 60L, "boxenstopp" to 28 * 60L, "freizeit" to 14 * 60L),
            zuletzt = mapOf("workout" to stand, "boxenstopp" to stand - 40 * 60_000L, "freizeit" to stand - 3 * 3_600_000L),
            ohneNetz = false,
            gelesen = stand,
        ),
    )

    @Test
    fun appHell() = aufnehmen("app-hell", 0xFFF2F3F6.toInt())

    @Test
    @Config(qualifiers = "+night")
    fun appDunkel() = aufnehmen("app-dunkel", 0xFF111215.toInt())

    /**
     * Tobis S24 (15.11.): Der Schalter war grau, „Gesteuert durch eingeschränkte
     * Einstellung“, und in der App-Info fehlte ⋮. Das Menü gibt es erst, wenn man
     * den grauen Schalter einmal angetippt und den Hinweis geschlossen hat – der
     * Hinweis sagt das als ersten Schritt, vor dem Weg über die App-Info; fehlt ⋮
     * auch dann, die Automatische Sperre von Samsung kurz ausschalten.
     */
    @Test
    fun ohneErlaubnisErstDenGrauenSchalterAntippen() {
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        aktivitaet.zeigen(ohneErlaubnis())
        shadowOf(Looper.getMainLooper()).idle()
        val hinweis = aktivitaet.findViewById<TextView>(R.id.schritt1_hinweis)
        assertEquals(View.VISIBLE, hinweis.visibility)
        assertEquals(View.VISIBLE, aktivitaet.findViewById<View>(R.id.app_info).visibility)
        val text = hinweis.text.toString()
        val antippen = text.indexOf("Trotzdem auf „Berechtigung erteilen“ tippen")
        val ok = text.indexOf("„OK“")
        val menue = text.indexOf("oben rechts ⋮ → „Eingeschränkte Einstellungen zulassen“")
        assertTrue("erst den grauen Schalter antippen: $text", antippen >= 0)
        assertTrue("den Hinweis mit OK schließen: $text", ok > antippen)
        assertTrue("danach erst das Menü in der App-Info: $text", menue > ok)
        assertTrue("„Gesteuert durch eingeschränkte Einstellung“ – so steht es auf dem Handy: $text", text.contains("Gesteuert durch eingeschränkte Einstellung"))
        // Tobis S24 (15.11., 08:24): auch danach kein ⋮ – Samsungs Automatische Sperre war wieder an.
        val sperre = text.indexOf("Automatische Sperre aus")
        assertTrue("Fehlt ⋮: die Automatische Sperre ausschalten, nach dem Weg über die App-Info: $text", sperre > menue)
        assertTrue("App-Info auch über die Einstellungen: $text", text.contains("Einstellungen → Apps → Bildschirmzeit"))
    }

    @Test
    fun appEinrichtenHell() = aufnehmen("app-einrichten-hell", 0xFFF2F3F6.toInt(), ohneErlaubnis())

    private fun ohneErlaubnis() = beispiel().copy(erlaubt = false, posten = emptyList(), web = WebDaten.LEER)

    @Test
    fun ohneVerbindungSagtEsDas() {
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        aktivitaet.zeigen(beispiel().copy(web = WebDaten.LEER))
        val liste = aktivitaet.findViewById<ViewGroup>(R.id.web_liste)
        assertEquals(3, liste.childCount)
        for (i in 0 until 3) {
            assertEquals("noch nicht verbunden", liste.getChildAt(i).findViewById<TextView>(R.id.stand).text.toString())
        }
        assertEquals(7, aktivitaet.findViewById<ViewGroup>(R.id.app_liste).childCount)
    }

    @Test
    fun verbindenOeffnetChrome() {
        // Angemeldet: Verbinden geht ohne Netz gleich zum Link.
        angemeldet()
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        aktivitaet.findViewById<ViewGroup>(R.id.web_liste).findViewWithTag<View>("workout")
            .findViewById<View>(R.id.verbinden).performClick()
        warten()
        val geoeffnet = shadowOf(aktivitaet).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, geoeffnet.action)
        assertEquals(CHROME, geoeffnet.`package`)
        assertTrue(geoeffnet.dataString!!.startsWith("https://finanzdienste.github.io/Workout/#bildschirmzeit="))
    }

    @Test
    fun ohneChromeDerStandardbrowser() {
        val link = WebApp.FREIZEIT.link("x".repeat(43))
        val absichten = verbindenAbsichten(link)
        assertEquals(listOf(CHROME, null), absichten.map { it.`package` })
        assertEquals(listOf(link, link), absichten.map { it.dataString })

        // Auf dem „Gerät“ gibt es nur einen anderen Browser: Der Chrome-Versuch scheitert, der zweite geht.
        val app = RuntimeEnvironment.getApplication()
        val browser = ComponentName("com.beispiel.browser", "com.beispiel.browser.Haupt")
        shadowOf(app.packageManager).apply {
            installPackage(PackageInfo().apply { packageName = browser.packageName })
            addActivityIfNotPresent(browser)
            addIntentFilterForActivity(
                browser,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addDataScheme("https")
                },
            )
        }
        angemeldet()
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        shadowOf(app).checkActivities(true)
        aktivitaet.findViewById<ViewGroup>(R.id.web_liste).findViewWithTag<View>("freizeit")
            .findViewById<View>(R.id.verbinden).performClick()
        warten()
        val geoeffnet = shadowOf(aktivitaet).nextStartedActivity
        assertEquals(null, geoeffnet.`package`)
        assertTrue(geoeffnet.dataString!!.startsWith("https://finanzdienste.github.io/Freizeit/#bildschirmzeit="))
    }

    /** Eine (erfundene) Kennung, die Supabase schon kennt – erst erzeugen, dann als angemeldet merken. */
    private fun angemeldet() {
        val speicher = Speicher(RuntimeEnvironment.getApplication())
        speicher.kennung
        speicher.angemeldet = true
    }

    /** Hintergrundfäden (Rechnung, Versionsprüfung) laufen ohne Netz schnell zu Ende. */
    private fun warten() {
        repeat(20) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun aufnehmen(name: String, hintergrund: Int, ergebnis: Ergebnis = beispiel()) {
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        aktivitaet.zeigen(ergebnis)
        // Feste Version statt BuildConfig (lokal 1.1, in Actions 1.<Laufnummer>):
        // Sonst änderte jeder Bau die Bilder in docs/, ohne dass sich etwas geändert hat.
        aktivitaet.versionZeigen(VORSCHAU_VERSION)
        aktivitaet.updateZeigen(23, VORSCHAU_VERSION)
        shadowOf(Looper.getMainLooper()).idle()

        val inhalt = (aktivitaet.findViewById<ViewGroup>(R.id.wurzel)).getChildAt(0)
        val dichte = aktivitaet.resources.displayMetrics.density
        val breite = (384 * dichte).roundToInt()
        inhalt.measure(View.MeasureSpec.makeMeasureSpec(breite, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        inhalt.layout(0, 0, breite, inhalt.measuredHeight)
        val bild = Bitmap.createBitmap(breite, inhalt.measuredHeight, Bitmap.Config.ARGB_8888)
        val leinwand = Canvas(bild)
        leinwand.drawColor(hintergrund)
        inhalt.draw(leinwand)
        val ordner = File(System.getProperty("vorschau.ordner") ?: "build/vorschau")
        ordner.mkdirs()
        val datei = File(ordner, "vorschau-$name.png")
        datei.outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(datei.length() > 1000)
    }
}
