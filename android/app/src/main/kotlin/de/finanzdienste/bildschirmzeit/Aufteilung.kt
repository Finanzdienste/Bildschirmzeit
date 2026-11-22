package de.finanzdienste.bildschirmzeit

/** Chrome – in ihm laufen Tobis Web-Apps (WebApp), bis auf die Bewerbungszentrale. */
const val CHROME = "com.android.chrome"

/**
 * Firefox – dort läuft seit 21.11.2026 die Bewerbungszentrale (WebApp.browser).
 * Tobi: „Wenn ich in unserer App auf den stellenanzeigen link klicke soll sich
 * direkt Firefox öffnen“; danach am 22.11.: „hab in der widget app auf verbinden
 * geklickt und dann hat sich Chrome geöffnet“.
 */
const val FIREFOX = "org.mozilla.firefox"

/** „Chrome“ oder „Firefox“ für den Browser einer Web-App. */
fun browserName(paket: String): String = if (paket == FIREFOX) "Firefox" else "Chrome"

/**
 * Installierte Web-Apps (WebAPKs) haben ein eigenes kleines Paket, das nur
 * Chrome startet. Was Android ihm zuschreibt (meist Sekundenbruchteile), ist
 * Zeit im Browser und gehört zu Chrome – sonst stünde etwa „Workout“ zweimal da.
 */
const val WEBAPK = "org.chromium.webapk."

fun webApksZuChrome(android: Map<String, Long>): Map<String, Long> {
    if (android.keys.none { it.startsWith(WEBAPK) }) return android
    val aus = LinkedHashMap<String, Long>()
    var chrome = android[CHROME] ?: 0L
    for ((paket, sek) in android) {
        if (paket.startsWith(WEBAPK)) chrome += sek else if (paket != CHROME) aus[paket] = sek
    }
    if (chrome > 0) aus[CHROME] = chrome
    return aus
}

/**
 * Tobis Web-Apps: Schlüssel wie in Supabase (`p_app`) und in BZ_APPS von
 * web/bildschirmzeit.js (tests/test-web.mjs prüft, dass die Listen gleich
 * sind), Name und Symbol wie im Widget, dazu der Browser, in dem sie läuft:
 * Von dessen Zeit wird sie abgezogen, und dorthin geht der Verbinden-Link.
 */
enum class WebApp(val schluessel: String, val anzeigename: String, private val pfad: String, val symbol: Int, val browser: String = CHROME) {
    BOXENSTOPP("boxenstopp", "Boxenstopp", "Boxenstopp", R.drawable.symbol_boxenstopp),
    FREIZEIT("freizeit", "Freizeit", "Freizeit", R.drawable.symbol_freizeit),
    WORKOUT("workout", "Workout", "Workout", R.drawable.symbol_workout),

    // Seit 19.11.2026. Im Widget heißt sie wie auf Tobis Startbildschirm (short_name im Manifest).
    // Seit 21.11.2026 in Firefox (FIREFOX).
    BEWERBUNGSZENTRALE("bewerbungszentrale", "Bewerbungen", "Bewerbungszentrale", R.drawable.symbol_bewerbungszentrale, FIREFOX),
    ;

    /** Der Verbinden-Link: öffnet die Web-App, die sich damit an diese Kennung bindet. */
    fun link(kennung: String) = "https://finanzdienste.github.io/$pfad/#bildschirmzeit=$kennung"

    companion object {
        fun von(schluessel: String): WebApp? = entries.firstOrNull { it.schluessel == schluessel }
    }
}

/** Ein Eintrag der Liste: ein Paket oder eine der Web-Apps (Schlüssel ohne Punkt). */
data class Posten(val schluessel: String, val name: String, val sekunden: Long) {
    val webApp: WebApp? get() = WebApp.von(schluessel)
}

/**
 * Jeden Browser um seine Web-Apps kürzen (Chrome um Boxenstopp, Freizeit und
 * Workout, Firefox um die Bewerbungszentrale): `android` sind Sekunden je
 * Paket (Androids Rechnung), `web` Sekunden je Web-App (was die Apps selbst
 * gemessen haben). Haben die Web-Apps eines Browsers zusammen mehr gemeldet,
 * als er lief, werden sie anteilig auf seine Zeit gekürzt – die Gesamtzeit
 * bleibt so immer die von Android. Was vom Browser übrig bleibt, bleibt beim
 * Browser. Ergebnis: Sekunden je Schlüssel, nur Einträge über 0.
 */
fun aufteilen(android: Map<String, Long>, web: Map<String, Long>): Map<String, Long> {
    val browser = WebApp.entries.map { it.browser }.distinct()
    val aus = LinkedHashMap<String, Long>()
    for ((paket, sek) in android) if (paket !in browser && sek > 0) aus[paket] = sek
    for (b in browser) {
        val zeit = maxOf(0L, android[b] ?: 0L)
        val apps = WebApp.entries.filter { it.browser == b }
        val gemeldet = apps.map { maxOf(0L, web[it.schluessel] ?: 0L) }
        val anteile = if (gemeldet.sum() <= zeit) gemeldet else anteilig(gemeldet, zeit)
        apps.zip(anteile).forEach { (app, sek) -> if (sek > 0) aus[app.schluessel] = sek }
        val rest = zeit - anteile.sum()
        if (rest > 0) aus[b] = rest
    }
    return aus
}

/**
 * `werte` so verkleinern, dass sie zusammen genau `ziel` ergeben, jeder im
 * selben Verhältnis (größter Rest bekommt die übrigen Sekunden, bei Gleichstand
 * der frühere).
 */
internal fun anteilig(werte: List<Long>, ziel: Long): List<Long> {
    val summe = werte.sum()
    if (summe <= 0L || ziel <= 0L) return werte.map { 0L }
    val basis = werte.map { it * ziel / summe }.toMutableList()
    var uebrig = ziel - basis.sum()
    val reihenfolge = werte.indices.sortedWith(compareByDescending<Int> { werte[it] * ziel % summe }.thenBy { it })
    for (i in reihenfolge) {
        if (uebrig <= 0) break
        basis[i]++
        uebrig--
    }
    return basis
}

/** Die Liste fürs Widget und die App: absteigend nach Zeit, bei Gleichstand nach Name. */
fun sortiert(posten: Collection<Posten>): List<Posten> =
    posten.filter { it.sekunden > 0 }.sortedWith(compareByDescending<Posten> { it.sekunden }.thenBy { it.name.lowercase() })
