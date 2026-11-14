package de.finanzdienste.bildschirmzeit

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Der App-Bildschirm: einrichten, verbinden, heute ansehen, Update, löschen. */
class MainActivity : Activity() {
    private lateinit var faeden: ExecutorService
    private lateinit var speicher: Speicher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        faeden = Executors.newCachedThreadPool()
        speicher = Speicher(this)

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
        findViewById<Button>(R.id.update_knopf).setOnClickListener {
            oeffnen(Intent(Intent.ACTION_VIEW, NeueVersion.APK.toUri()))
        }
        findViewById<Button>(R.id.loeschen).setOnClickListener { loeschenFragen() }
        findViewById<TextView>(R.id.version).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        webZeilenAnlegen()

        Aktualisierung.einplanen(this)
        faeden.execute {
            val neu = runCatching { NeueVersion.pruefen(BuildConfig.VERSION_CODE) }.getOrNull()
            if (neu != null) imVordergrund { updateZeigen(neu) }
        }
    }

    override fun onResume() {
        super.onResume()
        neuLaden()
    }

    override fun onDestroy() {
        faeden.shutdown()
        super.onDestroy()
    }

    /** Erst was sofort geht (Erlaubnis, Widget), dann im Hintergrund rechnen. */
    private fun neuLaden() {
        schritt1Zeigen(Nutzungsdaten.erlaubt(this))
        schritt3Zeigen()
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

    internal fun zeigen(e: Ergebnis) {
        schritt1Zeigen(e.erlaubt)
        val groesse = (104 * resources.displayMetrics.density).roundToInt()
        findViewById<ImageView>(R.id.kopf_ring).setImageBitmap(ringBild(groesse, ringWerte(e.posten), ringFarben()))
        findViewById<TextView>(R.id.kopf_gesamt).text = if (e.erlaubt) WidgetAnsicht.ringZeichen(e.gesamt) else getString(R.string.strich)
        findViewById<TextView>(R.id.kopf_stand).text =
            if (e.erlaubt) "Heute, ${WidgetAnsicht.standText(e)}" else getString(R.string.heute_ohne_erlaubnis)

        for (app in WebApp.entries) {
            val zeile = findViewById<LinearLayout>(R.id.web_liste).findViewWithTag<View>(app.schluessel) ?: continue
            zeile.findViewById<TextView>(R.id.stand).text = webStandText(e.web, app.schluessel, e.stand)
        }

        val liste = findViewById<LinearLayout>(R.id.app_liste)
        liste.removeAllViews()
        val hinweis = findViewById<TextView>(R.id.heute_hinweis)
        hinweis.text = when {
            !e.erlaubt -> getString(R.string.heute_ohne_erlaubnis)
            e.posten.isEmpty() -> getString(R.string.heute_leer)
            e.web.verbunden -> "Chrome ohne Boxenstopp, Freizeit und Workout. ${WidgetAnsicht.standText(e)}."
            else -> "${WidgetAnsicht.standText(e)}."
        }
        val farben = ringFarben()
        val symbolPx = (30 * resources.displayMetrics.density).roundToInt()
        for ((i, p) in e.posten.withIndex()) {
            val z = LayoutInflater.from(this).inflate(R.layout.zeile_app, liste, false)
            val punkt = z.findViewById<ImageView>(R.id.punkt)
            if (i < 3) punkt.setColorFilter(farben.top[i]) else punkt.visibility = View.INVISIBLE
            z.findViewById<ImageView>(R.id.symbol).setImageBitmap(Symbole.bild(this, p, symbolPx))
            z.findViewById<TextView>(R.id.name).text = p.name
            z.findViewById<TextView>(R.id.zeit).text = dauerText(p.sekunden)
            liste.addView(z)
        }
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
                    oeffnen(Intent(Intent.ACTION_VIEW, app.link(kennung).toUri()))
                } else {
                    Toast.makeText(this, R.string.verbinden_ohne_netz, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Möglichst gleich auf der Seite dieser App, sonst die Liste, sonst App-Info. */
    private fun nutzungszugriffOeffnen() {
        val versuche = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri()),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
        )
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

    internal fun updateZeigen(laufnummer: Int) {
        findViewById<View>(R.id.update_karte).visibility = View.VISIBLE
        findViewById<TextView>(R.id.update_text).text = getString(R.string.update_text, laufnummer, BuildConfig.VERSION_NAME)
    }

    private fun loeschenFragen() {
        AlertDialog.Builder(this)
            .setTitle(R.string.loeschen_titel)
            .setMessage(R.string.loeschen_text)
            .setPositiveButton(R.string.loeschen_ja) { _, _ -> loeschen() }
            .setNegativeButton(R.string.abbrechen, null)
            .show()
    }

    /** Supabase löscht Anmeldung und Zahlen; danach eine neue Kennung. Die Web-Apps trennen sich von selbst. */
    private fun loeschen() {
        faeden.execute {
            val geloescht = Aktualisierung.allein {
                val ok = try {
                    Supabase.loeschen(speicher.kennung)
                    true
                } catch (e: IOException) {
                    false
                }
                if (ok) {
                    val neu = speicher.neueKennungSetzen()
                    try {
                        if (Supabase.anmelden(neu)) speicher.angemeldet = true
                    } catch (e: IOException) {
                        // holt der nächste Lauf nach
                    }
                }
                ok
            }
            imVordergrund {
                Toast.makeText(this, if (geloescht) R.string.geloescht else R.string.loeschen_ohne_netz, Toast.LENGTH_LONG).show()
                neuLaden()
            }
        }
    }

    private fun oeffnen(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.kein_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun ringFarben(): RingFarben =
        if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) RingFarben.DUNKEL else RingFarben.HELL

    private fun imVordergrund(arbeit: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) arbeit() }
    }
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
