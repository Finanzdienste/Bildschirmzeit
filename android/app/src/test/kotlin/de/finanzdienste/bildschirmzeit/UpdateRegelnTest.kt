package de.finanzdienste.bildschirmzeit

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tobi, 15.11.: „Keinen bock immer zu aktualisieren“ – die Regeln fürs Update
 * von selbst, ohne Gerät: Prüfsumme, Fassung, Wartezeit, Status → Text und
 * was die Karte in der App zeigt.
 */
class UpdateRegelnTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val jetzt = ZonedDateTime.of(2026, 11, 15, 9, 0, 0, 0, berlin).toInstant().toEpochMilli()
    private val stunde = 3_600_000L
    private val sha = "5ca617f4a71db056a7ce7d4accf8d876b313fc61882689243050a36991b30420"
    private val paket = "de.finanzdienste.bildschirmzeit"

    private fun freigabe(n: Int) = Freigabe(n, "https://github.com/Finanzdienste/Bildschirmzeit/releases/download/v$n/bildschirmzeit.apk", sha, 774_584)

    private fun lage(
        freigabe: Freigabe? = freigabe(8),
        darf: Boolean = true,
        laeuft: Boolean = false,
        fehler: UpdateFehler? = null,
        status: Int = 0,
        erfolg: Int = 0,
        geprueft: Long = jetzt - 17 * 60_000L,
    ) = UpdateLage(7, "1.7", freigabe, darf, still = true, laeuft = laeuft, fehler = fehler, fehlerStatus = status, erfolg = erfolg, erfolgUm = jetzt - stunde, geprueft = geprueft, jetzt = jetzt, zone = berlin)

    @Test
    fun pruefsummeAusDemDigestVonGitHub() {
        assertEquals(sha, NeueVersion.sha256AusDigest("sha256:$sha"))
        assertEquals(sha, NeueVersion.sha256AusDigest("sha256:${sha.uppercase()}"))
        assertNull(NeueVersion.sha256AusDigest("sha512:$sha"))
        assertNull(NeueVersion.sha256AusDigest("sha256:${sha.drop(1)}"))
        assertNull(NeueVersion.sha256AusDigest(""))
        assertNull(NeueVersion.sha256AusDigest(null))

        assertTrue(UpdateRegeln.pruefsummeStimmt(sha, sha.uppercase(), 774_584, 774_584))
        assertFalse("andere Bytes", UpdateRegeln.pruefsummeStimmt(sha, sha.replace('5', '6'), 774_584, 774_584))
        assertFalse("abgebrochen", UpdateRegeln.pruefsummeStimmt(sha, sha, 700_000, 774_584))
        assertFalse("ohne Prüfsumme nie", UpdateRegeln.pruefsummeStimmt(null, sha, 774_584, 774_584))
    }

    @Test
    fun nieGleichOderAelter() {
        assertTrue(UpdateRegeln.neuer(freigabe(8), 7))
        assertFalse(UpdateRegeln.neuer(freigabe(7), 7))
        assertFalse(UpdateRegeln.neuer(freigabe(6), 7))
        assertFalse(UpdateRegeln.neuer(null, 7))

        assertNull(UpdateRegeln.archivPruefen(paket, 7, 8, paket, 8))
        assertEquals(UpdateFehler.FALSCHE_DATEI, UpdateRegeln.archivPruefen(paket, 7, 8, "de.jemand.anders", 8))
        assertEquals("gleiche Fassung", UpdateFehler.FALSCHE_DATEI, UpdateRegeln.archivPruefen(paket, 7, 7, paket, 7))
        assertEquals("ältere Fassung", UpdateFehler.FALSCHE_DATEI, UpdateRegeln.archivPruefen(paket, 7, 8, paket, 6))
        assertEquals("nicht die des Release", UpdateFehler.FALSCHE_DATEI, UpdateRegeln.archivPruefen(paket, 7, 8, paket, 9))
        assertEquals("keine APK", UpdateFehler.FALSCHE_DATEI, UpdateRegeln.archivPruefen(paket, 7, 8, null, null))

        assertTrue(freigabe(8).installierbar)
        assertFalse(freigabe(8).copy(sha256 = null).installierbar)
        assertFalse(freigabe(8).copy(adresse = null).installierbar)
        assertFalse(freigabe(8).copy(groesse = 0).installierbar)
        assertFalse(freigabe(8).copy(groesse = NeueVersion.HOECHSTENS_BYTES + 1).installierbar)
    }

    @Test
    fun githubHoechstensAlleVierStunden() {
        assertTrue(UpdateRegeln.githubFaellig(0, jetzt, UpdateRegeln.FRAGEN_MS))
        assertFalse(UpdateRegeln.githubFaellig(jetzt - 3 * stunde, jetzt, UpdateRegeln.FRAGEN_MS))
        assertTrue(UpdateRegeln.githubFaellig(jetzt - 4 * stunde, jetzt, UpdateRegeln.FRAGEN_MS))
        // Höchstens 6 Anfragen am Tag aus dem Hintergrund – weit unter den 60 je Stunde ohne Token.
        assertEquals(6L, 24 * stunde / UpdateRegeln.FRAGEN_MS)
    }

    @Test
    fun keinEndlosversuch() {
        assertEquals(0L, UpdateRegeln.wartezeit(0))
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 24L, 24L, 24L), (1..8).map { UpdateRegeln.wartezeit(it) / stunde })
        assertTrue(UpdateRegeln.hintergrundDarf(null, 0, 0, jetzt))
        assertFalse(UpdateRegeln.hintergrundDarf(UpdateFehler.KEIN_NETZ, 2, jetzt - stunde, jetzt))
        assertTrue(UpdateRegeln.hintergrundDarf(UpdateFehler.KEIN_NETZ, 2, jetzt - 2 * stunde, jetzt))
        // Hier hilft Warten nicht: erst ein neues Release (dann sind die Fehler vergessen) oder der Knopf.
        for (f in listOf(UpdateFehler.FALSCHE_DATEI, UpdateFehler.KONFLIKT, UpdateFehler.INKOMPATIBEL)) {
            assertFalse(f.name, UpdateRegeln.hintergrundDarf(f, 1, jetzt - 30 * stunde, jetzt))
        }
    }

    @Test
    fun androidsStatusWirdZuTextMitRat() {
        assertEquals(UpdateFehler.BESTAETIGEN, UpdateRegeln.fehlerAusStatus(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(UpdateFehler.ABGEBROCHEN, UpdateRegeln.fehlerAusStatus(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(UpdateFehler.BLOCKIERT, UpdateRegeln.fehlerAusStatus(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertEquals(UpdateFehler.KONFLIKT, UpdateRegeln.fehlerAusStatus(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(UpdateFehler.INKOMPATIBEL, UpdateRegeln.fehlerAusStatus(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
        assertEquals(UpdateFehler.UNGUELTIG, UpdateRegeln.fehlerAusStatus(PackageInstaller.STATUS_FAILURE_INVALID))
        assertEquals(UpdateFehler.SPEICHER, UpdateRegeln.fehlerAusStatus(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(UpdateFehler.ZEIT, UpdateRegeln.fehlerAusStatus(8)) // STATUS_FAILURE_TIMEOUT (ab Android 14)
        assertEquals(UpdateFehler.FEHLER, UpdateRegeln.fehlerAusStatus(PackageInstaller.STATUS_FAILURE))
        assertEquals(UpdateFehler.FEHLER, UpdateRegeln.fehlerAusStatus(99))

        val blockiert = UpdateRegeln.fehlerText(UpdateFehler.BLOCKIERT, 2)
        assertTrue(blockiert, blockiert.contains("Einstellungen → Sicherheit und Datenschutz → Automatische Sperre → aus"))
        assertTrue(UpdateRegeln.fehlerText(UpdateFehler.ABGEBROCHEN, 3).contains("„Noch einmal versuchen“"))
        assertTrue(UpdateRegeln.fehlerText(UpdateFehler.NICHT_ERLAUBT, 0).contains("„Diese Quelle zulassen“"))
        assertTrue(UpdateRegeln.fehlerText(UpdateFehler.FEHLER, 1).contains("Status 1"))
        // Im Speicher steht der feste Code, nicht der Name der Konstante (den R8 kürzen darf).
        assertEquals(UpdateFehler.entries.size, UpdateFehler.entries.map { it.code }.toSet().size)
        for (f in UpdateFehler.entries) {
            val text = UpdateRegeln.fehlerText(f, 1)
            assertTrue("$f: $text", text.endsWith(".") && text.length > 30 && !text.contains("null"))
        }
    }

    @Test
    fun ohneErlaubnisFuehrtDieKarteZumEinrichten() {
        val a = UpdateRegeln.anzeige(lage(darf = false, freigabe = null))
        assertEquals("Automatische Updates", a.titel)
        assertEquals(UpdateKnopf.EINRICHTEN, a.knopf)
        assertTrue(a.text, a.text.contains("„Automatische Updates einrichten“ → Schalter „Diese Quelle zulassen“ ein → zurück"))
        val neu = UpdateRegeln.anzeige(lage(darf = false))
        assertEquals("Neue Version verfügbar", neu.titel)
        assertEquals(UpdateKnopf.EINRICHTEN, neu.knopf)
        assertTrue(neu.text, neu.text.startsWith("Version 1.8 ist da (du hast 1.7)."))
    }

    @Test
    fun eingerichtetKommtEsVonSelbstOderPerKnopf() {
        val a = UpdateRegeln.anzeige(lage())
        assertEquals(UpdateKnopf.INSTALLIEREN, a.knopf)
        assertTrue(a.text, a.text.contains("von selbst"))
        assertTrue(a.text, a.text.contains("schließt sich dabei kurz"))

        val laeuft = UpdateRegeln.anzeige(lage(laeuft = true))
        assertEquals(UpdateKnopf.WARTEN, laeuft.knopf)
        assertTrue(laeuft.text.startsWith("Version 1.8 wird geladen und installiert."))

        // Android will es einmal bestätigt haben (im Hintergrund verworfen): Knopf statt Fehlermeldung.
        val bestaetigen = UpdateRegeln.anzeige(lage(fehler = UpdateFehler.BESTAETIGEN, status = -1))
        assertEquals("Neue Version verfügbar", bestaetigen.titel)
        assertEquals(UpdateKnopf.INSTALLIEREN, bestaetigen.knopf)
        assertTrue(bestaetigen.text, bestaetigen.text.contains("„Neue Version installieren“ → „Aktualisieren“"))

        val fehler = UpdateRegeln.anzeige(lage(fehler = UpdateFehler.BLOCKIERT, status = 2))
        assertEquals("Update hat nicht geklappt", fehler.titel)
        assertEquals(UpdateKnopf.NOCHMAL, fehler.knopf)
        assertTrue(fehler.text, fehler.text.startsWith("Version 1.8: Android hat die Installation blockiert"))

        // Ohne Prüfsumme bei GitHub: nicht von selbst, nur der Weg über den Browser.
        val ohne = UpdateRegeln.anzeige(lage(freigabe = freigabe(8).copy(sha256 = null)))
        assertEquals(UpdateKnopf.BROWSER, ohne.knopf)

        // Unter Android 12 fragt Android jedes Mal.
        val alt = UpdateRegeln.anzeige(lage().copy(still = false))
        assertEquals(UpdateKnopf.INSTALLIEREN, alt.knopf)
        assertTrue(alt.text.contains("dann „Aktualisieren“"))
    }

    @Test
    fun aktuellOhneKnopf() {
        val a = UpdateRegeln.anzeige(lage(freigabe = freigabe(7)))
        assertEquals("Automatische Updates ✓", a.titel)
        assertNull(a.knopf)
        assertEquals("Neue Versionen installiert die App von selbst – sie fragt GitHub alle 4 Stunden. Zuletzt nachgesehen: 08:43.", a.text)
        val danach = UpdateRegeln.anzeige(lage(freigabe = freigabe(7), erfolg = 7))
        assertTrue(danach.text, danach.text.contains("Zuletzt aktualisiert: 08:00 auf 1.7."))
        // Ein alter Fehler zu einer Fassung, die nicht neuer ist, zeigt nichts mehr.
        assertNull(UpdateRegeln.anzeige(lage(freigabe = freigabe(6), fehler = UpdateFehler.KEIN_NETZ)).knopf)
    }
}
