package com.nex.recorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Toast
import java.io.FileOutputStream
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                startRecordingService(r.resultCode, data)
                moveTaskToBack(true) // langsung kembali ke game/aplikasi sebelumnya
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            askProjection() // lanjut walau izin ditolak; audio otomatis dimatikan jika ditolak
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) setFloating(true)
        }

    // Alur facecam: izin "tampil di atas aplikasi lain" lalu izin kamera, baru kamera dinyalakan.
    private val facecamOverlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) ensureCameraThenStart()
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startFacecam()
        }

    // Watermark: pilih gambar dari penyimpanan, lalu izin "tampil di atas aplikasi lain".
    private var pendingWatermarkStart = false

    private val watermarkPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importWatermark(uri) else pendingWatermarkStart = false
        }

    private val watermarkOverlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) startWatermark() else pendingWatermarkStart = false
        }

    private val watermarkActions = WatermarkActions(
        toggle = { onWatermarkToggle(it) },
        pick = { pickWatermark() },
        lock = { onWatermarkLock(it) },
        reset = { onWatermarkReset() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Pulihkan tombol melayang jika sebelumnya diaktifkan dan izinnya masih ada.
        if (Prefs.floating(this) && Settings.canDrawOverlays(this)) setFloating(true)
        else OverlayState.enabled.value = false
        // Pulihkan facecam jika sebelumnya aktif dan semua izinnya masih ada.
        CamPrefs.syncState(this)
        if (CamPrefs.enabled(this) && Settings.canDrawOverlays(this) &&
            granted(Manifest.permission.CAMERA)
        ) {
            startFacecam()
        } else {
            FaceCamState.enabled.value = false
        }
        // Pulihkan watermark jika sebelumnya aktif dan gambarnya masih ada.
        WmPrefs.syncState(this)
        if (WmPrefs.enabled(this) && WatermarkState.hasImage.value && Settings.canDrawOverlays(this)) {
            startWatermark()
        } else {
            WatermarkState.enabled.value = false
        }
        setContent {
            AppTheme {
                MainScreen(
                    onStart = ::onStartClicked,
                    onStop = ::onStopClicked,
                    onFloating = ::onFloatingChanged,
                    onFacecam = ::onFacecamChanged,
                    watermark = watermarkActions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UiSignals.resumeTick.value += 1
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        val s = Prefs.load(this)
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (s.audioOn && !granted(Manifest.permission.RECORD_AUDIO)) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (need.isEmpty()) askProjection() else permissionLauncher.launch(need.toTypedArray())
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun onStopClicked() {
        startService(Intent(this, RecorderService::class.java).setAction(ACTION_STOP))
    }

    private fun onFloatingChanged(on: Boolean) {
        if (!on) {
            setFloating(false)
        } else if (Settings.canDrawOverlays(this)) {
            setFloating(true)
        } else {
            // Buka pengaturan "Tampil di atas aplikasi lain"; saat kembali, tombol diaktifkan jika diizinkan.
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
    }

    private fun setFloating(on: Boolean) {
        Prefs.setFloating(this, on)
        OverlayState.enabled.value = on
        val svc = Intent(this, OverlayService::class.java)
        if (on) ContextCompat.startForegroundService(this, svc) else stopService(svc)
    }

    private fun onFacecamChanged(on: Boolean) {
        if (!on) {
            stopFacecam()
        } else if (!Settings.canDrawOverlays(this)) {
            facecamOverlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        } else {
            ensureCameraThenStart()
        }
    }

    private fun ensureCameraThenStart() {
        if (granted(Manifest.permission.CAMERA)) startFacecam()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startFacecam() {
        CamPrefs.setEnabled(this, true)
        FaceCamState.enabled.value = true
        ContextCompat.startForegroundService(this, Intent(this, FaceCamService::class.java))
    }

    private fun stopFacecam() {
        CamPrefs.setEnabled(this, false)
        FaceCamState.enabled.value = false
        stopService(Intent(this, FaceCamService::class.java))
    }

    // ---------------------------------------------------------------- watermark

    private fun pickWatermark() {
        watermarkPicker.launch(arrayOf("image/*"))
    }

    private fun onWatermarkToggle(on: Boolean) {
        if (!on) {
            stopWatermark()
        } else if (!WatermarkState.hasImage.value) {
            pendingWatermarkStart = true // belum ada gambar: pilih dulu, lalu nyalakan
            pickWatermark()
        } else {
            requestWatermarkStart()
        }
    }

    private fun requestWatermarkStart() {
        if (Settings.canDrawOverlays(this)) {
            startWatermark()
        } else {
            watermarkOverlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
    }

    private fun startWatermark() {
        pendingWatermarkStart = false
        WmPrefs.setEnabled(this, true)
        WatermarkState.enabled.value = true
        ContextCompat.startForegroundService(this, Intent(this, WatermarkService::class.java))
    }

    private fun stopWatermark() {
        WmPrefs.setEnabled(this, false)
        WatermarkState.enabled.value = false
        stopService(Intent(this, WatermarkService::class.java))
    }

    private fun onWatermarkLock(on: Boolean) {
        WmPrefs.setLocked(this, on)
        WatermarkState.locked.value = on
    }

    private fun onWatermarkReset() {
        WmPrefs.resetTransform(this)
        WatermarkState.resetTick.value += 1
    }

    /** Membaca gambar terpilih di thread terpisah, mengecilkannya, lalu menyimpannya sebagai PNG internal. */
    private fun importWatermark(uri: Uri) {
        Thread {
            val ok = try { saveWatermarkImage(uri) } catch (e: Exception) { false }
            runOnUiThread {
                if (!ok) {
                    pendingWatermarkStart = false
                    Toast.makeText(this, "Gambar tidak dapat dibaca", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                WmPrefs.resetTransform(this)
                WatermarkState.hasImage.value = true
                // Gambar baru: muat ulang jika watermark sedang aktif, atau nyalakan jika diminta.
                if (WatermarkState.enabled.value || pendingWatermarkStart) {
                    stopService(Intent(this, WatermarkService::class.java))
                    requestWatermarkStart()
                }
            }
        }.start()
    }

    private fun saveWatermarkImage(uri: Uri): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        // Foto dari kamera menyimpan arah di EXIF; putar agar tampil tegak.
        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        }
        FileOutputStream(WmPrefs.imageFile(this)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }
}
