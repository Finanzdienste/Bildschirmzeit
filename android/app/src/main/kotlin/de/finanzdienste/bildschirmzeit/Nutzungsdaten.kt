package de.finanzdienste.bildschirmzeit

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Process

/** Was Android über die Nutzung verrät – der Teil, der ein Gerät braucht. */
object Nutzungsdaten {

    /** Hat der Nutzer „Nutzungsdatenzugriff“ für diese App eingeschaltet? */
    fun erlaubt(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        // checkOpNoThrow gibt es auf jeder Version (von 29 bis 36 hieß die
        // empfohlene Fassung unsafeCheckOpNoThrow, ab 37 wieder diese).
        val modus = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (modus == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            modus == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Die Ereignisse von `von` bis `bis`, nur die Arten, die Nutzung.kt braucht. */
    fun ereignisse(context: Context, von: Long, bis: Long): List<Ereignis> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val roh = usm.queryEvents(von, bis) ?: return emptyList()
        val e = UsageEvents.Event()
        val aus = ArrayList<Ereignis>()
        while (roh.hasNextEvent()) {
            roh.getNextEvent(e)
            val art = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> Art.VORN
                UsageEvents.Event.ACTIVITY_PAUSED -> Art.PAUSIERT
                UsageEvents.Event.ACTIVITY_STOPPED -> Art.GESTOPPT
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> Art.BILDSCHIRM_AUS
                UsageEvents.Event.KEYGUARD_SHOWN -> Art.GESPERRT
                UsageEvents.Event.DEVICE_SHUTDOWN -> Art.AUSGESCHALTET
                UsageEvents.Event.DEVICE_STARTUP -> Art.GESTARTET
                else -> null
            } ?: continue
            aus += Ereignis(e.timeStamp, art, e.packageName.orEmpty(), e.className.orEmpty())
        }
        return aus
    }

    /** Pakete mit Eintrag im App-Drawer (sichtbar über <queries> im Manifest). */
    fun launcherPakete(pm: PackageManager): Set<String> =
        activities(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapTo(HashSet()) { it.activityInfo.packageName }

    /** Jede HOME-Activity als „paket/klasse“ (auch die Ziel-Klasse eines activity-alias). */
    fun homeActivities(pm: PackageManager): Set<String> {
        val aus = HashSet<String>()
        for (r in activities(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DISABLED_COMPONENTS)) {
            val a = r.activityInfo
            aus += "${a.packageName}/${a.name}"
            a.targetActivity?.let { aus += "${a.packageName}/$it" }
        }
        return aus
    }

    /** Der Name, den der Nutzer sieht; ohne Namen das Paket. */
    fun name(pm: PackageManager, paket: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(paket, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(paket, 0)
        }
        pm.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        paket
    }

    private fun activities(pm: PackageManager, intent: Intent, flags: Int): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags)
        }
}
