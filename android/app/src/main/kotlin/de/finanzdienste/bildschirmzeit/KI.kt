package de.finanzdienste.bildschirmzeit

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Claude mit Websuche, für das Nachschlagen unbekannter Anrufer (Anrufer.kt).
 * Mit Tobis eigenem Claude-Schlüssel aus der Bewerbungszentrale (sie gibt ihn
 * über bildschirmzeit://ki#… weiter, nur auf dem Handy). Rohes HTTP über
 * Netz.anfrage wie der Rest der App – keine Bibliothek, kein SDK. Modell,
 * Version und Rückfall wie in der Bewerbungszentrale (js/plattform.js,
 * KI_MODELL.anthropic). Der Schlüssel steht nur im Kopf der Anfrage, nie im Log.
 */
object KI {
    const val ADRESSE = "https://api.anthropic.com/v1/messages"
    const val MODELL = "claude-opus-5-5"
    private const val WARTEN_MS = 90_000
    private const val RUNDEN = 3
    private val SCHLUESSEL = Regex("^sk-ant-[A-Za-z0-9_-]{20,250}$")

    fun gueltig(schluessel: String?): Boolean = schluessel != null && SCHLUESSEL.matches(schluessel)

    /** Die Anfrage: Opus mit wenig Aufwand (es soll schnell gehen), höchstens drei Suchen, Rückfall bei einer Ablehnung. */
    fun anfrage(nachrichten: JSONArray): JSONObject = JSONObject()
        .put("model", MODELL)
        .put("max_tokens", 4000)
        .put("output_config", JSONObject().put("effort", "low"))
        .put("fallbacks", "default")
        .put(
            "tools",
            JSONArray().put(
                JSONObject().put("type", "web_search_20260209").put("name", "web_search").put("max_uses", 3)
                    .put("user_location", JSONObject().put("type", "approximate").put("country", "DE")),
            ),
        )
        .put("messages", nachrichten)

    /**
     * Fragt Claude und gibt den Text der Antwort zurück (leer bei einer
     * Ablehnung). Unterbricht der Server den Zug (pause_turn, etwa bei langer
     * Suche), geht es mit der bisherigen Antwort weiter, höchstens dreimal.
     * Netzfehler und Status ≥ 300 werfen IOException (HttpFehler).
     */
    fun fragen(schluessel: String, frage: String): String {
        val nachrichten = JSONArray().put(JSONObject().put("role", "user").put("content", frage))
        repeat(RUNDEN) {
            val antwort = try {
                JSONObject(
                    Netz.anfrage(
                        ADRESSE,
                        "POST",
                        mapOf(
                            "x-api-key" to schluessel,
                            "anthropic-version" to "2026-10-21",
                            "anthropic-beta" to "server-side-fallback-2026-07-01",
                            "content-type" to "application/json",
                        ),
                        anfrage(nachrichten).toString(),
                        WARTEN_MS,
                    ),
                )
            } catch (e: JSONException) {
                throw IOException("Antwort unlesbar", e)
            }
            when (antwort.optString("stop_reason")) {
                "pause_turn" -> nachrichten.put(JSONObject().put("role", "assistant").put("content", antwort.optJSONArray("content") ?: JSONArray()))
                "refusal" -> return ""
                else -> return textAus(antwort)
            }
        }
        throw IOException("Claude wurde nicht fertig")
    }

    /** Alle Textblöcke der Antwort hintereinander; Suchaufrufe und -ergebnisse (server_tool_use, web_search_tool_result) fallen weg. */
    fun textAus(antwort: JSONObject): String {
        val inhalt = antwort.optJSONArray("content") ?: return ""
        val aus = StringBuilder()
        for (i in 0 until inhalt.length()) {
            val b = inhalt.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") aus.append(b.optString("text"))
        }
        return aus.toString().trim()
    }
}
