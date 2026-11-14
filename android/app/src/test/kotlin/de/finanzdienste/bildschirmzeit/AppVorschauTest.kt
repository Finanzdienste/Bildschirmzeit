package de.finanzdienste.bildschirmzeit

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

    /** Hintergrundfäden (Rechnung, Versionsprüfung) laufen ohne Netz schnell zu Ende. */
    private fun warten() {
        repeat(20) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun aufnehmen(name: String, hintergrund: Int) {
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        aktivitaet.zeigen(beispiel())
        aktivitaet.updateZeigen(23)
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
