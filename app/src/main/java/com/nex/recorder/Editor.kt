package com.nex.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Pemutar + penyunting hasil rekaman. */
class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = intent.getStringArrayListExtra("uris") ?: arrayListOf()
        val names = intent.getStringArrayListExtra("names") ?: arrayListOf()
        val index = intent.getIntExtra("index", 0)
        if (uris.isEmpty()) {
            finish()
            return
        }
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    EditorScreen(uris, names, index) { finish() }
                }
            }
        }
    }
}

private enum class Feat(val label: String) {
    TRIM("Trim"),
    CUT("Cut"),
    MUTE("Mute"),
    COMPRESS("Kompres & Resolusi"),
    CROP("Crop & Rasio"),
    SENSOR("Sensor Area"),
    TEXT("Teks / Subtitle"),
    SPEED("Kecepatan"),
    ROTATE("Putar & Flip"),
    BGM("Musik Latar"),
    VOICE("Sulih Suara"),
    DOODLE("Coretan"),
    GIF("Video ke GIF"),
    AUDIO("Ekstrak Audio"),
    CANVAS("Bingkai Kanvas"),
    BRAND("Watermark"),
    SMART("Smart Trim"),
}

private val TOP_FEATS = listOf(Feat.TRIM, Feat.CUT, Feat.MUTE, Feat.COMPRESS, Feat.CROP)

private fun applied(f: Feat, s: EditState): Boolean = when (f) {
    Feat.TRIM -> s.trimStart > 0 || s.trimEnd >= 0
    Feat.CUT -> s.cuts.isNotEmpty()
    Feat.MUTE -> s.mute
    Feat.COMPRESS -> s.shortSide > 0 || s.bitrateMbps > 0
    Feat.CROP -> s.cropAspect > 0f || s.cropL > 0f || s.cropR > 0f || s.cropT > 0f || s.cropB > 0f
    Feat.SENSOR -> s.blocks.isNotEmpty()
    Feat.TEXT -> s.texts.isNotEmpty()
    Feat.SPEED -> s.speed != 1f
    Feat.ROTATE -> s.rotation != 0 || s.flipH || s.flipV
    Feat.BGM -> s.bgmUri != null
    Feat.VOICE -> s.voicePath != null
    Feat.DOODLE -> s.strokes.isNotEmpty()
    Feat.GIF -> false
    Feat.AUDIO -> false
    Feat.CANVAS -> s.canvasAspect > 0f
    Feat.BRAND -> s.wmBitmap != null
    Feat.SMART -> s.silent.isNotEmpty()
}

/** Perekam suara (sulih suara) ke berkas M4A di cache. */
private class VoiceRec(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null

    fun start(): Boolean {
        return try {
            val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r
            file = f
            true
        } catch (e: Exception) {
            cancel()
            false
        }
    }

    fun stop(): String? {
        val r = rec ?: return null
        rec = null
        return try {
            r.stop()
            r.release()
            file?.absolutePath
        } catch (e: Exception) {
            try { r.release() } catch (e2: Exception) { }
            null
        }
    }

    fun cancel() {
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
    }
}

private fun displayName(ctx: Context, u: Uri): String {
    ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "audio"
    }
    return u.lastPathSegment ?: "audio"
}

private fun decodeBitmap(ctx: Context, u: Uri): Bitmap? {
    return try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (o.outWidth / sample > 1024 || o.outHeight / sample > 1024) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o2) }
    } catch (e: Exception) {
        null
    }
}

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

@Composable
private fun EditorScreen(uris: List<String>, names: List<String>, startIndex: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, uris.size - 1)) }
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var meta by remember { mutableStateOf<Meta?>(null) }
    var st by remember { mutableStateOf(EditState()) }
    var editMode by remember { mutableStateOf(false) }
    var feat by remember { mutableStateOf<Feat?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var prog by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recStart by remember { mutableLongStateOf(0L) }
    var found by remember { mutableStateOf<List<LongRange>?>(null) }
    var dColor by remember { mutableIntStateOf(0xFFF44336.toInt()) }
    var dWidth by remember { mutableFloatStateOf(0.012f) }
    val voice = remember { VoiceRec(ctx) }
    val view = LocalView.current

    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(Unit) { onDispose { voice.cancel() } }
    DisposableEffect(busy != null) {
        view.keepScreenOn = busy != null
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) playing = false
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(index) {
        meta = null
        st = EditState()
        feat = null
        found = null
        pos = 0L
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uris[index])))
        player.prepare()
        player.playWhenReady = false
        meta = withContext(Dispatchers.IO) {
            try { EditEngine.readMeta(ctx, Uri.parse(uris[index])) } catch (e: Exception) { null }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            delay(200)
        }
    }

    fun runJob(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        prog = 0
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(ctx, "Gagal: ${(e.message ?: e.javaClass.simpleName).take(160)}")
            } finally {
                busy = null
                job = null
            }
        }
    }

    fun stopRec() {
        val f = voice.stop()
        recording = false
        player.pause()
        if (f != null) st = st.copy(voicePath = f, voiceStartMs = recStart)
    }

    fun startRec() {
        if (voice.start()) {
            recStart = player.currentPosition
            player.play()
            recording = true
        } else {
            toast(ctx, "Tidak bisa merekam suara")
        }
    }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRec() else toast(ctx, "Izin mikrofon ditolak")
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) st = st.copy(bgmUri = u, bgmName = displayName(ctx, u))
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            scope.launch {
                val b = withContext(Dispatchers.IO) { decodeBitmap(ctx, u) }
                if (b != null) st = st.copy(wmBitmap = b) else toast(ctx, "Gambar tidak bisa dibuka")
            }
        }
    }

    val m = meta
    val src = Uri.parse(uris[index])

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Tutup") }
            Text(
                names.getOrElse(index) { "" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (m == null || m.w <= 0 || m.h <= 0) {
            Spacer(Modifier.height(40.dp))
            Text("Memuat video...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val activeFeat = if (editMode && busy == null) feat else null
            val keep = EditEngine.keepRanges(st, m.durMs)
            PreviewBox(player, m, st, activeFeat, EditEngine.outTimeMs(keep, pos, st.speed), dColor, dWidth) { st = it }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = if (m.durMs > 0) (pos.toFloat() / m.durMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { v ->
                    val t = (v * m.durMs).toLong()
                    pos = t
                    player.seekTo(t)
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtDuration(pos), style = MaterialTheme.typography.bodySmall)
                Text(fmtDuration(m.durMs), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editMode) {
                    Button(onClick = { editMode = false }) { Text("Edit") }
                } else {
                    OutlinedButton(onClick = { editMode = true }) { Text("Edit") }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { if (index > 0) { if (recording) stopRec(); index-- } }, enabled = index > 0) {
                    ControlIcon(2, if (index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                }) {
                    ControlIcon(if (playing) 1 else 0, MaterialTheme.colorScheme.onSurface)
                }
                IconButton(
                    onClick = { if (index < uris.size - 1) { if (recording) stopRec(); index++ } },
                    enabled = index < uris.size - 1,
                ) {
                    ControlIcon(3, if (index < uris.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                }
            }

            if (editMode) {
                Spacer(Modifier.height(12.dp))
                val b = busy
                if (b != null) {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("$b ($prog%)", style = MaterialTheme.typography.bodyLarge)
                            LinearProgressIndicator(progress = { prog / 100f }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { job?.cancel(); toast(ctx, "Dibatalkan") }) { Text("Batalkan") }
                        }
                    }
                } else {
                    FeatureMenu(feat, st) { feat = if (feat == it) null else it }
                    Spacer(Modifier.height(12.dp))
                    val f = feat
                    if (f != null) {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(f.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                when (f) {
                                    Feat.TRIM -> TrimPanel(m, st, pos) { st = it }
                                    Feat.CUT -> CutPanel(m, st, pos) { st = it }
                                    Feat.MUTE -> MutePanel(m, st) { st = it }
                                    Feat.COMPRESS -> CompressPanel(st) { st = it }
                                    Feat.CROP -> CropPanel(st) { st = it }
                                    Feat.SENSOR -> SensorPanel(st) { st = it }
                                    Feat.TEXT -> TextPanel(m, st) { st = it }
                                    Feat.SPEED -> SpeedPanel(st) { st = it }
                                    Feat.ROTATE -> RotatePanel(st) { st = it }
                                    Feat.BGM -> BgmPanel(st, { pickAudio.launch("audio/*") }) { st = it }
                                    Feat.VOICE -> VoicePanel(st, recording, {
                                        if (recording) {
                                            stopRec()
                                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                            PackageManager.PERMISSION_GRANTED
                                        ) {
                                            startRec()
                                        } else {
                                            micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }) { st = it }
                                    Feat.DOODLE -> DoodlePanel(st, dColor, dWidth, { dColor = it }, { dWidth = it }) { st = it }
                                    Feat.GIF -> GifPanel { widthPx, fps ->
                                        val end = if (st.trimEnd < 0) m.durMs else st.trimEnd
                                        runJob("Membuat GIF") {
                                            EditEngine.makeGif(ctx, src, m, st.trimStart, end, widthPx, fps) { prog = it }
                                            toast(ctx, "GIF tersimpan di Pictures/NexRecorder")
                                        }
                                    }
                                    Feat.AUDIO -> AudioPanel(m) {
                                        val end = if (st.trimEnd < 0) -1L else st.trimEnd
                                        runJob("Mengekstrak audio") {
                                            EditEngine.extractAudio(ctx, src, st.trimStart, end)
                                            toast(ctx, "Audio tersimpan di Music/NexRecorder")
                                        }
                                    }
                                    Feat.CANVAS -> CanvasPanel(st) { st = it }
                                    Feat.BRAND -> BrandPanel(st, { pickImage.launch("image/*") }) { st = it }
                                    Feat.SMART -> SmartPanel(m, st, found, { level ->
                                        val th = floatArrayOf(-50f, -40f, -32f)[level]
                                        runJob("Menganalisis keheningan") {
                                            val r = EditEngine.analyzeSilence(ctx, src, th, 700L) { prog = it }
                                            if (r == null) {
                                                toast(ctx, "Video ini tidak punya suara")
                                            } else {
                                                found = r
                                            }
                                        }
                                    }, { found = it }) { st = it }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (recording) stopRec()
                                player.pause()
                                runJob("Mengekspor video") {
                                    EditEngine.exportVideo(ctx, src, m, st) { prog = it }
                                    toast(ctx, "Tersimpan di Movies/NexRecorder")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Simpan hasil") }
                        OutlinedButton(onClick = { st = EditState(); found = null }, modifier = Modifier.weight(1f)) {
                            Text("Reset")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Simpan hasil menerapkan semua suntingan menjadi video baru. Video asli tidak diubah.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------- pratinjau

@Composable
private fun PreviewBox(
    player: ExoPlayer,
    m: Meta,
    st: EditState,
    feat: Feat?,
    outMs: Long,
    dColor: Int,
    dWidth: Float,
    onChange: (EditState) -> Unit,
) {
    val stNow by rememberUpdatedState(st)
    val onNow by rememberUpdatedState(onChange)
    val colorNow by rememberUpdatedState(dColor)
    val widthNow by rememberUpdatedState(dWidth)
    var dragA by remember { mutableStateOf<Offset?>(null) }
    var dragB by remember { mutableStateOf<Offset?>(null) }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val r = m.w.toFloat() / m.h
        val maxH = 340.dp
        val boxW = if (maxWidth.value / maxH.value > r) maxH * r else maxWidth
        val boxH = if (maxWidth.value / maxH.value > r) maxH else maxWidth / r
        Box(Modifier.size(boxW, boxH).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            AndroidView(
                factory = { c -> TextureView(c).also { tv -> player.setVideoTextureView(tv) } },
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width.toInt()
                val h = size.height.toInt()
                if (w > 0 && h > 0) {
                    drawIntoCanvas { cv ->
                        drawStage1(cv.nativeCanvas, w, h, st)
                        drawStage2(cv.nativeCanvas, w, h, st, outMs)
                    }
                }
                val a = dragA
                val b = dragB
                if (a != null && b != null) {
                    drawRect(
                        Color.Red,
                        topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                        size = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)),
                        style = Stroke(3f),
                    )
                }
                if (live.size > 1) {
                    val p = Path()
                    p.moveTo(live[0].x, live[0].y)
                    for (k in 1 until live.size) p.lineTo(live[k].x, live[k].y)
                    drawPath(
                        p, Color(colorNow),
                        style = Stroke(
                            kotlin.math.max(2f, widthNow * min(size.width, size.height)),
                            cap = StrokeCap.Round, join = StrokeJoin.Round,
                        ),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(feat) {
                        if (feat == Feat.SENSOR) {
                            detectDragGestures(
                                onDragStart = { o -> dragA = o; dragB = o },
                                onDrag = { ch, _ -> ch.consume(); dragB = ch.position },
                                onDragEnd = {
                                    val a = dragA
                                    val b = dragB
                                    dragA = null
                                    dragB = null
                                    if (a != null && b != null && size.width > 0 && size.height > 0) {
                                        val l = (min(a.x, b.x) / size.width).coerceIn(0f, 1f)
                                        val rr = (kotlin.math.max(a.x, b.x) / size.width).coerceIn(0f, 1f)
                                        val t = (min(a.y, b.y) / size.height).coerceIn(0f, 1f)
                                        val bb = (kotlin.math.max(a.y, b.y) / size.height).coerceIn(0f, 1f)
                                        if (rr - l > 0.02f && bb - t > 0.02f) {
                                            onNow(stNow.copy(blocks = stNow.blocks + Blk(l, t, rr, bb)))
                                        }
                                    }
                                },
                                onDragCancel = { dragA = null; dragB = null },
                            )
                        } else if (feat == Feat.DOODLE) {
                            detectDragGestures(
                                onDragStart = { o -> live = listOf(o) },
                                onDrag = { ch, _ -> ch.consume(); live = live + ch.position },
                                onDragEnd = {
                                    val pts = live
                                    live = emptyList()
                                    if (pts.isNotEmpty() && size.width > 0 && size.height > 0) {
                                        val s = DoodleStroke(
                                            colorNow, widthNow,
                                            pts.map { Pt(it.x / size.width, it.y / size.height) },
                                        )
                                        onNow(stNow.copy(strokes = stNow.strokes + s))
                                    }
                                },
                                onDragCancel = { live = emptyList() },
                            )
                        }
                    },
            )
        }
    }
}

@Composable
private fun ControlIcon(kind: Int, color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        when (kind) {
            0 -> {
                val p = Path().apply {
                    moveTo(w * 0.25f, h * 0.15f)
                    lineTo(w * 0.25f, h * 0.85f)
                    lineTo(w * 0.85f, h * 0.5f)
                    close()
                }
                drawPath(p, color)
            }
            1 -> {
                drawRect(color, Offset(w * 0.2f, h * 0.15f), Size(w * 0.2f, h * 0.7f))
                drawRect(color, Offset(w * 0.6f, h * 0.15f), Size(w * 0.2f, h * 0.7f))
            }
            2 -> {
                drawRect(color, Offset(w * 0.12f, h * 0.2f), Size(w * 0.12f, h * 0.6f))
                val p = Path().apply {
                    moveTo(w * 0.88f, h * 0.2f)
                    lineTo(w * 0.88f, h * 0.8f)
                    lineTo(w * 0.3f, h * 0.5f)
                    close()
                }
                drawPath(p, color)
            }
            else -> {
                drawRect(color, Offset(w * 0.76f, h * 0.2f), Size(w * 0.12f, h * 0.6f))
                val p = Path().apply {
                    moveTo(w * 0.12f, h * 0.2f)
                    lineTo(w * 0.12f, h * 0.8f)
                    lineTo(w * 0.7f, h * 0.5f)
                    close()
                }
                drawPath(p, color)
            }
        }
    }
}

// ---------------------------------------------------------------- menu fitur

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureMenu(current: Feat?, st: EditState, onPick: (Feat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TOP_FEATS.forEach { f ->
                FilterChip(
                    selected = current == f,
                    onClick = { onPick(f) },
                    label = { Text(if (applied(f, st)) f.label + " \u2022" else f.label) },
                )
            }
        }
        Text(
            "Fitur tambahan",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Feat.entries.filter { it !in TOP_FEATS }.forEach { f ->
                FilterChip(
                    selected = current == f,
                    onClick = { onPick(f) },
                    label = { Text(if (applied(f, st)) f.label + " \u2022" else f.label) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- bantuan UI

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    val cols = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFF44336, 0xFF4CAF50, 0xFF2196F3).map { it.toInt() }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        cols.forEach { c ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        if (c == selected) 3.dp else 1.dp,
                        if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .clickable { onPick(c) },
            )
        }
    }
}

private val ASPECTS = listOf(
    "Asli" to 0f,
    "1:1" to 1f,
    "16:9" to 16f / 9f,
    "9:16" to 9f / 16f,
    "4:5" to 0.8f,
    "4:3" to 4f / 3f,
)

// ---------------------------------------------------------------- panel fitur

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimPanel(m: Meta, st: EditState, pos: Long, onChange: (EditState) -> Unit) {
    val end = if (st.trimEnd < 0) m.durMs else st.trimEnd
    RangeSlider(
        value = st.trimStart.toFloat()..end.toFloat(),
        onValueChange = { r ->
            val e = r.endInclusive.toLong()
            onChange(st.copy(trimStart = r.start.toLong(), trimEnd = if (e >= m.durMs - 50) -1L else e))
        },
        valueRange = 0f..m.durMs.toFloat(),
    )
    Text(
        "Mulai ${fmtDuration(st.trimStart)}  \u2022  Akhir ${fmtDuration(end)}  \u2022  Durasi ${fmtDuration(end - st.trimStart)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(st.copy(trimStart = pos.coerceAtMost(end - 200).coerceAtLeast(0))) }) {
            Text("Mulai = posisi ini")
        }
        OutlinedButton(onClick = {
            val e = pos.coerceAtLeast(st.trimStart + 200)
            onChange(st.copy(trimEnd = if (e >= m.durMs - 50) -1L else e))
        }) { Text("Akhir = posisi ini") }
    }
    Hint("Hanya bagian di antara Mulai dan Akhir yang disimpan.")
}

@Composable
private fun CutPanel(m: Meta, st: EditState, pos: Long, onChange: (EditState) -> Unit) {
    var cs by remember { mutableLongStateOf(-1L) }
    var ce by remember { mutableLongStateOf(-1L) }
    Hint("Tandai bagian tengah yang ingin dibuang. Mainkan video, lalu tandai awal dan akhirnya.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { cs = pos }) { Text("Awal = posisi ini") }
        OutlinedButton(onClick = { ce = pos }) { Text("Akhir = posisi ini") }
    }
    Text(
        "Awal: ${if (cs >= 0) fmtDuration(cs) else "-"}   Akhir: ${if (ce >= 0) fmtDuration(ce) else "-"}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(
        onClick = {
            onChange(st.copy(cuts = st.cuts + (cs..ce)))
            cs = -1L
            ce = -1L
        },
        enabled = cs >= 0 && ce > cs + 100,
    ) { Text("Buang bagian ini") }
    st.cuts.forEachIndexed { i, r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Dibuang: ${fmtDuration(r.first)} - ${fmtDuration(r.last)}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onChange(st.copy(cuts = st.cuts.filterIndexed { k, _ -> k != i })) }) { Text("Hapus") }
        }
    }
}

@Composable
private fun MutePanel(m: Meta, st: EditState, onChange: (EditState) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Hapus suara video", style = MaterialTheme.typography.bodyLarge)
        Switch(st.mute, { onChange(st.copy(mute = it)) })
    }
    if (!m.hasAudio) Hint("Video ini memang tidak punya suara.")
    Hint("Musik latar atau sulih suara tetap bisa ditambahkan setelah suara asli dihapus.")
}

@Composable
private fun CompressPanel(st: EditState, onChange: (EditState) -> Unit) {
    Text("Resolusi (sisi pendek)", style = MaterialTheme.typography.bodyMedium)
    ChipRow(
        listOf("Asli" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360),
        st.shortSide,
    ) { onChange(st.copy(shortSide = it)) }
    Text("Bitrate (kompresi)", style = MaterialTheme.typography.bodyMedium)
    ChipRow(
        listOf("Otomatis" to 0, "Tinggi 8 Mbps" to 8, "Sedang 4 Mbps" to 4, "Rendah 2 Mbps" to 2, "Hemat 1 Mbps" to 1),
        st.bitrateMbps,
    ) { onChange(st.copy(bitrateMbps = it)) }
    Hint("Resolusi hanya bisa diturunkan, tidak dinaikkan. Bitrate lebih rendah = berkas lebih kecil.")
}

@Composable
private fun CropPanel(st: EditState, onChange: (EditState) -> Unit) {
    Text("Rasio (potong dari tengah)", style = MaterialTheme.typography.bodyMedium)
    ChipRow(ASPECTS, st.cropAspect) { onChange(st.copy(cropAspect = it)) }
    Text("Pangkas tepi", style = MaterialTheme.typography.bodyMedium)
    LabeledSlider("Kiri", st.cropL, 0f..0.4f, "${(st.cropL * 100).roundToInt()}%") { onChange(st.copy(cropL = it)) }
    LabeledSlider("Kanan", st.cropR, 0f..0.4f, "${(st.cropR * 100).roundToInt()}%") { onChange(st.copy(cropR = it)) }
    LabeledSlider("Atas", st.cropT, 0f..0.4f, "${(st.cropT * 100).roundToInt()}%") { onChange(st.copy(cropT = it)) }
    LabeledSlider("Bawah", st.cropB, 0f..0.4f, "${(st.cropB * 100).roundToInt()}%") { onChange(st.copy(cropB = it)) }
    Hint("Pratinjau di atas belum menampilkan hasil crop. Hasilnya terlihat setelah disimpan.")
}

@Composable
private fun SensorPanel(st: EditState, onChange: (EditState) -> Unit) {
    Hint("Seret di atas pratinjau video untuk menutup area sensitif (nomor, wajah, nama).")
    ChipRow(listOf("Hitam" to 0, "Mosaik" to 1), st.blockStyle) { onChange(st.copy(blockStyle = it)) }
    Text("Area tertutup: ${st.blocks.size}", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(st.copy(blocks = st.blocks.dropLast(1))) }, enabled = st.blocks.isNotEmpty()) {
            Text("Hapus terakhir")
        }
        OutlinedButton(onClick = { onChange(st.copy(blocks = emptyList())) }, enabled = st.blocks.isNotEmpty()) {
            Text("Hapus semua")
        }
    }
    Hint("Area ditutup penuh (bukan blur halus) dan berlaku sepanjang video.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextPanel(m: Meta, st: EditState, onChange: (EditState) -> Unit) {
    val outDur = EditEngine.outDurationMs(st, m.durMs).toFloat().coerceAtLeast(1000f)
    var text by remember { mutableStateOf("") }
    var size by remember { mutableFloatStateOf(0.06f) }
    var y by remember { mutableFloatStateOf(0.88f) }
    var color by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var whole by remember { mutableStateOf(true) }
    var range by remember(outDur) { mutableStateOf(0f..outDur) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Teks / subtitle") },
        modifier = Modifier.fillMaxWidth(),
    )
    LabeledSlider("Ukuran", size, 0.03f..0.14f, "${(size * 100).roundToInt()}%") { size = it }
    LabeledSlider("Posisi (atas ke bawah)", y, 0.08f..0.97f, "${(y * 100).roundToInt()}%") { y = it }
    ColorRow(color) { color = it }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Tampil sepanjang video", style = MaterialTheme.typography.bodyMedium)
        Switch(whole, { whole = it })
    }
    if (!whole) {
        RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..outDur)
        Text(
            "Tampil ${fmtDuration(range.start.toLong())} - ${fmtDuration(range.endInclusive.toLong())} (waktu hasil akhir)",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(
        onClick = {
            val item = TextItem(
                text.trim(), color, size, y,
                if (whole) 0L else range.start.toLong(),
                if (whole) -1L else range.endInclusive.toLong(),
            )
            onChange(st.copy(texts = st.texts + item))
            text = ""
        },
        enabled = text.isNotBlank(),
    ) { Text("Tambah teks") }
    st.texts.forEachIndexed { i, t ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                t.text.replace('\n', ' '),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onChange(st.copy(texts = st.texts.filterIndexed { k, _ -> k != i })) }) { Text("Hapus") }
        }
    }
    Hint("Ketik beberapa baris untuk subtitle panjang. Atur waktu tampil untuk membuat subtitle bergantian.")
}

@Composable
private fun SpeedPanel(st: EditState, onChange: (EditState) -> Unit) {
    ChipRow(listOf("0.5x" to 0.5f, "1x" to 1f, "1.5x" to 1.5f, "2x" to 2f), st.speed) { onChange(st.copy(speed = it)) }
    Hint("Berlaku untuk seluruh video, termasuk suaranya.")
}

@Composable
private fun RotatePanel(st: EditState, onChange: (EditState) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(st.copy(rotation = (st.rotation + 90) % 360)) }) { Text("Putar 90\u00B0") }
        OutlinedButton(onClick = { onChange(st.copy(flipH = !st.flipH)) }) { Text("Flip horizontal") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(st.copy(flipV = !st.flipV)) }) { Text("Flip vertikal") }
        OutlinedButton(onClick = { onChange(st.copy(rotation = 0, flipH = false, flipV = false)) }) { Text("Normal") }
    }
    Text(
        "Putaran ${st.rotation}\u00B0 \u2022 Flip H ${if (st.flipH) "ya" else "tidak"} \u2022 Flip V ${if (st.flipV) "ya" else "tidak"}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Hint("Pratinjau belum menampilkan putaran. Hasilnya terlihat setelah disimpan.")
}

@Composable
private fun BgmPanel(st: EditState, onPick: () -> Unit, onChange: (EditState) -> Unit) {
    OutlinedButton(onClick = onPick) { Text(if (st.bgmUri == null) "Pilih musik" else "Ganti musik") }
    if (st.bgmUri != null) {
        Text(st.bgmName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        LabeledSlider("Volume musik", st.bgmVol, 0f..1f, "${(st.bgmVol * 100).roundToInt()}%") { onChange(st.copy(bgmVol = it)) }
        TextButton(onClick = { onChange(st.copy(bgmUri = null, bgmName = "")) }) { Text("Hapus musik") }
    }
    Hint("Musik diulang otomatis dan dipotong mengikuti panjang video.")
}

@Composable
private fun VoicePanel(st: EditState, recording: Boolean, onToggle: () -> Unit, onChange: (EditState) -> Unit) {
    Hint("Putar video ke posisi awal sulih suara, lalu tekan Rekam. Video ikut berjalan. Gunakan headset agar suara video tidak terekam.")
    Button(onClick = onToggle) { Text(if (recording) "Berhenti merekam" else "Rekam suara") }
    if (st.voicePath != null) {
        Text("Sulih suara tersimpan (mulai ${fmtDuration(st.voiceStartMs)})", style = MaterialTheme.typography.bodyMedium)
        LabeledSlider("Volume suara", st.voiceVol, 0f..2f, "${(st.voiceVol * 100).roundToInt()}%") { onChange(st.copy(voiceVol = it)) }
        TextButton(onClick = { onChange(st.copy(voicePath = null)) }) { Text("Hapus sulih suara") }
    }
}

@Composable
private fun DoodlePanel(
    st: EditState,
    color: Int,
    width: Float,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onChange: (EditState) -> Unit,
) {
    Hint("Gambar langsung di atas pratinjau video. Coretan tampil sepanjang video.")
    ColorRow(color, onColor)
    LabeledSlider("Tebal kuas", width, 0.004f..0.04f, "${(width * 1000).roundToInt()}") { onWidth(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(st.copy(strokes = st.strokes.dropLast(1))) }, enabled = st.strokes.isNotEmpty()) {
            Text("Urungkan")
        }
        OutlinedButton(onClick = { onChange(st.copy(strokes = emptyList())) }, enabled = st.strokes.isNotEmpty()) {
            Text("Hapus semua")
        }
    }
}

@Composable
private fun GifPanel(onMake: (Int, Int) -> Unit) {
    var width by remember { mutableIntStateOf(360) }
    var fps by remember { mutableIntStateOf(10) }
    Text("Lebar GIF", style = MaterialTheme.typography.bodyMedium)
    ChipRow(listOf("240 px" to 240, "360 px" to 360, "480 px" to 480), width) { width = it }
    Text("Frame per detik", style = MaterialTheme.typography.bodyMedium)
    ChipRow(listOf("8" to 8, "10" to 10, "15" to 15), fps) { fps = it }
    Button(onClick = { onMake(width, fps) }) { Text("Buat GIF") }
    Hint("Memakai rentang Trim, maksimal 15 detik. Warna GIF terbatas 256 warna. Tersimpan di Pictures/NexRecorder.")
}

@Composable
private fun AudioPanel(m: Meta, onExtract: () -> Unit) {
    Button(onClick = onExtract, enabled = m.hasAudio) { Text("Simpan audio (M4A)") }
    if (!m.hasAudio) Hint("Video ini tidak punya suara.")
    Hint("Audio disalin tanpa mengurangi kualitas, memakai rentang Trim. Format MP3 belum tersedia. Tersimpan di Music/NexRecorder.")
}

@Composable
private fun CanvasPanel(st: EditState, onChange: (EditState) -> Unit) {
    Text("Bingkai (video diperkecil, sisi sisanya hitam)", style = MaterialTheme.typography.bodyMedium)
    ChipRow(ASPECTS, st.canvasAspect) { onChange(st.copy(canvasAspect = it)) }
    Hint("Cocok untuk membuat video 9:16 atau 1:1 untuk media sosial. Latar saat ini hitam polos (belum blur).")
}

@Composable
private fun BrandPanel(st: EditState, onPick: () -> Unit, onChange: (EditState) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPick) { Text(if (st.wmBitmap == null) "Pilih gambar" else "Ganti gambar") }
        if (st.wmBitmap != null) TextButton(onClick = { onChange(st.copy(wmBitmap = null)) }) { Text("Hapus") }
    }
    if (st.wmBitmap != null) {
        Text("Posisi", style = MaterialTheme.typography.bodyMedium)
        ChipRow(
            listOf("Kiri atas" to 0, "Kanan atas" to 1, "Kiri bawah" to 2, "Kanan bawah" to 3, "Tengah" to 4),
            st.wmPos,
        ) { onChange(st.copy(wmPos = it)) }
        LabeledSlider("Ukuran", st.wmSizePct, 0.05f..0.6f, "${(st.wmSizePct * 100).roundToInt()}%") { onChange(st.copy(wmSizePct = it)) }
        LabeledSlider("Kepekatan", st.wmAlpha, 0.1f..1f, "${(st.wmAlpha * 100).roundToInt()}%") { onChange(st.copy(wmAlpha = it)) }
    }
}

@Composable
private fun SmartPanel(
    m: Meta,
    st: EditState,
    found: List<LongRange>?,
    onAnalyze: (Int) -> Unit,
    onFound: (List<LongRange>?) -> Unit,
    onChange: (EditState) -> Unit,
) {
    var level by remember { mutableIntStateOf(1) }
    Hint("Mencari bagian video yang tanpa suara lalu membuangnya otomatis.")
    ChipRow(listOf("Halus" to 0, "Sedang" to 1, "Agresif" to 2), level) { level = it }
    Button(onClick = { onAnalyze(level) }, enabled = m.hasAudio) { Text("Analisis keheningan") }
    if (!m.hasAudio) Hint("Video ini tidak punya suara.")
    if (found != null) {
        val total = found.sumOf { it.last - it.first }
        Text(
            if (found.isEmpty()) "Tidak ditemukan bagian diam." else "Ditemukan ${found.size} bagian diam, total ${total / 1000} detik.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (found.isNotEmpty()) {
            Button(onClick = { onChange(st.copy(silent = found)) }) { Text("Terapkan (buang bagian diam)") }
        }
    }
    if (st.silent.isNotEmpty()) {
        TextButton(onClick = { onChange(st.copy(silent = emptyList())); onFound(null) }) { Text("Batalkan smart trim") }
    }
}
