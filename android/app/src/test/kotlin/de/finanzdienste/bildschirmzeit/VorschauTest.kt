package de.finanzdienste.bildschirmzeit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Zeichnet das Widget mit erfundenen Zahlen in docs/vorschau-*.png – so sieht
 * man ohne Handy, wie es aussieht (hell/dunkel) – und prüft an den echten
 * Views, dass nichts abgeschnitten wird. Die Bilder haben die Größen von
 * Tobis S24 (4×2 = 400 × 201 dp, aus seinem Screenshot gemessen; 2×2, 3×2,
 * 4×1 davon abgeleitet). Ohne Rahmen (für die Widget-Auswahl) landen die Bilder
 * zusätzlich in build/vorschau.
 *
 * Tobi, 15.11.: „"Andere Apps" ist ja scheiße wir brauchen diesen Kreis links
 * nicht. Was ich will ist die gesamtzzeit alles Apps zu sehen und Vllt so die
 * top 8 oder so mit der größten nutzungszeit. Je nachdem wieviel hin passt.“
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class VorschauTest {
    private val kontext get() = RuntimeEnvironment.getApplication()

    private val stand = ZonedDateTime.of(2026, 11, 14, 21, 40, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val verbunden = WebDaten(emptyMap(), mapOf("workout" to stand, "boxenstopp" to stand), ohneNetz = false, gelesen = stand)

    /** 4 Std. 7 Min. in zehn Apps (erfunden). */
    private fun beispiel() = Ergebnis(
        erlaubt = true,
        posten = listOf(
            Posten("boxenstopp", "Boxenstopp", 69 * 60L),
            Posten("com.beispiel.chat", "WhatsApp", 54 * 60L),
            Posten("workout", "Workout", 41 * 60L),
            Posten("com.beispiel.fotos", "Instagram", 28 * 60L),
            Posten("com.android.chrome", "Chrome", 21 * 60L),
            Posten("com.beispiel.beruf", "LinkedIn", 14 * 60L),
            Posten("freizeit", "Freizeit", 9 * 60L),
            Posten("com.beispiel.karten", "Karten", 6 * 60L),
            Posten("com.beispiel.kamera", "Kamera", 3 * 60L),
            Posten("com.beispiel.einstellungen", "Einstellungen", 2 * 60L),
        ),
        stand = stand,
        web = verbunden,
    )

    /** Ein langer Tag ohne Netz: lange Zeiten, lange Namen, über zehn Stunden. */
    private fun langerTag() = Ergebnis(
        erlaubt = true,
        posten = listOf(
            Posten("com.beispiel.video", "Videos und Serien", 4 * 3600L + 12 * 60L),
            Posten("com.beispiel.karten", "Navigation und Karten", 2 * 3600L + 41 * 60L),
            Posten("workout", "Workout", 1 * 3600L + 5 * 60L),
            Posten("com.beispiel.einstellungen", "Einstellungen", 59 * 60L),
            Posten("boxenstopp", "Boxenstopp", 48 * 60L),
            Posten("com.beispiel.chat", "WhatsApp", 43 * 60L),
        ),
        stand = stand,
        web = verbunden.copy(ohneNetz = true),
    )

    @After
    fun schriftZurueck() = RuntimeEnvironment.setFontScale(1f)

    @Test
    fun grossHell() = speichern("gross-hell", beispiel(), 400f, 201f, nacht = false)

    @Test
    @Config(qualifiers = "+night")
    fun grossDunkel() = speichern("gross-dunkel", beispiel(), 400f, 201f, nacht = true)

    @Test
    fun kleinHell() = speichern("klein-hell", beispiel(), 200f, 201f, nacht = false)

    @Test
    @Config(qualifiers = "+night")
    fun kleinDunkel() = speichern("klein-dunkel", beispiel(), 200f, 201f, nacht = true)

    @Test
    fun langerTagHell() = speichern("lang-hell", langerTag(), 300f, 201f, nacht = false)

    @Test
    fun flachHell() = speichern("flach-hell", beispiel(), 400f, 100f, nacht = false)

    @Test
    fun einrichtenHell() = speichern("einrichten-hell", beispiel().copy(erlaubt = false), 400f, 201f, nacht = false)

    @Test
    fun updateHell() = speichern("update-hell", beispiel().copy(updateHinweis = true), 400f, 201f, nacht = false)

    /**
     * Tobis Widget (400 × 201 dp): acht Apps in zwei Spalten, links Platz 1–4,
     * rechts 5–8, kein Ring, kein „Andere Apps“, oben „Heute“ und die
     * Gesamtzeit aller Apps. Namen und Zeiten ganz.
     */
    @Test
    fun tobisWidgetZeigtAchtAppsInZweiSpalten() {
        val view = aufgebaut(beispiel(), 400f, 201f)
        assertEquals(
            listOf("Boxenstopp", "WhatsApp", "Workout", "Instagram"),
            zeilen(view, R.id.spalte1).map { text(it, R.id.name) },
        )
        assertEquals(listOf("Chrome", "LinkedIn", "Freizeit", "Karten"), zeilen(view, R.id.spalte2).map { text(it, R.id.name) })
        assertEquals(listOf("1:09 Std.", "54 Min.", "41 Min.", "28 Min."), zeilen(view, R.id.spalte1).map { text(it, R.id.zeit) })
        for (z in zeilen(view, R.id.spalte1) + zeilen(view, R.id.spalte2)) {
            assertEquals(text(z, R.id.name), 0, ellipsen(z.findViewById(R.id.name)))
            assertEquals(View.VISIBLE, z.findViewById<View>(R.id.symbol).visibility)
        }
        assertEquals("Heute", text(view, R.id.titel))
        assertEquals("4 Std. 7 Min.", text(view, R.id.gesamt))
        assertEquals("Stand 21:40", text(view, R.id.stand))
        // Kein Ring, keine „Andere Apps“ – auch nicht im Stand.
        val texte = alleTexte(view)
        assertFalse(texte.toString(), texte.any { it.contains("ndere") })
        assertEquals(0, alleViews(view).count { it.id != View.NO_ID && kontext.resources.getResourceEntryName(it.id).startsWith("ring") })
        ganz(view, "400 × 201")
    }

    /**
     * Jede Größe: so viele Zeilen, wie das Raster sagt, jede ganz im Widget
     * (gemessene Lage, nicht measuredHeight – die wird geklemmt), der Stand und
     * „Heute“ ganz, die Gesamtzeit und die Zeiten nie gekürzt.
     */
    @Test
    fun jedeGroesseGanzOhneHalbeZeilen() {
        val groessen = listOf(
            400f to 201f, // Tobis 4×2
            352f to 176f, // 4×2 auf einem 4×5-Raster
            300f to 176f,
            264f to 176f, // 3×2 auf engen Rastern
            352f to 150f,
            400f to 100f, // 4×1
            352f to 100f,
            352f to 80f,
            170f to 170f, // 2×2
            200f to 201f,
            352f to 290f, // 4×3
            400f to 300f,
            170f to 290f, // 2×3
            170f to 100f, // 2×1
            250f to 110f, // ohne Größe vom Startbildschirm
            300f to 201f,
            352f to 60f, // flacher als jedes Raster: ohne Stand
            170f to 60f,
        )
        for ((breite, hoehe) in groessen) {
            for (ergebnis in listOf(beispiel(), langerTag())) {
                val wo = "$breite × $hoehe (${ergebnis.posten.size} Apps)"
                val view = aufgebaut(ergebnis, breite, hoehe)
                val raster = WidgetRaster.fuer(breite, hoehe)
                val alle = zeilen(view, R.id.spalte1) + zeilen(view, R.id.spalte2)
                assertEquals(wo, minOf(raster.plaetze, ergebnis.posten.size), alle.size)
                assertEquals(wo, ergebnis.posten.take(alle.size).map { it.name }, alle.map { text(it, R.id.name) })
                ganz(view, wo)
                // Ist die Spalte so breit, wie das Raster für die Schrift verlangt, bleiben Namen wie „Boxenstopp“ ganz.
                if (ergebnis.posten.first().name == "Boxenstopp") {
                    val mindestens = if (raster.kompakt) WidgetRaster.SPALTE_KOMPAKT else WidgetRaster.SPALTE
                    for (z in alle) {
                        if (z.width / kontext.resources.displayMetrics.density < mindestens) continue
                        assertEquals("$wo: ${text(z, R.id.name)} gekürzt", 0, ellipsen(z.findViewById(R.id.name)))
                    }
                    if (raster.spalten == 2 || !raster.kompakt) assertTrue(wo, alle.all { it.width / kontext.resources.displayMetrics.density >= mindestens - 0.5f })
                }
            }
        }
    }

    /** Tobis Größe mit größerer Schrift (Einstellungen → Anzeige → Schriftgröße): weniger Zeilen, abgeschnitten wird nichts. */
    @Test
    fun groessereSchriftSchneidetNichtsAb() {
        RuntimeEnvironment.setFontScale(1.3f)
        for ((breite, hoehe) in listOf(400f to 201f, 352f to 176f, 400f to 100f, 170f to 170f, 264f to 176f)) {
            val view = aufgebaut(beispiel(), breite, hoehe)
            val raster = WidgetRaster.fuer(breite, hoehe, 1.3f)
            assertEquals("$breite × $hoehe", raster.plaetze, (zeilen(view, R.id.spalte1) + zeilen(view, R.id.spalte2)).size)
            ganz(view, "$breite × $hoehe bei Schrift 130 %")
        }
    }

    /** Ein sehr langer Tag ohne Netz: die längste Gesamtzeit, die vorkommt („18 Std. 49 Min.“). */
    private fun extremerTag() = langerTag().let { e -> e.copy(posten = listOf(Posten("com.beispiel.video", "Videos und Serien", 11 * 3600L + 37 * 60L)) + e.posten.drop(1)) }

    /**
     * Größere Schrift (110 und 130 %) und schmale Widgets (2×2 auf engem
     * Raster, 170 dp): Der Kopf bleibt ganz. Vorher stand bei 130 % „18 Std. 49“
     * (ohne „Min.“) und „Stand 21:40 · ohne“ da. Jetzt fällt erst „· ohne Netz“
     * weg, dann „Stand“, „Update: antippen“ wird „Update“, und die Gesamtzeit
     * wird kleiner statt abgeschnitten. Tobis Größen bleiben, wie sie waren.
     */
    @Test
    fun kopfBeiGroesserSchriftGanz() {
        val groessen = listOf(170f to 170f, 170f to 290f, 170f to 100f, 200f to 201f, 264f to 176f, 300f to 176f, 352f to 176f, 352f to 150f, 400f to 201f, 400f to 100f, 110f to 40f)
        for (schrift in listOf(1f, 1.1f, 1.3f)) {
            RuntimeEnvironment.setFontScale(schrift)
            for ((breite, hoehe) in groessen) {
                for (e in listOf(beispiel(), langerTag(), extremerTag())) {
                    for (update in listOf(false, true)) {
                        val ergebnis = e.copy(updateHinweis = update)
                        val wo = "$breite × $hoehe bei ${(schrift * 100).roundToInt()} % (${dauerText(ergebnis.gesamt)}${if (update) ", Update" else ""})"
                        val view = aufgebaut(ergebnis, breite, hoehe)
                        ganz(view, wo)
                        val raster = WidgetRaster.fuer(breite, hoehe, schrift)
                        assertEquals(wo, dauerText(ergebnis.gesamt), text(view, R.id.gesamt))
                        if (raster.stand) assertTrue(wo, text(view, R.id.stand).endsWith("21:40") || text(view, R.id.stand).endsWith("ohne Netz"))
                        if (raster.titel && update) assertTrue(wo, text(view, R.id.titel).startsWith("Update"))
                    }
                }
            }
        }
        // Wo es passt, steht alles da wie bisher – auch bei 130 % in Tobis 4×2.
        RuntimeEnvironment.setFontScale(1.3f)
        val tobi = aufgebaut(langerTag().copy(updateHinweis = true), 400f, 201f)
        assertEquals("Update: antippen", text(tobi, R.id.titel))
        assertEquals("Stand 21:40 · ohne Netz", text(tobi, R.id.stand))
        val gesamt = tobi.findViewById<TextView>(R.id.gesamt)
        assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 26f, kontext.resources.displayMetrics), gesamt.textSize, 0.5f)
        // Im 2×2 auf engem Raster wird gekürzt, nicht abgeschnitten.
        val eng = aufgebaut(extremerTag().copy(updateHinweis = true), 170f, 170f)
        assertEquals("Stand 21:40", text(eng, R.id.stand))
        assertEquals("Update: antippen", text(eng, R.id.titel))
        assertTrue(eng.findViewById<TextView>(R.id.gesamt).textSize < gesamt.textSize)
        // Im 2×2 mit 200 × 201 ist „Stand 21:40 · ohne Netz“ bei 130 % gut 161 dp breit, mit der Luft
        // 169 – Platz sind 168: „· ohne Netz“ fällt weg, statt bei etwas breiterer Schrift auf dem
        // Handy als „Stand 21:40 · ohne“ dazustehen (maxLines 1 bricht am Wort ab). Bei 100 und 110 % bleibt es.
        assertEquals("Stand 21:40", text(aufgebaut(langerTag(), 200f, 201f), R.id.stand))
        for (s in listOf(1f, 1.1f)) {
            RuntimeEnvironment.setFontScale(s)
            assertEquals("bei $s", "Stand 21:40 · ohne Netz", text(aufgebaut(langerTag(), 200f, 201f), R.id.stand))
        }
        // Bei 200 % (so weit lässt One UI die Schrift wachsen) wird auch „Update: antippen“ zu „Update“.
        RuntimeEnvironment.setFontScale(2f)
        val kopf = WidgetAnsicht.Kopf.fuer(kontext, extremerTag(), WidgetRaster.fuer(170f, 170f, 2f), 170f, hinweis = true)
        assertEquals("Update", kopf.titel)
        assertFalse(kopf.stand, kopf.stand.contains("ohne"))
        assertTrue(kopf.gesamtSp < WidgetAnsicht.Kopf.GESAMT_SP)
    }

    /** Lange Namen werden gekürzt, die Zeit nie; über zehn Stunden passt die Gesamtzeit auch ins 2×2. */
    @Test
    fun langeNamenGekuerztZeitNie() {
        for ((breite, hoehe) in listOf(170f to 170f, 264f to 176f, 300f to 201f, 400f to 201f)) {
            val view = aufgebaut(langerTag(), breite, hoehe)
            val wo = "$breite × $hoehe"
            assertEquals(wo, "10 Std. 28 Min.", text(view, R.id.gesamt))
            assertEquals(wo, "Stand 21:40 · ohne Netz", text(view, R.id.stand))
            val erste = zeilen(view, R.id.spalte1).first()
            assertEquals(wo, "4:12 Std.", text(erste, R.id.zeit))
            ganz(view, wo)
        }
        // Im 2×2 reicht der Platz nicht für „Navigation und Karten“: gekürzt.
        val klein = aufgebaut(langerTag(), 170f, 170f)
        assertTrue(ellipsen(zeilen(klein, R.id.spalte1)[1].findViewById(R.id.name)) > 0)
    }

    /** Weniger als acht Apps: nur so viele Zeilen; gar keine: „Heute noch nichts“. */
    @Test
    fun wenigerAppsOderKeine() {
        val drei = beispiel().let { it.copy(posten = it.posten.take(3)) }
        val view = aufgebaut(drei, 400f, 201f)
        assertEquals(listOf("Boxenstopp", "WhatsApp", "Workout"), zeilen(view, R.id.spalte1).map { text(it, R.id.name) })
        assertEquals(0, zeilen(view, R.id.spalte2).size)
        assertEquals("2 Std. 44 Min.", text(view, R.id.gesamt))
        assertEquals(View.GONE, view.findViewById<View>(R.id.leer).visibility)

        for ((breite, hoehe) in listOf(400f to 201f, 400f to 100f, 170f to 170f)) {
            val leer = aufgebaut(beispiel().copy(posten = emptyList()), breite, hoehe)
            assertEquals("0 Min.", text(leer, R.id.gesamt))
            assertEquals(View.VISIBLE, leer.findViewById<View>(R.id.leer).visibility)
            assertEquals("Heute noch nichts", text(leer, R.id.leer))
            assertEquals(View.GONE, leer.findViewById<View>(R.id.liste).visibility)
            assertEquals("Stand 21:40", text(leer, R.id.stand))
            ganz(leer, "leer $breite × $hoehe")
        }
    }

    /**
     * Wartet ein Update auf Tobi, steht statt „Heute“ „Update: antippen“ im
     * Kopf – ganz, in jeder Größe mit Kopf –, der Stand bleibt ganz.
     */
    @Test
    fun updateHinweisImKopf() {
        for ((breite, hoehe) in listOf(400f to 201f, 352f to 176f, 300f to 176f, 264f to 176f, 352f to 150f, 400f to 100f, 170f to 170f)) {
            for (ergebnis in listOf(beispiel(), langerTag())) {
                val wo = "$breite × $hoehe"
                val view = aufgebaut(ergebnis.copy(updateHinweis = true), breite, hoehe)
                val titel = view.findViewById<TextView>(R.id.titel)
                assertEquals(wo, View.VISIBLE, titel.visibility)
                assertEquals(wo, "Update: antippen", titel.text.toString())
                assertEquals(wo, 0, ellipsen(titel))
                assertEquals(wo, WidgetAnsicht.standText(ergebnis), text(view, R.id.stand))
                ganz(view, wo)
            }
        }
        assertEquals("Heute", text(aufgebaut(beispiel(), 352f, 176f), R.id.titel))
    }

    /**
     * widget_info.xml: Standard 4×2, verkleinerbar bis 2×1 – auch flach (4×1)
     * zeigt das Widget jetzt Apps. Vorher ging es nur bis 2×2 (110 dp hoch).
     */
    @Test
    fun widgetLaesstSichBisZweiMalEinsVerkleinern() {
        val p = kontext.resources.getXml(R.xml.widget_info)
        while (p.next() != XmlPullParser.START_TAG) Unit
        val attribute = intArrayOf(android.R.attr.minResizeWidth, android.R.attr.minResizeHeight, android.R.attr.targetCellWidth, android.R.attr.targetCellHeight)
        val a = kontext.resources.obtainAttributes(Xml.asAttributeSet(p), attribute)
        val dichte = kontext.resources.displayMetrics.density
        // 70 × Zellen − 30 dp: zwei Zellen breit, eine hoch.
        assertEquals(110f, a.getDimension(0, 0f) / dichte, 0.5f)
        assertEquals(40f, a.getDimension(1, 0f) / dichte, 0.5f)
        assertEquals(4, a.getInt(2, 0))
        assertEquals(2, a.getInt(3, 0))
        a.recycle()
    }

    @Test
    fun auswahlBilder() {
        // Für res/drawable-nodpi/widget_vorschau.png (und -night-): ohne Hintergrund.
        val ordner = File(System.getProperty("vorschau.bau") ?: return)
        ordner.mkdirs()
        val bild = zeichnen(WidgetAnsicht.ansicht(kontext, beispiel(), 400f, 201f), 400f, 201f, rahmen = 0, hintergrund = 0)
        File(ordner, "widget_vorschau_hell.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "+night")
    fun auswahlBilderDunkel() {
        val ordner = File(System.getProperty("vorschau.bau") ?: return)
        ordner.mkdirs()
        val bild = zeichnen(WidgetAnsicht.ansicht(kontext, beispiel(), 400f, 201f), 400f, 201f, rahmen = 0, hintergrund = 0)
        File(ordner, "widget_vorschau_dunkel.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun speichern(name: String, ergebnis: Ergebnis, breite: Float, hoehe: Float, nacht: Boolean) {
        val ansicht = WidgetAnsicht.ansicht(kontext, ergebnis, breite, hoehe)
        // Ein ruhiger „Hintergrundbild“-Ton, damit man die Ecken der Karte sieht.
        val bild = zeichnen(ansicht, breite, hoehe, rahmen = 20, hintergrund = if (nacht) 0xFF0D1015.toInt() else 0xFFD9DFE7.toInt())
        val ordner = File(System.getProperty("vorschau.ordner") ?: "build/vorschau")
        ordner.mkdirs()
        val datei = File(ordner, "vorschau-$name.png")
        datei.outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(datei.length() > 1000)
        if (ergebnis.erlaubt) ganz(aufgebaut(ergebnis, breite, hoehe), name)
    }

    /**
     * Alles Sichtbare liegt ganz im Innern des Widgets (innerhalb des Rands),
     * gemessen an der Lage der Views; Gesamtzeit, Stand, „Heute“ und die Zeiten
     * sind nicht gekürzt, die Namen höchstens mit „…“.
     */
    private fun ganz(wurzel: View, wo: String) {
        val innen = Rect(wurzel.paddingLeft, wurzel.paddingTop, wurzel.width - wurzel.paddingRight, wurzel.height - wurzel.paddingBottom)
        for (v in alleViews(wurzel)) {
            if (!sichtbar(v, wurzel) || v === wurzel || v.width == 0 || v.height == 0) continue
            if (v is ViewGroup && v.childCount == 0) continue
            val l = lage(v, wurzel)
            val name = if (v.id != View.NO_ID) kontext.resources.getResourceEntryName(v.id) else v.javaClass.simpleName
            assertTrue("$wo: $name ragt heraus ($l, innen $innen)", innen.contains(l))
            if (v is TextView && v.text.isNotEmpty()) {
                val platz = v.width - v.totalPaddingLeft - v.totalPaddingRight
                if (v.id == R.id.name) continue
                assertEquals("$wo: $name gekürzt: ${v.text}", 0, ellipsen(v))
                assertTrue("$wo: $name breiter als Platz (${v.layout.getLineWidth(0)} > $platz): ${v.text}", v.layout.getLineWidth(0) <= platz + 0.5f)
                assertEquals("$wo: $name umgebrochen: ${v.text}", 1, v.lineCount)
                assertEquals("$wo: $name nicht ganz in der Zeile: ${v.text}", v.text.length, v.layout.getLineEnd(0))
            }
        }
    }

    private fun sichtbar(v: View, wurzel: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p.visibility != View.VISIBLE) return false
            if (p === wurzel) return true
            p = p.parent as? View
        }
        return true
    }

    private fun lage(v: View, wurzel: View): Rect {
        var x = 0
        var y = 0
        var p: View = v
        while (p !== wurzel) {
            x += p.left
            y += p.top
            p = p.parent as View
        }
        return Rect(x, y, x + v.width, y + v.height)
    }

    private fun alleViews(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { alleViews(v.getChildAt(it)) } else listOf(v)

    private fun alleTexte(v: View): List<String> = alleViews(v).filterIsInstance<TextView>().filter { sichtbar(it, v) }.map { it.text.toString() }

    private fun zeilen(view: View, spalte: Int): List<View> {
        val s = view.findViewById<LinearLayout>(spalte)
        if (s.visibility != View.VISIBLE) return emptyList()
        return (0 until s.childCount).map { s.getChildAt(it) }
    }

    private fun text(v: View, id: Int): String = v.findViewById<TextView>(id).text.toString()

    private fun ellipsen(t: TextView): Int = t.layout.getEllipsisCount(0)

    /** Die RemoteViews als echte Views, gemessen und gelegt in breite × hoehe dp. */
    private fun aufgebaut(ergebnis: Ergebnis, breite: Float, hoehe: Float): View =
        aufgebaut(WidgetAnsicht.ansicht(kontext, ergebnis, breite, hoehe), breite, hoehe)

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
