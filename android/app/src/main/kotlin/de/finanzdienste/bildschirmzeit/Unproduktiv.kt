package de.finanzdienste.bildschirmzeit

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/*
 * Was als unproduktiv zählt – im Widget hinter der Gesamtzeit „(davon
 * unproduktiv: 2:10 Std.)“, in der Statistik unter ihr. Tobi, 26.11.: „Ich
 * will dass im widget bei der gesamtatundenzahl dahinter in Klammern steht
 * "davon unproduktiv:" oder so“.
 *
 * Von selbst (UnproduktivRegeln): Spiele, Video und soziale Netzwerke nach
 * Androids Kategorie der App (Messenger nicht), bekannte Apps und Seiten
 * (YouTube, Netflix, DAZN, Instagram, TikTok …), WhatsApp (Tobi, 27.11.:
 * „WhatsApp ist unproduktiv.“), „Sonstiges“ (Dating-Apps und selbst
 * zusammengelegte) und wie dieses die diskreten Seiten (Erotik, Dating,
 * selbst zusammengefasste; seit v35, Tobi, 27.11.: „Jo“). Nicht: „Firefox“
 * (Suche, kleine Seiten, private Tabs), Chrome, Tobis Web-Apps und alles
 * andere. Jeden Eintrag stellt Tobi
 * in der Statistik um (Unproduktiv.umstellen); das gilt vor der Vorgabe.
 * Gerechnet wird auf dem Handy, ohne Netz.
 */

/** Die Vorgaben – rein, getestet in UnproduktivTest. */
object UnproduktivRegeln {
    /** Diese Apps zählen von selbst, auch wenn sie Android keine Kategorie nennen. */
    val APPS = setOf(
        // Tobi, 27.11.: „WhatsApp ist unproduktiv.“ (WhatsApp Business bleibt ein Messenger.)
        "com.whatsapp",
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.facebook.katana",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.snapchat.android",
        "com.pinterest",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus",
        "com.dazn",
        "tv.twitch.android.app",
    )

    /** Andere Messenger zählen nie von selbst – auch wenn sie sich bei Android „sozial“ nennen. */
    val MESSENGER = setOf(
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "ch.threema.app",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.facebook.orca",
    )

    /** Seiten (die Domain, wie domainAus sie liefert), die von selbst zählen. */
    val SEITEN = setOf(
        "youtube.com",
        "netflix.com",
        "twitch.tv",
        "reddit.com",
        "instagram.com",
        "tiktok.com",
        "facebook.com",
        "x.com",
        "twitter.com",
        "dazn.com",
        "primevideo.com",
        "disneyplus.com",
        "9gag.com",
        // WhatsApp Web (web.whatsapp.com) wie die App.
        "whatsapp.com",
    )

    /** Androids Kategorien (ApplicationInfo.category), die von selbst zählen. */
    val KATEGORIEN = setOf(ApplicationInfo.CATEGORY_GAME, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_SOCIAL)

    /** Die Vorgabe für einen gezeigten Schlüssel; `kategorie` ist Androids Kategorie des Pakets (null: keine). */
    fun vorgabe(schluessel: String, kategorie: Int? = null): Boolean = when {
        schluessel == SONSTIGES -> true
        schluessel.startsWith(WEBSEITE) -> schluessel.removePrefix(WEBSEITE) in SEITEN
        schluessel in BROWSER -> false
        WebApp.von(schluessel) != null -> false
        schluessel in MESSENGER -> false
        schluessel in APPS -> true
        else -> kategorie in KATEGORIEN
    }
}

/**
 * Unter welchem gezeigten Schlüssel ein Posten aus dem Verlauf (Seiten je
 * Browser) eingestuft wird: eine Seite nach ihrer Domain – auch eine kleine,
 * die im Widget bei „Firefox“ steht –, eine ohne Namen und Firefox selbst wie
 * „Firefox“ (`firefox`), eine diskrete Seite (DISKRET_DOMAIN, steht auch bei
 * „Firefox“) und eine App, die nie mit Namen erscheint, wie „Sonstiges“.
 */
fun einstufungsSchluessel(p: Posten, firefox: String, appVerborgen: (String, String) -> Boolean): String {
    val seite = seiteVon(p.schluessel)
    return when {
        seite != null -> when (seite.second) {
            OHNE_DOMAIN -> firefox
            DISKRET_DOMAIN -> SONSTIGES
            else -> WEBSEITE + seite.second
        }
        appVerborgen(p.schluessel, p.name) -> SONSTIGES
        p.schluessel in FIREFOXE -> firefox
        else -> p.schluessel
    }
}

/** Wie viele Sekunden der `posten` (aus dem Verlauf) unproduktiv sind; `ist` sagt es für einen gezeigten Schlüssel. */
fun unproduktivSekunden(
    posten: List<Posten>,
    ist: (String) -> Boolean,
    appVerborgen: (String, String) -> Boolean = { _, _ -> false },
    firefox: String = firefoxSchluessel(posten.map { it.schluessel }),
): Long = posten.sumOf { p -> if (p.sekunden > 0L && ist(einstufungsSchluessel(p, firefox, appVerborgen))) p.sekunden else 0L }

/**
 * Tobis eigene Einstufung vor der Vorgabe: eine kleine Textdatei im
 * noBackupFilesDir („+schlüssel“ zählt, „-schlüssel“ zählt nicht) – nur auf
 * dem Handy, nie in der Sicherung, nie im Netz.
 */
class Unproduktiv(context: Context) {
    private val app = context.applicationContext
    private val datei = File(app.noBackupFilesDir, "unproduktiv.txt")

    /** Schlüssel → zählt (true) oder nicht (false); was hier fehlt, entscheidet die Vorgabe. */
    fun eigene(): Map<String, Boolean> = synchronized(SPERRE) {
        cache?.takeIf { it.first == datei }?.second ?: try {
            if (!datei.isFile) {
                emptyMap()
            } else {
                datei.readLines().map { it.trim() }.filter { it.length > 1 && (it[0] == '+' || it[0] == '-') }
                    .associate { it.substring(1) to (it[0] == '+') }
            }
        } catch (e: IOException) {
            emptyMap()
        }.also { cache = datei to it }
    }

    /** Zählt der gezeigte Eintrag `schluessel` als unproduktiv? */
    fun ist(schluessel: String): Boolean = eigene()[schluessel] ?: UnproduktivRegeln.vorgabe(schluessel, kategorie(schluessel))

    /** Andersherum zählen; entspricht das der Vorgabe, fällt die eigene Angabe weg. */
    fun umstellen(schluessel: String) = synchronized(SPERRE) {
        val neu = !ist(schluessel)
        val liste = eigene().toMutableMap()
        if (neu == UnproduktivRegeln.vorgabe(schluessel, kategorie(schluessel))) liste.remove(schluessel) else liste[schluessel] = neu
        speichern(liste)
    }

    /** „Alle Daten löschen“: wieder die Vorgaben. */
    fun alleVergessen() = synchronized(SPERRE) {
        datei.delete()
        cache = datei to emptyMap()
    }

    private fun speichern(liste: Map<String, Boolean>) {
        try {
            datei.parentFile?.mkdirs()
            datei.writeText(liste.toSortedMap().entries.joinToString("") { (k, ja) -> "${if (ja) '+' else '-'}$k\n" })
            cache = datei to liste
        } catch (e: IOException) {
            cache = null
        }
    }

    /** Androids Kategorie eines Pakets (null für Seiten, Web-Apps und Unbekanntes). */
    private fun kategorie(schluessel: String): Int? {
        if ('.' !in schluessel || ':' in schluessel) return null
        kategorien[schluessel]?.let { return it.takeIf { k -> k != KEINE } }
        val k = try {
            app.packageManager.getApplicationInfo(schluessel, 0).category
        } catch (e: PackageManager.NameNotFoundException) {
            KEINE
        }
        kategorien[schluessel] = k
        return k.takeIf { it != KEINE }
    }

    /** Je Rechnung einmal je Paket gefragt. */
    private val kategorien = ConcurrentHashMap<String, Int>()

    companion object {
        private val SPERRE = Any()

        /** Die Datei, zu der der Zwischenstand gehört (in Tests wechselt sie mit jedem Gerät). */
        private var cache: Pair<File, Map<String, Boolean>>? = null
        private const val KEINE = ApplicationInfo.CATEGORY_UNDEFINED
    }
}
