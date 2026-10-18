package no.makrologg.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Nightly send to the coach sheet. The page hands over the latest records for today and yesterday
 * (as ready-made Apps Script URLs) every time something changes. At 23:50 we send them ourselves,
 * so the sheet has the whole day even if the app was closed before its own sync ran.
 */
object CoachSync {
    private const val PREFS = "coachsync"
    private const val KEY = "urls"
    private const val TRIES = "tries"
    private const val REQ = 900001
    const val HOUR = 23
    const val MINUTE = 50

    fun queue(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json).putInt(TRIES, 0).apply()
        schedule(ctx, nextRun())
    }

    fun restore(ctx: Context) {
        val urls = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        if (urls != "[]") schedule(ctx, nextRun())
    }

    private fun nextRun(): Long {
        val c = Calendar.getInstance()
        val now = c.timeInMillis
        c.set(Calendar.HOUR_OF_DAY, HOUR); c.set(Calendar.MINUTE, MINUTE)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ, Intent(ctx, CoachSyncReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun schedule(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Sends everything queued. Runs off the main thread. */
    fun sendNow(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val urls = try {
            val a = JSONArray(json); (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) { emptyList() }
        val failed = JSONArray()
        for (u in urls) if (!get(u)) failed.put(u)
        // Only clear what we sent if the page has not queued something newer meanwhile.
        if (prefs.getString(KEY, "[]") != json) return
        if (failed.length() == 0) {
            prefs.edit().putString(KEY, "[]").putInt(TRIES, 0).apply()
        } else {
            val tries = prefs.getInt(TRIES, 0) + 1
            prefs.edit().putString(KEY, failed.toString()).putInt(TRIES, tries).apply()
            // No network: try again a few times, 15 minutes apart. The app's own sync catches up on next open.
            if (tries < 4) schedule(ctx, System.currentTimeMillis() + 15 * 60_000L)
        }
    }

    private fun get(url: String): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Makrologg-Android/1.0")
        val status = conn.responseCode
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        status == 200 && body.contains("\"ok\":true")
    } catch (_: Exception) { false }
}

class CoachSyncReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try { CoachSync.sendNow(ctx.applicationContext) } finally { result.finish() }
        }.start()
    }
}
