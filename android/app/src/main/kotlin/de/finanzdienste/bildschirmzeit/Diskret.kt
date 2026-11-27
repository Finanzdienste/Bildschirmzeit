package de.finanzdienste.bildschirmzeit

import android.content.Context
import java.io.File
import java.io.IOException

/*
 * Diskret: Manches soll nie mit Namen erscheinen – das Widget steht auf dem
 * Startbildschirm, den jeder sieht. Erotikseiten (Erotik) sowie Dating-Apps
 * und -Seiten (Partnersuche) erkennt die App selbst, alles andere legt Tobi in
 * der Statistik mit einem Tipp dazu (Verborgen). Seiten zählen dann immer zu
 * „Firefox“, Apps stehen unter „Sonstiges“ (seit v28; vorher beides
 * „Sonstiges“). Bei Seiten zudem: Der Dienst
 * merkt sich für sie nur „eine diskrete Seite ohne Namen“ (DISKRET_DOMAIN,
 * bis v34 OHNE_DOMAIN), und was schon mit Namen gespeichert war, wird
 * umgeschrieben (namenEntfernen) – im Verlauf und in den Beobachtungen auf
 * dem Handy steht der Name danach nicht mehr. Die Zeiten bleiben, wie sie
 * sind. Seit v35 zählen diskrete Seiten wie „Sonstiges“ als unproduktiv
 * (Tobi, 27.11.: „Jo“). Rein getestet in DiskretTest.
 */

/** Erotikseiten am Namen erkennen – ohne Netz, ohne Liste aus dem Netz. */
object Erotik {
    /** Wörter im Namen (ohne die Endung). */
    private val WOERTER = listOf(
        "porn", "xxx", "hentai", "nsfw", "xvideo", "xnxx", "xhamster", "redtube", "youjizz", "spankbang",
        "spankwire", "chaturbate", "stripchat", "bongacams", "livejasmin", "camsoda", "myfreecams", "onlyfans",
        "fansly", "brazzers", "bangbros", "eporner", "motherless", "rule34", "milf", "bdsm", "fetish", "erotik",
        "erotic", "sexcam", "camgirl", "shemale",
    )

    /** Endungen nur für Erwachsene. */
    private val ENDUNGEN = setOf("xxx", "porn", "adult", "sex", "sexy")

    /**
     * Bekannte Seiten ohne ein solches Wort im Namen (Tube-Seiten, Fan- und
     * Cam-Plattformen, Seitensprung-Seiten) – nur als Prüfsumme (die ersten 16
     * Zeichen von SHA-256 der Domain), damit das öffentliche Repo keine Liste
     * mit Namen trägt. Neue Seite: `pruefsumme("beispiel.com")` dazuschreiben
     * und STAND erhöhen.
     */
    private val SEITEN = setOf(
        "9c3ac886739d8082", "260aa6ca64eb5407", "1b895868bf7cb1d2", "ecc707a41c541bb6", "8d64b040262512ed", "d429ec44e062a745",
        "40aeed2dad5f8f2d", "b771b3e4a35d237e", "6776e57d2059132e", "7e2af09f443c5c2b", "527d9db31ee56438", "a506af4bb20d68cd",
        "f6ca1c7d5d00a2a3", "82560809dafa90a2", "264a37010653f90c", "381ab09d28e2d268", "3ba0f5403736f8fb", "06550c73a04dca5e",
        "2cd75121906a2b17", "98a9bcd7e106fae3", "2b77217f7beb3764", "d21277a6953c6987", "7beb528080886e3d", "f9e512ce074feef5",
        "62c699f7b4d4c889", "1baf7544f7f5c43d", "3aaed023032d8b53", "acd9bb784add8fcb", "953a8ca0071939ee", "831801e7ca06141c",
        "d448bc3825c3dfcd", "68a66706b2db959d", "c97f5805b6e1d747", "7dc21e276108d299", "da5a53c69372de8b", "af57333188dd25a9",
        "59a75130b4bb08de", "6dfb4036a6a83879", "4ca446d56a4319d5", "981c3924647b17f4", "31806f42377a846d", "fd2d75a2dce18e91",
        "6a1f47d324ea17a7", "1c56e55b337c6064", "b2f6a8ef98a46c0b", "7928684959a21613", "d4073c78ab5cceb2", "ed407093ff64d574",
        "9b91630fb631fa1c", "4cdf6d28150111fd", "1ffe2e06af838588", "220ed8309791ed71", "2be8e544f4f7d23b", "d6984447174cb432",
        "2495cd3afbcabda6", "c9f3b6ccbcb4166d", "93b2b083dc94865c", "dae39b22e80aa433", "3c34ce0170c66e98", "19bb9267327258fa",
        "fbecd907d41b5400", "4a0a50438c167f37", "704dafd4cf1dac68", "f15800988caf887a", "00f97025867646ce", "356fff710e767de4",
        "743855d459e9a074", "81002de4697c0340", "ee02ddcee91d9dac", "91a8de3e163ad291", "54d054d293f880a8", "35244c9744cdd46c",
        "3da232af99726005", "ea4debe96a9bc6fa", "58ec9415a23b3ce5", "0abffbba8c171d88", "7d6383638dbd4b7c", "d00ff3d4a0647352",
        "1cb02f28994d105f", "b336d7fa435d8a14", "8538269111e156c0", "bec917b4f8d1a0e6", "c57b9b9801799fe5", "90011efd20b4de6e",
        "25c56591f1aa8c07", "a4d12d958426eeaf", "16deab9f6d9d12c2", "3bd0ccc0bcd5e223", "78e2fc0b3ee81e7d", "f7fee3f688021740",
        "455bb3f75f44645d", "196ac981d1c02ccb", "3832238395a1bea1", "ca3d16482a2d51c2", "8aedfb4d2198b107", "8a960d42313e7745",
        "e2736d4efe32cbe9", "a8cdccbce541016c", "a4ce8828a9c532ba", "cac916a6511c606c", "09edd53bb1174635", "2637b2beb0c08952",
        "f42be587bfdfc463", "b3422148e58557bc", "5c440ada8878cdab", "8ae2e2727b00a067",
    )

    private val gesehen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Die ersten 16 Zeichen von SHA-256 – für SEITEN. */
    internal fun pruefsumme(domain: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(domain.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Ist `domain` (registrierbar, wie domainAus sie liefert) eine Erotikseite? */
    fun ist(domain: String): Boolean {
        val d = domain.lowercase()
        if (namenlos(d)) return false
        return gesehen.getOrPut(d) { pruefen(d) }
    }

    private fun pruefen(d: String): Boolean {
        if (d.substringAfterLast('.') in ENDUNGEN || pruefsumme(d) in SEITEN) return true
        val name = d.substringBeforeLast('.')
        if (WOERTER.any { it in name }) return true
        // „sex“ nur am Anfang eines Teils (sexshop, sex-…), nicht in sussex oder essex.
        return name.split('.', '-').any { it.startsWith("sex") }
    }
}

/**
 * Partnersuche: Dating-Apps und -Seiten erscheinen nie mit Namen (Tobi,
 * 17.11.). Apps am Paket oder am Namen der App, Seiten an der Domain.
 */
object Partnersuche {
    private val PAKETE = setOf(
        "com.tinder", "com.bumble.app", "co.hinge.app", "com.okcupid.okcupid", "com.badoo.mobile", "net.lovoo.android",
        "com.grindrapp.android", "com.ftw_and_co.happn", "co.feeld", "com.pof.android", "com.match.android.matchmobile",
        "com.hily.app", "com.jaumo", "com.zoosk.zoosk", "com.coffeemeetsbagel", "com.p1.mobile.putong", "com.weareher.her",
        "enterprises.dating.boo",
    )

    /** Namen von Apps, genau so (klein geschrieben). */
    private val NAMEN = setOf(
        "tinder", "bumble", "hinge", "okcupid", "badoo", "lovoo", "grindr", "parship", "elitepartner", "lovescout24",
        "happn", "feeld", "hily", "jaumo", "zoosk", "joyclub", "once", "boo", "her", "raya", "tantan", "taimi", "meetic",
        "pof", "plenty of fish", "coffee meets bagel", "match", "edarling", "finya", "lesarion", "romeo", "planetromeo",
        "eharmony", "be2", "the inner circle", "poppen", "c-date",
    )

    /** Wörter in Paket oder Name. */
    private val WOERTER = listOf("dating", "partnersuche", "singleb", "flirt")

    private val SEITEN = setOf(
        "tinder.com", "gotinder.com", "bumble.com", "hinge.co", "okcupid.com", "badoo.com", "lovoo.com", "parship.de",
        "elitepartner.de", "lovescout24.de", "finya.de", "neu.de", "match.com", "pof.com", "zoosk.com", "grindr.com",
        "happn.com", "feeld.co", "hily.com", "jaumo.com", "meetic.com", "meetic.de", "lesarion.de", "planetromeo.com",
        "romeo.com", "boo.world", "coffeemeetsbagel.com", "theinnercircle.co", "eharmony.com", "eharmony.de",
        "edarling.de", "be2.de", "academicsingles.de",
    )

    fun app(paket: String, name: String): Boolean {
        val p = paket.lowercase()
        val n = name.trim().lowercase()
        return p in PAKETE || n in NAMEN || WOERTER.any { it in p || it in n }
    }

    fun seite(domain: String): Boolean {
        val d = domain.lowercase()
        return d in SEITEN || WOERTER.any { it in d.substringBeforeLast('.') }
    }
}

/**
 * Erhöhen, wenn sich die Erkennung (Erotik, Partnersuche) ändert: Dann schreibt
 * die nächste Rechnung einmal alles Gespeicherte um (Speicher.erotikStand).
 */
const val DISKRET_STAND = 2

/** Seiten, die die App selbst nie mit Namen zeigt. */
fun nieMitNamen(domain: String): Boolean = Erotik.ist(domain) || Partnersuche.seite(domain)

/**
 * Was Tobi selbst zusammengelegt hat – eine kleine Textdatei im
 * noBackupFilesDir (nie in der Sicherung, nie im Netz), je Zeile eine Domain
 * oder „app:<paket>“. `ist` und `app` fragen dazu die Erkennung.
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

    /** Eine Seite nie mit Namen zeigen: Erotik, Partnersuche oder von Tobi zusammengelegt. */
    fun ist(domain: String): Boolean = nieMitNamen(domain) || domain in eigene()

    /** Eine App nie mit Namen zeigen: Dating-App oder von Tobi zusammengelegt. */
    fun app(paket: String, name: String): Boolean = Partnersuche.app(paket, name) || APP + paket in eigene()

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
        /** Vor dem Paketnamen in der Liste: eine App, keine Seite. */
        const val APP = "app:"
        private val SPERRE = Any()
        private var cache: Set<String>? = null
    }
}

/**
 * Ein Tag ohne die Namen der Seiten, für die `weg` gilt: Ihre Posten
 * („seite:<paket>/<domain>“) gehen in „seite:<paket>/?diskret“ auf (Summe,
 * Name „Firefox“, dorthin zählen sie; unproduktiv wie „Sonstiges“). null:
 * nichts zu tun.
 */
fun ohneNamen(tag: Tag, weg: (String) -> Boolean): Tag? {
    if (tag.posten.none { p -> seiteVon(p.schluessel)?.let { (_, d) -> !namenlos(d) && weg(d) } == true }) return null
    val summe = LinkedHashMap<String, Long>()
    val namen = HashMap<String, String>()
    for (p in tag.posten) {
        val seite = seiteVon(p.schluessel)
        val k = if (seite != null && !namenlos(seite.second) && weg(seite.second)) seitenSchluessel(seite.first, DISKRET_DOMAIN) else p.schluessel
        summe[k] = (summe[k] ?: 0L) + p.sekunden
        namen[k] = if (k == p.schluessel) p.name else FIREFOX_NAME
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
