package de.finanzdienste.bildschirmzeit

/*
 * Die Rechnung „wie lange war jede App heute im Vordergrund“ – als reine
 * Funktion über eine Ereignisliste, damit sie ohne Gerät getestet werden kann
 * (NutzungTest). Woher die Ereignisse kommen, steht in Nutzungsdaten.kt.
 */

/** Was in Androids Nutzungsdaten passiert ist – nur die Arten, die hier zählen. */
enum class Art {
    /** ACTIVITY_RESUMED: eine Activity ist vorn. */
    VORN,

    /** ACTIVITY_PAUSED: sie ist nicht mehr vorn. */
    PAUSIERT,

    /** ACTIVITY_STOPPED: nicht mehr sichtbar (schließt auch, wenn PAUSED fehlte). */
    GESTOPPT,

    /** SCREEN_NON_INTERACTIVE: Bildschirm aus – alles zu. */
    BILDSCHIRM_AUS,

    /** KEYGUARD_SHOWN: Sperrbildschirm – alles zu. */
    GESPERRT,

    /** DEVICE_SHUTDOWN: Gerät geht aus – alles zu. */
    AUSGESCHALTET,

    /**
     * DEVICE_STARTUP: Gerät ist (wieder) an. Was davor noch offen war, hat
     * kein Ende bekommen (Absturz, Akku leer) und zählt nicht: Wie lange es
     * wirklich lief, weiß niemand.
     */
    GESTARTET,
}

data class Ereignis(val zeit: Long, val art: Art, val paket: String = "", val klasse: String = "")

/** Die Systemoberfläche (Benachrichtigungen, Schnelleinstellungen) zählt nie. */
const val SYSTEMUI = "com.android.systemui"

/**
 * Vordergrundzeit in Millisekunden je Paket im Zeitraum [von, bis].
 *
 * Eine App ist vorn, solange mindestens eine ihrer Activities vorn ist –
 * zwei Activities derselben App (auch gleichzeitig, etwa beim Wechsel)
 * zählen einmal. Bildschirm aus, Sperrbildschirm und Ausschalten schließen
 * alles. Was am Ende noch offen ist, zählt bis `bis`. Die Ereignisse dürfen
 * vor `von` beginnen (so zählt eine über Mitternacht offene App ab
 * Mitternacht); gezählt wird nur, was in [von, bis] liegt. Ein PAUSIERT ohne
 * vorheriges VORN (die App kam vor Beginn der Abfrage nach vorn) zählt nicht.
 */
fun vordergrundZeiten(ereignisse: List<Ereignis>, von: Long, bis: Long): Map<String, Long> {
    val offen = HashMap<String, MutableSet<String>>() // Paket → Activities, die gerade vorn sind
    val seit = HashMap<String, Long>() // Paket → seit wann vorn
    val summe = HashMap<String, Long>()

    fun gutschreiben(paket: String, anfang: Long, ende: Long) {
        val a = maxOf(anfang, von)
        val e = minOf(ende, bis)
        if (e > a) summe[paket] = (summe[paket] ?: 0L) + (e - a)
    }

    fun schliessen(paket: String, zeit: Long) {
        seit.remove(paket)?.let { gutschreiben(paket, it, zeit) }
        offen.remove(paket)
    }

    fun alleSchliessen(zeit: Long) {
        for (paket in seit.keys.toList()) schliessen(paket, zeit)
        offen.clear()
    }

    // sortedBy ist stabil: Ereignisse mit gleicher Zeit behalten ihre Reihenfolge.
    for (e in ereignisse.sortedBy { it.zeit }) {
        if (e.zeit > bis) break
        when (e.art) {
            Art.VORN -> {
                val activities = offen.getOrPut(e.paket) { mutableSetOf() }
                if (activities.isEmpty()) seit[e.paket] = e.zeit
                activities.add(e.klasse)
            }
            Art.PAUSIERT, Art.GESTOPPT -> {
                val activities = offen[e.paket] ?: continue
                if (activities.remove(e.klasse) && activities.isEmpty()) schliessen(e.paket, e.zeit)
            }
            Art.BILDSCHIRM_AUS, Art.GESPERRT, Art.AUSGESCHALTET -> alleSchliessen(e.zeit)
            Art.GESTARTET -> {
                seit.clear()
                offen.clear()
            }
        }
    }
    alleSchliessen(bis)
    return summe
}

/**
 * Vordergrundzeit heute je App in Sekunden: ohne Startbildschirm (jede
 * HOME-Activity, als „paket/klasse“ in `homeActivities`), ohne
 * Systemoberfläche, und nur Pakete mit einem Eintrag im App-Drawer
 * (`launcherPakete`). So zählt etwa die Einstellungen-App, nicht aber deren
 * Notfall-Startbildschirm.
 */
fun heuteJeApp(
    ereignisse: List<Ereignis>,
    von: Long,
    bis: Long,
    launcherPakete: Set<String>,
    homeActivities: Set<String>,
): Map<String, Long> {
    val zaehlbar = ereignisse.filter { e ->
        when (e.art) {
            Art.VORN, Art.PAUSIERT, Art.GESTOPPT ->
                e.paket != SYSTEMUI && "${e.paket}/${e.klasse}" !in homeActivities
            else -> true
        }
    }
    return vordergrundZeiten(zaehlbar, von, bis)
        .filterKeys { it in launcherPakete }
        .mapValues { it.value / 1000 }
        .filterValues { it > 0 }
}
