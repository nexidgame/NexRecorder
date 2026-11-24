package com.nex.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------------ model

/** Informasi dasar video (ukuran sudah memperhitungkan rotasi). */
data class Meta(val w: Int, val h: Int, val durMs: Long, val hasAudio: Boolean)

/** Kotak area sensor, dalam pecahan 0..1 dari lebar/tinggi video. */
data class Blk(val l: Float, val t: Float, val r: Float, val b: Float)

data class Pt(val x: Float, val y: Float)

data class DoodleStroke(val color: Int, val widthPct: Float, val pts: List<Pt>)

/** endMs < 0 artinya tampil sepanjang video. Waktu dihitung pada hasil akhir. */
data class TextItem(
    val text: String,
    val color: Int,
    val sizePct: Float,
    val yFrac: Float,
    val startMs: Long,
    val endMs: Long,
)

/** Semua suntingan yang belum diekspor. */
data class EditState(
    val trimStart: Long = 0L,
    val trimEnd: Long = -1L,
    val cuts: List<LongRange> = emptyList(),
    val silent: List<LongRange> = emptyList(),
    val mute: Boolean = false,
    val shortSide: Int = 0,
    val bitrateMbps: Int = 0,
    val cropAspect: Float = 0f,
    val cropL: Float = 0f,
    val cropR: Float = 0f,
    val cropT: Float = 0f,
    val cropB: Float = 0f,
    val canvasAspect: Float = 0f,
    val blocks: List<Blk> = emptyList(),
    val blockStyle: Int = 0,
    val texts: List<TextItem> = emptyList(),
    val strokes: List<DoodleStroke> = emptyList(),
    val speed: Float = 1f,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val bgmUri: Uri? = null,
    val bgmName: String = "",
    val bgmVol: Float = 0.5f,
    val voicePath: String? = null,
    val voiceStartMs: Long = 0L,
    val voiceVol: Float = 1f,
    val wmBitmap: Bitmap? = null,
    val wmPos: Int = 1,
    val wmSizePct: Float = 0.2f,
    val wmAlpha: Float = 1f,
)

data class Dims(val w: Int, val h: Int)

// ------------------------------------------------------------------ penggambaran lapisan

/** Lapisan tahap 1 (pada bingkai asli, sebelum crop/putar): area sensor dan coretan. */
fun drawStage1(c: Canvas, w: Int, h: Int, st: EditState) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    for ((i, b) in st.blocks.withIndex()) {
        val l = b.l * w
        val t = b.t * h
        val r = b.r * w
        val bm = b.b * h
        if (st.blockStyle == 0) {
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            c.drawRect(l, t, r, bm, p)
        } else {
            val tile = max(8f, min(r - l, bm - t) / 6f)
            var y = t
            var row = 0
            while (y < bm) {
                var x = l
                var col = 0
                while (x < r) {
                    val g = 70 + ((row * 7 + col * 13 + i * 5) % 9) * 14
                    p.style = Paint.Style.FILL
                    p.color = Color.rgb(g, g, g)
                    c.drawRect(x, y, min(x + tile, r), min(y + tile, bm), p)
                    x += tile
                    col++
                }
                y += tile
                row++
            }
        }
    }
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (s in st.strokes) {
        if (s.pts.isEmpty()) continue
        sp.color = s.color
        sp.strokeWidth = max(2f, s.widthPct * min(w, h))
        if (s.pts.size == 1) {
            c.drawPoint(s.pts[0].x * w, s.pts[0].y * h, sp)
        } else {
            val path = Path()
            path.moveTo(s.pts[0].x * w, s.pts[0].y * h)
            for (k in 1 until s.pts.size) path.lineTo(s.pts[k].x * w, s.pts[k].y * h)
            c.drawPath(path, sp)
        }
    }
}

/** Lapisan tahap 2 (pada bingkai akhir): watermark dan teks. tMs = waktu pada hasil akhir. */
fun drawStage2(c: Canvas, w: Int, h: Int, st: EditState, tMs: Long) {
    val bmp = st.wmBitmap
    if (bmp != null && bmp.width > 0 && bmp.height > 0) {
        val tw = w * st.wmSizePct
        val th = tw * bmp.height / bmp.width
        val m = w * 0.03f
        val x: Float
        val y: Float
        when (st.wmPos) {
            0 -> { x = m; y = m }
            1 -> { x = w - tw - m; y = m }
            2 -> { x = m; y = h - th - m }
            3 -> { x = w - tw - m; y = h - th - m }
            else -> { x = (w - tw) / 2f; y = (h - th) / 2f }
        }
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        p.alpha = (st.wmAlpha.coerceIn(0f, 1f) * 255).roundToInt()
        c.drawBitmap(bmp, null, RectF(x, y, x + tw, y + th), p)
    }
    for (t in st.texts) {
        if (t.endMs >= 0 && (tMs < t.startMs || tMs >= t.endMs)) continue
        var size = t.sizePct * h
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = t.color
            isFakeBoldText = true
        }
        val lines = t.text.split('\n')
        fill.textSize = size
        val widest = lines.maxOf { fill.measureText(it) }
        if (widest > w * 0.94f) {
            size *= (w * 0.94f) / widest
            fill.textSize = size
        }
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.14f
            color = if (t.color == Color.BLACK) Color.WHITE else Color.BLACK
        }
        val lineH = size * 1.2f
        var y = t.yFrac * h
        for (ln in lines) {
            c.drawText(ln, w / 2f, y, stroke)
            c.drawText(ln, w / 2f, y, fill)
            y += lineH
        }
    }
}

// ------------------------------------------------------------------ overlay Media3

/** Overlay satu bitmap penuh-bingkai; bitmap dibuat ulang saat waktu melewati batas interval. */
@androidx.annotation.OptIn(UnstableApi::class)
private class LayerOverlay(
    private val w: Int,
    private val h: Int,
    private val boundsMs: List<Long>,
    private val painter: (Canvas, Long) -> Unit,
) : BitmapOverlay() {
    private var idx = -1
    private var bmp: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val tMs = presentationTimeUs / 1000
        var i = 0
        while (i + 1 < boundsMs.size && boundsMs[i + 1] <= tMs) i++
        var b = bmp
        if (i != idx || b == null) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            painter(Canvas(b), boundsMs[i])
            bmp = b
            idx = i
        }
        return b!!
    }
}

// ------------------------------------------------------------------ mesin

object EditEngine {

    fun readMeta(ctx: Context, uri: Uri): Meta {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w
                w = h
                h = t
            }
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            return Meta(w, h, dur, audio)
        } finally {
            r.release()
        }
    }

    /** Bagian video yang dipertahankan (ms), setelah trim, cut, dan smart trim. */
    fun keepRanges(st: EditState, durMs: Long): List<LongRange> {
        val start = st.trimStart.coerceIn(0L, durMs)
        val end = (if (st.trimEnd < 0) durMs else st.trimEnd).coerceIn(start, durMs)
        val removed = (st.cuts + st.silent).sortedBy { it.first }
        val out = ArrayList<LongRange>()
        var cur = start
        for (r in removed) {
            if (r.last <= cur) continue
            if (r.first >= end) break
            if (r.first > cur) out.add(cur..r.first)
            cur = max(cur, r.last)
        }
        if (cur < end) out.add(cur..end)
        return out.filter { it.last - it.first >= 100 }
    }

    /** Durasi hasil akhir (ms) setelah potongan dan kecepatan. */
    fun outDurationMs(st: EditState, durMs: Long): Long {
        val total = keepRanges(st, durMs).sumOf { it.last - it.first }
        return (total / st.speed).toLong()
    }

    /** Waktu video asli -> waktu pada hasil akhir. */
    fun outTimeMs(keep: List<LongRange>, srcMs: Long, speed: Float): Long {
        var acc = 0L
        for (r in keep) {
            if (srcMs >= r.last) {
                acc += r.last - r.first
            } else {
                if (srcMs > r.first) acc += srcMs - r.first
                break
            }
        }
        return (acc / speed).toLong()
    }

    fun finalDims(srcW: Int, srcH: Int, st: EditState): Dims {
        var w = srcW * (1f - st.cropL - st.cropR)
        var h = srcH * (1f - st.cropT - st.cropB)
        if (st.rotation == 90 || st.rotation == 270) {
            val t = w
            w = h
            h = t
        }
        if (st.cropAspect > 0f) {
            if (w / h > st.cropAspect) w = h * st.cropAspect else h = w / st.cropAspect
        }
        if (st.canvasAspect > 0f) {
            if (w / h > st.canvasAspect) h = w / st.canvasAspect else w = h * st.canvasAspect
        }
        if (st.shortSide > 0) {
            val s = min(w, h)
            if (st.shortSide < s) {
                val f = st.shortSide / s
                w *= f
                h *= f
            }
        }
        val ew = (w.roundToInt() / 2 * 2).coerceAtLeast(2)
        val eh = (h.roundToInt() / 2 * 2).coerceAtLeast(2)
        return Dims(ew, eh)
    }

    // ------------------------------------------------------------- ekspor video

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun videoEffects(m: Meta, st: EditState, dims: Dims): List<Effect> {
        val list = ArrayList<Effect>()
        if (st.blocks.isNotEmpty() || st.strokes.isNotEmpty()) {
            val ov = LayerOverlay(m.w, m.h, listOf(0L)) { c, _ -> drawStage1(c, m.w, m.h, st) }
            list.add(OverlayEffect(ImmutableList.of<TextureOverlay>(ov)))
        }
        if (st.cropL > 0f || st.cropR > 0f || st.cropT > 0f || st.cropB > 0f) {
            list.add(Crop(-1f + 2f * st.cropL, 1f - 2f * st.cropR, -1f + 2f * st.cropB, 1f - 2f * st.cropT))
        }
        if (st.rotation != 0 || st.flipH || st.flipV) {
            list.add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(if (st.flipH) -1f else 1f, if (st.flipV) -1f else 1f)
                    .setRotationDegrees(((360 - st.rotation) % 360).toFloat())
                    .build(),
            )
        }
        if (st.cropAspect > 0f) {
            var w = m.w * (1f - st.cropL - st.cropR)
            var h = m.h * (1f - st.cropT - st.cropB)
            if (st.rotation == 90 || st.rotation == 270) {
                val t = w
                w = h
                h = t
            }
            var fx = 0f
            var fy = 0f
            if (w / h > st.cropAspect) {
                fx = (1f - (h * st.cropAspect) / w) / 2f
            } else {
                fy = (1f - (w / st.cropAspect) / h) / 2f
            }
            if (fx > 0.001f || fy > 0.001f) {
                list.add(Crop(-1f + 2f * fx, 1f - 2f * fx, -1f + 2f * fy, 1f - 2f * fy))
            }
        }
        if (st.speed != 1f) list.add(SpeedChangeEffect(st.speed))
        list.add(Presentation.createForWidthAndHeight(dims.w, dims.h, Presentation.LAYOUT_SCALE_TO_FIT))
        return list
    }

    private fun volumeProcessor(v: Float): AudioProcessor {
        val p = ChannelMixingAudioProcessor()
        p.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(v))
        p.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v))
        return p
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildComposition(src: Uri, m: Meta, st: EditState, keep: List<LongRange>, dims: Dims): Composition {
        val items = keep.map { r ->
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(r.first)
                .setEndPositionMs(r.last)
                .build()
            val mi = MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build()
            val audio = ArrayList<AudioProcessor>()
            if (st.speed != 1f) {
                val sonic = SonicAudioProcessor()
                sonic.setSpeed(st.speed)
                audio.add(sonic)
            }
            EditedMediaItem.Builder(mi)
                .setRemoveAudio(st.mute)
                .setEffects(Effects(audio, videoEffects(m, st, dims)))
                .build()
        }
        val seqs = ArrayList<EditedMediaItemSequence>()
        val vb = EditedMediaItemSequence.Builder()
        for (it in items) vb.addItem(it)
        seqs.add(vb.build())

        val bgm = st.bgmUri
        if (bgm != null) {
            val bi = EditedMediaItem.Builder(MediaItem.fromUri(bgm))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volumeProcessor(st.bgmVol)), emptyList()))
                .build()
            seqs.add(EditedMediaItemSequence.Builder().addItem(bi).setIsLooping(true).build())
        }
        val voice = st.voicePath
        if (voice != null && File(voice).exists()) {
            val vi = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(voice))))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volumeProcessor(st.voiceVol)), emptyList()))
                .build()
            val vs = EditedMediaItemSequence.Builder()
            val gapMs = outTimeMs(keep, st.voiceStartMs, st.speed)
            if (gapMs > 50) vs.addGap(gapMs * 1000)
            vs.addItem(vi)
            seqs.add(vs.build())
        }

        val cb = Composition.Builder(seqs)
        if (st.texts.isNotEmpty() || st.wmBitmap != null) {
            val bounds = (listOf(0L) + st.texts.flatMap { listOf(it.startMs, it.endMs).filter { v -> v >= 0 } })
                .distinct().sorted()
            val ov = LayerOverlay(dims.w, dims.h, bounds) { c, t -> drawStage2(c, dims.w, dims.h, st, t) }
            cb.setEffects(Effects(emptyList(), listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(ov)))))
        }
        return cb.build()
    }

    /** Mengekspor semua suntingan menjadi MP4 baru di Movies/NexRecorder. */
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun exportVideo(ctx: Context, src: Uri, m: Meta, st: EditState, onProgress: (Int) -> Unit): Uri {
        val keep = keepRanges(st, m.durMs)
        if (keep.isEmpty()) throw IllegalStateException("Tidak ada bagian video yang tersisa")
        val dims = finalDims(m.w, m.h, st)
        val outFile = File(ctx.cacheDir, "nexedit_out.mp4")
        outFile.delete()
        withContext(Dispatchers.Main) {
            coroutineScope {
                val done = CompletableDeferred<Unit>()
                val builder = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            done.completeExceptionally(exportException)
                        }
                    })
                if (st.bitrateMbps > 0) {
                    builder.setEncoderFactory(
                        DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(st.bitrateMbps * 1_000_000).build(),
                            )
                            .build(),
                    )
                }
                val transformer = builder.build()
                transformer.start(buildComposition(src, m, st, keep, dims), outFile.absolutePath)
                val poll = launch {
                    val ph = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(ph.progress)
                        delay(300)
                    }
                }
                try {
                    done.await()
                } finally {
                    poll.cancel()
                    if (!done.isCompleted) transformer.cancel()
                }
            }
        }
        return withContext(Dispatchers.IO) {
            val uri = saveToStore(
                ctx, outFile, "NexEdit_${stamp()}.mp4", "video/mp4",
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/NexRecorder",
            )
            outFile.delete()
            uri
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun saveToStore(ctx: Context, file: File, name: String, mime: String, collection: Uri, relPath: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(collection, cv) ?: throw IOException("Gagal membuat berkas")
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, done, null, null)
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    // ------------------------------------------------------------- ekstrak audio (M4A)

    /** Menyalin trek audio (tanpa encode ulang) ke berkas M4A di Music/NexRecorder. */
    suspend fun extractAudio(ctx: Context, src: Uri, startMs: Long, endMs: Long): Uri = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        val tmp = File(ctx.cacheDir, "nexaudio.m4a")
        tmp.delete()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(ctx, src, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("Video ini tidak punya suara")
            ex.selectTrack(track)
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(fmt)
            muxer.start()
            val startUs = startMs * 1000
            val endUs = if (endMs < 0) Long.MAX_VALUE else endMs * 1000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val size = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                max(fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 1 shl 16)
            } else {
                1 shl 20
            }
            val buf = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                if (t >= startUs) {
                    info.set(0, n, t - startUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(outTrack, buf, info)
                }
                ex.advance()
            }
            muxer.stop()
            muxer.release()
            muxer = null
            val uri = saveToStore(
                ctx, tmp, "NexAudio_${stamp()}.m4a", "audio/mp4",
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/NexRecorder",
            )
            uri
        } finally {
            try { muxer?.release() } catch (e: Exception) { }
            ex.release()
            tmp.delete()
        }
    }

    // ------------------------------------------------------------- smart trim (deteksi diam)

    /** Mencari bagian diam (ms). null jika video tidak punya suara. */
    suspend fun analyzeSilence(
        ctx: Context,
        uri: Uri,
        thresholdDb: Float,
        minSilentMs: Long,
        onProgress: (Int) -> Unit,
    ): List<LongRange>? = withContext(Dispatchers.Default) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) return@withContext null
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()
            var sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val info = MediaCodec.BufferInfo()
            val winMs = 50
            val dbs = ArrayList<Float>()
            var winFrames = 0
            var winSum = 0.0
            var inDone = false
            var outDone = false
            var lastPct = -1
            while (!outDone && isActive) {
                if (!inDone) {
                    val ii = dec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            if (durUs > 0) {
                                val pct = (ex.sampleTime * 100 / durUs).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = dec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val framesPerWin = max(1, sampleRate * winMs / 1000)
                    val ch = max(1, channels)
                    while (sb.remaining() >= ch) {
                        var s = 0.0
                        for (k in 0 until ch) {
                            val v = sb.get().toDouble()
                            s += v * v
                        }
                        winSum += s / ch
                        winFrames++
                        if (winFrames >= framesPerWin) {
                            val ms = winSum / winFrames / (32768.0 * 32768.0)
                            dbs.add((10.0 * log10(max(ms, 1e-12))).toFloat())
                            winSum = 0.0
                            winFrames = 0
                        }
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            val out = ArrayList<LongRange>()
            var i = 0
            val pad = 150L
            while (i < dbs.size) {
                if (dbs[i] < thresholdDb) {
                    var j = i
                    while (j + 1 < dbs.size && dbs[j + 1] < thresholdDb) j++
                    val s = i * winMs.toLong()
                    val e = (j + 1) * winMs.toLong()
                    if (e - s >= minSilentMs) {
                        val a = s + pad
                        val b = e - pad
                        if (b - a >= 200) out.add(a..b)
                    }
                    i = j + 1
                } else {
                    i++
                }
            }
            out
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }

    // ------------------------------------------------------------- video ke GIF

    /** Membuat GIF dari rentang video. Disimpan di Pictures/NexRecorder. */
    suspend fun makeGif(
        ctx: Context,
        uri: Uri,
        m: Meta,
        startMs: Long,
        endMs: Long,
        widthPx: Int,
        fps: Int,
        onProgress: (Int) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val w = widthPx.coerceAtLeast(16)
        val h = (widthPx.toLong() * m.h / max(m.w, 1)).toInt().coerceAtLeast(16)
        val span = (endMs - startMs).coerceIn(200L, 15_000L)
        val step = 1000L / fps
        val count = (span / step).toInt().coerceAtLeast(1)
        val tmp = File(ctx.cacheDir, "nexgif.gif")
        tmp.delete()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            BufferedOutputStream(FileOutputStream(tmp)).use { os ->
                val g = GifWriter(os, w, h, (step / 10).toInt().coerceAtLeast(2))
                var last: IntArray? = null
                for (i in 0 until count) {
                    if (!isActive) throw kotlinx.coroutines.CancellationException()
                    val t = (startMs + i * step) * 1000
                    val bmp = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                    if (bmp != null) {
                        val px = IntArray(w * h)
                        val sc = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
                        sc.getPixels(px, 0, w, 0, 0, w, h)
                        last = px
                    }
                    val frame = last
                    if (frame != null) g.addFrame(frame)
                    onProgress((i + 1) * 100 / count)
                }
                g.finish()
            }
            saveToStore(
                ctx, tmp, "NexGif_${stamp()}.gif", "image/gif",
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/NexRecorder",
            )
        } finally {
            r.release()
            tmp.delete()
        }
    }
}

/** Penulis GIF sederhana: palet tetap 3-3-2 bit (256 warna), kompresi LZW. */
private class GifWriter(private val os: OutputStream, private val w: Int, private val h: Int, private val delayCs: Int) {
    private var started = false

    private fun le16(v: Int) {
        os.write(v and 0xFF)
        os.write((v shr 8) and 0xFF)
    }

    private fun header() {
        os.write("GIF89a".toByteArray())
        le16(w)
        le16(h)
        os.write(0xF7) // tabel warna global, 256 warna
        os.write(0)
        os.write(0)
        for (i in 0 until 256) {
            os.write(((i shr 5) and 7) * 255 / 7)
            os.write(((i shr 2) and 7) * 255 / 7)
            os.write((i and 3) * 255 / 3)
        }
        // ulangi selamanya
        os.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        os.write("NETSCAPE2.0".toByteArray())
        os.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
    }

    fun addFrame(argb: IntArray) {
        if (!started) {
            header()
            started = true
        }
        // kontrol grafis
        os.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00))
        le16(delayCs)
        os.write(0)
        os.write(0)
        // deskriptor gambar
        os.write(0x2C)
        le16(0)
        le16(0)
        le16(w)
        le16(h)
        os.write(0)
        val idx = ByteArray(w * h)
        for (i in idx.indices) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            idx[i] = ((r and 0xE0) or ((g and 0xE0) shr 3) or (b shr 6)).toByte()
        }
        os.write(8)
        val data = lzw(idx)
        var p = 0
        while (p < data.size) {
            val n = min(255, data.size - p)
            os.write(n)
            os.write(data, p, n)
            p += n
        }
        os.write(0)
    }

    fun finish() {
        if (started) os.write(0x3B)
    }

    private fun lzw(pixels: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(pixels.size / 2 + 64)
        var cur = 0
        var curBits = 0
        var codeSize = 9
        val clear = 256
        val eoi = 257
        var next = 258
        val dict = HashMap<Int, Int>()

        fun emit(code: Int) {
            cur = cur or (code shl curBits)
            curBits += codeSize
            while (curBits >= 8) {
                out.write(cur and 0xFF)
                cur = cur ushr 8
                curBits -= 8
            }
        }

        emit(clear)
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val c = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            val code = dict[key]
            if (code != null) {
                prefix = code
            } else {
                emit(prefix)
                if (next < 4096) {
                    if (next > (1 shl codeSize) - 1 && codeSize < 12) codeSize++
                    dict[key] = next
                    next++
                } else {
                    emit(clear)
                    dict.clear()
                    next = 258
                    codeSize = 9
                }
                prefix = c
            }
        }
        emit(prefix)
        emit(eoi)
        if (curBits > 0) out.write(cur and 0xFF)
        return out.toByteArray()
    }
}
