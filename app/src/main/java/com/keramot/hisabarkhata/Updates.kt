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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** GitHub-এর Releases থেকে নতুন ভার্সন খোঁজে। */
object Updates {
    const val REPO = "fbkeramot-wq/hisab-ar-khata"
    const val EXTRA_START_UPDATE = "start_update"
    private const val CHANNEL = "updates"

    data class Info(val version: String, val notes: String, val apkUrl: String)

    fun versionNumber(v: String): Long {
        val p = v.trim().removePrefix("v").split(".").map { s -> s.filter { it.isDigit() }.toLongOrNull() ?: 0L }
        return p.getOrElse(0) { 0L } * 1_000_000L + p.getOrElse(1) { 0L } * 1_000L + p.getOrElse(2) { 0L }
    }

    fun isNewer(v: String) = versionNumber(v) > versionNumber(BuildConfig.VERSION_NAME)

    /** নেট না থাকলে বা কিছু না পেলে null। পেছনের থ্রেডে চালাতে হবে। */
    fun fetchLatest(): Info? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "HisabKhata-Android")
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name").removePrefix("v")
            val assets = j.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            if (tag.isBlank() || url.isNullOrBlank()) return null
            val notes = j.optString("body").lineSequence().map { it.trim().trimStart('-', '*', '#', ' ') }
                .firstOrNull { it.isNotBlank() }?.take(90) ?: ""
            return Info(tag, notes, url)
        } finally {
            c.disconnect()
        }
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun notify(ctx: Context, info: Info) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "অ্যাপ আপডেট", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_START_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (info.notes.isNotBlank()) info.notes else "চাপ দিয়ে আপডেট করুন"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF0A5A4E.toInt())
            .setContentTitle("হিসাবের খাতার নতুন আপডেট (${info.version})")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nচাপ দিলে অ্যাপের ভেতরেই আপডেট হবে।"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(7, n) } catch (_: SecurityException) {}
    }
}

/** দিনে দুবার পেছন থেকে আপডেট দেখে, পেলে একবার নোটিফিকেশন দেয়। */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val info = try { Updates.fetchLatest() } catch (_: Exception) { null } ?: return Result.success()
        if (!Updates.isNewer(info.version)) return Result.success()
        val sp = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (sp.getString("notified", "") == info.version) return Result.success()
        Updates.notify(applicationContext, info)
        sp.edit().putString("notified", info.version).apply()
        return Result.success()
    }
}
