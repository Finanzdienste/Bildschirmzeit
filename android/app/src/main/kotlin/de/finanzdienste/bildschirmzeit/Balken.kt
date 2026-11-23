package de.finanzdienste.bildschirmzeit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * Das Balkendiagramm der Tagessummen in Woche und Monat – eigene View mit
 * Canvas, keine Bibliothek. Farben aus dem Thema (values und values-night):
 * Balken im Akzent, leiser, heute voll; Linien und Beschriftung leise.
 * Ein Tag ohne Daten bekommt einen kleinen leeren Kreis statt eines Balkens –
 * nie eine 0; ein Tag mit Daten, aber ganz ohne Nutzung, einen flachen Strich
 * auf der Grundlinie (sonst sähe er aus wie ein Tag, der erst kommt). Ein Tipp
 * auf einen Tag ruft `beiTipp` (die App zeigt ihn im Reiter Tag).
 */
class Balken @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var werte: List<Tageswert> = emptyList()
    private var heute: LocalDate = LocalDate.MIN

    /** Wird mit dem angetippten Tag gerufen (nur Tage bis heute). */
    var beiTipp: ((LocalDate) -> Unit)? = null

    private val dichte = resources.displayMetrics.density
    private val akzent = context.getColor(R.color.akzent)
    private val leise = context.getColor(R.color.text_leise)
    private val karte = context.getColor(R.color.karte)

    /** Die übrigen Tage: der Akzent zur Karte hin aufgehellt (dunkel etwas kräftiger, sonst versinkt er). */
    private val leiser = ColorUtils.blendARGB(karte, akzent, if (ColorUtils.calculateLuminance(karte) < 0.5) 0.58f else 0.42f)
    private val balken = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linie = Paint().apply { strokeWidth = max(1f, dichte * 0.75f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dichte
    }
    private val schrift = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val pfad = Path()
    private val rechteck = RectF()
    private var tippX = -1f

    fun setzen(werte: List<Tageswert>, heute: LocalDate) {
        this.werte = werte
        this.heute = heute
        contentDescription = beschreibung(werte, heute)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hoehe = resolveSize((156 * dichte).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), hoehe)
    }

    /**
     * Die Achse: oben die nächste runde Stufe über dem größten Tag, dazwischen höchstens drei Linien.
     * 5 und 10 Minuten für eine App, die nur wenig läuft (sonst stünde keine Linie da).
     */
    private fun stufe(groesster: Long): Long {
        val stufen = longArrayOf(5, 10, 15, 30, 60, 120, 180, 240, 360, 480, 720).map { it * 60 }
        return stufen.firstOrNull { groesster / it <= 3 } ?: stufen.last()
    }

    override fun onDraw(canvas: Canvas) {
        if (werte.isEmpty()) return
        val unten = height - schrift.textSize - 8 * dichte
        val oben = schrift.textSize + 6 * dichte
        val groesster = groesster()
        val decke = max(groesster, stufe(groesster)).toFloat()

        // Linien mit Beschriftung links, die Balken rechts davon.
        schrift.color = leise
        schrift.typeface = Typeface.DEFAULT
        schrift.textAlign = Paint.Align.LEFT
        val links = linkerRand()
        val breite = width - links
        linie.color = ColorUtils.setAlphaComponent(leise, 0x40)
        canvas.drawLine(links, unten, width.toFloat(), unten, linie)
        linie.color = ColorUtils.setAlphaComponent(leise, 0x26)
        for (wert in linien()) {
            val y = unten - (unten - oben) * wert / decke
            canvas.drawLine(links, y, width.toFloat(), y, linie)
            canvas.drawText(achsenText(wert), 0f, y + schrift.textSize * 0.35f, schrift)
        }

        val platz = breite / werte.size
        val dicke = min(24 * dichte, platz * if (werte.size <= 7) 0.5f else 0.66f)
        val radius = min(4 * dichte, dicke / 2)
        schrift.textAlign = Paint.Align.CENTER
        for ((i, w) in werte.withIndex()) {
            val mitte = links + platz * (i + 0.5f)
            val istHeute = w.datum == heute
            val s = w.sekunden
            if (s == null && !w.zukunft) {
                ring.color = ColorUtils.setAlphaComponent(leise, 0x99)
                val r = min(3 * dichte, dicke / 2)
                canvas.drawCircle(mitte, unten - r - 2 * dichte, r, ring)
            } else if (s != null) {
                // Ganz ohne Nutzung: ein flacher Strich (1,5 dp), sonst mindestens 2 dp hoch.
                val h = if (s <= 0L) 1.5f * dichte else max(2 * dichte, (unten - oben) * s / decke)
                balken.color = if (istHeute) akzent else leiser
                rechteck.set(mitte - dicke / 2, unten - h, mitte + dicke / 2, unten)
                pfad.reset()
                // Oben rund, unten gerade auf der Grundlinie.
                pfad.addRoundRect(rechteck, floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(pfad, balken)
            }
            val beschriftung = beschriftung(i, w.datum)
            if (beschriftung != null) {
                schrift.color = if (istHeute) akzent else leise
                schrift.typeface = if (istHeute) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                canvas.drawText(beschriftung, mitte, height - 4 * dichte, schrift)
            }
        }
    }

    /** Woche: jeder Tag („Mo“); Monat: jeder siebte Tag ab dem ersten des Zeitraums („25.“, „1.“, …). */
    private fun beschriftung(i: Int, tag: LocalDate): String? = when {
        werte.size <= 7 -> wochentagKurz(tag)
        i % 7 == 0 -> tag.dayOfMonth.toString()
        else -> null
    }

    private fun groesster(): Long = max(1L, werte.maxOfOrNull { it.sekunden ?: 0L } ?: 0L)

    /** Die Werte der Linien: Vielfache der Stufe bis zum größten Tag. */
    private fun linien(): List<Long> {
        val groesster = groesster()
        val schritt = stufe(groesster)
        return (1..(groesster / schritt).toInt()).map { it * schritt }
    }

    /** Wo die Balken anfangen: rechts von der breitesten Beschriftung der Linien. */
    private fun linkerRand(): Float {
        schrift.textAlign = Paint.Align.LEFT
        schrift.typeface = Typeface.DEFAULT
        return (linien().maxOfOrNull { schrift.measureText(achsenText(it)) } ?: 0f) + 8 * dichte
    }

    /** Welcher Tag unter `x` liegt (null: daneben oder noch nicht da). */
    fun tagBei(x: Float): LocalDate? {
        if (werte.isEmpty() || width <= 0) return null
        val links = linkerRand()
        if (x < links) return null
        val i = ((x - links) / ((width - links) / werte.size)).toInt().coerceIn(0, werte.size - 1)
        return werte[i].datum.takeIf { !werte[i].zukunft }
    }

    /** Die Mitte des Balkens von `tag` in Pixeln – für Tests. */
    fun mitteVon(tag: LocalDate): Float? {
        val i = werte.indexOfFirst { it.datum == tag }.takeIf { it >= 0 } ?: return null
        val links = linkerRand()
        return links + (width - links) / werte.size * (i + 0.5f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tippX = event.x
                return tagBei(event.x) != null
            }
            MotionEvent.ACTION_UP -> {
                tippX = event.x
                return performClick()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        val tag = tagBei(tippX) ?: return false
        beiTipp?.invoke(tag)
        return true
    }

    companion object {
        /** „1 Std.“, „30 Min.“, „1:30 Std.“ an den Linien. */
        fun achsenText(sekunden: Long): String = when {
            sekunden < 3600 -> "${sekunden / 60} Min."
            sekunden % 3600 == 0L -> "${sekunden / 3600} Std."
            else -> kurzText(sekunden)
        }

        /** Für TalkBack: jeder Tag mit Zeit oder „keine Daten“. */
        fun beschreibung(werte: List<Tageswert>, heute: LocalDate): String = werte.filter { !it.zukunft }.joinToString(", ") {
            val tag = if (werte.size <= 7) wochentagKurz(it.datum) else "${it.datum.dayOfMonth}."
            "$tag ${it.sekunden?.let(::summeText) ?: "keine Daten"}" + if (it.datum == heute) " (heute)" else ""
        }
    }
}
