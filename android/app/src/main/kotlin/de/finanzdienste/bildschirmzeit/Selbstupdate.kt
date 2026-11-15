package de.finanzdienste.bildschirmzeit

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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
}

/** Was der Knopf der Update-Karte tut. */
enum class UpdateKnopf(val text: String) {
    EINRICHTEN("Automatische Updates einrichten"),
    INSTALLIEREN("Neue Version installieren"),
    NOCHMAL("Noch einmal versuchen"),
    BROWSER("Im Browser herunterladen"),
    WARTEN("Wird installiert …"),
}

data class UpdateAnzeige(val titel: String, val text: String, val knopf: UpdateKnopf?)

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
     * wird verworfen – etwa, wenn Tobi Androids Rückfrage offen liegen ließ.
     */
    const val SITZUNG_MS = 10 * 60_000L

    private const val STUNDE = 3_600_000L

    /** Hier hilft Warten nicht – erst ein neues Release (oder Tobis Knopf). */
    private val ENDGUELTIG = setOf(UpdateFehler.FALSCHE_DATEI, UpdateFehler.KONFLIKT, UpdateFehler.INKOMPATIBEL)

    /**
     * Hier kommt das Update ohne Tobi nicht an: Rückfrage, abgebrochen, blockiert – und das Endgültige.
     * (Fehlt die Erlaubnis, sagt es `darf`; ein altes „nicht erlaubt“ bei erteilter Erlaubnis holt der Hintergrund nach.)
     */
    private val BRAUCHT_TOBI = ENDGUELTIG + setOf(UpdateFehler.BESTAETIGEN, UpdateFehler.ABGEBROCHEN, UpdateFehler.BLOCKIERT)

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
        fehler !in ENDGUELTIG && Takt.seit(fehlerZeit, jetzt) >= wartezeit(fehlversuche)

    /** Androids Status aus der Installer-Sitzung. */
    @SuppressLint("InlinedApi") // STATUS_FAILURE_TIMEOUT gibt es ab Android 14; vorher kommt der Wert nie.
    fun fehlerAusStatus(status: Int): UpdateFehler = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> UpdateFehler.BESTAETIGEN
        PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateFehler.ABGEBROCHEN
        PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateFehler.BLOCKIERT
        PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateFehler.KONFLIKT
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateFehler.INKOMPATIBEL
        PackageInstaller.STATUS_FAILURE_INVALID -> UpdateFehler.UNGUELTIG
        PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateFehler.SPEICHER
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> UpdateFehler.ZEIT
        else -> UpdateFehler.FEHLER
    }

    /** Was schiefging und was Tobi tun kann. */
    fun fehlerText(f: UpdateFehler, status: Int): String = when (f) {
        UpdateFehler.KEIN_NETZ -> "Kein Netz (oder GitHub nicht erreicht). Die App versucht es später von selbst noch einmal."
        UpdateFehler.PRUEFSUMME -> "Die geladene Datei passt nicht zur Prüfsumme bei GitHub – nichts installiert. Die App lädt sie später neu."
        UpdateFehler.FALSCHE_DATEI -> "Die geladene Datei ist keine neuere Bildschirmzeit – nichts installiert. Bitte einen Screenshot an Claude."
        UpdateFehler.NICHT_ERLAUBT -> "Bildschirmzeit darf keine Apps installieren: „Automatische Updates einrichten“ → Schalter „Diese Quelle zulassen“ ein → zurück."
        UpdateFehler.BESTAETIGEN -> "Android will dieses Update einmal bestätigt haben: „Neue Version installieren“ → „Aktualisieren“."
        UpdateFehler.ABGEBROCHEN -> "Abgebrochen – nichts installiert. „Noch einmal versuchen“, dann „Aktualisieren“ antippen."
        UpdateFehler.BLOCKIERT -> "Android hat die Installation blockiert – meist ist es Samsungs Automatische Sperre: Einstellungen → Sicherheit und Datenschutz → Automatische Sperre → aus, dann hier „Noch einmal versuchen“."
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
            l.fehler != null -> UpdateAnzeige("Update hat nicht geklappt", "Version 1.${neu.laufnummer}: " + fehlerText(l.fehler, l.fehlerStatus), UpdateKnopf.NOCHMAL)
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
    val sitzungSeit: Long get() = p.getLong("sitzung_seit", 0L)
    val ziel: Int get() = p.getInt("ziel", 0)

    fun sitzungMerken(id: Int, seit: Long, ziel: Int) =
        p.edit(commit = true) { putInt("sitzung", id).putLong("sitzung_seit", seit).putInt("ziel", ziel) }

    fun sitzungVergessen() = p.edit(commit = true) { remove("sitzung").remove("sitzung_seit") }

    val fehler: UpdateFehler? get() = p.getString("fehler", null)?.let { c -> UpdateFehler.entries.firstOrNull { it.code == c } }
    val fehlerStatus: Int get() = p.getInt("fehler_status", 0)
    val fehlerZeit: Long get() = p.getLong("fehler_zeit", 0L)
    val fehlversuche: Int get() = p.getInt("fehlversuche", 0)

    fun fehlerMerken(f: UpdateFehler, status: Int, jetzt: Long) = p.edit(commit = true) {
        putString("fehler", f.code).putInt("fehler_status", status).putLong("fehler_zeit", jetzt).putInt("fehlversuche", fehlversuche + 1)
    }

    fun fehlerVergessen() = p.edit(commit = true) { remove("fehler").remove("fehler_status").remove("fehler_zeit").remove("fehlversuche") }

    val erfolg: Int get() = p.getInt("erfolg", 0)
    val erfolgUm: Long get() = p.getLong("erfolg_um", 0L)

    fun erfolgMerken(fassung: Int, um: Long) = p.edit(commit = true) {
        putInt("erfolg", fassung).putLong("erfolg_um", um).remove("ziel").remove("sitzung").remove("sitzung_seit")
            .remove("fehler").remove("fehler_status").remove("fehler_zeit").remove("fehlversuche")
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
        erfolg = s.erfolg,
        erfolgUm = s.erfolgUm,
        geprueft = s.geprueft,
        jetzt = jetzt,
    )

    /**
     * Fürs Widget (bei jedem Zeichnen): wartet ein Update auf Tobi? Nur lesen –
     * hier wird keine Sitzung verworfen und nichts gefragt.
     */
    fun widgetHinweis(context: Context, jetzt: Long = System.currentTimeMillis()): Boolean {
        val s = UpdateSpeicher(context)
        if (!UpdateRegeln.neuer(s.freigabe, BuildConfig.VERSION_CODE)) return false
        return UpdateRegeln.widgetHinweis(lageAus(context, s, jetzt, installiertGerade = laeuft.get() || s.sitzung >= 0))
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

    /** Läuft gerade ein Update (hier oder eine Sitzung bei Android)? Alte Sitzungen werden verworfen. */
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
        if (info != null && Takt.seit(s.sitzungSeit, jetzt) <= UpdateRegeln.SITZUNG_MS) return true
        // Erst vergessen, dann verwerfen: Androids „abgebrochen“ zu dieser Sitzung zählt dann nicht.
        s.sitzungVergessen()
        if (info != null) verwerfen(context, id)
        return false
    }

    private fun installieren(context: Context, s: UpdateSpeicher, f: Freigabe, vordergrund: Boolean, jetzt: Long) {
        if (!laeuft.compareAndSet(false, true)) return
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
                sitzungStarten(context, s, datei, f.laufnummer, vordergrund, jetzt)
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
     */
    private fun sitzungStarten(context: Context, s: UpdateSpeicher, datei: File, ziel: Int, vordergrund: Boolean, jetzt: Long) {
        val installer = context.packageManager.packageInstaller
        // Reste früherer Versuche (Prozess beendet, bevor die Sitzung fertig war). Ihr „abgebrochen“ zählt nicht (andere Nummer).
        for (alt in installer.mySessions) if (alt.appPackageName == context.packageName) verwerfen(context, alt.sessionId)
        val id = installer.createSession(sitzungsParameter(context.packageName))
        s.sitzungMerken(id, jetzt, ziel)
        try {
            installer.openSession(id).use { sitzung ->
                sitzung.openWrite("bildschirmzeit.apk", 0, datei.length()).use { aus ->
                    datei.inputStream().use { it.copyTo(aus) }
                    sitzung.fsync(aus)
                }
                sitzung.commit(statusAbsender(context, id, vordergrund).intentSender)
            }
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

    /** Was Android über die Sitzung meldet. */
    internal fun statusErhalten(context: Context, intent: Intent, jetzt: Long = System.currentTimeMillis()) {
        val s = UpdateSpeicher(context)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i(LOG, "Update: Status $status")
        // Nur die gemerkte Sitzung zählt (sonst nur ein Erfolg). Verwirft die App eine Sitzung selbst
        // (Rückfrage im Hintergrund, zu alt, Reste), schickt Android dazu noch „abgebrochen“
        // (AOSP PackageInstallerSession.abandon → dispatchSessionFinished(INSTALL_FAILED_ABORTED)) –
        // sie ist dann schon vergessen, und die Meldung überschreibt nicht den wahren Grund.
        if (status != PackageInstaller.STATUS_SUCCESS && (id < 0 || id != s.sitzung)) return
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
                if (!gezeigt) {
                    s.sitzungVergessen()
                    verwerfen(context, id)
                    s.fehlerMerken(UpdateFehler.BESTAETIGEN, status, jetzt)
                }
            }
            else -> {
                val fehler = UpdateRegeln.fehlerAusStatus(status)
                s.sitzungVergessen()
                s.fehlerMerken(fehler, status, jetzt)
                if (fehler == UpdateFehler.UNGUELTIG) aufraeumen(context)
            }
        }
        melden()
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == STATUS) Selbstupdate.statusErhalten(context.applicationContext, intent)
    }
}

/** „Unbekannte Apps installieren“ für diese App – sonst die Liste, sonst die App-Info. */
fun installQuelleAbsichten(paket: String): List<Intent> = listOf(
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$paket".toUri()),
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$paket".toUri()),
)
