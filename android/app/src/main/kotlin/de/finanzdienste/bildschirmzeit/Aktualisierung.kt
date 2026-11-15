package de.finanzdienste.bildschirmzeit

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Wann das Widget neu gerechnet wird: der nicht weckende Wecker (alle 2
 * Minuten, solange das Handy benutzt wird, und gleich nach dem Einschalten –
 * Takt.kt), WorkManager alle 15 Minuten (auch nach einem Neustart; prüft dabei
 * auch auf Updates), beim Hinzufügen, wenn der Startbildschirm die Größe ändert
 * und wenn die App offen ist – Antippen öffnet die App (WidgetAnsicht.oeffnen),
 * die beim Öffnen sofort mit Netz rechnet. Nie zwei Rechnungen zugleich.
 */
object Aktualisierung {
    private const val ARBEIT = "widget-aktualisieren"
    private val sperre = ReentrantLock()

    /** WorkManager alle 15 Minuten (KEEP: Ist schon geplant, bleibt es dabei) und, wenn es ein Widget gibt, der Wecker. */
    fun einplanen(context: Context) {
        val anfrage = PeriodicWorkRequest.Builder(WidgetArbeit::class.java, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(ARBEIT, ExistingPeriodicWorkPolicy.KEEP, anfrage)
        if (widgetIds(context).isNotEmpty()) Wecker.einplanen(context) else Wecker.abbestellen(context)
    }

    fun abbestellen(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ARBEIT)
        Wecker.abbestellen(context)
    }

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, BildschirmzeitWidget::class.java))

    /**
     * Rechnet die Widgets neu, so oft und mit so viel Netz, wie `Takt.plan` für
     * den `anlass` sagt. Bei Anlass.TIPPEN (ein Widget kam gerade auf den
     * Startbildschirm) erst ohne Netz zeichnen (sofort etwas zu sehen), dann
     * mit. Rechnet gerade schon jemand, bleibt es bei dessen Ergebnis. true:
     * gerechnet.
     */
    fun imTakt(context: Context, anlass: Anlass, jetzt: Long = System.currentTimeMillis()): Boolean {
        if (!sperre.tryLock()) return false
        try {
            val ids = widgetIds(context)
            if (ids.isEmpty()) {
                // Kein Widget mehr: Dann soll auch kein Wecker mehr klingeln.
                Wecker.abbestellen(context)
                return false
            }
            val speicher = Speicher(context)
            val plan = Takt.plan(anlass, jetzt, speicher.gerechnet, speicher.netzVersuch, interaktiv(context))
            if (!plan.rechnen) return false
            if (anlass == Anlass.TIPPEN) WidgetAnsicht.zeigen(context, Rechner.berechnen(context, netz = false, jetzt = jetzt), ids)
            WidgetAnsicht.zeigen(context, Rechner.berechnen(context, netz = plan.netz, jetzt = jetzt), ids)
            speicher.gerechnet = jetzt
            return true
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

    /**
     * Nach einem Schritt des Updates (Sitzung gemerkt, Androids Status da): die
     * Widgets ohne Netz neu zeichnen, damit Hinweis und Antippen (mit oder ohne
     * CLEAR_TOP) gleich passen, nicht erst beim nächsten Wecker. Wartet, falls
     * gerade gerechnet wird – nie auf dem Hauptfaden aufrufen.
     */
    fun neuZeichnen(context: Context) {
        sperre.lock()
        try {
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) WidgetAnsicht.zeigen(context, Rechner.berechnen(context, netz = false), ids)
        } finally {
            sperre.unlock()
        }
    }

    /** Für die App: wartet, falls gerade gerechnet wird, und zeichnet die Widgets mit. */
    fun fuerApp(context: Context): Ergebnis {
        sperre.lock()
        try {
            val jetzt = System.currentTimeMillis()
            val ergebnis = Rechner.berechnen(context, netz = true, jetzt = jetzt)
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) {
                WidgetAnsicht.zeigen(context, ergebnis, ids)
                Speicher(context).gerechnet = jetzt
            }
            return ergebnis
        } finally {
            sperre.unlock()
        }
    }

    /** Bildschirm an? Ohne PowerManager lieber ja (dann gilt der 5-Minuten-Takt). */
    private fun interaktiv(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
}

/** Der 15-Minuten-Lauf: das Widget, danach (höchstens alle 4 Stunden bei GitHub) das Update. */
class WidgetArbeit(context: Context, parameter: WorkerParameters) : Worker(context, parameter) {
    override fun doWork(): Result {
        try {
            Aktualisierung.imTakt(applicationContext, Anlass.ARBEIT)
        } catch (e: RuntimeException) {
            Log.w(LOG, "Widget-Lauf fehlgeschlagen: ${e.javaClass.simpleName}")
        }
        try {
            Selbstupdate.imHintergrund(applicationContext)
        } catch (e: RuntimeException) {
            Log.w(LOG, "Update-Lauf fehlgeschlagen: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }
}

/**
 * Das Widget. Gerechnet wird gleich hier (goAsync, eigener Faden) – das kommt
 * sofort an, auch wenn Samsungs Akku-Optimierung WorkManager bremst. Antippen
 * kommt hier nicht an: Es öffnet die App (Tobi, 15.11.: „Wenn ich auf das
 * widget tippe soll ich mir auch die Wochen und Monatsnutzung nach App
 * aufgeteilt und so anzeigen lassen können“), und die rechnet beim Öffnen.
 */
class BildschirmzeitWidget : AppWidgetProvider() {

    /** Neu auf dem Startbildschirm (oder nach Neustart und Update): sofort und mit Netz. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        imHintergrund {
            Aktualisierung.einplanen(app)
            Aktualisierung.imTakt(app, Anlass.TIPPEN)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        imHintergrund { Aktualisierung.imTakt(context.applicationContext, Anlass.GROESSE) }
    }

    override fun onEnabled(context: Context) {
        Aktualisierung.einplanen(context)
    }

    override fun onDisabled(context: Context) {
        Aktualisierung.abbestellen(context)
    }
}
