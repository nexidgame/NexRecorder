package com.nex.recorder

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Red = Color(0xFFE53935)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFFF8A80), background = Color(0xFF121214))
        else -> lightColorScheme(primary = Red)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String) {
    REKAM("Rekam"),
    MEDIA("Media"),
    VIDEO("Video"),
    AUDIO("Audio"),
    PENGATURAN("Pengaturan"),
}

@Composable
fun MainScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFloating: (Boolean) -> Unit,
    onFacecam: (Boolean) -> Unit,
    watermark: WatermarkActions,
) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(Prefs.load(ctx)) }
    val startedAt by RecorderState.startedAt.collectAsState()
    val floating by OverlayState.enabled.collectAsState()
    val facecam by FaceCamState.enabled.collectAsState()
    val recording = startedAt != 0L
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val resumeTick by UiSignals.resumeTick.collectAsState()
    LaunchedEffect(resumeTick) { s = Prefs.load(ctx) }

    LaunchedEffect(recording) {
        while (recording) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    fun update(n: RecSettings) {
        s = n
        Prefs.save(ctx, n)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { TabIcon(t) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        if (Tab.entries[tab] == Tab.MEDIA) {
            Box(Modifier.padding(pad).padding(horizontal = 20.dp)) { MediaTab() }
        } else Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            when (Tab.entries[tab]) {
                Tab.MEDIA -> Unit
                Tab.REKAM -> RecordTab(
                    s, recording, startedAt, now, floating, onStart, onStop, onFloating, facecam, onFacecam, watermark,
                )
                Tab.VIDEO -> VideoTab(s, recording) { update(it) }
                Tab.AUDIO -> AudioTab(s, recording) { update(it) }
                Tab.PENGATURAN -> SettingsTab(s, recording, { update(it) }) { update(RecSettings()) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------- Tab Rekam

@Composable
private fun RecordTab(
    s: RecSettings,
    recording: Boolean,
    startedAt: Long,
    now: Long,
    floating: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFloating: (Boolean) -> Unit,
    facecam: Boolean,
    onFacecam: (Boolean) -> Unit,
    watermark: WatermarkActions,
) {
    Text(
        "Nex Recorder",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(36.dp))
    RecordButton(recording, if (recording) onStop else onStart)
    Spacer(Modifier.height(16.dp))
    Text(
        if (recording) formatTime(now - startedAt) else "Ketuk untuk mulai merekam",
        style = MaterialTheme.typography.titleLarge,
        color = if (recording) Red else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        summary(s),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))
    SectionCard(spacing = 8.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Tombol rekam melayang", style = MaterialTheme.typography.bodyLarge)
            Switch(floating, onFloating)
        }
        Text(
            "Tombol merah di tengah layar. Ketuk untuk mulai merekam, lalu tombol hilang. " +
                "Berhenti lewat tombol di notifikasi. Tombol bisa digeser; seret ke ikon tempat sampah " +
                "yang muncul di bawah layar untuk menghapusnya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
    SectionCard(spacing = 8.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Kamera (facecam)", style = MaterialTheme.typography.bodyLarge)
            Switch(facecam, onFacecam)
        }
        Text(
            "Menampilkan kamera depan di layar (bawaan di kiri atas) yang ikut terekam. " +
                "Geser untuk memindahkan. Ketuk kamera untuk menampilkan tombol pengaturan, " +
                "tutup, balik kamera, dan ubah ukuran.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
    WatermarkCard(watermark)
}

@Composable
private fun WatermarkCard(a: WatermarkActions) {
    val enabled by WatermarkState.enabled.collectAsState()
    val locked by WatermarkState.locked.collectAsState()
    val hasImage by WatermarkState.hasImage.collectAsState()
    val vertical by WatermarkState.vertical.collectAsState()
    val wmAlpha by WatermarkState.alpha.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SectionCard(spacing = 8.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Watermark", style = MaterialTheme.typography.bodyLarge)
            Switch(enabled, a.toggle)
        }
        Text(
            "Gambar dari penyimpanan HP yang ikut terekam. Posisi awal di tengah atas layar. " +
                "Geser untuk memindahkan, cubit dua jari untuk memperbesar atau memperkecil, " +
                "putar dua jari untuk memutar 360 derajat. Ketuk gambar untuk menampilkan tombol kunci. " +
                "Seret ke ikon tempat sampah untuk membuangnya (hanya saat belum dikunci).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = a.pick, modifier = Modifier.fillMaxWidth()) {
            Text(if (hasImage) "Ganti gambar" else "Pilih gambar")
        }
        if (hasImage) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Kunci posisi", style = MaterialTheme.typography.bodyLarge)
                Switch(locked, a.lock, enabled = enabled)
            }
            Text(
                "Saat dikunci, watermark tidak bisa digeser, diubah ukurannya, diputar, atau dibuang. " +
                    "Tampilannya tetap jelas (tidak jadi transparan). Hanya area gambar yang menahan sentuhan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Geser vertikal saja", style = MaterialTheme.typography.bodyLarge)
                Switch(vertical, { v ->
                    WmPrefs.setVertical(ctx, v)
                    WatermarkState.vertical.value = v
                })
            }
            Text(
                "Mati: watermark bebas digeser ke mana saja. Aktif: watermark selalu di tengah layar " +
                    "(kiri-kanan) dan hanya bisa digeser naik-turun dari paling atas sampai paling bawah layar (posisi awal tetap di tengah). " +
                    "Cubitan hanya mengubah ukuran (tidak bisa diputar), posisi tengahnya tidak bergeser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Transparansi", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${((1f - wmAlpha) * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = wmAlpha,
                onValueChange = { WatermarkState.alpha.value = it },
                onValueChangeFinished = { WmPrefs.setAlpha(ctx, WatermarkState.alpha.value) },
                valueRange = 0.1f..1f,
            )
            Text(
                "Geser ke kanan = makin jelas, ke kiri = makin transparan. Pengaturan ini terpisah dari kunci posisi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = a.reset, modifier = Modifier.fillMaxWidth()) {
                Text("Kembalikan posisi awal")
            }
        }
    }
}

private fun summary(s: RecSettings): String {
    val res = if (s.shortSide == 0) "Resolusi asli" else "${s.shortSide}p"
    val codec = if (s.codec == 265) "H.265" else "H.264"
    val audio = if (!s.audioOn) "tanpa audio" else when (s.audioSource) {
        SRC_RAW -> "audio Raw"
        SRC_PERF -> "audio Performa"
        SRC_INTERNAL -> "audio Internal"
        SRC_INTERNAL_MIC -> "audio Internal + MIC"
        else -> "audio MIC"
    }
    return "$res, ${s.fps} fps, $codec, $audio"
}

// ---------------------------------------------------------------- Tab Video

/** Preset video: satu ketukan mengatur resolusi, frame rate, bitrate, dan codec. */
private data class VideoPreset(
    val label: String,
    val side: Int,
    val fps: Int,
    val mbps: Int,
    val codec: Int,
    val note: String,
)

private val VIDEO_PRESETS = listOf(
    VideoPreset("Hemat", 720, 30, 4, 264, "Hemat: 720p, 30 fps, 4 Mbps, H.264. Paling ringan untuk game dan ukuran file kecil."),
    VideoPreset("Standar", 720, 30, 8, 264, "Standar: 720p, 30 fps, 8 Mbps, H.264. Pengaturan awal aplikasi."),
    VideoPreset("Halus", 720, 60, 12, 264, "Halus: 720p, 60 fps, 12 Mbps, H.264. Gerakan lebih mulus untuk game cepat."),
    VideoPreset("Kualitas", 1080, 60, 16, 264, "Kualitas: 1080p, 60 fps, 16 Mbps, H.264. Gambar paling tajam, file lebih besar."),
)

private fun presetIndex(s: RecSettings): Int = VIDEO_PRESETS.indexOfFirst {
    it.side == s.shortSide && it.fps == s.fps && it.mbps == s.bitrateMbps && it.codec == s.codec
}

@Composable
private fun VideoTab(s: RecSettings, recording: Boolean, update: (RecSettings) -> Unit) {
    SectionCard {
        SectionTitle("Video")
        val preset = presetIndex(s)
        PillGrid(
            "Preset",
            VIDEO_PRESETS.mapIndexed { i, p -> p.label to i },
            preset, !recording,
        ) { i ->
            val p = VIDEO_PRESETS[i]
            update(s.copy(shortSide = p.side, fps = p.fps, bitrateMbps = p.mbps, codec = p.codec))
        }
        Text(
            if (preset >= 0) VIDEO_PRESETS[preset].note
            else "Kustom: pengaturan video diubah manual. Pilih preset untuk mengembalikan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Option(
            "Resolusi", listOf("480p" to 480, "720p" to 720, "1080p" to 1080, "Asli" to 0),
            s.shortSide, !recording,
        ) { update(s.copy(shortSide = it)) }
        Option(
            "Frame rate",
            listOf("30 fps" to 30, "60 fps" to 60, "90 fps" to 90, "120 fps" to 120),
            s.fps, !recording,
        ) { update(s.copy(fps = it)) }
        Option(
            "Bitrate",
            listOf(
                "4 Mbps" to 4, "8 Mbps" to 8, "12 Mbps" to 12, "16 Mbps" to 16,
                "24 Mbps" to 24, "32 Mbps" to 32, "50 Mbps" to 50, "80 Mbps" to 80,
            ),
            s.bitrateMbps, !recording,
        ) { update(s.copy(bitrateMbps = it)) }
        Option(
            "Codec video", listOf("H.264" to 264, "H.265" to 265),
            s.codec, !recording,
        ) { update(s.copy(codec = it)) }
    }
}

// ---------------------------------------------------------------- Tab Audio

@Composable
private fun AudioTab(s: RecSettings, recording: Boolean, update: (RecSettings) -> Unit) {
    var qualityOpen by rememberSaveable { mutableStateOf(false) }

    // Kartu 1: sumber dan volume
    SectionCard {
        SectionTitle("Sumber dan volume")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Rekam audio", style = MaterialTheme.typography.bodyLarge)
            Switch(s.audioOn, { update(s.copy(audioOn = it)) }, enabled = !recording)
        }
        if (s.audioOn) {
            StackedOption(
                "Sumber suara",
                listOf(
                    "MIC" to SRC_MIC,
                    "Raw (tanpa terproses)" to SRC_RAW,
                    "Performa Suara" to SRC_PERF,
                    "Suara Internal" to SRC_INTERNAL,
                    "Internal + MIC" to SRC_INTERNAL_MIC,
                ),
                s.audioSource, !recording,
            ) { update(s.copy(audioSource = it)) }
            Text(
                when (s.audioSource) {
                    SRC_RAW -> "Raw: mikrofon tanpa terproses (tanpa peredam noise dan penguat otomatis)."
                    SRC_PERF -> "Performa Suara: mikrofon dengan latensi rendah."
                    SRC_INTERNAL -> "Suara Internal: merekam suara dari HP (game/media), bukan mikrofon. " +
                        "Sebagian aplikasi memblokirnya, dan video disatukan saat berhenti."
                    SRC_INTERNAL_MIC -> "Internal + MIC: suara game dan mikrofon dicampur. " +
                        "Pakai earphone agar suara game tidak terekam dua kali lewat mikrofon."
                    else -> "MIC: mikrofon standar."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.audioSource == SRC_INTERNAL_MIC) {
                VolumeSlider("Volume suara game", s.gameVolume, !recording) {
                    update(s.copy(gameVolume = it))
                }
                VolumeSlider("Volume mikrofon", s.micVolume, !recording) {
                    update(s.copy(micVolume = it))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Mode streamer", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        s.streamerMode,
                        { update(s.copy(streamerMode = it)) },
                        enabled = !recording,
                    )
                }
                Text(
                    "Turunkan volume suara game saat Anda sedang berbicara.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (!s.audioOn) return

    // Kartu 2: efek suara (tidak relevan untuk Suara Internal saja)
    if (s.audioSource != SRC_INTERNAL) {
        Spacer(Modifier.height(16.dp))
        SectionCard {
            SectionTitle("Efek suara")
            PillGrid(
                "Pengubah suara",
                listOf(
                    "Original" to VOICE_ORIGINAL, "Bayi" to VOICE_BABY, "Wanita" to VOICE_WOMAN,
                    "Paman" to VOICE_UNCLE, "Robot" to VOICE_ROBOT, "Orang Tua" to VOICE_OLD,
                    "Monster" to VOICE_MONSTER,
                ),
                s.voiceMode, !recording,
            ) { update(s.copy(voiceMode = it)) }
            Text(
                "Efek diterapkan pada suara mikrofon. Selain Original, video disatukan saat berhenti " +
                    "(ada jeda singkat untuk menyimpan).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.audioSource == SRC_INTERNAL_MIC) {
                Option(
                    "Filter suara mikrofon",
                    listOf("Mati" to 0, "Ringan" to 1, "Kuat" to 2),
                    s.filterLevel, !recording,
                ) { update(s.copy(filterLevel = it)) }
                Text(
                    when (s.filterLevel) {
                        1 -> "Ringan: peredam noise bawaan HP dan pembuang dengung rendah."
                        2 -> "Kuat: Ringan ditambah noise gate, peredam gema bawaan HP, dan pembatas suara pecah. " +
                            "Efek bawaan HP tidak tersedia di semua HP."
                        else -> "Mati: suara mikrofon apa adanya."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Kartu 3: kualitas audio (bisa dilipat)
    Spacer(Modifier.height(16.dp))
    SectionCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { qualityOpen = !qualityOpen },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionTitle("Kualitas audio")
            Text(
                if (qualityOpen) "Sembunyikan" else "Tampilkan",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (qualityOpen) {
            Option(
                "Tingkat sampel audio",
                listOf("44100 Hz" to 44100, "48000 Hz" to 48000),
                s.sampleRate, !recording,
            ) { update(s.copy(sampleRate = it)) }
            Option(
                "Saluran audio", listOf("Mono" to 1, "Stereo" to 2),
                s.channels, !recording,
            ) { update(s.copy(channels = it)) }
            Option(
                "Enkoder audio",
                listOf("AAC" to 0, "AAC_ELD" to 1, "HE-AAC" to 2),
                s.audioCodec, !recording,
            ) { update(s.copy(audioCodec = it)) }
            Option(
                "Bit rata-rata audio",
                listOf("64 Kbps" to 64, "128 Kbps" to 128, "256 Kbps" to 256, "320 Kbps" to 320),
                s.audioBitrate, !recording,
            ) { update(s.copy(audioBitrate = it)) }
            Text(
                "Otomatis diturunkan jika melebihi batas encoder " +
                    "(misalnya mono tidak mencapai 320 Kbps).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- Tab Pengaturan

@Composable
private fun SettingsTab(
    s: RecSettings,
    recording: Boolean,
    update: (RecSettings) -> Unit,
    onReset: () -> Unit,
) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "-"
        } catch (e: Exception) {
            "-"
        }
    }
    SectionCard {
        SectionTitle("Mode trigger")
        Text(
            "Saat merekam, geser dari tepi layar untuk memunculkan tombol hijau. " +
                "Ketuk tombol hijau untuk menyimpan rekaman. " +
                "Aktif jika Tombol rekam melayang dinyalakan (tab Rekam).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StackedOption(
            "Mode",
            listOf(
                "Klik Ikon" to TRIG_CLICK,
                "Geser dari Sisi" to TRIG_SIDE,
                "Geser Setengah Atas" to TRIG_HALF,
                "Geser Dapat Diatur" to TRIG_ADJ,
            ),
            s.triggerMode, !recording,
        ) { m ->
            val sideOk = m == TRIG_ADJ || (s.triggerSide != SIDE_TOP && s.triggerSide != SIDE_BOTTOM)
            update(s.copy(triggerMode = m, triggerSide = if (sideOk) s.triggerSide else SIDE_RIGHT))
        }
        Text(
            when (s.triggerMode) {
                TRIG_SIDE -> "Geser dari Sisi: geser dari tepi layar (sepanjang sisi)."
                TRIG_HALF -> "Geser Setengah Atas: geser dari tepi layar bagian atas (setengah tinggi layar)."
                TRIG_ADJ -> "Geser Dapat Diatur: sisi dan area cakupan bisa diatur."
                else -> "Klik Ikon: hanya tombol rekam melayang. Berhenti lewat tombol di notifikasi."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.triggerMode != TRIG_CLICK) {
            StackedOption(
                "Sisi trigger",
                if (s.triggerMode == TRIG_ADJ) {
                    listOf(
                        "Kanan" to SIDE_RIGHT, "Kiri" to SIDE_LEFT, "Atas" to SIDE_TOP,
                        "Bawah" to SIDE_BOTTOM, "Kedua Sisi" to SIDE_BOTH,
                    )
                } else {
                    listOf("Kanan" to SIDE_RIGHT, "Kiri" to SIDE_LEFT, "Kedua Sisi" to SIDE_BOTH)
                },
                s.triggerSide, !recording,
            ) { update(s.copy(triggerSide = it)) }
            if (s.triggerMode == TRIG_ADJ) {
                Text(
                    "Area cakupan: ${s.covStart}% sampai ${s.covEnd}% dari panjang sisi layar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(ctx, CoverageActivity::class.java)) },
                    enabled = !recording,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Atur area cakupan")
                }
            }
            StackedOption(
                "Sensitivitas geser",
                listOf(
                    "Paling Tidak Sensitif" to 0, "Kurang Sensitif" to 1,
                    "Bawaan" to 2, "Lebih Sensitif" to 3,
                ),
                s.triggerSens, !recording,
            ) { update(s.copy(triggerSens = it)) }
            Text(
                "Naikkan sensitivitas jika tombol hijau sulit muncul. Turunkan jika area geser " +
                    "mengganggu keyboard atau gerakan lain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    SectionCard(spacing = 8.dp) {
        SectionTitle("Tentang")
        Text("Nex Recorder", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Versi $version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Hasil rekaman disimpan di Movies/NexRecorder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
    SectionCard(spacing = 12.dp) {
        SectionTitle("Pengaturan awal")
        Text(
            "Kembalikan resolusi, frame rate, bitrate, codec, dan semua pengaturan audio ke nilai awal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onReset, enabled = !recording, modifier = Modifier.fillMaxWidth()) {
            Text("Kembalikan pengaturan awal")
        }
    }
}

// ---------------------------------------------------------------- Komponen bersama

@Composable
private fun SectionCard(spacing: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** Ikon tab sederhana yang digambar sendiri (tanpa library ikon, supaya APK tetap kecil). */
@Composable
private fun TabIcon(tab: Tab) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        when (tab) {
            Tab.REKAM -> {
                drawCircle(color, radius = w * 0.42f, style = Stroke(sw))
                drawCircle(color, radius = w * 0.2f)
            }
            Tab.MEDIA -> {
                val ys = floatArrayOf(0.28f, 0.5f, 0.72f)
                for (y in ys) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(w * 0.08f, h * (y - 0.07f)),
                        size = Size(w * 0.16f, h * 0.14f),
                        cornerRadius = CornerRadius(w * 0.03f),
                    )
                    drawLine(
                        color = color,
                        start = Offset(w * 0.34f, h * y),
                        end = Offset(w * 0.92f, h * y),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                }
            }
            Tab.VIDEO -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.08f, h * 0.2f),
                    size = Size(w * 0.84f, h * 0.6f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(sw),
                )
                val play = Path().apply {
                    moveTo(w * 0.42f, h * 0.36f)
                    lineTo(w * 0.42f, h * 0.64f)
                    lineTo(w * 0.67f, h * 0.5f)
                    close()
                }
                drawPath(play, color)
            }
            Tab.AUDIO -> {
                val xs = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)
                val hs = floatArrayOf(0.35f, 0.8f, 0.55f, 0.3f)
                for (i in xs.indices) {
                    drawLine(
                        color = color,
                        start = Offset(w * xs[i], h * (0.5f - hs[i] / 2f)),
                        end = Offset(w * xs[i], h * (0.5f + hs[i] / 2f)),
                        strokeWidth = sw * 1.3f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            Tab.PENGATURAN -> {
                val ys = floatArrayOf(0.25f, 0.5f, 0.75f)
                val knob = floatArrayOf(0.65f, 0.35f, 0.6f)
                for (i in ys.indices) {
                    drawLine(
                        color = color,
                        start = Offset(w * 0.1f, h * ys[i]),
                        end = Offset(w * 0.9f, h * ys[i]),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(color, radius = w * 0.11f, center = Offset(w * knob[i], h * ys[i]))
                }
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val size by animateDpAsState(if (recording) 44.dp else 96.dp, label = "size")
    val corner by animateDpAsState(if (recording) 12.dp else 48.dp, label = "corner")
    Box(
        Modifier
            .size(136.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(Red),
        )
    }
}

@Composable
private fun <T> StackedOption(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (label, value) ->
                val isSelected = value == selected
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .alpha(if (enabled) 1f else 0.5f)
                        .clip(shape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent,
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(value) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> PillGrid(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.chunked(3).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowItems.forEach { (label, value) ->
                        val isSelected = value == selected
                        val shape = RoundedCornerShape(50)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .alpha(if (enabled) 1f else 0.5f)
                                .clip(shape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent,
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                                .selectable(
                                    selected = isSelected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(value) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("$value%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..200f,
            steps = 19,
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Option(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.chunked(4).forEach { rowItems ->
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    rowItems.forEachIndexed { i, (label, value) ->
                        SegmentedButton(
                            selected = value == selected,
                            onClick = { onSelect(value) },
                            shape = SegmentedButtonDefaults.itemShape(i, rowItems.size),
                            enabled = enabled,
                            icon = {},
                            label = {
                                Text(
                                    label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)
    else "%02d:%02d".format(t / 60, t % 60)
}
