package de.finanzdienste.bildschirmzeit

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Die Bedienungshilfe „Bildschirmzeit: Webseiten“ (freiwillig): Sie bekommt
 * nur Ereignisse von Firefox (packageNames in xml/webseiten_dienst.xml),
 * liest nur die Adresszeile (Leiste) und merkt sich nur die Domain mit
 * Uhrzeit (Beobachtungen) – nie Adresse, Titel oder Inhalt, nichts im Log,
 * nichts im Netz. Sie handelt nie: keine Gesten, kein Tippen, kein Text.
 *
 * Gedrosselt, weil ein laufendes Video ständig Inhaltsereignisse schickt: Auf
 * einen Fensterwechsel wird nach FENSTER_MS gelesen, auf Inhaltsereignisse
 * höchstens alle INHALT_ABSTAND_MS (Drossel). Gelesen wird auf einem eigenen
 * Faden: Knotenabfragen sind synchrone Aufrufe ins Firefox-Fenster, und der
 * Prozess teilt sich den Hauptfaden mit Widget und Empfängern.
 */
class WebseitenDienst : AccessibilityService() {
    private var faden: HandlerThread? = null
    private var handler: Handler? = null
    private val geplant = AtomicBoolean(false)

    @Volatile
    private var letzteAbfrage = 0L

    private val pruefen = Runnable {
        geplant.set(false)
        jetztLesen()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val f = HandlerThread("Webseiten").also { it.start() }
        faden = f
        handler = Handler(f.looper)
        // Was vorher galt, weiß der Dienst nicht: Bis zur ersten Beobachtung bleibt die Zeit bei Firefox.
        handler?.post { Beobachtungen(this).merken(Beobachtung(System.currentTimeMillis(), ALLE, null)) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.packageName?.toString() !in FIREFOXE) return
        val h = handler ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                h.removeCallbacks(pruefen)
                geplant.set(true)
                h.postDelayed(pruefen, Drossel.FENSTER_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (geplant.compareAndSet(false, true)) {
                h.postDelayed(pruefen, Drossel.wartezeit(SystemClock.uptimeMillis(), letzteAbfrage))
            }
            // Andere Arten bestellt der Dienst nicht (xml/webseiten_dienst.xml).
            else -> Unit
        }
    }

    private fun jetztLesen() {
        letzteAbfrage = SystemClock.uptimeMillis()
        val befund = try {
            val wurzel = rootInActiveWindow ?: return
            val paket = wurzel.packageName?.toString()
            // Das aktive Fenster kann schon eine andere App sein.
            if (paket == null || paket !in FIREFOXE) return
            paket to Leiste.lesen(InfoKnoten(wurzel), paket)
        } catch (e: RuntimeException) {
            // Fenster weg, Knoten veraltet: beim nächsten Ereignis wieder.
            return
        }
        val (paket, b) = befund
        val jetzt = System.currentTimeMillis()
        when (b) {
            is Befund.Seite -> Beobachtungen(this).merken(Beobachtung(jetzt, paket, b.domain))
            Befund.Keine, Befund.Privat -> Beobachtungen(this).merken(Beobachtung(jetzt, paket, null))
            Befund.Unbekannt -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        beenden()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        beenden()
        super.onDestroy()
    }

    /** Aus: Ab jetzt zählt nichts mehr für eine Seite (die Zeit bleibt Firefox). */
    private fun beenden() {
        val f = faden ?: return
        faden = null
        handler?.removeCallbacks(pruefen)
        handler = null
        f.quitSafely()
        Beobachtungen(this).merken(Beobachtung(System.currentTimeMillis(), ALLE, null))
    }

    /** Ein echter Knoten hinter der kleinen Schnittstelle von Leiste. */
    private class InfoKnoten(private val n: AccessibilityNodeInfo) : Knoten {
        override val id: String? get() = n.viewIdResourceName
        override val klasse: String? get() = n.className?.toString()
        override val text: CharSequence? get() = n.text
        override val beschreibung: CharSequence? get() = n.contentDescription
        override val editierbar: Boolean get() = n.isEditable
        override val fokussiert: Boolean get() = n.isFocused
        override val kinder: Int get() = n.childCount

        override fun kind(i: Int): Knoten? = n.getChild(i)?.let(::InfoKnoten)
    }
}

/** Wann der Dienst liest – rein, getestet in WebseitenTest. */
object Drossel {
    /** Nach einem Fensterwechsel kurz warten, bis die Leiste steht. */
    const val FENSTER_MS = 300L

    /** Inhaltsereignisse: frühestens so lange danach … */
    const val INHALT_MIN_MS = 1_000L

    /** … und höchstens so oft (ein Video schickt sie ständig). */
    const val INHALT_ABSTAND_MS = 5_000L

    fun wartezeit(jetzt: Long, letzteAbfrage: Long): Long = maxOf(INHALT_MIN_MS, letzteAbfrage + INHALT_ABSTAND_MS - jetzt)
}
