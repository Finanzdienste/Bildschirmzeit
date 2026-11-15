package de.finanzdienste.bildschirmzeit

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap

/** App-Symbole für Widget und App. */
object Symbole {

    /** Das Symbol eines Postens als Drawable: Web-App aus den Ressourcen, sonst vom System. */
    fun drawable(context: Context, posten: Posten): Drawable? {
        posten.webApp?.let { return ContextCompat.getDrawable(context, it.symbol) }
        if (seiteVon(posten.schluessel) != null) return null
        return try {
            context.packageManager.getApplicationIcon(posten.schluessel)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Als Bild mit `groesse` Pixeln; ohne Symbol ein grauer Kreis mit dem
     * Anfangsbuchstaben. Eine Webseite (Firefox) bekommt eine eigene, blaue
     * Kachel mit ihrem Anfangsbuchstaben – kein Favicon (das hieße, die Seite
     * im Netz zu fragen), und klar anders als ein App-Symbol.
     */
    fun bild(context: Context, posten: Posten, groesse: Int): Bitmap {
        if (seiteVon(posten.schluessel) != null) return buchstabe(posten.name, groesse, SEITE_FARBE)
        return drawable(context, posten)?.toBitmap(groesse, groesse, Bitmap.Config.ARGB_8888) ?: buchstabe(posten.name, groesse)
    }

    /** Die Kachel einer Webseite: ein ruhiges Blau, in hell und dunkel lesbar (weißer Buchstabe). */
    const val SEITE_FARBE = 0xFF4F7CAC.toInt()

    /** Ein neutrales Ersatzsymbol – nie das Logo einer fremden App. */
    fun buchstabe(name: String, groesse: Int, farbe: Int = 0xFF8A93A0.toInt()): Bitmap {
        val bild = createBitmap(groesse, groesse)
        val leinwand = Canvas(bild)
        val pinsel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = farbe }
        val r = groesse * 0.24f
        leinwand.drawRoundRect(RectF(0f, 0f, groesse.toFloat(), groesse.toFloat()), r, r, pinsel)
        pinsel.color = 0xFFFFFFFF.toInt()
        pinsel.textAlign = Paint.Align.CENTER
        pinsel.textSize = groesse * 0.52f
        pinsel.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val zeichen = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val y = groesse / 2f - (pinsel.descent() + pinsel.ascent()) / 2
        leinwand.drawText(zeichen, groesse / 2f, y, pinsel)
        return bild
    }
}
