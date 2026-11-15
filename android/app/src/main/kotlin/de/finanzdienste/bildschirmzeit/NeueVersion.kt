package de.finanzdienste.bildschirmzeit

import org.json.JSONException
import org.json.JSONObject

/**
 * Was GitHub über das neueste Release sagt: die Laufnummer (Tag „v<n>“, auch
 * der versionCode der APK) und, wenn alles stimmt, wo die APK liegt, ihr
 * SHA-256 und ihre Größe. Ohne Adresse oder Prüfsumme wird nur angezeigt, nie
 * selbst installiert.
 */
data class Freigabe(val laufnummer: Int, val adresse: String?, val sha256: String?, val groesse: Long) {
    val installierbar: Boolean get() = adresse != null && sha256 != null && groesse in 1..NeueVersion.HOECHSTENS_BYTES
}

/**
 * Das neueste Release: GitHub Actions veröffentlicht jede APK als Release
 * „v<Laufnummer>“ mit der Datei `bildschirmzeit.apk`. Installiert wird sie
 * von `Selbstupdate.kt`.
 */
object NeueVersion {
    const val API = "https://api.github.com/repos/Finanzdienste/Bildschirmzeit/releases/latest"
    const val APK = "https://github.com/Finanzdienste/Bildschirmzeit/releases/latest/download/bildschirmzeit.apk"
    val KOEPFE = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")

    /** Die APK eines Release liegt nur hier – andere Adressen in der Antwort werden nicht geladen. */
    private const val DOWNLOAD = "https://github.com/Finanzdienste/Bildschirmzeit/releases/download/"
    private const val DATEI = "bildschirmzeit.apk"

    /** Größer war die APK nie (rund 0,75 MB); mehr wird gar nicht erst geladen. */
    const val HOECHSTENS_BYTES = 50L * 1024 * 1024

    /** „v12“ → 12, sonst null. */
    fun laufnummer(tag: String): Int? = Regex("^v(\\d{1,9})$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Die Laufnummer des neuesten Releases aus der Antwort von GitHub. */
    fun ausAntwort(text: String): Int? = freigabeAus(text)?.laufnummer

    /** „sha256:<64 Hex>“ (das Feld `digest` eines Release-Assets) → Hex klein, sonst null. */
    fun sha256AusDigest(digest: String?): String? =
        Regex("^sha256:([0-9a-fA-F]{64})$").find(digest?.trim().orEmpty())?.groupValues?.get(1)?.lowercase()

    /** Die Antwort von releases/latest; unlesbar oder ohne Tag „v<n>“ → null. */
    fun freigabeAus(text: String): Freigabe? = try {
        val o = JSONObject(text)
        val n = laufnummer(o.optString("tag_name", ""))
        if (n == null || o.optBoolean("draft") || o.optBoolean("prerelease")) {
            null
        } else {
            val liste = o.optJSONArray("assets")
            var asset: JSONObject? = null
            for (i in 0 until (liste?.length() ?: 0)) {
                val a = liste!!.optJSONObject(i) ?: continue
                if (a.optString("name") == DATEI && a.optString("state", "uploaded") == "uploaded") asset = a
            }
            val adresse = asset?.optString("browser_download_url")?.takeIf { it == "${DOWNLOAD}v$n/$DATEI" }
            Freigabe(
                laufnummer = n,
                adresse = adresse,
                sha256 = sha256AusDigest(asset?.optString("digest")),
                groesse = asset?.optLong("size") ?: 0L,
            )
        }
    } catch (e: JSONException) {
        null
    }

    /** Für den Zwischenspeicher. */
    fun alsJson(f: Freigabe): String = JSONObject()
        .put("laufnummer", f.laufnummer)
        .put("adresse", f.adresse ?: JSONObject.NULL)
        .put("sha256", f.sha256 ?: JSONObject.NULL)
        .put("groesse", f.groesse)
        .toString()

    fun ausJson(text: String?): Freigabe? = try {
        if (text == null) {
            null
        } else {
            val o = JSONObject(text)
            Freigabe(
                laufnummer = o.getInt("laufnummer"),
                adresse = if (o.isNull("adresse")) null else o.getString("adresse"),
                sha256 = if (o.isNull("sha256")) null else sha256AusDigest("sha256:" + o.getString("sha256")),
                groesse = o.optLong("groesse"),
            )
        }
    } catch (e: JSONException) {
        null
    }
}
