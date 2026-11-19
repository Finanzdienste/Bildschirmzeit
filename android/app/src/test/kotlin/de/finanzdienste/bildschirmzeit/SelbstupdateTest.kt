package de.finanzdienste.bildschirmzeit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Tobi, 15.11.: „Keinen bock immer zu aktualisieren“ – die App installiert
 * neue Releases selbst. Auf einem nachgebauten Gerät, GitHub und der Download
 * sind ersetzt (Netz.gesperrt; nie das echte GitHub). Vorher öffnete
 * „Herunterladen“ nur den Browser.
 */
@RunWith(RobolectricTestRunner::class)
class SelbstupdateTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val installer get() = kontext.packageManager.packageInstaller
    private val eigene = BuildConfig.VERSION_CODE
    private val neu = eigene + 1
    private val t = 1_794_700_000_000L
    private val minute = 60_000L
    private val stunde = 60 * minute

    // Erfundene Bytes – die Paketdaten der „APK“ liefert der nachgebaute PackageManager.
    private val apk = "erfundene APK für den Test".toByteArray()
    private val pruefsumme = hex(MessageDigest.getInstance("SHA-256").digest(apk))

    private var gefragt = 0
    private var geladen = 0
    private val erstellt = mutableListOf<Int>()
    private val altFragen = Selbstupdate.fragen
    private val altLaden = Selbstupdate.laden

    private val beobachten = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {
            erstellt += sessionId
        }

        override fun onBadgingChanged(sessionId: Int) = Unit
        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
        override fun onProgressChanged(sessionId: Int, progress: Float) = Unit
        override fun onFinished(sessionId: Int, success: Boolean) = Unit
    }

    @Before
    fun vorbereiten() {
        Netz.gesperrt = true
        Selbstupdate.beobachter = null
        try {
            WorkManager.initialize(kontext, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
        Selbstupdate.fragen = {
            gefragt++
            antwort(neu)
        }
        Selbstupdate.laden = { _, ziel, _ ->
            geladen++
            ziel.writeBytes(apk)
            pruefsumme
        }
        darf(true)
        archiv(kontext.packageName, neu.toLong())
        installer.registerSessionCallback(beobachten, Handler(Looper.getMainLooper()))
    }

    @After
    fun aufraeumen() {
        // Erst ausklingen lassen: Jede offene App fragt beim Nach-vorn-Kommen GitHub (eigener Faden) –
        // käme das erst im nächsten Test an, zählte es dort mit (Selbstupdate.fragen ist für alle gleich).
        warten()
        Selbstupdate.fragen = altFragen
        Selbstupdate.laden = altLaden
        Selbstupdate.beobachter = null
        installer.unregisterSessionCallback(beobachten)
    }

    /** So antwortet GitHub auf releases/latest (gekürzt, erfundene Prüfsumme der erfundenen Bytes). */
    private fun antwort(n: Int, sha: String = pruefsumme, groesse: Int = apk.size) = """
        {"tag_name":"v$n","name":"Bildschirmzeit 1.$n","draft":false,"prerelease":false,
         "assets":[{"name":"bildschirmzeit.apk","state":"uploaded","size":$groesse,"digest":"sha256:$sha",
                    "content_type":"application/vnd.android.package-archive",
                    "browser_download_url":"https://github.com/Finanzdienste/Bildschirmzeit/releases/download/v$n/bildschirmzeit.apk"}]}
    """.trimIndent()

    private fun darf(ja: Boolean) = shadowOf(kontext.packageManager).setCanRequestPackageInstalls(ja)

    private fun archiv(paket: String, fassung: Long, n: Int = neu) = shadowOf(kontext.packageManager).setPackageArchiveInfo(
        Selbstupdate.datei(kontext, n).path,
        PackageInfo().apply {
            packageName = paket
            longVersionCode = fassung
        },
    )

    private fun sitzungen(): List<Int> {
        shadowOf(Looper.getMainLooper()).idle()
        return erstellt.toList()
    }

    private fun status(id: Int, status: Int, vorn: Boolean = false, rueckfrage: Intent? = null) = Intent(UpdateStatus.STATUS)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)
        .putExtra(UpdateStatus.VORDERGRUND, vorn)
        .apply { if (rueckfrage != null) putExtra(Intent.EXTRA_INTENT, rueckfrage) }

    /** So schickt Android die Rückfrage (PackageInstallerSession.sendPendingUserActionIntent). */
    private fun rueckfrage(id: Int) = Intent("android.content.pm.action.CONFIRM_INSTALL")
        .setPackage("com.android.packageinstaller")
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)

    private fun anzeige() = UpdateRegeln.anzeige(Selbstupdate.lage(kontext, t))

    @Test
    fun imHintergrundOhneRueckfrageInstalliert() {
        Selbstupdate.imHintergrund(kontext, t)
        assertEquals(1, gefragt)
        assertEquals(1, geladen)
        val ids = sitzungen()
        assertEquals(1, ids.size)
        val s = UpdateSpeicher(kontext)
        assertEquals(neu, s.ziel)
        assertEquals(ids[0], s.sitzung)
        assertNull(s.fehler)

        // Android meldet Erfolg (der Prozess der neuen Fassung bekommt die Meldung).
        Selbstupdate.statusErhalten(kontext, status(ids[0], PackageInstaller.STATUS_SUCCESS), t + minute)
        assertEquals(neu, s.erfolg)
        assertEquals(-1, s.sitzung)
        assertEquals(0, File(kontext.noBackupFilesDir, "update").listFiles().orEmpty().size)
    }

    @Test
    fun sitzungNurFuersEigenePaketOhneRueckfrage() {
        val p = Selbstupdate.sitzungsParameter(kontext.packageName)
        fun feld(name: String): Any? = PackageInstaller.SessionParams::class.java.getDeclaredField(name).apply { isAccessible = true }.get(p)
        assertEquals(kontext.packageName, feld("appPackageName"))
        assertEquals(PackageInstaller.SessionParams.MODE_FULL_INSTALL, feld("mode"))
        assertEquals(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED, feld("requireUserAction"))

        // Der Status geht explizit an den eigenen, nicht exportierten Empfänger; veränderbar, damit Android die Extras eintragen kann.
        val absender = shadowOf(Selbstupdate.statusAbsender(kontext, 42, vordergrund = true))
        assertTrue(absender.isBroadcast)
        assertFalse(absender.isImmutable)
        assertTrue(absender.flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(ComponentName(kontext, UpdateStatus::class.java), absender.savedIntent.component)
        assertEquals(kontext.packageName, absender.savedIntent.`package`)
        assertTrue(absender.savedIntent.getBooleanExtra(UpdateStatus.VORDERGRUND, false))
    }

    @Test
    fun falschePruefsummeNichtsInstalliert() {
        Selbstupdate.laden = { _, ziel, _ ->
            geladen++
            ziel.writeBytes("unterwegs verändert".toByteArray())
            hex(MessageDigest.getInstance("SHA-256").digest("unterwegs verändert".toByteArray()))
        }
        Selbstupdate.imHintergrund(kontext, t)
        assertEquals(1, geladen)
        assertEquals(emptyList<Int>(), sitzungen())
        assertEquals(UpdateFehler.PRUEFSUMME, UpdateSpeicher(kontext).fehler)
        assertFalse(Selbstupdate.datei(kontext, neu).exists())
        assertEquals(UpdateKnopf.NOCHMAL, anzeige().knopf)
    }

    @Test
    fun ohneErlaubnisNichtGeladen() {
        darf(false)
        Selbstupdate.imHintergrund(kontext, t)
        assertEquals(1, gefragt)
        assertEquals(0, geladen)
        assertEquals(emptyList<Int>(), sitzungen())
        val a = anzeige()
        assertEquals("Neue Version verfügbar", a.titel)
        assertEquals(UpdateKnopf.EINRICHTEN, a.knopf)
    }

    @Test
    fun nieGleicheOderAeltereFassung() {
        // GitHub meldet nur die eigene Fassung: nichts zu tun.
        Selbstupdate.fragen = { antwort(eigene) }
        Selbstupdate.imHintergrund(kontext, t)
        assertEquals(0, geladen)
        assertNull(anzeige().knopf)

        // Das Release sagt „neuer“, die Datei ist es nicht: nichts installiert, und der Hintergrund versucht es nicht wieder.
        Selbstupdate.fragen = { antwort(neu) }
        archiv(kontext.packageName, eigene.toLong())
        Selbstupdate.imHintergrund(kontext, t + 5 * stunde)
        assertEquals(1, geladen)
        assertEquals(emptyList<Int>(), sitzungen())
        assertEquals(UpdateFehler.FALSCHE_DATEI, UpdateSpeicher(kontext).fehler)
        Selbstupdate.imHintergrund(kontext, t + 40 * stunde)
        assertEquals(1, geladen)

        // Ein fremdes Paket ebenso – über den Knopf (der darf es trotz des Fehlers noch einmal versuchen).
        archiv("de.jemand.anders", neu.toLong())
        Selbstupdate.jetztInstallieren(kontext, t + 41 * stunde)
        assertEquals(2, geladen)
        assertEquals(emptyList<Int>(), sitzungen())
    }

    @Test
    fun githubHoechstensJedeStundeBeimOeffnenAlleZehnMinuten() {
        darf(false)
        Selbstupdate.imHintergrund(kontext, t)
        Selbstupdate.imHintergrund(kontext, t + 15 * minute)
        Selbstupdate.imHintergrund(kontext, t + 59 * minute)
        assertEquals(1, gefragt)
        // Bis v15 erst nach 4 Stunden (Tobi, 19.11.: „Jo app aktualisiert sich ja nur alle vier Stunden“).
        Selbstupdate.imHintergrund(kontext, t + stunde)
        assertEquals(2, gefragt)
        Selbstupdate.pruefenFuerApp(kontext, t + stunde + 9 * minute)
        assertEquals(2, gefragt)
        Selbstupdate.pruefenFuerApp(kontext, t + stunde + 10 * minute)
        assertEquals(3, gefragt)
    }

    @Test
    fun keinZweitesUpdateWaehrendEinesLaufenden() {
        val laufend = installer.createSession(Selbstupdate.sitzungsParameter(kontext.packageName))
        UpdateSpeicher(kontext).sitzungMerken(laufend, t, neu)
        Selbstupdate.imHintergrund(kontext, t + minute)
        Selbstupdate.jetztInstallieren(kontext, t + minute)
        assertEquals(0, geladen)
        assertEquals(listOf(laufend), sitzungen())
        assertEquals(UpdateKnopf.WARTEN, UpdateRegeln.anzeige(Selbstupdate.lage(kontext, t + minute)).knopf)

        // Hängt sie länger als 10 Minuten (Rückfrage liegen gelassen): verwerfen und neu.
        Selbstupdate.imHintergrund(kontext, t + 11 * minute)
        assertNull(installer.getSessionInfo(laufend))
        assertEquals(1, geladen)
        assertEquals(2, sitzungen().size)
    }

    @Test
    fun nachFehlernWartenStattEndlosVersuch() {
        Selbstupdate.laden = { _, _, _ ->
            geladen++
            throw IOException("kein Netz")
        }
        Selbstupdate.imHintergrund(kontext, t)
        assertEquals(1, geladen)
        assertEquals(UpdateFehler.KEIN_NETZ, UpdateSpeicher(kontext).fehler)
        Selbstupdate.imHintergrund(kontext, t + 30 * minute)
        assertEquals(1, geladen)
        Selbstupdate.imHintergrund(kontext, t + stunde)
        assertEquals(2, geladen)
        Selbstupdate.imHintergrund(kontext, t + 2 * stunde + 59 * minute)
        assertEquals("nach dem zweiten Fehler 2 Stunden Pause", 2, geladen)
        Selbstupdate.imHintergrund(kontext, t + 3 * stunde)
        assertEquals(3, geladen)
        // Tobis Knopf wartet nicht.
        Selbstupdate.jetztInstallieren(kontext, t + 3 * stunde + minute)
        assertEquals(4, geladen)
        // Ein neues Release: neuer Versuch, die Wartezeit der alten Fassung gilt nicht mehr.
        Selbstupdate.fragen = { antwort(neu + 1) }
        archiv(kontext.packageName, (neu + 1).toLong(), neu + 1)
        Selbstupdate.laden = { _, ziel, _ ->
            geladen++
            ziel.writeBytes(apk)
            pruefsumme
        }
        Selbstupdate.imHintergrund(kontext, t + 7 * stunde + 2 * minute)
        assertEquals(5, geladen)
        assertEquals(1, sitzungen().size)
        assertEquals(neu + 1, UpdateSpeicher(kontext).ziel)
    }

    @Test
    fun rueckfrageImHintergrundWirdVerworfen() {
        Selbstupdate.imHintergrund(kontext, t)
        val id = sitzungen().single()
        Selbstupdate.statusErhalten(
            kontext,
            status(id, PackageInstaller.STATUS_PENDING_USER_ACTION, vorn = false, rueckfrage = rueckfrage(id)),
            t + minute,
        )
        assertNull(shadowOf(kontext).nextStartedActivity)
        val s = UpdateSpeicher(kontext)
        assertEquals(UpdateFehler.BESTAETIGEN, s.fehler)
        assertEquals(-1, s.sitzung)
        assertEquals(UpdateKnopf.INSTALLIEREN, anzeige().knopf)
        assertTrue(anzeige().text.contains("einmal bestätigt"))
        // Der Hintergrund lädt nicht gleich wieder (Wartezeit), die heruntergeladene Datei bleibt für den Knopf.
        Selbstupdate.imHintergrund(kontext, t + 30 * minute)
        assertEquals(1, geladen)
        assertTrue(Selbstupdate.datei(kontext, neu).exists())
    }

    /**
     * Verwirft die App eine Sitzung selbst, schickt Android dazu noch
     * „abgebrochen“ (AOSP PackageInstallerSession.abandon →
     * dispatchSessionFinished(INSTALL_FAILED_ABORTED) an den Status-Empfänger
     * aus commit). Robolectric meldet das nicht – hier von Hand. Vorher
     * überschrieb diese Meldung „bestätigen“ mit „Abgebrochen“ und zählte den
     * Fehlversuch doppelt (Prüfer, 15.11.).
     */
    @Test
    fun androidsAbbruchNachDemEigenenVerwerfenZaehltNicht() {
        Selbstupdate.imHintergrund(kontext, t)
        val id = sitzungen().single()
        Selbstupdate.statusErhalten(
            kontext,
            status(id, PackageInstaller.STATUS_PENDING_USER_ACTION, vorn = false, rueckfrage = rueckfrage(id)),
            t + minute,
        )
        Selbstupdate.statusErhalten(kontext, status(id, PackageInstaller.STATUS_FAILURE_ABORTED), t + minute)
        val s = UpdateSpeicher(kontext)
        assertEquals(UpdateFehler.BESTAETIGEN, s.fehler)
        assertEquals(1, s.fehlversuche)
        val a = anzeige()
        assertEquals("Neue Version verfügbar", a.titel)
        assertEquals(UpdateKnopf.INSTALLIEREN, a.knopf)
        assertTrue(a.text, a.text.contains("einmal bestätigt"))

        // Dasselbe für eine zu alte Sitzung, die die Karte beim Öffnen verwirft (Rückfrage liegen gelassen).
        val alt = installer.createSession(Selbstupdate.sitzungsParameter(kontext.packageName))
        s.fehlerVergessen()
        s.sitzungMerken(alt, t, neu)
        Selbstupdate.lage(kontext, t + 11 * minute)
        assertNull(installer.getSessionInfo(alt))
        Selbstupdate.statusErhalten(kontext, status(alt, PackageInstaller.STATUS_FAILURE_ABORTED), t + 11 * minute)
        assertNull(s.fehler)
        assertEquals(0, s.fehlversuche)
        // Meldungen ohne Sitzungsnummer zählen nie (außer einem Erfolg).
        Selbstupdate.statusErhalten(kontext, Intent(UpdateStatus.STATUS).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE), t + 12 * minute)
        assertNull(s.fehler)
    }

    /**
     * Benachrichtigungen hat die App nicht: Kommt ein Update ohne Tobi nicht an,
     * sagt es das Widget („Update: antippen“, Antippen öffnet die App). Vorher
     * stand das nur auf der Karte in der App (Prüfer, 15.11.).
     */
    @Test
    fun widgetSagtBescheidWennDasUpdateAufTobiWartet() {
        assertFalse("noch kein Release bekannt", Selbstupdate.widgetHinweis(kontext, t))
        darf(false)
        Selbstupdate.imHintergrund(kontext, t)
        assertTrue("nicht eingerichtet", Selbstupdate.widgetHinweis(kontext, t))
        darf(true)
        assertFalse("kommt von selbst", Selbstupdate.widgetHinweis(kontext, t))
        Selbstupdate.imHintergrund(kontext, t + minute)
        val id = sitzungen().single()
        assertFalse("wird gerade installiert", Selbstupdate.widgetHinweis(kontext, t + minute))
        Selbstupdate.statusErhalten(
            kontext,
            status(id, PackageInstaller.STATUS_PENDING_USER_ACTION, vorn = false, rueckfrage = rueckfrage(id)),
            t + 2 * minute,
        )
        assertTrue("Android will es bestätigt haben", Selbstupdate.widgetHinweis(kontext, t + 2 * minute))

        // Gezeichnet über den Wecker, in Tobis Größe (400 × 201 dp): der Kopf sagt es, Antippen öffnet die App bei der Update-Karte.
        val awm = AppWidgetManager.getInstance(kontext)
        shadowOf(awm).bindAppWidgetId(7, ComponentName(kontext, BildschirmzeitWidget::class.java))
        awm.updateAppWidgetOptions(
            7,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 400)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 201)
            },
        )
        shadowOf(kontext.getSystemService(PowerManager::class.java)).turnScreenOn(true)
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, t + 3 * minute))
        val widget = shadowOf(awm).getViewFor(7)
        assertEquals("Update: antippen", widget.findViewById<TextView>(R.id.titel).text.toString())
        widget.findViewById<View>(android.R.id.background).performClick()
        warten()
        val gestartet = shadowOf(kontext).nextStartedActivity
        assertEquals(ComponentName(kontext, MainActivity::class.java), gestartet?.component)
        assertEquals(Ziel.UPDATE, gestartet?.getStringExtra(Ziel.EXTRA))

        // Installiert (MY_PACKAGE_REPLACED in der neuen Fassung): wieder „Heute“, Antippen öffnet die Statistik.
        UpdateSpeicher(kontext).freigabe = NeueVersion.freigabeAus(antwort(eigene))
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, t + 6 * minute))
        val danach = shadowOf(awm).getViewFor(7)
        assertEquals("Heute", danach.findViewById<TextView>(R.id.titel).text.toString())
        danach.findViewById<View>(android.R.id.background).performClick()
        warten()
        assertEquals(Ziel.STATISTIK, shadowOf(kontext).nextStartedActivity?.getStringExtra(Ziel.EXTRA))
    }

    @Test
    fun rueckfrageImVordergrundZeigtAndroidsDialog() {
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        assertTrue(Selbstupdate.beobachter === aktivitaet)
        UpdateSpeicher(kontext).sitzungMerken(5, t, neu)

        // Die App ist offen, aber die Installation kam aus dem Hintergrund (nicht von Tobis Knopf): kein Dialog aus dem Nichts.
        UpdateSpeicher(kontext).sitzungMerken(4, t, neu)
        Selbstupdate.statusErhalten(kontext, status(4, PackageInstaller.STATUS_PENDING_USER_ACTION, vorn = false, rueckfrage = rueckfrage(4)), t)
        assertNull(shadowOf(aktivitaet).nextStartedActivity)
        assertEquals(UpdateFehler.BESTAETIGEN, UpdateSpeicher(kontext).fehler)

        // Eine fremde Absicht statt Androids Rückfrage: nicht starten.
        UpdateSpeicher(kontext).fehlerVergessen()
        UpdateSpeicher(kontext).sitzungMerken(5, t, neu)
        val fremd = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        Selbstupdate.statusErhalten(kontext, status(5, PackageInstaller.STATUS_PENDING_USER_ACTION, vorn = true, rueckfrage = fremd), t)
        assertNull(shadowOf(aktivitaet).nextStartedActivity)
        assertEquals(UpdateFehler.BESTAETIGEN, UpdateSpeicher(kontext).fehler)
        assertEquals(-1, UpdateSpeicher(kontext).sitzung)

        // Androids Rückfrage, während Tobi gerade „Neue Version installieren“ getippt hat: der Dialog kommt, die Sitzung bleibt.
        val id = installer.createSession(Selbstupdate.sitzungsParameter(kontext.packageName))
        UpdateSpeicher(kontext).sitzungMerken(id, System.currentTimeMillis(), neu)
        Selbstupdate.statusErhalten(kontext, status(id, PackageInstaller.STATUS_PENDING_USER_ACTION, vorn = true, rueckfrage = rueckfrage(id)), t)
        val gestartet = shadowOf(aktivitaet).nextStartedActivity
        assertNotNull(gestartet)
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", gestartet.action)
        warten()
        assertEquals(id, UpdateSpeicher(kontext).sitzung)
        assertNotNull(installer.getSessionInfo(id))
        assertEquals(UpdateKnopf.WARTEN, aktivitaet.findViewById<Button>(R.id.update_knopf).tag)

        // Tobi tippt in Androids Dialog „Abbrechen“: Das zählt (es ist die laufende Sitzung), und die Meldung weiß, dass gefragt wurde.
        Selbstupdate.statusErhalten(
            kontext,
            status(id, PackageInstaller.STATUS_FAILURE_ABORTED, vorn = true).putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_ABORTED: User rejected permissions"),
            t,
        )
        assertEquals(UpdateFehler.ABGEBROCHEN, UpdateSpeicher(kontext).fehler)
        assertEquals(-1, UpdateSpeicher(kontext).sitzung)
        assertEquals(UpdateDiagnose("INSTALL_FAILED_ABORTED: User rejected permissions", 0, null, vordergrund = true, rueckfrage = true), UpdateSpeicher(kontext).diagnose)

        // Nicht mehr vorn: dann auch keine Rückfrage.
        Robolectric.buildActivity(MainActivity::class.java).setup().pause()
        assertNull(Selbstupdate.beobachter)
    }

    @Test
    fun rueckfrageOhneDateirechte() {
        val mit = rueckfrage(3).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val sicher = Selbstupdate.bestaetigungSicher(mit)!!
        assertEquals(0, sicher.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertEquals(3, sicher.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
        assertNull(Selbstupdate.bestaetigungSicher(null))
        assertNull(Selbstupdate.bestaetigungSicher(Intent("android.content.pm.action.CONFIRM_INSTALL")))
        assertNull(Selbstupdate.bestaetigungSicher(rueckfrage(3).setData(Uri.parse("content://x/y"))))
    }

    @Test
    fun fehlerMitRatUndErfolgNachDemUpdate() {
        Selbstupdate.imHintergrund(kontext, t)
        val id = sitzungen().single()
        Selbstupdate.statusErhalten(kontext, status(id, PackageInstaller.STATUS_FAILURE_BLOCKED), t + minute)
        val a = anzeige()
        assertEquals("Update hat nicht geklappt", a.titel)
        // „blockiert“ ist nicht die Automatische Sperre (die kommt als „abgebrochen“ mit eigener Meldung).
        assertTrue(a.text, a.text.contains("blockiert (Status 2)"))
        assertFalse(a.text, a.text.contains("Automatische Sperre"))
        assertEquals(UpdateKnopf.NOCHMAL, a.knopf)

        // MY_PACKAGE_REPLACED in der neuen Fassung (hier: die laufende ist das Ziel): Erfolg, aufgeräumt.
        UpdateSpeicher(kontext).sitzungMerken(id, t, eigene)
        Selbstupdate.datei(kontext, eigene).apply { parentFile!!.mkdirs() }.writeBytes(apk)
        Selbstupdate.nachUpdate(kontext, t + 2 * minute)
        val s = UpdateSpeicher(kontext)
        assertEquals(eigene, s.erfolg)
        assertNull(s.fehler)
        assertEquals(0, File(kontext.noBackupFilesDir, "update").listFiles().orEmpty().size)
    }

    /**
     * Tobi, 19.11., 16:44 (Screenshot): die App auf 1.14, v16 draußen, „Zuletzt nachgesehen:
     * 13:52“ und kein Knopf – die App war noch offen, gefragt wurde nur beim ersten Start.
     */
    @Test
    fun nachVornFragtDieAppGithubAuchOhneNeustart() {
        // Gezählt am eigenen Speicher, nicht an Selbstupdate.fragen: WorkManager-Läufe anderer
        // Tests (eigener Kontext) können dort mitzählen.
        val s = UpdateSpeicher(kontext)
        // Vor einer halben Stunde gefragt: fällig für die App (10 Minuten), nicht für den Hintergrund (eine Stunde).
        s.geprueft = System.currentTimeMillis() - 30 * minute
        val steuerung = Robolectric.buildActivity(MainActivity::class.java).setup()
        val aktivitaet = steuerung.get()
        warten()
        assertTrue("beim Start gefragt", System.currentTimeMillis() - s.geprueft < minute)
        steuerung.pause().stop()

        // Eine Viertelstunde später kommt die noch offene App wieder nach vorn (Startbildschirm, Widget).
        val vorher = System.currentTimeMillis() - 15 * minute
        s.geprueft = vorher
        steuerung.restart().start().resume()
        warten()
        assertTrue("nach vorn wieder gefragt", s.geprueft > vorher)
        val knopf = aktivitaet.findViewById<Button>(R.id.update_knopf)
        assertEquals("Neue Version installieren", knopf.text.toString())
        assertTrue(aktivitaet.findViewById<TextView>(R.id.update_text).text.contains("Version 1.$neu ist da"))

        // Gleich noch einmal nach vorn: GitHub höchstens alle 10 Minuten.
        val zuletzt = s.geprueft
        steuerung.pause().resume()
        warten()
        assertEquals(zuletzt, s.geprueft)
    }

    @Test
    fun appKarteFuehrtZumEinrichtenUndInstalliert() {
        darf(false)
        UpdateSpeicher(kontext).freigabe = NeueVersion.freigabeAus(antwort(neu))
        UpdateSpeicher(kontext).geprueft = System.currentTimeMillis()
        val steuerung = Robolectric.buildActivity(MainActivity::class.java).setup()
        val aktivitaet = steuerung.get()
        warten()
        assertEquals(View.VISIBLE, aktivitaet.findViewById<View>(R.id.update_karte).visibility)
        val knopf = aktivitaet.findViewById<Button>(R.id.update_knopf)
        assertEquals("Automatische Updates einrichten", knopf.text.toString())
        knopf.performClick()
        val einstellung = shadowOf(aktivitaet).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, einstellung.action)
        assertEquals("package:${kontext.packageName}", einstellung.dataString)

        // Zurück mit Erlaubnis: Die Karte zeigt gleich den Knopf zum Installieren.
        darf(true)
        steuerung.pause().resume()
        warten()
        assertEquals("Neue Version installieren", knopf.text.toString())
        assertTrue(aktivitaet.findViewById<TextView>(R.id.update_text).text.contains("Version 1.$neu ist da"))
        knopf.performClick()
        warten()
        assertEquals(1, geladen)
        assertEquals(1, sitzungen().size)
    }

    private fun warten() {
        repeat(20) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
