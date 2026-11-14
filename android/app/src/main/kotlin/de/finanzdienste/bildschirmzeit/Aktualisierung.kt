package de.finanzdienste.bildschirmzeit

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Wann das Widget neu gerechnet wird: alle 15 Minuten (WorkManager, auch nach
 * einem Neustart), beim Antippen, beim Hinzufügen, wenn der Startbildschirm
 * die Größe ändert und wenn die App offen ist. Nie zwei Rechnungen zugleich.
 */
object Aktualisierung {
    private const val ARBEIT = "widget-aktualisieren"
    private val sperre = ReentrantLock()

    /** Alle 15 Minuten; KEEP: Ist schon geplant, bleibt es dabei. */
    fun einplanen(context: Context) {
        val anfrage = PeriodicWorkRequest.Builder(WidgetArbeit::class.java, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(ARBEIT, ExistingPeriodicWorkPolicy.KEEP, anfrage)
    }

    fun abbestellen(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ARBEIT)
    }

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, BildschirmzeitWidget::class.java))

    /**
     * Für Antippen und WorkManager. `schnell`: erst ohne Netz zeichnen (sofort
     * etwas Neues zu sehen), dann mit. Rechnet gerade schon jemand, bleibt es
     * bei dessen Ergebnis.
     */
    fun widgetsNeu(context: Context, schnell: Boolean, netz: Boolean = true) {
        if (!sperre.tryLock()) return
        try {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            if (schnell || !netz) WidgetAnsicht.zeigen(context, Rechner.berechnen(context, netz = false), ids)
            if (netz) WidgetAnsicht.zeigen(context, Rechner.berechnen(context, netz = true), ids)
        } finally {
            sperre.unlock()
        }
    }

    /**
     * Etwas, das nicht mitten in eine Rechnung fallen darf – „Alle Daten
     * löschen“: Sonst könnte ein laufender Lauf die alte Kennung gleich wieder
     * anmelden.
     */
    fun <T> allein(arbeit: () -> T): T {
        sperre.lock()
        try {
            return arbeit()
        } finally {
            sperre.unlock()
        }
    }

    /** Für die App: wartet, falls gerade gerechnet wird, und zeichnet die Widgets mit. */
    fun fuerApp(context: Context): Ergebnis {
        sperre.lock()
        try {
            val ergebnis = Rechner.berechnen(context, netz = true)
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) WidgetAnsicht.zeigen(context, ergebnis, ids)
            return ergebnis
        } finally {
            sperre.unlock()
        }
    }
}

/** Der 15-Minuten-Lauf. */
class WidgetArbeit(context: Context, parameter: WorkerParameters) : Worker(context, parameter) {
    override fun doWork(): Result {
        try {
            Aktualisierung.widgetsNeu(applicationContext, schnell = false)
        } catch (e: RuntimeException) {
            Log.w(LOG, "Widget-Lauf fehlgeschlagen", e)
        }
        return Result.success()
    }
}

/**
 * Das Widget. Antippen schickt AKTUALISIEREN an diesen Empfänger; gerechnet
 * wird gleich hier (goAsync, eigener Faden) – das kommt sofort an, auch wenn
 * Samsungs Akku-Optimierung WorkManager bremst.
 */
class BildschirmzeitWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetAnsicht.AKTUALISIEREN) {
            imHintergrund { Aktualisierung.widgetsNeu(context.applicationContext, schnell = true) }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Aktualisierung.einplanen(context)
        imHintergrund { Aktualisierung.widgetsNeu(context.applicationContext, schnell = true) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        imHintergrund { Aktualisierung.widgetsNeu(context.applicationContext, schnell = false, netz = false) }
    }

    override fun onEnabled(context: Context) {
        Aktualisierung.einplanen(context)
    }

    override fun onDisabled(context: Context) {
        Aktualisierung.abbestellen(context)
    }

    private fun imHintergrund(arbeit: () -> Unit) {
        val ausstehend = goAsync()
        Thread {
            try {
                arbeit()
            } catch (e: RuntimeException) {
                Log.w(LOG, "Widget nicht aktualisiert", e)
            } finally {
                ausstehend.finish()
            }
        }.start()
    }
}
