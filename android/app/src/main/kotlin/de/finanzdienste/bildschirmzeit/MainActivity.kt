package de.finanzdienste.bildschirmzeit

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Der App-Bildschirm: oben die Statistik (Tag, Woche, Monat aus dem Verlauf),
 * darunter einrichten, verbinden, Widget, Update, löschen. Das Widget öffnet
 * die App mit einem Ziel (Ziel.kt): STATISTIK zeigt den zuletzt gewählten
 * Reiter mit dem jetzigen Zeitraum, UPDATE stellt die Update-Karte nach oben.
 */
class MainActivity : Activity(), UpdateBeobachter {
    private lateinit var faeden: ExecutorService
    private lateinit var speicher: Speicher
    private lateinit var verlauf: Verlauf

    /** Zwischen onResume und onPause: Nur dann darf Androids Rückfrage zum Update kommen. */
    private var vorn = false

    /** Die Uhr der Statistik – nur Vorschau und Tests setzen eine feste (uhrStellen). */
    private var jetzt: () -> Long = { System.currentTimeMillis() }

    private var reiter = Reiter.WOCHE
    private lateinit var zeitraum: Zeitraum

    /** „Heute“, als der Zeitraum gewählt wurde – über Mitternacht offen, rückt der jetzige Zeitraum mit. */
    private lateinit var gewaehltAm: LocalDate

    /** Das Widget hat die App wegen eines Updates geöffnet: die Update-Karte steht oben. */
    private var updateOben = false

    /** Zuletzt bekannt: Nutzungsdatenzugriff erlaubt? Sonst steht dessen Karte vor der Statistik. */
    private var erlaubt = true

    /** Die letzte Rechnung – für „Stand 21:40“ unter der Statistik. */
    private var letztes: Ergebnis? = null

    /** Nur das Ergebnis des neuesten Auftrags zeigen (Reiter schnell hintereinander getippt). */
    private var auftrag = 0

    /**
     * Eine App, per Tipp auf ihre Zeile gewählt: Die Statistik zeigt nur sie
     * (Zeit je Tag, Ø, Anteil), über Reiter und ‹ › hinweg, bis „Alle Apps“ oder
     * Zurück. Nur `schluessel` und `name` zählen.
     */
    private var appWahl: Posten? = null

    /** Wo die Liste stand, als die App gewählt wurde – „Alle Apps“ führt dorthin zurück. */
    private var listeScroll = 0

    /** Ein Tag, per Tipp auf einen Balken geöffnet: Zurück führt in diesen Zeitraum (null: Zurück schließt die App). */
    private var vomBalken: Zeitraum? = null

    /**
     * Zurück aus einem Tag vom Balken. Das Manifest setzt
     * enableOnBackInvokedCallback="true": Ab Android 13 kommt Zurück nur über
     * solche Rückrufe an, onBackPressed gar nicht (Lint GestureBackNavigation)
     * – auf 13 bis 15 wie auf Android 16. Darunter gibt es den Rückruf nicht,
     * dort schließt Zurück die App wie bisher.
     */
    internal val zurueckRuf: OnBackInvokedCallback? = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { zurueckZumZeitraum() } else null
    private var zurueckAngemeldet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        faeden = Executors.newCachedThreadPool()
        speicher = Speicher(this)
        verlauf = Verlauf(this)
        erlaubt = Nutzungsdaten.erlaubt(this)
        reiter = Reiter.von(speicher.reiter)
        gewaehltAm = heute()
        zeitraum = Zeitraum.um(reiter, gewaehltAm)

        val wurzel = findViewById<View>(R.id.wurzel)
        ViewCompat.setOnApplyWindowInsetsListener(wurzel) { v, insets ->
            val r = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(r.left, r.top, r.right, r.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<Button>(R.id.schritt1_knopf).setOnClickListener { nutzungszugriffOeffnen() }
        findViewById<Button>(R.id.app_info).setOnClickListener {
            oeffnen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
        findViewById<Button>(R.id.schritt3_knopf).setOnClickListener { widgetHinzufuegen() }
        findViewById<Button>(R.id.schritt4_knopf).setOnClickListener {
            einstellungOeffnen(listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
        }
        findViewById<Button>(R.id.schritt4_wieder_zeigen).setOnClickListener {
            Verborgen(this).alleZeigen()
            schritt4Zeigen()
            statistikNeu()
        }
        findViewById<Button>(R.id.update_knopf).setOnClickListener { updateKnopf(it.tag as? UpdateKnopf) }
        findViewById<Button>(R.id.update_knopf2).setOnClickListener { updateKnopf(it.tag as? UpdateKnopf) }
        findViewById<Button>(R.id.loeschen).setOnClickListener { loeschenFragen() }
        versionZeigen(BuildConfig.VERSION_NAME)
        webZeilenAnlegen()
        statistikAnlegen()
        // Neu gebaut (Drehen, Hell/Dunkel nach Zeitplan): der Zeitraum, den Tobi gerade ansah.
        // Das Ziel gilt nur für eine neue Absicht des Widgets, nicht noch einmal beim Neubau.
        if (savedInstanceState == null || !zustandLesen(savedInstanceState)) zielBehandeln(intent)

        Aktualisierung.einplanen(this)
        faeden.execute {
            Selbstupdate.pruefenFuerApp(applicationContext)
            imVordergrund { updateKarteNeu() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ZUSTAND_REITER, reiter.name)
        outState.putString(ZUSTAND_VON, zeitraum.von.toString())
        outState.putString(ZUSTAND_AM, gewaehltAm.toString())
        outState.putBoolean(ZUSTAND_UPDATE, updateOben)
        vomBalken?.let {
            outState.putString(ZUSTAND_BALKEN_REITER, it.reiter.name)
            outState.putString(ZUSTAND_BALKEN_VON, it.von.toString())
        }
        appWahl?.let {
            outState.putString(ZUSTAND_APP, it.schluessel)
            outState.putString(ZUSTAND_APP_NAME, it.name)
        }
    }

    /** Den gesicherten Zustand übernehmen; false, wenn er fehlt oder unlesbar ist. */
    private fun zustandLesen(b: Bundle): Boolean {
        val r = b.getString(ZUSTAND_REITER) ?: return false
        val von = b.getString(ZUSTAND_VON) ?: return false
        val am = b.getString(ZUSTAND_AM) ?: return false
        return try {
            reiter = Reiter.von(r)
            zeitraum = Zeitraum.um(reiter, LocalDate.parse(von))
            gewaehltAm = LocalDate.parse(am)
            updateOben = b.getBoolean(ZUSTAND_UPDATE)
            vomBalken = b.getString(ZUSTAND_BALKEN_VON)?.let { Zeitraum.um(Reiter.von(b.getString(ZUSTAND_BALKEN_REITER)), LocalDate.parse(it)) }
            appWahl = b.getString(ZUSTAND_APP)?.let { Posten(it, b.getString(ZUSTAND_APP_NAME) ?: it, 0L) }
            zurueckPflegen()
            true
        } catch (e: DateTimeParseException) {
            false
        }
    }

    /** Das Widget tippt, während die App schon offen ist (singleTop): dann kommt das Ziel hier an. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        zielBehandeln(intent)
    }

    /**
     * STATISTIK: der zuletzt gewählte Reiter (beim ersten Mal Woche) mit dem
     * Zeitraum von heute, ganz oben. UPDATE: die Update-Karte steht oben. Ohne
     * Ziel (vom Startbildschirm) bleibt alles, wie es ist.
     */
    private fun zielBehandeln(intent: Intent?) {
        when (intent?.getStringExtra(Ziel.EXTRA)) {
            Ziel.STATISTIK -> {
                updateOben = false
                reiter = Reiter.von(speicher.reiter)
                gewaehltAm = heute()
                zeitraum = Zeitraum.um(reiter, gewaehltAm)
                vomBalken = null
                appWahl = null
                zurueckPflegen()
            }
            Ziel.UPDATE -> updateOben = true
            else -> return
        }
        ordnen()
        findViewById<ScrollView>(R.id.wurzel).scrollTo(0, 0)
        statistikNeu()
    }

    override fun onResume() {
        super.onResume()
        vorn = true
        Selbstupdate.beobachter = this
        neuLaden()
        // Zurück aus „Unbekannte Apps installieren“: Die Karte zeigt gleich, ob es jetzt erlaubt ist.
        updateKarteNeu()
    }

    override fun onPause() {
        vorn = false
        if (Selbstupdate.beobachter === this) Selbstupdate.beobachter = null
        super.onPause()
    }

    override fun onDestroy() {
        faeden.shutdown()
        super.onDestroy()
    }

    /**
     * Zurück: aus dem Tag in den Zeitraum, aus dem der Balken kam; sonst von
     * der einen App zu allen. false: nichts zu tun (die App schließt).
     */
    internal fun zurueckZumZeitraum(): Boolean {
        val z = vomBalken
        if (z == null) return alleAppsZeigen()
        vomBalken = null
        zurueckPflegen()
        reiter = z.reiter
        zeitraum = z
        statistikNeu()
        return true
    }

    /** Eine App angetippt: nur sie, im selben Zeitraum; die Statistik rückt nach oben ins Bild. */
    internal fun appWaehlen(p: Posten) {
        val wurzel = findViewById<ScrollView>(R.id.wurzel)
        if (appWahl == null) listeScroll = wurzel.scrollY
        appWahl = Posten(p.schluessel, p.name, 0L)
        zurueckPflegen()
        statistikNeu()
        val karte = findViewById<View>(R.id.statistik_karte)
        if (wurzel.scrollY > karte.top) wurzel.scrollTo(0, karte.top)
    }

    /** „Alle Apps“ (oder Zurück): wieder alle, die Liste dort, wo sie war. false: es war keine gewählt. */
    internal fun alleAppsZeigen(): Boolean {
        if (appWahl == null) return false
        appWahl = null
        zurueckPflegen()
        statistikNeu(nachher = { findViewById<ScrollView>(R.id.wurzel).post { findViewById<ScrollView>(R.id.wurzel).scrollTo(0, listeScroll) } })
        return true
    }

    /** Den Rückruf für Zurück nur anmelden, solange ein Tag vom Balken oder eine App offen ist – sonst schließt Zurück die App wie gewohnt. */
    private fun zurueckPflegen() {
        if (Build.VERSION.SDK_INT < 33) return
        val ruf = zurueckRuf ?: return
        val soll = vomBalken != null || appWahl != null
        if (soll == zurueckAngemeldet) return
        if (soll) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, ruf)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(ruf)
        }
        zurueckAngemeldet = soll
    }

    override fun updateGeaendert() {
        imVordergrund { updateKarteNeu() }
    }

    /** Androids Rückfrage „Möchtest du diese App aktualisieren?“ – nur, solange die App vorn ist. */
    override fun bestaetigen(absicht: Intent): Boolean {
        if (!vorn || isFinishing || isDestroyed) return false
        return try {
            startActivity(absicht)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Erst was sofort geht (Erlaubnis, Widget, der Verlauf), dann im Hintergrund rechnen. */
    private fun neuLaden() {
        erlaubt = Nutzungsdaten.erlaubt(this)
        schritt1Zeigen(erlaubt)
        ordnen()
        schritt3Zeigen()
        schritt4Zeigen()
        val heute = heute()
        if (heute != gewaehltAm && zeitraum.enthaelt(gewaehltAm)) zeitraum = Zeitraum.um(reiter, heute)
        gewaehltAm = heute
        statistikNeu()
        faeden.execute {
            val ergebnis = try {
                Aktualisierung.fuerApp(applicationContext)
            } catch (e: RuntimeException) {
                android.util.Log.w(LOG, "Rechnung fehlgeschlagen", e)
                null
            }
            if (ergebnis != null) imVordergrund { zeigen(ergebnis) }
        }
    }

    /** Nach einer Rechnung: Erlaubnis, Stand der Web-Apps – und die Statistik neu aus dem Verlauf. */
    internal fun zeigen(e: Ergebnis) {
        letztes = e
        erlaubt = e.erlaubt
        schritt1Zeigen(e.erlaubt)
        ordnen()
        for (app in WebApp.entries) {
            val zeile = findViewById<LinearLayout>(R.id.web_liste).findViewWithTag<View>(app.schluessel) ?: continue
            zeile.findViewById<TextView>(R.id.stand).text = webStandText(e.web, app.schluessel, e.stand)
        }
        statistikNeu()
    }

    /**
     * Die Reihenfolge der Karten: Statistik oben, darunter die Einrichtung.
     * Ohne Erlaubnis zuerst deren Karte (ohne sie gibt es keine Zahlen), wegen
     * eines Updates geöffnet zuerst die Update-Karte.
     */
    private fun ordnen() {
        val inhalt = findViewById<LinearLayout>(R.id.inhalt)
        val update = findViewById<View>(R.id.update_karte)
        val erlaubnis = findViewById<View>(R.id.erlaubnis_karte)
        val reihe = buildList {
            add(findViewById<View>(R.id.kopf_titel))
            if (updateOben) add(update)
            if (!erlaubt) add(erlaubnis)
            add(findViewById<View>(R.id.statistik_karte))
            if (erlaubt) add(erlaubnis)
            add(findViewById<View>(R.id.web_karte))
            add(findViewById<View>(R.id.widget_karte))
            add(findViewById<View>(R.id.webseiten_karte))
            if (!updateOben) add(update)
            add(findViewById<View>(R.id.loeschen))
            add(findViewById<View>(R.id.version))
        }
        if ((0 until inhalt.childCount).map { inhalt.getChildAt(it) } == reihe) return
        inhalt.removeAllViews()
        for (v in reihe) inhalt.addView(v)
    }

    /** Vorschau und Tests: eine feste Uhr – der Zeitraum beginnt dann bei deren „heute“. */
    internal fun uhrStellen(uhr: () -> Long) {
        jetzt = uhr
        gewaehltAm = heute()
        zeitraum = Zeitraum.um(reiter, gewaehltAm)
        statistikNeu()
    }

    private fun heute(): LocalDate = Instant.ofEpochMilli(jetzt()).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun statistikAnlegen() {
        for ((id, r) in listOf(R.id.reiter_tag to Reiter.TAG, R.id.reiter_woche to Reiter.WOCHE, R.id.reiter_monat to Reiter.MONAT)) {
            findViewById<View>(id).setOnClickListener { reiterWaehlen(r) }
        }
        findViewById<View>(R.id.zurueck).setOnClickListener {
            zeitraum = zeitraum.vorher()
            statistikNeu()
        }
        findViewById<View>(R.id.weiter).setOnClickListener {
            if (zeitraum.hatNachher(heute())) zeitraum = zeitraum.nachher()
            statistikNeu()
        }
        findViewById<Balken>(R.id.balken).beiTipp = { tag -> tagZeigen(tag) }
        findViewById<View>(R.id.alle_apps).setOnClickListener { alleAppsZeigen() }
    }

    /** Ein Reiter von Hand gewählt: gemerkt, und der Zeitraum um den letzten gezeigten Tag (nie nach heute). */
    private fun reiterWaehlen(r: Reiter) {
        speicher.reiter = r.name
        val tag = minOf(zeitraum.bis, heute())
        reiter = r
        zeitraum = Zeitraum.um(r, tag)
        vomBalken = null
        zurueckPflegen()
        statistikNeu()
    }

    /**
     * Ein Balken angetippt: dieser Tag im Reiter Tag (gemerkt wird nur ein von
     * Hand gewählter Reiter). Zurück führt in die Woche bzw. den Monat davor.
     */
    internal fun tagZeigen(tag: LocalDate) {
        if (reiter != Reiter.TAG) vomBalken = zeitraum
        reiter = Reiter.TAG
        zeitraum = Zeitraum.um(Reiter.TAG, tag)
        zurueckPflegen()
        statistikNeu()
    }

    /** Den Zeitraum aus dem Verlauf lesen (im Hintergrund: bis zu 31 kleine Dateien) und zeigen. */
    internal fun statistikNeu(nachher: (() -> Unit)? = null) {
        if (faeden.isShutdown) return
        val z = zeitraum
        val nur = appWahl?.schluessel
        val heute = heute()
        val nr = ++auftrag
        faeden.execute {
            val tage = verlauf.lesen(z.von, minOf(z.bis, heute))
            val a = auswerten(z, tage, heute, verlauf.erster(), nur, Verborgen(applicationContext)::ist)
            imVordergrund {
                if (nr == auftrag) {
                    statistikZeigen(a)
                    nachher?.invoke()
                }
            }
        }
    }

    internal fun statistikZeigen(a: Auswertung) {
        val z = a.zeitraum
        findViewById<View>(R.id.reiter_tag).isSelected = z.reiter == Reiter.TAG
        findViewById<View>(R.id.reiter_woche).isSelected = z.reiter == Reiter.WOCHE
        findViewById<View>(R.id.reiter_monat).isSelected = z.reiter == Reiter.MONAT
        findViewById<TextView>(R.id.zeitraum_titel).text = zeitraumTitel(z, a.heute)
        findViewById<View>(R.id.zurueck).isEnabled = z.hatVorher(a.datenSeit)
        findViewById<View>(R.id.weiter).isEnabled = z.hatNachher(a.heute)

        val ohneAlles = a.datenSeit == null
        findViewById<TextView>(R.id.gesamt).text = if (ohneAlles && !erlaubt) getString(R.string.strich) else gesamtText(a)
        textSetzen(R.id.schnitt, schnittText(a))
        val balken = findViewById<Balken>(R.id.balken)
        if (z.reiter != Reiter.TAG && !ohneAlles) {
            balken.visibility = View.VISIBLE
            balken.setzen(a.tage, a.heute)
        } else {
            balken.visibility = View.GONE
        }
        textSetzen(R.id.luecken, lueckenText(a))

        // Eine App gewählt: oben ihr Name, die Liste weg (die Balken sind ihre Verteilung).
        val gewaehlt = appWahl?.takeIf { it.schluessel == a.nur }
        val kopf = findViewById<View>(R.id.app_kopf)
        val liste = findViewById<LinearLayout>(R.id.app_liste)
        liste.removeAllViews()
        val dichte = resources.displayMetrics.density
        if (gewaehlt != null) {
            // Der Name aus dem Zeitraum (der jüngste), sonst der beim Antippen.
            val name = a.apps.firstOrNull()?.name ?: gewaehlt.name
            kopf.visibility = View.VISIBLE
            findViewById<ImageView>(R.id.app_kopf_symbol).setImageBitmap(Symbole.bild(this, Posten(gewaehlt.schluessel, name, 0L), (28 * dichte).roundToInt()))
            findViewById<TextView>(R.id.app_kopf_name).text = name
            liste.visibility = View.GONE
            // Eine Webseite (nicht „Sonstige“): die lässt sich unter „Sonstige“ legen.
            val seite = gewaehlt.schluessel.takeIf { it.startsWith(WEBSEITE) && it != SONSTIGE_WEBSEITEN }?.removePrefix(WEBSEITE)
            val knopf = findViewById<Button>(R.id.seite_zusammenfassen)
            knopf.visibility = if (seite != null) View.VISIBLE else View.GONE
            knopf.setOnClickListener { if (seite != null) zusammenfassenFragen(seite) }
        } else {
            kopf.visibility = View.GONE
            findViewById<View>(R.id.seite_zusammenfassen).visibility = View.GONE
            liste.visibility = View.VISIBLE
            val symbolPx = (30 * dichte).roundToInt()
            for (p in a.apps) {
                val zeile = LayoutInflater.from(this).inflate(R.layout.zeile_app, liste, false)
                zeile.findViewById<ImageView>(R.id.symbol).setImageBitmap(Symbole.bild(this, p, symbolPx))
                zeile.findViewById<TextView>(R.id.name).text = p.name
                zeile.findViewById<TextView>(R.id.zeit).text = dauerText(p.sekunden)
                val anteil = anteilText(p.sekunden, a.gesamt)
                zeile.findViewById<TextView>(R.id.anteil).text = anteil
                val seite = if (istWebseite(p.schluessel) && p.schluessel != SONSTIGE_WEBSEITEN) ", Webseite" else ""
                zeile.contentDescription = "${p.name}$seite, ${dauerText(p.sekunden)}, $anteil"
                zeile.setOnClickListener { appWaehlen(p) }
                liste.addView(zeile)
            }
        }
        textSetzen(R.id.statistik_hinweis, hinweis(a))
    }

    /** Der leise Satz unten in der Statistik. */
    private fun hinweis(a: Auswertung): String? {
        val e = letztes
        return when {
            !erlaubt -> getString(R.string.statistik_ohne_erlaubnis)
            a.nur == null && a.zeitraum.reiter == Reiter.TAG && a.belegt == 1 && a.apps.isEmpty() ->
                getString(if (a.zeitraum.von == a.heute) R.string.heute_leer else R.string.tag_leer)
            e == null || !a.zeitraum.enthaelt(a.heute) -> null
            e.web.verbunden && (a.nur == null || a.nur == CHROME) -> "Chrome ohne Boxenstopp, Freizeit und Workout. ${statistikStand(e)}."
            else -> "${statistikStand(e)}."
        }
    }

    private fun textSetzen(id: Int, text: String?) {
        val v = findViewById<TextView>(id)
        v.text = text.orEmpty()
        v.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun schritt1Zeigen(erlaubt: Boolean) {
        findViewById<TextView>(R.id.schritt1_titel).setText(if (erlaubt) R.string.schritt1_fertig else R.string.schritt1_titel)
        findViewById<TextView>(R.id.schritt1_text).setText(if (erlaubt) R.string.schritt1_text_fertig else R.string.schritt1_text)
        val sichtbar = if (erlaubt) View.GONE else View.VISIBLE
        findViewById<View>(R.id.schritt1_knopf).visibility = sichtbar
        findViewById<View>(R.id.schritt1_hinweis).visibility = sichtbar
        findViewById<View>(R.id.app_info).visibility = sichtbar
    }

    private fun schritt3Zeigen() {
        val da = Aktualisierung.widgetIds(this).isNotEmpty()
        findViewById<TextView>(R.id.schritt3_titel).setText(if (da) R.string.schritt3_fertig else R.string.schritt3_titel)
        findViewById<TextView>(R.id.schritt3_text).setText(if (da) R.string.schritt3_text_fertig else R.string.schritt3_text)
        findViewById<View>(R.id.schritt3_knopf).visibility = if (da) View.GONE else View.VISIBLE
        if (da) findViewById<View>(R.id.schritt3_anleitung).visibility = View.GONE
    }

    /**
     * Schritt 4, freiwillig: Webseiten in Firefox einzeln. Aus: was es tut, der
     * Knopf zur Eingabehilfe und die Klickfolge. An: ob der Dienst läuft und
     * welche Seite er zuletzt erkannt hat (aus den eigenen Beobachtungen).
     */
    internal fun schritt4Zeigen() {
        val an = WebseitenStatus.eingeschaltet(this)
        findViewById<TextView>(R.id.schritt4_titel).setText(if (an) R.string.schritt4_fertig else R.string.schritt4_titel)
        findViewById<TextView>(R.id.schritt4_text).setText(if (an) R.string.schritt4_text_fertig else R.string.schritt4_text)
        findViewById<Button>(R.id.schritt4_knopf).setText(if (an) R.string.schritt4_knopf_fertig else R.string.schritt4_knopf)
        findViewById<View>(R.id.schritt4_anleitung).visibility = if (an) View.GONE else View.VISIBLE
        val stand = when {
            !an -> null
            !WebseitenStatus.laeuft(this) -> getString(R.string.schritt4_haengt)
            else -> zuletztText(Beobachtungen(this), jetzt())
        }
        textSetzen(R.id.schritt4_stand, stand)
        val eigene = Verborgen(this).eigene().size
        textSetzen(R.id.schritt4_diskret, if (!an) null else if (eigene == 0) getString(R.string.schritt4_diskret) else getString(R.string.schritt4_diskret_eigene, getString(R.string.schritt4_diskret), eigene))
        findViewById<View>(R.id.schritt4_wieder_zeigen).visibility = if (an && eigene > 0) View.VISIBLE else View.GONE
    }

    /** Eine Webseite unter „Sonstige“ legen – nach Rückfrage, weil ihr Name dabei aus dem Verlauf gelöscht wird. */
    private fun zusammenfassenFragen(domain: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.seite_zusammenfassen_titel, domain))
            .setMessage(R.string.seite_zusammenfassen_text)
            .setPositiveButton(R.string.seite_zusammenfassen_ja) { _, _ -> zusammenfassen(domain) }
            .setNegativeButton(R.string.abbrechen, null)
            .show()
    }

    /** Merken, die Namen aus Verlauf und Beobachtungen löschen (unter der Sperre der Rechnung), Widget neu, zurück zu allen Apps. */
    internal fun zusammenfassen(domain: String) {
        val ctx = applicationContext
        faeden.execute {
            Aktualisierung.allein {
                Verborgen(ctx).hinzufuegen(domain)
                namenEntfernen(ctx) { it == domain }
            }
            Aktualisierung.neuZeichnen(ctx)
            imVordergrund {
                Toast.makeText(this, getString(R.string.seite_zusammengefasst, domain), Toast.LENGTH_SHORT).show()
                alleAppsZeigen()
                schritt4Zeigen()
            }
        }
    }

    /**
     * „Läuft. Zuletzt erkannt: spiegel.de in Firefox, 19:46; in Chrome noch
     * keine Seite.“ – je Browser, damit man sieht, ob beide gelesen werden.
     */
    private fun zuletztText(b: Beobachtungen, jetzt: Long): String {
        val teile = listOf("Firefox" to FIREFOXE, "Chrome" to CHROMES).map { (name, pakete) ->
            b.zuletztErkannt(pakete, Verborgen(this)::ist)?.let { "${it.domain} in $name, ${zeitpunktText(it.zeit, jetzt)}" } ?: "in $name noch keine Seite"
        }
        val zuletzt = if (teile.all { it.startsWith("in ") }) getString(R.string.schritt4_noch_nichts) else getString(R.string.schritt4_zuletzt, teile.joinToString("; "))
        val heute = Instant.ofEpochMilli(jetzt).atZone(ZoneId.systemDefault()).toLocalDate()
        return listOfNotNull(zuletzt, aufschluesselungText(b.aufschluesselung(heute))).joinToString("\n")
    }

    private fun webZeilenAnlegen() {
        val liste = findViewById<LinearLayout>(R.id.web_liste)
        for (app in WebApp.entries) {
            val z = LayoutInflater.from(this).inflate(R.layout.zeile_web, liste, false)
            z.tag = app.schluessel
            z.findViewById<ImageView>(R.id.symbol).setImageResource(app.symbol)
            z.findViewById<TextView>(R.id.name).text = app.anzeigename
            z.findViewById<TextView>(R.id.stand).text = "…"
            z.findViewById<Button>(R.id.verbinden).setOnClickListener { verbinden(app, it) }
            liste.addView(z)
        }
    }

    /** Erst sicherstellen, dass Supabase die Kennung kennt – dann die Web-App mit dem Link öffnen. */
    private fun verbinden(app: WebApp, knopf: View) {
        knopf.isEnabled = false
        faeden.execute {
            val kennung = speicher.kennung
            val bereit = speicher.angemeldet || try {
                Supabase.anmelden(kennung).also { if (it) speicher.angemeldet = true }
            } catch (e: IOException) {
                false
            }
            imVordergrund {
                knopf.isEnabled = true
                if (bereit) {
                    versuchen(verbindenAbsichten(app.link(kennung)))
                } else {
                    Toast.makeText(this, R.string.verbinden_ohne_netz, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Möglichst gleich auf der Seite dieser App, sonst die Liste, sonst App-Info. */
    private fun nutzungszugriffOeffnen() = einstellungOeffnen(
        listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri()),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
        ),
    )

    private fun einstellungOeffnen(versuche: List<Intent>) {
        for (intent in versuche) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // nächster Versuch
            } catch (e: SecurityException) {
                // nächster Versuch
            }
        }
    }

    private fun widgetHinzufuegen() {
        val awm = AppWidgetManager.getInstance(this)
        val angefragt = awm.isRequestPinAppWidgetSupported &&
            awm.requestPinAppWidget(ComponentName(this, BildschirmzeitWidget::class.java), null, null)
        if (!angefragt) findViewById<View>(R.id.schritt3_anleitung).visibility = View.VISIBLE
    }

    /** `version`: nur die Vorschau (AppVorschauTest) setzt eine feste, damit die Bilder nicht an der Laufnummer hängen. */
    internal fun versionZeigen(version: String) {
        findViewById<TextView>(R.id.version).text = getString(R.string.version, version)
    }

    /** Die Update-Karte nach dem Stand von Selbstupdate (im Hintergrund gelesen: Android fragen dauert). */
    private fun updateKarteNeu() {
        if (faeden.isShutdown) return
        faeden.execute {
            val anzeige = UpdateRegeln.anzeige(Selbstupdate.lage(applicationContext))
            imVordergrund { updateZeigen(anzeige) }
        }
    }

    internal fun updateZeigen(a: UpdateAnzeige) {
        findViewById<View>(R.id.update_karte).visibility = View.VISIBLE
        findViewById<TextView>(R.id.update_titel).text = a.titel
        findViewById<TextView>(R.id.update_text).text = a.text
        knopfZeigen(findViewById(R.id.update_knopf), a.knopf)
        knopfZeigen(findViewById(R.id.update_knopf2), a.zweiter)
        textSetzen(R.id.update_diagnose, a.diagnose)
        if (updateOben) findViewById<ScrollView>(R.id.wurzel).scrollTo(0, 0)
    }

    private fun knopfZeigen(knopf: Button, was: UpdateKnopf?) {
        knopf.tag = was
        knopf.visibility = if (was == null) View.GONE else View.VISIBLE
        knopf.text = was?.text.orEmpty()
        knopf.isEnabled = was != UpdateKnopf.WARTEN
    }

    private fun updateKnopf(knopf: UpdateKnopf?) {
        when (knopf) {
            UpdateKnopf.EINRICHTEN -> einstellungOeffnen(installQuelleAbsichten(packageName))
            UpdateKnopf.SPERRE -> einstellungOeffnen(sperreAbsichten())
            UpdateKnopf.INSTALLIEREN, UpdateKnopf.NOCHMAL -> {
                findViewById<Button>(R.id.update_knopf).isEnabled = false
                findViewById<Button>(R.id.update_knopf2).isEnabled = false
                faeden.execute { Selbstupdate.jetztInstallieren(applicationContext) }
            }
            UpdateKnopf.BROWSER -> oeffnen(Intent(Intent.ACTION_VIEW, NeueVersion.APK.toUri()))
            UpdateKnopf.WARTEN, null -> Unit
        }
    }

    private fun loeschenFragen() {
        AlertDialog.Builder(this)
            .setTitle(R.string.loeschen_titel)
            .setMessage(R.string.loeschen_text)
            .setPositiveButton(R.string.loeschen_ja) { _, _ -> loeschen() }
            .setNegativeButton(R.string.abbrechen, null)
            .show()
    }

    /** Supabase löscht Anmeldung und Zahlen, der Verlauf ist leer; danach eine neue Kennung. Die Web-Apps trennen sich von selbst. */
    private fun loeschen() {
        faeden.execute {
            val geloescht = Rechner.allesLoeschen(applicationContext)
            imVordergrund {
                Toast.makeText(this, if (geloescht) R.string.geloescht else R.string.loeschen_ohne_netz, Toast.LENGTH_LONG).show()
                if (geloescht) {
                    zeitraum = Zeitraum.um(reiter, heute())
                    vomBalken = null
                    zurueckPflegen()
                }
                neuLaden()
            }
        }
    }

    private fun oeffnen(intent: Intent) = versuchen(listOf(intent))

    /** Der erste Versuch, den Android starten kann; sonst „Kein Browser gefunden“. */
    private fun versuchen(absichten: List<Intent>) {
        for (intent in absichten) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // nächster Versuch
            }
        }
        Toast.makeText(this, R.string.kein_browser, Toast.LENGTH_LONG).show()
    }

    private fun imVordergrund(arbeit: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) arbeit() }
    }

    private companion object {
        const val ZUSTAND_REITER = "reiter"
        const val ZUSTAND_VON = "von"
        const val ZUSTAND_AM = "gewaehltAm"
        const val ZUSTAND_UPDATE = "updateOben"
        const val ZUSTAND_BALKEN_REITER = "balkenReiter"
        const val ZUSTAND_BALKEN_VON = "balkenVon"
        const val ZUSTAND_APP = "app"
        const val ZUSTAND_APP_NAME = "appName"
    }
}

/**
 * Der Verbinden-Link geht zuerst ausdrücklich an Chrome: Dort laufen die drei
 * Web-Apps, und nur dort teilen sie sich den localStorage, in den der Link
 * die Kennung legt. Im Standardbrowser (auf Samsung oft Samsung Internet)
 * landete sie im Speicher dieses Browsers, und die Web-Apps in Chrome
 * blieben unverbunden. Gibt es kein Chrome, dann doch der Standardbrowser.
 */
fun verbindenAbsichten(link: String): List<Intent> {
    val uri = link.toUri()
    return listOf(Intent(Intent.ACTION_VIEW, uri).setPackage(CHROME), Intent(Intent.ACTION_VIEW, uri))
}

/** „heute 23 Min., zuletzt 21:40“ oder „noch nicht verbunden“ – Schritt 2 der App. */
fun webStandText(web: WebDaten, app: String, jetzt: Long): String {
    val zuletzt = web.zuletzt[app]
    val text = if (zuletzt == null) {
        "noch nicht verbunden"
    } else {
        "heute ${dauerText(web.heute[app] ?: 0L)}, zuletzt ${zeitpunktText(zuletzt, jetzt)}"
    }
    return if (web.ohneNetz) "$text · ohne Netz" else text
}
/**
 * „Chrome heute 2 Std. 4 Min.: 1 Std. 30 Min. Seiten, 10 Min. ohne Namen,
 * 24 Min. nicht erfasst.“ – je Browser (ab einer Minute), Teile ab einer
 * Minute; null, wenn heute kein Browser lief. Tobi, 16.11.: „Zwei Stunden Chrome?“
 */
fun aufschluesselungText(zeiten: Map<String, BrowserZeit>): String? {
    val zeilen = ArrayList<String>()
    var nichtErfasst = false
    for ((name, pakete) in listOf("Firefox" to FIREFOXE, "Chrome" to CHROMES)) {
        val z = zeiten.filterKeys { it in pakete }.values.fold(BrowserZeit()) { a, b -> a + b }
        if (z.gesamt < 60) continue
        val teile = listOf(z.seiten to "Seiten", z.ohneName to "ohne Namen", z.keinWeb to "Web-Apps", z.nichtErfasst to "nicht erfasst")
            .filter { it.first >= 60 }.map { (s, was) -> "${dauerText(s)} $was" }
        if (z.nichtErfasst >= 60) nichtErfasst = true
        zeilen += "$name heute ${dauerText(z.gesamt)}: ${teile.joinToString(", ").ifEmpty { "alles unter einer Minute" }}."
    }
    if (zeilen.isEmpty()) return null
    if (nichtErfasst) zeilen += "„Nicht erfasst“: Die Bedienungshilfe lief da nicht oder hat den Browser nicht erkannt."
    return zeilen.joinToString("\n")
}
