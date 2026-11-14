package de.finanzdienste.bildschirmzeit

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * „Neue Version verfügbar“: GitHub Actions veröffentlicht jede APK als Release
 * „v<Laufnummer>“ – die Laufnummer ist auch der versionCode der APK.
 */
object NeueVersion {
    const val API = "https://api.github.com/repos/Finanzdienste/Bildschirmzeit/releases/latest"
    const val APK = "https://github.com/Finanzdienste/Bildschirmzeit/releases/latest/download/bildschirmzeit.apk"

    /** „v12“ → 12, sonst null. */
    fun laufnummer(tag: String): Int? = Regex("^v(\\d{1,9})$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Die Laufnummer des neuesten Releases aus der Antwort von GitHub. */
    fun ausAntwort(text: String): Int? = try {
        laufnummer(JSONObject(text).optString("tag_name", ""))
    } catch (e: JSONException) {
        null
    }

    /** Die Laufnummer einer neueren Fassung als `eigene`, sonst null (auch ohne Netz). */
    fun pruefen(eigene: Int): Int? = try {
        val text = Netz.anfrage(API, "GET", mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"))
        ausAntwort(text)?.takeIf { it > eigene }
    } catch (e: IOException) {
        null
    }
}
