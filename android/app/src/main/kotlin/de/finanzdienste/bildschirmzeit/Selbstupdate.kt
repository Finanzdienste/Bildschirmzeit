package de.finanzdienste.bildschirmzeit

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tobi, 15.11.: „Keinen bock immer zu aktualisieren“ – die App installiert neue
 * Releases selbst (README „Updates von selbst“).
 *
 * Der Weg (geprüft gegen AOSP, PackageInstallerSession.computeUserActionRequirement):
 * Android installiert ohne Rückfrage, wenn die App sich selbst aktualisiert,
 * `USER_ACTION_NOT_REQUIRED` setzt (ab Android 12), UPDATE_PACKAGES_WITHOUT_USER_ACTION
 * deklariert, die neue APK targetSdk ≥ 33 hat und „Unbekannte Apps
 * installieren“ für sie erlaubt ist (sonst „isInstallDisabledForPackage“ →
 * Rückfrage). Hat ein anderer Installer die Update-Hoheit (Update-Owner),
 * fragt Android trotzdem nach – dann nur im Vordergrund über den Knopf in der
 * App, im Hintergrund wird die Sitzung verworfen und die App sagt es.
 *
 * Sicher: Laden nur über HTTPS von den Releases dieses Repos, SHA-256 gegen
 * das Feld „digest“ der GitHub-API, Paketname und versionCode der Datei
 * gegen das Release; nie gleich alt oder älter. Die Signatur prüft Android
 * (gleiche Signatur wie die installierte App).
 */
enum class UpdateFehler(
    /** So steht er im Speicher – fest, auch wenn R8 die Namen der Konstanten kürzt. */
    val code: String,
) {
    KEIN_NETZ("kein_netz"),
    PRUEFSUMME("pruefsumme"),
    FALSCHE_DATEI("falsche_datei"),
    NICHT_ERLAUBT("nicht_erlaubt"),
    BESTAETIGEN("bestaetigen"),
    ABGEBROCHEN("abgebrochen"),
    BLOCKIERT("blockiert"),
    KONFLIKT("konflikt"),
    INKOMPATIBEL("inkompatibel"),
    UNGUELTIG("ungueltig"),
    SPEICHER("speicher"),
    ZEIT("zeit"),
    FEHLER("fehler"),

    /** Samsungs Automatische Sperre („… unknown source package“, kommt als ABORTED). */
    SPERRE("sperre"),

    /** Ein Paket-Prüfer, meist Play Protect (Legacy -22/-21, kommt als ABORTED). */
    PRUEFUNG("pruefung"),

    /** Android hat die Sitzung selbst verworfen („Session was abandoned“, kommt als ABORTED). */
    VERWORFEN("verworfen"),

    /** Androids Rückfrage war gezeigt und blieb länger als SITZUNG_MS offen – die App hat verworfen. */
    ZU_LANGE("zu_lange"),
}

/** Was ein Knopf der Update-Karte tut. */
enum class UpdateKnopf(val text: String) {
    EINRICHTEN("Automatische Updates einrichten"),
    INSTALLIEREN("Neue Version installieren"),
    NOCHMAL("Noch einmal versuchen"),
    BROWSER("Im Browser herunterladen"),
    WARTEN("Wird installiert …"),

    /** Samsungs Seite der Automatischen Sperre (sonst Sicherheit, sonst Einstellungen – sperreAbsichten). */
    SPERRE("Automatische Sperre öffnen"),
}

/**
 * Die Karte: `knopf` vorn, `zweiter` leise darunter; `diagnose` ist die kleine
 * Zeile mit Androids Meldung (nur bei Fehlschlägen, die Android gemeldet hat).
 */
data class UpdateAnzeige(
    val titel: String,
    val text: String,
    val knopf: UpdateKnopf?,
    val zweiter: UpdateKnopf? = null,
    val diagnose: String? = null,
)

/**
 * Was Android zu einem Fehlschlag gemeldet hat, gesäubert (UpdateRegeln.meldungSaeubern):
 * EXTRA_STATUS_MESSAGE, EXTRA_LEGACY_STATUS, EXTRA_OTHER_PACKAGE_NAME; dazu, ob der
 * Versuch vom Knopf kam und ob Androids Rückfrage gezeigt wurde. Bis v7 ging das verloren.
 */
data class UpdateDiagnose(
    val meldung: String?,
    /** PackageManager.INSTALL_FAILED_… (0: keiner gemeldet). */
    val legacy: Int,
    val paket: String?,
    val vordergrund: Boolean,
    val rueckfrage: Boolean,
)

/** Alles, was die Update-Karte zeigt – gesammelt, damit `UpdateRegeln.anzeige` rein bleibt. */
data class UpdateLage(
    val eigene: Int,
    val eigeneName: String,
    val freigabe: Freigabe?,
    /** „Unbekannte Apps installieren“ für Bildschirmzeit erlaubt (canRequestPackageInstalls). */
    val darf: Boolean,
    /** Android 12+: ohne Rückfrage möglich (USER_ACTION_NOT_REQUIRED). */
    val still: Boolean,
    val laeuft: Boolean,
    val fehler: UpdateFehler?,
    val fehlerStatus: Int,
    /** Fehlschläge hintereinander (für den Hinweis im Widget). */
    val fehlversuche: Int = 0,
    val fehlerZeit: Long = 0L,
    val diagnose: UpdateDiagnose? = null,
    val erfolg: Int,
    val erfolgUm: Long,
    val geprueft: Long,
    val jetzt: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** Die Regeln fürs Update – rein, getestet in UpdateRegelnTest. */
object UpdateRegeln {
    /** GitHub höchstens alle 4 Stunden fragen (ohne Token 60 Anfragen je Stunde und Adresse – beim Mobilfunk teilen sich viele eine). */
    const val FRAGEN_MS = 4 * 3_600_000L

    /** Beim Öffnen der App höchstens alle 10 Minuten. */
    const val FRAGEN_APP_MS = 10 * 60_000L

    /**
     * Länger läuft keine Installation (0,75 MB in Sekunden); eine ältere Sitzung
     * wird verworfen. Gezählt ab commit, und wurde Androids Rückfrage gezeigt,
     * ab da – bis v7 ab dem Start des Laufs, vor dem Laden.
     */
    const val SITZUNG_MS = 10 * 60_000L

    private const val STUNDE = 3_600_000L

    /** So lang höchstens Androids Meldung auf der Karte (gesäubert, eine Zeile). */
    const val MELDUNG_ZEICHEN = 120

    /** Hier hilft Warten nicht – erst ein neues Release (oder Tobis Knopf). */
    private val ENDGUELTIG = setOf(UpdateFehler.FALSCHE_DATEI, UpdateFehler.KONFLIKT, UpdateFehler.INKOMPATIBEL)

    /**
     * Nicht noch einmal aus dem Hintergrund bis zum nächsten Release: das Endgültige und
     * Samsungs Sperre (deren Fenster „Unbekannte App gesperrt“ käme sonst ungefragt).
     */
    private val NICHT_IM_HINTERGRUND = ENDGUELTIG + UpdateFehler.SPERRE

    /**
     * Hier kommt das Update ohne Tobi nicht an: Rückfrage, abgebrochen, blockiert, Sperre,
     * Prüfer, Fenster zu lange offen – und das Endgültige. (Fehlt die Erlaubnis, sagt es
     * `darf`; ein altes „nicht erlaubt“ bei erteilter Erlaubnis holt der Hintergrund nach.)
     */
    private val BRAUCHT_TOBI = ENDGUELTIG + setOf(
        UpdateFehler.BESTAETIGEN,
        UpdateFehler.ABGEBROCHEN,
        UpdateFehler.BLOCKIERT,
        UpdateFehler.SPERRE,
        UpdateFehler.PRUEFUNG,
        UpdateFehler.ZU_LANGE,
    )

    private const val LEGACY_PRUEFER_ABGELEHNT = -22 // PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE
    private const val LEGACY_PRUEFER_ZEIT = -21 // PackageManager.INSTALL_FAILED_VERIFICATION_TIMEOUT

    /** AOSP, wenn Androids Rückfrage ohne „Aktualisieren“ endet (PackageInstallerSession.setPermissionsResult). */
    private const val ANDROID_ABGELEHNT = "User rejected permissions"

    /** So oft hintereinander gescheitert (auch an etwas, das Warten sonst behebt): dann doch Bescheid sagen. */
    const val HINWEIS_NACH_FEHLVERSUCHEN = 3

    fun githubFaellig(geprueft: Long, jetzt: Long, abstand: Long): Boolean = Takt.seit(geprueft, jetzt) >= abstand

    /** Ist `f` neuer als die installierte Fassung? Gleich alt oder älter: nie. */
    fun neuer(f: Freigabe?, eigene: Int): Boolean = f != null && f.laufnummer > eigene

    /** Stimmt die geladene Datei mit GitHub überein (SHA-256 und Größe)? */
    fun pruefsummeStimmt(erwartet: String?, geladen: String?, laenge: Long, erwarteteLaenge: Long): Boolean =
        erwartet != null && geladen != null && erwartet.equals(geladen, ignoreCase = true) && laenge == erwarteteLaenge

    /**
     * Die Datei selbst (PackageManager.getPackageArchiveInfo): unser Paket, genau
     * die Laufnummer des Release und neuer als die installierte. null = in Ordnung.
     */
    fun archivPruefen(eigenesPaket: String, eigeneFassung: Long, erwartet: Int, paket: String?, fassung: Long?): UpdateFehler? =
        if (paket != eigenesPaket || fassung == null || fassung <= eigeneFassung || fassung != erwartet.toLong()) UpdateFehler.FALSCHE_DATEI else null

    /** Kein Endlosversuch: 1, 2, 4, 8, 16, dann 24 Stunden Pause nach dem n-ten Fehlschlag. */
    fun wartezeit(fehlversuche: Int): Long =
        if (fehlversuche <= 0) 0L else minOf(24 * STUNDE, STUNDE shl minOf(fehlversuche - 1, 5))

    /** Darf es der Hintergrund (wieder) versuchen? Der Knopf in der App darf immer. */
    fun hintergrundDarf(fehler: UpdateFehler?, fehlversuche: Int, fehlerZeit: Long, jetzt: Long): Boolean =
        fehler !in NICHT_IM_HINTERGRUND && Takt.seit(fehlerZeit, jetzt) >= wartezeit(fehlversuche)

    /**
     * Androids Status aus der Installer-Sitzung, mit Androids Meldung und dem
     * Legacy-Code. ABORTED (3) heißt nicht nur „Tobi hat abgebrochen“: So kommen
     * auch Samsungs Automatische Sperre (-115, „Self update is blocked by unknown
     * source package“ ohne Fenster, „User rejected installing unknown source
     * package“ nach dessen Fenster „Unbekannte App gesperrt“), Paket-Prüfer wie
     * Play Protect (-22/-21) und eine von Android verworfene Sitzung. BLOCKED (2)
     * gibt es bei Installationen in AOSP nur für PRE_APPROVAL_NOT_AVAILABLE – mit
     * der Sperre hat es nichts zu tun (bis v7 stand das so auf der Karte).
     */
    @SuppressLint("InlinedApi") // STATUS_FAILURE_TIMEOUT gibt es ab Android 14; vorher kommt der Wert nie.
    fun fehlerAus(status: Int, legacy: Int = 0, meldung: String? = null): UpdateFehler {
        val m = meldung.orEmpty()
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (m.contains("unknown source", ignoreCase = true)) return UpdateFehler.SPERRE
            if (legacy == LEGACY_PRUEFER_ABGELEHNT || legacy == LEGACY_PRUEFER_ZEIT || m.contains("INSTALL_FAILED_VERIFICATION")) return UpdateFehler.PRUEFUNG
        }
        return when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> UpdateFehler.BESTAETIGEN
            PackageInstaller.STATUS_FAILURE_ABORTED -> if (m.contains("Session was abandoned")) UpdateFehler.VERWORFEN else UpdateFehler.ABGEBROCHEN
            PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateFehler.BLOCKIERT
            PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateFehler.KONFLIKT
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateFehler.INKOMPATIBEL
            PackageInstaller.STATUS_FAILURE_INVALID -> UpdateFehler.UNGUELTIG
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateFehler.SPEICHER
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> UpdateFehler.ZEIT
            else -> UpdateFehler.FEHLER
        }
    }

    private val PFAD = Regex("""(?:file|content):[^\s)\]"']*|(?<![\w.:])/[^\s)\]"']+""")
    private val LEER = Regex("""\s+""")

    /**
     * Androids Meldung für Karte und Log: ohne Pfade und Adressen (file:…,
     * content://…, /data/… und jeder andere absolute Pfad werden „…“), ohne
     * Steuerzeichen, eine Zeile, höchstens MELDUNG_ZEICHEN Zeichen. null, wenn
     * nichts bleibt.
     */
    fun meldungSaeubern(roh: String?): String? {
        if (roh.isNullOrBlank()) return null
        val ohnePfad = PFAD.replace(roh, "…")
        val zeile = LEER.replace(ohnePfad.map { if (it.isISOControl()) ' ' else it }.joinToString(""), " ").trim()
        if (zeile.isEmpty()) return null
        return if (zeile.length <= MELDUNG_ZEICHEN) zeile else zeile.take(MELDUNG_ZEICHEN - 1).trimEnd() + "…"
    }

    /** Ein Paketname (EXTRA_OTHER_PACKAGE_NAME) – nur, was wie einer aussieht. */
    fun paketSaeubern(roh: String?): String? =
        roh?.trim()?.takeIf { it.length in 1..100 && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' } }

    /**
     * Die kleine Zeile auf der Fehler-Karte (und im Log), etwa „Android:
     * INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package ·
     * 15:12 · Hintergrund · ohne Rückfrage“. Ohne Meldung Status und Legacy-Code.
     */
    fun diagnoseText(d: UpdateDiagnose, status: Int, um: Long, jetzt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val was = d.meldung ?: ("Status $status" + (if (d.legacy != 0) " (${d.legacy})" else "") + ", ohne Meldung")
        val teile = mutableListOf("Android: $was")
        d.paket?.let { teile += "Paket $it" }
        if (um > 0) teile += zeitpunktText(um, jetzt, zone)
        teile += if (d.vordergrund) "Knopf" else "Hintergrund"
        teile += if (d.rueckfrage) "mit Rückfrage" else "ohne Rückfrage"
        return teile.joinToString(" · ")
    }

    private fun diagnoseZeile(l: UpdateLage): String? = l.diagnose?.let { diagnoseText(it, l.fehlerStatus, l.fehlerZeit, l.jetzt, l.zone) }

    /**
     * Was schiefging und was Tobi tun kann. `meldung`: Androids Meldung – bei
     * ABGEBROCHEN sagt „User rejected permissions“, dass Androids Fenster ohne
     * „Aktualisieren“ zuging; ohne sie nennt der Text beide Wege.
     */
    fun fehlerText(f: UpdateFehler, status: Int, meldung: String? = null): String = when (f) {
        UpdateFehler.KEIN_NETZ -> "Kein Netz (oder GitHub nicht erreicht). Die App versucht es später von selbst noch einmal."
        UpdateFehler.PRUEFSUMME -> "Die geladene Datei passt nicht zur Prüfsumme bei GitHub – nichts installiert. Die App lädt sie später neu."
        UpdateFehler.FALSCHE_DATEI -> "Die geladene Datei ist keine neuere Bildschirmzeit – nichts installiert. Bitte einen Screenshot an Claude."
        UpdateFehler.NICHT_ERLAUBT -> "Bildschirmzeit darf keine Apps installieren: „Automatische Updates einrichten“ → Schalter „Diese Quelle zulassen“ ein → zurück."
        UpdateFehler.BESTAETIGEN -> "Android will dieses Update einmal bestätigt haben: „Neue Version installieren“ → „Aktualisieren“."
        UpdateFehler.ABGEBROCHEN -> if (meldung?.contains(ANDROID_ABGELEHNT) == true) {
            "Androids Fenster ging ohne „Aktualisieren“ zu – nichts installiert. „Noch einmal versuchen“ → „Aktualisieren“."
        } else {
            "Abgebrochen – nichts installiert. Hast du ein Android-Fenster geschlossen: „Noch einmal versuchen“ → „Aktualisieren“. " +
                "Kam keins oder „Unbekannte App gesperrt“: Automatische Sperre kurz aus (Einstellungen → Sicherheit und Datenschutz → " +
                "Automatische Sperre), dann „Noch einmal versuchen“, danach wieder an."
        }
        UpdateFehler.BLOCKIERT -> "Android hat die Installation blockiert (Status $status) – nichts installiert. „Noch einmal versuchen“; bleibt es dabei, bitte einen Screenshot an Claude."
        UpdateFehler.SPERRE -> "Samsungs Automatische Sperre hat das Update verhindert – sie lässt nur Apps aus Play Store und Galaxy Store zu. " +
            "„Automatische Sperre öffnen“ (Einstellungen → Sicherheit und Datenschutz → Automatische Sperre) → aus → zurück → " +
            "„Noch einmal versuchen“ (kommt ein Android-Fenster: „Aktualisieren“). Danach kann die Sperre wieder an."
        UpdateFehler.PRUEFUNG -> "Ein Paket-Prüfer (meist Google Play Protect) hat das Update abgelehnt oder nicht rechtzeitig geprüft – nichts installiert. " +
            "„Noch einmal versuchen“; kam eine Warnung von Play Protect oder bleibt es dabei, bitte einen Screenshot an Claude."
        UpdateFehler.VERWORFEN -> "Android hat die Installation verworfen, bevor sie fertig war – nichts installiert. Die App versucht es später von selbst noch einmal (oder jetzt „Noch einmal versuchen“)."
        UpdateFehler.ZU_LANGE -> "Android-Fenster zu lange offen – noch einmal versuchen. Nichts installiert; nach „Noch einmal versuchen“ im Android-Fenster „Aktualisieren“ antippen."
        UpdateFehler.KONFLIKT -> "Android lehnt die neue Fassung ab (sie passt nicht zur installierten, etwa eine andere Signatur; Status $status). Nichts geändert – bitte einen Screenshot an Claude."
        UpdateFehler.INKOMPATIBEL -> "Die neue Fassung passt nicht zu diesem Handy (Status $status). Nichts geändert – bitte einen Screenshot an Claude."
        UpdateFehler.UNGUELTIG -> "Die geladene Datei ist beschädigt. Die App lädt sie später neu."
        UpdateFehler.SPEICHER -> "Zu wenig freier Speicher. Etwas Platz schaffen – die App versucht es später von selbst noch einmal."
        UpdateFehler.ZEIT -> "Android hat zu lange gebraucht. Die App versucht es später von selbst noch einmal."
        UpdateFehler.FEHLER -> "Die Installation ist fehlgeschlagen (Status $status). Die App versucht es später noch einmal; bleibt es dabei, bitte einen Screenshot an Claude."
    }

    private const val EINRICHTEN_TEXT = "Neue Versionen kommen von selbst, wenn Bildschirmzeit Apps installieren darf – " +
        "sie installiert damit nur sich selbst, aus den Releases auf GitHub. Einmal: „Automatische Updates einrichten“ → " +
        "Schalter „Diese Quelle zulassen“ ein → zurück."

    /**
     * Soll das Widget „Update: antippen“ zeigen? Benachrichtigungen hat die App
     * nicht, und wer die App nie öffnet, sieht die Karte nicht. Nur, wenn eine
     * neuere Fassung da ist, die ohne Tobi nicht ankommt: nicht eingerichtet,
     * keine Prüfsumme bei GitHub, unter Android 12, ein Fehler, den Warten nicht
     * behebt, oder drei Fehlschläge hintereinander. Nicht, solange installiert
     * wird, und nicht, solange der Hintergrund es noch von selbst schafft.
     */
    fun widgetHinweis(l: UpdateLage): Boolean {
        val neu = l.freigabe?.takeIf { neuer(it, l.eigene) } ?: return false
        if (l.laeuft) return false
        return !l.darf || !neu.installierbar || !l.still || l.fehler in BRAUCHT_TOBI || l.fehlversuche >= HINWEIS_NACH_FEHLVERSUCHEN
    }

    /**
     * Zeigt die Karte gerade Samsungs Sperre (siehe `anzeige`), sagt das Widget
     * „Update blockiert“ statt „Update: antippen“.
     */
    fun widgetGesperrt(l: UpdateLage): Boolean =
        l.fehler == UpdateFehler.SPERRE && !l.laeuft && l.darf && l.freigabe?.takeIf { neuer(it, l.eigene) }?.installierbar == true

    /** Die Update-Karte der App. */
    fun anzeige(l: UpdateLage): UpdateAnzeige {
        val neu = l.freigabe?.takeIf { neuer(it, l.eigene) }
        val ist = neu?.let { "Version 1.${it.laufnummer} ist da (du hast ${l.eigeneName}). " }.orEmpty()
        return when {
            l.laeuft -> UpdateAnzeige(
                "Neue Version wird installiert",
                "${neu?.let { "Version 1.${it.laufnummer}" } ?: "Die neue Version"} wird geladen und installiert. " +
                    "Die App schließt sich dabei kurz – danach einfach wieder öffnen.",
                UpdateKnopf.WARTEN,
            )
            !l.darf -> UpdateAnzeige(if (neu != null) "Neue Version verfügbar" else "Automatische Updates", ist + EINRICHTEN_TEXT, UpdateKnopf.EINRICHTEN)
            neu == null -> UpdateAnzeige("Automatische Updates ✓", anText(l), null)
            !neu.installierbar -> UpdateAnzeige(
                "Neue Version verfügbar",
                ist + "GitHub nennt dafür keine Prüfsumme – deshalb nicht von selbst. „Im Browser herunterladen“, dann die Datei öffnen und „Aktualisieren“.",
                UpdateKnopf.BROWSER,
            )
            l.fehler == UpdateFehler.BESTAETIGEN -> UpdateAnzeige(
                "Neue Version verfügbar",
                ist + "Android will dieses Update einmal bestätigt haben: „Neue Version installieren“ → „Aktualisieren“. Die App schließt sich dabei kurz.",
                UpdateKnopf.INSTALLIEREN,
            )
            // Erst die Seite der Sperre, „Noch einmal versuchen“ bleibt darunter.
            l.fehler == UpdateFehler.SPERRE -> UpdateAnzeige(
                "Update blockiert",
                "Version 1.${neu.laufnummer}: " + fehlerText(l.fehler, l.fehlerStatus),
                UpdateKnopf.SPERRE,
                zweiter = UpdateKnopf.NOCHMAL,
                diagnose = diagnoseZeile(l),
            )
            l.fehler != null -> UpdateAnzeige(
                "Update hat nicht geklappt",
                "Version 1.${neu.laufnummer}: " + fehlerText(l.fehler, l.fehlerStatus, l.diagnose?.meldung),
                UpdateKnopf.NOCHMAL,
                diagnose = diagnoseZeile(l),
            )
            !l.still -> UpdateAnzeige("Neue Version verfügbar", ist + "„Neue Version installieren“, dann „Aktualisieren“ – deine Einstellungen bleiben.", UpdateKnopf.INSTALLIEREN)
            else -> UpdateAnzeige(
                "Neue Version verfügbar",
                ist + "Sie kommt in den nächsten Stunden von selbst – oder jetzt. Die App schließt sich dabei kurz; Widget und Verbindungen bleiben.",
                UpdateKnopf.INSTALLIEREN,
            )
        }
    }

    private fun anText(l: UpdateLage): String {
        val teile = mutableListOf("Neue Versionen installiert die App von selbst – sie fragt GitHub alle 4 Stunden.")
        if (l.erfolg > 0 && l.erfolg == l.eigene && l.erfolgUm > 0) {
            teile += "Zuletzt aktualisiert: ${zeitpunktText(l.erfolgUm, l.jetzt, l.zone)} auf ${l.eigeneName}."
        }
        if (l.geprueft > 0) teile += "Zuletzt nachgesehen: ${zeitpunktText(l.geprueft, l.jetzt, l.zone)}."
        return teile.joinToString(" ")
    }
}

/** Der Stand des Updates (eigene SharedPreferences, nicht in der Sicherung – datenregeln.xml). */
@SuppressLint("ApplySharedPref") // commit: Gleich nach dem Start der Installation beendet Android den Prozess.
internal class UpdateSpeicher(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("selbstupdate", Context.MODE_PRIVATE)

    var geprueft: Long
        get() = p.getLong("geprueft", 0L)
        set(wert) = p.edit(commit = true) { putLong("geprueft", wert) }

    var freigabe: Freigabe?
        get() = NeueVersion.ausJson(p.getString("freigabe", null))
        set(wert) = p.edit(commit = true) { if (wert == null) remove("freigabe") else putString("freigabe", NeueVersion.alsJson(wert)) }

    val sitzung: Int get() = p.getInt("sitzung", -1)

    /** Ab hier zählt SITZUNG_MS: commit (vorher createSession). */
    val sitzungSeit: Long get() = p.getLong("sitzung_seit", 0L)
    val ziel: Int get() = p.getInt("ziel", 0)

    /** Wann Androids Rückfrage zu dieser Sitzung gezeigt wurde (0: nicht). */
    val rueckfrageUm: Long get() = p.getLong("sitzung_rueckfrage", 0L)

    fun sitzungMerken(id: Int, seit: Long, ziel: Int) =
        p.edit(commit = true) { putInt("sitzung", id).putLong("sitzung_seit", seit).putInt("ziel", ziel).remove("sitzung_rueckfrage") }

    /** commit ist durch: Die Frist zählt ab jetzt (nur, wenn es noch dieselbe Sitzung ist – Androids Antwort kann schneller sein). */
    fun commitMerken(id: Int, um: Long) {
        if (sitzung == id) p.edit(commit = true) { putLong("sitzung_seit", um) }
    }

    fun rueckfrageMerken(id: Int, um: Long) {
        if (sitzung == id) p.edit(commit = true) { putLong("sitzung_rueckfrage", um) }
    }

    fun sitzungVergessen() = p.edit(commit = true) { remove("sitzung").remove("sitzung_seit").remove("sitzung_rueckfrage") }

    val fehler: UpdateFehler? get() = p.getString("fehler", null)?.let { c -> UpdateFehler.entries.firstOrNull { it.code == c } }
    val fehlerStatus: Int get() = p.getInt("fehler_status", 0)
    val fehlerZeit: Long get() = p.getLong("fehler_zeit", 0L)
    val fehlversuche: Int get() = p.getInt("fehlversuche", 0)

    /** Androids Meldung zum gemerkten Fehler (nur, wenn er aus Androids Status kam). */
    val diagnose: UpdateDiagnose?
        get() = if (!p.contains("fehler_quelle")) {
            null
        } else {
            UpdateDiagnose(
                meldung = p.getString("fehler_meldung", null),
                legacy = p.getInt("fehler_legacy", 0),
                paket = p.getString("fehler_paket", null),
                vordergrund = p.getString("fehler_quelle", null) == QUELLE_KNOPF,
                rueckfrage = p.getBoolean("fehler_rueckfrage", false),
            )
        }

    fun fehlerMerken(f: UpdateFehler, status: Int, jetzt: Long, diagnose: UpdateDiagnose? = null) = p.edit(commit = true) {
        putString("fehler", f.code).putInt("fehler_status", status).putLong("fehler_zeit", jetzt).putInt("fehlversuche", fehlversuche + 1)
        diagnoseWeg(this)
        if (diagnose != null) {
            putString("fehler_quelle", if (diagnose.vordergrund) QUELLE_KNOPF else QUELLE_HINTERGRUND)
            putInt("fehler_legacy", diagnose.legacy)
            putBoolean("fehler_rueckfrage", diagnose.rueckfrage)
            diagnose.meldung?.let { putString("fehler_meldung", it) }
            diagnose.paket?.let { putString("fehler_paket", it) }
        }
    }

    fun fehlerVergessen() = p.edit(commit = true) {
        remove("fehler").remove("fehler_status").remove("fehler_zeit").remove("fehlversuche")
        diagnoseWeg(this)
    }

    val erfolg: Int get() = p.getInt("erfolg", 0)
    val erfolgUm: Long get() = p.getLong("erfolg_um", 0L)

    fun erfolgMerken(fassung: Int, um: Long) = p.edit(commit = true) {
        putInt("erfolg", fassung).putLong("erfolg_um", um).remove("ziel").remove("sitzung").remove("sitzung_seit").remove("sitzung_rueckfrage")
            .remove("fehler").remove("fehler_status").remove("fehler_zeit").remove("fehlversuche")
        diagnoseWeg(this)
    }

    private fun diagnoseWeg(e: SharedPreferences.Editor) {
        e.remove("fehler_quelle").remove("fehler_meldung").remove("fehler_legacy").remove("fehler_paket").remove("fehler_rueckfrage")
    }

    private companion object {
        const val QUELLE_KNOPF = "knopf"
        const val QUELLE_HINTERGRUND = "hintergrund"
    }
}

/** Die App, solange sie vorn ist: zeichnet die Karte neu und darf Androids Rückfrage zeigen. */
interface UpdateBeobachter {
    fun updateGeaendert()

    /** true, wenn der Dialog gestartet wurde. */
    fun bestaetigen(absicht: Intent): Boolean
}

object Selbstupdate {
    private const val CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"

    /** PackageInstaller.EXTRA_LEGACY_STATUS – @SystemApi, deshalb als Text; Android legt es jedem Status bei. */
    internal const val EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"
    private const val ORDNER = "update"
    private val laeuft = AtomicBoolean(false)

    /** Die App, solange sie vorn ist (onResume … onPause). */
    @Volatile
    var beobachter: UpdateBeobachter? = null

    /** GitHub fragen – in Tests ersetzt (Netz.gesperrt gilt ohnehin). */
    @Volatile
    internal var fragen: () -> String = { Netz.anfrage(NeueVersion.API, "GET", NeueVersion.KOEPFE) }

    /** Die APK laden, Rückgabe ihr SHA-256 – in Tests ersetzt. */
    @Volatile
    internal var laden: (String, File, Long) -> String = { adresse, ziel, hoechstens -> Netz.laden(adresse, ziel, hoechstens) }

    fun darf(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun lage(context: Context, jetzt: Long = System.currentTimeMillis()): UpdateLage {
        val s = UpdateSpeicher(context)
        return lageAus(context, s, jetzt, laeuftGerade(context, s, jetzt))
    }

    private fun lageAus(context: Context, s: UpdateSpeicher, jetzt: Long, installiertGerade: Boolean) = UpdateLage(
        eigene = BuildConfig.VERSION_CODE,
        eigeneName = BuildConfig.VERSION_NAME,
        freigabe = s.freigabe,
        darf = darf(context),
        still = Build.VERSION.SDK_INT >= 31,
        laeuft = installiertGerade,
        fehler = s.fehler,
        fehlerStatus = s.fehlerStatus,
        fehlversuche = s.fehlversuche,
        fehlerZeit = s.fehlerZeit,
        diagnose = s.diagnose,
        erfolg = s.erfolg,
        erfolgUm = s.erfolgUm,
        geprueft = s.geprueft,
        jetzt = jetzt,
    )

    /**
     * Fürs Widget (bei jedem Zeichnen): wartet ein Update auf Tobi? Nur lesen –
     * hier wird keine Sitzung verworfen und nichts gefragt.
     */
    fun widgetHinweis(context: Context, jetzt: Long = System.currentTimeMillis()): Boolean =
        widgetLage(context, jetzt)?.let { UpdateRegeln.widgetHinweis(it) } ?: false

    /** Wie widgetHinweis, für beides auf einmal (WidgetAnsicht): null, wenn keine neuere Fassung bekannt ist. */
    fun widgetLage(context: Context, jetzt: Long = System.currentTimeMillis()): UpdateLage? {
        val s = UpdateSpeicher(context)
        if (!UpdateRegeln.neuer(s.freigabe, BuildConfig.VERSION_CODE)) return null
        return lageAus(context, s, jetzt, installiertGerade = laeuft.get() || s.sitzung >= 0)
    }

    /** Beim Öffnen der App: GitHub höchstens alle 10 Minuten. */
    fun pruefenFuerApp(context: Context, jetzt: Long = System.currentTimeMillis()) {
        val s = UpdateSpeicher(context)
        if (UpdateRegeln.githubFaellig(s.geprueft, jetzt, UpdateRegeln.FRAGEN_APP_MS)) fragen(s, jetzt)
    }

    /**
     * Der WorkManager-Lauf: GitHub höchstens alle 4 Stunden; ist eine neuere
     * Fassung da und alles eingerichtet, ohne Rückfrage installieren. Nicht,
     * solange die App vorn ist (die Installation schließt sie), nicht während
     * einer laufenden Installation, nicht vor Ablauf der Wartezeit nach einem
     * Fehler. Unter Android 12 fragte Android jedes Mal – dann nur der Knopf.
     */
    fun imHintergrund(context: Context, jetzt: Long = System.currentTimeMillis()) {
        val s = UpdateSpeicher(context)
        if (UpdateRegeln.githubFaellig(s.geprueft, jetzt, UpdateRegeln.FRAGEN_MS)) fragen(s, jetzt)
        val f = s.freigabe ?: return
        if (!UpdateRegeln.neuer(f, BuildConfig.VERSION_CODE) || !f.installierbar) return
        if (Build.VERSION.SDK_INT < 31 || beobachter != null || !darf(context)) return
        if (laeuftGerade(context, s, jetzt)) return
        if (!UpdateRegeln.hintergrundDarf(s.fehler, s.fehlversuche, s.fehlerZeit, jetzt)) return
        installieren(context, s, f, vordergrund = false, jetzt = jetzt)
    }

    /** Tobis Knopf „Neue Version installieren“: sofort, ohne Wartezeit; Androids Rückfrage darf kommen. */
    fun jetztInstallieren(context: Context, jetzt: Long = System.currentTimeMillis()) {
        val s = UpdateSpeicher(context)
        if (laeuftGerade(context, s, jetzt)) return
        val f = s.freigabe?.takeIf { UpdateRegeln.neuer(it, BuildConfig.VERSION_CODE) } ?: run {
            fragen(s, jetzt)
            s.freigabe
        }
        if (f == null || !UpdateRegeln.neuer(f, BuildConfig.VERSION_CODE) || !f.installierbar) return melden()
        if (!darf(context)) {
            s.fehlerMerken(UpdateFehler.NICHT_ERLAUBT, 0, jetzt)
            return melden()
        }
        installieren(context, s, f, vordergrund = true, jetzt = jetzt)
    }

    private fun fragen(s: UpdateSpeicher, jetzt: Long) {
        s.geprueft = jetzt
        val f = try {
            NeueVersion.freigabeAus(fragen())
        } catch (e: IOException) {
            Log.i(LOG, "GitHub nicht erreicht: ${e.javaClass.simpleName}")
            null
        } ?: return
        // Ein neues Release, ein neuer Versuch: Fehler und Wartezeit der alten Fassung gelten nicht mehr.
        if (f.laufnummer != s.freigabe?.laufnummer) s.fehlerVergessen()
        s.freigabe = f
    }

    /**
     * Läuft gerade ein Update (hier oder eine Sitzung bei Android)? Alte Sitzungen
     * werden verworfen: SITZUNG_MS nach commit, und war Androids Rückfrage gezeigt,
     * nach deren Zeigen – dann sagt die Karte „Android-Fenster zu lange offen“
     * (bis v7 hieß es danach nur „kommt von selbst“).
     */
    private fun laeuftGerade(context: Context, s: UpdateSpeicher, jetzt: Long): Boolean {
        if (laeuft.get()) return true
        val id = s.sitzung
        if (id < 0) return false
        val installer = context.packageManager.packageInstaller
        val info = try {
            installer.getSessionInfo(id)
        } catch (e: SecurityException) {
            null
        }
        val rueckfrage = s.rueckfrageUm
        if (info != null && Takt.seit(maxOf(s.sitzungSeit, rueckfrage), jetzt) <= UpdateRegeln.SITZUNG_MS) return true
        // Erst vergessen, dann verwerfen: Androids „abgebrochen“ zu dieser Sitzung zählt dann nicht.
        s.sitzungVergessen()
        if (info != null) {
            verwerfen(context, id)
            if (rueckfrage > 0) {
                Log.i(LOG, "Update: Android-Fenster zu lange offen, Sitzung verworfen")
                s.fehlerMerken(UpdateFehler.ZU_LANGE, 0, jetzt)
            }
        }
        return false
    }

    private fun installieren(context: Context, s: UpdateSpeicher, f: Freigabe, vordergrund: Boolean, jetzt: Long) {
        if (!laeuft.compareAndSet(false, true)) return
        // Die Uhr dieses Laufs: `jetzt` plus die Zeit seither (Laden kann dauern) – für die Frist ab commit.
        val beginn = SystemClock.elapsedRealtime()
        val uhr = { jetzt + (SystemClock.elapsedRealtime() - beginn) }
        try {
            melden()
            val datei = geladen(context, s, f, jetzt) ?: return
            val info = archiv(context, datei)
            val fehler = UpdateRegeln.archivPruefen(context.packageName, BuildConfig.VERSION_CODE.toLong(), f.laufnummer, info?.first, info?.second)
            if (fehler != null) {
                datei.delete()
                s.fehlerMerken(fehler, 0, jetzt)
                return
            }
            try {
                sitzungStarten(context, s, datei, f.laufnummer, vordergrund, uhr)
            } catch (e: IOException) {
                s.fehlerMerken(UpdateFehler.SPEICHER, 0, jetzt)
                return
            } catch (e: SecurityException) {
                s.fehlerMerken(UpdateFehler.NICHT_ERLAUBT, 0, jetzt)
                return
            }
            Log.i(LOG, "Update auf 1.${f.laufnummer} gestartet")
        } finally {
            laeuft.set(false)
            melden()
        }
    }

    /** Die geprüfte APK im eigenen Speicher – schon geladen und noch gültig, sonst neu laden. */
    private fun geladen(context: Context, s: UpdateSpeicher, f: Freigabe, jetzt: Long): File? {
        val datei = datei(context, f.laufnummer)
        val ordner = datei.parentFile!!
        ordner.mkdirs()
        ordner.listFiles()?.forEach { if (it != datei) it.delete() }
        if (datei.isFile && UpdateRegeln.pruefsummeStimmt(f.sha256, sha256(datei), datei.length(), f.groesse)) return datei
        datei.delete()
        val teil = File(ordner, "laden.tmp")
        val geladen = try {
            laden(f.adresse!!, teil, NeueVersion.HOECHSTENS_BYTES)
        } catch (e: IOException) {
            Log.i(LOG, "APK nicht geladen: ${e.javaClass.simpleName}")
            teil.delete()
            s.fehlerMerken(UpdateFehler.KEIN_NETZ, 0, jetzt)
            return null
        }
        if (!UpdateRegeln.pruefsummeStimmt(f.sha256, geladen, teil.length(), f.groesse) || !teil.renameTo(datei)) {
            teil.delete()
            s.fehlerMerken(UpdateFehler.PRUEFSUMME, 0, jetzt)
            return null
        }
        return datei
    }

    internal fun datei(context: Context, laufnummer: Int): File = File(File(context.noBackupFilesDir, ORDNER), "bildschirmzeit-$laufnummer.apk")

    /** Paketname und versionCode der Datei (null: keine lesbare APK). */
    private fun archiv(context: Context, datei: File): Pair<String, Long>? {
        @Suppress("DEPRECATION") // Die Fassung mit PackageInfoFlags ruft ab Android 13 dasselbe.
        val info = context.packageManager.getPackageArchiveInfo(datei.path, 0) ?: return null
        return info.packageName to info.longVersionCode
    }

    /**
     * Nur das eigene Paket, ab Android 12 ohne Rückfrage, wo Android es zulässt.
     * Kein setRequestUpdateOwnership: Laut AOSP wirkt es nur bei der
     * Erstinstallation („on package update is a no-op“), und für ein
     * Selbst-Update braucht es keine Update-Hoheit.
     */
    internal fun sitzungsParameter(paket: String): PackageInstaller.SessionParams =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(paket)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

    /**
     * Die Sitzung wird gleich nach createSession gemerkt, vor commit: Androids
     * Antwort (Rückfrage, blockiert …) kann kommen, bevor commit zurückkehrt,
     * und statusErhalten nimmt nur Meldungen zur gemerkten Sitzung an.
     *
     * Gleich danach, noch vor commit, die Widgets neu zeichnen: Ihr Antippen
     * öffnet die App dann ohne CLEAR_TOP (WidgetAnsicht.oeffnen) – ein vorher
     * gezeichnetes Widget behielte es und räumte Androids Rückfrage weg.
     */
    private fun sitzungStarten(context: Context, s: UpdateSpeicher, datei: File, ziel: Int, vordergrund: Boolean, uhr: () -> Long) {
        val installer = context.packageManager.packageInstaller
        // Reste früherer Versuche (Prozess beendet, bevor die Sitzung fertig war). Ihr „abgebrochen“ zählt nicht (andere Nummer).
        for (alt in installer.mySessions) if (alt.appPackageName == context.packageName) verwerfen(context, alt.sessionId)
        val id = installer.createSession(sitzungsParameter(context.packageName))
        s.sitzungMerken(id, uhr(), ziel)
        widgetsNeu(context)
        try {
            installer.openSession(id).use { sitzung ->
                sitzung.openWrite("bildschirmzeit.apk", 0, datei.length()).use { aus ->
                    datei.inputStream().use { it.copyTo(aus) }
                    sitzung.fsync(aus)
                }
                sitzung.commit(statusAbsender(context, id, vordergrund).intentSender)
            }
            s.commitMerken(id, uhr())
        } catch (e: IOException) {
            s.sitzungVergessen()
            verwerfen(context, id)
            throw e
        } catch (e: RuntimeException) {
            s.sitzungVergessen()
            verwerfen(context, id)
            throw e
        }
    }

    /**
     * Wohin Android den Status schickt: explizit an den eigenen, nicht
     * exportierten Empfänger. FLAG_MUTABLE ist nötig, damit Android die Extras
     * (Status, Rückfrage) hineinschreiben kann (ab targetSdk 35 Pflicht).
     */
    internal fun statusAbsender(context: Context, id: Int, vordergrund: Boolean): PendingIntent {
        val absicht = Intent(context, UpdateStatus::class.java)
            .setAction(UpdateStatus.STATUS)
            .setPackage(context.packageName)
            .putExtra(UpdateStatus.VORDERGRUND, vordergrund)
        val veraenderbar = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, id, absicht, PendingIntent.FLAG_UPDATE_CURRENT or veraenderbar)
    }

    /**
     * Was Android über die Sitzung meldet – mit Androids Meldung, dem
     * Legacy-Code und einem anderen Paket (gesäubert; bis v7 nur der Status).
     * Gemerkt mit dem Fehler, gezeigt als kleine Zeile auf der Karte, dieselbe
     * Zeile im Log.
     */
    internal fun statusErhalten(context: Context, intent: Intent, jetzt: Long = System.currentTimeMillis()) {
        val s = UpdateSpeicher(context)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val gemerkt = id >= 0 && id == s.sitzung
        // Eingeordnet wird nach der ganzen Meldung; gemerkt, gezeigt und geloggt nur die gesäuberte.
        val meldung = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val diagnose = UpdateDiagnose(
            meldung = UpdateRegeln.meldungSaeubern(meldung),
            legacy = intent.getIntExtra(EXTRA_LEGACY_STATUS, 0),
            paket = UpdateRegeln.paketSaeubern(intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)),
            vordergrund = intent.getBooleanExtra(UpdateStatus.VORDERGRUND, false),
            rueckfrage = gemerkt && s.rueckfrageUm > 0,
        )
        Log.i(LOG, "Update: Status $status${if (gemerkt) "" else " (nicht die gemerkte Sitzung)"} – ${UpdateRegeln.diagnoseText(diagnose, status, jetzt, jetzt)}")
        // Nur die gemerkte Sitzung zählt (sonst nur ein Erfolg). Verwirft die App eine Sitzung selbst
        // (Rückfrage im Hintergrund, zu alt, Reste), schickt Android dazu noch „abgebrochen“
        // (AOSP PackageInstallerSession.abandon → dispatchSessionFinished(INSTALL_FAILED_ABORTED)) –
        // sie ist dann schon vergessen, und die Meldung überschreibt nicht den wahren Grund.
        if (status != PackageInstaller.STATUS_SUCCESS && !gemerkt) return
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                s.erfolgMerken(s.ziel.takeIf { it > 0 } ?: BuildConfig.VERSION_CODE, jetzt)
                aufraeumen(context)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Androids Rückfrage nur, wenn Tobi gerade auf „Installieren“ getippt hat und die App vorn ist.
                val rueckfrage = bestaetigungSicher(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
                val vorn = intent.getBooleanExtra(UpdateStatus.VORDERGRUND, false)
                val gezeigt = vorn && rueckfrage != null && beobachter?.bestaetigen(rueckfrage) == true
                if (gezeigt) {
                    // Die Frist zählt ab jetzt (Tobi liest und tippt), und der Ausgang weiß, dass gefragt wurde.
                    s.rueckfrageMerken(id, jetzt)
                } else {
                    s.sitzungVergessen()
                    verwerfen(context, id)
                    s.fehlerMerken(UpdateFehler.BESTAETIGEN, status, jetzt, diagnose)
                }
            }
            else -> {
                val fehler = UpdateRegeln.fehlerAus(status, diagnose.legacy, meldung)
                s.sitzungVergessen()
                s.fehlerMerken(fehler, status, jetzt, diagnose)
                if (fehler == UpdateFehler.UNGUELTIG) aufraeumen(context)
            }
        }
        melden()
    }

    /** Die Widgets ohne Netz neu zeichnen (Hinweis, Antippen); scheitert das, geht das Update trotzdem weiter. */
    internal fun widgetsNeu(context: Context) {
        try {
            Aktualisierung.neuZeichnen(context)
        } catch (e: RuntimeException) {
            Log.w(LOG, "Widget nach dem Update-Schritt nicht gezeichnet: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Nur Androids eigene Rückfrage (Aktion CONFIRM_INSTALL an den
     * System-Installer), ohne Daten und ohne weitergereichte Dateirechte.
     */
    internal fun bestaetigungSicher(absicht: Intent?): Intent? {
        if (absicht == null || absicht.action != CONFIRM_INSTALL || absicht.data != null || absicht.selector != null) return null
        if ((absicht.`package` ?: absicht.component?.packageName).isNullOrEmpty()) return null
        val rechte = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        return Intent(absicht).apply {
            flags = flags and rechte.inv()
            clipData = null
        }
    }

    /** MY_PACKAGE_REPLACED: hat die eigene Installation geklappt? Danach die APK wegräumen. */
    fun nachUpdate(context: Context, jetzt: Long = System.currentTimeMillis()) {
        val s = UpdateSpeicher(context)
        val eigene = BuildConfig.VERSION_CODE
        if (s.ziel in 1..eigene) {
            s.erfolgMerken(eigene, jetzt)
        } else if (!UpdateRegeln.neuer(s.freigabe, eigene)) {
            // Von Hand aktualisiert: Alte Fehler gelten nicht mehr.
            s.fehlerVergessen()
            s.sitzungVergessen()
        }
        aufraeumen(context)
    }

    private fun aufraeumen(context: Context) {
        File(context.noBackupFilesDir, ORDNER).listFiles()?.forEach { it.delete() }
    }

    private fun verwerfen(context: Context, id: Int) {
        try {
            context.packageManager.packageInstaller.abandonSession(id)
        } catch (e: SecurityException) {
            // schon fertig oder nicht (mehr) unsere
        } catch (e: IllegalStateException) {
            // schon fertig
        }
    }

    private fun melden() {
        beobachter?.updateGeaendert()
    }
}

/**
 * Der Status der Installer-Sitzung. Nicht exportiert: Nur Android (über den
 * PendingIntent der App) und die App selbst erreichen ihn.
 */
class UpdateStatus : BroadcastReceiver() {
    companion object {
        const val STATUS = "de.finanzdienste.bildschirmzeit.UPDATE_STATUS"
        const val VORDERGRUND = "vordergrund"
    }

    /** Erst merken (schnell, hier), dann die Widgets im Hintergrund neu – Hinweis und Antippen passen gleich. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != STATUS) return
        val app = context.applicationContext
        Selbstupdate.statusErhalten(app, intent)
        imHintergrund { Selbstupdate.widgetsNeu(app) }
    }
}

/**
 * Samsungs Automatische Sperre (One UI 7 und 8: eigene App „rampart“; ob die
 * Aktion auf Tobis S24 die Seite öffnet, ist nicht geprüft) – sonst
 * „Sicherheit und Datenschutz“, sonst die Einstellungen.
 */
fun sperreAbsichten(): List<Intent> = listOf(
    Intent("com.samsung.android.rampart.action.MAIN_SETTING_ACTIVITY"),
    Intent(Settings.ACTION_SECURITY_SETTINGS),
    Intent(Settings.ACTION_SETTINGS),
)

/** „Unbekannte Apps installieren“ für diese App – sonst die Liste, sonst die App-Info. */
fun installQuelleAbsichten(paket: String): List<Intent> = listOf(
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$paket".toUri()),
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$paket".toUri()),
)
