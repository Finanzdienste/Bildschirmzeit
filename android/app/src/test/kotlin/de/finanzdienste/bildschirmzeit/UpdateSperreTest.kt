package de.finanzdienste.bildschirmzeit

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.security.MessageDigest
import java.time.Duration

/**
 * Auf Tobis S24 (One UI 8) endete das Selbst-Update v6 → v7 mit „Abgebrochen
 * – nichts installiert“. Wahrscheinlich war es Samsungs Automatische Sperre:
 * Sie meldet sich als ABORTED (-115) mit „Self update is blocked by unknown
 * source package“, bis v7 las die App nur den Status. Hier: Androids Meldung
 * steht auf der Karte, die Sperre hat eigenen Text, eigenen Knopf und eigenen
 * Hinweis im Widget, und der Hintergrund versucht es danach nicht stündlich
 * neu. Dazu zwei Lücken: Widgets werden gleich nach dem Start der Sitzung neu
 * gezeichnet (kein altes CLEAR_TOP), und die 10-Minuten-Frist zählt ab commit
 * bzw. ab Androids Rückfrage.
 *
 * Nur mit Schnittstellen, die es auch bis v7 gab – so ist jeder Test gegen
 * die alte Fassung rot (Gegenprobe), nicht bloß nicht übersetzbar.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateSperreTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val installer get() = kontext.packageManager.packageInstaller
    private val eigene = BuildConfig.VERSION_CODE
    private val neu = eigene + 1
    private val t = 1_794_700_000_000L
    private val minute = 60_000L
    private val stunde = 60 * minute

    private val apk = "erfundene APK für den Test".toByteArray()
    private val pruefsumme = hex(MessageDigest.getInstance("SHA-256").digest(apk))
    private val altFragen = Selbstupdate.fragen
    private val altLaden = Selbstupdate.laden
    private var antwortFuer = neu

    private val sperreMeldung = "INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package"
    private val rampart = "com.samsung.android.rampart.action.MAIN_SETTING_ACTIVITY"

    @Before
    fun vorbereiten() {
        Netz.gesperrt = true
        Selbstupdate.beobachter = null
        try {
            WorkManager.initialize(kontext, Configuration.Builder().build())
        } catch (e: IllegalStateException) {
            // schon vom vorigen Test
        }
        Selbstupdate.fragen = { antwort(antwortFuer) }
        Selbstupdate.laden = { _, ziel, _ ->
            ziel.writeBytes(apk)
            pruefsumme
        }
        shadowOf(kontext.packageManager).setCanRequestPackageInstalls(true)
        archiv(neu)
        installer.registerSessionCallback(beobachten, Handler(Looper.getMainLooper()))
    }

    @After
    fun aufraeumen() {
        Selbstupdate.fragen = altFragen
        Selbstupdate.laden = altLaden
        Selbstupdate.beobachter = null
        installer.unregisterSessionCallback(beobachten)
    }

    private fun antwort(n: Int) = """
        {"tag_name":"v$n","name":"Bildschirmzeit 1.$n","draft":false,"prerelease":false,
         "assets":[{"name":"bildschirmzeit.apk","state":"uploaded","size":${apk.size},"digest":"sha256:$pruefsumme",
                    "content_type":"application/vnd.android.package-archive",
                    "browser_download_url":"https://github.com/Finanzdienste/Bildschirmzeit/releases/download/v$n/bildschirmzeit.apk"}]}
    """.trimIndent()

    private fun archiv(n: Int) = shadowOf(kontext.packageManager).setPackageArchiveInfo(
        Selbstupdate.datei(kontext, n).path,
        PackageInfo().apply {
            packageName = kontext.packageName
            longVersionCode = n.toLong()
        },
    )

    /** Die Sitzungen, die bei Android angelegt wurden (auch schon verworfene). */
    private val angelegt = mutableListOf<Int>()
    private val beobachten = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {
            angelegt += sessionId
        }

        override fun onBadgingChanged(sessionId: Int) = Unit
        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
        override fun onProgressChanged(sessionId: Int, progress: Float) = Unit
        override fun onFinished(sessionId: Int, success: Boolean) = Unit
    }

    private fun neueSitzungen(): List<Int> {
        shadowOf(Looper.getMainLooper()).idle()
        return angelegt.toList()
    }

    /** So meldet Android den Ausgang (PackageInstallerSession.dispatchSessionFinished), mit Meldung und Legacy-Code. */
    private fun status(id: Int, status: Int, meldung: String? = null, legacy: Int = 0, vorn: Boolean = false) = Intent(UpdateStatus.STATUS)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)
        .putExtra(UpdateStatus.VORDERGRUND, vorn)
        .apply {
            if (meldung != null) putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, meldung)
            if (legacy != 0) putExtra("android.content.pm.extra.LEGACY_STATUS", legacy)
        }

    /** Aus dem Hintergrund gestartet, dann meldet Android (wie auf dem S24) „abgebrochen“ – mit `meldung`. */
    private fun abgebrochen(meldung: String?, legacy: Int = -115): Int {
        Selbstupdate.imHintergrund(kontext, t)
        val id = UpdateSpeicher(kontext).sitzung
        assertTrue("Sitzung gestartet", id >= 0)
        Selbstupdate.statusErhalten(kontext, status(id, PackageInstaller.STATUS_FAILURE_ABORTED, meldung, legacy), t + minute)
        return id
    }

    private fun anzeige(jetzt: Long = t + 2 * minute) = UpdateRegeln.anzeige(Selbstupdate.lage(kontext, jetzt))

    /** Die App, offen, wie Tobi sie sieht: alle sichtbaren Texte der Update-Karte. */
    private fun karte(): Pair<MainActivity, List<TextView>> {
        val aktivitaet = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        warten()
        val texte = mutableListOf<TextView>()
        fun sammeln(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView) texte += v
            if (v is ViewGroup) for (i in 0 until v.childCount) sammeln(v.getChildAt(i))
        }
        sammeln(aktivitaet.findViewById(R.id.update_karte))
        return aktivitaet to texte
    }

    @Test
    fun androidsMeldungStehtAufDerKarte() {
        abgebrochen(sperreMeldung)
        val (_, texte) = karte()
        val alles = texte.joinToString("\n") { it.text.toString() }
        assertTrue(alles, alles.contains("Android: $sperreMeldung"))
        assertTrue("Quelle des Versuchs: $alles", alles.contains("Hintergrund"))
        assertTrue("ob Androids Rückfrage kam: $alles", alles.contains("ohne Rückfrage"))
    }

    /** Ein Paket-Prüfer (Play Protect, -22): eigener Text, und Androids Pfad steht nicht auf der Karte. */
    @Test
    fun pruefplatzMitEigenemTextUndOhnePfad() {
        abgebrochen("INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:/data/app/vmdl4711.tmp/base.apk", legacy = -22)
        assertEquals("pruefung", UpdateSpeicher(kontext).fehler?.code)
        val (_, texte) = karte()
        val alles = texte.joinToString("\n") { it.text.toString() }
        assertTrue(alles, alles.contains("Play Protect"))
        assertTrue(alles, alles.contains("Install not allowed for …"))
        assertFalse(alles, alles.contains("/data/"))
        assertFalse(alles, alles.contains("file:"))
        // Im Log dieselbe gesäuberte Zeile, nirgends der Pfad.
        val log = ShadowLog.getLogs().map { it.msg }
        assertTrue(log.toString(), log.any { it.contains("Android: INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for …") })
        assertTrue(log.toString(), log.none { it.contains("/data/") || it.contains("vmdl4711") })
    }

    /** ABORTED nach Androids Meldung: Sperre (beide Samsung-Texte), Tobis „Abbrechen“, von Android verworfen. */
    @Test
    fun abgebrochenNachMeldungAufgeteilt() {
        abgebrochen(sperreMeldung)
        assertEquals("sperre", UpdateSpeicher(kontext).fehler?.code)
        val a = anzeige()
        assertTrue(a.text, a.text.contains("Samsungs Automatische Sperre hat das Update verhindert"))
        assertTrue(a.text, a.text.contains("Einstellungen → Sicherheit und Datenschutz → Automatische Sperre"))

        for ((meldung, code) in listOf(
            "INSTALL_FAILED_ABORTED: User rejected installing unknown source package" to "sperre",
            "INSTALL_FAILED_ABORTED: User rejected permissions" to "abgebrochen",
            "INSTALL_FAILED_ABORTED: Session was abandoned" to "verworfen",
        )) {
            UpdateSpeicher(kontext).fehlerVergessen()
            val id = installer.createSession(Selbstupdate.sitzungsParameter(kontext.packageName))
            UpdateSpeicher(kontext).sitzungMerken(id, t, neu)
            Selbstupdate.statusErhalten(kontext, status(id, PackageInstaller.STATUS_FAILURE_ABORTED, meldung, -115), t + minute)
            assertEquals(meldung, code, UpdateSpeicher(kontext).fehler?.code)
        }
    }

    /**
     * Ohne Androids Meldung nennt „Abgebrochen“ beide Wege; „blockiert“ (Status 2)
     * nennt die Sperre nicht mehr – das ist in AOSP nur PRE_APPROVAL_NOT_AVAILABLE.
     */
    @Test
    fun ohneMeldungBeideWegeUndBlockiertOhneSperre() {
        abgebrochen(null, legacy = 0)
        val a = anzeige()
        assertEquals("Update hat nicht geklappt", a.titel)
        assertTrue(a.text, a.text.contains("„Noch einmal versuchen“ → „Aktualisieren“"))
        assertTrue(a.text, a.text.contains("„Unbekannte App gesperrt“"))
        assertTrue(a.text, a.text.contains("Automatische Sperre kurz aus"))
        val blockiert = UpdateRegeln.fehlerText(UpdateFehler.BLOCKIERT, 2)
        assertFalse(blockiert, blockiert.contains("Automatische Sperre"))
    }

    /** Nach der Sperre kein Hintergrund-Versuch mehr für dieses Release – der Knopf darf, ein neues Release setzt zurück. */
    @Test
    fun nachDerSperreKeinHintergrundVersuch() {
        abgebrochen(sperreMeldung)
        assertEquals(1, neueSitzungen().size)
        Selbstupdate.imHintergrund(kontext, t + 30 * stunde)
        assertEquals("nicht von selbst noch einmal", 1, neueSitzungen().size)
        Selbstupdate.jetztInstallieren(kontext, t + 30 * stunde + minute)
        assertEquals("der Knopf darf", 2, neueSitzungen().size)
        Selbstupdate.statusErhalten(kontext, status(UpdateSpeicher(kontext).sitzung, PackageInstaller.STATUS_FAILURE_ABORTED, sperreMeldung, -115, vorn = true), t + 30 * stunde + 2 * minute)
        Selbstupdate.imHintergrund(kontext, t + 60 * stunde)
        assertEquals(2, neueSitzungen().size)
        // Ein neues Release: ein neuer Versuch aus dem Hintergrund.
        antwortFuer = neu + 1
        archiv(neu + 1)
        Selbstupdate.imHintergrund(kontext, t + 65 * stunde)
        assertEquals(3, neueSitzungen().size)
        assertEquals(neu + 1, UpdateSpeicher(kontext).ziel)
    }

    private fun widgetAnlegen(): AppWidgetManager {
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
        return awm
    }

    private fun antippen(awm: AppWidgetManager): Intent {
        shadowOf(awm).getViewFor(7).findViewById<View>(android.R.id.background).performClick()
        warten()
        return shadowOf(kontext).nextStartedActivity ?: throw AssertionError("nichts gestartet")
    }

    private fun titel(awm: AppWidgetManager) = shadowOf(awm).getViewFor(7).findViewById<TextView>(R.id.titel).text.toString()

    /**
     * Ein vorher gezeichnetes Widget hatte CLEAR_TOP, und das blieb bis zum
     * nächsten Wecker: Tippte Tobi es an, während Androids Rückfrage offen war,
     * räumte es die Rückfrage weg. Jetzt zeichnet der Start der Sitzung die
     * Widgets neu.
     */
    @Test
    fun widgetGleichNachDemStartOhneClearTop() {
        val awm = widgetAnlegen()
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, t))
        assertTrue("ohne Sitzung mit CLEAR_TOP", antippen(awm).flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        Selbstupdate.imHintergrund(kontext, t + minute)
        assertTrue(UpdateSpeicher(kontext).sitzung >= 0)
        assertEquals("während der Sitzung kein CLEAR_TOP", 0, antippen(awm).flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /**
     * Kommt Androids Status (über den Empfänger), zeichnet die App die Widgets
     * gleich neu – bei der Sperre steht dort „Update blockiert“, Antippen öffnet
     * die Update-Karte (wieder mit CLEAR_TOP).
     */
    @Test
    fun nachDemStatusSagtDasWidgetUpdateBlockiert() {
        val awm = widgetAnlegen()
        assertTrue(Aktualisierung.imTakt(kontext, Anlass.WECKER, t))
        assertEquals("Heute", titel(awm))
        Selbstupdate.imHintergrund(kontext, t + minute)
        val id = UpdateSpeicher(kontext).sitzung
        UpdateStatus().onReceive(kontext, status(id, PackageInstaller.STATUS_FAILURE_ABORTED, sperreMeldung, -115))
        bis("„Update blockiert“ im Widget, ohne auf den Wecker zu warten") { titel(awm) == "Update blockiert" }
        Aktualisierung.allein { }
        val gestartet = antippen(awm)
        assertEquals(Ziel.UPDATE, gestartet.getStringExtra(Ziel.EXTRA))
        assertTrue(gestartet.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    private fun knopf(texte: List<TextView>, text: String): Button =
        texte.filterIsInstance<Button>().firstOrNull { it.text.toString() == text } ?: throw AssertionError("kein Knopf „$text“: ${texte.map { it.text }}")

    /** Der Knopf der Sperre-Karte öffnet Samsungs Seite der Sperre; „Noch einmal versuchen“ bleibt da. */
    @Test
    fun sperreKarteOeffnetDieSperre() {
        abgebrochen(sperreMeldung)
        val (aktivitaet, texte) = karte()
        knopf(texte, "Automatische Sperre öffnen").performClick()
        assertEquals(rampart, shadowOf(aktivitaet).nextStartedActivity?.action)
        val nochmal = knopf(texte, "Noch einmal versuchen")
        assertTrue(nochmal.isEnabled)
        nochmal.performClick()
        warten()
        assertEquals("noch einmal, trotz der Sperre", 2, neueSitzungen().size)
    }

    /** Gibt es Samsungs Seite nicht, die Sicherheitseinstellungen, sonst die Einstellungen. */
    @Test
    fun ohneSamsungsSeiteDieSicherheit() {
        abgebrochen(sperreMeldung)
        val (aktivitaet, texte) = karte()
        shadowOf(kontext).checkActivities(true)
        fun kann(aktion: String) = shadowOf(kontext.packageManager).addResolveInfoForIntent(
            Intent(aktion),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.settings"; name = "Einstellungen" } },
        )
        kann(Settings.ACTION_SETTINGS)
        knopf(texte, "Automatische Sperre öffnen").performClick()
        assertEquals(Settings.ACTION_SETTINGS, shadowOf(aktivitaet).nextStartedActivity?.action)
        kann(Settings.ACTION_SECURITY_SETTINGS)
        knopf(texte, "Automatische Sperre öffnen").performClick()
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, shadowOf(aktivitaet).nextStartedActivity?.action)
        shadowOf(kontext).checkActivities(false)
    }

    /** Die 10 Minuten zählen ab commit – nicht ab dem Start des Laufs vor dem Laden (das kann im Mobilfunk dauern). */
    @Test
    fun fristAbCommit() {
        Selbstupdate.laden = { _, ziel, _ ->
            ShadowSystemClock.advanceBy(Duration.ofMinutes(8))
            ziel.writeBytes(apk)
            pruefsumme
        }
        Selbstupdate.imHintergrund(kontext, t)
        val id = UpdateSpeicher(kontext).sitzung
        assertEquals("3 Minuten nach commit läuft sie noch", UpdateKnopf.WARTEN, anzeige(t + 11 * minute).knopf)
        assertNotNull(installer.getSessionInfo(id))
        // 11 Minuten nach commit: verworfen, still (keine Rückfrage gezeigt) – der Hintergrund versucht es wieder.
        assertEquals(UpdateKnopf.INSTALLIEREN, anzeige(t + 19 * minute).knopf)
        assertNull(installer.getSessionInfo(id))
        assertNull(UpdateSpeicher(kontext).fehler)
    }

    /**
     * Androids Rückfrage war gezeigt (Tobis Knopf): Die Frist zählt ab da, und
     * wird verworfen, sagt die Karte es – bis v7 verwarf die App sie zehn
     * Minuten nach dem Start still, und die Karte hieß „kommt von selbst“.
     */
    @Test
    fun rueckfrageZuLangeOffen() {
        Selbstupdate.beobachter = object : UpdateBeobachter {
            override fun updateGeaendert() = Unit
            override fun bestaetigen(absicht: Intent) = true
        }
        val id = installer.createSession(Selbstupdate.sitzungsParameter(kontext.packageName))
        UpdateSpeicher(kontext).sitzungMerken(id, t, neu)
        UpdateSpeicher(kontext).freigabe = NeueVersion.freigabeAus(antwort(neu))
        val rueckfrage = Intent("android.content.pm.action.CONFIRM_INSTALL").setPackage("com.android.packageinstaller").putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
        Selbstupdate.statusErhalten(
            kontext,
            status(id, PackageInstaller.STATUS_PENDING_USER_ACTION, vorn = true).putExtra(Intent.EXTRA_INTENT, rueckfrage),
            t + 5 * minute,
        )
        assertEquals("7 Minuten nach der Rückfrage läuft sie noch", UpdateKnopf.WARTEN, anzeige(t + 12 * minute).knopf)
        val a = anzeige(t + 16 * minute)
        assertTrue(a.text, a.text.contains("Android-Fenster zu lange offen – noch einmal versuchen"))
        assertEquals(UpdateKnopf.NOCHMAL, a.knopf)
        assertNull(installer.getSessionInfo(id))
    }

    private fun bis(was: String, bedingung: () -> Boolean) {
        repeat(200) {
            if (bedingung()) return
            Thread.sleep(25)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(was, bedingung())
    }

    private fun warten() {
        repeat(20) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
