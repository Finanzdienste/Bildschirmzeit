package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/** Die Anrufliste für die Bewerbungszentrale (Anrufe.kt), mit erfundenen Nummern und ohne Netz. */
@RunWith(RobolectricTestRunner::class)
class AnrufeTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val jetzt = Instant.parse("2026-11-23T09:00:00Z").toEpochMilli()

    /** Androids Anrufliste, erfunden: ein verpasster Anruf, ein angenommener mit Namen, ein unterdrückter, ein zu alter. */
    class ErfundeneAnrufliste : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val ab = selectionArgs?.firstOrNull()?.toLong() ?: 0L
            val c = MatrixCursor(arrayOf(CallLog.Calls.DATE, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DURATION))
            for (z in ZEILEN) if ((z[0] as Long) >= ab) c.addRow(z)
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            private val T = Instant.parse("2026-11-23T09:00:00Z").toEpochMilli()
            val ZEILEN = listOf<Array<Any?>>(
                arrayOf(T - 30 * 60_000L, "+49 40 1234-56", null, 3, 0L),
                arrayOf(T - 90 * 60_000L, "0176 12345678", "Erika Beispiel", 1, 312L),
                arrayOf(T - 120 * 60_000L, "", " ", 3, 0L),
                arrayOf(T - 20L * 86_400_000L, "040111", null, 3, 0L),
            )
        }
    }

    @Before
    fun vorbereiten() {
        Netz.gesperrt = true
        Robolectric.buildContentProvider(ErfundeneAnrufliste::class.java).create(CallLog.AUTHORITY)
        val s = Speicher(kontext)
        s.anrufeTeilen = false
        s.anrufeGemeldet = 0L
        s.anrufeLoeschenOffen = false
    }

    @After
    fun aufraeumen() {
        shadowOf(kontext).denyPermissions(Manifest.permission.READ_CALL_LOG)
    }

    @Test
    fun artenWieInSupabase() {
        assertEquals("eingehend", Anrufe.art(CallLog.Calls.INCOMING_TYPE))
        assertEquals("eingehend", Anrufe.art(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE))
        assertEquals("ausgehend", Anrufe.art(CallLog.Calls.OUTGOING_TYPE))
        assertEquals("verpasst", Anrufe.art(CallLog.Calls.MISSED_TYPE))
        assertEquals("mailbox", Anrufe.art(CallLog.Calls.VOICEMAIL_TYPE))
        assertEquals("abgelehnt", Anrufe.art(CallLog.Calls.REJECTED_TYPE))
        assertEquals("blockiert", Anrufe.art(CallLog.Calls.BLOCKED_TYPE))
        assertNull(Anrufe.art(99))
    }

    @Test
    fun nummernSauber() {
        assertEquals("+4940123456", Anrufe.nummer("+49 40 1234-56"))
        assertEquals("017612345678", Anrufe.nummer("(0176) 123 456 78"))
        assertEquals("unbekannt", Anrufe.nummer(""))
        assertEquals("unbekannt", Anrufe.nummer(null))
        assertEquals("unbekannt", Anrufe.nummer("-2"))
        assertNull(Anrufe.nummer("49+40"))
        assertNull(Anrufe.nummer("1".repeat(40)))
    }

    @Test
    fun ohneErlaubnisWirdNichtsGelesen() {
        assertTrue(Anrufe.lesen(kontext, jetzt).isEmpty())
        Speicher(kontext).anrufeTeilen = true
        assertFalse(Anrufe.imTakt(kontext, jetzt))
    }

    @Test
    fun liestDieLetzten14TageUndBautJson() {
        shadowOf(kontext).grantPermissions(Manifest.permission.READ_CALL_LOG)
        val liste = Anrufe.lesen(kontext, jetzt)
        assertEquals(3, liste.size)
        assertEquals(Anruf(jetzt - 30 * 60_000L, "+4940123456", null, "verpasst", 0L), liste[0])
        assertEquals("Erika Beispiel", liste[1].name)
        assertEquals("unbekannt", liste[2].nummer)
        assertNull("leerer Name → kein Name", liste[2].name)
        val json = Anrufe.alsJson(liste)
        val o = json.getJSONObject(0)
        assertEquals("2026-11-23T08:30:00Z", o.getString("zeit"))
        assertEquals("verpasst", o.getString("art"))
        assertTrue(o.isNull("name"))
        assertEquals(312L, json.getJSONObject(1).getLong("sekunden"))
    }

    @Test
    fun hoechstensAlleDreissigMinuten() {
        assertTrue(Anrufe.faellig(jetzt, 0L))
        assertFalse(Anrufe.faellig(jetzt, jetzt - 29 * 60_000L))
        assertTrue(Anrufe.faellig(jetzt, jetzt - 30 * 60_000L))
        assertTrue("Uhr zurückgestellt", Anrufe.faellig(jetzt, jetzt + 60_000L))
    }

    @Test
    fun ohneNetzGehtNichtsVerloren() {
        shadowOf(kontext).grantPermissions(Manifest.permission.READ_CALL_LOG)
        val s = Speicher(kontext)
        s.anrufeTeilen = true
        // Kein Netz: nicht als gemeldet merken, damit der nächste Lauf (mit Netz) alles meldet.
        assertFalse(Anrufe.imTakt(kontext, jetzt))
        assertEquals(0L, s.anrufeGemeldet)
        assertFalse(Anrufe.imTakt(kontext, jetzt + 60_000L))
        assertEquals(0L, s.anrufeGemeldet)
        // „Nicht mehr teilen“ ohne Netz: aus, und das Löschen bei Supabase ist vorgemerkt.
        Anrufe.nichtMehrTeilen(kontext)
        assertFalse(s.anrufeTeilen)
        assertTrue(s.anrufeLoeschenOffen)
        assertFalse(Anrufe.imTakt(kontext, jetzt + 120_000L))
        assertTrue("bleibt vorgemerkt, bis Supabase antwortet", s.anrufeLoeschenOffen)
    }
}
