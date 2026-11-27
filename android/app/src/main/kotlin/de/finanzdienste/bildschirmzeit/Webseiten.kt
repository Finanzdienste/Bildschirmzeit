package de.finanzdienste.bildschirmzeit

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.io.IOException

/*
 * Webseiten in Firefox und Chrome einzeln – Tobi, 15.11.: „Bei Firefox bin ich
 * viel auf Youtube. Kann man sich die größten Webseiten als einzelnen Punkt
 * auflisten lassen?“, und 16.11.: „Das gleiche für Chrome. Aber würde sagen
 * lass mal machen dass alles unter fünf min unter sonstige websiten
 * zusammengefasst wird oder? Also Chrome und Firefox addiert“.
 *
 * Android kennt nur die App; welche Seite offen ist, steht nur in der
 * Adresszeile. Die liest der WebseitenDienst (eine Bedienungshilfe, die nur
 * die Browser sieht) und merkt sich bei jedem Wechsel die Domain mit Uhrzeit
 * (Beobachtungen). Die Rechnung teilt die Vordergrundzeit des Browsers danach
 * auf: jede erkannte Seite ein Posten „seite:<paket>/<domain>“ im Verlauf,
 * der Rest bleibt der Browser. Gezeigt (Widget, Statistik) wird sie
 * zusammengefasst (webseitenZusammenfassen): dieselbe Domain aus beiden
 * Browsern addiert, ab 5 Minuten (am Tag bzw. im Zeitraum) einzeln, sonst
 * zu „Firefox“ (seit v28). Die Gesamtzeit ändert sich nie. Rein und ohne Android
 * getestet in WebseitenTest.
 */

/** Die Firefox-Pakete, deren Adresszeile gelesen wird. */
val FIREFOXE = setOf("org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix")

/** Chrome und seine Vorabfassungen. In Chrome laufen auch Tobis Web-Apps (Aufteilung.kt). */
val CHROMES = setOf(CHROME, "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")

/** Alle Browser, die der Dienst sieht (packageNames in xml/webseiten_dienst.xml). */
val BROWSER = FIREFOXE + CHROMES

/** Schlüsselraum der Seiten im Verlauf (je Browser) – kein Paketname beginnt so. */
const val SEITE = "seite:"

/** Ab so viel Zeit am Tag wird eine erkannte Seite ein Posten; der Rest bleibt im Browser. */
const val SEITE_MIN_SEKUNDEN = 1L

/** Ab so viel Zeit (beide Browser zusammen, im Tag bzw. Zeitraum) steht eine Seite einzeln da. */
const val WEBSEITE_EINZELN_SEKUNDEN = 5 * 60L

/** Schlüsselraum der gezeigten Seiten (beide Browser zusammen): „webseite:youtube.com“. */
const val WEBSEITE = "webseite:"

/**
 * Apps, die nie mit Namen erscheinen (Dating, selbst zusammengelegt; Diskret.kt):
 * „Sonstiges“. Bis v27 standen hier auch alle Seiten, die nicht einzeln stehen –
 * seit v28 zählen die zu Firefox (FIREFOX_NAME). Der Wert ist noch von damals.
 */
const val SONSTIGES = "webseite:*"
const val SONSTIGE_NAME = "Sonstiges"

/** Im Widget (dort ist neben der Zeit weniger Platz). */
const val SONSTIGE_KURZ = "Sonstiges"

/**
 * Seiten, die nicht einzeln stehen – unter 5 Minuten, ohne Namen (neuer Tab,
 * Suche, privat, Inkognito), Erotik und Dating, selbst zusammengelegte –, aus
 * Firefox und Chrome zählen seit v28 zu „Firefox“, zusammen mit Firefox selbst.
 * Tobi, 25.11.: „Aber statt sonstiges können wir das ja einfach Firefox nennen.
 * Und Dating Apps sind dann stattdessen sonstiges“.
 */
const val FIREFOX_NAME = "Firefox"

/**
 * Hier liegen Tobis Web-Apps (WebApp, etwa Workout). In Chrome zählen
 * sie über Supabase unter eigenem Namen – dort also keine Seite.
 */
const val WEB_APPS_DOMAIN = "finanzdienste.github.io"

/**
 * „Eine Seite, aber ohne erkennbare Domain“: neuer Tab, Suchbegriffe statt
 * Adresse, about:, private und Inkognito-Tabs. Zählt zu „Firefox“ (seit v28,
 * vorher „Sonstiges“), nie einzeln – Tobi, 16.11., Widget mit Chrome als eigenem Eintrag: „Eigentlich dürften
 * Chrome und Firefox ja garnicht mehr auftauchen oder?“
 */
const val OHNE_DOMAIN = "?"

/**
 * Eine diskrete Seite (Erotik, Dating, selbst zusammengefasste – Diskret.kt):
 * ohne Namen wie OHNE_DOMAIN und bei „Firefox“, zählt aber wie „Sonstiges“ als
 * unproduktiv (Unproduktiv.kt) – Tobi, 27.11., auf die Frage, ob die
 * diskreten Seiten als unproduktiv zählen sollen: „Jo“. Seit v35; was vorher
 * gespeichert wurde, bleibt OHNE_DOMAIN (lässt sich nicht mehr trennen).
 */
const val DISKRET_DOMAIN = "?diskret"

/** Eine Seite ohne Namen: OHNE_DOMAIN oder DISKRET_DOMAIN. */
fun namenlos(domain: String?): Boolean = domain == OHNE_DOMAIN || domain == DISKRET_DOMAIN

/** Eine Beobachtung gilt höchstens so lange, wenn danach keine mehr kam (Vollbild-Video: keine Leiste zu sehen). */
const val BEOBACHTUNG_GILT_MS = 12 * 60 * 60 * 1000L

/** Beobachtungen werden so lange aufgehoben – länger, als Android seine Ereignisse hat (Nachtragen rechnet bis dahin neu). */
const val BEOBACHTUNG_BEHALTEN_MS = 12 * 24 * 60 * 60 * 1000L

/** Paket einer Beobachtung, die für alle Browser gilt: Der Dienst ging an oder aus. */
const val ALLE = "*"

fun seitenSchluessel(paket: String, domain: String) = "$SEITE$paket/$domain"

/** Paket und Domain aus einem Seiten-Schlüssel; null für alles andere. */
fun seiteVon(schluessel: String): Pair<String, String>? {
    if (!schluessel.startsWith(SEITE)) return null
    val rest = schluessel.substring(SEITE.length)
    val i = rest.indexOf('/')
    if (i <= 0 || i == rest.length - 1) return null
    return rest.substring(0, i) to rest.substring(i + 1)
}

/** Keine Seite, sondern Firefox selbst oder etwas Lokales. */
private val OHNE_SEITE = listOf(
    "about:", "moz-extension:", "file:", "content:", "javascript:", "data:", "chrome:", "view-source:", "resource:", "blob:", "intent:",
)

/**
 * Zweistufige Endungen (eine Auswahl der Public Suffix List) und Dienste, unter
 * denen jede Unterdomain eine eigene Seite ist: dort zählen drei Teile
 * („bbc.co.uk“, „finanzdienste.github.io“), sonst zwei („youtube.com“).
 */
private val DREITEILIG = setOf(
    "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "com.au", "net.au", "org.au", "edu.au", "gov.au",
    "co.nz", "org.nz", "co.jp", "ne.jp", "or.jp", "ac.jp", "co.kr", "or.kr", "com.br", "com.tr", "com.mx", "co.za", "co.in",
    "com.cn", "com.hk", "com.sg", "com.tw", "com.ar", "co.il", "co.at", "or.at", "ac.at", "gv.at",
    "github.io", "gitlab.io", "blogspot.com", "netlify.app", "vercel.app", "pages.dev", "web.app", "firebaseapp.com",
    "herokuapp.com", "appspot.com", "wordpress.com", "substack.com", "tumblr.com",
)

/**
 * Die Domain aus dem, was in der Adresszeile steht: „youtube.com“,
 * „https://m.youtube.com/watch?v=…“, „www.spiegel.de/politik“ → nur die
 * registrierbare Domain („youtube.com“, „spiegel.de“), nie Pfad, Abfrage oder
 * Anker. null: keine Seite (Suchtext, about:, leer, kein Punkt).
 */
fun domainAus(text: CharSequence?): String? {
    val roh = text?.toString()?.trim()?.lowercase() ?: return null
    if (roh.isEmpty() || roh.any { it.isWhitespace() }) return null
    if (OHNE_SEITE.any { roh.startsWith(it) }) return null
    val ohneSchema = if ("://" in roh) roh.substringAfter("://") else roh
    var host = ohneSchema.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    if (host.startsWith("[")) return null // IPv6 – keine Seite mit Namen
    host = host.substringBefore(':').trimEnd('.')
    val teile = host.split('.')
    if (teile.size < 2 || teile.any { t -> t.isEmpty() || !t.all { it.isLetterOrDigit() || it == '-' } }) return null
    if (teile.all { t -> t.all(Char::isDigit) }) return if (teile.size == 4) host else null
    val endung = teile.last()
    if (endung.length < 2 || endung.all(Char::isDigit)) return null
    val n = if (teile.size >= 3 && teile.takeLast(2).joinToString(".") in DREITEILIG) 3 else 2
    return teile.takeLast(n).joinToString(".")
}

/** Ein Knoten des Bedienungshilfe-Baums – so klein, dass Tests ihn ohne Android bauen. */
interface Knoten {
    val id: String?
    val klasse: String?
    val text: CharSequence?
    val beschreibung: CharSequence?
    val editierbar: Boolean
    val fokussiert: Boolean
    val kinder: Int

    fun kind(i: Int): Knoten?
}

/** Was die Adresszeile gerade zeigt. */
sealed interface Befund {
    data class Seite(val domain: String) : Befund

    /** Leiste da, aber keine Domain (neuer Tab, Startseite, about:, Suchbegriffe): zählt zu „Firefox“. */
    data object Keine : Befund

    /** Ein privater bzw. Inkognito-Tab: keine Domain merken – zählt zu „Firefox“. */
    data object Privat : Befund

    /** Kein Web: eine installierte Web-App oder ein Custom Tab ohne Leiste – die Zeit bleibt beim Browser. */
    data object KeinWeb : Befund

    /** Keine Leiste zu sehen (Vollbild-Video, Tab-Übersicht) oder gerade in Eingabe: das Bisherige gilt weiter. */
    data object Unbekannt : Befund
}

/**
 * Was der Dienst sich merkt: die Domain – außer Tobis Web-Apps
 * (finanzdienste.github.io), in Chrome wie in Firefox: Sie zählen über
 * Supabase unter eigenem Namen (die Bewerbungszentrale seit 21.11.2026 in
 * Firefox), sonst stünde ihre Zeit doppelt da. Nicht verbundene bleiben so
 * beim Browser.
 */
fun gemerkteDomain(paket: String, domain: String): String? = if (paket in BROWSER && domain == WEB_APPS_DOMAIN) null else domain

/**
 * Was zu einem Befund in die Beobachtungen kommt: die Domain, OHNE_DOMAIN
 * (eine Seite ohne Namen), DISKRET_DOMAIN (eine diskrete, auch ohne Namen),
 * null (kein Web, die Zeit bleibt beim Browser) – oder gar nichts (Unbekannt:
 * das Bisherige gilt weiter, Rückgabe `false`).
 */
fun zuMerken(paket: String, befund: Befund, verborgen: (String) -> Boolean = { false }): Pair<Boolean, String?> = when (befund) {
    // Eine Seite, die nie mit Namen erscheinen soll (Diskret.kt): nur „eine diskrete Seite ohne Namen“.
    is Befund.Seite -> true to gemerkteDomain(paket, befund.domain)?.let { if (verborgen(it)) DISKRET_DOMAIN else it }
    Befund.Keine, Befund.Privat -> true to OHNE_DOMAIN
    Befund.KeinWeb -> true to null
    Befund.Unbekannt -> false to null
}

/**
 * Die Adresszeile im Baum finden. Firefox – nachgelesen in den Quellen
 * (searchfox, mozilla-central, mobile/android):
 * - Die neue Leiste (Compose, android-components browser-toolbar) setzt
 *   `testTagsAsResourceId`; ihr Feld heißt ADDRESSBAR_URL_BOX (Origin.kt), und
 *   es löscht die Semantik darunter und trägt nur die Beschreibung
 *   „‹Titel› ‹Adresse›. ‹Hinweis›“ (ohne Adresse nur der Hinweis).
 * - Die alte Leiste ist ein TextView `<paket>:id/mozac_browser_toolbar_url_view`
 *   mit der Adresse als Text.
 * - Privat erkennt man am Tab-Zähler: „Offene private Tabs: 2. …“ bzw.
 *   „Private Tabs Open: 2. …“ (tabcounter strings.xml, deutsch und englisch).
 * Chrome – nachgelesen in den Quellen (chromium.googlesource.com, chrome/…):
 * - Die Adresszeile ist der UrlBar `<paket>:id/url_bar`, ein EditText, auch
 *   wenn man nicht tippt; getippt wird nur mit Fokus.
 * - Der neue Tab zeigt statt ihrer das Suchfeld `…:id/search_box_text`
 *   (fake_search_box_layout.xml): keine Seite.
 * - Inkognito zeigt das Abzeichen `…:id/location_bar_incognito_badge`
 *   (location_status.xml), Beschreibung „Inkognitomodus“ bzw. „Incognito mode“.
 * - Installierte Web-Apps (WebappActivity …) und Trusted Web Activities haben
 *   keine Adresszeile: keine Seite, nicht „das Bisherige“ (`fenster`).
 * Gesucht wird in der Breite, höchstens HOECHSTENS Knoten; den Inhalt der
 * Seite selbst (GeckoView, WebView) betritt die Suche nicht.
 */
object Leiste {
    const val COMPOSE = "ADDRESSBAR_URL_BOX"
    const val ALT = "mozac_browser_toolbar_url_view"
    const val CHROME_LEISTE = "url_bar"
    const val CHROME_NEUER_TAB = "search_box_text"
    const val CHROME_INKOGNITO = "location_bar_incognito_badge"
    private const val HOECHSTENS = 600

    /** Nach der Leiste noch so viele Knoten ansehen (der Tab-Zähler steht daneben). */
    private const val DANACH = 150

    /**
     * `fenster`: die Klasse des Fensters aus dem letzten Fensterwechsel. Ohne
     * Leiste heißt eine Web-App oder ein Custom Tab „keine Seite“, sonst
     * (Vollbild-Video, Tab-Übersicht) gilt das Bisherige weiter.
     */
    fun lesen(wurzel: Knoten, paket: String, fenster: String? = null): Befund {
        var gefunden: Befund? = null
        var privat = false
        var danach = 0
        val schlange = ArrayDeque<Knoten>()
        schlange.add(wurzel)
        var besucht = 0
        while (schlange.isNotEmpty() && besucht < HOECHSTENS) {
            val k = schlange.removeFirst()
            besucht++
            if (privatZaehler(k.beschreibung) || k.id?.endsWith(":id/$CHROME_INKOGNITO") == true) privat = true
            val hier = ausKnoten(k, paket)
            // Mehrere Leisten (voll und schmal): eine mit Seite gewinnt.
            if (hier != null && (gefunden == null || hier is Befund.Seite && gefunden !is Befund.Seite)) gefunden = hier
            if (gefunden != null && ++danach > DANACH) break
            if (webinhalt(k)) continue
            for (i in 0 until k.kinder) k.kind(i)?.let(schlange::add)
        }
        return when {
            privat -> Befund.Privat
            gefunden != null -> gefunden
            ohneLeiste(fenster) -> Befund.KeinWeb
            else -> Befund.Unbekannt
        }
    }

    /** Fenster ohne Adresszeile: installierte Web-Apps, Trusted Web Activities, Custom Tabs ohne Leiste. */
    internal fun ohneLeiste(fenster: String?): Boolean {
        val f = fenster?.lowercase() ?: return false
        return ".webapps." in f || "webapk" in f || "webapp" in f || "customtab" in f || "externalappbrowser" in f || "trustedwebactivity" in f
    }

    private fun ausKnoten(k: Knoten, paket: String): Befund? {
        val id = k.id ?: return null
        val compose = id == COMPOSE || id.endsWith(":id/$COMPOSE")
        val alt = id == "$paket:id/$ALT" || id.endsWith(":id/$ALT")
        val chrome = id.endsWith(":id/$CHROME_LEISTE")
        if (id.endsWith(":id/$CHROME_NEUER_TAB")) return Befund.Keine
        if (!compose && !alt && !chrome) return null
        // Chromes Leiste ist immer ein EditText – getippt wird dort nur mit Fokus.
        if (k.fokussiert || (k.editierbar && !chrome)) return Befund.Unbekannt
        return if (compose) ausBeschreibung(k.beschreibung) else domainAus(k.text)?.let(Befund::Seite) ?: Befund.Keine
    }

    /** „‹Titel› ‹Adresse›. ‹Hinweis›“ → die Domain der Adresse; nur der Hinweis → keine Seite. */
    internal fun ausBeschreibung(b: CharSequence?): Befund {
        val s = b?.toString()?.trim() ?: return Befund.Keine
        val ende = s.lastIndexOf(". ")
        if (ende < 0) return Befund.Keine
        val adresse = s.substring(0, ende).trim().substringAfterLast(' ')
        return domainAus(adresse)?.let(Befund::Seite) ?: Befund.Keine
    }

    /** Firefox' Tab-Zähler im privaten Modus, oder Chromes Inkognito-Abzeichen. */
    internal fun privatZaehler(b: CharSequence?): Boolean {
        val s = b?.toString()?.trim()?.lowercase() ?: return false
        if (s == "inkognitomodus" || s == "incognito mode") return true
        val privat = "offene private tabs" in s || "private tabs open" in s
        return privat && "non-private" !in s && "nicht private" !in s
    }

    private fun webinhalt(k: Knoten): Boolean =
        k.klasse == "android.webkit.WebView" || k.klasse?.contains("GeckoView") == true || k.id?.endsWith(":id/engineView") == true
}

/** Ein Stück Vordergrund einer App (ms, schon auf den Tag geschnitten). */
data class Intervall(val paket: String, val von: Long, val bis: Long)

/** Was der Dienst gesehen hat: ab `zeit` zeigt `paket` die Seite `domain` (null: keine Seite, privat, Dienst an oder aus). */
data class Beobachtung(val zeit: Long, val paket: String, val domain: String?)

/**
 * Sekunden je Seiten-Schlüssel: Jedes Vordergrund-Intervall eines Browsers
 * wird an den Beobachtungen geteilt; ein Stück zählt für die Domain, die
 * zuletzt davor gesehen wurde (nicht älter als `gilt`). Bis zur ersten
 * Beobachtung, nach „keine Seite“ und nach „Dienst an/aus“ (`ALLE`) zählt
 * nichts – das bleibt Firefox. Zusammen nie mehr als die Intervalle.
 */
fun seitenZeiten(intervalle: List<Intervall>, beobachtungen: List<Beobachtung>, gilt: Long = BEOBACHTUNG_GILT_MS): Map<String, Long> {
    val ms = HashMap<String, Long>()
    stuecke(intervalle, beobachtungen, gilt) { paket, b, dauer ->
        val domain = b?.domain
        if (domain != null) {
            val k = seitenSchluessel(paket, domain)
            ms[k] = (ms[k] ?: 0L) + dauer
        }
    }
    return ms.mapValues { it.value / 1000 }.filterValues { it > 0 }
}

/**
 * Wie die Vordergrundzeit eines Browsers aufgeteilt war (Sekunden) – für
 * Karte 4, damit „2 Std. Chrome“ (Tobi, 16.11.: „Zwei Stunden Chrome?“) nicht
 * geraten werden muss: Seiten mit Namen, Seiten ohne Namen (neuer Tab, Suche,
 * privat), kein Web (Web-Apps, Custom Tabs ohne Leiste), nicht erfasst
 * (Bedienungshilfe lief nicht, oder der Browser wurde nie erkannt).
 */
data class BrowserZeit(val seiten: Long = 0, val ohneName: Long = 0, val keinWeb: Long = 0, val nichtErfasst: Long = 0) {
    val gesamt: Long get() = seiten + ohneName + keinWeb + nichtErfasst

    operator fun plus(o: BrowserZeit) = BrowserZeit(seiten + o.seiten, ohneName + o.ohneName, keinWeb + o.keinWeb, nichtErfasst + o.nichtErfasst)
}

/** Dieselben Stücke wie seitenZeiten, nach Art des Stands je Browser-Paket (Sekunden). */
fun browserZeiten(intervalle: List<Intervall>, beobachtungen: List<Beobachtung>, gilt: Long = BEOBACHTUNG_GILT_MS): Map<String, BrowserZeit> {
    val ms = HashMap<String, LongArray>()
    for (iv in intervalle) ms.getOrPut(iv.paket) { LongArray(4) }
    stuecke(intervalle, beobachtungen, gilt) { paket, b, dauer ->
        val art = when {
            b == null || b.paket == ALLE -> 3
            b.domain == null -> 2
            namenlos(b.domain) -> 1
            else -> 0
        }
        ms.getValue(paket)[art] += dauer
    }
    return ms.mapValues { (_, a) -> BrowserZeit(a[0] / 1000, a[1] / 1000, a[2] / 1000, a[3] / 1000) }
}

/**
 * Jedes Vordergrund-Intervall eines Browsers an den Beobachtungen teilen: `je`
 * bekommt Paket, die dann geltende Beobachtung (null: keine oder älter als
 * `gilt`) und die Dauer in ms. Zusammen genau die Intervalle.
 */
private inline fun stuecke(intervalle: List<Intervall>, beobachtungen: List<Beobachtung>, gilt: Long, je: (String, Beobachtung?, Long) -> Unit) {
    val fuerAlle = beobachtungen.filter { it.paket == ALLE }
    val jePaket = HashMap<String, List<Beobachtung>>()
    for (iv in intervalle) {
        if (iv.bis <= iv.von) continue
        val liste = jePaket.getOrPut(iv.paket) { (beobachtungen.filter { it.paket == iv.paket } + fuerAlle).sortedBy { it.zeit } }
        val davor = liste.indexOfLast { it.zeit <= iv.von }
        var aktuell = liste.getOrNull(davor)?.takeIf { iv.von - it.zeit <= gilt }
        var t = iv.von
        var j = davor + 1
        while (t < iv.bis) {
            val naechste = liste.getOrNull(j)?.takeIf { it.zeit < iv.bis }
            val ende = naechste?.zeit ?: iv.bis
            if (ende > t) je(iv.paket, aktuell, ende - t)
            if (naechste == null) break
            aktuell = naechste
            t = maxOf(t, naechste.zeit)
            j++
        }
    }
}

/**
 * Die Browser um ihre Seiten kürzen: `geteilt` sind Sekunden je Schlüssel
 * (nach dem Abzug der Web-Apps von Chrome), `seiten` Sekunden je
 * Seiten-Schlüssel. Seiten unter `mindestens` bleiben im Browser; zusammen nie
 * mehr, als der Browser lief (sonst anteilig gekürzt). Die Gesamtzeit bleibt.
 */
fun seitenAbziehen(geteilt: Map<String, Long>, seiten: Map<String, Long>, mindestens: Long = SEITE_MIN_SEKUNDEN): Map<String, Long> {
    // Browser: erst sind die Web-Apps abgezogen (aufteilen); die Seiten kommen aus dem Rest.
    val gross = seiten.entries.filter { (k, s) -> s >= maxOf(1L, mindestens) && seiteVon(k) != null }
    if (gross.isEmpty()) return geteilt
    val aus = LinkedHashMap(geteilt)
    for ((paket, eintraege) in gross.groupBy { seiteVon(it.key)!!.first }) {
        val browser = aus[paket] ?: continue
        if (browser <= 0L) continue
        val werte = eintraege.map { it.value }
        val anteile = if (werte.sum() <= browser) werte else anteilig(werte, browser)
        eintraege.zip(anteile).forEach { (e, s) -> if (s > 0L) aus[e.key] = s }
        val rest = browser - anteile.sum()
        if (rest > 0L) aus[paket] = rest else aus.remove(paket)
    }
    return aus
}

/**
 * Welche Domains einzeln stehen: Chrome und Firefox zusammen ab
 * WEBSEITE_EINZELN_SEKUNDEN in `sekunden` (Seiten-Schlüssel → Sekunden),
 * dazu `immer` (die gerade angetippte Seite, damit sie in jeder Woche gleich
 * zählt).
 */
fun einzelneDomains(sekunden: Map<String, Long>, immer: String? = null, verborgen: (String) -> Boolean = { false }): Set<String> {
    val jeDomain = HashMap<String, Long>()
    for ((k, s) in sekunden) seiteVon(k)?.let { (_, d) -> if (!namenlos(d) && !verborgen(d)) jeDomain[d] = (jeDomain[d] ?: 0L) + s }
    val aus = jeDomain.filterValues { it >= WEBSEITE_EINZELN_SEKUNDEN }.keys.toMutableSet()
    val angetippt = immer?.takeIf { it.startsWith(WEBSEITE) && it != SONSTIGES && !namenlos(it.removePrefix(WEBSEITE)) }?.removePrefix(WEBSEITE)
    if (angetippt != null && !verborgen(angetippt)) aus += angetippt
    return aus
}

/**
 * Unter welchem Schlüssel ein Posten gezeigt wird: eine Seite (beide Browser
 * zusammen) oder – was nicht einzeln steht – Firefox (`firefox`, das Paket aus
 * firefoxSchluessel).
 */
fun anzeigeSchluessel(schluessel: String, einzeln: Set<String>, firefox: String = FIREFOXE.first()): String {
    val (_, d) = seiteVon(schluessel) ?: return schluessel
    return if (d in einzeln) WEBSEITE + d else firefox
}

/**
 * Das Paket, unter dem „Firefox“ steht: das Firefox, das in den Schlüsseln
 * vorkommt (selbst oder mit Seiten), sonst das gewöhnliche.
 */
fun firefoxSchluessel(schluessel: Iterable<String>): String =
    schluessel.firstOrNull { it in FIREFOXE }
        ?: schluessel.firstNotNullOfOrNull { k -> seiteVon(k)?.first?.takeIf { it in FIREFOXE } }
        ?: FIREFOXE.first()

/** Der Name des Eintrags `k`, der für Seiten auch Firefox sein kann (dann „Firefox“, wenn Firefox selbst nicht dabei ist). */
private fun gezeigterName(k: String, firefox: String, bisher: String?, p: Posten): String =
    anzeigename(k) ?: when {
        k != firefox || p.schluessel == firefox -> p.name
        else -> bisher ?: FIREFOX_NAME
    }

/** Ist das eine gezeigte Seite (oder „Sonstiges“, Wert von früher) oder ein Seiten-Posten im Verlauf? */
fun istWebseite(schluessel: String): Boolean = schluessel.startsWith(WEBSEITE) || seiteVon(schluessel) != null

/**
 * Die Liste für Widget und App: Seiten aus Chrome und Firefox zusammen, ab
 * 5 Minuten einzeln, der Rest zählt zu Firefox (seit v28, FIREFOX_NAME).
 * Chrome bleibt, wie es ist (seine Zeit ohne erkannte Seite); Apps, die nie
 * mit Namen erscheinen, stehen unter „Sonstiges“.
 */
fun webseitenZusammenfassen(
    posten: List<Posten>,
    verborgen: (String) -> Boolean = { false },
    appVerborgen: (String, String) -> Boolean = { _, _ -> false },
): List<Posten> {
    if (posten.none { seiteVon(it.schluessel) != null || appVerborgen(it.schluessel, it.name) }) return posten
    val einzeln = einzelneDomains(posten.associate { it.schluessel to it.sekunden }, verborgen = verborgen)
    val firefox = firefoxSchluessel(posten.map { it.schluessel })
    val summe = LinkedHashMap<String, Long>()
    val namen = HashMap<String, String>()
    for (p in posten) {
        // Eine App, die nie mit Namen erscheint (Dating, selbst zusammengelegt): „Sonstiges“.
        val k = if (seiteVon(p.schluessel) == null && appVerborgen(p.schluessel, p.name)) SONSTIGES else anzeigeSchluessel(p.schluessel, einzeln, firefox)
        summe[k] = (summe[k] ?: 0L) + p.sekunden
        namen[k] = gezeigterName(k, firefox, namen[k], p)
    }
    return sortiert(summe.map { (k, s) -> Posten(k, namen.getValue(k), s) })
}

/**
 * Die Beobachtungen des Dienstes – eine kleine Textdatei („zeit⇥paket⇥domain“,
 * „-“ für keine) im noBackupFilesDir: nur auf dem Handy, nie in der Sicherung,
 * nie im Netz. Geschrieben wird nur bei einem Wechsel. Dienst (eigener Faden)
 * und Rechnung greifen über eine gemeinsame Sperre zu.
 */
class Beobachtungen(context: Context) {
    private val datei = File(File(context.applicationContext.noBackupFilesDir, ORDNER), "beobachtungen.txt")

    /** Merken, wenn sie sich vom letzten Stand dieses Pakets unterscheidet; true, wenn geschrieben. */
    fun merken(b: Beobachtung): Boolean = synchronized(SPERRE) {
        val zuletzt = letzte ?: alleLesen().groupBy { it.paket }.mapValuesTo(HashMap()) { it.value.last().domain }.also { letzte = it }
        // „Dienst an/aus“ immer; sonst nur, wenn sich für dieses Paket etwas geändert hat.
        if (b.paket != ALLE && zuletzt.containsKey(b.paket) && zuletzt[b.paket] == b.domain) return false
        try {
            datei.parentFile?.mkdirs()
            datei.appendText("${b.zeit}\t${b.paket}\t${b.domain ?: "-"}\n")
        } catch (e: IOException) {
            return false
        }
        zuletzt[b.paket] = b.domain
        // „Dienst an/aus“ gilt für alle: danach zählt jeder Browser erst wieder ab der nächsten Beobachtung.
        if (b.paket == ALLE) for (k in zuletzt.keys.toList()) zuletzt[k] = null
        true
    }

    /** Alle Beobachtungen ab `von - gilt` bis `bis` (die davor braucht die Rechnung für den Anfang). */
    fun lesen(von: Long, bis: Long): List<Beobachtung> = synchronized(SPERRE) {
        alleLesen().filter { it.zeit in (von - BEOBACHTUNG_GILT_MS)..bis }
    }

    /** Die letzte erkannte Seite (mit Namen) – für „zuletzt erkannt: youtube.com in Firefox, 16:40“; `pakete`: nur diese Browser. */
    fun zuletztErkannt(pakete: Set<String> = BROWSER, verborgen: (String) -> Boolean = { false }): Beobachtung? = synchronized(SPERRE) {
        alleLesen().lastOrNull { it.domain != null && !namenlos(it.domain) && it.paket in pakete && !verborgen(it.domain) }
    }

    /** Ältere als BEOBACHTUNG_BEHALTEN_MS weg – sie gelten ohnehin nicht mehr (BEOBACHTUNG_GILT_MS). */
    fun aufraeumen(jetzt: Long) = synchronized(SPERRE) {
        val alle = alleLesen()
        val grenze = jetzt - BEOBACHTUNG_BEHALTEN_MS
        if (alle.none { it.zeit < grenze }) return@synchronized
        val behalten = alle.filter { it.zeit >= grenze }
        try {
            datei.writeText(behalten.joinToString("") { "${it.zeit}\t${it.paket}\t${it.domain ?: "-"}\n" })
        } catch (e: IOException) {
            // beim nächsten Mal
        }
    }

    fun loeschen() = synchronized(SPERRE) {
        datei.delete()
        aufschluesselung.delete()
        letzte = null
    }

    /** Die Namen der Seiten, für die `weg` gilt, durch DISKRET_DOMAIN ersetzen (Diskret.kt); die Zeiten bleiben. */
    fun namenErsetzen(weg: (String) -> Boolean) = synchronized(SPERRE) {
        val alle = alleLesen()
        if (alle.none { it.domain != null && !namenlos(it.domain) && weg(it.domain) }) return@synchronized
        val neu = alle.map { if (it.domain != null && !namenlos(it.domain) && weg(it.domain)) it.copy(domain = DISKRET_DOMAIN) else it }
        try {
            datei.writeText(neu.joinToString("") { "${it.zeit}\t${it.paket}\t${it.domain ?: "-"}\n" })
        } catch (e: IOException) {
            // beim nächsten Mal
        }
        letzte = null
    }

    private val aufschluesselung = File(datei.parentFile, "heute.json")

    /** Die Aufschlüsselung von heute je Browser-Paket merken (nur Sekunden, keine Seiten). */
    fun aufschluesselungMerken(tag: java.time.LocalDate, zeiten: Map<String, BrowserZeit>) = synchronized(SPERRE) {
        val o = org.json.JSONObject().put("tag", tag.toString())
        val p = org.json.JSONObject()
        for ((paket, z) in zeiten) p.put(paket, org.json.JSONArray(listOf(z.seiten, z.ohneName, z.keinWeb, z.nichtErfasst)))
        try {
            aufschluesselung.parentFile?.mkdirs()
            aufschluesselung.writeText(o.put("pakete", p).toString())
        } catch (e: IOException) {
            // beim nächsten Mal
        }
    }

    /** Die Aufschlüsselung, wenn sie von `tag` ist; sonst leer. */
    fun aufschluesselung(tag: java.time.LocalDate): Map<String, BrowserZeit> = synchronized(SPERRE) {
        try {
            if (!aufschluesselung.isFile) return@synchronized emptyMap()
            val o = org.json.JSONObject(aufschluesselung.readText())
            if (o.optString("tag") != tag.toString()) return@synchronized emptyMap()
            val p = o.optJSONObject("pakete") ?: return@synchronized emptyMap()
            p.keys().asSequence().associateWith { k ->
                val a = p.getJSONArray(k)
                BrowserZeit(a.optLong(0), a.optLong(1), a.optLong(2), a.optLong(3))
            }
        } catch (e: IOException) {
            emptyMap()
        } catch (e: org.json.JSONException) {
            emptyMap()
        }
    }

    private fun alleLesen(): List<Beobachtung> {
        if (!datei.isFile) return emptyList()
        return try {
            datei.readLines().mapNotNull { zeile ->
                val t = zeile.split('\t')
                val zeit = t.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val paket = t.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Beobachtung(zeit, paket, t.getOrNull(2)?.takeIf { it.isNotEmpty() && it != "-" })
            }.sortedBy { it.zeit }
        } catch (e: IOException) {
            emptyList()
        }
    }

    companion object {
        const val ORDNER = "webseiten"
        private val SPERRE = Any()

        /** Der letzte Stand je Paket, für „nur bei einem Wechsel schreiben“ (gilt für den ganzen Prozess). */
        private var letzte: HashMap<String, String?>? = null
    }
}

/** Ist die Bedienungshilfe eingeschaltet, und läuft sie? */
object WebseitenStatus {
    fun eingeschaltet(context: Context): Boolean {
        val eigene = ComponentName(context, WebseitenDienst::class.java)
        val liste = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return liste.split(':').any { ComponentName.unflattenFromString(it.trim()) == eigene }
    }

    fun laeuft(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val s = it.resolveInfo?.serviceInfo
            s != null && s.packageName == context.packageName && s.name == WebseitenDienst::class.java.name
        }
    }
}
