package de.finanzdienste.bildschirmzeit

import java.time.LocalDate
import kotlin.math.roundToLong

/*
 * Die Statistik der App: Tag, Woche (die letzten sieben Tage – Tobi, 23.11.:
 * „Woche bedeutet ja nicht Montag bis Sonntag sondern letzte sieben tage“),
 * Monat (die letzten 30 Tage – „Monat genauso. Ist nicht November sondern die
 * letzten x tage“) – rein,
 * getestet in StatistikTest. Die Zahlen kommen aus dem Verlauf (Verlauf.kt).
 * Ein Tag ohne Eintrag hat „keine Daten“ – nie 0.
 */

/** Die drei Reiter oben in der App. */
enum class Reiter(val text: String, val tage: Int) {
    TAG("Tag", 1),
    WOCHE("Woche", 7),
    MONAT("Monat", 30),
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

    /** Der nächste Zeitraum; Woche und Monat enden dabei höchstens `heute` (dann sind es wieder die letzten 7 bzw. 30 Tage). */
    fun nachher(heute: LocalDate? = null): Zeitraum = when (reiter) {
        Reiter.TAG -> um(reiter, bis.plusDays(1))
        else -> um(reiter, bis.plusDays(reiter.tage.toLong()).let { if (heute != null && it > heute) heute else it })
    }

    /** › gibt es, solange der nächste Zeitraum nicht nach heute beginnt. */
    fun hatNachher(heute: LocalDate): Boolean = bis < heute

    /** ‹ gibt es, solange der Verlauf vor diesem Zeitraum noch etwas hat. */
    fun hatVorher(datenSeit: LocalDate?): Boolean = datenSeit != null && datenSeit < von

    fun enthaelt(tag: LocalDate): Boolean = tag in von..bis

    companion object {
        /** Der Zeitraum dieses Reiters, der mit `tag` endet: der Tag, die sieben bzw. 30 Tage bis `tag`. */
        fun um(reiter: Reiter, tag: LocalDate): Zeitraum = Zeitraum(reiter, tag.minusDays(reiter.tage - 1L), tag)
    }
}

private val MONATE_KURZ = listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.", "Okt.", "Nov.", "Dez.")
private val WOCHENTAGE = listOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")

/** „Mo“ … „So“ – unter den Balken. */
fun wochentagKurz(tag: LocalDate): String = WOCHENTAGE[tag.dayOfWeek.value - 1].removeSuffix(".")

/** „14. Nov.“, aus einem anderen Jahr „14. Nov. 2025“. */
fun datumKurz(tag: LocalDate, heute: LocalDate): String =
    "${tag.dayOfMonth}. ${MONATE_KURZ[tag.monthValue - 1]}" + if (tag.year != heute.year) " ${tag.year}" else ""

/** Der Titel zwischen ‹ und ›: „Heute“, „Gestern“, „Sa., 14. Nov.“, „17.–23. Nov.“, „25. Okt. – 23. Nov.“. */
fun zeitraumTitel(z: Zeitraum, heute: LocalDate): String = when (z.reiter) {
    Reiter.TAG -> when (z.von) {
        heute -> "Heute"
        heute.minusDays(1) -> "Gestern"
        else -> "${WOCHENTAGE[z.von.dayOfWeek.value - 1]}, ${datumKurz(z.von, heute)}"
    }
    Reiter.WOCHE, Reiter.MONAT -> spanne(z.von, z.bis, heute)
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
    /** Davon unproduktiv (alle Apps im Zeitraum, Unproduktiv.kt). */
    val unproduktiv: Long = 0L,
    /** Zählt die eine gewählte App (`nur`) als unproduktiv? */
    val nurUnproduktiv: Boolean = false,
    /** Alle, nur Produktives oder nur Unproduktives: Dann sind `tage` und `apps` nur das; `alle` bleibt alles. */
    val filter: WidgetFilter = WidgetFilter.ALLE,
) {
    val gesamt: Long get() = apps.sumOf { it.sekunden }

    /** Tage mit Daten. */
    val belegt: Int get() = tage.count { it.sekunden != null }

    /** Tage bis heute, ab dem Beginn des Verlaufs (die noch kommenden und die davor zählen nicht – „Daten seit …“ steht darunter). */
    val vergangen: Int get() = tage.count { !it.zukunft && (datenSeit == null || it.datum >= datenSeit) }

    /** Ø je Tag – nur über die Tage, für die es Daten gibt. */
    val schnitt: Long? get() = if (belegt > 0) (gesamt.toDouble() / belegt).roundToLong() else null

    /** Ø je Tag unproduktiv – über dieselben Tage wie `schnitt`; null, wenn es nichts gibt. */
    val unproduktivSchnitt: Long? get() = if (belegt > 0 && unproduktiv > 0L) (unproduktiv.toDouble() / belegt).roundToLong() else null

    /** Vergangene Tage ab dem Beginn des Verlaufs, für die nichts da ist. */
    val luecken: List<LocalDate>
        get() = tage.filter { it.sekunden == null && !it.zukunft && datenSeit != null && it.datum >= datenSeit }.map { it.datum }
}

/**
 * Die Tage des Zeitraums aus dem Verlauf zusammenzählen; Namen vom jüngsten Tag.
 * Mit `nur` die eine App (Tobi, 15.11.: „Ich will auch auf ne eigene App klicken
 * und dann deren verteilung zb über die Woche sehen können“): ihre Zeit je Tag –
 * ein Tag mit Daten, an dem sie nicht lief, hat 0, einer ohne Daten bleibt „keine Daten“.
 *
 * Webseiten (Tobi, 16.11.: „… alles unter fünf min unter sonstige websiten
 * zusammengefasst … Also Chrome und Firefox addiert“): je Domain beide
 * Browser zusammen, ab 5 Minuten im Zeitraum einzeln, sonst „Sonstige
 * Webseiten“. `nur` ist ein gezeigter Schlüssel (auch „webseite:…“); eine
 * angetippte Seite zählt in jedem Zeitraum einzeln.
 */
fun auswerten(
    z: Zeitraum,
    verlauf: Map<LocalDate, Tag>,
    heute: LocalDate,
    datenSeit: LocalDate?,
    nur: String? = null,
    verborgen: (String) -> Boolean = { false },
    appVerborgen: (String, String) -> Boolean = { _, _ -> false },
    unproduktiv: (String) -> Boolean = { false },
    filter: WidgetFilter = WidgetFilter.ALLE,
): Auswertung {
    val summe = HashMap<String, Long>()
    val namen = HashMap<String, String>()
    for (d in z.tage.sorted()) {
        if (d > heute) continue
        for (p in verlauf[d]?.posten.orEmpty()) {
            summe[p.schluessel] = (summe[p.schluessel] ?: 0L) + p.sekunden
            namen[p.schluessel] = p.name
        }
    }
    val einzeln = einzelneDomains(summe, immer = nur, verborgen = verborgen)
    val firefox = firefoxSchluessel(summe.keys)
    // Seiten zusammen (ab 5 Minuten einzeln, der Rest zu Firefox), Apps, die nie mit Namen erscheinen, zu „Sonstiges“ (Diskret.kt).
    fun gruppe(k: String, name: String): String =
        if (seiteVon(k) == null && appVerborgen(k, name)) SONSTIGES else anzeigeSchluessel(k, einzeln, firefox)
    // Nur Produktives oder nur Unproduktives (Tobi, 27.11.): jeder Posten nach Unproduktiv.kt, wie im Widget –
    // „Firefox“ kann so in beiden Ansichten stehen, je mit seinem Teil.
    fun zaehlt(p: Posten): Boolean = filter == WidgetFilter.ALLE ||
        (p.sekunden > 0L && unproduktiv(einstufungsSchluessel(p, firefox, appVerborgen)) == (filter == WidgetFilter.UNPRODUKTIV))
    val tage = z.tage.map { d ->
        val tag = verlauf[d]
        when {
            d > heute -> Tageswert(d, null, zukunft = true)
            tag == null -> Tageswert(d, null)
            nur == null && filter == WidgetFilter.ALLE -> Tageswert(d, tag.gesamt)
            nur == null -> Tageswert(d, tag.posten.filter { zaehlt(it) }.sumOf { it.sekunden })
            else -> Tageswert(d, tag.posten.filter { gruppe(it.schluessel, it.name) == nur && zaehlt(it) }.sumOf { it.sekunden })
        }
    }
    val gruppiert = HashMap<String, Long>()
    val gezeigt = HashMap<String, Long>()
    for ((k, s) in summe) {
        val name = namen[k] ?: k
        val g = gruppe(k, name)
        gruppiert[g] = (gruppiert[g] ?: 0L) + s
        if (filter != WidgetFilter.ALLE && zaehlt(Posten(k, name, s))) gezeigt[g] = (gezeigt[g] ?: 0L) + s
    }
    fun liste(m: Map<String, Long>) = sortiert(m.map { (k, s) -> Posten(k, anzeigename(k) ?: namen[k] ?: if (k == firefox) FIREFOX_NAME else k, s) })
    val alle = liste(gruppiert)
    val auswahl = if (filter == WidgetFilter.ALLE) alle else liste(gezeigt)
    val apps = if (nur == null) auswahl else auswahl.filter { it.schluessel == nur }
    // Unproduktiv nach den Posten jedes Tages (eine kleine Seite nach ihrer Domain, wie im Widget).
    val roh = z.tage.filter { it <= heute }.flatMap { verlauf[it]?.posten.orEmpty() }
    val u = unproduktivSekunden(roh, unproduktiv, appVerborgen, firefox)
    return Auswertung(z, heute, tage, apps, datenSeit, nur, alle.sumOf { it.sekunden }, u, nur?.let(unproduktiv) == true, filter)
}

/** Die große Zahl: „12 Std. 34 Min.“, ein Tag ganz ohne Nutzung „0 Min.“ (wie im Widget), oder „keine Daten“. */
fun gesamtText(a: Auswertung): String = if (a.belegt == 0) "keine Daten" else summeText(a.gesamt)

/**
 * „Ø je Tag 2 Std. 15 Min.“ und, wenn Tage fehlen, „· 5 von 7 Tagen mit Daten“ – nur bei Woche und Monat.
 * Für eine App darunter ihr Anteil: „27 % deiner Bildschirmzeit“ (auch am Tag).
 * Für alle Apps „davon unproduktiv: 3 Std. 5 Min. (29 %)“, wenn es etwas gibt,
 * bei Woche und Monat darunter „Ø je Tag unproduktiv: 26 Min.“ (Tobi, 26.11.:
 * „Will auch bei der unproduktiven Zeit den tagesschnitt wissen“); für eine
 * App, die als unproduktiv zählt, „zählt als unproduktiv“. Nur Produktives
 * oder nur Unproduktives (Tobi, 27.11.: „Meinte ich nicht ich will oben so
 * Buttons wo ich zwischen produktiv und unproduktiv und so wechseln kann?“):
 * „von 4 Std. 27 Min. (86 %)“ und „Ø je Tag produktiv: …“.
 */
fun schnittText(a: Auswertung): String? {
    val zeilen = ArrayList<String>()
    // Nur Produktives oder Unproduktives (Tobi, 27.11.): zuerst, wovon – wie im Widget „(von …)“.
    val gefiltert = a.nur == null && a.filter != WidgetFilter.ALLE
    if (gefiltert && a.alle > 0L) zeilen += "von ${summeText(a.alle)} (${if (a.gesamt > 0L) anteilText(a.gesamt, a.alle) else "0\u00A0%"})"
    val s = a.schnitt
    if (a.zeitraum.reiter != Reiter.TAG && s != null) {
        val text = when {
            !gefiltert -> "Ø je Tag ${summeText(s)}"
            a.filter == WidgetFilter.PRODUKTIV -> "Ø je Tag produktiv: ${summeText(s)}"
            else -> "Ø je Tag unproduktiv: ${summeText(s)}"
        }
        zeilen += if (a.belegt < a.vergangen) "$text · ${a.belegt} von ${a.vergangen} Tagen mit Daten" else text
    }
    if (a.nur != null && a.alle > 0L && a.gesamt > 0L) zeilen += "${anteilText(a.gesamt, a.alle)} deiner Bildschirmzeit"
    // Wie im Widget „davon unproduktiv“ (hier nur, wenn es etwas gibt); eine gewählte App sagt, ob sie zählt.
    if (a.nur == null && a.filter == WidgetFilter.ALLE && a.unproduktiv > 0L && a.gesamt > 0L) {
        zeilen += "davon unproduktiv: ${summeText(a.unproduktiv)} (${anteilText(a.unproduktiv, a.gesamt)})"
        val u = a.unproduktivSchnitt
        if (a.zeitraum.reiter != Reiter.TAG && u != null) zeilen += "Ø je Tag unproduktiv: ${summeText(u)}"
    }
    if (a.nur != null && a.nurUnproduktiv) zeilen += "zählt als unproduktiv"
    return zeilen.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/**
 * Nur Produktives oder Unproduktives, und davon nichts im Zeitraum (Tobi,
 * 27.11.): ein Satz statt einer leeren Liste – „Heute noch nichts
 * Unproduktives.“, „An diesem Tag …“, bei Woche und Monat „In diesem Zeitraum
 * …“. null: nichts zu sagen.
 */
fun filterLeerText(a: Auswertung): String? {
    if (a.nur != null || a.filter == WidgetFilter.ALLE || a.belegt == 0 || a.apps.isNotEmpty()) return null
    val wort = if (a.filter == WidgetFilter.PRODUKTIV) "Produktives" else "Unproduktives"
    return when {
        a.zeitraum.reiter != Reiter.TAG -> "In diesem Zeitraum nichts $wort."
        a.zeitraum.von == a.heute -> "Heute noch nichts $wort."
        else -> "An diesem Tag nichts $wort."
    }
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
