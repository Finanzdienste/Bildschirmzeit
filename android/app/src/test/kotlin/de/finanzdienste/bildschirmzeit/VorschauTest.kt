package de.finanzdienste.bildschirmzeit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Zeichnet das Widget mit erfundenen Zahlen in docs/vorschau-*.png – so sieht
 * man ohne Handy, wie es aussieht (hell/dunkel, 4×2 und 2×2). Die Größen
 * sind die eines S24-Startbildschirms mit 4×5-Raster (etwa 352 × 176 dp für
 * 4×2). Ohne Rahmen (für die Widget-Auswahl) landen die Bilder zusätzlich in
 * build/vorschau.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class VorschauTest {
    private val kontext get() = RuntimeEnvironment.getApplication()

    private val stand = ZonedDateTime.of(2026, 11, 14, 21, 40, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val verbunden = WebDaten(emptyMap(), mapOf("workout" to stand, "boxenstopp" to stand), ohneNetz = false, gelesen = stand)

    /** 3 Std. 12 Min.: Workout 54, WhatsApp 41, Boxenstopp 28, dazu kleinere. */
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
        web = verbunden,
    )

    /** Ein langer Tag ohne Netz: lange Zeiten, lange Namen. */
    private fun langerTag() = Ergebnis(
        erlaubt = true,
        posten = listOf(
            Posten("com.beispiel.video", "Videos und Serien", 2 * 3600L + 41 * 60L),
            Posten("workout", "Workout", 1 * 3600L + 5 * 60L),
            Posten("com.beispiel.einstellungen", "Einstellungen", 59 * 60L),
            Posten("com.beispiel.karten", "Karten", 3 * 3600L),
        ),
        stand = stand,
        web = verbunden.copy(ohneNetz = true),
    )

    @Test
    fun grossHell() = speichern("gross-hell", WidgetAnsicht.gross(kontext, beispiel(), 352f, 176f), 352f, 176f, nacht = false)

    @Test
    @Config(qualifiers = "+night")
    fun grossDunkel() = speichern("gross-dunkel", WidgetAnsicht.gross(kontext, beispiel(), 352f, 176f), 352f, 176f, nacht = true)

    @Test
    fun kleinHell() = speichern("klein-hell", WidgetAnsicht.klein(kontext, beispiel(), 168f, 176f), 168f, 176f, nacht = false)

    @Test
    @Config(qualifiers = "+night")
    fun kleinDunkel() = speichern("klein-dunkel", WidgetAnsicht.klein(kontext, beispiel(), 168f, 176f), 168f, 176f, nacht = true)

    @Test
    fun langerTagHell() = speichern("lang-hell", WidgetAnsicht.gross(kontext, langerTag(), 300f, 176f), 300f, 176f, nacht = false)

    @Test
    fun flachHell() = speichern("flach-hell", WidgetAnsicht.gross(kontext, beispiel(), 352f, 120f), 352f, 120f, nacht = false)

    @Test
    fun einrichtenHell() = speichern("einrichten-hell", WidgetAnsicht.einrichten(kontext), 352f, 176f, nacht = false)

    @Test
    fun schmalBleibenDieNamenGanz() {
        // 3×2 (300 dp, auf manchen Rastern nur gut 260 dp) mit langen Zeiten:
        // „Workout“ und „Einstellungen“ ganz, dafür ohne App-Symbol.
        for (breite in listOf(300f, 264f)) {
            val view = aufgebaut(WidgetAnsicht.gross(kontext, langerTag(), breite, 176f), breite, 176f)
            assertEquals("Workout", view.findViewById<TextView>(R.id.name2).text.toString())
            assertEquals("$breite dp: Workout gekürzt", 0, view.findViewById<TextView>(R.id.name2).layout.getEllipsisCount(0))
            if (breite >= 300f) {
                assertEquals("$breite dp: Einstellungen gekürzt", 0, view.findViewById<TextView>(R.id.name3).layout.getEllipsisCount(0))
            }
            assertEquals(View.GONE, view.findViewById<View>(R.id.symbol2).visibility)
        }
        val breit = aufgebaut(WidgetAnsicht.gross(kontext, langerTag(), 352f, 176f), 352f, 176f)
        assertEquals(View.VISIBLE, breit.findViewById<View>(R.id.symbol2).visibility)
        assertEquals(0, breit.findViewById<TextView>(R.id.name2).layout.getEllipsisCount(0))
    }

    @Test
    fun auswahlBilder() {
        // Für res/drawable-nodpi/widget_vorschau.png (und -night-): ohne Hintergrund.
        val ordner = File(System.getProperty("vorschau.bau") ?: return)
        ordner.mkdirs()
        val bild = zeichnen(WidgetAnsicht.gross(kontext, beispiel(), 352f, 176f), 352f, 176f, rahmen = 0, hintergrund = 0)
        File(ordner, "widget_vorschau_hell.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "+night")
    fun auswahlBilderDunkel() {
        val ordner = File(System.getProperty("vorschau.bau") ?: return)
        ordner.mkdirs()
        val bild = zeichnen(WidgetAnsicht.gross(kontext, beispiel(), 352f, 176f), 352f, 176f, rahmen = 0, hintergrund = 0)
        File(ordner, "widget_vorschau_dunkel.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun speichern(name: String, ansicht: RemoteViews, breite: Float, hoehe: Float, nacht: Boolean) {
        // Ein ruhiger „Hintergrundbild“-Ton, damit man die Ecken der Karte sieht.
        val bild = zeichnen(ansicht, breite, hoehe, rahmen = 20, hintergrund = if (nacht) 0xFF0D1015.toInt() else 0xFFD9DFE7.toInt())
        val ordner = File(System.getProperty("vorschau.ordner") ?: "build/vorschau")
        ordner.mkdirs()
        val datei = File(ordner, "vorschau-$name.png")
        datei.outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(datei.length() > 1000)
    }

    /** Die RemoteViews als echte Views, gemessen und gelegt in breite × hoehe dp. */
    private fun aufgebaut(ansicht: RemoteViews, breite: Float, hoehe: Float): View {
        val dichte = kontext.resources.displayMetrics.density
        val b = (breite * dichte).roundToInt()
        val h = (hoehe * dichte).roundToInt()
        val view = ansicht.apply(kontext, FrameLayout(kontext))
        view.measure(View.MeasureSpec.makeMeasureSpec(b, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, b, h)
        return view
    }

    private fun zeichnen(ansicht: RemoteViews, breite: Float, hoehe: Float, rahmen: Int, hintergrund: Int): Bitmap {
        val dichte = kontext.resources.displayMetrics.density
        val b = (breite * dichte).roundToInt()
        val h = (hoehe * dichte).roundToInt()
        val r = (rahmen * dichte).roundToInt()
        val view = aufgebaut(ansicht, breite, hoehe)
        val bild = Bitmap.createBitmap(b + 2 * r, h + 2 * r, Bitmap.Config.ARGB_8888)
        val leinwand = Canvas(bild)
        leinwand.drawColor(hintergrund)
        leinwand.translate(r.toFloat(), r.toFloat())
        view.draw(leinwand)
        return bild
    }
}
