package de.finanzdienste.bildschirmzeit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/*
 * Die Statistik der App: Tag, Woche (Montag bis Sonntag), Monat – rein,
 * getestet in StatistikTest. Die Zahlen kommen aus dem Verlauf (Verlauf.kt).
 * Ein Tag ohne Eintrag hat „keine Daten“ – nie 0.
 */

/** Die drei Reiter oben in der App. */
enum class Reiter(val text: String) {
    TAG("Tag"),
    WOCHE("Woche"),
    MONAT("Monat"),
    ;

    companion object {
        /** Beim ersten Mal (nichts gemerkt): Woche. */
        fun von(name: String?): Reiter = entries.firstOrNull { it.name == name } ?: WOCHE
    }
}

/** Ein Zeitraum von `von` bis `bis` (beide eingeschlossen). */
data class Zeitraum(val reiter: Reiter, val von: LocalDate, val bis: LocalDate) {
    val tage: List<LocalDate>
        get() {
            val aus = ArrayList<LocalDate>()
            var t = von
            while (t <= bis) {
                aus += t
                t = t.plusDays(1)
            }
            return aus
        }

    fun vorher(): Zeitraum = um(reiter, von.minusDays(1))

    fun nachher(): Zeitraum = um(reiter, bis.plusDays(1))

    /** › gibt es, solange der nächste Zeitraum nicht nach heute beginnt. */
    fun hatNachher(heute: LocalDate): Boolean = bis < heute

    /** ‹ gibt es, solange der Verlauf vor diesem Zeitraum noch etwas hat. */
    fun hatVorher(datenSeit: LocalDate?): Boolean = datenSeit != null && datenSeit < von

    fun enthaelt(tag: LocalDate): Boolean = tag in von..bis

    companion object {
        /** Der Zeitraum dieses Reiters, in dem `tag` liegt. */
        fun um(reiter: Reiter, tag: LocalDate): Zeitraum = when (reiter) {
            Reiter.TAG -> Zeitraum(reiter, tag, tag)
            Reiter.WOCHE -> {
                val montag = tag.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Zeitraum(reiter, montag, montag.plusDays(6))
            }
            Reiter.MONAT -> {
                val erster = tag.withDayOfMonth(1)
                Zeitraum(reiter, erster, erster.plusMonths(1).minusDays(1))
            }
        }
    }
}

private val MONATE = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
private val MONATE_KURZ = listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.", "Okt.", "Nov.", "Dez.")
private val WOCHENTAGE = listOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")

/** „Mo“ … „So“ – unter den Balken. */
fun wochentagKurz(tag: LocalDate): String = WOCHENTAGE[tag.dayOfWeek.value - 1].removeSuffix(".")

/** „14. Nov.“, aus einem anderen Jahr „14. Nov. 2025“. */
fun datumKurz(tag: LocalDate, heute: LocalDate): String =
    "${tag.dayOfMonth}. ${MONATE_KURZ[tag.monthValue - 1]}" + if (tag.year != heute.year) " ${tag.year}" else ""

/** Der Titel zwischen ‹ und ›: „Heute“, „Gestern“, „Sa., 14. Nov.“, „9.–15. Nov.“, „November 2026“. */
fun zeitraumTitel(z: Zeitraum, heute: LocalDate): String = when (z.reiter) {
    Reiter.TAG -> when (z.von) {
        heute -> "Heute"
        heute.minusDays(1) -> "Gestern"
        else -> "${WOCHENTAGE[z.von.dayOfWeek.value - 1]}, ${datumKurz(z.von, heute)}"
    }
    Reiter.WOCHE -> spanne(z.von, z.bis, heute)
    Reiter.MONAT -> "${MONATE[z.von.monthValue - 1]} ${z.von.year}"
}

/**
 * „9.–15. Nov.“, über den Monatswechsel „27. Okt. – 2. Nov.“, übers Jahr
 * „28. Dez. – 3. Jan. 2027“ (die Jahreszahl einmal, hinten: Mit beiden passte
 * der Titel bei größerer Schrift nicht mehr zwischen ‹ und ›).
 */
fun spanne(von: LocalDate, bis: LocalDate, heute: LocalDate): String = when {
    von == bis -> datumKurz(von, heute)
    von.year != bis.year -> "${von.dayOfMonth}. ${MONATE_KURZ[von.monthValue - 1]} – ${datumKurz(bis, bis.plusYears(1))}"
    von.month == bis.month -> "${von.dayOfMonth}.–${datumKurz(bis, heute)}"
    else -> "${datumKurz(von, heute).removeSuffix(" ${von.year}")} – ${datumKurz(bis, heute)}"
}

/** Ein Tag im Zeitraum: Sekunden, oder null = keine Daten; `zukunft`: kommt erst noch. */
data class Tageswert(val datum: LocalDate, val sekunden: Long?, val zukunft: Boolean = false)

/** Was die Statistik für einen Zeitraum zeigt. */
data class Auswertung(
    val zeitraum: Zeitraum,
    val heute: LocalDate,
    /** Jeder Tag des Zeitraums, auch die ohne Daten und die noch kommenden. */
    val tage: List<Tageswert>,
    /** Alle Apps des Zeitraums, absteigend nach Zeit. */
    val apps: List<Posten>,
    /** Der früheste Tag im Verlauf; null = noch gar keine Daten. */
    val datenSeit: LocalDate?,
    /** Nur diese App (Schlüssel wie im Verlauf); null = alle. Dann sind `tage` und `apps` nur ihre. */
    val nur: String? = null,
    /** Die Zeit aller Apps im Zeitraum – für den Anteil der einen. */
    val alle: Long = apps.sumOf { it.sekunden },
) {
    val gesamt: Long get() = apps.sumOf { it.sekunden }

    /** Tage mit Daten. */
    val belegt: Int get() = tage.count { it.sekunden != null }

    /** Tage bis heute (die noch kommenden zählen nicht). */
    val vergangen: Int get() = tage.count { !it.zukunft }

    /** Ø je Tag – nur über die Tage, für die es Daten gibt. */
    val schnitt: Long? get() = if (belegt > 0) (gesamt.toDouble() / belegt).roundToLong() else null

    /** Vergangene Tage ab dem Beginn des Verlaufs, für die nichts da ist. */
    val luecken: List<LocalDate>
        get() = tage.filter { it.sekunden == null && !it.zukunft && datenSeit != null && it.datum >= datenSeit }.map { it.datum }
}

/**
 * Die Tage des Zeitraums aus dem Verlauf zusammenzählen; Namen vom jüngsten Tag.
 * Mit `nur` die eine App (Tobi, 15.11.: „Ich will auch auf ne eigene App klicken
 * und dann deren verteilung zb über die Woche sehen können“): ihre Zeit je Tag –
 * ein Tag mit Daten, an dem sie nicht lief, hat 0, einer ohne Daten bleibt „keine Daten“.
 */
fun auswerten(z: Zeitraum, verlauf: Map<LocalDate, Tag>, heute: LocalDate, datenSeit: LocalDate?, nur: String? = null): Auswertung {
    val tage = z.tage.map { d ->
        val tag = verlauf[d]
        when {
            d > heute -> Tageswert(d, null, zukunft = true)
            tag == null -> Tageswert(d, null)
            nur == null -> Tageswert(d, tag.gesamt)
            else -> Tageswert(d, tag.posten.filter { it.schluessel == nur }.sumOf { it.sekunden })
        }
    }
    val summe = HashMap<String, Long>()
    val namen = HashMap<String, String>()
    for (d in z.tage.sorted()) {
        if (d > heute) continue
        for (p in verlauf[d]?.posten.orEmpty()) {
            summe[p.schluessel] = (summe[p.schluessel] ?: 0L) + p.sekunden
            namen[p.schluessel] = p.name
        }
    }
    val alle = sortiert(summe.map { (k, s) -> Posten(k, WebApp.von(k)?.anzeigename ?: namen[k] ?: k, s) })
    val apps = if (nur == null) alle else alle.filter { it.schluessel == nur }
    return Auswertung(z, heute, tage, apps, datenSeit, nur, alle.sumOf { it.sekunden })
}

/** Die große Zahl: „12 Std. 34 Min.“, ein Tag ganz ohne Nutzung „0 Min.“ (wie im Widget), oder „keine Daten“. */
fun gesamtText(a: Auswertung): String = if (a.belegt == 0) "keine Daten" else summeText(a.gesamt)

/**
 * „Ø je Tag 2 Std. 15 Min.“ und, wenn Tage fehlen, „· 5 von 7 Tagen mit Daten“ – nur bei Woche und Monat.
 * Für eine App darunter ihr Anteil: „27 % deiner Bildschirmzeit“ (auch am Tag).
 */
fun schnittText(a: Auswertung): String? {
    val zeilen = ArrayList<String>()
    val s = a.schnitt
    if (a.zeitraum.reiter != Reiter.TAG && s != null) {
        val text = "Ø je Tag ${summeText(s)}"
        zeilen += if (a.belegt < a.vergangen) "$text · ${a.belegt} von ${a.vergangen} Tagen mit Daten" else text
    }
    if (a.nur != null && a.alle > 0L && a.gesamt > 0L) zeilen += "${anteilText(a.gesamt, a.alle)} deiner Bildschirmzeit"
    return zeilen.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/**
 * Was unter dem Diagramm steht: „Daten seit 6. Nov.“, wenn der Verlauf
 * erst im oder nach dem Zeitraum beginnt, und „Keine Daten: 9.–11. Nov.,
 * 14. Nov.“ für Lücken danach. null: nichts zu sagen.
 */
fun lueckenText(a: Auswertung): String? {
    val zeilen = ArrayList<String>()
    val seit = a.datenSeit
    if (seit != null && seit > a.zeitraum.von && seit <= a.heute) zeilen += "Daten seit ${datumKurz(seit, a.heute)}"
    val luecken = a.luecken
    if (luecken.isNotEmpty() && a.zeitraum.reiter != Reiter.TAG) {
        // Zusammenhängende Tage als eine Spanne: „9.–11. Nov.“
        val stuecke = ArrayList<String>()
        var i = 0
        while (i < luecken.size) {
            var j = i
            while (j + 1 < luecken.size && luecken[j + 1] == luecken[j].plusDays(1)) j++
            stuecke += spanne(luecken[i], luecken[j], a.heute)
            i = j + 1
        }
        zeilen += "Keine Daten: ${stuecke.joinToString(", ")}"
    }
    return zeilen.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** Anteil an der Gesamtzeit: „27 %“, unter einem halben Prozent „<1 %“. */
fun anteilText(sekunden: Long, gesamt: Long): String {
    if (gesamt <= 0L) return ""
    val p = (sekunden * 100.0 / gesamt).roundToLong()
    return if (p < 1) "<1 %" else "$p %"
}

/** „Stand 21:40“ (nie umbrochen), leise „· ohne Netz“, wenn die Web-Apps aus dem Zwischenspeicher kommen. */
fun statistikStand(e: Ergebnis): String = "Stand\u00A0${uhrzeit(e.stand)}" + if (e.ohneNetz) " · ohne\u00A0Netz" else ""
