package de.finanzdienste.bildschirmzeit

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime

/** Ehrlich, wer fragt – für GitHub und Supabase. */
const val USER_AGENT = "Bildschirmzeit-App/1.0 (github.com/Finanzdienste/Bildschirmzeit)"

/** Kleine HTTP-Hilfe ohne Bibliotheken. Wirft IOException bei Netzfehler oder Status ≥ 300. */
internal object Netz {
    private const val WARTEN_MS = 6000

    /** In Tests an: Dann geht keine Anfrage hinaus (schon gar nicht an das echte Supabase). */
    @Volatile
    var gesperrt = false

    fun anfrage(adresse: String, methode: String, koepfe: Map<String, String>, inhalt: String? = null): String {
        if (gesperrt) throw IOException("Netz gesperrt")
        val verbindung = URL(adresse).openConnection() as HttpURLConnection
        try {
            verbindung.requestMethod = methode
            verbindung.connectTimeout = WARTEN_MS
            verbindung.readTimeout = WARTEN_MS
            verbindung.useCaches = false
            verbindung.setRequestProperty("User-Agent", USER_AGENT)
            for ((k, v) in koepfe) verbindung.setRequestProperty(k, v)
            if (inhalt != null) {
                val bytes = inhalt.toByteArray(Charsets.UTF_8)
                verbindung.doOutput = true
                verbindung.setFixedLengthStreamingMode(bytes.size)
                verbindung.outputStream.use { it.write(bytes) }
            }
            val status = verbindung.responseCode
            if (status !in 200..299) throw IOException("Fehler $status")
            return verbindung.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            verbindung.disconnect()
        }
    }

    /**
     * Lädt `adresse` nach `ziel` (für das Update: die APK von GitHub) und gibt
     * den SHA-256 des Geladenen als Hex zurück. Nur HTTPS, auch nach
     * Weiterleitungen (github.com leitet auf seinen Dateiserver weiter); mehr als
     * `hoechstens` Byte sind ein Fehler.
     */
    fun laden(adresse: String, ziel: File, hoechstens: Long): String {
        if (gesperrt) throw IOException("Netz gesperrt")
        if (!adresse.startsWith("https://")) throw IOException("Nur HTTPS")
        val verbindung = URL(adresse).openConnection() as HttpURLConnection
        try {
            verbindung.instanceFollowRedirects = true
            verbindung.connectTimeout = LADEN_WARTEN_MS
            verbindung.readTimeout = LADEN_WARTEN_MS
            verbindung.useCaches = false
            verbindung.setRequestProperty("User-Agent", USER_AGENT)
            verbindung.setRequestProperty("Accept", "application/octet-stream")
            val status = verbindung.responseCode
            if (status !in 200..299) throw IOException("Fehler $status")
            // HttpURLConnection folgt nur Weiterleitungen im selben Protokoll – trotzdem prüfen.
            if (verbindung.url.protocol != "https") throw IOException("Nur HTTPS")
            val pruefsumme = MessageDigest.getInstance("SHA-256")
            var gelesen = 0L
            verbindung.inputStream.use { ein ->
                ziel.outputStream().use { aus ->
                    val puffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = ein.read(puffer)
                        if (n < 0) break
                        gelesen += n
                        if (gelesen > hoechstens) throw IOException("Datei zu groß")
                        pruefsumme.update(puffer, 0, n)
                        aus.write(puffer, 0, n)
                    }
                }
            }
            return hex(pruefsumme.digest())
        } finally {
            verbindung.disconnect()
        }
    }

    private const val LADEN_WARTEN_MS = 20_000
}

/** SHA-256 einer Datei als Hex (klein geschrieben). */
fun sha256(datei: File): String {
    val pruefsumme = MessageDigest.getInstance("SHA-256")
    datei.inputStream().use { ein ->
        val puffer = ByteArray(64 * 1024)
        while (true) {
            val n = ein.read(puffer)
            if (n < 0) break
            pruefsumme.update(puffer, 0, n)
        }
    }
    return hex(pruefsumme.digest())
}

fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Eine Zeile aus bildschirmzeit_lesen: so lange war `app` am `tag` offen. */
data class Zeile(val app: String, val tag: String, val sekunden: Long, val aktualisiert: Long)

/**
 * Die vier Funktionen aus supabase/bildschirmzeit.sql, so wie die Widget-App
 * sie braucht. Der Schlüssel ist der öffentliche („publishable“) – kein JWT,
 * deshalb nur der Kopf „apikey“ und kein Authorization. Die Kennung steht nur
 * im Inhalt der Anfrage, nie in der Adresse oder im Log.
 */
object Supabase {
    const val URL = "https://vjyohppohmvhhxgwouti.supabase.co"
    const val SCHLUESSEL = "sb_publishable_wTUNShIKMFjCOmZzQuuN-A_vFHPkKUI"

    fun anmelden(kennung: String): Boolean = wahr(rpc("bildschirmzeit_anmelden", JSONObject().put("p_kennung", kennung)))

    /** Alle Zeilen von `von` bis `bis` (höchstens 62 Tage). */
    fun lesen(kennung: String, von: LocalDate, bis: LocalDate): List<Zeile> = zeilenAus(
        rpc(
            "bildschirmzeit_lesen",
            JSONObject().put("p_kennung", kennung).put("p_von", tagText(von)).put("p_bis", tagText(bis)),
        ),
    )

    /** Die Anrufliste (Anrufe.alsJson); false: Kennung nicht (mehr) angemeldet. */
    fun anrufeMelden(kennung: String, anrufe: JSONArray): Boolean =
        wahr(rpc("bildschirmzeit_anrufe_melden", JSONObject().put("p_kennung", kennung).put("p_anrufe", anrufe)))

    /** „Nicht mehr teilen“: alle gemeldeten Anrufe weg; false: Kennung nicht (mehr) angemeldet. */
    fun anrufeLoeschen(kennung: String): Boolean = wahr(rpc("bildschirmzeit_anrufe_loeschen", JSONObject().put("p_kennung", kennung)))

    /** Die Namen für die Nummern aus den Bewerbungen (Kontakte.kt); unbekannte Kennung: leer. Unlesbares wirft IOException. */
    fun kontakteLesen(kennung: String): List<Wunsch> {
        val text = rpc("bildschirmzeit_kontakte_lesen", JSONObject().put("p_kennung", kennung))
        try {
            JSONArray(text)
        } catch (e: JSONException) {
            throw IOException("Antwort unlesbar", e)
        }
        return Kontakte.ausJson(text)
    }

    /** true: gelöscht; false: Kennung war nicht (mehr) angemeldet. */
    fun loeschen(kennung: String): Boolean = wahr(rpc("bildschirmzeit_loeschen", JSONObject().put("p_kennung", kennung)))

    private fun rpc(name: String, inhalt: JSONObject): String = Netz.anfrage(
        "$URL/rest/v1/rpc/$name",
        "POST",
        mapOf("apikey" to SCHLUESSEL, "Content-Type" to "application/json", "Accept" to "application/json"),
        inhalt.toString(),
    )

    private fun wahr(antwort: String): Boolean = when (antwort.trim()) {
        "true" -> true
        "false" -> false
        else -> throw IOException("Antwort unklar")
    }
}

/** Die Antwort von bildschirmzeit_lesen; Unlesbares wirft IOException. */
fun zeilenAus(text: String): List<Zeile> {
    try {
        val liste = JSONArray(text)
        val aus = ArrayList<Zeile>(liste.length())
        for (i in 0 until liste.length()) {
            val o = liste.getJSONObject(i)
            val app = o.getString("app")
            if (WebApp.von(app) == null) continue
            aus += Zeile(
                app = app,
                tag = o.getString("tag"),
                sekunden = maxOf(0L, o.getLong("sekunden")),
                aktualisiert = zeitAus(o.optString("aktualisiert", "")),
            )
        }
        return aus
    } catch (e: JSONException) {
        throw IOException("Antwort unlesbar", e)
    }
}

/** timestamptz von PostgREST („2026-11-14T20:40:12.345678+00:00“) in ms; unlesbar → 0. */
internal fun zeitAus(text: String): Long = try {
    OffsetDateTime.parse(text).toInstant().toEpochMilli()
} catch (e: Exception) {
    0L
}

/** Zeilen zum Zwischenspeichern – und zurück. */
fun zeilenAlsJson(zeilen: List<Zeile>): JSONArray = JSONArray().also { a ->
    for (z in zeilen) {
        a.put(JSONObject().put("app", z.app).put("tag", z.tag).put("sekunden", z.sekunden).put("aktualisiert", z.aktualisiert))
    }
}

fun zeilenAusJson(a: JSONArray): List<Zeile> = (0 until a.length()).map { i ->
    val o = a.getJSONObject(i)
    Zeile(o.getString("app"), o.getString("tag"), o.getLong("sekunden"), o.optLong("aktualisiert"))
}

/**
 * Was die Widget-App über die Web-Apps weiß: Sekunden heute, wann jede
 * zuletzt gemeldet hat (nur wer in den letzten acht Tagen gemeldet hat, steht
 * drin – das heißt „verbunden“), ob das gerade aus dem Netz kam oder aus dem
 * Zwischenspeicher (`ohneNetz`) und wann zuletzt gelesen wurde.
 */
data class WebDaten(
    val heute: Map<String, Long>,
    val zuletzt: Map<String, Long>,
    val ohneNetz: Boolean,
    val gelesen: Long,
) {
    val verbunden: Boolean get() = zuletzt.isNotEmpty()

    companion object {
        val LEER = WebDaten(emptyMap(), emptyMap(), ohneNetz = false, gelesen = 0L)
    }
}

/** Aus den Zeilen der letzten Tage: was heute (`heute` = 'JJJJ-MM-TT') gilt. */
fun webDaten(zeilen: List<Zeile>, heute: String, ohneNetz: Boolean, gelesen: Long): WebDaten {
    val sekunden = HashMap<String, Long>()
    val zuletzt = HashMap<String, Long>()
    for (z in zeilen) {
        if (z.tag == heute) sekunden[z.app] = maxOf(sekunden[z.app] ?: 0L, z.sekunden)
        zuletzt[z.app] = maxOf(zuletzt[z.app] ?: 0L, z.aktualisiert)
    }
    return WebDaten(sekunden, zuletzt, ohneNetz, gelesen)
}
