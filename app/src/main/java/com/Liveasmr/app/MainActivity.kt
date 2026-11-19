package com.liveasmr.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var videoUri: Uri? = null
    private lateinit var fileText: TextView
    private lateinit var serverEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var hoursEdit: EditText
    private lateinit var bitrateEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button

    private val urls = listOf(
        "rtmp://a.rtmp.youtube.com/live2",
        "rtmps://live-api-s.facebook.com:443/rtmp",
        ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("live", Context.MODE_PRIVATE)
        videoUri = prefs.getString("uri", null)?.let { Uri.parse(it) }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(28), dp(16), dp(28))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "Live ASMR"
        title.textSize = 24f
        root.addView(title)

        root.addView(label("1. Video (diputar berulang)"))
        val pickBtn = Button(this)
        pickBtn.text = "Pilih video"
        root.addView(pickBtn)
        fileText = TextView(this)
        root.addView(fileText)
        showFileName()

        root.addView(label("2. Platform"))
        val group = RadioGroup(this)
        group.orientation = RadioGroup.HORIZONTAL
        val names = listOf("YouTube", "Facebook", "Lainnya")
        val ids = names.map { View.generateViewId() }
        names.forEachIndexed { i, n ->
            val rb = RadioButton(this)
            rb.text = n
            rb.id = ids[i]
            group.addView(rb)
        }
        root.addView(group)

        root.addView(label("Alamat server"))
        serverEdit = EditText(this)
        serverEdit.setSingleLine()
        serverEdit.setText(prefs.getString("server", urls[0]))
        root.addView(serverEdit)

        group.setOnCheckedChangeListener { _, checkedId ->
            val i = ids.indexOf(checkedId)
            if (i in 0..1) serverEdit.setText(urls[i])
            prefs.edit().putInt("platform", i).apply()
        }
        group.check(ids[prefs.getInt("platform", 0).coerceIn(0, 2)])

        root.addView(label("Stream Key"))
        keyEdit = EditText(this)
        keyEdit.setSingleLine()
        keyEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        keyEdit.setText(prefs.getString("key", ""))
        root.addView(keyEdit)

        root.addView(label("3. Lama live (jam)"))
        hoursEdit = EditText(this)
        hoursEdit.setSingleLine()
        hoursEdit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        hoursEdit.setText(prefs.getString("hours", "12"))
        root.addView(hoursEdit)

        root.addView(label("Kualitas (kbps, 2500 = standar 720p)"))
        bitrateEdit = EditText(this)
        bitrateEdit.setSingleLine()
        bitrateEdit.inputType = InputType.TYPE_CLASS_NUMBER
        bitrateEdit.setText(prefs.getString("kbps", "2500"))
        root.addView(bitrateEdit)

        startBtn = Button(this)
        startBtn.text = "MULAI LIVE"
        root.addView(startBtn)

        statusText = TextView(this)
        statusText.textSize = 16f
        statusText.setPadding(0, dp(12), 0, dp(12))
        root.addView(statusText)

        val battBtn = Button(this)
        battBtn.text = "Matikan hemat baterai (cari Live ASMR)"
        root.addView(battBtn)

        pickBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "video/*"
            i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            startActivityForResult(i, 1)
        }

        startBtn.setOnClickListener {
            if (LiveState.running) {
                startService(Intent(this, LiveService::class.java).setAction(LiveService.ACTION_STOP))
            } else {
                startLive()
            }
        }

        battBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Buka Pengaturan > Baterai secara manual")
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            videoUri = uri
            prefs.edit().putString("uri", uri.toString()).apply()
            showFileName()
        }
    }

    private fun startLive() {
        val uri = videoUri
        if (uri == null) {
            toast("Pilih video dulu")
            return
        }
        val server = serverEdit.text.toString().trim().trimEnd('/')
        val key = keyEdit.text.toString().trim()
        if (server.isEmpty() || key.isEmpty()) {
            toast("Isi alamat server dan Stream Key")
            return
        }
        val hours = (hoursEdit.text.toString().toDoubleOrNull() ?: 12.0).coerceIn(0.05, 72.0)
        val kbps = (bitrateEdit.text.toString().toIntOrNull() ?: 2500).coerceIn(500, 8000)
        prefs.edit()
            .putString("server", server)
            .putString("key", key)
            .putString("hours", hoursEdit.text.toString())
            .putString("kbps", kbps.toString())
            .apply()

        val i = Intent(this, LiveService::class.java)
        i.action = LiveService.ACTION_START
        i.putExtra(LiveService.EXTRA_URI, uri.toString())
        i.putExtra(LiveService.EXTRA_URL, "$server/$key")
        i.putExtra(LiveService.EXTRA_BITRATE, kbps)
        i.putExtra(LiveService.EXTRA_HOURS, hours)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun showFileName() {
        val uri = videoUri
        if (uri == null) {
            fileText.text = "Belum ada video dipilih"
            return
        }
        var name = uri.lastPathSegment ?: "video"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
            }
        } catch (_: Exception) {
        }
        fileText.text = "Video: $name"
    }

    private val tick = object : Runnable {
        override fun run() {
            val running = LiveState.running
            startBtn.text = if (running) "BERHENTI LIVE" else "MULAI LIVE"
            val sb = StringBuilder(LiveState.status)
            if (running) {
                val now = System.currentTimeMillis()
                sb.append("\nBerjalan: ").append(fmt(now - LiveState.startedAt))
                sb.append("\nSisa: ").append(fmt(maxOf(0L, LiveState.endAt - now)))
            }
            statusText.text = sb.toString()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, dp(16), 0, dp(4))
        return t
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
