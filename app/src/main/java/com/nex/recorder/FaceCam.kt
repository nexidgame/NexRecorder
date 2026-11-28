package com.nex.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val CAM_SQUARE = 0
const val CAM_CIRCLE = 1
const val CAM_FX_NONE = 0
const val CAM_FX_BRIGHT = 1
const val CAM_FX_WARM = 2
const val CAM_FX_DARK = 3
const val ACTION_FACECAM_OFF = "com.nex.recorder.FACECAM_OFF"

/** Ukuran maksimum facecam: sekitar 75% sisi pendek layar (seperti XRecorder). */
private const val CAM_MAX_FRACTION = 0.75f

/** Status facecam yang dibaca UI dan service. */
object FaceCamState {
    val enabled = MutableStateFlow(false)
    val shape = MutableStateFlow(CAM_SQUARE)
    val effect = MutableStateFlow(CAM_FX_NONE)
}

/** Pengaturan facecam yang disimpan (terpisah dari pengaturan rekaman). */
object CamPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("facecam", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("on", on).apply()

    fun front(c: Context) = sp(c).getBoolean("front", true)
    fun setFront(c: Context, v: Boolean) = sp(c).edit().putBoolean("front", v).apply()

    fun sizeDp(c: Context) = sp(c).getInt("size_dp", 150)
    fun setSizeDp(c: Context, v: Int) = sp(c).edit().putInt("size_dp", v).apply()

    fun posX(c: Context) = sp(c).getInt("x", -1)
    fun posY(c: Context) = sp(c).getInt("y", -1)
    fun setPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("x", x).putInt("y", y).apply()

    fun shape(c: Context) = sp(c).getInt("shape", CAM_SQUARE)
    fun setShape(c: Context, v: Int) {
        sp(c).edit().putInt("shape", v).apply()
        FaceCamState.shape.value = v
    }

    fun effect(c: Context) = sp(c).getInt("effect", CAM_FX_NONE)
    fun setEffect(c: Context, v: Int) {
        sp(c).edit().putInt("effect", v).apply()
        FaceCamState.effect.value = v
    }

    /** Memuat bentuk dan efek tersimpan ke status yang dibaca service. */
    fun syncState(c: Context) {
        FaceCamState.shape.value = shape(c)
        FaceCamState.effect.value = effect(c)
    }
}

/**
 * Facecam: pratinjau kamera melayang (bawaan di kiri atas), bisa digeser.
 * Ketuk kamera untuk menampilkan tombol: pengaturan, tutup, balik kamera, dan ubah ukuran.
 * Pratinjau berada di atas layar sehingga ikut terekam.
 *
 * Kamera memakai CameraX (PreviewView) sehingga arah gambar otomatis mengikuti arah HP
 * (portrait, landscape, terbalik) dan kamera depan dicerminkan seperti selfie.
 */
class FaceCamService : LifecycleService() {

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    private var box: FrameLayout? = null
    private var preview: PreviewView? = null
    private var controls: List<ControlButton> = emptyList()
    private var cameraProvider: ProcessCameraProvider? = null
    private var sizePx = 0
    private var front = true
    private var controlsVisible = false
    private val hideControlsRunnable = Runnable { showControls(false) }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun dp(v: Int) = (v * density).roundToInt()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        scope.launch { FaceCamState.shape.collect { applyShape(it) } }
        scope.launch { FaceCamState.effect.collect { applyEffect(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_FACECAM_OFF) {
            turnOff()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: Exception) {
            turnOff()
            return START_NOT_STICKY
        }
        if (box == null) {
            val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (!camOk || !Settings.canDrawOverlays(this)) {
                turnOff()
                return START_NOT_STICKY
            }
            buildWindow()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Layar diputar: sesuaikan posisi. Arah gambar kamera diurus otomatis oleh CameraX.
        handler.post {
            if (box == null) return@post
            clampToScreen()
            updateWindow()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        try { cameraProvider?.unbindAll() } catch (e: Exception) { }
        cameraProvider = null
        box?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        box = null
        super.onDestroy()
    }

    private fun turnOff() {
        FaceCamState.enabled.value = false
        CamPrefs.setEnabled(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ jendela

    private fun buildWindow() {
        front = CamPrefs.front(this)
        sizePx = min(dp(CamPrefs.sizeDp(this)), maxSizePx())

        val b = FrameLayout(this)
        val pv = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // perlu agar bisa dipotong bulat
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        b.addView(pv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        preview = pv

        val kinds = listOf(KIND_SETTINGS, KIND_CLOSE, KIND_FLIP, KIND_RESIZE)
        controls = kinds.map { k ->
            ControlButton(this, k).also {
                it.visibility = View.GONE
                b.addView(it, FrameLayout.LayoutParams(1, 1))
            }
        }
        layoutControls()
        controls[0].setOnClickListener { openSettings() }
        controls[1].setOnClickListener { turnOff() }
        controls[2].setOnClickListener { flipCamera() }
        setupResize(controls[3])

        b.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.invalidateOutline() }
        box = b

        val p = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // wajib untuk pratinjau kamera
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.LEFT
        val sx = CamPrefs.posX(this)
        val sy = CamPrefs.posY(this)
        p.x = if (sx >= 0) sx else dp(8)   // bawaan: kiri atas
        p.y = if (sy >= 0) sy else dp(40)
        lp = p
        clampToScreen()

        setupDrag(b)
        applyShape(FaceCamState.shape.value)
        applyEffect(FaceCamState.effect.value)
        try {
            wm.addView(b, lp)
        } catch (e: Exception) {
            turnOff()
            return
        }
        bindCamera()
    }

    private fun screenBounds() = wm.maximumWindowMetrics.bounds

    private fun maxSizePx(): Int {
        val b = screenBounds()
        return (min(b.width(), b.height()) * CAM_MAX_FRACTION).roundToInt()
    }

    private fun clampToScreen() {
        val b = screenBounds()
        lp.x = lp.x.coerceIn(0, max(0, b.width() - sizePx))
        lp.y = lp.y.coerceIn(0, max(0, b.height() - sizePx))
    }

    private fun updateWindow() {
        box?.let { try { wm.updateViewLayout(it, lp) } catch (e: Exception) { } }
    }

    private fun layoutControls() {
        val btn = (sizePx * 0.2f).roundToInt().coerceIn(dp(26), dp(40))
        val m = (sizePx * 0.14f).roundToInt()
        val gravities = intArrayOf(
            Gravity.TOP or Gravity.LEFT, Gravity.TOP or Gravity.RIGHT,
            Gravity.BOTTOM or Gravity.LEFT, Gravity.BOTTOM or Gravity.RIGHT,
        )
        controls.forEachIndexed { i, v ->
            val p = FrameLayout.LayoutParams(btn, btn, gravities[i])
            p.setMargins(m, m, m, m)
            v.layoutParams = p
        }
    }

    private fun applyShape(shape: Int) {
        val b = box ?: return
        val radius = dp(14).toFloat()
        b.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (shape == CAM_CIRCLE) outline.setOval(0, 0, view.width, view.height)
                else outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        b.clipToOutline = true
        b.invalidateOutline()
    }

    private fun applyEffect(fx: Int) {
        val pv = preview ?: return
        val m = when (fx) {
            CAM_FX_BRIGHT -> ColorMatrix(
                floatArrayOf(
                    1.15f, 0f, 0f, 0f, 18f, 0f, 1.15f, 0f, 0f, 18f,
                    0f, 0f, 1.15f, 0f, 18f, 0f, 0f, 0f, 1f, 0f,
                ),
            )
            CAM_FX_WARM -> ColorMatrix(
                floatArrayOf(
                    1.10f, 0f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 0f,
                    0f, 0f, 0.86f, 0f, -8f, 0f, 0f, 0f, 1f, 0f,
                ),
            )
            CAM_FX_DARK -> ColorMatrix(
                floatArrayOf(
                    0.72f, 0f, 0f, 0f, -6f, 0f, 0.72f, 0f, 0f, -6f,
                    0f, 0f, 0.72f, 0f, -6f, 0f, 0f, 0f, 1f, 0f,
                ),
            )
            else -> null
        }
        if (m == null) {
            pv.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            pv.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        }
    }

    // ------------------------------------------------------------ geser & kontrol

    private fun setupDrag(b: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        showControls(false)
                    }
                    if (moved) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        clampToScreen()
                        updateWindow()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) CamPrefs.setPos(this, lp.x, lp.y) else showControls(!controlsVisible)
                }
            }
            true
        }
    }

    private fun setupResize(btn: View) {
        var downX = 0f
        var downY = 0f
        var startSize = 0
        btn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startSize = sizePx
                    handler.removeCallbacks(hideControlsRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = ((e.rawX - downX) + (e.rawY - downY)) / 2f
                    resizeTo(startSize + d.roundToInt())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    CamPrefs.setSizeDp(this, (sizePx / density).roundToInt())
                    scheduleHideControls()
                    true
                }
                else -> false
            }
        }
    }

    private fun resizeTo(px: Int) {
        sizePx = px.coerceIn(dp(80), maxSizePx())
        lp.width = sizePx
        lp.height = sizePx
        clampToScreen()
        updateWindow()
        layoutControls()
    }

    private fun showControls(show: Boolean) {
        controlsVisible = show
        handler.removeCallbacks(hideControlsRunnable)
        controls.forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
        if (show) scheduleHideControls()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun openSettings() {
        showControls(false)
        startActivity(
            Intent(this, FacecamSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun flipCamera() {
        front = !front
        CamPrefs.setFront(this, front)
        bindCamera()
        scheduleHideControls()
    }

    // ------------------------------------------------------------------ kamera

    /** Menghubungkan pratinjau kamera (CameraX) ke jendela; dipanggil ulang saat kamera dibalik. */
    private fun bindCamera() {
        val pv = preview ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    // Pratinjau kecil sudah cukup untuk facecam dan lebih hemat daya.
                    val selector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()
                    val useCase = Preview.Builder().setResolutionSelector(selector).build()
                    useCase.setSurfaceProvider(pv.surfaceProvider)
                    val camera = if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                    provider.unbindAll()
                    provider.bindToLifecycle(this, camera, useCase)
                } catch (e: Exception) {
                    Toast.makeText(this, "Kamera tidak dapat dibuka", Toast.LENGTH_SHORT).show()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    // ------------------------------------------------------------- notifikasi

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Facecam", NotificationManager.IMPORTANCE_MIN),
        )
        val off = PendingIntent.getService(
            this, 3,
            Intent(this, FaceCamService::class.java).setAction(ACTION_FACECAM_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Facecam aktif")
            .setContentText("Ketuk kamera di layar untuk pengaturan")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, "Matikan", off)
            .build()
    }

    private companion object {
        const val CHANNEL = "facecam"
        const val NOTIF_ID = 3
    }
}

private const val KIND_SETTINGS = 0
private const val KIND_CLOSE = 1
private const val KIND_FLIP = 2
private const val KIND_RESIZE = 3

/** Tombol bulat biru di sudut facecam; ikonnya digambar sendiri (tanpa library ikon). */
private class ControlButton(context: Context, private val kind: Int) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 37, 99, 235) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        contentDescription = when (kind) {
            KIND_SETTINGS -> "Pengaturan facecam"
            KIND_CLOSE -> "Tutup facecam"
            KIND_FLIP -> "Balik kamera"
            else -> "Ubah ukuran"
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, w / 2f, bg)
        fg.strokeWidth = w * 0.075f
        when (kind) {
            KIND_CLOSE -> {
                val d = w * 0.18f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, fg)
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, fg)
            }
            KIND_SETTINGS -> {
                val r = w * 0.27f
                val hex = Path()
                for (i in 0 until 6) {
                    val a = Math.toRadians((60.0 * i) - 90.0)
                    val x = cx + r * cos(a).toFloat()
                    val y = cy + r * sin(a).toFloat()
                    if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
                }
                hex.close()
                canvas.drawPath(hex, fg)
                canvas.drawCircle(cx, cy, w * 0.1f, fg)
            }
            KIND_FLIP -> {
                val r = w * 0.24f
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, 200f, 120f, false, fg)
                canvas.drawArc(rect, 20f, 120f, false, fg)
                arrowHead(canvas, cx, cy, r, 320f, w * 0.13f)
                arrowHead(canvas, cx, cy, r, 140f, w * 0.13f)
            }
            else -> {
                val d = w * 0.19f
                val a = w * 0.13f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, fg)
                canvas.drawLine(cx + d, cy + d, cx + d - a, cy + d, fg)
                canvas.drawLine(cx + d, cy + d, cx + d, cy + d - a, fg)
                canvas.drawLine(cx - d, cy - d, cx - d + a, cy - d, fg)
                canvas.drawLine(cx - d, cy - d, cx - d, cy - d + a, fg)
            }
        }
    }

    /** Kepala panah di ujung busur pada sudut [endDeg], mengikuti arah putaran searah jarum jam. */
    private fun arrowHead(canvas: Canvas, cx: Float, cy: Float, r: Float, endDeg: Float, len: Float) {
        val a = Math.toRadians(endDeg.toDouble())
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        val dir = a + Math.PI / 2.0 // arah singgung
        for (off in doubleArrayOf(Math.PI - 0.6, Math.PI + 0.6)) {
            canvas.drawLine(
                x, y,
                x + len * cos(dir + off).toFloat(),
                y + len * sin(dir + off).toFloat(),
                fg,
            )
        }
    }
}
