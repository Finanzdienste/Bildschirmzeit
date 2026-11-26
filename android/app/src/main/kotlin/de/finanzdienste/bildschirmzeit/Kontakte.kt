package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

/** Ein Platzhalter „Job Neu …“, den die App gelöscht hat: Name und Nummern, damit „Nicht mehr benennen“ ihn wieder anlegt. */
data class Geloescht(val name: String, val nummern: List<String>)

/**
 * Kontakte für die Nummern aus den Bewerbungen (Tobi, 23.11.2026: „Du kannst
 * meine Telefon Kontakte auch umbennenen“). Die Bewerbungszentrale legt je
 * Bewerbung Namen, Nummern und Briefing bei Supabase ab
 * (bildschirmzeit_kontakte_setzen, Name etwa „Erika Muster (Beispiel)“), die
 * App holt die Liste im 15-Minuten-Lauf höchstens alle 30 Minuten und merkt
 * sie sich (auch für Anrufer.kt). Erst nach Tobis Freigabe in der App
 * (Speicher.kontakteBenennen und Androids Erlaubnis für die Kontakte).
 *
 * Angewandt wird nur, wenn sich die Liste geändert hat – was Tobi danach von
 * Hand umbenennt, bleibt, bis sich der Name in der Liste ändert. Ein Kontakt mit
 * der Nummer bekommt den neuen Namen (der alte wird gemerkt), fehlt er, legt
 * die App einen im Telefonspeicher an. Fällt eine Nummer aus der Liste, löscht
 * sie den angelegten Kontakt wieder und gibt einem umbenannten seinen alten
 * Namen zurück; „Nicht mehr benennen“ tut das für alle. Ohne Netz bleibt es
 * beim zuletzt geholten Stand, nichts geht verloren.
 *
 * Tobis Platzhalter „Job Neu 2“ & Co. (Tobi, 26.11.2026: „lass machen dass die
 * widget app auch selbst benannte Kontakte wieder löscht“): Gehört die Nummer zu
 * einer Bewerbung, bekommt der Kontakt deren Namen, sonst löscht die App ihn –
 * „Anrufer erkennen“ schlägt die Nummer dann wie jede unbekannte nach. Andere
 * selbst benannte Kontakte fasst sie nie an.
 */
object Kontakte {
    const val ABSTAND_MS = 30 * 60_000L
    const val HOECHSTENS = 500
    private val NUMMER = Regex("^\\+?[0-9]{3,32}$")

    /** Nur Tobis Platzhalter „Job Neu …“ (beliebige Schreibweise), nicht jeder Kontakt mit „Job“ im Namen. */
    private val PLATZHALTER = Regex("^\\s*job\\s*neu\\b", RegexOption.IGNORE_CASE)

    fun platzhalter(name: String?): Boolean = name != null && PLATZHALTER.containsMatchIn(name)

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
    fun imTakt(context: Context, jetzt: Long = System.currentTimeMillis(), holen: (String) -> String = Supabase::kontakteLesen): Int =
        Aktualisierung.allein {
            val speicher = Speicher(context)
            // Die Liste brauchen beide: Kontakte benennen und Anrufer erkennen (Anrufer.kt, mit den Briefings).
            val benennen = speicher.kontakteBenennen && erlaubt(context)
            if (!benennen && !speicher.anruferErkennen) return@allein 0
            if (faellig(jetzt, speicher.kontakteGeholt)) {
                try {
                    speicher.kontakteListe = holen(speicher.kennung)
                    speicher.kontakteGeholt = jetzt
                } catch (e: IOException) {
                    Log.i(LOG, "Kontakte: gerade kein Netz, es bleibt beim letzten Stand")
                }
            }
            benennen(context)
        }

    /**
     * Den gemerkten Stand anwenden, falls er sich seit dem letzten Mal geändert
     * hat – die Liste der Bewerbungszentrale und dazu, was Anrufer.kt sicher
     * einer Bewerbung zugeordnet hat. Eine leere Liste ändert nichts.
     */
    fun benennen(context: Context): Int {
        val speicher = Speicher(context)
        if (!speicher.kontakteBenennen || !erlaubt(context)) return 0
        val liste = (ausJson(speicher.kontakteListe) + Anrufer.wuensche(speicher)).distinctBy { vergleich(it.nummer) }
        // Leer: Die Bewerbungszentrale hat noch nichts geschickt – dann weiß die App nicht, welche Nummern dazugehören.
        if (liste.isEmpty()) return 0
        val stand = alsJson(liste)
        return try {
            var n = 0
            if (stand != speicher.kontakteAngewandt) {
                n += anwenden(context.contentResolver, liste, speicher)
                // Ging etwas schief (Android lehnt das Anlegen ab), beim nächsten Lauf noch einmal versuchen
                if (speicher.kontakteFehler == null) speicher.kontakteAngewandt = stand
            }
            // Platzhalter bei jedem Lauf: Tobi legt sie an, wann er will, auch ohne dass sich die Liste ändert.
            n + platzhalterAufraeumen(context.contentResolver, liste, speicher)
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
        var fehler: String? = null
        // Jede Nummer für sich: Lehnt Android einen neuen Kontakt ab, werden die übrigen trotzdem benannt
        // (bis v26 brach der ganze Lauf beim ersten Fehler ab, und es wurde gar nichts umbenannt).
        for (w in liste) {
            try {
                val kontakte = telefon[vergleich(w.nummer)]
                if (kontakte.isNullOrEmpty()) {
                    val raw = anlegen(cr, w)
                    if (raw == null) fehler = "Android hat das Anlegen abgelehnt" else { angelegt[raw] = w.nummer; n++ }
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
            } catch (e: RuntimeException) {
                if (e is SecurityException) throw e
                Log.w(LOG, "Kontakt benennen fehlgeschlagen: ${e.javaClass.simpleName}") // ohne Namen und Nummer
                fehler = (e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").take(120)
            }
        }
        speicher.kontakteFehler = fehler
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
        speicher.kontakteFehler = null
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
            // Gelöschte Platzhalter kommen zurück, so wie Tobi sie hatte.
            for (g in speicher.kontakteGeloescht) {
                if (g.nummern.isEmpty()) continue
                if (anlegen(cr, Wunsch(g.nummern.first(), g.name), g.nummern.drop(1)) != null) n++
            }
        } catch (e: SecurityException) {
            return@allein 0
        }
        speicher.kontakteAngelegt = emptyMap()
        speicher.kontakteUmbenannt = emptyMap()
        speicher.kontakteGeloescht = emptyList()
        n
    }

    /**
     * Platzhalter „Job Neu …“: mit einer Nummer aus der Liste umbenennen (der alte Name wird gemerkt wie sonst auch),
     * ohne löschen und Name und Nummern merken. Gibt zurück, wie viele Kontakte es betraf.
     */
    private fun platzhalterAufraeumen(cr: ContentResolver, liste: List<Wunsch>, speicher: Speicher): Int {
        val wunsch = liste.associateBy { vergleich(it.nummer) }
        val gefunden = LinkedHashMap<Long, Pair<String, MutableList<String>>>() // Kontakt → Name, Nummern
        cr.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2)
                if (!platzhalter(name)) continue
                gefunden.getOrPut(c.getLong(0)) { name!! to ArrayList() }.second += c.getString(1).orEmpty()
            }
        }
        if (gefunden.isEmpty()) return 0
        val umbenannt = speicher.kontakteUmbenannt.toMutableMap()
        val geloescht = speicher.kontakteGeloescht.toMutableList()
        var n = 0
        for ((kontakt, eintrag) in gefunden) {
            val (name, nummern) = eintrag
            val w = nummern.firstNotNullOfOrNull { wunsch[vergleich(it)] }
            for (raw in rohkontakte(cr, kontakt)) {
                if (w != null) {
                    umbenannt.putIfAbsent(raw, Umbenannt(nameVon(cr, raw).orEmpty(), w.nummer))
                    nameSetzen(cr, raw, w.name)
                } else {
                    cr.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw), null, null)
                }
            }
            if (w == null) geloescht += Geloescht(name, nummern.filter { it.isNotBlank() }.distinct())
            n++
        }
        speicher.kontakteUmbenannt = umbenannt
        speicher.kontakteGeloescht = geloescht
        Log.i(LOG, "Platzhalter: $n aufgeräumt") // ohne Namen und Nummern
        return n
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

    /**
     * Ein neuer Kontakt mit Name und der Nummer als „Geschäftlich“. Wohin: Android 16 kennt ein
     * Standardkonto für neue Kontakte und lehnt Kontakte „nur auf dem Telefon“ ab, wenn das ein
     * Google-Konto ist. Deshalb erst das Standardkonto, sonst der Telefonspeicher, und lehnt Android
     * den ab, das Konto, in dem die vorhandenen Kontakte liegen (Google zuerst).
     */
    private fun anlegen(cr: ContentResolver, w: Wunsch, weitere: List<String> = emptyList()): Long? {
        val erst = standardKonto(cr)
        val raw = try {
            rohAnlegen(cr, erst)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        } ?: kontoVorhandener(cr)?.takeIf { it != erst }?.let { rohAnlegen(cr, it) } ?: return null
        cr.insert(
            Data.CONTENT_URI,
            ContentValues().apply {
                put(Data.RAW_CONTACT_ID, raw)
                put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                put(StructuredName.DISPLAY_NAME, w.name)
            },
        )
        for (nummer in listOf(w.nummer) + weitere) {
            cr.insert(
                Data.CONTENT_URI,
                ContentValues().apply {
                    put(Data.RAW_CONTACT_ID, raw)
                    put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    put(Phone.NUMBER, nummer)
                    put(Phone.TYPE, Phone.TYPE_WORK)
                },
            )
        }
        return raw
    }

    /** Konto (Typ, Name) oder null für den Telefonspeicher. */
    private fun rohAnlegen(cr: ContentResolver, konto: Pair<String, String>?): Long? {
        val uri = cr.insert(
            RawContacts.CONTENT_URI,
            ContentValues().apply {
                if (konto == null) {
                    putNull(RawContacts.ACCOUNT_TYPE)
                    putNull(RawContacts.ACCOUNT_NAME)
                } else {
                    put(RawContacts.ACCOUNT_TYPE, konto.first)
                    put(RawContacts.ACCOUNT_NAME, konto.second)
                }
            },
        ) ?: return null
        return ContentUris.parseId(uri)
    }

    /** Das Standardkonto für neue Kontakte (ab Android 16), wenn es ein Konto ist; sonst null (Telefonspeicher). */
    private fun standardKonto(cr: ContentResolver): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            val d = RawContacts.DefaultAccount.getDefaultAccountForNewContacts(cr)
            val a = d.account
            val konto = d.state == RawContacts.DefaultAccount.DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_CLOUD ||
                d.state == RawContacts.DefaultAccount.DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_SIM
            if (konto && a != null) a.type to a.name else null
        } catch (e: RuntimeException) {
            null // manche Hersteller oder Fassungen kennen die Abfrage nicht
        }
    }

    /** Das Konto mit den meisten vorhandenen Kontakten, Google bevorzugt. */
    private fun kontoVorhandener(cr: ContentResolver): Pair<String, String>? {
        val zaehler = HashMap<Pair<String, String>, Int>()
        cr.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.DELETED}=0 AND ${RawContacts.ACCOUNT_TYPE} IS NOT NULL",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val typ = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                zaehler.merge(typ to name, 1, Int::plus)
            }
        }
        return zaehler.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.key.first == "com.google" }.thenByDescending { it.value })
            .firstOrNull()?.key
    }
}
