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
    @Volatile var startedAt = 0L
    @Volatile var endAt = 0L
}

class LiveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var stream: RtmpFromFile? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var videoUri: Uri? = null
    private var url = ""
    private var bitrate = 2500 * 1000
    private var stopping = false
    private var retryScheduled = false
    private var attempts = 0
    private var generation = 0

    private val stopTimer = Runnable {
        stopEverything("Waktu habis, live berhenti otomatis")
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
                val uriText = intent.getStringExtra(EXTRA_URI)
                val target = intent.getStringExtra(EXTRA_URL)
                if (uriText == null || target == null) {
                    stopEverything("Data live tidak lengkap")
                    return START_NOT_STICKY
                }
                videoUri = Uri.parse(uriText)
                url = target
                bitrate = intent.getIntExtra(EXTRA_BITRATE, 2500) * 1000
                val hours = intent.getDoubleExtra(EXTRA_HOURS, 12.0)
                val durationMs = (hours * 3600_000).toLong()
                stopping = false
                attempts = 0
                LiveState.running = true
                LiveState.startedAt = System.currentTimeMillis()
                LiveState.endAt = LiveState.startedAt + durationMs
                acquireLocks(durationMs)
                handler.postDelayed(stopTimer, durationMs)
                beginStream()
            }
            ACTION_STOP -> stopEverything("Live dihentikan")
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun makeChecker(gen: Int) = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}
        override fun onConnectionSuccess() {
            handler.post {
                if (gen == generation) {
                    attempts = 0
                    setStatus("LIVE berjalan")
                }
            }
        }
        override fun onConnectionFailed(reason: String) {
            handler.post {
                if (gen == generation) scheduleRetry("Gagal sambung ($reason)")
            }
        }
        override fun onNewBitrate(bitrate: Long) {}
        override fun onDisconnect() {
            handler.post {
                if (gen == generation) scheduleRetry("Koneksi terputus")
            }
        }
        override fun onAuthError() {
            handler.post {
                if (gen == generation) stopEverything("Stream Key ditolak server")
            }
        }
        override fun onAuthSuccess() {}
    }

    private fun beginStream() {
        if (stopping) return
        retryScheduled = false
        releaseStream()
        generation++
        val gen = generation
        try {
            val video = object : VideoDecoderInterface {
                override fun onVideoDecoderFinished() {}
            }
            val audio = object : AudioDecoderInterface {
                override fun onAudioDecoderFinished() {}
            }
            val s = RtmpFromFile(makeChecker(gen), video, audio)
            stream = s
            val uri = videoUri ?: return
            if (!s.prepareVideo(applicationContext, uri, bitrate, 0)) {
                stopEverything("Video tidak bisa dibaca. Pilih ulang video (MP4, maks 1080p)")
                return
            }
            s.prepareAudio(applicationContext, uri, 128 * 1000)
            s.setLoopMode(true)
            setStatus("Menyambung ke server...")
            s.startStream(url)
        } catch (e: Exception) {
            scheduleRetry("Error: ${e.message}")
        }
    }

    private fun scheduleRetry(reason: String) {
        if (stopping || retryScheduled) return
        retryScheduled = true
        attempts++
        val delay = minOf(5000L * attempts, 30000L)
        setStatus("$reason. Coba lagi ${delay / 1000} dtk (percobaan $attempts)")
        handler.postDelayed({ beginStream() }, delay)
    }

    private fun releaseStream() {
        try {
            stream?.stopStream()
        } catch (_: Exception) {
        }
        stream = null
    }

    private fun stopEverything(msg: String) {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        generation++
        releaseStream()
        releaseLocks()
        LiveState.running = false
        LiveState.status = msg
        stopForeground(true)
        stopSelf()
    }

    private fun setStatus(text: String) {
        LiveState.status = text
        if (!stopping) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
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
        generation++
        releaseStream()
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
        const val EXTRA_URL = "url"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_HOURS = "hours"
        private const val CHANNEL_ID = "live_asmr"
        private const val NOTIF_ID = 11
    }
}
