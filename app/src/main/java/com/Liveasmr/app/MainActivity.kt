package com.liveasmr.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {

    private val BG = Color.parseColor("#1B1033")
    private val CARD = Color.parseColor("#2B1D52")
    private val CHIP = Color.parseColor("#3D2A70")
    private val ACC = Color.parseColor("#B58CFF")
    private val RED = Color.parseColor("#FF3B5C")
    private val MUTED = Color.parseColor("#A99BC9")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val platNames = listOf("YouTube", "Facebook", "Lainnya")
    private val platServers = listOf(
        "rtmp://a.rtmp.youtube.com/live2",
        "rtmps://live-api-s.facebook.com:443/rtmp/",
        ""
    )

    private inner class TargetUi(val key: String) {
        var plat = 0
        lateinit var server: EditText
        lateinit var streamKey: EditText
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var onPicked: ((Uri) -> Unit)? = null
    private var videoUri: Uri? = null
    private var coverUri: Uri? = null
    private var modeIdx = 0

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var startBtn: Button
    private lateinit var resumeCard: LinearLayout
    private lateinit var videoName: TextView
    private lateinit var coverName: TextView
    private lateinit var coverResult: TextView
    private lateinit var t1: TargetUi
    private lateinit var t2: TargetUi
    private lateinit var swSecond: Switch
    private lateinit var secondBox: LinearLayout
    private lateinit var etHours: EditText
    private lateinit var etKbps: EditText
    private lateinit var etTitle: EditText
    private lateinit var etSub: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("live", MODE_PRIVATE)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(0, dp(28), 0, 0)
        setContentView(root)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(4), dp(16), dp(4))
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher)
        header.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = TextView(this)
        title.text = "  Live ASMR"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)
        root.addView(header)

        val sv = ScrollView(this)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(6), dp(14), dp(30))
        sv.addView(col)

        // Status
        val sc = card(col, "Status")
        statusText = TextView(this)
        statusText.setTextColor(Color.WHITE)
        statusText.textSize = 14f
        sc.addView(statusText)
        detailText = small("")
        sc.addView(detailText)

        // Lanjutkan live
        resumeCard = card(col, "Live sebelumnya terhenti")
        resumeCard.addView(small("HP sempat mati atau aplikasi tertutup. Lanjutkan dengan sisa waktu yang tersisa."))
        val rb = Button(this)
        rb.text = "LANJUTKAN LIVE"
        rb.setOnClickListener { resumeLive() }
        resumeCard.addView(rb)
        resumeCard.visibility = View.GONE

        // Video
        val vc = card(col, "1. Video")
        val vr = LinearLayout(this)
        vr.orientation = LinearLayout.HORIZONTAL
        vr.gravity = Gravity.CENTER_VERTICAL
        videoName = TextView(this)
        videoName.setTextColor(MUTED)
        videoName.textSize = 14f
        vr.addView(videoName, LinearLayout.LayoutParams(0, WRAP, 1f))
        val vb = Button(this)
        vb.text = "Pilih"
        vb.setOnClickListener {
            pickFile("video/*", 11) { uri ->
                videoUri = uri
                videoName.text = nameOf(uri)
                videoName.setTextColor(Color.WHITE)
            }
        }
        vr.addView(vb)
        vc.addView(vr)
        val savedVideo = prefs.getString("videoUri", null)
        if (savedVideo != null) {
            val u = Uri.parse(savedVideo)
            videoUri = u
            videoName.text = nameOf(u)
            videoName.setTextColor(Color.WHITE)
        } else {
            videoName.text = "Belum ada video"
        }

        // Tujuan live
        t1 = targetCard(col, "2. Tujuan live", "t1", 0)
        swSecond = Switch(this)
        swSecond.text = "Live juga ke platform kedua"
        swSecond.setTextColor(Color.WHITE)
        swSecond.isChecked = prefs.getBoolean("use2", false)
        val swLp = LinearLayout.LayoutParams(MATCH, WRAP)
        swLp.setMargins(dp(4), dp(12), dp(4), 0)
        col.addView(swSecond, swLp)
        secondBox = LinearLayout(this)
        secondBox.orientation = LinearLayout.VERTICAL
        col.addView(secondBox)
        t2 = targetCard(secondBox, "Tujuan kedua", "t2", 1)
        secondBox.visibility = if (swSecond.isChecked) View.VISIBLE else View.GONE
        swSecond.setOnCheckedChangeListener { _, on ->
            secondBox.visibility = if (on) View.VISIBLE else View.GONE
        }

        // Pengaturan
        val pc = card(col, "3. Pengaturan")
        pc.addView(small("Lama live (jam)"))
        etHours = field("12", prefs.getString("hours", "12") ?: "12")
        etHours.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        pc.addView(etHours)
        pc.addView(small("Kualitas video (kbps)"))
        etKbps = field("2500", prefs.getString("kbps", "2500") ?: "2500")
        etKbps.inputType = InputType.TYPE_CLASS_NUMBER
        pc.addView(etKbps)
        pc.addView(small("Mode sinyal"))
        modeIdx = prefs.getInt("mode", 0)
        choice(pc, listOf("Normal", "Sinyal lemah", "Otomatis"), modeIdx) { modeIdx = it }
        pc.addView(
            small(
                "Normal = kualitas tetap. Sinyal lemah = 600 kbps, hemat kuota. " +
                    "Otomatis = kualitas turun sendiri kalau koneksi putus berulang, lalu naik lagi setelah stabil."
            )
        )

        // Tombol mulai
        startBtn = Button(this)
        startBtn.setTypeface(null, Typeface.BOLD)
        startBtn.setOnClickListener { onStartClicked() }
        val sbLp = LinearLayout.LayoutParams(MATCH, dp(56))
        sbLp.setMargins(0, dp(18), 0, 0)
        col.addView(startBtn, sbLp)

        // Tips agar tidak mati
        val tc = card(col, "Agar live tidak mati saat ditinggal")
        tc.addView(small("Colokkan charger, lalu atur baterai aplikasi ini ke \"Tanpa batasan\" / \"Tidak dioptimalkan\"."))
        val bb = Button(this)
        bb.text = "Atur baterai"
        bb.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Buka Pengaturan > Baterai secara manual")
            }
        }
        tc.addView(bb)

        // Pembuat sampul
        val cc = card(col, "Pembuat sampul (thumbnail 1280x720)")
        cc.addView(small("Hasilnya disimpan ke galeri (folder Pictures/LiveASMR). Pasang di YouTube Studio sebagai thumbnail."))
        val cr = LinearLayout(this)
        cr.orientation = LinearLayout.HORIZONTAL
        cr.gravity = Gravity.CENTER_VERTICAL
        coverName = TextView(this)
        coverName.text = "Belum ada foto (latar polos)"
        coverName.setTextColor(MUTED)
        coverName.textSize = 14f
        cr.addView(coverName, LinearLayout.LayoutParams(0, WRAP, 1f))
        val cb = Button(this)
        cb.text = "Foto"
        cb.setOnClickListener {
            pickFile("image/*", 12) { uri ->
                coverUri = uri
                coverName.text = nameOf(uri)
                coverName.setTextColor(Color.WHITE)
            }
        }
        cr.addView(cb)
        cc.addView(cr)
        etTitle = field("Judul, contoh: ASMR HEAVY RAIN SOUNDS", prefs.getString("coverTitle", "") ?: "")
        cc.addView(etTitle)
        etSub = field("Teks kecil, contoh: RAYO NATURE", prefs.getString("coverSub", "") ?: "")
        cc.addView(etSub)
        val mk = Button(this)
        mk.text = "BUAT SAMPUL"
        mk.setOnClickListener { onMakeCover() }
        cc.addView(mk)
        coverResult = small("")
        cc.addView(coverResult)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        handleResumeIntent()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleResumeIntent()
    }

    // ---------- Live ----------

    private fun targetCard(parent: LinearLayout, title: String, key: String, defPlat: Int): TargetUi {
        val t = TargetUi(key)
        val c = card(parent, title)
        t.plat = prefs.getInt(key + "_plat", defPlat)
        choice(c, platNames, t.plat) { i ->
            t.plat = i
            if (i < 2) t.server.setText(platServers[i])
        }
        c.addView(small("Alamat server"))
        t.server = field("rtmp://...", prefs.getString(key + "_server", platServers[t.plat]) ?: "")
        c.addView(t.server)
        c.addView(small("Stream Key"))
        t.streamKey = field("Tempel Stream Key di sini", prefs.getString(key + "_key", "") ?: "")
        c.addView(t.streamKey)
        return t
    }

    private fun buildUrl(t: TargetUi): String? {
        val s = t.server.text.toString().trim().trimEnd('/')
        val k = t.streamKey.text.toString().trim()
        if (!s.startsWith("rtmp") || k.isEmpty()) return null
        return "$s/$k"
    }

    private fun savePrefs() {
        val e = prefs.edit()
        e.putString("videoUri", videoUri?.toString())
        e.putBoolean("use2", swSecond.isChecked)
        e.putString("hours", etHours.text.toString())
        e.putString("kbps", etKbps.text.toString())
        e.putInt("mode", modeIdx)
        for (t in listOf(t1, t2)) {
            e.putInt(t.key + "_plat", t.plat)
            e.putString(t.key + "_server", t.server.text.toString())
            e.putString(t.key + "_key", t.streamKey.text.toString())
        }
        e.putString("coverTitle", etTitle.text.toString())
        e.putString("coverSub", etSub.text.toString())
        e.apply()
    }

    private fun onStartClicked() {
        if (LiveState.running) {
            startService(Intent(this, LiveService::class.java).setAction(LiveService.ACTION_STOP))
            return
        }
        val hours = (etHours.text.toString().toDoubleOrNull() ?: 12.0).coerceIn(0.1, 24.0)
        launch(hours)
    }

    private fun launch(hours: Double) {
        val v = videoUri
        if (v == null) {
            toast("Pilih video dulu")
            return
        }
        val urls = ArrayList<String>()
        val labels = ArrayList<String>()
        val u1 = buildUrl(t1)
        if (u1 == null) {
            toast("Isi alamat server dan Stream Key untuk tujuan live")
            return
        }
        urls.add(u1)
        labels.add(platNames[t1.plat])
        if (swSecond.isChecked) {
            val u2 = buildUrl(t2)
            if (u2 == null) {
                toast("Isi alamat server dan Stream Key untuk tujuan kedua")
                return
            }
            urls.add(u2)
            labels.add(platNames[t2.plat] + if (t2.plat == t1.plat) " 2" else "")
        }
        savePrefs()
        val kbps = (etKbps.text.toString().toIntOrNull() ?: 2500).coerceIn(300, 8000)
        val i = Intent(this, LiveService::class.java)
            .setAction(LiveService.ACTION_START)
            .putExtra(LiveService.EXTRA_URI, v.toString())
            .putExtra(LiveService.EXTRA_URLS, urls.toTypedArray())
            .putExtra(LiveService.EXTRA_LABELS, labels.toTypedArray())
            .putExtra(LiveService.EXTRA_BITRATE, kbps)
            .putExtra(LiveService.EXTRA_HOURS, hours)
            .putExtra(LiveService.EXTRA_MODE, modeIdx)
        startForegroundService(i)
    }

    private fun canResume(): Boolean =
        !LiveState.running && prefs.getBoolean("wasLive", false) &&
            prefs.getLong("endAt", 0L) > System.currentTimeMillis()

    private fun resumeLive() {
        if (!canResume()) return
        val left = (prefs.getLong("endAt", 0L) - System.currentTimeMillis()) / 3600_000.0
        launch(left.coerceAtLeast(0.05))
    }

    private fun handleResumeIntent() {
        val it = intent ?: return
        if (it.getBooleanExtra("resume", false)) {
            it.removeExtra("resume")
            resumeLive()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            statusText.text = LiveState.status
            var d = LiveState.detail
            if (LiveState.running) {
                val leftMin = (LiveState.endAt - System.currentTimeMillis()) / 60000
                if (leftMin >= 0) d += "\nSisa waktu: " + (leftMin / 60) + " jam " + (leftMin % 60) + " menit"
            }
            detailText.text = d
            if (LiveState.running) {
                startBtn.text = "BERHENTI"
                startBtn.setTextColor(Color.WHITE)
                startBtn.background = rounded(RED, 14)
            } else {
                startBtn.text = "MULAI LIVE"
                startBtn.setTextColor(Color.BLACK)
                startBtn.background = rounded(ACC, 14)
            }
            resumeCard.visibility = if (canResume()) View.VISIBLE else View.GONE
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        savePrefs()
        super.onPause()
    }

    // ---------- Pembuat sampul ----------

    private fun onMakeCover() {
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            toast("Tulis judul dulu")
            return
        }
        val sub = etSub.text.toString().trim()
        val img = coverUri
        coverResult.text = "Membuat sampul..."
        Thread {
            val result = try {
                saveCover(renderCover(img, title, sub))
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                coverResult.text = if (result != null) {
                    "Selesai! Tersimpan di galeri: Pictures/LiveASMR/$result"
                } else {
                    "Gagal membuat sampul. Coba foto lain."
                }
            }
        }.start()
    }

    private fun loadBitmap(uri: Uri, maxW: Int, maxH: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, o2)
        } ?: return null
        var rot = 0
        try {
            contentResolver.openInputStream(uri)?.use {
                val ex = ExifInterface(it)
                rot = when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90
                    3 -> 180
                    8 -> 270
                    else -> 0
                }
            }
        } catch (_: Exception) {
        }
        if (rot == 0) return bmp
        val m = Matrix()
        m.postRotate(rot.toFloat())
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun renderCover(img: Uri?, title: String, sub: String): Bitmap {
        val w = 1280
        val h = 720
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val src = if (img != null) loadBitmap(img, w, h) else null
        if (src != null) {
            val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            c.drawBitmap(
                src, null,
                RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } else {
            c.drawColor(Color.parseColor("#1B1033"))
        }
        val shade = Paint()
        shade.shader = LinearGradient(
            0f, h * 0.3f, 0f, h.toFloat(), 0x00000000, 0xDD000000.toInt(), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)

        val text = title.uppercase()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.color = Color.WHITE
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.setShadowLayer(8f, 0f, 4f, Color.BLACK)
        var layout: StaticLayout
        var size = 100f
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, w - 120)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .build()
            if (layout.lineCount <= 3 || size <= 44f) break
            size -= 6f
        }
        var bottom = h - 56f
        if (sub.isNotEmpty()) {
            val sp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            sp.color = Color.parseColor("#FFD166")
            sp.typeface = Typeface.DEFAULT_BOLD
            sp.textSize = 44f
            sp.setShadowLayer(6f, 0f, 3f, Color.BLACK)
            c.drawText(sub.uppercase(), 60f, bottom, sp)
            bottom -= 62f
        }
        c.save()
        c.translate(60f, bottom - layout.height)
        layout.draw(c)
        c.restore()
        return out
    }

    private fun saveCover(bmp: Bitmap): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "Sampul_$stamp.jpg"
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LiveASMR")
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Tidak bisa membuat file")
        contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
        } ?: throw IllegalStateException("Tidak bisa menulis file")
        return name
    }

    // ---------- Pilih file ----------

    private fun pickFile(mime: String, code: Int, cb: (Uri) -> Unit) {
        onPicked = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(i, code)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 11 || requestCode == 12) && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            onPicked?.invoke(uri)
        }
    }

    private fun nameOf(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
            }
        } catch (_: Exception) {
        }
        return name
    }

    // ---------- Komponen tampilan ----------

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = rounded(CARD, 16)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(10), 0, 0)
        val t = TextView(this)
        t.text = title
        t.setTextColor(ACC)
        t.textSize = 15f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, 0, 0, dp(6))
        c.addView(t)
        parent.addView(c, lp)
        return c
    }

    private fun small(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(MUTED)
        t.textSize = 12f
        t.setPadding(dp(2), dp(6), dp(2), dp(2))
        return t
    }

    private fun field(hint: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(MUTED)
        e.setText(value)
        e.setTextColor(Color.WHITE)
        e.textSize = 14f
        e.setSingleLine(true)
        e.background = rounded(CHIP, 12)
        e.setPadding(dp(12), dp(12), dp(12), dp(12))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(4), 0, dp(4))
        e.layoutParams = lp
        return e
    }

    private fun choice(parent: LinearLayout, labels: List<String>, def: Int, onChange: (Int) -> Unit) {
        var selected = def
        val views = ArrayList<TextView>()
        fun paint() {
            views.forEachIndexed { i, v ->
                v.background = rounded(if (i == selected) ACC else CHIP, 20)
                v.setTextColor(if (i == selected) Color.BLACK else Color.WHITE)
            }
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        parent.addView(row)
        for ((i, l) in labels.withIndex()) {
            val tv = TextView(this)
            tv.text = l
            tv.gravity = Gravity.CENTER
            tv.textSize = 13f
            tv.setPadding(dp(6), dp(12), dp(6), dp(12))
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            tv.setOnClickListener {
                selected = i
                paint()
                onChange(i)
            }
            row.addView(tv, lp)
            views.add(tv)
        }
        paint()
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
