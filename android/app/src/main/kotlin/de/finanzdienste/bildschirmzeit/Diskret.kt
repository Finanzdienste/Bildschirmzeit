package de.finanzdienste.bildschirmzeit

import android.content.Context
import java.io.File
import java.io.IOException

/*
 * Diskret: Manche Seiten sollen nie mit Namen erscheinen – das Widget steht auf
 * dem Startbildschirm, den jeder sieht. Erotikseiten erkennt die App selbst
 * (Erotik), jede andere legt Tobi in der Statistik mit einem Tipp dazu
 * (Verborgen). Solche Seiten zählen immer zu „Sonstige Webseiten“: Der Dienst
 * merkt sich für sie nur „eine Seite ohne Namen“ (OHNE_DOMAIN), und was schon
 * mit Namen gespeichert war, wird umgeschrieben (namenEntfernen) – im Verlauf
 * und in den Beobachtungen auf dem Handy steht der Name danach nicht mehr.
 * Die Zeiten bleiben, wie sie sind. Rein getestet in DiskretTest.
 */

/** Erotikseiten am Namen erkennen – ohne Netz, ohne Liste aus dem Netz. */
object Erotik {
    /**
     * Erhöhen, wenn sich die Erkennung ändert: Dann schreibt die nächste
     * Rechnung einmal alles Gespeicherte um (Speicher.erotikStand).
     */
    const val STAND = 1

    /** Wörter im Namen (ohne die Endung). */
    private val WOERTER = listOf(
        "porn", "xxx", "hentai", "nsfw", "xvideo", "xnxx", "xhamster", "redtube", "youjizz", "spankbang",
        "spankwire", "chaturbate", "stripchat", "bongacams", "livejasmin", "camsoda", "myfreecams", "onlyfans",
        "fansly", "brazzers", "bangbros", "eporner", "motherless", "rule34", "milf", "bdsm", "fetish", "erotik",
        "erotic", "sexcam", "camgirl", "shemale",
    )

    /** Endungen nur für Erwachsene. */
    private val ENDUNGEN = setOf("xxx", "porn", "adult", "sex", "sexy")

    /** Bekannte Seiten ohne ein solches Wort im Namen. */
    private val SEITEN = setOf(
        "beeg.com", "hclips.com", "tnaflix.com", "drtuber.com", "imagefap.com", "literotica.com", "cam4.com",
        "manyvids.com", "clips4sale.com", "f95zone.to", "erome.com", "iwara.tv", "gelbooru.com", "thumbzilla.com",
        "tubegalore.com", "xozilla.com", "hdzog.com", "anysex.com", "daftsex.com", "sxyprn.com", "freeones.com",
        "slutload.com", "tube8.com", "4tube.com", "fuq.com", "perfectgirls.net", "keezmovies.com", "extremetube.com",
        "nuvid.com", "missav.com", "jable.tv", "nhentai.net", "fapello.com", "coomer.su", "kemono.su",
    )

    /** Ist `domain` (registrierbar, wie domainAus sie liefert) eine Erotikseite? */
    fun ist(domain: String): Boolean {
        val d = domain.lowercase()
        if (d == OHNE_DOMAIN) return false
        if (d in SEITEN || d.substringAfterLast('.') in ENDUNGEN) return true
        val name = d.substringBeforeLast('.')
        if (WOERTER.any { it in name }) return true
        // „sex“ nur am Anfang eines Teils (sexshop, sex-…), nicht in sussex oder essex.
        return name.split('.', '-').any { it.startsWith("sex") }
    }
}

/**
 * Die Seiten, die Tobi selbst zusammengelegt hat – eine kleine Textdatei im
 * noBackupFilesDir (nie in der Sicherung, nie im Netz), je Zeile eine Domain.
 * `ist` fragt dazu die Erkennung.
 */
class Verborgen(context: Context) {
    private val datei = File(File(context.applicationContext.noBackupFilesDir, Beobachtungen.ORDNER), "verborgen.txt")

    fun eigene(): Set<String> = synchronized(SPERRE) {
        cache ?: try {
            if (datei.isFile) datei.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
        } catch (e: IOException) {
            emptySet()
        }.also { cache = it }
    }

    /** Nie mit Namen zeigen: eine Erotikseite oder von Tobi zusammengelegt. */
    fun ist(domain: String): Boolean = Erotik.ist(domain) || domain in eigene()

    fun hinzufuegen(domain: String) = synchronized(SPERRE) {
        val neu = eigene() + domain
        speichern(neu)
    }

    /** „Wieder einzeln zeigen“: Ab jetzt stehen sie wieder mit Namen da (was schon zusammengelegt war, bleibt es). */
    fun alleZeigen() = synchronized(SPERRE) {
        datei.delete()
        cache = emptySet()
    }

    private fun speichern(domains: Set<String>) {
        try {
            datei.parentFile?.mkdirs()
            datei.writeText(domains.sorted().joinToString("") { "$it\n" })
            cache = domains
        } catch (e: IOException) {
            cache = null
        }
    }

    companion object {
        private val SPERRE = Any()
        private var cache: Set<String>? = null
    }
}

/**
 * Ein Tag ohne die Namen der Seiten, für die `weg` gilt: Ihre Posten
 * („seite:<paket>/<domain>“) gehen in „seite:<paket>/?“ auf (Summe, Name wie
 * „Sonstige Webseiten“). null: nichts zu tun.
 */
fun ohneNamen(tag: Tag, weg: (String) -> Boolean): Tag? {
    if (tag.posten.none { p -> seiteVon(p.schluessel)?.let { (_, d) -> d != OHNE_DOMAIN && weg(d) } == true }) return null
    val summe = LinkedHashMap<String, Long>()
    val namen = HashMap<String, String>()
    for (p in tag.posten) {
        val seite = seiteVon(p.schluessel)
        val k = if (seite != null && seite.second != OHNE_DOMAIN && weg(seite.second)) seitenSchluessel(seite.first, OHNE_DOMAIN) else p.schluessel
        summe[k] = (summe[k] ?: 0L) + p.sekunden
        namen[k] = if (k == p.schluessel) p.name else SONSTIGE_NAME
    }
    return tag.copy(posten = sortiert(summe.map { (k, s) -> Posten(k, namen.getValue(k), s) }))
}

/**
 * Die Namen der Seiten, für die `weg` gilt, aus allem Gespeicherten löschen:
 * Verlauf (jeder Tag) und Beobachtungen werden umgeschrieben, die Zeiten
 * bleiben. Unter Aktualisierung.allein aufrufen (die Rechnung schreibt dieselben Dateien).
 */
fun namenEntfernen(context: Context, weg: (String) -> Boolean) {
    val verlauf = Verlauf(context)
    for (d in verlauf.tage()) {
        val tag = verlauf.lesen(d) ?: continue
        ohneNamen(tag, weg)?.let { verlauf.schreiben(it) }
    }
    Beobachtungen(context).namenErsetzen(weg)
}
