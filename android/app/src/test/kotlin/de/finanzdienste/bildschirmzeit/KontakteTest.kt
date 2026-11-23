package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
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
import java.io.IOException

/** Kontakte für die Nummern aus den Bewerbungen (Kontakte.kt), mit einem erfundenen Adressbuch und ohne Netz. */
@RunWith(RobolectricTestRunner::class)
class KontakteTest {
    private val kontext = RuntimeEnvironment.getApplication()
    private val jetzt = 1_800_000_000_000L
    private val holenVerboten: (String) -> String = { throw AssertionError("ohne Freigabe nichts holen") }

    /** Androids Adressbuch, erfunden und stark vereinfacht: RawContacts mit Name und Nummern. */
    class ErfundeneKontakte : ContentProvider() {
        data class Daten(val raw: Long, val mime: String, var wert: String?)

        companion object {
            val roh = LinkedHashMap<Long, Long>() // RawContact → Kontakt
            val daten = ArrayList<Daten>()
            private var naechste = 100L

            fun leeren() {
                roh.clear()
                daten.clear()
                naechste = 100L
            }

            /** Ein Kontakt mit Name und Nummern; gibt die Id des RawContacts zurück. */
            fun anlegen(name: String?, vararg nummern: String, kontakt: Long? = null): Long {
                val raw = naechste++
                roh[raw] = kontakt ?: raw
                if (name != null) daten += Daten(raw, NAME, name)
                for (n in nummern) daten += Daten(raw, TELEFON, n)
                return raw
            }

            fun name(raw: Long): String? = daten.firstOrNull { it.raw == raw && it.mime == NAME }?.wert
            fun nummern(raw: Long): List<String?> = daten.filter { it.raw == raw && it.mime == TELEFON }.map { it.wert }
            fun mitName(name: String): List<Long> = roh.keys.filter { name(it) == name }
            private fun kontaktName(kontakt: Long): String? = roh.filterValues { it == kontakt }.keys.firstNotNullOfOrNull { name(it) }

            const val NAME = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            const val TELEFON = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        }

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val c = MatrixCursor(projection)
            val s = uri.pathSegments
            when {
                s == listOf("data", "phones") ->
                    for (d in daten.filter { it.mime == TELEFON && it.raw in roh }) c.addRow(arrayOf<Any?>(roh[d.raw], d.wert, kontaktName(roh[d.raw]!!)))
                s.size == 2 && s[0] == "raw_contacts" -> {
                    val raw = s[1].toLong()
                    if (raw in roh) c.addRow(arrayOf<Any?>(raw, 0))
                }
                s == listOf("raw_contacts") -> {
                    val kontakt = selectionArgs!![0].toLong()
                    for ((raw, k) in roh) if (k == kontakt) c.addRow(arrayOf<Any?>(raw))
                }
                s == listOf("data") -> {
                    val raw = selectionArgs!![0].toLong()
                    for (d in daten.filter { it.raw == raw && it.mime == selectionArgs[1] }) c.addRow(arrayOf<Any?>(d.wert))
                }
                else -> throw IllegalArgumentException("unbekannt: $uri")
            }
            return c
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val v = values!!
            return when (uri.pathSegments) {
                listOf("raw_contacts") -> {
                    assertNull("Telefonspeicher, kein Konto", v.getAsString(ContactsContract.RawContacts.ACCOUNT_TYPE))
                    val raw = naechste++
                    roh[raw] = raw
                    ContentUris.withAppendedId(uri, raw)
                }
                listOf("data") -> {
                    val raw = v.getAsLong(ContactsContract.Data.RAW_CONTACT_ID)
                    daten += Daten(raw, v.getAsString(ContactsContract.Data.MIMETYPE), v.getAsString("data1"))
                    ContentUris.withAppendedId(uri, daten.size.toLong())
                }
                else -> throw IllegalArgumentException("unbekannt: $uri")
            }
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(listOf("data"), uri.pathSegments)
            val treffer = daten.filter { it.raw == selectionArgs!![0].toLong() && it.mime == selectionArgs[1] }
            for (d in treffer) d.wert = values!!.getAsString("data1")
            return treffer.size
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val s = uri.pathSegments
            if (s.size == 2 && s[0] == "raw_contacts") {
                val raw = s[1].toLong()
                daten.removeAll { it.raw == raw }
                return if (roh.remove(raw) != null) 1 else 0
            }
            val vorher = daten.size
            daten.removeAll { it.raw == selectionArgs!![0].toLong() && it.mime == selectionArgs[1] }
            return vorher - daten.size
        }

        override fun getType(uri: Uri): String? = null
    }

    private val speicher get() = Speicher(kontext)

    @Before
    fun vorbereiten() {
        Netz.gesperrt = true
        ErfundeneKontakte.leeren()
        Robolectric.buildContentProvider(ErfundeneKontakte::class.java).create(ContactsContract.AUTHORITY)
        speicher.kontakteBenennen = false
        speicher.kontakteGeholt = 0L
        speicher.kontakteListe = null
        speicher.kontakteAngewandt = null
        speicher.kontakteAngelegt = emptyMap()
        speicher.kontakteUmbenannt = emptyMap()
    }

    @After
    fun aufraeumen() {
        shadowOf(kontext).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }

    private fun freigeben() {
        shadowOf(kontext).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        speicher.kontakteBenennen = true
    }

    private val liste = listOf(
        Wunsch("+4940111122", "Erika Muster (Beispiel GmbH)"),
        Wunsch("+4930123456", "Muster AG"),
    )

    @Test
    fun nummernVergleichen() {
        assertEquals("040111122", Kontakte.vergleich("+49 40 1111-22"))
        assertEquals("040111122", Kontakte.vergleich("0049 (40) 111122"))
        assertEquals("040111122", Kontakte.vergleich("040 1111 22"))
        assertEquals("0043123", Kontakte.vergleich("+43 123"))
        assertEquals("", Kontakte.vergleich(null))
    }

    @Test
    fun listeAusJsonVertraegtUnpassendes() {
        val text = """[{"nummer":"+4940111122","name":" Erika\nMuster "},{"nummer":"040 1","name":"Leer"},{"nummer":"+4930123456","name":""},
            |{"nummer":"040111122","name":"doppelt"},"kein Objekt",{"nummer":"+4930999","name":null}]""".trimMargin()
        assertEquals(listOf(Wunsch("+4940111122", "Erika Muster")), Kontakte.ausJson(text))
        assertTrue(Kontakte.ausJson("kein JSON").isEmpty())
        assertTrue(Kontakte.ausJson(null).isEmpty())
        assertEquals(liste, Kontakte.ausJson(Kontakte.alsJson(liste)))
    }

    @Test
    fun ohneFreigabeOderErlaubnisPassiertNichts() {
        val mama = ErfundeneKontakte.anlegen("Mama", "040 1111-22")
        assertEquals(0, Kontakte.imTakt(kontext, jetzt, holenVerboten))
        speicher.kontakteBenennen = true // freigegeben, aber Android erlaubt es (noch) nicht
        assertEquals(0, Kontakte.imTakt(kontext, jetzt, holenVerboten))
        assertEquals("Mama", ErfundeneKontakte.name(mama))
        assertEquals(1, ErfundeneKontakte.roh.size)
    }

    @Test
    fun benenntUmUndLegtAn() {
        val heuer = ErfundeneKontakte.anlegen("Frau Heuer", "040 1111-22")
        val fremd = ErfundeneKontakte.anlegen("Max Beispiel", "0170 1111122") // gleiche Endziffern, andere Nummer
        freigeben()
        assertEquals(2, Kontakte.imTakt(kontext, jetzt) { Kontakte.alsJson(liste) })
        assertEquals("Erika Muster (Beispiel GmbH)", ErfundeneKontakte.name(heuer))
        assertEquals("Max Beispiel", ErfundeneKontakte.name(fremd))
        val neu = ErfundeneKontakte.mitName("Muster AG").single()
        assertEquals(listOf<String?>("+4930123456"), ErfundeneKontakte.nummern(neu))
        assertEquals(mapOf(neu to "+4930123456"), speicher.kontakteAngelegt)
        assertEquals(mapOf(heuer to Umbenannt("Frau Heuer", "+4940111122")), speicher.kontakteUmbenannt)
        assertEquals(jetzt, speicher.kontakteGeholt)

        // Dieselbe Liste noch einmal: nichts doppelt, und was Tobi von Hand umbenennt, bleibt.
        ErfundeneKontakte.daten.first { it.raw == heuer && it.mime == ErfundeneKontakte.NAME }.wert = "Erika (von Hand)"
        assertEquals(0, Kontakte.imTakt(kontext, jetzt + Kontakte.ABSTAND_MS) { Kontakte.alsJson(liste) })
        assertEquals("Erika (von Hand)", ErfundeneKontakte.name(heuer))
        assertEquals(3, ErfundeneKontakte.roh.size)
    }

    @Test
    fun hoechstensAlleDreissigMinutenHolen() {
        freigeben()
        var geholt = 0
        Kontakte.imTakt(kontext, jetzt) { geholt++; Kontakte.alsJson(liste) }
        Kontakte.imTakt(kontext, jetzt + Kontakte.ABSTAND_MS - 1) { geholt++; Kontakte.alsJson(liste) }
        assertEquals(1, geholt)
        Kontakte.imTakt(kontext, jetzt + Kontakte.ABSTAND_MS) { geholt++; Kontakte.alsJson(liste) }
        assertEquals(2, geholt)
        assertTrue("Uhr zurückgestellt", Kontakte.faellig(jetzt, jetzt + 60_000L))
    }

    @Test
    fun neueListeAendertNamenUndNimmtWegGefallenesZurueck() {
        val heuer = ErfundeneKontakte.anlegen("Frau Heuer", "040 1111-22")
        freigeben()
        Kontakte.imTakt(kontext, jetzt) { Kontakte.alsJson(liste) }
        val neu = ErfundeneKontakte.mitName("Muster AG").single()

        // Später: Muster AG mit Ansprechperson, die Nummer von Frau Heuer steht nicht mehr in der Liste.
        Kontakte.imTakt(kontext, jetzt + Kontakte.ABSTAND_MS) { Kontakte.alsJson(listOf(Wunsch("+4930123456", "Max Muster (Muster AG)"))) }
        assertEquals("Max Muster (Muster AG)", ErfundeneKontakte.name(neu))
        assertEquals("kein zweiter Kontakt", 2, ErfundeneKontakte.roh.size)
        assertEquals("Frau Heuer", ErfundeneKontakte.name(heuer))
        assertTrue(speicher.kontakteUmbenannt.isEmpty())

        // Leere Liste (Bewerbungszentrale hat noch nichts geschickt): nichts löschen.
        assertEquals(0, Kontakte.imTakt(kontext, jetzt + 2 * Kontakte.ABSTAND_MS) { "[]" })
        assertEquals(2, ErfundeneKontakte.roh.size)
    }

    @Test
    fun vonHandGeloeschtWirdVergessen() {
        freigeben()
        Kontakte.imTakt(kontext, jetzt) { Kontakte.alsJson(liste) }
        val neu = ErfundeneKontakte.mitName("Muster AG").single()
        ErfundeneKontakte.roh.remove(neu)
        Kontakte.imTakt(kontext, jetzt + Kontakte.ABSTAND_MS) { Kontakte.alsJson(liste.take(1) + Wunsch("+4930999", "Neu GmbH")) }
        assertFalse(neu in speicher.kontakteAngelegt)
    }

    @Test
    fun ohneNetzGiltDerLetzteStand() {
        val heuer = ErfundeneKontakte.anlegen("Frau Heuer", "040 1111-22")
        freigeben()
        speicher.kontakteListe = Kontakte.alsJson(liste) // früher geholt
        // Netz.gesperrt: Supabase antwortet nicht – benannt wird trotzdem, aus dem Gemerkten.
        assertEquals(2, Kontakte.imTakt(kontext, jetzt))
        assertEquals("Erika Muster (Beispiel GmbH)", ErfundeneKontakte.name(heuer))
        assertEquals("nicht als geholt merken: der nächste Lauf versucht es wieder", 0L, speicher.kontakteGeholt)
        assertEquals(Kontakte.alsJson(liste), speicher.kontakteListe)
        assertEquals(0, Kontakte.imTakt(kontext, jetzt + 60_000L) { throw IOException("kein Netz") })
    }

    @Test
    fun nichtMehrBenennenNimmtAllesZurueck() {
        val heuer = ErfundeneKontakte.anlegen("Frau Heuer", "040 1111-22")
        val ohneName = ErfundeneKontakte.anlegen(null, "+49 30 123456")
        freigeben()
        Kontakte.imTakt(kontext, jetzt) { Kontakte.alsJson(liste) }
        assertEquals("Muster AG", ErfundeneKontakte.name(ohneName))
        assertEquals(2, Kontakte.zuruecknehmen(kontext))
        assertEquals("Frau Heuer", ErfundeneKontakte.name(heuer))
        assertNull("hatte keinen Namen, hat wieder keinen", ErfundeneKontakte.name(ohneName))
        assertEquals(2, ErfundeneKontakte.roh.size)
        assertFalse(speicher.kontakteBenennen)
        assertTrue(speicher.kontakteUmbenannt.isEmpty() && speicher.kontakteAngelegt.isEmpty())
        assertNull(speicher.kontakteListe)
        assertEquals(0, Kontakte.imTakt(kontext, jetzt + Kontakte.ABSTAND_MS, holenVerboten))
    }

    @Test
    fun alleDatenLoeschenNimmtDieKontakteMit() {
        freigeben()
        Kontakte.imTakt(kontext, jetzt) { Kontakte.alsJson(liste) }
        assertEquals(2, ErfundeneKontakte.roh.size)
        assertTrue(Rechner.allesLoeschen(kontext, jetzt) { true })
        assertTrue(ErfundeneKontakte.roh.isEmpty())
        assertFalse(speicher.kontakteBenennen)
    }
}
