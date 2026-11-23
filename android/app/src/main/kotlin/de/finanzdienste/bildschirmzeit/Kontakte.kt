package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Ein Name für eine Nummer aus den Bewerbungen, so wie er von Supabase kommt. */
data class Wunsch(val nummer: String, val name: String)

/** Ein Kontakt, den die App umbenannt hat: sein alter Name (leer: hatte keinen) und die Nummer, für die er umbenannt wurde. */
data class Umbenannt(val alt: String, val nummer: String)

/**
 * Kontakte für die Nummern aus den Bewerbungen (Tobi, 23.11.2026: „Du kannst
 * meine Telefon Kontakte auch umbennenen“). Die Bewerbungszentrale legt die
 * Namen bei Supabase ab (bildschirmzeit_kontakte_setzen, etwa „Erika Muster
 * (Beispiel GmbH)“), die App holt sie im 15-Minuten-Lauf höchstens alle 30
 * Minuten und merkt sich die Liste. Erst nach Tobis Freigabe in der App
 * (Speicher.kontakteBenennen und Androids Erlaubnis für die Kontakte).
 *
 * Angewandt wird nur, wenn sich die Liste geändert hat – was Tobi danach von
 * Hand umbenennt, bleibt, bis sich der Name in der Liste ändert. Ein Kontakt mit
 * der Nummer bekommt den neuen Namen (der alte wird gemerkt), fehlt er, legt
 * die App einen im Telefonspeicher an. Fällt eine Nummer aus der Liste, löscht
 * sie den angelegten Kontakt wieder und gibt einem umbenannten seinen alten
 * Namen zurück; „Nicht mehr benennen“ tut das für alle. Ohne Netz bleibt es
 * beim zuletzt geholten Stand, nichts geht verloren.
 */
object Kontakte {
    const val ABSTAND_MS = 30 * 60_000L
    const val HOECHSTENS = 500
    private val NUMMER = Regex("^\\+?[0-9]{3,32}$")

    fun erlaubt(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Zum Vergleichen: nur Ziffern, +49 und 0049 → 0, andere Länder + → 00 („+49 40 1234-56“ = „040123456“). */
    fun vergleich(roh: String?): String {
        val d = roh.orEmpty().filter { it.isDigit() || it == '+' }
        val n = when {
            d.startsWith("+49") -> "0" + d.drop(3)
            d.startsWith("0049") -> "0" + d.drop(4)
            d.startsWith("+") -> "00" + d.drop(1)
            else -> d
        }
        return n.filter { it.isDigit() }
    }

    /** Die Liste aus Supabase oder dem Speicher; Unpassendes fällt still weg, doppelte Nummer: der erste Name. */
    fun ausJson(text: String?): List<Wunsch> {
        if (text.isNullOrBlank()) return emptyList()
        val liste = try {
            JSONArray(text)
        } catch (e: JSONException) {
            return emptyList()
        }
        val aus = LinkedHashMap<String, Wunsch>()
        for (i in 0 until minOf(liste.length(), HOECHSTENS)) {
            val o = liste.optJSONObject(i) ?: continue
            val nummer = o.optString("nummer").takeIf { NUMMER.matches(it) } ?: continue
            val name = o.optString("name").replace(Regex("\\p{Cntrl}+"), " ").trim().take(100)
            if (name.isEmpty() || name == "null") continue
            aus.putIfAbsent(vergleich(nummer), Wunsch(nummer, name))
        }
        return aus.values.toList()
    }

    fun alsJson(liste: List<Wunsch>): String =
        JSONArray().also { a -> for (w in liste) a.put(JSONObject().put("nummer", w.nummer).put("name", w.name)) }.toString()

    /** Fällig, wenn noch nie geholt, seit 30 Minuten nicht oder die Uhr zurückgestellt wurde. */
    fun faellig(jetzt: Long, zuletzt: Long): Boolean = zuletzt <= 0L || jetzt < zuletzt || jetzt - zuletzt >= ABSTAND_MS

    /**
     * Im Lauf: wenn freigegeben und fällig, die Namen holen (ohne Netz: der
     * letzte Stand), dann – falls sich die Liste geändert hat – anwenden.
     * Rückgabe: wie viele Kontakte angelegt, umbenannt oder zurückgenommen.
     */
    fun imTakt(context: Context, jetzt: Long = System.currentTimeMillis(), holen: (String) -> List<Wunsch> = Supabase::kontakteLesen): Int =
        Aktualisierung.allein {
            val speicher = Speicher(context)
            if (!speicher.kontakteBenennen || !erlaubt(context)) return@allein 0
            if (faellig(jetzt, speicher.kontakteGeholt)) {
                try {
                    speicher.kontakteListe = alsJson(holen(speicher.kennung))
                    speicher.kontakteGeholt = jetzt
                } catch (e: IOException) {
                    Log.i(LOG, "Kontakte: gerade kein Netz, es bleibt beim letzten Stand")
                }
            }
            benennen(context)
        }

    /** Den gemerkten Stand anwenden, falls er sich seit dem letzten Mal geändert hat. Eine leere Liste ändert nichts. */
    fun benennen(context: Context): Int {
        val speicher = Speicher(context)
        if (!speicher.kontakteBenennen || !erlaubt(context)) return 0
        val liste = ausJson(speicher.kontakteListe)
        val stand = alsJson(liste)
        if (liste.isEmpty() || stand == speicher.kontakteAngewandt) return 0
        return try {
            val n = anwenden(context.contentResolver, liste, speicher)
            speicher.kontakteAngewandt = stand
            n
        } catch (e: SecurityException) {
            0
        }
    }

    private fun anwenden(cr: ContentResolver, liste: List<Wunsch>, speicher: Speicher): Int {
        val angelegt = speicher.kontakteAngelegt.filterKeys { gibtEs(cr, it) }.toMutableMap()
        val umbenannt = speicher.kontakteUmbenannt.filterKeys { gibtEs(cr, it) }.toMutableMap()
        // Alle Nummern auf dem Handy: Vergleichsnummer → Kontakte, dazu deren Name.
        val telefon = HashMap<String, MutableSet<Long>>()
        val namen = HashMap<Long, String?>()
        cr.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val v = vergleich(c.getString(1))
                if (v.length < 3) continue
                telefon.getOrPut(v) { LinkedHashSet() } += c.getLong(0)
                namen[c.getLong(0)] = c.getString(2)
            }
        }
        val gewuenscht = liste.map { vergleich(it.nummer) }.toSet()
        val fertig = HashSet<Long>()
        var n = 0
        for (w in liste) {
            val kontakte = telefon[vergleich(w.nummer)]
            if (kontakte.isNullOrEmpty()) {
                anlegen(cr, w)?.let { angelegt[it] = w.nummer; n++ }
                continue
            }
            for (k in kontakte) {
                if (!fertig.add(k) || namen[k] == w.name) continue
                for (raw in rohkontakte(cr, k)) {
                    if (raw !in angelegt) umbenannt.putIfAbsent(raw, Umbenannt(nameVon(cr, raw).orEmpty(), w.nummer))
                    nameSetzen(cr, raw, w.name)
                }
                n++
            }
        }
        // Nicht mehr in der Liste: Angelegte weg, Umbenannte bekommen ihren alten Namen zurück.
        for ((raw, nummer) in angelegt.toMap()) {
            if (vergleich(nummer) in gewuenscht) continue
            cr.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw), null, null)
            angelegt.remove(raw)
            n++
        }
        for ((raw, u) in umbenannt.toMap()) {
            if (vergleich(u.nummer) in gewuenscht) continue
            nameSetzen(cr, raw, u.alt)
            umbenannt.remove(raw)
            n++
        }
        speicher.kontakteAngelegt = angelegt
        speicher.kontakteUmbenannt = umbenannt
        return n
    }

    /**
     * „Nicht mehr benennen“ (und „Alle Daten löschen“): aus, die angelegten
     * Kontakte weg, die umbenannten mit altem Namen. Ohne Erlaubnis bleibt das
     * Gemerkte, bis sie wieder da ist. Nie auf dem Hauptfaden.
     */
    fun zuruecknehmen(context: Context): Int = Aktualisierung.allein {
        val speicher = Speicher(context)
        speicher.kontakteBenennen = false
        speicher.kontakteGeholt = 0L
        speicher.kontakteListe = null
        speicher.kontakteAngewandt = null
        if (!erlaubt(context)) return@allein 0
        val cr = context.contentResolver
        var n = 0
        try {
            for (raw in speicher.kontakteAngelegt.keys) {
                if (gibtEs(cr, raw)) cr.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw), null, null)
                n++
            }
            for ((raw, u) in speicher.kontakteUmbenannt) {
                if (gibtEs(cr, raw)) nameSetzen(cr, raw, u.alt)
                n++
            }
        } catch (e: SecurityException) {
            return@allein 0
        }
        speicher.kontakteAngelegt = emptyMap()
        speicher.kontakteUmbenannt = emptyMap()
        n
    }

    private fun gibtEs(cr: ContentResolver, raw: Long): Boolean =
        cr.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw), arrayOf(RawContacts._ID, RawContacts.DELETED), null, null, null)?.use { c ->
            c.moveToFirst() && c.getInt(1) == 0
        } ?: false

    private fun rohkontakte(cr: ContentResolver, kontakt: Long): List<Long> {
        val aus = ArrayList<Long>()
        cr.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0",
            arrayOf(kontakt.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) aus += c.getLong(0) }
        return aus
    }

    private val NAME_ZEILE = "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?"

    private fun nameVon(cr: ContentResolver, raw: Long): String? =
        cr.query(Data.CONTENT_URI, arrayOf(StructuredName.DISPLAY_NAME), NAME_ZEILE, arrayOf(raw.toString(), StructuredName.CONTENT_ITEM_TYPE), null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Neuer Name; Vor- und Nachname leer, damit Android sie aus dem ganzen Namen neu teilt. Leerer Name: kein Name mehr. */
    private fun nameSetzen(cr: ContentResolver, raw: Long, name: String) {
        val wo = arrayOf(raw.toString(), StructuredName.CONTENT_ITEM_TYPE)
        if (name.isEmpty()) {
            cr.delete(Data.CONTENT_URI, NAME_ZEILE, wo)
            return
        }
        val werte = ContentValues().apply {
            put(StructuredName.DISPLAY_NAME, name)
            for (feld in arrayOf(
                StructuredName.GIVEN_NAME, StructuredName.FAMILY_NAME, StructuredName.MIDDLE_NAME, StructuredName.PREFIX, StructuredName.SUFFIX,
                StructuredName.PHONETIC_GIVEN_NAME, StructuredName.PHONETIC_MIDDLE_NAME, StructuredName.PHONETIC_FAMILY_NAME,
            )) putNull(feld)
        }
        if (cr.update(Data.CONTENT_URI, werte, NAME_ZEILE, wo) > 0) return
        werte.put(Data.RAW_CONTACT_ID, raw)
        werte.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
        cr.insert(Data.CONTENT_URI, werte)
    }

    /** Ein neuer Kontakt im Telefonspeicher (kein Konto): Name und die Nummer als „Geschäftlich“. */
    private fun anlegen(cr: ContentResolver, w: Wunsch): Long? {
        val uri = cr.insert(
            RawContacts.CONTENT_URI,
            ContentValues().apply {
                putNull(RawContacts.ACCOUNT_TYPE)
                putNull(RawContacts.ACCOUNT_NAME)
            },
        ) ?: return null
        val raw = ContentUris.parseId(uri)
        cr.insert(
            Data.CONTENT_URI,
            ContentValues().apply {
                put(Data.RAW_CONTACT_ID, raw)
                put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                put(StructuredName.DISPLAY_NAME, w.name)
            },
        )
        cr.insert(
            Data.CONTENT_URI,
            ContentValues().apply {
                put(Data.RAW_CONTACT_ID, raw)
                put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                put(Phone.NUMBER, w.nummer)
                put(Phone.TYPE, Phone.TYPE_WORK)
            },
        )
        return raw
    }
}
