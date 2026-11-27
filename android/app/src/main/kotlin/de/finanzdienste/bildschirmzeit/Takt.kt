package de.finanzdienste.bildschirmzeit

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/** Warum das Widget gerade neu gerechnet werden soll. */
enum class Anlass {
    /** Ein Widget kam neu auf den Startbildschirm: sofort und mit Netz. Antippen öffnet seit v7 die App (bis v6 hieß dieser Anlass genau das). */
    TIPPEN,

    /** Der nicht weckende Wecker – alle 2 Minuten, solange das Handy benutzt wird, und gleich nach dem Einschalten. */
    WECKER,

    /** WorkManager alle 15 Minuten, auch wenn das Handy liegt (z. B. für Mitternacht). */
    ARBEIT,

    /** Nach einem Neustart oder Update der App. */
    START,

    /** Der Startbildschirm hat die Größe geändert: nur neu zeichnen. */
    GROESSE,
}

/**
 * Tobi, 15.11.: „Alter stand? Keinen bock immer zu aktualisieren“ – das Widget
 * soll von selbst frisch sein, wenn er hinschaut. Wie oft gerechnet wird und
 * wann dabei Supabase gefragt wird (rein, getestet in TaktTest):
 *
 * - Rechnen ist billig (Androids Ereignisse seit Mitternacht, ein paar
 *   Millisekunden) – der Wecker rechnet bei jedem Klingeln, nur nicht zweimal
 *   in derselben Minute (wenn gerade ein neues Widget oder die App gerechnet hat).
 * - Supabase höchstens alle 5 Minuten: Öfter melden die Web-Apps auch nicht.
 *   Dazwischen gilt der Zwischenspeicher. Bei dunklem Bildschirm (WorkManager
 *   nachts) höchstens einmal in der Stunde – dann schaut niemand hin.
 * - Ein neues Widget und das Öffnen der App (auch über das Widget) fragen
 *   immer: Wer hinschaut, will es genau wissen.
 */
object Takt {
    /** Abstand des Weckers. Android legt ungenaue Wecker zusammen: es klingelt nach 2 bis 3½ Minuten. */
    const val WECKER_MS = 2 * 60_000L

    /** Der Wecker rechnet nicht, wenn die letzte Rechnung keine Minute her ist. */
    const val RECHNEN_MS = 60_000L

    /** Supabase höchstens so oft, solange der Bildschirm an ist … */
    const val NETZ_MS = 5 * 60_000L

    /** … und so oft, wenn er aus ist. */
    const val NETZ_DUNKEL_MS = 60 * 60_000L

    data class Plan(val rechnen: Boolean, val netz: Boolean)

    /**
     * `gerechnet`/`netzVersuch`: wann zuletzt gerechnet bzw. Supabase gefragt
     * wurde (ms, 0 = noch nie). `interaktiv`: Bildschirm an.
     */
    fun plan(anlass: Anlass, jetzt: Long, gerechnet: Long, netzVersuch: Long, interaktiv: Boolean): Plan = when (anlass) {
        Anlass.TIPPEN -> Plan(rechnen = true, netz = true)
        Anlass.GROESSE -> Plan(rechnen = true, netz = false)
        Anlass.WECKER ->
            if (seit(gerechnet, jetzt) < RECHNEN_MS) {
                Plan(rechnen = false, netz = false)
            } else {
                Plan(rechnen = true, netz = interaktiv && seit(netzVersuch, jetzt) >= NETZ_MS)
            }
        Anlass.ARBEIT, Anlass.START ->
            Plan(rechnen = true, netz = seit(netzVersuch, jetzt) >= if (interaktiv) NETZ_MS else NETZ_DUNKEL_MS)
    }

    /** Wie lange `damals` her ist; noch nie oder Uhr zurückgestellt → unendlich lange. */
    fun seit(damals: Long, jetzt: Long): Long = if (damals <= 0L || jetzt < damals) Long.MAX_VALUE else jetzt - damals
}

/**
 * Der Wecker fürs Widget: AlarmManager, ungenau und wiederholt, Art
 * ELAPSED_REALTIME – **nicht** _WAKEUP. Er weckt das Handy nie und braucht
 * keine Erlaubnis für genaue Wecker. Ist der Bildschirm aus, hält Android ihn
 * zurück und stellt ihn zu, sobald der Bildschirm wieder angeht (AOSP
 * AlarmManagerService, interactiveStateChangedLocked) – genau dann, wenn Tobi
 * hinschaut. Nach einem Neustart sind Wecker weg (Hintergrund plant neu),
 * ein Update der App überleben sie (geplant wird trotzdem neu).
 */
object Wecker {
    const val TAKT = "de.finanzdienste.bildschirmzeit.TAKT"

    fun einplanen(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + Takt.WECKER_MS,
            Takt.WECKER_MS,
            absicht(context),
        )
    }

    fun abbestellen(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(absicht(context))
    }

    /** Explizit an den eigenen, nicht exportierten Empfänger. */
    internal fun absicht(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        2,
        Intent(context, Hintergrund::class.java).setAction(TAKT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Nicht exportiert: der Wecker (eigener PendingIntent), BOOT_COMPLETED und
 * MY_PACKAGE_REPLACED (beide schickt das System, das darf auch an nicht
 * exportierte Empfänger). Nach Neustart und Update wird neu eingeplant. Dazu
 * die Schalter im Kopf des Widgets (Alle, Produktiv, Unproduktiv; eigener
 * PendingIntent): Die Wahl wird gemerkt und das Widget gleich ohne Netz neu
 * gezeichnet – hier statt beim exportierten Widget, so kann keine fremde App
 * umschalten.
 */
class Hintergrund : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Wecker.TAKT -> imHintergrund { Aktualisierung.imTakt(app, Anlass.WECKER) }
            WidgetAnsicht.FILTER_AKTION -> {
                // Gleich hier gemerkt (in der Reihenfolge der Tipps), gezeichnet im Hintergrund.
                Speicher(app).widgetFilter = WidgetFilter.von(intent.getStringExtra(WidgetAnsicht.FILTER_EXTRA)).name
                imHintergrund { Aktualisierung.neuZeichnen(app) }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val update = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                imHintergrund {
                    if (update) Selbstupdate.nachUpdate(app)
                    Aktualisierung.einplanen(app)
                    Aktualisierung.imTakt(app, Anlass.START)
                }
            }
        }
    }
}

/** goAsync und ein eigener Faden – Rechnen und Netz gehören nicht auf den Hauptfaden. */
internal fun BroadcastReceiver.imHintergrund(arbeit: () -> Unit) {
    val ausstehend: BroadcastReceiver.PendingResult? = goAsync()
    Thread {
        try {
            arbeit()
        } catch (e: RuntimeException) {
            Log.w(LOG, "Hintergrund: ${e.javaClass.simpleName}")
        } finally {
            ausstehend?.finish()
        }
    }.start()
}
