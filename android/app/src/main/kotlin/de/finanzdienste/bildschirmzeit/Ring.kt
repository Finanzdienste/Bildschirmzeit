package de.finanzdienste.bildschirmzeit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap

/** Ein Stück des Rings: Anfang und Länge in Grad (0 = rechts, wie Canvas.drawArc), Farbe. */
data class Bogen(val anfang: Float, val laenge: Float, val farbe: Int)

/** Farben des Rings: drei für die Top 3, grau für den Rest, die Spur dahinter. */
data class RingFarben(val top: List<Int>, val rest: Int, val spur: Int) {
    companion object {
        val HELL = RingFarben(listOf(0xFF3A7BF2.toInt(), 0xFF1FB37A.toInt(), 0xFFF5A623.toInt()), 0xFFC3C8D0.toInt(), 0xFFECEEF2.toInt())
        val DUNKEL = RingFarben(listOf(0xFF5B93FF.toInt(), 0xFF2CC98B.toInt(), 0xFFFFB648.toInt()), 0xFF5E646D.toInt(), 0xFF2E3138.toInt())
    }
}

/**
 * Die Werte (Top 3, dann der Rest) als Bögen eines vollen Rings, oben
 * beginnend im Uhrzeigersinn, mit `luecke` Grad zwischen zwei Bögen. Werte
 * von 0 fallen weg; ein einzelner Wert ist ein geschlossener Ring. Jeder Bogen
 * bleibt sichtbar (mindestens `kleinster` Grad).
 */
fun boegen(werte: List<Long>, farben: List<Int>, luecke: Float = 3f, kleinster: Float = 1.2f): List<Bogen> {
    val teile = werte.zip(farben).filter { it.first > 0 }
    val summe = teile.sumOf { it.first }
    if (summe <= 0) return emptyList()
    if (teile.size == 1) return listOf(Bogen(-90f, 360f, teile[0].second))
    val aus = ArrayList<Bogen>()
    var winkel = -90f
    for ((wert, farbe) in teile) {
        val anteil = 360f * wert / summe
        aus += Bogen(winkel + luecke / 2, maxOf(kleinster, anteil - luecke), farbe)
        winkel += anteil
    }
    return aus
}

/** Die Ringwerte zu einer Liste: die ersten drei, dann alles Übrige zusammen. */
fun ringWerte(posten: List<Posten>): List<Long> {
    val top = posten.take(3).map { it.sekunden }
    val rest = posten.drop(3).sumOf { it.sekunden }
    return top + List(3 - top.size) { 0L } + rest
}

/** Der Ring als quadratisches Bild mit `groesse` Pixeln Kantenlänge. */
fun ringBild(groesse: Int, werte: List<Long>, farben: RingFarben): Bitmap {
    val bild = createBitmap(groesse, groesse)
    val leinwand = Canvas(bild)
    val dicke = groesse * RING_DICKE
    val rahmen = RectF(dicke / 2, dicke / 2, groesse - dicke / 2, groesse - dicke / 2)
    val stift = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dicke
        strokeCap = Paint.Cap.BUTT
    }
    stift.color = farben.spur
    leinwand.drawOval(rahmen, stift)
    for (b in boegen(werte, farben.top + farben.rest)) {
        stift.color = b.farbe
        leinwand.drawArc(rahmen, b.anfang, b.laenge, false, stift)
    }
    return bild
}

/** Ringbreite im Verhältnis zum Durchmesser. */
const val RING_DICKE = 0.115f
