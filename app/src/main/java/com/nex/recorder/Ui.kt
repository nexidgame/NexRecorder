package com.nex.recorder

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Tema biru modern

private val BlueTop = Color(0xFF5B9BFF)
private val BlueBottom = Color(0xFF1D4ED8)
private val BlueBrush = Brush.verticalGradient(listOf(BlueTop, BlueBottom))

private val NexLight = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9DBFF),
    onPrimaryContainer = Color(0xFF0B2A78),
    secondary = Color(0xFF3B6FD8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E1FF),
    onSecondaryContainer = Color(0xFF0B2A78),
    tertiary = Color(0xFF0EA5E9),
    background = Color(0xFFEEF3FF),
    onBackground = Color(0xFF0F1B3D),
    surface = Color(0xFFEEF3FF),
    onSurface = Color(0xFF0F1B3D),
    surfaceVariant = Color(0xFFDDE7FB),
    onSurfaceVariant = Color(0xFF50608A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F9FF),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE2EAFC),
    surfaceContainerHighest = Color(0xFFD5E0F9),
    outline = Color(0xFF9AAAD0),
    outlineVariant = Color(0xFFD3DDF3),
)

private val NexDark = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E4BB8),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF6FA0FF),
    onSecondary = Color(0xFF04153D),
    secondaryContainer = Color(0xFF24407F),
    onSecondaryContainer = Color(0xFFD8E5FF),
    tertiary = Color(0xFF38BDF8),
    background = Color(0xFF0E1527),
    onBackground = Color(0xFFE8EEFF),
    surface = Color(0xFF0E1527),
    onSurface = Color(0xFFE8EEFF),
    surfaceVariant = Color(0xFF22304F),
    onSurfaceVariant = Color(0xFFA3B3D6),
    surfaceContainerLowest = Color(0xFF0A1020),
    surfaceContainerLow = Color(0xFF131C33),
    surfaceContainer = Color(0xFF18233F),
    surfaceContainerHigh = Color(0xFF223055),
    surfaceContainerHighest = Color(0xFF2B3B64),
    outline = Color(0xFF5A6C99),
    outlineVariant = Color(0xFF2E3E66),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) NexDark else NexLight,
        content = content,
    )
}

// ---------------------------------------------------------------- Ikon (digambar sendiri)

internal enum class NexIcon { REC, MEDIA, VIDEO, AUDIO, SETTINGS, FLOAT, CAM, IMAGE, CHEVRON }

@Composable
internal fun NexIconView(icon: NexIcon, color: Color, iconSize: Dp = 24.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(w * x, h * y)
        fun sz(a: Float, b: Float) = Size(w * a, h * b)
        when (icon) {
            NexIcon.REC -> {
                drawCircle(color, w * 0.40f, style = st)
                drawCircle(color, w * 0.17f)
            }
            NexIcon.MEDIA -> {
                drawRoundRect(color, o(0.12f, 0.18f), sz(0.76f, 0.64f), CornerRadius(w * 0.14f), style = st)
                val p = Path().apply {
                    moveTo(w * 0.43f, h * 0.36f)
                    lineTo(w * 0.43f, h * 0.64f)
                    lineTo(w * 0.66f, h * 0.50f)
                    close()
                }
                drawPath(p, color)
            }
            NexIcon.VIDEO -> {
                drawRoundRect(color, o(0.10f, 0.26f), sz(0.54f, 0.48f), CornerRadius(w * 0.12f), style = st)
                val p = Path().apply {
                    moveTo(w * 0.70f, h * 0.46f)
                    lineTo(w * 0.90f, h * 0.32f)
                    lineTo(w * 0.90f, h * 0.68f)
                    lineTo(w * 0.70f, h * 0.54f)
                    close()
                }
                drawPath(p, color)
            }
            NexIcon.AUDIO -> {
                drawRoundRect(color, o(0.38f, 0.10f), sz(0.24f, 0.44f), CornerRadius(w * 0.12f))
                drawArc(color, 0f, 180f, false, o(0.24f, 0.30f), sz(0.52f, 0.40f), style = st)
                drawLine(color, o(0.50f, 0.70f), o(0.50f, 0.88f), sw, StrokeCap.Round)
                drawLine(color, o(0.36f, 0.88f), o(0.64f, 0.88f), sw, StrokeCap.Round)
            }
            NexIcon.SETTINGS -> {
                val ys = floatArrayOf(0.25f, 0.5f, 0.75f)
                val knob = floatArrayOf(0.66f, 0.34f, 0.6f)
                for (i in ys.indices) {
                    drawLine(color, o(0.10f, ys[i]), o(0.90f, ys[i]), sw, StrokeCap.Round)
                    drawCircle(color, w * 0.12f, o(knob[i], ys[i]))
                }
            }
            NexIcon.FLOAT -> {
                drawRoundRect(color, o(0.12f, 0.16f), sz(0.76f, 0.68f), CornerRadius(w * 0.14f), style = st)
                drawCircle(color, w * 0.13f, o(0.62f, 0.58f))
            }
            NexIcon.CAM -> {
                drawCircle(color, w * 0.15f, o(0.50f, 0.36f))
                drawArc(color, 180f, 180f, false, o(0.24f, 0.56f), sz(0.52f, 0.52f), style = st)
            }
            NexIcon.IMAGE -> {
                drawRoundRect(color, o(0.12f, 0.18f), sz(0.76f, 0.64f), CornerRadius(w * 0.12f), style = st)
                drawCircle(color, w * 0.07f, o(0.34f, 0.38f))
                val p = Path().apply {
                    moveTo(w * 0.16f, h * 0.74f)
                    lineTo(w * 0.42f, h * 0.50f)
                    lineTo(w * 0.58f, h * 0.64f)
                    lineTo(w * 0.68f, h * 0.56f)
                    lineTo(w * 0.84f, h * 0.74f)
                }
                drawPath(p, color, style = st)
            }
            NexIcon.CHEVRON -> {
                val p = Path().apply {
                    moveTo(w * 0.30f, h * 0.40f)
                    lineTo(w * 0.50f, h * 0.60f)
                    lineTo(w * 0.70f, h * 0.40f)
                }
                drawPath(p, color, style = st)
            }
        }
    }
}

// ---------------------------------------------------------------- Layar utama

private enum class Tab(val label: String, val icon: NexIcon) {
    REKAM("Rekam", NexIcon.REC),
    MEDIA("Media", NexIcon.MEDIA),
    VIDEO("Video", NexIcon.VIDEO),
    AUDIO("Audio", NexIcon.AUDIO),
    PENGATURAN("Pengaturan", NexIcon.SETTINGS),
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
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { NexIconView(t.icon, LocalContentColor.current) },
                        label = {
                            Text(
                                t.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            when (Tab.entries[tab]) {
                Tab.REKAM -> RecordTab(
                    s, recording, startedAt, now, floating, onStart, onStop, onFloating, facecam, onFacecam, watermark,
                )
                Tab.MEDIA -> MediaTab()
                Tab.VIDEO -> Page { VideoTab(s, recording) { update(it) } }
                Tab.AUDIO -> Page { AudioTab(s, recording) { update(it) } }
                Tab.PENGATURAN -> Page { SettingsTab(s, recording, { update(it) }) { update(RecSettings()) } }
            }
        }
    }
}

/** Halaman satu layar. Gulir hanya bekerja jika layar HP sangat kecil dan isinya tidak muat. */
@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun TabHeader(icon: NexIcon, title: String, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BlueBrush),
            contentAlignment = Alignment.Center,
        ) { NexIconView(icon, Color.White, 22.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    val wmEnabled by WatermarkState.enabled.collectAsState()
    var wmOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TabHeader(NexIcon.REC, "Nex Recorder", "Perekam layar ringan")
        Spacer(Modifier.weight(1f))
        RecordButton(recording, if (recording) onStop else onStart)
        Spacer(Modifier.height(12.dp))
        if (recording) {
            Text(
                formatTime(now - startedAt),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "Ketuk untuk mulai merekam",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                summary(s),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FeatureTile(
                NexIcon.FLOAT, "Melayang", floating, { onFloating(!floating) },
                Modifier.weight(1f),
            )
            FeatureTile(
                NexIcon.CAM, "Facecam", facecam, { onFacecam(!facecam) },
                Modifier.weight(1f),
            )
            FeatureTile(
                NexIcon.IMAGE, "Watermark", wmEnabled, { watermark.toggle(!wmEnabled) },
                Modifier.weight(1f),
                onGear = { wmOpen = true },
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    if (wmOpen) WatermarkDialog(watermark) { wmOpen = false }
}

@Composable
private fun FeatureTile(
    icon: NexIcon,
    title: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onGear: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurface
    val sub = if (on) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .height(104.dp)
            .clip(shape)
            .then(
                if (on) Modifier.background(BlueBrush)
                else Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            NexIconView(icon, fg, 28.dp)
            if (onGear != null) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            if (on) Color.White.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .clickable(onClick = onGear),
                    contentAlignment = Alignment.Center,
                ) { NexIconView(NexIcon.SETTINGS, fg, 18.dp) }
            }
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (on) "Aktif" else "Mati",
                style = MaterialTheme.typography.labelMedium,
                color = sub,
            )
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val glyph by animateDpAsState(if (recording) 32.dp else 38.dp, label = "glyph")
    val corner by animateDpAsState(if (recording) 9.dp else 19.dp, label = "corner")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val haloAlpha by pulse.animateFloat(
        initialValue = 0.10f,
        targetValue = if (recording) 0.30f else 0.10f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "halo",
    )
    Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(184.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = haloAlpha)),
        )
        Box(
            Modifier
                .size(146.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        )
        Box(
            Modifier
                .size(110.dp)
                .shadow(12.dp, CircleShape)
                .clip(CircleShape)
                .background(BlueBrush)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(glyph)
                    .clip(RoundedCornerShape(corner))
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun WatermarkDialog(a: WatermarkActions, onDismiss: () -> Unit) {
    val enabled by WatermarkState.enabled.collectAsState()
    val locked by WatermarkState.locked.collectAsState()
    val hasImage by WatermarkState.hasImage.collectAsState()
    val vertical by WatermarkState.vertical.collectAsState()
    val wmAlpha by WatermarkState.alpha.collectAsState()
    val ctx = LocalContext.current
    NexDialog("Watermark", onDismiss) {
        Text(
            "Gambar ikut terekam. Geser untuk memindah, cubit dua jari untuk ukuran dan putaran, " +
                "seret ke tempat sampah untuk membuang.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NexButton(if (hasImage) "Ganti gambar" else "Pilih gambar", onClick = a.pick)
        if (hasImage) {
            SwitchRow("Kunci posisi", locked, enabled) { a.lock(it) }
            SwitchRow("Geser vertikal saja", vertical) { v ->
                WmPrefs.setVertical(ctx, v)
                WatermarkState.vertical.value = v
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Transparansi", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${((1f - wmAlpha) * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = wmAlpha,
                    onValueChange = { WatermarkState.alpha.value = it },
                    onValueChangeFinished = { WmPrefs.setAlpha(ctx, WatermarkState.alpha.value) },
                    valueRange = 0.1f..1f,
                )
            }
            NexButton("Kembalikan posisi awal", filled = false, onClick = a.reset)
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
    VideoPreset("Hemat", 720, 30, 4, 264, "720p, 30 fps, 4 Mbps. Paling ringan, file kecil."),
    VideoPreset("Standar", 720, 30, 8, 264, "720p, 30 fps, 8 Mbps. Pengaturan awal aplikasi."),
    VideoPreset("Halus", 720, 60, 12, 264, "720p, 60 fps, 12 Mbps. Gerakan mulus untuk game cepat."),
    VideoPreset("Kualitas", 1080, 60, 16, 264, "1080p, 60 fps, 16 Mbps. Paling tajam, file lebih besar."),
)

private fun presetIndex(s: RecSettings): Int = VIDEO_PRESETS.indexOfFirst {
    it.side == s.shortSide && it.fps == s.fps && it.mbps == s.bitrateMbps && it.codec == s.codec
}

@Composable
private fun VideoTab(s: RecSettings, recording: Boolean, update: (RecSettings) -> Unit) {
    TabHeader(NexIcon.VIDEO, "Video", "Kualitas dan format rekaman")
    NexCard {
        val preset = presetIndex(s)
        Text("Preset", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(VIDEO_PRESETS.mapIndexed { i, p -> p.label to i }, preset, !recording) { i ->
            val p = VIDEO_PRESETS[i]
            update(s.copy(shortSide = p.side, fps = p.fps, bitrateMbps = p.mbps, codec = p.codec))
        }
        Text(
            if (preset >= 0) VIDEO_PRESETS[preset].note else "Kustom: pilih preset untuk mengembalikan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
    NexCard(spacing = 4.dp) {
        SelectRow(
            "Resolusi", listOf("480p" to 480, "720p" to 720, "1080p" to 1080, "Asli" to 0),
            s.shortSide, !recording,
        ) { update(s.copy(shortSide = it)) }
        SelectRow(
            "Frame rate",
            listOf("30 fps" to 30, "60 fps" to 60, "90 fps" to 90, "120 fps" to 120),
            s.fps, !recording,
        ) { update(s.copy(fps = it)) }
        SelectRow(
            "Bitrate",
            listOf(
                "4 Mbps" to 4, "8 Mbps" to 8, "12 Mbps" to 12, "16 Mbps" to 16,
                "24 Mbps" to 24, "32 Mbps" to 32, "50 Mbps" to 50, "80 Mbps" to 80,
            ),
            s.bitrateMbps, !recording,
        ) { update(s.copy(bitrateMbps = it)) }
        SelectRow(
            "Codec video", listOf("H.264" to 264, "H.265" to 265),
            s.codec, !recording,
        ) { update(s.copy(codec = it)) }
    }
}

// ---------------------------------------------------------------- Tab Audio

@Composable
private fun AudioTab(s: RecSettings, recording: Boolean, update: (RecSettings) -> Unit) {
    var qualityOpen by rememberSaveable { mutableStateOf(false) }
    var mixerOpen by rememberSaveable { mutableStateOf(false) }

    TabHeader(NexIcon.AUDIO, "Audio", "Sumber dan kualitas suara")
    NexCard(spacing = 4.dp) {
        SwitchRow("Rekam audio", s.audioOn, !recording) { update(s.copy(audioOn = it)) }
        if (s.audioOn) {
            SelectRow(
                "Sumber suara",
                listOf(
                    "MIC" to SRC_MIC,
                    "Raw" to SRC_RAW,
                    "Performa Suara" to SRC_PERF,
                    "Suara Internal" to SRC_INTERNAL,
                    "Internal + MIC" to SRC_INTERNAL_MIC,
                ),
                s.audioSource, !recording,
            ) { update(s.copy(audioSource = it)) }
            Text(
                when (s.audioSource) {
                    SRC_RAW -> "Mikrofon tanpa peredam noise dan penguat otomatis."
                    SRC_PERF -> "Mikrofon dengan latensi rendah."
                    SRC_INTERNAL -> "Suara dari HP (game/media). Sebagian aplikasi memblokirnya."
                    SRC_INTERNAL_MIC -> "Suara game dan mikrofon dicampur. Pakai earphone agar tidak terekam dua kali."
                    else -> "Mikrofon standar."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            if (s.audioSource != SRC_INTERNAL) {
                SelectRow(
                    "Pengubah suara",
                    listOf(
                        "Original" to VOICE_ORIGINAL, "Bayi" to VOICE_BABY, "Wanita" to VOICE_WOMAN,
                        "Paman" to VOICE_UNCLE, "Robot" to VOICE_ROBOT, "Orang Tua" to VOICE_OLD,
                        "Monster" to VOICE_MONSTER,
                    ),
                    s.voiceMode, !recording,
                ) { update(s.copy(voiceMode = it)) }
            }
        }
    }
    if (s.audioOn) {
        NexCard(spacing = 4.dp) {
            if (s.audioSource == SRC_INTERNAL_MIC) {
                LinkRow("Mixer dan filter", "Game ${s.gameVolume}%, MIC ${s.micVolume}%") { mixerOpen = true }
            }
            LinkRow(
                "Kualitas audio",
                "${s.sampleRate} Hz, ${if (s.channels == 2) "Stereo" else "Mono"}, " +
                    "${listOf("AAC", "AAC_ELD", "HE-AAC").getOrElse(s.audioCodec) { "AAC" }}, ${s.audioBitrate} Kbps",
            ) { qualityOpen = true }
        }
    }

    if (mixerOpen) {
        NexDialog("Mixer dan filter", { mixerOpen = false }) {
            VolumeSlider("Volume suara game", s.gameVolume, !recording) { update(s.copy(gameVolume = it)) }
            VolumeSlider("Volume mikrofon", s.micVolume, !recording) { update(s.copy(micVolume = it)) }
            SwitchRow("Mode streamer", s.streamerMode, !recording) { update(s.copy(streamerMode = it)) }
            Text(
                "Mode streamer menurunkan volume game saat Anda berbicara.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectRow(
                "Filter mikrofon",
                listOf("Mati" to 0, "Ringan" to 1, "Kuat" to 2),
                s.filterLevel, !recording,
            ) { update(s.copy(filterLevel = it)) }
        }
    }
    if (qualityOpen) {
        NexDialog("Kualitas audio", { qualityOpen = false }) {
            SelectRow(
                "Sampel audio", listOf("44100 Hz" to 44100, "48000 Hz" to 48000),
                s.sampleRate, !recording,
            ) { update(s.copy(sampleRate = it)) }
            SelectRow(
                "Saluran", listOf("Mono" to 1, "Stereo" to 2),
                s.channels, !recording,
            ) { update(s.copy(channels = it)) }
            SelectRow(
                "Enkoder", listOf("AAC" to 0, "AAC_ELD" to 1, "HE-AAC" to 2),
                s.audioCodec, !recording,
            ) { update(s.copy(audioCodec = it)) }
            SelectRow(
                "Bit rata-rata",
                listOf("64 Kbps" to 64, "128 Kbps" to 128, "256 Kbps" to 256, "320 Kbps" to 320),
                s.audioBitrate, !recording,
            ) { update(s.copy(audioBitrate = it)) }
            Text(
                "Otomatis diturunkan jika melebihi batas encoder.",
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
    TabHeader(NexIcon.SETTINGS, "Pengaturan", "Mode trigger dan aplikasi")
    NexCard(spacing = 4.dp) {
        SelectRow(
            "Mode trigger",
            listOf(
                "Klik Ikon" to TRIG_CLICK,
                "Geser dari Sisi" to TRIG_SIDE,
                "Setengah Atas" to TRIG_HALF,
                "Dapat Diatur" to TRIG_ADJ,
            ),
            s.triggerMode, !recording,
        ) { m ->
            val sideOk = m == TRIG_ADJ || (s.triggerSide != SIDE_TOP && s.triggerSide != SIDE_BOTTOM)
            update(s.copy(triggerMode = m, triggerSide = if (sideOk) s.triggerSide else SIDE_RIGHT))
        }
        Text(
            when (s.triggerMode) {
                TRIG_SIDE -> "Geser dari tepi layar untuk memunculkan tombol biru, lalu ketuk untuk menyimpan."
                TRIG_HALF -> "Geser dari tepi atas (setengah tinggi layar) untuk memunculkan tombol biru."
                TRIG_ADJ -> "Sisi dan area cakupan geser bisa diatur sendiri."
                else -> "Hanya tombol rekam melayang. Berhenti lewat tombol di notifikasi."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        if (s.triggerMode != TRIG_CLICK) {
            SelectRow(
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
            SelectRow(
                "Sensitivitas",
                listOf("Paling rendah" to 0, "Rendah" to 1, "Bawaan" to 2, "Tinggi" to 3),
                s.triggerSens, !recording,
            ) { update(s.copy(triggerSens = it)) }
            if (s.triggerMode == TRIG_ADJ) {
                LinkRow("Area cakupan", "${s.covStart}% sampai ${s.covEnd}% dari sisi layar", enabled = !recording) {
                    ctx.startActivity(android.content.Intent(ctx, CoverageActivity::class.java))
                }
            }
        }
    }
    NexCard(spacing = 10.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Nex Recorder", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Hasil di Movies/NexRecorder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "v$version",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        NexButton("Kembalikan pengaturan awal", enabled = !recording, filled = false, onClick = onReset)
    }
}

// ---------------------------------------------------------------- Komponen bersama

@Composable
private fun NexCard(spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
private fun NexDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Selesai", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun NexButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    onClick: () -> Unit,
) {
    val m = modifier
        .fillMaxWidth()
        .height(46.dp)
    val shape = RoundedCornerShape(16.dp)
    if (filled) {
        Button(onClick = onClick, modifier = m, enabled = enabled, shape = shape) {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = m, enabled = enabled, shape = shape) {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun LinkRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { NexIconView(NexIcon.CHEVRON, MaterialTheme.colorScheme.primary, 18.dp) }
    }
}

@Composable
private fun NexChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<String, T>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            NexChip(label, value == selected, enabled, Modifier.weight(1f)) { onSelect(value) }
        }
    }
}

/** Satu baris pengaturan: judul di kiri, pilihan (menu tarik-turun) di kanan. */
@Composable
private fun <T> SelectRow(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.second == selected }?.first ?: "-"
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Row(
                Modifier
                    .alpha(if (enabled) 1f else 0.5f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = enabled) { open = true }
                    .height(40.dp)
                    .padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                NexIconView(NexIcon.CHEVRON, MaterialTheme.colorScheme.primary, 18.dp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (value == selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$value%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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

private fun formatTime(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)
    else "%02d:%02d".format(t / 60, t % 60)
}
