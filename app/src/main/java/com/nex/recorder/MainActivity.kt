package com.nex.recorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
        setContent {
            AppTheme {
                MainScreen(
                    onStart = ::onStartClicked,
                    onStop = ::onStopClicked,
                    onFloating = ::onFloatingChanged,
                    onFacecam = ::onFacecamChanged,
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
}
