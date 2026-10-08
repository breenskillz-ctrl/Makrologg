package no.makrologg.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Meal reminders. The page sends the full list; we replace whatever was scheduled before. */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY = "list"
    const val CHANNEL = "meals"

    fun replaceAll(ctx: Context, json: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cancel(ctx, prefs.getString(KEY, "[]") ?: "[]")
        prefs.edit().putString(KEY, json).apply()
        scheduleAll(ctx, json)
    }

    fun restore(ctx: Context) {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        scheduleAll(ctx, json)
    }

    private fun items(json: String): List<JSONObject> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun pending(ctx: Context, item: JSONObject): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("data", item.toString())
        return PendingIntent.getBroadcast(
            ctx, item.optInt("id"), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancel(ctx: Context, json: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        items(json).forEach { am.cancel(pending(ctx, it)) }
    }

    private fun scheduleAll(ctx: Context, json: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        for (item in items(json)) {
            val at = item.optLong("at")
            if (at <= now) continue
            val pi = pending(ctx, item)
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Måltider", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Påminnelser om måltider og vann"
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val raw = intent.getStringExtra("data") ?: return
        val data = try { JSONObject(raw) } catch (_: Exception) { return }
        if (!Reminders.canNotify(ctx)) return
        Reminders.ensureChannel(ctx)
        val id = data.optInt("id")
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra("slot", data.optString("slot"))
            .putExtra("day", data.optString("day"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = data.optString("text")
        val n = NotificationCompat.Builder(ctx, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(data.optString("title"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(0xFF0F6B5C.toInt())
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) { }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.restore(ctx)
    }
}
