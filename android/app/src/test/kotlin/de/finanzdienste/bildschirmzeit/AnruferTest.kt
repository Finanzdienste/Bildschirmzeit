package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** Anrufer erkennen (Anrufer.kt, KI.kt): Briefing, Nachschlagen, Vormerken ohne Netz – erfundene Nummern, kein echtes Claude. */
@RunWith(RobolectricTestRunner::class)
class AnruferTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val jetzt = Instant.parse("2026-11-23T09:00:00Z").toEpochMilli()
    private val speicher get() = Speicher(kontext)
    private val schluessel = "sk-ant-" + "a".repeat(30)
    private var geplant = 0

    private val liste = JSONArray()
        .put(
            JSONObject().put("akte", "beispiel-1").put("name", "Erika Beispiel (Beispiel)").put("firma", "Beispiel")
                .put("nummer", "+4940111122").put("basis", "+494011112").put("weitere", JSONArray().put("+491701234567"))
                .put("briefing", "Beispiel GmbH – Controller\nStand: Beworben\nForderung 65.000 €"),
        )
        .put(
            JSONObject().put("akte", "muster-2").put("name", "Muster").put("firma", "Muster").put("nummer", JSONObject.NULL)
                .put("basis", JSONObject.NULL).put("weitere", JSONArray()).put("briefing", "Muster AG – Analyst"),
        )
        .toString()

    @Before
    fun vorbereiten() {
        Netz.gesperrt = true
        KontakteTest.ErfundeneKontakte.leeren()
        Robolectric.buildContentProvider(KontakteTest.ErfundeneKontakte::class.java).create(ContactsContract.AUTHORITY)
        Anrufer.planer = { geplant++ }
        val s = speicher
        s.anruferErkennen = true
        s.kontakteListe = liste
        s.kiSchluessel = null
        s.kiAngefragt = 0L
        s.kiPause = 0L
        s.kiFehler = null
        s.nachgeschlagen = emptyMap()
        s.nachschlagOffen = emptyMap()
        s.nachschlagHeute = null
        s.zuletztGeklingelt = null
        s.kontakteBenennen = false
        s.kontakteAngewandt = null
        s.kontakteAngelegt = emptyMap()
        s.kontakteUmbenannt = emptyMap()
        shadowOf(kontext).grantPermissions(*ALLE)
    }

    @After
    fun aufraeumen() {
        shadowOf(kontext).denyPermissions(*ALLE, Manifest.permission.WRITE_CONTACTS)
    }

    private fun mitteilungen(): List<Notification> = shadowOf(kontext.getSystemService(NotificationManager::class.java)).allNotifications
    private fun titel(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()

    @Test
    fun nummernUndAnzeige() {
        assertEquals("+4940111122", Anrufer.e164("040 1111-22"))
        assertEquals("+4940111122", Anrufer.e164("+49 40 1111 22"))
        assertEquals("+431234567", Anrufer.e164("0043 1234567"))
        assertNull("Kurzwahl", Anrufer.e164("11880"))
        assertNull(Anrufer.e164(null))
        assertEquals("040 111122", Anrufer.anzeige("+4940111122"))
        assertEquals("0170 1234567", Anrufer.anzeige("+491701234567"))
        assertEquals("04193 753543", Anrufer.anzeige("+494193753543"))
    }

    @Test
    fun findenNummerZentraleWeitere() {
        val akten = Anrufer.akten(liste)
        assertEquals(2, akten.size)
        assertEquals("beispiel-1", Anrufer.finden("+4940111122", akten)?.akte)
        assertEquals("Zentrale mit anderer Durchwahl", "beispiel-1", Anrufer.finden("040 1111-25", akten)?.akte)
        assertEquals("weitere Nummer aus dem Briefing", "beispiel-1", Anrufer.finden("0170 1234567", akten)?.akte)
        assertNull(Anrufer.finden("030 1234567", akten))
        assertNull("Durchwahl viel zu lang", Anrufer.finden("040 1111-2123456", akten))
        assertTrue(Anrufer.akten("kein JSON").isEmpty())
    }

    @Test
    fun bekannteNummerZeigtGleichDasBriefingAuchOhneNetz() {
        Anrufer.klingelt(kontext, "040 1111-22", jetzt)
        val m = mitteilungen().single()
        assertEquals("Erika Beispiel (Beispiel)", titel(m))
        assertTrue(text(m).contains("Forderung 65.000 €"))
        val ziel = shadowOf(m.contentIntent).savedIntent
        assertEquals("https://finanzdienste.github.io/Bewerbungszentrale/#akte/beispiel-1", ziel.dataString)
        assertEquals(FIREFOX, ziel.`package`)
        assertEquals(0, geplant)
    }

    @Test
    fun ausgeschaltetPassiertNichts() {
        speicher.anruferErkennen = false
        speicher.kiSchluessel = schluessel
        Anrufer.klingelt(kontext, "040 1111-22", jetzt)
        Anrufer.klingelt(kontext, "030 1234567", jetzt)
        assertTrue(mitteilungen().isEmpty())
        assertTrue(speicher.nachschlagOffen.isEmpty())
    }

    @Test
    fun unbekannteNummerOhneSchluesselNichts() {
        Anrufer.klingelt(kontext, "030 1234567", jetzt)
        assertTrue(mitteilungen().isEmpty())
        assertTrue(speicher.nachschlagOffen.isEmpty())
    }

    @Test
    fun unbekannteNummerOhneNetzVorgemerktUndNurEinmal() {
        speicher.kiSchluessel = schluessel
        Anrufer.klingelt(kontext, "030 1234567", jetzt)
        Anrufer.klingelt(kontext, "+49 30 1234567", jetzt + 5_000L) // Android meldet das Klingeln oft zweimal
        assertEquals(mapOf("+49301234567" to jetzt), speicher.nachschlagOffen)
        assertEquals(1, geplant)
        val m = mitteilungen().single()
        assertEquals("Unbekannte Nummer 030 1234567", titel(m))
        assertEquals(kontext.getString(R.string.anrufer_sucht_spaeter), text(m))
    }

    @Test
    fun nummerAusDenKontaktenGehtNieAnClaude() {
        speicher.kiSchluessel = schluessel
        KontakteTest.ErfundeneKontakte.anlegen("Max Beispiel", "030 1234567")
        Anrufer.klingelt(kontext, "030 1234567", jetzt)
        assertTrue(speicher.nachschlagOffen.isEmpty())
        assertTrue(mitteilungen().isEmpty())
        // ohne Erlaubnis für die Kontakte (nicht prüfbar) auch nicht
        shadowOf(kontext).denyPermissions(Manifest.permission.READ_CONTACTS)
        Anrufer.klingelt(kontext, "030 7654321", jetzt + DOPPELT)
        assertTrue(speicher.nachschlagOffen.isEmpty())
    }

    @Test
    fun ohneNetzBleibtVorgemerkt() {
        speicher.kiSchluessel = schluessel
        speicher.nachschlagOffen = mapOf("+49301234567" to jetzt)
        assertEquals(0, Anrufer.nachschlagen(kontext, jetzt) { _, _ -> throw IOException("kein Netz") })
        assertEquals(mapOf("+49301234567" to jetzt), speicher.nachschlagOffen)
        assertEquals(0, Anrufer.nachschlagen(kontext, jetzt) { _, _ -> throw HttpFehler(429) })
        assertEquals("zu viele Anfragen: später noch einmal", 1, speicher.nachschlagOffen.size)
        assertEquals(0, Anrufer.nachschlagen(kontext, jetzt) { _, _ -> throw HttpFehler(401) })
        assertEquals("falscher Schlüssel: bleibt vorgemerkt", 1, speicher.nachschlagOffen.size)
        val m = mitteilungen().single()
        assertEquals("Nachschlagen pausiert", titel(m))
        assertTrue(text(m).contains("Claude-Schlüssel gilt nicht"))
    }

    @Test
    fun abgelehntRuhtSechsStundenUndVerliertNichts() {
        // Tobi, 23.11.: drei Mitteilungen „Fehler 400“ – vorher fiel die Nummer heraus und kam mit „nachholen“ alle 15 Minuten wieder
        speicher.kiSchluessel = schluessel
        val offen = mapOf("+49301234567" to jetzt, "+49309876543" to jetzt - 60_000L, "+491601234567" to jetzt - 120_000L)
        speicher.nachschlagOffen = offen
        val guthaben = """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."}}"""
        var gefragt = 0
        assertEquals(0, Anrufer.nachschlagen(kontext, jetzt) { _, _ -> gefragt++; throw HttpFehler(400, guthaben) })
        assertEquals("nach der ersten Ablehnung keine weitere Anfrage", 1, gefragt)
        assertEquals(offen, speicher.nachschlagOffen)
        val m = mitteilungen().single()
        assertTrue(text(m).contains("kein Guthaben") && text(m).contains("Billing"))
        assertTrue(Anrufer.pausiert(speicher, jetzt + 3_600_000L))
        // in der Pause: nichts fragen, keine neue Mitteilung, auch nicht beim Klingeln einer neuen Nummer
        assertEquals(0, Anrufer.nachschlagen(kontext, jetzt + 3_600_000L) { _, _ -> gefragt++; "" })
        assertEquals(1, gefragt)
        Anrufer.klingelt(kontext, "040 5555555", jetzt + 3_700_000L)
        assertTrue(speicher.nachschlagOffen.containsKey("+49405555555"))
        assertTrue(mitteilungen().any { titel(it) != "Nachschlagen pausiert" && text(it).contains("Nachschlagen ist gerade pausiert") })
        // derselbe Grund nach der Pause: noch ein Versuch, aber keine zweite Pausen-Mitteilung
        assertEquals(0, Anrufer.nachschlagen(kontext, jetzt + Anrufer.PAUSE_MS + 1) { _, _ -> gefragt++; throw HttpFehler(400, guthaben) })
        assertEquals(2, gefragt)
        assertEquals(1, mitteilungen().count { titel(it) == "Nachschlagen pausiert" })
        // neuer Schlüssel: sofort weiter, und es klappt
        speicher.kiAngefragt = jetzt + Anrufer.PAUSE_MS + 2
        assertTrue(Anrufer.schluesselAnnehmen(speicher, "sk-ant-" + "b".repeat(30), jetzt + Anrufer.PAUSE_MS + 3))
        assertFalse(Anrufer.pausiert(speicher, jetzt + Anrufer.PAUSE_MS + 4))
        val n = Anrufer.nachschlagen(kontext, jetzt + Anrufer.PAUSE_MS + 4) { _, _ -> "{\"wer\": \"unbekannt\", \"sicher\": false}" }
        assertEquals(4, n)
        assertTrue(speicher.nachschlagOffen.isEmpty())
        assertNull(speicher.kiFehler)
    }

    @Test
    fun claudeFehlerVerstaendlich() {
        val f = { status: Int, meldung: String -> HttpFehler(status, JSONObject().put("type", "error").put("error", JSONObject().put("type", "x").put("message", meldung)).toString()) }
        assertTrue(KI.grund(f(400, "Your credit balance is too low to access the Anthropic API.")).contains("kein Guthaben"))
        assertTrue(KI.grund(f(401, "invalid x-api-key")).contains("gilt nicht"))
        assertTrue(KI.grund(f(400, "Web search is not enabled for your organization. An admin can enable it in Console settings.")).contains("Websuche"))
        assertEquals("Claude meldet Fehler 400: messages: Feld fehlt", KI.grund(f(400, "messages:  Feld\nfehlt")))
        assertEquals("Claude meldet Fehler 404.", KI.grund(HttpFehler(404, "<html>nicht da</html>")))
        assertFalse("ein Werkzeug-Fehler ist keine abgeschaltete Websuche", KI.grund(f(400, "tools.0: web_search_20260209 is not a valid type")).contains("nicht erlaubt"))
    }

    @Test
    fun ohneRueckfallNochEinmal() {
        // kennt das Konto den Beta-Kopf oder „fallbacks“ nicht: dieselbe Frage ohne – einmal
        val gesendet = mutableListOf<Pair<Map<String, String>, JSONObject>>()
        val antwort = JSONObject().put("stop_reason", "end_turn").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok"))).toString()
        val text = KI.fragen(schluessel, "Wem gehört 040 1234567?") { koepfe, inhalt ->
            gesendet += koepfe to JSONObject(inhalt)
            if (gesendet.size == 1) throw HttpFehler(400, """{"type":"error","error":{"type":"invalid_request_error","message":"Unexpected value(s) `server-side-fallback-2026-07-01` for the `anthropic-beta` header."}}""")
            antwort
        }
        assertEquals("ok", text)
        assertEquals(2, gesendet.size)
        assertEquals("server-side-fallback-2026-07-01", gesendet[0].first["anthropic-beta"])
        assertTrue(gesendet[0].second.has("fallbacks"))
        assertNull(gesendet[1].first["anthropic-beta"])
        assertFalse(gesendet[1].second.has("fallbacks"))
        assertEquals(schluessel, gesendet[1].first["x-api-key"])
        // andere 400er gehen gleich weiter (an Anrufer.nachschlagen)
        var mal = 0
        val e = runCatching { KI.fragen(schluessel, "x") { _, _ -> mal++; throw HttpFehler(400, "{}") } }.exceptionOrNull()
        assertTrue(e is HttpFehler)
        assertEquals(1, mal)
    }

    @Test
    fun nachgeschlagenGehoertZurBewerbungUndBenenntDenKontakt() {
        speicher.kiSchluessel = schluessel
        speicher.kontakteBenennen = true
        shadowOf(kontext).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        speicher.nachschlagOffen = mapOf("+49301234567" to jetzt)
        var gefragt = ""
        val n = Anrufer.nachschlagen(kontext, jetzt) { k, frage ->
            assertEquals(schluessel, k)
            gefragt = frage
            "Hier das Ergebnis:\n```json\n{\"wer\": \"Muster AG, Personal\", \"akte\": \"muster-2\", \"sicher\": true, \"hinweis\": \"Impressum der Muster AG\"}\n```"
        }
        assertEquals(1, n)
        assertTrue(gefragt.contains("030 1234567 (+49301234567)"))
        assertTrue("die Firmen aus den Bewerbungen", gefragt.contains("- muster-2: Muster – Muster") && gefragt.contains("- beispiel-1: Beispiel – Erika Beispiel (Beispiel)"))
        val f = speicher.nachgeschlagen.getValue("0301234567")
        assertEquals(Fund("+49301234567", jetzt, "Muster AG, Personal", "muster-2", true, "Impressum der Muster AG", gemeldet = false, bewerbung = true), f)
        assertTrue(speicher.nachschlagOffen.isEmpty())
        val m = mitteilungen().single()
        assertEquals("030 1234567: Muster AG, Personal", titel(m))
        assertTrue(text(m).contains("Muster AG – Analyst"))
        // der Kontakt heißt jetzt wie die Firma
        val neu = KontakteTest.ErfundeneKontakte.mitName("Muster").single()
        assertEquals(listOf<String?>("+49301234567"), KontakteTest.ErfundeneKontakte.nummern(neu))
        // ruft die Nummer wieder an: gleich das Gefundene, kein neues Nachschlagen
        Anrufer.klingelt(kontext, "030 1234567", jetzt + 3_600_000L)
        assertEquals(0, geplant)
        assertTrue(speicher.nachschlagOffen.isEmpty())
    }

    @Test
    fun unsicherOderFremdeAkteBenenntNichts() {
        speicher.kiSchluessel = schluessel
        speicher.kontakteBenennen = true
        shadowOf(kontext).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        speicher.nachschlagOffen = mapOf("+49301234567" to jetzt, "+49307777777" to jetzt)
        Anrufer.nachschlagen(kontext, jetzt) { _, frage ->
            if (frage.contains("+49301234567")) "{\"wer\": \"Werbeanruf\", \"akte\": \"\", \"sicher\": true}" else "{\"wer\": \"Irgendwer\", \"akte\": \"gibt-es-nicht\", \"sicher\": true}"
        }
        assertNull(speicher.nachgeschlagen.getValue("0307777777").akte)
        assertNull(speicher.nachgeschlagen.getValue("0301234567").akte)
        val namen = KontakteTest.ErfundeneKontakte.roh.keys.map { KontakteTest.ErfundeneKontakte.name(it) }
        assertEquals("nur der Kontakt aus der Liste der Bewerbungszentrale", listOf<String?>("Erika Beispiel (Beispiel)"), namen)
        assertEquals(Fund("+4930", 0, "nicht gefunden", null, false), Anrufer.fundAus("kein JSON", "+4930", 0, emptyList()))
    }

    @Test
    fun hoechstensZehnAmTag() {
        speicher.kiSchluessel = schluessel
        speicher.nachschlagHeute = Instant.ofEpochMilli(jetzt).atZone(ZoneId.systemDefault()).toLocalDate().toString() + "|10"
        speicher.nachschlagOffen = mapOf("+49301234567" to jetzt)
        assertEquals(0, Anrufer.nachschlagen(kontext, jetzt) { _, _ -> throw AssertionError("nicht mehr fragen") })
        assertTrue(speicher.nachschlagOffen.isEmpty())
        assertEquals(kontext.getString(R.string.anrufer_genug_heute), text(mitteilungen().single()))
    }

    @Test
    fun schluesselNurKurzNachDerAnfrage() {
        val s = speicher
        assertFalse("ungefragt nie", Anrufer.schluesselAnnehmen(s, schluessel, jetzt))
        s.kiAngefragt = jetzt - 11 * 60_000L
        assertFalse("zu spät", Anrufer.schluesselAnnehmen(s, schluessel, jetzt))
        s.kiAngefragt = jetzt - 60_000L
        assertFalse("kein Claude-Schlüssel", Anrufer.schluesselAnnehmen(s, "AIza" + "b".repeat(35), jetzt))
        assertTrue(Anrufer.schluesselAnnehmen(s, schluessel, jetzt))
        assertEquals(schluessel, s.kiSchluessel)
        assertFalse("nur einmal je Anfrage", Anrufer.schluesselAnnehmen(s, "sk-ant-" + "c".repeat(30), jetzt))
        assertEquals(schluessel, s.kiSchluessel)
    }

    @Test
    fun empfaengerLiestZustandUndNummer() {
        val i = Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
        @Suppress("DEPRECATION")
        i.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, "040 1111-22")
        assertEquals(TelephonyManager.EXTRA_STATE_RINGING to "040 1111-22", AnrufEmpfaenger.auswerten(i))
        assertEquals(TelephonyManager.EXTRA_STATE_IDLE to null, AnrufEmpfaenger.auswerten(Intent().putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)))
    }

    @Test
    fun ohneNummerBeimKlingelnKommtDasBriefingNachDemAuflegen() {
        Robolectric.buildContentProvider(AnrufeTest.ErfundeneAnrufliste::class.java).create(CallLog.AUTHORITY)
        // Der letzte Anruf der erfundenen Anrufliste: verpasst, +49 40 1234-56, vor 30 Minuten.
        speicher.kontakteListe = JSONArray().put(
            JSONObject().put("akte", "a1").put("name", "Hafen Beispiel").put("firma", "Hafen").put("nummer", "+4940123456").put("briefing", "Hafen Beispiel AG – Controller"),
        ).toString()
        Anrufer.aufgelegt(kontext, jetzt)
        assertEquals("Hafen Beispiel", titel(mitteilungen().single()))
        Anrufer.aufgelegt(kontext, jetzt + 60_000L)
        assertEquals("dieser Anruf schon gezeigt", 1, mitteilungen().size)
    }

    /** Tobi, 23.11.: „Okay aber die sind alle noch nicht vernünftig benannt“ – ältere Anrufe und Platzhalter „Job …“. */
    @Test
    fun nachholenSchlaegtAlteAnrufeNachUndBenenntPlatzhalter() {
        Robolectric.buildContentProvider(AnrufeTest.ErfundeneAnrufliste::class.java).create(CallLog.AUTHORITY)
        speicher.kiSchluessel = schluessel
        KontakteTest.ErfundeneKontakte.anlegen("Max Beispiel", "+49 40 1234-56") // privat: geht nie an Claude
        val platzhalter = KontakteTest.ErfundeneKontakte.anlegen("Job Neu 2", "0176 12345678") // von Tobi selbst als „Job …“ gespeichert
        assertEquals(2, Anrufer.nachholen(kontext, jetzt))
        assertEquals(setOf("+4917612345678", "+4940111"), speicher.nachschlagOffen.keys)
        assertEquals(1, geplant)
        assertEquals("nichts doppelt", 0, Anrufer.nachholen(kontext, jetzt))
        // Heute schon zehn: Nachgeholtes bleibt für die nächsten Tage, ohne Mitteilung.
        speicher.nachschlagHeute = Instant.ofEpochMilli(jetzt).atZone(ZoneId.systemDefault()).toLocalDate().toString() + "|10"
        assertEquals(0, Anrufer.nachschlagen(kontext, jetzt) { _, _ -> throw AssertionError("nicht mehr fragen") })
        assertEquals(2, speicher.nachschlagOffen.size)
        assertTrue(mitteilungen().isEmpty())
        // Am nächsten Tag: nachgeschlagen, der Platzhalter heißt wie der Vermittler.
        speicher.kontakteBenennen = true
        shadowOf(kontext).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        val morgen = jetzt + 86_400_000L
        val n = Anrufer.nachschlagen(kontext, morgen) { _, frage ->
            if (frage.contains("+4917612345678")) {
                "{\"wer\": \"Beispiel Personal GmbH, Personalvermittlung\", \"akte\": \"\", \"bewerbung\": true, \"sicher\": true}"
            } else {
                "{\"wer\": \"unbekannt\", \"sicher\": false}"
            }
        }
        assertEquals(2, n)
        assertEquals("Beispiel Personal GmbH, Personalvermittlung", KontakteTest.ErfundeneKontakte.name(platzhalter))
        assertTrue(speicher.nachschlagOffen.isEmpty())
        assertTrue("mit der Zeit des Anrufs", mitteilungen().any { text(it).startsWith("Anruf am 23.11., ") })
        assertEquals("schon nachgeschlagen", 0, Anrufer.nachholen(kontext, morgen))
        // Ohne Schlüssel holt es nichts nach.
        speicher.kiSchluessel = null
        speicher.nachgeschlagen = emptyMap()
        assertEquals(0, Anrufer.nachholen(kontext, morgen))
    }

    @Test
    fun claudeAntwortLesen() {
        val antwort = JSONObject().put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "server_tool_use").put("name", "web_search"))
                .put(JSONObject().put("type", "web_search_tool_result").put("content", JSONArray()))
                .put(JSONObject().put("type", "text").put("text", "{\"wer\": \"Beispiel GmbH\", "))
                .put(JSONObject().put("type", "text").put("text", "\"akte\": \"\", \"sicher\": false}")),
        )
        assertEquals("{\"wer\": \"Beispiel GmbH\", \"akte\": \"\", \"sicher\": false}", KI.textAus(antwort))
        val a = KI.anfrage(JSONArray())
        assertEquals("claude-opus-5-5", a.getString("model"))
        assertEquals("web_search_20260209", a.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("default", a.getString("fallbacks"))
        assertFalse(KI.gueltig("sk-ant-kurz"))
    }

    private companion object {
        const val DOPPELT = Anrufer.DOPPELT_MS
        val ALLE = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
