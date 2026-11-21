package com.liveasmr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.rtmp.RtmpFromFile

object LiveState {
    @Volatile var running = false
    @Volatile var status = "Belum live"
    @Volatile var detail = ""
    @Volatile var startedAt = 0L
    @Volatile var endAt = 0L
}

private class Target(val label: String, val url: String) {
    var stream: RtmpFromFile? = null
    var gen = 0
    var attempts = 0
    var retryScheduled = false
    var connected = false
    var dead = false
    var info = "Menyiapkan..."
}

class LiveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val targets = ArrayList<Target>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var videoUri: Uri? = null
    private var baseBitrate = 2500 * 1000
    private var mode = 0
    private var level = 0
    private var lastFailAt = 0L
    private var stopping = false

    private val stopTimer = Runnable {
        stopEverything("Waktu habis, live berhenti otomatis")
    }

    // Mode Otomatis: naikkan kualitas lagi kalau sudah stabil 20 menit
    private val upCheck = object : Runnable {
        override fun run() {
            if (stopping) return
            if (mode == 2 && level > 0 &&
                targets.all { it.connected || it.dead } &&
                System.currentTimeMillis() - lastFailAt > 20 * 60_000L
            ) {
                level--
                restartAll()
            }
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                startForegroundCompat("Menyiapkan live...")
                if (LiveState.running) return START_NOT_STICKY
                stopping = false
                val uriText = intent.getStringExtra(EXTRA_URI)
                val urls = intent.getStringArrayExtra(EXTRA_URLS)
                val labels = intent.getStringArrayExtra(EXTRA_LABELS)
                if (uriText == null || urls == null || urls.isEmpty()) {
                    stopEverything("Data live tidak lengkap")
                    return START_NOT_STICKY
                }
                targets.clear()
                for (i in urls.indices) {
                    targets.add(Target(labels?.getOrNull(i) ?: "Tujuan ${i + 1}", urls[i]))
                }
                videoUri = Uri.parse(uriText)
                baseBitrate = intent.getIntExtra(EXTRA_BITRATE, 2500) * 1000
                mode = intent.getIntExtra(EXTRA_MODE, 0)
                level = 0
                lastFailAt = 0L
                val hours = intent.getDoubleExtra(EXTRA_HOURS, 12.0)
                val durationMs = (hours * 3600_000).toLong()
                LiveState.running = true
                LiveState.startedAt = System.currentTimeMillis()
                LiveState.endAt = LiveState.startedAt + durationMs
                getSharedPreferences("live", Context.MODE_PRIVATE).edit()
                    .putBoolean("wasLive", true)
                    .putLong("endAt", LiveState.endAt)
                    .apply()
                acquireLocks(durationMs)
                handler.postDelayed(stopTimer, durationMs)
                handler.postDelayed(upCheck, 5 * 60_000L)
                for ((i, t) in targets.withIndex()) {
                    handler.postDelayed({ beginStream(t) }, i * 1500L)
                }
            }
            ACTION_STOP -> stopEverything("Live dihentikan")
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun videoBitrate(): Int = when (mode) {
        1 -> minOf(baseBitrate, 600_000)
        2 -> (baseBitrate * FACTORS[level]).toInt().coerceAtLeast(500_000).coerceAtMost(baseBitrate)
        else -> baseBitrate
    }

    private fun audioBitrate(): Int =
        if (mode == 1 || (mode == 2 && level >= 2)) 64_000 else 128_000

    private fun makeChecker(t: Target, gen: Int) = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}
        override fun onConnectionSuccess() {
            handler.post {
                if (gen == t.gen) {
                    t.attempts = 0
                    t.connected = true
                    t.info = "LIVE berjalan"
                    refreshStatus()
                }
            }
        }
        override fun onConnectionFailed(reason: String) {
            handler.post {
                if (gen == t.gen) failure(t, "Gagal sambung ($reason)")
            }
        }
        override fun onNewBitrate(bitrate: Long) {}
        override fun onDisconnect() {
            handler.post {
                if (gen == t.gen) failure(t, "Koneksi terputus")
            }
        }
        override fun onAuthError() {
            handler.post {
                if (gen == t.gen) {
                    t.dead = true
                    t.connected = false
                    t.info = "Stream Key ditolak server"
                    refreshStatus()
                    if (targets.all { it.dead }) stopEverything("Stream Key ditolak server")
                }
            }
        }
        override fun onAuthSuccess() {}
    }

    private fun beginStream(t: Target) {
        if (stopping || t.dead) return
        t.retryScheduled = false
        releaseStream(t)
        t.gen++
        val gen = t.gen
        try {
            val video = object : VideoDecoderInterface {
                override fun onVideoDecoderFinished() {}
            }
            val audio = object : AudioDecoderInterface {
                override fun onAudioDecoderFinished() {}
            }
            val s = RtmpFromFile(makeChecker(t, gen), video, audio)
            t.stream = s
            val uri = videoUri ?: return
            if (!s.prepareVideo(applicationContext, uri, videoBitrate(), 0)) {
                stopEverything("Video tidak bisa dibaca. Pilih ulang video (MP4, maks 1080p)")
                return
            }
            s.prepareAudio(applicationContext, uri, audioBitrate())
            s.setLoopMode(true)
            t.info = "Menyambung ke server..."
            refreshStatus()
            s.startStream(t.url)
        } catch (e: Exception) {
            failure(t, "Error: ${e.message}")
        }
    }

    private fun failure(t: Target, reason: String) {
        if (stopping) return
        t.connected = false
        lastFailAt = System.currentTimeMillis()
        // Mode Otomatis: gagal berulang -> turunkan kualitas
        if (mode == 2 && t.attempts >= 1 && level < FACTORS.size - 1) level++
        scheduleRetry(t, reason)
    }

    private fun scheduleRetry(t: Target, reason: String) {
        if (stopping || t.retryScheduled || t.dead) return
        t.retryScheduled = true
        t.attempts++
        val delay = minOf(5000L * t.attempts, 30000L)
        t.info = "$reason. Coba lagi ${delay / 1000} dtk (percobaan ${t.attempts})"
        refreshStatus()
        handler.postDelayed({ beginStream(t) }, delay)
    }

    private fun restartAll() {
        for ((i, t) in targets.withIndex()) {
            if (t.dead) continue
            t.attempts = 0
            handler.postDelayed({ beginStream(t) }, i * 1500L)
        }
    }

    private fun releaseStream(t: Target) {
        try {
            t.stream?.stopStream()
        } catch (_: Exception) {
        }
        t.stream = null
    }

    private fun stopEverything(msg: String) {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        for (t in targets) {
            t.gen++
            releaseStream(t)
        }
        releaseLocks()
        getSharedPreferences("live", Context.MODE_PRIVATE).edit()
            .putBoolean("wasLive", false).apply()
        LiveState.running = false
        LiveState.status = msg
        LiveState.detail = ""
        stopForeground(true)
        stopSelf()
    }

    private fun refreshStatus() {
        val text = targets.joinToString("\n") { it.label + ": " + it.info }
        val modeName = when (mode) {
            1 -> "Sinyal lemah"
            2 -> "Otomatis"
            else -> "Normal"
        }
        LiveState.status = text
        LiveState.detail = "Mode $modeName, video ${videoBitrate() / 1000} kbps"
        if (!stopping) {
            val short = targets.joinToString(" | ") { it.label + ": " + (if (it.connected) "LIVE" else "...") }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(short))
        }
    }

    private fun acquireLocks(durationMs: Long) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveASMR:wake").apply {
            acquire(durationMs + 600_000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LiveASMR:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Live ASMR", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP), flags
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        @Suppress("DEPRECATION")
        val action = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Berhenti", stopPi
        ).build()
        return b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Live ASMR")
            .setContentText(text)
            .setContentIntent(openPi)
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        for (t in targets) {
            t.gen++
            releaseStream(t)
        }
        releaseLocks()
        if (LiveState.running) {
            LiveState.running = false
            LiveState.status = "Live berhenti tiba-tiba"
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.liveasmr.app.START"
        const val ACTION_STOP = "com.liveasmr.app.STOP"
        const val EXTRA_URI = "uri"
        const val EXTRA_URLS = "urls"
        const val EXTRA_LABELS = "labels"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_HOURS = "hours"
        const val EXTRA_MODE = "mode"
        private val FACTORS = doubleArrayOf(1.0, 0.6, 0.35)
        private const val CHANNEL_ID = "live_asmr"
        private const val NOTIF_ID = 11
    }
}
