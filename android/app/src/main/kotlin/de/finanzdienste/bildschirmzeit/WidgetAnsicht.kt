package de.finanzdienste.bildschirmzeit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.core.os.BundleCompat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Tobi, 15.11.: „"Andere Apps" ist ja scheiße wir brauchen diesen Kreis links
 * nicht. Was ich will ist die gesamtzzeit alles Apps zu sehen und Vllt so die
 * top 8 oder so mit der größten nutzungszeit. Je nachdem wieviel hin passt.“
 *
 * Wie viele Apps in welche Größe passen (rein, in dp, getestet in
 * WidgetRasterTest): oben klein „Heute“ und „Stand 21:40“, groß die
 * Gesamtzeit, darunter die Top-Apps – so viele, wie ganz hineinpassen,
 * höchstens 8, in breiten Widgets in zwei Spalten (links 1–4, rechts 5–8). Nie
 * eine halbe Zeile, der Stand nie abgeschnitten. Ist das Widget flach (4×1),
 * steht der Kopf links und die Liste rechts daneben.
 *
 * Die Höhen sind die gemessenen Zeilenhöhen von Roboto (13 sp ohne
 * Schriftrand 15,2 dp, 26 sp 30,5 dp, 12 sp 14,1 dp, mit Schriftrand 13 sp
 * 17,3 dp) aufgerundet, dazu `RESERVE` für eine etwas höhere Schrift auf dem
 * Handy. `schrift` ist Androids Schriftgröße (fontScale): Mit größerer Schrift
 * passen weniger Zeilen, abgeschnitten wird trotzdem nichts.
 */
object WidgetRaster {
    const val MAX = 8

    /** Rand links und rechts, oben und unten. */
    const val RAND_SEITE = 16f
    const val RAND_OBEN = 14f

    /** Bleibt unten frei – falls die Schrift auf dem Handy etwas höher ist als Roboto. */
    const val RESERVE = 4f

    const val TITEL = 16f
    const val GESAMT = 31f
    const val STAND = 15f

    /** Zwischen Kopf und Liste. */
    const val KOPF_ABSTAND = 10f

    /** Ab dieser Innenbreite steht der Stand rechts neben „Heute“ („Update: antippen“ und „Stand 21:40 · ohne Netz“ ganz). */
    const val KOPF_BREIT = 262f

    /** Flach: der Kopf links, so breit („10 Std. 48 Min.“ ganz), dann der Abstand zur Liste. */
    const val KOPF_SEITE = 140f
    const val KOPF_SEITE_ABSTAND = 14f

    /** Zeile: Symbol 20 dp, Text 13 sp (mit Schriftrand 18 dp); kompakt 18 dp und 12 sp (16 dp). */
    const val ZEILE = 20f
    const val ZEILE_TEXT = 18f
    const val ZEILE_KOMPAKT = 18f
    const val ZEILE_TEXT_KOMPAKT = 16f
    const val ABSTAND_MIN = 6f
    const val ABSTAND_MAX = 12f

    /** Zwischen den beiden Spalten. */
    const val LUECKE = 16f

    /**
     * Mindestbreite einer Spalte für zwei Spalten: Symbol, „Boxenstopp“ und
     * „1:09 Std.“ ganz (13 sp: 20 + 8 + 69 + 6 + 51 dp, kompakt 12 sp: 18 + 6
     * + 64 + 6 + 47 dp), mit etwa einem Zehntel Luft für eine breitere Schrift.
     */
    const val SPALTE = 168f
    const val SPALTE_KOMPAKT = 140f

    enum class Form {
        /** Kopf oben; „Stand“ rechts neben „Heute“. */
        BREIT,

        /** Kopf oben, zu schmal für beides in einer Zeile: „Stand“ unter der Gesamtzeit. */
        SCHMAL,

        /** Kopf links (Heute, Gesamtzeit, Stand), Liste rechts – für flache Widgets. */
        FLACH,
    }

    data class Raster(
        val form: Form,
        /** 1 oder 2. */
        val spalten: Int,
        /** Zeilen je Spalte; 0 = nur der Kopf. */
        val zeilen: Int,
        /** 12 sp und 18-dp-Symbole statt 13 sp und 20 dp. */
        val kompakt: Boolean,
        /** „Heute“ (bzw. „Update: antippen“) zu sehen. */
        val titel: Boolean = true,
        /** „Stand 21:40“ zu sehen. */
        val stand: Boolean = true,
        /** Zwischen zwei Zeilen (dp). */
        val abstand: Float = ABSTAND_MIN,
    ) {
        val plaetze: Int get() = minOf(MAX, spalten * zeilen)

        /** Höhe der Liste (dp) bei `schrift`. */
        fun listenHoehe(schrift: Float = 1f): Float =
            if (zeilen == 0) 0f else zeilen * zeilenHoehe(kompakt, schrift) + (zeilen - 1) * abstand
    }

    fun zeilenHoehe(kompakt: Boolean, schrift: Float = 1f): Float =
        if (kompakt) maxOf(ZEILE_KOMPAKT, ZEILE_TEXT_KOMPAKT * schrift) else maxOf(ZEILE, ZEILE_TEXT * schrift)

    /** Höhe des Kopfs (dp) – bei FLACH die der Spalte links. */
    fun kopfHoehe(form: Form, schrift: Float = 1f): Float =
        if (form == Form.BREIT) (TITEL + GESAMT) * schrift else (TITEL + GESAMT + STAND) * schrift

    /**
     * Das Raster für ein Widget von `breite` × `hoehe` dp: das mit den meisten
     * Apps. Bei Gleichstand lieber die größere Schrift, dann lieber den Kopf
     * oben, dann lieber eine Spalte (ein hohes 4×3 zeigt die acht
     * untereinander, statt unten leer zu bleiben).
     */
    fun fuer(breite: Float, hoehe: Float, schrift: Float = 1f): Raster {
        val innenB = breite - 2 * RAND_SEITE
        val innenH = hoehe - 2 * RAND_OBEN - RESERVE
        val kandidaten = ArrayList<Raster>()
        val oben = if (innenB >= KOPF_BREIT * schrift) Form.BREIT else Form.SCHMAL
        val kopf = kopfHoehe(oben, schrift)
        val seitlichB = innenB - KOPF_SEITE * schrift - KOPF_SEITE_ABSTAND
        val seitlich = innenH >= kopfHoehe(Form.FLACH, schrift) && seitlichB >= SPALTE_KOMPAKT * schrift
        for (kompakt in listOf(false, true)) {
            if (innenH >= kopf) {
                for (spalten in 1..2) liste(oben, innenB, innenH - kopf - KOPF_ABSTAND, kompakt, spalten, schrift)?.let { kandidaten += it }
            }
            if (seitlich) {
                for (spalten in 1..2) liste(Form.FLACH, seitlichB, innenH, kompakt, spalten, schrift)?.let { kandidaten += it }
            }
        }
        // maxByOrNull nimmt bei Gleichstand den ersten – die Reihenfolge oben ist die Vorliebe.
        kandidaten.maxByOrNull { it.plaetze }?.let { return it }
        // Flacher als jedes Raster des Startbildschirms: erst der Stand weg, dann „Heute“ – die Gesamtzeit bleibt.
        if (innenH >= (TITEL + GESAMT) * schrift) return Raster(Form.SCHMAL, spalten = 1, zeilen = 0, kompakt = false, stand = false)
        return Raster(Form.BREIT, spalten = 1, zeilen = 0, kompakt = false, titel = false, stand = false)
    }

    /** Die Liste in `b` × `h` dp; null, wenn die Spalten dort zu schmal sind. */
    private fun liste(form: Form, b: Float, h: Float, kompakt: Boolean, spalten: Int, schrift: Float): Raster? {
        val mindestens = (if (kompakt) SPALTE_KOMPAKT else SPALTE) * schrift
        if (spalten == 2 && (b - LUECKE) / 2 < mindestens) return null
        // Eine Spalte nimmt die große Schrift nur, wenn „Boxenstopp“ ganz bleibt; kompakt geht immer (Namen gekürzt).
        if (spalten == 1 && !kompakt && b < mindestens) return null
        val zh = zeilenHoehe(kompakt, schrift)
        val moeglich = if (h < zh) 0 else floor((h + ABSTAND_MIN) / (zh + ABSTAND_MIN)).toInt()
        val zeilen = minOf(moeglich, ceil(MAX.toFloat() / spalten).toInt())
        val abstand = if (zeilen > 1) ((h - zeilen * zh) / (zeilen - 1)).coerceIn(ABSTAND_MIN, ABSTAND_MAX) else ABSTAND_MIN
        return Raster(form, spalten, zeilen, kompakt, abstand = abstand)
    }
}

/**
 * Zeichnet das Widget nach dem WidgetRaster: Kopf mit „Heute“, Gesamtzeit und
 * Stand, darunter die Top-Apps mit Symbol, Name und Zeit. Antippen öffnet die
 * App mit der Statistik (Ziel.STATISTIK); wartet ein Update auf Tobi, steht im
 * Kopf „Update: antippen“ statt „Heute“ (hat Samsungs Automatische Sperre es
 * verhindert: „Update blockiert“), und Antippen öffnet die Update-Karte
 * (Ziel.UPDATE). Die App rechnet beim Öffnen sofort mit Netz und zeichnet
 * dabei auch das Widget neu.
 *
 * Ab Android 12 bekommt jedes Widget genau die Größen, die der Startbildschirm
 * meldet (Hoch- und Querformat). Hell und dunkel kommen aus den Ressourcen
 * (values-night), so stimmt es auch nach dem Umschalten, ohne neu zu rechnen.
 */
object WidgetAnsicht {
    /** Symbole in dieser Größe; die kompakte Zeile verkleinert sie. */
    private const val SYMBOL_DP = 20f

    /** Ohne Größe vom Startbildschirm: das, was widget_info.xml mindestens zusagt (4×2 vor Android 12). */
    private val OHNE_GROESSE = SizeF(250f, 110f)

    fun zeigen(context: Context, ergebnis: Ergebnis, ids: IntArray) {
        val awm = AppWidgetManager.getInstance(context)
        val lage = updateLage(context)
        val mitHinweis = ergebnis.copy(
            updateHinweis = lage != null && UpdateRegeln.widgetHinweis(lage),
            updateGesperrt = lage != null && UpdateRegeln.widgetGesperrt(lage),
        )
        val symbole = HashMap<String, Bitmap>()
        for (id in ids) {
            try {
                awm.updateAppWidget(id, fuerWidget(context, mitHinweis, awm.getAppWidgetOptions(id), symbole))
            } catch (e: RuntimeException) {
                Log.w(LOG, "Widget nicht gezeichnet", e)
            }
        }
    }

    fun fuerWidget(context: Context, ergebnis: Ergebnis, optionen: Bundle?, symbole: MutableMap<String, Bitmap> = HashMap()): RemoteViews {
        if (!ergebnis.erlaubt) return einrichten(context)
        val groessen = groessen(optionen)
        if (Build.VERSION.SDK_INT >= 31 && groessen.size > 1) {
            return RemoteViews(groessen.distinct().take(8).associateWith { ansicht(context, ergebnis, it.width, it.height, symbole) })
        }
        val g = groessen.firstOrNull() ?: OHNE_GROESSE
        return ansicht(context, ergebnis, g.width, g.height, symbole)
    }

    /** Die Größen in dp, die der Startbildschirm meldet. */
    private fun groessen(o: Bundle?): List<SizeF> {
        if (o == null) return emptyList()
        if (Build.VERSION.SDK_INT >= 31) {
            val liste = BundleCompat.getParcelableArrayList(o, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            if (!liste.isNullOrEmpty()) return liste.filter { it.width > 0 && it.height > 0 }
        }
        // Hochformat: kleinste Breite, größte Höhe.
        val b = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return if (b > 0 && h > 0) listOf(SizeF(b.toFloat(), h.toFloat())) else emptyList()
    }

    /** Das Widget für eine Größe (dp). */
    fun ansicht(
        context: Context,
        ergebnis: Ergebnis,
        breite: Float,
        hoehe: Float,
        symbole: MutableMap<String, Bitmap> = HashMap(),
    ): RemoteViews {
        if (!ergebnis.erlaubt) return einrichten(context)
        val raster = WidgetRaster.fuer(breite, hoehe, schrift(context))
        val layout = when (raster.form) {
            WidgetRaster.Form.BREIT -> R.layout.widget_gross
            WidgetRaster.Form.SCHMAL -> R.layout.widget_schmal
            WidgetRaster.Form.FLACH -> R.layout.widget_flach
        }
        val v = RemoteViews(context.packageName, layout)
        // Alles immer setzen: Der Startbildschirm legt neue RemoteViews mit demselben Layout über die alten.
        val hinweis = ergebnis.updateHinweis && raster.titel
        val kopf = Kopf.fuer(context, ergebnis, raster, breite, hinweis)
        v.setViewVisibility(R.id.titel, if (raster.titel) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.titel, kopf.titel)
        textFarbe(context, v, R.id.titel, if (hinweis) R.color.akzent else R.color.widget_text_leise)
        v.setViewVisibility(R.id.stand, if (raster.stand) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.stand, kopf.stand)
        v.setTextViewText(R.id.gesamt, gesamtZeichen(ergebnis.gesamt))
        v.setTextViewTextSize(R.id.gesamt, TypedValue.COMPLEX_UNIT_SP, kopf.gesamtSp)
        if (raster.form == WidgetRaster.Form.FLACH && Build.VERSION.SDK_INT >= 31) {
            v.setViewLayoutWidth(R.id.kopf, WidgetRaster.KOPF_SEITE * schrift(context), TypedValue.COMPLEX_UNIT_DIP)
        }

        val top = ergebnis.posten.take(raster.plaetze)
        listeFuellen(context, v, raster, top, symbole)
        v.setViewVisibility(R.id.leer, if (top.isEmpty() && raster.zeilen > 0) View.VISIBLE else View.GONE)

        val ziel = if (ergebnis.updateHinweis) Ziel.UPDATE else Ziel.STATISTIK
        v.setOnClickPendingIntent(android.R.id.background, oeffnen(context, ziel))
        v.setContentDescription(
            android.R.id.background,
            "Bildschirmzeit heute: ${dauerText(ergebnis.gesamt)}. " + when {
                ergebnis.updateHinweis && ergebnis.updateGesperrt -> "Update blockiert – antippen öffnet das Update."
                ergebnis.updateHinweis -> "Antippen öffnet das Update."
                else -> "Antippen öffnet die Statistik."
            },
        )
        return v
    }

    /** Spaltenweise: links Platz 1 bis `zeilen`, rechts die nächsten. */
    private fun listeFuellen(context: Context, v: RemoteViews, raster: WidgetRaster.Raster, top: List<Posten>, symbole: MutableMap<String, Bitmap>) {
        val spalten = intArrayOf(R.id.spalte1, R.id.spalte2)
        val zeileLayout = if (raster.kompakt) R.layout.widget_zeile_kompakt else R.layout.widget_zeile
        val abstand = px(context, raster.abstand)
        val symbolPx = px(context, SYMBOL_DP)
        for ((s, spalte) in spalten.withIndex()) {
            v.removeAllViews(spalte)
            val teil = top.drop(s * raster.zeilen).take(raster.zeilen)
            v.setViewVisibility(spalte, if (s < raster.spalten) View.VISIBLE else View.GONE)
            if (s >= raster.spalten) continue
            for ((i, p) in teil.withIndex()) {
                val z = RemoteViews(context.packageName, zeileLayout)
                z.setViewPadding(R.id.zeile, 0, if (i == 0) 0 else abstand, 0, 0)
                z.setImageViewBitmap(R.id.symbol, symbole.getOrPut(p.schluessel) { Symbole.bild(context, p, symbolPx) })
                z.setTextViewText(R.id.name, p.name)
                z.setTextViewText(R.id.zeit, kurzText(p.sekunden))
                v.addView(spalte, z)
            }
        }
        v.setViewVisibility(R.id.liste, if (top.isEmpty()) View.GONE else View.VISIBLE)
    }

    fun einrichten(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_einrichten)
        v.setOnClickPendingIntent(android.R.id.background, oeffnen(context, Ziel.STATISTIK))
        return v
    }

    /**
     * Öffnet die App mit `ziel` (Ziel.STATISTIK oder Ziel.UPDATE). Je Ziel ein
     * eigener requestCode – sonst wäre es derselbe PendingIntent, und das
     * zuletzt gesetzte Extra gälte für beide. CLEAR_TOP und SINGLE_TOP: Läuft
     * die App schon (auch mit Androids Einstellungen darüber), bekommt sie das
     * neue Ziel über onNewIntent, statt nur nach vorn zu kommen.
     *
     * Läuft gerade eine Installation (UpdateSpeicher.sitzung), kein CLEAR_TOP:
     * Androids Rückfrage „Möchtest du diese App aktualisieren?“ liegt dann im
     * Task der App über ihr, und CLEAR_TOP räumte sie ohne Antwort weg – die
     * Sitzung hinge bis zu 10 Minuten. Dann nur NEW_TASK: Android holt den Task
     * so nach vorn, wie er ist, samt Rückfrage. Die Flags stehen fest, wenn
     * gezeichnet wird; deshalb zeichnet Selbstupdate die Widgets gleich nach dem
     * Merken der Sitzung (vor commit) und nach jedem Status neu. Dafür ist die Absicht dieselbe
     * wie die des App-Symbols (MAIN/LAUNCHER) – sonst legte Android über eine
     * vom Symbol gestartete App eine zweite Activity. Eigene requestCodes, denn
     * FLAG_UPDATE_CURRENT ersetzt nur die Extras, nicht die Flags.
     */
    internal fun oeffnen(context: Context, ziel: String): PendingIntent {
        val installiert = installiertGerade(context)
        val app = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity::class.java)
            .putExtra(Ziel.EXTRA, ziel)
            .addFlags(
                if (installiert) {
                    Intent.FLAG_ACTIVITY_NEW_TASK
                } else {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
            )
        val code = when {
            ziel == Ziel.UPDATE && installiert -> CODE_UPDATE_SITZUNG
            ziel == Ziel.UPDATE -> CODE_UPDATE
            installiert -> CODE_STATISTIK_SITZUNG
            else -> CODE_STATISTIK
        }
        return PendingIntent.getActivity(context, code, app, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal const val CODE_STATISTIK = 10
    internal const val CODE_UPDATE = 11
    internal const val CODE_STATISTIK_SITZUNG = 12
    internal const val CODE_UPDATE_SITZUNG = 13

    /** Eine Installer-Sitzung ist gemerkt (Selbstupdate) – nur gelesen; fehlt etwas, dann eben keine. */
    private fun installiertGerade(context: Context): Boolean = try {
        UpdateSpeicher(context).sitzung >= 0
    } catch (e: RuntimeException) {
        false
    }

    /** Fehlt etwas (kein Speicher, kein PackageManager), dann eben kein Hinweis – das Widget zeichnet trotzdem. */
    private fun updateLage(context: Context): UpdateLage? = try {
        Selbstupdate.widgetLage(context)
    } catch (e: RuntimeException) {
        null
    }

    /** „Stand 21:40“, leise „· ohne Netz“, wenn die Web-Apps aus dem Zwischenspeicher kommen. */
    fun standText(ergebnis: Ergebnis): String =
        "Stand ${uhrzeit(ergebnis.stand)}" + if (ergebnis.ohneNetz) " · ohne Netz" else ""

    /**
     * Was im Kopf steht, so dass es ganz in die Breite passt – gemessen in der
     * Schrift des Widgets, mit Androids Schriftgröße und einem Zwanzigstel
     * Luft (falls die Schrift auf dem Handy breiter läuft). Passt
     * „Stand 21:40 · ohne Netz“ nicht, fällt erst „· ohne Netz“ weg, dann
     * „Stand“; „Update: antippen“ und „Update blockiert“ werden „Update“; die Gesamtzeit wird kleiner
     * statt abgeschnitten („18 Std. 49“ ohne „Min.“ läse man falsch).
     */
    internal class Kopf(val titel: String, val stand: String, val gesamtSp: Float) {
        companion object {
            const val GESAMT_SP = 26f
            const val GESAMT_MIN_SP = 12f
            private const val TITEL_SP = 13f
            private const val STAND_SP = 12f

            /** Abstand zwischen „Heute“ und dem Stand in der breiten Form (widget_gross.xml). */
            private const val STAND_ABSTAND = 12f
            private const val LUFT = 1.05f

            fun fuer(context: Context, ergebnis: Ergebnis, raster: WidgetRaster.Raster, breite: Float, hinweis: Boolean): Kopf {
                val schrift = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
                val innen = breite - 2 * WidgetRaster.RAND_SEITE
                val platz = if (raster.form == WidgetRaster.Form.FLACH) minOf(innen, WidgetRaster.KOPF_SEITE * schrift) else innen
                val titelLang = context.getString(
                    when {
                        hinweis && ergebnis.updateGesperrt -> R.string.widget_update_gesperrt
                        hinweis -> R.string.widget_update
                        else -> R.string.widget_heute
                    },
                )
                val titel = if (!hinweis || passt(context, titelLang, TITEL_SP, mittel = true, platz)) titelLang else context.getString(R.string.widget_update_kurz)
                // Breit: Der Stand steht rechts neben „Heute“ (bzw. „Update: antippen“).
                val standPlatz = if (raster.form == WidgetRaster.Form.BREIT && raster.titel) {
                    platz - STAND_ABSTAND - breiteDp(context, titel, TITEL_SP, mittel = true) * LUFT
                } else {
                    platz
                }
                val uhr = uhrzeit(ergebnis.stand)
                val stand = listOf(standText(ergebnis), "Stand $uhr", uhr).firstOrNull { passt(context, it, STAND_SP, mittel = false, standPlatz, tnum = true) } ?: uhr
                val zeichen = gesamtZeichen(ergebnis.gesamt)
                var sp = GESAMT_SP
                while (sp > GESAMT_MIN_SP && !passt(context, zeichen, sp, mittel = true, platz)) sp -= 1f
                return Kopf(titel, stand, sp)
            }

            private fun passt(context: Context, text: CharSequence, sp: Float, mittel: Boolean, platz: Float, tnum: Boolean = false): Boolean =
                breiteDp(context, text, sp, mittel, tnum) * LUFT <= platz

            /** Breite in dp, wie der Startbildschirm den Text setzt (sp mit Androids Schriftgröße, auch der nichtlinearen ab Android 14). */
            fun breiteDp(context: Context, text: CharSequence, sp: Float, mittel: Boolean, tnum: Boolean = false): Float {
                val dm = context.resources.displayMetrics
                val farbe = TextPaint(Paint.ANTI_ALIAS_FLAG)
                farbe.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
                farbe.typeface = if (mittel) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                if (tnum) farbe.fontFeatureSettings = "tnum"
                return Layout.getDesiredWidth(text, farbe) / dm.density
            }
        }
    }

    /** „4 Std. 7 Min.“ in einer Zeile, große Zahlen, kleinere Einheiten – der Kopf des Widgets; ganz ohne Nutzung „0 Min.“. */
    fun gesamtZeichen(sekunden: Long): CharSequence = zahlenGross(summeText(sekunden), 0.62f)

    private fun zahlenGross(text: String, einheit: Float): CharSequence {
        val s = SpannableString(text)
        for (m in Regex("[^0-9]+").findAll(text)) {
            s.setSpan(RelativeSizeSpan(einheit), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /** Textfarbe aus den Ressourcen, ab Android 12 hell und dunkel zugleich (der Startbildschirm wählt). */
    private fun textFarbe(context: Context, v: RemoteViews, id: Int, @ColorRes farbe: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            v.setColorInt(id, "setTextColor", farbeIn(context, farbe, nacht = false), farbeIn(context, farbe, nacht = true))
        } else {
            v.setTextColor(id, context.getColor(farbe))
        }
    }

    private fun farbeIn(context: Context, @ColorRes farbe: Int, nacht: Boolean): Int {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (nacht) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(config).getColor(farbe)
    }

    private fun schrift(context: Context): Float = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f

    private fun px(context: Context, dp: Float): Int = (dp * context.resources.displayMetrics.density).roundToInt()
}
