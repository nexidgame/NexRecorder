package com.nex.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val ACTION_WM_OFF = "com.nex.recorder.WM_OFF"
const val ACTION_WM_LOCK = "com.nex.recorder.WM_LOCK_TOGGLE"

private const val WM_MIN_SCALE = 0.15f
private const val WM_MAX_SCALE = 5f

/** Status watermark yang dibaca UI dan service. */
object WatermarkState {
    val enabled = MutableStateFlow(false)
    val locked = MutableStateFlow(false)
    val hasImage = MutableStateFlow(false)
    val resetTick = MutableStateFlow(0)
    val vertical = MutableStateFlow(false) // true = geser vertikal saja (atas ke tengah)
    val alpha = MutableStateFlow(1f)       // kepekatan watermark 0.1 - 1.0
}

/** Aksi watermark yang dipanggil dari layar (diimplementasikan MainActivity). */
class WatermarkActions(
    val toggle: (Boolean) -> Unit,
    val pick: () -> Unit,
    val lock: (Boolean) -> Unit,
    val reset: () -> Unit,
)

/** Pengaturan watermark yang disimpan. Posisi disimpan per arah layar (0, 90, 180, 270 derajat). */
object WmPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("watermark", Context.MODE_PRIVATE)

    fun imageFile(c: Context) = File(c.filesDir, "watermark.png")

    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("on", v).apply()

    fun locked(c: Context) = sp(c).getBoolean("locked", false)
    fun setLocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("locked", v).apply()

    fun scale(c: Context) = sp(c).getFloat("scale", 1f)
    fun angle(c: Context) = sp(c).getFloat("angle", 0f)
    fun setTransform(c: Context, scale: Float, angle: Float) =
        sp(c).edit().putFloat("scale", scale).putFloat("angle", angle).apply()

    /** Pusat watermark sebagai pecahan lebar/tinggi layar; -1 = belum diatur (pakai posisi awal). */
    fun posX(c: Context, rot: Int) = sp(c).getFloat("x$rot", -1f)
    fun posY(c: Context, rot: Int) = sp(c).getFloat("y$rot", -1f)
    fun setPos(c: Context, rot: Int, fx: Float, fy: Float) =
        sp(c).edit().putFloat("x$rot", fx).putFloat("y$rot", fy).apply()

    /** Kepekatan watermark (1 = tidak transparan). Terpisah dari kunci posisi. */
    fun alpha(c: Context) = sp(c).getFloat("alpha", 1f).coerceIn(0.1f, 1f)
    fun setAlpha(c: Context, v: Float) = sp(c).edit().putFloat("alpha", v.coerceIn(0.1f, 1f)).apply()

    /** Mode geser: false = bebas, true = hanya vertikal dari atas sampai tengah layar. */
    fun vertical(c: Context) = sp(c).getBoolean("vertical", false)
    fun setVertical(c: Context, v: Boolean) = sp(c).edit().putBoolean("vertical", v).apply()

    /** Pusat vertikal (pecahan tinggi layar) untuk mode vertikal; -1 = posisi awal di atas. */
    fun vPosY(c: Context, rot: Int) = sp(c).getFloat("vy$rot", -1f)
    fun setVPosY(c: Context, rot: Int, fy: Float) = sp(c).edit().putFloat("vy$rot", fy).apply()

    fun resetTransform(c: Context) {
        val e = sp(c).edit().remove("scale").remove("angle")
        for (r in 0..3) e.remove("x$r").remove("y$r").remove("vy$r")
        e.apply()
    }

    fun syncState(c: Context) {
        val has = imageFile(c).exists()
        WatermarkState.hasImage.value = has
        WatermarkState.locked.value = has && locked(c)
        WatermarkState.vertical.value = vertical(c)
        WatermarkState.alpha.value = alpha(c)
    }
}

/**
 * Watermark: gambar dari penyimpanan yang tampil di atas layar dan ikut terekam.
 * Posisi awal di tengah atas layar (untuk setiap arah layar). Geser untuk memindahkan (mode bebas, atau mode vertikal: hanya naik-turun dari atas sampai tengah), cubit untuk
 * membesarkan/mengecilkan, putar dua jari untuk memutar 360 derajat. Ketuk untuk menampilkan tombol kunci.
 * Setelah dikunci, jendelanya tidak menerima sentuhan sama sekali (tidak mengganggu game).
 * Sebelum dikunci, watermark bisa dibuang dengan menyeretnya ke ikon tempat sampah.
 */
class WatermarkService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    private var root: FrameLayout? = null
    private var image: ImageView? = null
    private var lockBtn: LockButton? = null
    private var trash: TrashView? = null
    private val hideLockRunnable = Runnable { showLock(false) }

    private var bmpW = 0   // ukuran dasar (px) pada skala 1
    private var bmpH = 0
    private var scale = 1f
    private var angle = 0f // derajat, searah jarum jam
    private var cx = 0f    // pusat watermark dalam piksel layar
    private var cy = 0f
    private var screenW = 0
    private var screenH = 0
    private var locked = false
    private var vertical = false
    private var wmAlpha = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * density).roundToInt()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        vertical = WmPrefs.vertical(this)
        wmAlpha = WmPrefs.alpha(this)
        scope.launch {
            WatermarkState.alpha.collect {
                wmAlpha = it
                image?.alpha = it
            }
        }
        scope.launch { WatermarkState.locked.collect { applyLocked(it) } }
        scope.launch {
            WatermarkState.vertical.collect {
                if (it == vertical) return@collect
                vertical = it
                if (root != null) {
                    refreshScreen()
                    placeFromPrefs()
                    applyLayout()
                }
            }
        }
        val initial = WatermarkState.resetTick.value
        scope.launch {
            WatermarkState.resetTick.collect { if (it != initial) resetPlacement() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WM_OFF -> {
                turnOff()
                return START_NOT_STICKY
            }
            ACTION_WM_LOCK -> {
                setLockedState(!WatermarkState.locked.value)
                return START_NOT_STICKY
            }
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (root == null) {
            if (!Settings.canDrawOverlays(this)) {
                turnOff()
                return START_NOT_STICKY
            }
            buildWindow()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Layar diputar: pakai posisi tersimpan untuk arah baru (atau tengah atas jika belum diatur).
        handler.post {
            if (root == null) return@post
            refreshScreen()
            placeFromPrefs()
            applyLayout()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        hideTrash()
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null
        super.onDestroy()
    }

    /** Mematikan watermark tetapi gambarnya tetap tersimpan. */
    private fun turnOff() {
        WatermarkState.enabled.value = false
        WmPrefs.setEnabled(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Membuang watermark: gambar dihapus dan fitur dimatikan. */
    private fun discard() {
        WmPrefs.imageFile(this).delete()
        WmPrefs.setLocked(this, false)
        WatermarkState.hasImage.value = false
        WatermarkState.locked.value = false
        Toast.makeText(this, "Watermark dibuang. Pilih gambar lagi di tab Rekam.", Toast.LENGTH_LONG).show()
        turnOff()
    }

    // ------------------------------------------------------------------ jendela

    private fun flags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        // Sengaja tanpa FLAG_NOT_TOUCHABLE: bendera itu membuat Android 12+ membatasi kepekatan overlay
        // (jadi agak transparan). Saat dikunci, jendela dibuat pas sebesar gambar dan menolak semua sentuhan.
        return f
    }

    private fun screenBounds() = wm.maximumWindowMetrics.bounds

    private fun refreshScreen() {
        val b = screenBounds()
        screenW = b.width()
        screenH = b.height()
    }

    private fun displayRotation(): Int {
        val dm = getSystemService(DisplayManager::class.java)
        return dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    private fun buildWindow() {
        val file = WmPrefs.imageFile(this)
        val bmp = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        if (bmp == null) {
            WatermarkState.hasImage.value = false
            turnOff()
            return
        }
        refreshScreen()

        // Ukuran dasar: sisi terpanjang gambar = 35% sisi pendek layar, rasio gambar dipertahankan.
        val target = min(screenW, screenH) * 0.35f
        val ratio = bmp.width.toFloat() / bmp.height
        var w = target
        var h = target / ratio
        if (h > target) {
            h = target
            w = target * ratio
        }
        bmpW = w.roundToInt().coerceAtLeast(dp(24))
        bmpH = h.roundToInt().coerceAtLeast(dp(24))
        scale = WmPrefs.scale(this).coerceIn(WM_MIN_SCALE, WM_MAX_SCALE)
        angle = WmPrefs.angle(this)
        locked = WatermarkState.locked.value

        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = wmAlpha
        }
        val r = FrameLayout(this)
        r.addView(iv, FrameLayout.LayoutParams(bmpW, bmpH, Gravity.CENTER))
        val lb = LockButton(this).apply {
            visibility = View.GONE
            setOnClickListener { lockNow() }
        }
        r.addView(lb, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.RIGHT))
        image = iv
        lockBtn = lb
        root = r

        lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags(),
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        placeFromPrefs()
        applyLayout()
        setupTouch(r)
        try {
            wm.addView(r, lp)
        } catch (e: Exception) {
            turnOff()
        }
    }

    /** Tinggi kotak pembatas watermark setelah diputar, dipakai untuk menempel ke tepi atas. */
    private fun boundsHeight(): Float {
        val w = bmpW * scale
        val h = bmpH * scale
        val rad = Math.toRadians((if (vertical) 0f else angle).toDouble())
        return (abs(w * sin(rad)) + abs(h * cos(rad))).toFloat()
    }

    /** Posisi tersimpan untuk arah layar saat ini, atau tengah atas layar jika belum pernah diatur. */
    private fun placeFromPrefs() {
        val rot = displayRotation()
        val fx = WmPrefs.posX(this, rot)
        val fy = WmPrefs.posY(this, rot)
        if (vertical) {
            val vy = WmPrefs.vPosY(this, rot)
            cx = screenW / 2f
            cy = if (vy >= 0f) vy * screenH else boundsHeight() / 2f + dp(4)
        } else if (fx >= 0f && fy >= 0f) {
            cx = fx * screenW
            cy = fy * screenH
        } else {
            cx = screenW / 2f
            cy = boundsHeight() / 2f + dp(4)
        }
        clampCenter()
    }

    private fun clampCenter() {
        if (vertical) {
            // Selalu di tengah horizontal; vertikal hanya dari atas sampai tengah layar.
            val top = boundsHeight() / 2f + dp(4)
            val bottom = max(top, screenH / 2f)
            cx = screenW / 2f
            cy = cy.coerceIn(top, bottom)
        } else {
            cx = cx.coerceIn(0f, screenW.toFloat())
            cy = cy.coerceIn(0f, screenH.toFloat())
        }
    }

    /** Memasang ukuran, putaran, dan posisi ke jendela. Jendela berbentuk kotak sebesar diagonal gambar agar muat saat diputar. */
    private fun applyLayout() {
        val w = bmpW * scale
        val h = bmpH * scale
        if (locked) {
            // Terkunci: jendela pas sebesar gambar agar area yang menahan sentuhan sekecil mungkin.
            val rad = Math.toRadians((if (vertical) 0f else angle).toDouble())
            val bw = (abs(w * cos(rad)) + abs(h * sin(rad))).toFloat()
            val bh = (abs(w * sin(rad)) + abs(h * cos(rad))).toFloat()
            lp.width = bw.roundToInt() + 2
            lp.height = bh.roundToInt() + 2
        } else {
            val diag = hypot(w, h).roundToInt() + dp(16)
            lp.width = diag
            lp.height = diag
        }
        lp.x = (cx - lp.width / 2f).roundToInt()
        lp.y = (cy - lp.height / 2f).roundToInt()
        image?.layoutParams = FrameLayout.LayoutParams(w.roundToInt(), h.roundToInt(), Gravity.CENTER)
        image?.rotation = if (vertical) 0f else angle // mode vertikal: tidak diputar
        root?.let { try { wm.updateViewLayout(it, lp) } catch (e: Exception) { } }
    }

    private fun savePlacement() {
        if (screenW <= 0 || screenH <= 0) return
        if (vertical) WmPrefs.setVPosY(this, displayRotation(), cy / screenH)
        else WmPrefs.setPos(this, displayRotation(), cx / screenW, cy / screenH)
        WmPrefs.setTransform(this, scale, angle)
    }

    private fun resetPlacement() {
        if (root == null) return
        WmPrefs.resetTransform(this)
        scale = 1f
        angle = 0f
        placeFromPrefs()
        applyLayout()
    }

    // ------------------------------------------------------------------ sentuhan

    private fun setupTouch(v: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startCx = 0f
        var startCy = 0f
        var moved = false
        var multi = false
        var startDist = 1f
        var startScale = 1f
        var lastAngle = 0f

        fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        fun ang(e: MotionEvent) =
            Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

        v.setOnTouchListener { _, e ->
            if (locked) return@setOnTouchListener true // terkunci: abaikan semua sentuhan
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startCx = cx
                    startCy = cy
                    moved = false
                    multi = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    multi = true
                    moved = true
                    hideTrash()
                    image?.alpha = wmAlpha
                    showLock(false)
                    startDist = max(dist(e), 1f)
                    startScale = scale
                    lastAngle = ang(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (multi && e.pointerCount >= 2) {
                        scale = (startScale * dist(e) / startDist).coerceIn(WM_MIN_SCALE, WM_MAX_SCALE)
                        val a = ang(e)
                        if (!vertical) {
                            var delta = a - lastAngle
                            if (delta > 180f) delta -= 360f
                            if (delta < -180f) delta += 360f
                            angle = ((angle + delta) % 360f + 360f) % 360f
                        }
                        lastAngle = a
                        clampCenter()
                        applyLayout()
                    } else if (!multi) {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                        if (moved) {
                            cx = if (vertical) screenW / 2f else startCx + dx
                            cy = startCy + dy
                            clampCenter()
                            applyLayout()
                            showLock(false)
                            showTrash()
                            val over = overTrash(e.rawX, e.rawY)
                            trash?.activate(over)
                            image?.alpha = if (over) wmAlpha * 0.6f else wmAlpha
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> if (multi && e.pointerCount - 1 < 2) {
                    // Satu jari tersisa: lanjut menggeser tanpa melompat.
                    multi = false
                    moved = true
                    val remaining = if (e.actionIndex == 0) 1 else 0
                    downX = e.getRawX(remaining)
                    downY = e.getRawY(remaining)
                    startCx = cx
                    startCy = cy
                    savePlacement()
                }
                MotionEvent.ACTION_UP -> {
                    if (multi) {
                        multi = false
                        savePlacement()
                    } else if (!moved) {
                        showLock(lockBtn?.visibility != View.VISIBLE) // ketuk: tampilkan/sembunyikan tombol kunci
                    } else {
                        val dropped = overTrash(e.rawX, e.rawY)
                        hideTrash()
                        image?.alpha = wmAlpha
                        if (dropped) discard() else savePlacement()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideTrash()
                    image?.alpha = wmAlpha
                    savePlacement()
                }
            }
            true
        }
    }

    // ---------------------------------------------------------- kunci dan tempat sampah

    private fun showLock(show: Boolean) {
        handler.removeCallbacks(hideLockRunnable)
        lockBtn?.visibility = if (show && !locked) View.VISIBLE else View.GONE
        if (show && !locked) handler.postDelayed(hideLockRunnable, 3500)
    }

    /** Tombol kunci diketuk: posisi, ukuran, dan putaran dikunci. */
    private fun lockNow() {
        setLockedState(true)
        Toast.makeText(
            this, "Watermark dikunci. Buka kunci di tab Rekam atau lewat notifikasi.", Toast.LENGTH_LONG,
        ).show()
    }

    private fun setLockedState(on: Boolean) {
        WmPrefs.setLocked(this, on)
        WatermarkState.locked.value = on
    }

    /** Saat dikunci, jendela tidak menerima sentuhan sama sekali sehingga game di bawahnya tetap bisa disentuh. */
    private fun applyLocked(on: Boolean) {
        locked = on
        showLock(false)
        if (root != null) {
            lp.flags = flags()
            applyLayout()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun showTrash() {
        if (trash != null) return
        val size = dp(64)
        val t = TrashView(this)
        val tlp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        try {
            wm.addView(t, tlp)
            trash = t
        } catch (e: Exception) { }
    }

    private fun hideTrash() {
        trash?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        trash = null
    }

    /** true jika jari berada di atas ikon tempat sampah (koordinat layar). */
    private fun overTrash(x: Float, y: Float): Boolean {
        val t = trash ?: return false
        if (t.width == 0) return false
        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        val dx = x - (loc[0] + t.width / 2f)
        val dy = y - (loc[1] + t.height / 2f)
        val r = t.width * 0.9f
        return dx * dx + dy * dy <= r * r
    }

    // ------------------------------------------------------------- notifikasi

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watermark", NotificationManager.IMPORTANCE_MIN),
        )
        val lockPi = PendingIntent.getService(
            this, 4,
            Intent(this, WatermarkService::class.java).setAction(ACTION_WM_LOCK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val offPi = PendingIntent.getService(
            this, 5,
            Intent(this, WatermarkService::class.java).setAction(ACTION_WM_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(if (locked) "Watermark aktif (terkunci)" else "Watermark aktif")
            .setContentText("Posisi, ukuran, dan putaran bisa diatur selama belum dikunci")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, if (locked) "Buka kunci" else "Kunci", lockPi)
            .addAction(0, "Matikan", offPi)
            .build()
    }

    private companion object {
        const val CHANNEL = "watermark"
        const val NOTIF_ID = 4
    }
}

/** Tombol kunci bulat yang muncul saat watermark diketuk; gembok digambar sendiri. */
private class LockButton(context: Context) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 25, 25, 25) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        contentDescription = "Kunci watermark"
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawCircle(w / 2f, h / 2f, w / 2f, bg)
        fg.strokeWidth = w * 0.07f
        // gagang gembok
        canvas.drawArc(RectF(w * 0.36f, h * 0.22f, w * 0.64f, h * 0.56f), 180f, 180f, false, fg)
        canvas.drawLine(w * 0.36f, h * 0.39f, w * 0.36f, h * 0.5f, fg)
        canvas.drawLine(w * 0.64f, h * 0.39f, w * 0.64f, h * 0.5f, fg)
        // badan gembok
        canvas.drawRoundRect(RectF(w * 0.29f, h * 0.48f, w * 0.71f, h * 0.76f), w * 0.05f, w * 0.05f, fill)
    }
}
