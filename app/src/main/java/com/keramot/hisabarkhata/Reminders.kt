package com.keramot.hisabarkhata

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** টাকা দেওয়ার তারিখ এলে সকালে মনে করিয়ে দেয়। */
object Reminders {
    private const val PREFS = "reminders"
    private const val CHANNEL = "dues"

    fun save(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dues", json).apply()
    }

    /** প্রতিদিন সকাল ৯টার কাছাকাছি একবার দেখে। */
    fun schedule(ctx: Context) {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val req = PeriodicWorkRequestBuilder<DueWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("due-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun check(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val t = today()
        if (sp.getString("notified", "") == t) return
        val arr = try { JSONArray(sp.getString("dues", "[]")) } catch (_: Exception) { JSONArray() }
        val get = mutableListOf<String>()
        val give = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("d") > t) continue
            val line = o.optString("n") + " " + o.optString("a")
            if (o.optString("k") == "supplier") give.add(line) else get.add(line)
        }
        if (get.isEmpty() && give.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "টাকা আদায়ের তারিখ", NotificationManager.IMPORTANCE_HIGH))
        }
        val title = when {
            get.isNotEmpty() && give.isNotEmpty() -> "আজ ${bn(get.size)} জনের কাছে টাকা পাওয়ার, ${bn(give.size)} জনকে দেওয়ার কথা"
            get.isNotEmpty() -> "আজ ${bn(get.size)} জনের কাছে টাকা পাওয়ার কথা"
            else -> "আজ ${bn(give.size)} জন মহাজনকে টাকা দেওয়ার কথা"
        }
        val body = (get + give).joinToString("\n")
        val pi = PendingIntent.getActivity(
            ctx, 9, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF0A5A4E.toInt())
            .setContentTitle(title)
            .setContentText((get + give).joinToString(", "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(9, n) } catch (_: SecurityException) { return }
        sp.edit().putString("notified", t).apply()
    }

    private fun bn(n: Int) = n.toString().map { if (it.isDigit()) "০১২৩৪৫৬৭৮৯"[it - '0'] else it }.joinToString("")
}

class DueWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { Reminders.check(applicationContext) } catch (_: Exception) {}
        return Result.success()
    }
}
