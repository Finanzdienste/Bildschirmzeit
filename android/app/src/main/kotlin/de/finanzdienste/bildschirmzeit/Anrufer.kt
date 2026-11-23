package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Eine Bewerbung, wie die Bewerbungszentrale sie fürs Handy schickt (bildschirmzeit_kontakte_lesen). */
data class Akte(
    val akte: String,
    val name: String,
    val firma: String,
    val nummer: String?,
    val basis: String?,
    val weitere: List<String>,
    val briefing: String,
)

/** Was Claude zu einer Nummer gefunden hat; `gemeldet`: schon bei Supabase (für die Bewerbungszentrale). */
data class Fund(
    val nummer: String,
    val zeit: Long,
    val wer: String,
    val akte: String?,
    val sicher: Boolean,
    val hinweis: String = "",
    val gemeldet: Boolean = false,
    /** Hat der Anruf mit den Bewerbungen zu tun (Personalabteilung, Personalvermittler, Firma aus der Liste)? */
    val bewerbung: Boolean = false,
) {
    fun alsJson(): JSONObject = JSONObject().put("nummer", nummer).put("zeit", zeit).put("wer", wer).put("akte", akte ?: JSONObject.NULL)
        .put("sicher", sicher).put("hinweis", hinweis).put("gemeldet", gemeldet).put("bewerbung", bewerbung)

    companion object {
        fun aus(o: JSONObject): Fund? {
            val nummer = o.optString("nummer").takeIf { it.startsWith("+") } ?: return null
            val wer = o.optString("wer").takeIf { it.isNotBlank() } ?: return null
            return Fund(
                nummer, o.optLong("zeit"), wer, o.optString("akte").takeIf { !o.isNull("akte") && it.isNotEmpty() }, o.optBoolean("sicher"),
                o.optString("hinweis"), o.optBoolean("gemeldet"), o.optBoolean("bewerbung"),
            )
        }
    }
}

/**
 * Anrufer erkennen (Tobi, 23.11.2026: „Geil wärs wenn er während des
 * Telefonats direkt schon die Nummer googlet oder so und ich n schriftliches
 * briefing bekomme und der Kontakt benannt wird“). Erst nach Tobis Freigabe in
 * der App (Speicher.anruferErkennen; Telefon, Anrufliste, Kontakte,
 * Mitteilungen).
 *
 * Es klingelt (AnrufEmpfaenger, PHONE_STATE mit Nummer; kommt die Nummer nicht,
 * nach dem Auflegen aus der Anrufliste): Gehört die Nummer zu einer Bewerbung
 * (die Liste der Bewerbungszentrale, Kontakte.kt holt sie), zeigt eine
 * Mitteilung sofort das Briefing – ohne Netz aus dem Gemerkten. Sonst, wenn sie
 * auch nicht in Tobis Kontakten steht und ein Claude-Schlüssel da ist: Claude
 * schlägt sie mit Websuche nach (KI.kt), höchstens zehnmal am Tag. Ohne Netz
 * ist das vorgemerkt und läuft von selbst, sobald Netz da ist (WorkManager mit
 * Netz-Bedingung, dazu der 15-Minuten-Lauf). Das Ergebnis kommt als Mitteilung;
 * gehört die Nummer zu einer Bewerbung, benennt Kontakte.kt den Kontakt, und
 * Supabase bekommt den Fund für die Bewerbungszentrale (ohne Netz später).
 * Nummern aus Tobis Kontakten gehen nie an Claude.
 */
object Anrufer {
    const val KANAL = "anrufer"
    const val JE_TAG = 10
    const val DOPPELT_MS = 2 * 60_000L
    const val MERKEN_MS = 30L * 86_400_000L
    const val SCHLUESSEL_MS = 10 * 60_000L
    const val NACHHOLEN_TAGE = 30
    private const val LIVE_MS = 3_600_000L
    const val BEWERBUNGSZENTRALE = "https://finanzdienste.github.io/Bewerbungszentrale/"
    private const val ARBEIT = "nachschlagen"
    private val NUMMER = Regex("^\\+[0-9]{6,32}$")
    private val AKTE = Regex("^[A-Za-z0-9._-]{1,120}$")
    private val EINGEHEND = setOf("eingehend", "verpasst", "abgelehnt", "mailbox")

    /** Kontakte, die Tobi selbst „Job …“ genannt hat (etwa „Job Neu 2“), sind Platzhalter für unbekannte Anrufer aus den Bewerbungen. */
    private val PLATZHALTER = Regex("^\\s*job\\b", RegexOption.IGNORE_CASE)

    /** Was das Nachschlagen anstößt – in Tests ersetzt (dort gibt es kein WorkManager). */
    @Volatile
    internal var planer: (Context) -> Unit = ::planen

    fun erlaubt(context: Context): Boolean =
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Die Erlaubnisse, nach denen die App fragt; Mitteilungen erst ab Android 13. */
    fun erlaubnisse(): Array<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** Jede Schreibweise als +49… (Ausland +…); Kurzwahlen und Unterdrücktes → null. */
    fun e164(roh: String?): String? {
        val v = Kontakte.vergleich(roh)
        val n = when {
            v.startsWith("00") -> "+" + v.drop(2)
            v.startsWith("0") -> "+49" + v.drop(1)
            else -> return null
        }
        return n.takeIf { NUMMER.matches(it) }
    }

    /** Die Liste der Bewerbungszentrale (Speicher.kontakteListe); Unpassendes fällt still weg. */
    fun akten(text: String?): List<Akte> {
        val liste = try {
            JSONArray(text ?: return emptyList())
        } catch (e: JSONException) {
            return emptyList()
        }
        val aus = ArrayList<Akte>()
        for (i in 0 until liste.length()) {
            val o = liste.optJSONObject(i) ?: continue
            val akte = o.optString("akte").takeIf { AKTE.matches(it) } ?: continue
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: continue
            val nummer = o.optString("nummer").takeIf { !o.isNull("nummer") && NUMMER.matches(it) }
            val basis = o.optString("basis").takeIf { !o.isNull("basis") && NUMMER.matches(it) }
            val w = o.optJSONArray("weitere")
            val weitere = (0 until (w?.length() ?: 0)).mapNotNull { w?.optString(it)?.takeIf { x -> NUMMER.matches(x) } }
            aus += Akte(akte, name, o.optString("firma").trim().ifEmpty { name }, nummer, basis, weitere, o.optString("briefing").trim())
        }
        return aus
    }

    /** Die Bewerbung zur Nummer: zuerst die Nummer der Akte, dann die Zentrale mit Durchwahl, dann weitere Nummern. */
    fun finden(nummer: String, akten: List<Akte>): Akte? {
        val n = Kontakte.vergleich(nummer)
        if (n.length < 6) return null
        akten.firstOrNull { it.nummer != null && Kontakte.vergleich(it.nummer) == n }?.let { return it }
        akten.firstOrNull { a ->
            val b = a.basis?.let { Kontakte.vergleich(it) } ?: return@firstOrNull false
            n.startsWith(b) && n.length - b.length in 0..4
        }?.let { return it }
        return akten.firstOrNull { a -> a.weitere.any { Kontakte.vergleich(it) == n } }
    }

    /** Es klingelt (oder es hat geklingelt: aus der Anrufliste nach dem Auflegen). Nie auf dem Hauptfaden. */
    fun klingelt(context: Context, roh: String?, jetzt: Long = System.currentTimeMillis()) {
        val s = Speicher(context)
        if (!s.anruferErkennen) return
        val nummer = e164(roh) ?: return
        val vorher = s.zuletztGeklingelt?.split('|')
        if (vorher?.getOrNull(0) == nummer && jetzt - (vorher.getOrNull(1)?.toLongOrNull() ?: 0L) in 0 until DOPPELT_MS) return
        s.zuletztGeklingelt = "$nummer|$jetzt"
        val akten = akten(s.kontakteListe)
        finden(nummer, akten)?.let {
            mitteilen(context, nummer, it.name, it.briefing.ifEmpty { it.firma }, it.akte)
            return
        }
        s.nachgeschlagen[Kontakte.vergleich(nummer)]?.takeIf { jetzt - it.zeit in 0 until MERKEN_MS }?.let {
            fundMitteilen(context, it, akten, jetzt)
            return
        }
        // Nur mit Schlüssel, und nie für Nummern aus Tobis Kontakten (die gehen nicht an Claude).
        if (s.kiSchluessel == null || !erlaubt(context) || privat(context, nummer)) return
        s.nachschlagOffen = s.nachschlagOffen + (nummer to jetzt)
        val text = context.getString(if (online(context)) R.string.anrufer_sucht else R.string.anrufer_sucht_spaeter)
        mitteilen(context, nummer, context.getString(R.string.anrufer_unbekannt, anzeige(nummer)), text, null)
        planer(context)
    }

    /**
     * Aufgelegt: Kam beim Klingeln keine Nummer mit (manche Android-Fassungen
     * geben sie nicht her), den letzten Anruf aus der Anrufliste nehmen – dann
     * kommt das Briefing eben gleich nach dem Gespräch.
     */
    fun aufgelegt(context: Context, jetzt: Long = System.currentTimeMillis()) {
        val s = Speicher(context)
        if (!s.anruferErkennen) return
        val anruf = Anrufe.lesen(context, jetzt).firstOrNull() ?: return
        if (anruf.art !in EINGEHEND || jetzt - anruf.zeit !in 0 until 3 * 3_600_000L) return
        val nummer = e164(anruf.nummer) ?: return
        val vorher = s.zuletztGeklingelt?.split('|')
        if (vorher?.getOrNull(0) == nummer && (vorher.getOrNull(1)?.toLongOrNull() ?: 0L) >= anruf.zeit - 60_000L) return
        klingelt(context, nummer, jetzt)
    }

    /**
     * Ältere Anrufe nachholen (Tobi, 23.11.: „Okay aber die sind alle noch nicht
     * vernünftig benannt“): die eingehenden und verpassten Anrufe der letzten 30
     * Tage aus der Anrufliste, deren Nummer zu keiner Bewerbung gehört, noch nicht
     * nachgeschlagen ist und nicht in Tobis Kontakten steht – außer als
     * Platzhalter „Job …“. Vorgemerkt wie ein Klingeln, das Nachschlagen läuft
     * dann wie sonst (höchstens zehn am Tag, der Rest an den nächsten Tagen).
     * Beim Einschalten, nach dem Schlüssel und im 15-Minuten-Lauf. Rückgabe: wie
     * viele neu vorgemerkt.
     */
    fun nachholen(context: Context, jetzt: Long = System.currentTimeMillis()): Int {
        val s = Speicher(context)
        if (!s.anruferErkennen || s.kiSchluessel == null || !erlaubt(context)) return 0
        val akten = akten(s.kontakteListe)
        val bekannt = s.nachgeschlagen.keys
        val offen = s.nachschlagOffen.toMutableMap()
        var neu = 0
        for (a in Anrufe.lesen(context, jetzt, NACHHOLEN_TAGE)) {
            if (a.art !in EINGEHEND) continue
            val nummer = e164(a.nummer) ?: continue
            val v = Kontakte.vergleich(nummer)
            if (v in bekannt || offen.keys.any { Kontakte.vergleich(it) == v } || finden(nummer, akten) != null || privat(context, nummer)) continue
            offen[nummer] = a.zeit
            neu++
        }
        if (neu > 0) {
            s.nachschlagOffen = offen
            planer(context)
        }
        return neu
    }

    /** Nur kurz nach „Claude-Schlüssel holen“ in der App: Sonst könnte jede Webseite einen fremden Schlüssel unterschieben. */
    fun schluesselAnnehmen(s: Speicher, roh: String?, jetzt: Long): Boolean {
        val angefragt = s.kiAngefragt
        if (angefragt <= 0L || jetzt - angefragt !in 0..SCHLUESSEL_MS) return false
        val schluessel = roh?.trim()
        if (!KI.gueltig(schluessel)) return false
        s.kiSchluessel = schluessel
        s.kiAngefragt = 0L
        return true
    }

    /**
     * Das Vorgemerkte nachschlagen (WorkManager mit Netz, der 15-Minuten-Lauf,
     * gleich nach dem Klingeln). Ohne Netz bleibt es vorgemerkt; ein Fehler des
     * Schlüssels (4xx) nimmt die Nummer heraus und sagt es. Rückgabe: wie viele
     * nachgeschlagen wurden.
     */
    fun nachschlagen(context: Context, jetzt: Long = System.currentTimeMillis(), fragen: (String, String) -> String = KI::fragen): Int {
        val s = Speicher(context)
        melden(s)
        val schluessel = s.kiSchluessel
        if (schluessel == null || !s.anruferErkennen) {
            s.nachschlagOffen = emptyMap()
            return 0
        }
        val akten = akten(s.kontakteListe)
        var n = 0
        for ((nummer, zeit) in s.nachschlagOffen) {
            if (!kontingent(s, jetzt)) {
                if (jetzt - zeit > LIVE_MS) continue // nachgeholt: bleibt für die nächsten Tage vorgemerkt
                s.nachschlagOffen = s.nachschlagOffen - nummer
                mitteilen(context, nummer, context.getString(R.string.anrufer_unbekannt, anzeige(nummer)), context.getString(R.string.anrufer_genug_heute), null)
                continue
            }
            val text = try {
                fragen(schluessel, frage(nummer, zeit, akten))
            } catch (e: HttpFehler) {
                if (e.status == 408 || e.status == 429 || e.status >= 500) break
                s.nachschlagOffen = s.nachschlagOffen - nummer
                mitteilen(context, nummer, context.getString(R.string.anrufer_unbekannt, anzeige(nummer)), context.getString(R.string.anrufer_fehler, e.status), null)
                continue
            } catch (e: IOException) {
                Log.i(LOG, "Nachschlagen: gerade kein Netz, bleibt vorgemerkt")
                break
            }
            zaehlen(s, jetzt)
            val fund = fundAus(text, nummer, zeit, akten)
            s.nachgeschlagen = s.nachgeschlagen + (Kontakte.vergleich(nummer) to fund)
            s.nachschlagOffen = s.nachschlagOffen - nummer
            fundMitteilen(context, fund, akten, jetzt)
            n++
        }
        if (n > 0) {
            Aktualisierung.allein { Kontakte.benennen(context) }
            melden(s)
        }
        return n
    }

    /** Was Claude gefunden hat, aus seiner Antwort (JSON, auch mit Text drumherum); eine Akte nur aus der Liste. */
    fun fundAus(text: String, nummer: String, zeit: Long, akten: List<Akte>): Fund {
        val a = text.indexOf('{')
        val e = text.lastIndexOf('}')
        val o = if (a in 0 until e) {
            try {
                JSONObject(text.substring(a, e + 1))
            } catch (x: JSONException) {
                null
            }
        } else {
            null
        }
        val wer = o?.optString("wer")?.replace(Regex("\\s+"), " ")?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: "nicht gefunden"
        val akte = o?.optString("akte")?.takeIf { id -> akten.any { it.akte == id } }
        return Fund(
            nummer, zeit, wer, akte, o?.optBoolean("sicher") == true && wer != "unbekannt", o?.optString("hinweis")?.trim()?.take(300).orEmpty(),
            bewerbung = akte != null || o?.optBoolean("bewerbung") == true,
        )
    }

    /** Die Frage an Claude: Nummer, Zeit, die Firmen aus den Bewerbungen; Antwort als JSON. */
    fun frage(nummer: String, zeit: Long, akten: List<Akte>): String {
        val z = Instant.ofEpochMilli(zeit).atZone(ZoneId.systemDefault())
        val liste = akten.joinToString("\n") { "- ${it.akte}: ${it.firma} – ${it.name}" }.ifEmpty { "(keine)" }
        return """
            |Auf meinem Handy kam am ${z.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} um ${z.format(DateTimeFormatter.ofPattern("HH:mm"))} Uhr ein Anruf von ${anzeige(nummer)} ($nummer). Die Nummer steht nicht in meinen Kontakten.
            |Finde mit der Websuche heraus, wem sie gehört: Firma, Abteilung oder Person. Nummern von Firmen stehen oft im Impressum, auf Kontakt- oder Karriereseiten oder in Branchenverzeichnissen; eine Durchwahl gehört meist zur Zentrale mit derselben Vorwahl und denselben ersten Ziffern. Werbe- und Callcenter-Nummern stehen oft in Verzeichnissen für unerwünschte Anrufe.
            |Ich bewerbe mich gerade. Diese Firmen und Personalvermittler könnten anrufen (Kennung: Firma – Kontakt):
            |$liste
            |Antworte nur mit einem JSON-Objekt, ohne Text davor oder danach:
            |{"wer": "kurz, wem die Nummer gehört, z. B. Beispiel GmbH, Personalabteilung Hamburg; oder unbekannt", "akte": "die Kennung aus der Liste, wenn die Nummer zu dieser Firma oder diesem Vermittler gehört, sonst leer", "bewerbung": true, wenn der Anruf mit meinen Bewerbungen zu tun haben kann (Personalabteilung, Personalvermittler, Firma aus der Liste), sonst false, "sicher": true oder false, "hinweis": "ein kurzer Satz, worauf das beruht"}
            |"sicher" ist nur true, wenn eine Quelle genau diese Nummer (oder ihre Zentrale mit Durchwahlbereich) dieser Firma zuordnet. Nicht raten.
        """.trimMargin()
    }

    /** „040 1234567“ statt „+49401234567“ für die Mitteilung. */
    fun anzeige(nummer: String): String {
        if (!nummer.startsWith("+49")) return nummer
        val n = "0" + nummer.drop(3)
        val vorwahl = when {
            n.startsWith("01") -> 4
            n.startsWith("030") || n.startsWith("040") || n.startsWith("069") || n.startsWith("089") -> 3
            else -> 5
        }
        return if (n.length > vorwahl + 2) n.take(vorwahl) + " " + n.drop(vorwahl) else n
    }

    /**
     * Sicher Nachgeschlagenes als Namen für Kontakte.kt: gehört die Nummer zu
     * einer Bewerbung, die Firma; sonst, wenn der Anruf mit den Bewerbungen zu
     * tun hat (etwa ein Personalvermittler), was Claude gefunden hat. Werbung
     * und Privates bekommen keinen Kontakt.
     */
    fun wuensche(s: Speicher): List<Wunsch> {
        val akten = akten(s.kontakteListe).associateBy { it.akte }
        return s.nachgeschlagen.values.mapNotNull { f ->
            when {
                !f.sicher -> null
                f.akte != null -> akten[f.akte]?.let { Wunsch(f.nummer, it.firma) }
                f.bewerbung -> Wunsch(f.nummer, f.wer.take(100))
                else -> null
            }
        }
    }

    private fun kontingent(s: Speicher, jetzt: Long): Boolean {
        val tag = Instant.ofEpochMilli(jetzt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val (t, anzahl) = (s.nachschlagHeute ?: "").split('|').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        return t != tag || anzahl < JE_TAG
    }

    private fun zaehlen(s: Speicher, jetzt: Long) {
        val tag = Instant.ofEpochMilli(jetzt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val (t, anzahl) = (s.nachschlagHeute ?: "").split('|').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        s.nachschlagHeute = "$tag|${if (t == tag) anzahl + 1 else 1}"
    }

    /** Funde, die Supabase noch nicht hat, nachmelden (ohne Netz beim nächsten Mal). */
    private fun melden(s: Speicher) {
        val offen = s.nachgeschlagen.filterValues { !it.gemeldet }
        if (offen.isEmpty()) return
        try {
            for ((k, f) in offen) {
                var ok = Supabase.nachschlagMelden(s.kennung, f)
                if (!ok && Supabase.anmelden(s.kennung)) {
                    s.angemeldet = true
                    ok = Supabase.nachschlagMelden(s.kennung, f)
                }
                if (ok) s.nachgeschlagen = s.nachgeschlagen + (k to f.copy(gemeldet = true))
            }
        } catch (e: IOException) {
            Log.i(LOG, "Nachschlagen: Melden ohne Netz, beim nächsten Mal")
        }
    }

    /** Steht die Nummer in Tobis Kontakten (dann geht sie nie an Claude)? Platzhalter „Job …“ zählen nicht; nicht prüfbar zählt als ja. */
    private fun privat(context: Context, nummer: String): Boolean {
        val n = Kontakte.vergleich(nummer)
        return try {
            context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                var privat = false
                while (!privat && c.moveToNext()) {
                    privat = Kontakte.vergleich(c.getString(1)) == n && !PLATZHALTER.containsMatchIn(c.getString(2).orEmpty())
                }
                privat
            } ?: true
        } catch (e: SecurityException) {
            true // nicht prüfbar: dann lieber nicht nachschlagen
        }
    }

    private fun online(context: Context): Boolean {
        if (Netz.gesperrt) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val f = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return f.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun fundMitteilen(context: Context, f: Fund, akten: List<Akte>, jetzt: Long) {
        val akte = f.akte?.let { id -> akten.firstOrNull { it.akte == id } }
        val titel = "${anzeige(f.nummer)}: ${f.wer}"
        val wann = Instant.ofEpochMilli(f.zeit).atZone(ZoneId.systemDefault())
        val text = listOfNotNull(
            if (jetzt - f.zeit > LIVE_MS) context.getString(R.string.anrufer_anruf_am, wann.format(DateTimeFormatter.ofPattern("dd.MM., HH:mm"))) else null,
            if (f.sicher) null else context.getString(R.string.anrufer_unsicher),
            f.hinweis.takeIf { it.isNotEmpty() },
            akte?.briefing?.takeIf { it.isNotEmpty() },
        ).joinToString("\n\n").ifEmpty { f.wer }
        mitteilen(context, f.nummer, titel, text, akte?.akte)
    }

    /** Eine Mitteilung je Nummer (dieselbe wird ersetzt): Titel, zwei Zeilen, aufgeklappt alles; Antippen öffnet die Akte in der Bewerbungszentrale. */
    private fun mitteilen(context: Context, nummer: String, titel: String, text: String, akte: String?) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(KANAL, context.getString(R.string.anrufer_kanal), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.anrufer_kanal_text)
            },
        )
        val ziel = Intent(Intent.ACTION_VIEW, (BEWERBUNGSZENTRALE + (akte?.let { "#akte/$it" } ?: "")).toUri()).setPackage(FIREFOX)
        val antippen = PendingIntent.getActivity(context, nummer.hashCode(), ziel, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val m = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.ic_launcher_einfarbig)
            .setContentTitle(titel)
            .setContentText(text.lineSequence().filter { it.isNotBlank() }.take(2).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(antippen)
            .build()
        nm.notify(nummer.hashCode(), m)
    }

    /** WorkManager: sobald Netz da ist (gleich, wenn es schon da ist), einmal; bleibt etwas offen, später noch einmal. */
    private fun planen(context: Context) {
        val anfrage = OneTimeWorkRequest.Builder(NachschlagArbeit::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply { if (Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ARBEIT, ExistingWorkPolicy.KEEP, anfrage)
    }
}

/** Nachschlagen, sobald Netz da ist; bleibt etwas offen (Netz weg, neue Nummer), später noch einmal. */
class NachschlagArbeit(context: Context, parameter: WorkerParameters) : Worker(context, parameter) {
    override fun doWork(): Result {
        try {
            Anrufer.nachschlagen(applicationContext)
        } catch (e: RuntimeException) {
            Log.w(LOG, "Nachschlagen fehlgeschlagen: ${e.javaClass.simpleName}")
        }
        return if (Speicher(applicationContext).nachschlagOffen.isEmpty()) Result.success() else Result.retry()
    }
}

/**
 * Androids PHONE_STATE (darf eine App weiter im Manifest empfangen): beim
 * Klingeln mit Nummer – die gibt Android nur mit READ_CALL_LOG mit –, beim
 * Auflegen ohne.
 */
class AnrufEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val app = context.applicationContext
        val (zustand, nummer) = auswerten(intent)
        when (zustand) {
            TelephonyManager.EXTRA_STATE_RINGING -> if (nummer != null) imHintergrund { Anrufer.klingelt(app, nummer) }
            TelephonyManager.EXTRA_STATE_IDLE -> imHintergrund { Anrufer.aufgelegt(app) }
        }
    }

    companion object {
        /** (Zustand, Nummer) aus dem Intent; EXTRA_INCOMING_NUMBER ist seit Android 10 abgekündigt, kommt aber weiter. */
        @Suppress("DEPRECATION")
        fun auswerten(intent: Intent): Pair<String?, String?> =
            intent.getStringExtra(TelephonyManager.EXTRA_STATE) to intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
    }
}
