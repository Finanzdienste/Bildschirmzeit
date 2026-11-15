package de.finanzdienste.bildschirmzeit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import kotlin.math.roundToInt

/**
 * Zeichnet das Widget: Ring mit Gesamtzeit, daneben „Bildschirmzeit“, die
 * Top 3 und „Andere Apps“ (alle übrigen zusammen, der graue Teil des Rings),
 * unten der Stand. Ist das Widget zu flach für die vierte Zeile, steht der
 * Rest vorn im Stand („9 Min. andere · Stand 08:40“). Unter GROSS_AB_DP Breite (2×2) nur der Ring, unter
 * SCHMAL_DP (3×2) ein kleinerer Ring und Zeilen ohne App-Symbol – sonst
 * blieben bei langen Zeiten von den Namen nur „Worko…“.
 *
 * Ab Android 12 bekommt jedes Widget genau die Größen, die der Startbildschirm
 * meldet (Hoch- und Querformat), und den Ring einmal hell und einmal dunkel –
 * so stimmt er auch nach dem Umschalten des Systems, ohne neu zu rechnen.
 */
object WidgetAnsicht {
    const val AKTUALISIEREN = "de.finanzdienste.bildschirmzeit.AKTUALISIEREN"
    private const val GROSS_AB_DP = 200f
    private const val SCHMAL_DP = 320f
    private const val RAND_DP = 14f
    private const val ABSTAND_DP = 14f
    private const val SYMBOL_DP = 20f
    private const val FLACH_DP = 150f
    private const val SEHR_FLACH_DP = 125f
    // Titel, vier Zeilen und Stand brauchen gut 144 dp plus den Rand (VorschauTest prüft es).
    private const val VIER_ZEILEN_DP = 172f
    private const val SEHR_SCHMAL_DP = 290f

    private val ZEILEN = intArrayOf(R.id.zeile1, R.id.zeile2, R.id.zeile3)
    private val PUNKTE = intArrayOf(R.id.punkt1, R.id.punkt2, R.id.punkt3)
    private val SYMBOLE = intArrayOf(R.id.symbol1, R.id.symbol2, R.id.symbol3)
    private val NAMEN = intArrayOf(R.id.name1, R.id.name2, R.id.name3)
    private val ZEITEN = intArrayOf(R.id.zeit1, R.id.zeit2, R.id.zeit3)

    fun zeigen(context: Context, ergebnis: Ergebnis, ids: IntArray) {
        val awm = AppWidgetManager.getInstance(context)
        val mitHinweis = ergebnis.copy(updateHinweis = updateHinweis(context))
        for (id in ids) {
            try {
                awm.updateAppWidget(id, fuerWidget(context, mitHinweis, awm.getAppWidgetOptions(id)))
            } catch (e: RuntimeException) {
                Log.w(LOG, "Widget nicht gezeichnet", e)
            }
        }
    }

    fun fuerWidget(context: Context, ergebnis: Ergebnis, optionen: Bundle?): RemoteViews {
        if (!ergebnis.erlaubt) return einrichten(context)
        val groessen = groessen(optionen)
        if (Build.VERSION.SDK_INT >= 31 && groessen.size > 1) {
            return RemoteViews(groessen.distinct().take(8).associateWith { ansicht(context, ergebnis, it.width, it.height) })
        }
        val g = groessen.firstOrNull() ?: SizeF(250f, 110f)
        return ansicht(context, ergebnis, g.width, g.height)
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
    fun ansicht(context: Context, ergebnis: Ergebnis, breite: Float, hoehe: Float): RemoteViews =
        if (!ergebnis.erlaubt) einrichten(context) else if (breite < GROSS_AB_DP) klein(context, ergebnis, breite, hoehe) else gross(context, ergebnis, breite, hoehe)

    fun gross(context: Context, ergebnis: Ergebnis, breite: Float, hoehe: Float): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_gross)
        val schmal = breite < SCHMAL_DP
        val ring = minOf(hoehe - 2 * RAND_DP, (breite - 2 * RAND_DP) * if (schmal) 0.34f else 0.40f).coerceIn(72f, 200f)
        ringSetzen(context, v, ergebnis, ring)

        val top = ergebnis.posten.take(3)
        val symbolPx = px(context, SYMBOL_DP)
        for (i in 0 until 3) {
            if (i >= top.size) {
                v.setViewVisibility(ZEILEN[i], View.GONE)
                continue
            }
            val p = top[i]
            v.setViewVisibility(ZEILEN[i], View.VISIBLE)
            farbeSetzen(v, PUNKTE[i], RingFarben.HELL.top[i], RingFarben.DUNKEL.top[i], context)
            // Schmal: Der Farbpunkt bleibt (er verbindet Zeile und Ring), das Symbol fällt weg.
            v.setViewVisibility(SYMBOLE[i], if (schmal) View.GONE else View.VISIBLE)
            if (!schmal) v.setImageViewBitmap(SYMBOLE[i], Symbole.bild(context, p, symbolPx))
            v.setTextViewText(NAMEN[i], p.name)
            v.setTextViewText(ZEITEN[i], kurzText(p.sekunden))
        }
        v.setViewVisibility(R.id.leer, if (top.isEmpty()) View.VISIBLE else View.GONE)
        // Tobi, 15.11.: „Hab ich wirklich nur diese drei Apps heute verwendet?“ – der graue Rest bekommt eine Zeile.
        val rest = ergebnis.posten.drop(3).sumOf { it.sekunden }
        val restZeile = rest > 0 && hoehe >= VIER_ZEILEN_DP
        v.setViewVisibility(R.id.zeile4, if (restZeile) View.VISIBLE else View.GONE)
        if (restZeile) {
            farbeSetzen(v, R.id.punkt4, RingFarben.HELL.rest, RingFarben.DUNKEL.rest, context)
            v.setTextViewText(R.id.zeit4, kurzText(rest))
            // Sehr schmal (3×2 auf engen Rastern): „Andere“ statt „Andere A…“.
            v.setTextViewText(R.id.name4, context.getString(if (breite < SEHR_SCHMAL_DP) R.string.widget_andere_kurz else R.string.widget_andere))
        }
        v.setTextViewText(R.id.stand, if (rest > 0 && !restZeile) "${kurzText(rest)} andere · ${standText(ergebnis)}" else standText(ergebnis))
        // Flache Widgets (manche Startbildschirme, 4×1½): erst der Titel weg, dann der Stand.
        v.setViewVisibility(R.id.titel, if (hoehe < FLACH_DP) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.stand, if (hoehe < SEHR_FLACH_DP) View.GONE else View.VISIBLE)
        // Wartet ein Update auf Tobi (Benachrichtigungen hat die App nicht): Der Titel sagt es, Antippen öffnet
        // die App mit der Karte – die rechnet dabei auch neu. Immer setzen: Der Startbildschirm legt neue
        // RemoteViews über die alten, ein einmal gesetzter Titel bliebe sonst stehen.
        val hinweis = ergebnis.updateHinweis && hoehe >= FLACH_DP
        v.setTextViewText(R.id.titel, context.getString(if (hinweis) R.string.widget_update else R.string.app_name))
        v.setOnClickPendingIntent(android.R.id.background, if (hinweis) appOeffnen(context) else neuRechnen(context))
        return v
    }

    fun klein(context: Context, ergebnis: Ergebnis, breite: Float, hoehe: Float): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_klein)
        val ring = (minOf(breite, hoehe) - 2 * RAND_DP).coerceIn(56f, 200f)
        ringSetzen(context, v, ergebnis, ring)
        v.setOnClickPendingIntent(android.R.id.background, neuRechnen(context))
        return v
    }

    fun einrichten(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_einrichten)
        v.setOnClickPendingIntent(android.R.id.background, appOeffnen(context))
        return v
    }

    private fun appOeffnen(context: Context): PendingIntent {
        val app = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 1, app, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Fehlt etwas (kein Speicher, kein PackageManager), dann eben kein Hinweis – das Widget zeichnet trotzdem. */
    private fun updateHinweis(context: Context): Boolean = try {
        Selbstupdate.widgetHinweis(context)
    } catch (e: RuntimeException) {
        false
    }

    /** „Stand 21:40“, leise „· ohne Netz“, wenn die Web-Apps aus dem Zwischenspeicher kommen. */
    fun standText(ergebnis: Ergebnis): String =
        "Stand ${uhrzeit(ergebnis.stand)}" + if (ergebnis.ohneNetz) " · ohne Netz" else ""

    /** „3 Std.\n12 Min.“ mit großen Zahlen und kleinen Einheiten. */
    fun ringZeichen(sekunden: Long): CharSequence {
        val text = ringText(sekunden)
        val s = SpannableString(text)
        for (m in Regex("[^0-9\\n]+").findAll(text)) {
            s.setSpan(RelativeSizeSpan(0.5f), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    private fun ringSetzen(context: Context, v: RemoteViews, ergebnis: Ergebnis, ringDp: Float) {
        val groesse = px(context, ringDp).coerceIn(96, 640)
        val werte = ringWerte(ergebnis.posten)
        if (Build.VERSION.SDK_INT >= 31) {
            v.setViewLayoutWidth(R.id.ring_rahmen, ringDp, TypedValue.COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(R.id.ring_rahmen, ringDp, TypedValue.COMPLEX_UNIT_DIP)
            v.setIcon(
                R.id.ring,
                "setImageIcon",
                Icon.createWithBitmap(ringBild(groesse, werte, RingFarben.HELL)),
                Icon.createWithBitmap(ringBild(groesse, werte, RingFarben.DUNKEL)),
            )
        } else {
            v.setImageViewBitmap(R.id.ring, ringBild(groesse, werte, if (nacht(context)) RingFarben.DUNKEL else RingFarben.HELL))
        }
        val innen = px(context, ringDp * (RING_DICKE + 0.06f))
        v.setViewPadding(R.id.gesamt, innen, innen, innen, innen)
        v.setTextViewTextSize(R.id.gesamt, TypedValue.COMPLEX_UNIT_DIP, ringDp * 0.2f)
        v.setTextViewText(R.id.gesamt, ringZeichen(ergebnis.gesamt))
        v.setContentDescription(R.id.ring_rahmen, "Bildschirmzeit heute: ${dauerText(ergebnis.gesamt)}")
    }

    private fun farbeSetzen(v: RemoteViews, id: Int, hell: Int, dunkel: Int, context: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            v.setColorInt(id, "setColorFilter", hell, dunkel)
        } else {
            v.setInt(id, "setColorFilter", if (nacht(context)) dunkel else hell)
        }
    }

    private fun neuRechnen(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, BildschirmzeitWidget::class.java).setAction(AKTUALISIEREN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun px(context: Context, dp: Float): Int = (dp * context.resources.displayMetrics.density).roundToInt()

    private fun nacht(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
