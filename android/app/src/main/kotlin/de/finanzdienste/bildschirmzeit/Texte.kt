package de.finanzdienste.bildschirmzeit

import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/** „1 Std. 5 Min.“, „12 Min.“, „unter 1 Min.“ – wortgleich mit dauerText() im Web-Modul. */
fun dauerText(sekunden: Long): String {
    val min = maxOf(0L, sekunden) / 60
    if (min < 1) return "unter 1 Min."
    val std = min / 60
    val rest = min % 60
    if (std == 0L) return "$rest Min."
    return if (rest != 0L) "$std Std. $rest Min." else "$std Std."
}

/**
 * Kurz für die Zeilen im Widget, wo neben dem Namen wenig Platz ist:
 * „54 Min.“, ab einer Stunde „2:41 Std.“.
 */
fun kurzText(sekunden: Long): String {
    val min = maxOf(0L, sekunden) / 60
    if (min < 60) return dauerText(sekunden)
    return "${min / 60}:${(min % 60).toString().padStart(2, '0')} Std."
}

/** Für die Mitte des Rings: Stunden und Minuten untereinander. */
fun ringText(sekunden: Long): String = dauerText(sekunden).replace(" Std. ", " Std.\n")

private val UHR = DateTimeFormatter.ofPattern("HH:mm")
private val DATUM_UHR = DateTimeFormatter.ofPattern("d.M., HH:mm")

/** „21:40“ */
fun uhrzeit(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    UHR.format(Instant.ofEpochMilli(ms).atZone(zone))

/** „21:40“ heute, „gestern 21:40“, sonst „12.11., 21:40“. */
fun zeitpunktText(ms: Long, jetzt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val heute = Instant.ofEpochMilli(jetzt).atZone(zone).toLocalDate()
    return when (t.toLocalDate()) {
        heute -> UHR.format(t)
        heute.minusDays(1) -> "gestern ${UHR.format(t)}"
        else -> DATUM_UHR.format(t)
    }
}

/** Der Kalendertag 'JJJJ-MM-TT' – wie tagVon() im Web-Modul. */
fun tagText(tag: LocalDate): String = tag.toString()

/** Die Regel aus supabase/bildschirmzeit.sql. */
val KENNUNG_REGEL = Regex("^[A-Za-z0-9_-]{32,128}$")

/** 32 zufällige Byte als base64url ohne „=“: 43 Zeichen. */
fun neueKennung(zufall: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    zufall.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
