package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** Ein Anruf aus Androids Anrufliste, so wie er an Supabase geht. */
data class Anruf(val zeit: Long, val nummer: String, val name: String?, val art: String, val sekunden: Long)

/**
 * Die Anrufliste für die Bewerbungszentrale (Tobi, 23.11.2026: „lass doch
 * machen dass du meine verpassten und angenommenen Anrufe und so einsehen
 * darfst“ – alle Anrufe, nicht nur unbekannte Nummern). Erst nach Tobis
 * Freigabe in der App (Speicher.anrufeTeilen und Androids Erlaubnis
 * READ_CALL_LOG). Im 15-Minuten-Lauf höchstens alle 30 Minuten: die Anrufe der
 * letzten 14 Tage an bildschirmzeit_anrufe_melden. Ohne Netz geht nichts
 * verloren – beim nächsten Lauf wird die Liste neu gelesen und ganz gemeldet,
 * Supabase nimmt Doppeltes nicht doppelt. „Nicht mehr teilen“ löscht die
 * Anrufe bei Supabase, ohne Netz beim nächsten Lauf.
 */
object Anrufe {
    const val TAGE = 14
    const val ABSTAND_MS = 30 * 60_000L
    const val HOECHSTENS = 500

    fun erlaubt(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** Androids CallLog.Calls.TYPE als Art für Supabase; Unbekanntes → null (fällt weg). */
    fun art(typ: Int): String? = when (typ) {
        1, 7 -> "eingehend" // INCOMING_TYPE, ANSWERED_EXTERNALLY_TYPE (auf einem anderen Gerät angenommen)
        2 -> "ausgehend" // OUTGOING_TYPE
        3 -> "verpasst" // MISSED_TYPE
        4 -> "mailbox" // VOICEMAIL_TYPE
        5 -> "abgelehnt" // REJECTED_TYPE
        6 -> "blockiert" // BLOCKED_TYPE
        else -> null
    }

    /** Nummer ohne Leer- und Trennzeichen; unterdrückt oder leer → „unbekannt“; Unbrauchbares → null. */
    fun nummer(roh: String?): String? {
        // Androids Platzhalter für unterdrückte, unbekannte und Münztelefon-Nummern: -1, -2, -3
        if (roh == null || roh.trim() in setOf("", "-1", "-2", "-3")) return "unbekannt"
        val n = roh.filter { it.isDigit() || it in "+*#" }
        if (n.isEmpty()) return "unbekannt"
        if (n.length > 33 || n.indexOf('+', 1) >= 0) return null
        return n
    }

    /** Wie es bildschirmzeit_anrufe_melden erwartet (siehe supabase/bildschirmzeit.sql). */
    fun alsJson(liste: List<Anruf>): JSONArray = JSONArray().also { a ->
        for (x in liste.take(HOECHSTENS)) {
            a.put(
                JSONObject()
                    .put("zeit", Instant.ofEpochMilli(x.zeit).toString())
                    .put("nummer", x.nummer)
                    .put("name", x.name ?: JSONObject.NULL)
                    .put("art", x.art)
                    .put("sekunden", x.sekunden),
            )
        }
    }

    /** Fällig, wenn noch nie gemeldet, seit 30 Minuten nicht oder die Uhr zurückgestellt wurde. */
    fun faellig(jetzt: Long, zuletzt: Long): Boolean = zuletzt <= 0L || jetzt < zuletzt || jetzt - zuletzt >= ABSTAND_MS

    /** Die Anrufe der letzten 14 Tage (oder `tage`), neueste zuerst; ohne Erlaubnis leer. */
    fun lesen(context: Context, jetzt: Long, tage: Int = TAGE): List<Anruf> {
        if (!erlaubt(context)) return emptyList()
        val ab = jetzt - tage * 86_400_000L
        val aus = ArrayList<Anruf>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(ab.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && aus.size < HOECHSTENS) {
                    val art = art(c.getInt(3)) ?: continue
                    val nummer = nummer(c.getString(1)) ?: continue
                    val name = c.getString(2)?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
                    aus += Anruf(c.getLong(0), nummer, name, art, maxOf(0L, c.getLong(4)))
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return aus
    }

    /**
     * Im Lauf: ausstehendes Löschen nachholen, sonst – wenn freigegeben und
     * fällig – melden. Ist die Kennung bei Supabase noch nicht angemeldet,
     * erst anmelden. Netzfehler: still, beim nächsten Lauf. true: gemeldet.
     */
    fun imTakt(context: Context, jetzt: Long = System.currentTimeMillis()): Boolean =
        Aktualisierung.allein { melden(context, jetzt) } // nie mitten in „Alle Daten löschen“ (sonst meldete es die alte Kennung neu an)

    private fun melden(context: Context, jetzt: Long): Boolean {
        val speicher = Speicher(context)
        try {
            if (speicher.anrufeLoeschenOffen) {
                Supabase.anrufeLoeschen(speicher.kennung)
                speicher.anrufeLoeschenOffen = false
            }
            if (!speicher.anrufeTeilen || !erlaubt(context) || !faellig(jetzt, speicher.anrufeGemeldet)) return false
            val json = alsJson(lesen(context, jetzt))
            var ok = Supabase.anrufeMelden(speicher.kennung, json)
            if (!ok && Supabase.anmelden(speicher.kennung)) {
                speicher.angemeldet = true
                ok = Supabase.anrufeMelden(speicher.kennung, json)
            }
            if (ok) speicher.anrufeGemeldet = jetzt
            return ok
        } catch (e: IOException) {
            Log.i(LOG, "Anrufe: gerade kein Netz, beim nächsten Lauf")
            return false
        }
    }

    /** „Nicht mehr teilen“: aus, und die Anrufe bei Supabase löschen – ohne Netz beim nächsten Lauf. Nie auf dem Hauptfaden. */
    fun nichtMehrTeilen(context: Context): Unit = Aktualisierung.allein {
        val speicher = Speicher(context)
        speicher.anrufeTeilen = false
        speicher.anrufeGemeldet = 0L
        speicher.anrufeLoeschenOffen = true
        try {
            Supabase.anrufeLoeschen(speicher.kennung)
            speicher.anrufeLoeschenOffen = false
        } catch (e: IOException) {
            Log.i(LOG, "Anrufe: Löschen ohne Netz vorgemerkt")
        }
    }
}
