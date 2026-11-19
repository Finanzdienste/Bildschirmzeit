package de.finanzdienste.bildschirmzeit

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.Process
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
 * Der App-Bildschirm mit erfundenem Verlauf, ganz heruntergescrollt als ein
 * Bild: docs/vorschau-app-hell.png und -dunkel.png (Woche mit Balken),
 * vorschau-app-monat-*.png (Monat mit Lücken „keine Daten“, nur die
 * Statistik), vorschau-app-tag-hell.png (der Reiter Tag) und
 * vorschau-app-einrichten-hell.png (ohne Erlaubnis), vorschau-app-eine-*.png
 * (eine App angetippt: ihre Woche). Das Netz ist gesperrt –
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
        Verlauf(RuntimeEnvironment.getApplication()).ordner.deleteRecursively()
        // Ohne Erlaubnis rechnet die App selbst nichts in den Verlauf (sonst stünde dort der echte heutige Tag).
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            RuntimeEnvironment.getApplication().packageName,
            AppOpsManager.MODE_IGNORED,
        )
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

    /**
     * Die Update-Karte: hell, wie Tobi sie nach dem Installieren dieser Fassung
     * sieht (neue Version da, noch nicht eingerichtet); dunkel eingerichtet und
     * aktuell. Feste Zahlen statt BuildConfig – wie die Version unten.
     */
    private fun update(eingerichtet: Boolean, neu: Boolean = !eingerichtet) = UpdateLage(
        eigene = 22,
        eigeneName = VORSCHAU_VERSION,
        freigabe = Freigabe(
            if (neu) 23 else 22,
            "https://github.com/Finanzdienste/Bildschirmzeit/releases/download/v23/bildschirmzeit.apk",
            "0".repeat(64),
            774_584,
        ),
        darf = eingerichtet,
        still = true,
        laeuft = false,
        fehler = null,
        fehlerStatus = 0,
        erfolg = if (eingerichtet) 22 else 0,
        erfolgUm = stand - 5 * 3_600_000L,
        geprueft = stand - 17 * 60_000L,
        jetzt = stand,
    )

    @Test
    fun appHell() = aufnehmen("app-hell", 0xFFF2F3F6.toInt(), update = update(eingerichtet = false))

    @Test
    @Config(qualifiers = "+night")
    fun appDunkel() = aufnehmen("app-dunkel", 0xFF111215.toInt(), update = update(eingerichtet = true))

    @Test
    fun monatHell() = aufnehmen("app-monat-hell", 0xFFF2F3F6.toInt(), update = update(eingerichtet = true), reiter = Reiter.MONAT, nurStatistik = true)

    @Test
    @Config(qualifiers = "+night")
    fun monatDunkel() = aufnehmen("app-monat-dunkel", 0xFF111215.toInt(), update = update(eingerichtet = true), reiter = Reiter.MONAT, nurStatistik = true)

    @Test
    fun tagHell() = aufnehmen("app-tag-hell", 0xFFF2F3F6.toInt(), update = update(eingerichtet = true), reiter = Reiter.TAG, nurStatistik = true)

    /** Tobi, 15.11.: „… auf ne eigene App klicken und dann deren verteilung zb über die Woche sehen“. */
    @Test
    fun eineAppHell() = aufnehmen("app-eine-hell", 0xFFF2F3F6.toInt(), update = update(eingerichtet = true), nurStatistik = true, app = "Workout")

    @Test
    @Config(qualifiers = "+night")
    fun eineAppDunkel() = aufnehmen("app-eine-dunkel", 0xFF111215.toInt(), update = update(eingerichtet = true), nurStatistik = true, app = "WhatsApp")

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
        // So heißt der Hinweis auf Tobis S24 (One UI 8, 15.11.): „App wurde Zugriff verweigert“, Knopf „Schließen“.
        val ok = text.indexOf("„App wurde Zugriff verweigert“ – mit „Schließen“")
        val menue = text.indexOf("oben rechts ⋮ → „Eingeschränkte Einstellungen zulassen“")
        assertTrue("erst den grauen Schalter antippen: $text", antippen >= 0)
        assertTrue("den Hinweis „App wurde Zugriff verweigert“ schließen: $text", ok > antippen)
        assertTrue("danach erst das Menü in der App-Info: $text", menue > ok)
        assertTrue("„Gesteuert durch eingeschränkte Einstellung“ – so steht es auf dem Handy: $text", text.contains("Gesteuert durch eingeschränkte Einstellung"))
        // Tobis S24 (15.11., 08:24): auch danach kein ⋮ – Samsungs Automatische Sperre war wieder an.
        val sperre = text.indexOf("Automatische Sperre aus")
        assertTrue("Fehlt ⋮: die Automatische Sperre ausschalten, nach dem Weg über die App-Info: $text", sperre > menue)
        assertTrue("App-Info auch über die Einstellungen: $text", text.contains("Einstellungen → Apps → Bildschirmzeit"))
    }

    @Test
    fun appEinrichtenHell() = aufnehmen("app-einrichten-hell", 0xFFF2F3F6.toInt(), ohneErlaubnis(), update(eingerichtet = false, neu = false), verlauf = false)

    private fun ohneErlaubnis() = beispiel().copy(erlaubt = false, posten = emptyList(), web = WebDaten.LEER)

    @Test
    fun ohneVerbindungSagtEsDas() {
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        aktivitaet.zeigen(beispiel().copy(web = WebDaten.LEER))
        val liste = aktivitaet.findViewById<ViewGroup>(R.id.web_liste)
        assertEquals(WebApp.entries.map { it.anzeigename }, (0 until liste.childCount).map { liste.getChildAt(it).findViewById<TextView>(R.id.name).text.toString() })
        assertEquals(listOf("Boxenstopp", "Freizeit", "Workout", "Bewerbungen"), WebApp.entries.map { it.anzeigename })
        for (i in 0 until liste.childCount) {
            assertEquals("noch nicht verbunden", liste.getChildAt(i).findViewById<TextView>(R.id.stand).text.toString())
        }
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

    private fun aufnehmen(
        name: String,
        hintergrund: Int,
        ergebnis: Ergebnis = beispiel(),
        update: UpdateLage,
        reiter: Reiter = Reiter.WOCHE,
        nurStatistik: Boolean = false,
        verlauf: Boolean = true,
        app: String? = null,
    ) {
        if (verlauf) erfundenerVerlauf(RuntimeEnvironment.getApplication(), beispiel().posten)
        Speicher(RuntimeEnvironment.getApplication()).reiter = reiter.name
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        aktivitaet.zeigen(ergebnis)
        // Feste Version statt BuildConfig (lokal 1.1, in Actions 1.<Laufnummer>):
        // Sonst änderte jeder Bau die Bilder in docs/, ohne dass sich etwas geändert hat.
        aktivitaet.versionZeigen(VORSCHAU_VERSION)
        aktivitaet.updateZeigen(UpdateRegeln.anzeige(update))
        // Feste Uhr: Samstag, 14.11.2026, 21:40 – die Woche 9.–15. Nov., der Sonntag kommt noch.
        aktivitaet.uhrStellen { stand }
        warten()
        if (app != null) {
            // Wie Tobis Finger: die Zeile der App in der Liste antippen.
            val liste = aktivitaet.findViewById<ViewGroup>(R.id.app_liste)
            (0 until liste.childCount).map { liste.getChildAt(it) }.first { it.findViewById<android.widget.TextView>(R.id.name).text == app }.performClick()
            warten()
        }

        val inhalt = (aktivitaet.findViewById<ViewGroup>(R.id.wurzel)).getChildAt(0)
        val dichte = aktivitaet.resources.displayMetrics.density
        val breite = (384 * dichte).roundToInt()
        inhalt.measure(View.MeasureSpec.makeMeasureSpec(breite, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        inhalt.layout(0, 0, breite, inhalt.measuredHeight)
        val hoehe = if (nurStatistik) aktivitaet.findViewById<View>(R.id.statistik_karte).bottom + (16 * dichte).roundToInt() else inhalt.measuredHeight
        val bild = Bitmap.createBitmap(breite, hoehe, Bitmap.Config.ARGB_8888)
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

/**
 * Ein erfundener Verlauf für Vorschau und Tests: 3. bis 14. November 2026,
 * ohne den 7. und 8. (Handy aus) – heute (14.11.) mit `heute`. Keine echten Zahlen.
 */
internal fun erfundenerVerlauf(kontext: android.content.Context, heute: List<Posten>) {
    val verlauf = Verlauf(kontext)
    verlauf.ordner.deleteRecursively()
    val apps = listOf(
        Triple("workout", "Workout", 30 to 41),
        Triple("com.beispiel.chat", "WhatsApp", 22 to 35),
        Triple("boxenstopp", "Boxenstopp", 8 to 27),
        Triple("com.android.chrome", "Chrome", 12 to 30),
        Triple("com.beispiel.video", "Videos", 0 to 64),
        Triple("com.beispiel.karten", "Karten", 0 to 23),
        Triple("freizeit", "Freizeit", 4 to 17),
        Triple("com.beispiel.kamera", "Kamera", 0 to 11),
    )
    for (d in 3..13) {
        if (d == 7 || d == 8) continue
        val datum = java.time.LocalDate.of(2026, 11, d)
        val posten = apps.mapIndexed { i, (k, n, spanne) ->
            val (min, weite) = spanne
            Posten(k, n, (min + (d * 7 + i * 13) % (weite + 1)) * 60L)
        }
        verlauf.schreiben(Tag(datum, sortiert(posten), fertig = true))
    }
    verlauf.schreiben(Tag(java.time.LocalDate.of(2026, 11, 14), sortiert(heute), fertig = false))
}
