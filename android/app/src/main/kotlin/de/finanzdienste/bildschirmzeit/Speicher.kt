package de.finanzdienste.bildschirmzeit

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * SharedPreferences der App. Die Kennung ist geheim: Wer sie kennt, kann
 * die Minuten lesen und löschen. Sie steht nur hier und im Verbinden-Link –
 * nie im Log.
 */
class Speicher(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("bildschirmzeit", Context.MODE_PRIVATE)

    /** Die Kennung dieses Geräts; beim ersten Mal erzeugt. */
    val kennung: String
        get() = synchronized(SPERRE) {
            p.getString(KENNUNG, null)?.takeIf { KENNUNG_REGEL.matches(it) } ?: neueKennungSetzen()
        }

    /**
     * Eine neue Kennung (nach „Alle Daten löschen“); muss erst noch angemeldet
     * werden. commit statt apply: Gleich danach liest ein anderer Faden sie.
     */
    @SuppressLint("ApplySharedPref")
    fun neueKennungSetzen(): String = synchronized(SPERRE) {
        val k = neueKennung()
        p.edit(commit = true) { putString(KENNUNG, k).putBoolean(ANGEMELDET, false).remove(ZWISCHENSPEICHER) }
        k
    }

    /** Hat Supabase die Kennung bestätigt (bildschirmzeit_anmelden)? */
    var angemeldet: Boolean
        get() = p.getBoolean(ANGEMELDET, false)
        set(wert) {
            p.edit { putBoolean(ANGEMELDET, wert) }
        }

    /** Was zuletzt von Supabase kam – für die Zeit ohne Netz. */
    fun zwischenspeichern(gelesen: Long, zeilen: List<Zeile>) {
        val o = JSONObject().put("gelesen", gelesen).put("zeilen", zeilenAlsJson(zeilen))
        p.edit { putString(ZWISCHENSPEICHER, o.toString()) }
    }

    /** (gelesen, zeilen) oder null. */
    fun zwischengespeichert(): Pair<Long, List<Zeile>>? {
        val text = p.getString(ZWISCHENSPEICHER, null) ?: return null
        return try {
            val o = JSONObject(text)
            o.getLong("gelesen") to zeilenAusJson(o.optJSONArray("zeilen") ?: JSONArray())
        } catch (e: JSONException) {
            null
        }
    }

    private companion object {
        const val KENNUNG = "kennung"
        const val ANGEMELDET = "angemeldet"
        const val ZWISCHENSPEICHER = "web"
        val SPERRE = Any()
    }
}
