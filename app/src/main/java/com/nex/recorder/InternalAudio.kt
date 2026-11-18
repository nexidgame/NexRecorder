package com.nex.recorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Merekam suara internal HP (suara game/media) lewat AudioPlaybackCapture,
 * opsional dicampur dengan mikrofon, lalu dienkode ke AAC dan disimpan ke berkas .m4a sementara.
 * Berkas ini digabung dengan video saat rekaman berhenti.
 */
class InternalAudioRecorder(
    private val projection: MediaProjection,
    private val sampleRate: Int,
    private val channels: Int,
    private val audioCodec: Int, // 0 = AAC, 1 = AAC_ELD, 2 = HE-AAC
    private val outFile: File,
    private val mixMic: Boolean = false,
    private val gameGain: Float = 1f,
    private val micGain: Float = 1f,
    private val audioKbps: Int = 128,
    private val filterLevel: Int = 0, // 0 = mati, 1 = ringan, 2 = kuat
    private val useGame: Boolean = true,   // false = hanya mikrofon (dipakai pengubah suara)
    private val micSourceId: Int = MediaRecorder.AudioSource.MIC,
    private val streamer: Boolean = false, // mode streamer: volume game turun saat Anda bicara
    private val voiceMode: Int = VOICE_ORIGINAL,
) {
    private var record: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var micFilter: MicFilter? = null
    private var limiter = false
    private val frameBuf = FloatArray(channels)
    private var voice: VoiceChanger? = null
    private var ducker: Ducker? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var thread: Thread? = null
    private var bufSize = 0

    @Volatile private var running = false

    /** true jika ada data audio yang benar-benar tertulis. */
    @Volatile var hasAudio = false
        private set

    /** true jika mikrofon diminta tetapi gagal dibuka (suara internal tetap direkam). */
    @Volatile var micFailed = false
        private set

    @SuppressLint("MissingPermission")
    fun prepare() {
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(mask)
            .build()
        val min = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        bufSize = max(min, 8192)
        if (useGame) {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord gagal dibuat")
        }

        if (mixMic) {
            try {
                val m = AudioRecord.Builder()
                    .setAudioSource(micSourceId)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize * 2)
                    .build()
                if (m.state != AudioRecord.STATE_INITIALIZED) {
                    m.release()
                    error("Mikrofon gagal dibuka")
                }
                micRecord = m
                if (voiceMode != VOICE_ORIGINAL) voice = VoiceChanger(sampleRate, channels, voiceMode)
                if (streamer && useGame) ducker = Ducker(sampleRate)
                if (filterLevel >= 1) {
                    micFilter = MicFilter(sampleRate, channels, gate = filterLevel >= 2)
                    // Efek bawaan Android; dilewati jika HP tidak menyediakannya.
                    noiseSuppressor = try {
                        if (NoiseSuppressor.isAvailable()) {
                            NoiseSuppressor.create(m.audioSessionId)?.also { it.setEnabled(true) }
                        } else null
                    } catch (e: Exception) { null }
                }
                if (filterLevel >= 2) {
                    limiter = true
                    echoCanceler = try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            AcousticEchoCanceler.create(m.audioSessionId)?.also { it.setEnabled(true) }
                        } else null
                    } catch (e: Exception) { null }
                }
            } catch (e: Exception) {
                micFailed = true
            }
        }

        if (!useGame && micRecord == null) error("Mikrofon gagal dibuka")

        val wanted = when (audioCodec) {
            1 -> MediaCodecInfo.CodecProfileLevel.AACObjectELD
            2 -> MediaCodecInfo.CodecProfileLevel.AACObjectHE
            else -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
        }
        codec = try {
            createCodec(wanted)
        } catch (e: Exception) {
            if (wanted == MediaCodecInfo.CodecProfileLevel.AACObjectLC) throw e
            createCodec(MediaCodecInfo.CodecProfileLevel.AACObjectLC) // cadangan: AAC biasa
        }
        muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun createCodec(profile: Int): MediaCodec {
        val codecIdx = when (profile) {
            MediaCodecInfo.CodecProfileLevel.AACObjectELD -> 1
            MediaCodecInfo.CodecProfileLevel.AACObjectHE -> 2
            else -> 0
        }
        val bitrate = effectiveAudioBitrate(audioKbps, channels, sampleRate, codecIdx)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw e
        }
        return c
    }

    fun start() {
        running = true
        record?.startRecording()
        micRecord?.startRecording()
        thread = Thread { loop() }.also { it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(5000) } catch (e: InterruptedException) { }
        release()
    }

    fun release() {
        running = false
        try { record?.stop() } catch (e: Exception) { }
        record?.release()
        record = null
        try { noiseSuppressor?.release() } catch (e: Exception) { }
        noiseSuppressor = null
        try { echoCanceler?.release() } catch (e: Exception) { }
        echoCanceler = null
        try { micRecord?.stop() } catch (e: Exception) { }
        micRecord?.release()
        micRecord = null
        try { codec?.stop() } catch (e: Exception) { }
        codec?.release()
        codec = null
        try { muxer?.release() } catch (e: Exception) { }
        muxer = null
    }

    /**
     * Membaca suara game dan mikrofon dengan jumlah yang sama, lalu mencampurnya.
     * Mikrofon difilter lebih dulu (jika diaktifkan), hasil campuran dibatasi agar tidak pecah.
     */
    private fun readMixed(
        game: AudioRecord, mic: AudioRecord, out: ByteBuffer, want: Int,
        gb: ByteArray, mb: ByteArray,
    ): Int {
        val n = game.read(gb, 0, want)
        if (n <= 0) return n
        var m = mic.read(mb, 0, n)
        if (m < 0) m = 0
        if (m < n) java.util.Arrays.fill(mb, m, n, 0.toByte())

        val filter = micFilter
        val frameBytes = 2 * channels
        var i = 0
        while (i + frameBytes <= n) {
            for (c in 0 until channels) {
                val j = i + 2 * c
                frameBuf[c] = ((mb[j].toInt() and 0xFF) or (mb[j + 1].toInt() shl 8)).toFloat()
            }
            filter?.process(frameBuf)
            var duck = 1f
            val d = ducker
            if (d != null) {
                var pk = 0f
                for (c in 0 until channels) {
                    val a = abs(frameBuf[c])
                    if (a > pk) pk = a
                }
                duck = d.next(pk)
            }
            voice?.process(frameBuf)
            for (c in 0 until channels) {
                val j = i + 2 * c
                val g = (gb[j].toInt() and 0xFF) or (gb[j + 1].toInt() shl 8)
                val mix = g * gameGain * duck + frameBuf[c] * micGain
                val v = if (limiter) softLimit(mix) else mix.roundToInt().coerceIn(-32768, 32767)
                out.put(j, (v and 0xFF).toByte())
                out.put(j + 1, ((v shr 8) and 0xFF).toByte())
            }
            i += frameBytes
        }
        return i
    }

    /** Hanya mikrofon (tanpa suara game): filter, pengubah suara, lalu ditulis ke buffer encoder. */
    private fun readMicOnly(mic: AudioRecord, out: ByteBuffer, want: Int, mb: ByteArray): Int {
        val n = mic.read(mb, 0, want)
        if (n <= 0) return n
        val filter = micFilter
        val frameBytes = 2 * channels
        var i = 0
        while (i + frameBytes <= n) {
            for (c in 0 until channels) {
                val j = i + 2 * c
                frameBuf[c] = ((mb[j].toInt() and 0xFF) or (mb[j + 1].toInt() shl 8)).toFloat()
            }
            filter?.process(frameBuf)
            voice?.process(frameBuf)
            for (c in 0 until channels) {
                val j = i + 2 * c
                val mix = frameBuf[c] * micGain
                val v = if (limiter) softLimit(mix) else mix.roundToInt().coerceIn(-32768, 32767)
                out.put(j, (v and 0xFF).toByte())
                out.put(j + 1, ((v shr 8) and 0xFF).toByte())
            }
            i += frameBytes
        }
        return i
    }

    /** Pembatas lembut: linear sampai 70% skala penuh, lalu dilengkungkan menuju batas. */
    private fun softLimit(x: Float): Int {
        val t = 22938f
        val room = 32767f - t
        val a = abs(x)
        val y = if (a <= t) a else t + room * tanh((a - t) / room)
        return (if (x < 0f) -y else y).roundToInt()
    }

    private fun loop() {
        val rec = record
        val mic = micRecord
        if (rec == null && mic == null) return
        val c = codec ?: return
        val mux = muxer ?: return
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels
        val chunkMax = (bufSize / bytesPerFrame) * bytesPerFrame
        val gameBytes = ByteArray(chunkMax)
        val micBytes = ByteArray(chunkMax)
        var totalFrames = 0L
        var inputDone = false
        var outputDone = false
        var track = -1
        var started = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = c.getInputBuffer(idx)
                        if (ib != null) {
                            ib.clear()
                            val pts = totalFrames * 1_000_000L / sampleRate
                            if (!running) {
                                c.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val want = (minOf(ib.capacity(), chunkMax) / bytesPerFrame) * bytesPerFrame
                                val n = when {
                                    rec != null && mic != null -> readMixed(rec, mic, ib, want, gameBytes, micBytes)
                                    rec != null -> rec.read(ib, want)
                                    else -> readMicOnly(mic!!, ib, want, micBytes)
                                }
                                if (n > 0) {
                                    c.queueInputBuffer(idx, 0, n, pts, 0)
                                    totalFrames += n / bytesPerFrame
                                } else {
                                    if (n < 0) running = false
                                    c.queueInputBuffer(idx, 0, 0, pts, 0)
                                }
                            }
                        }
                    }
                }
                while (true) {
                    val out = c.dequeueOutputBuffer(info, 0)
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(c.outputFormat)
                        mux.start()
                        started = true
                    } else if (out >= 0) {
                        val ob = c.getOutputBuffer(out)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (ob != null && info.size > 0 && started && !isConfig) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            mux.writeSampleData(track, ob, info)
                            hasAudio = true
                        }
                        c.releaseOutputBuffer(out, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // data yang sudah tertulis tetap dipakai
        } finally {
            if (started) {
                try { mux.stop() } catch (e: Exception) { hasAudio = false }
            } else {
                hasAudio = false
            }
        }
    }
}

/** Menggabungkan video (tanpa suara) dan audio .m4a menjadi satu MP4 ke [out]. */
fun muxVideoAudio(video: File, audio: File, out: FileDescriptor) {
    val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val vEx = MediaExtractor()
    val aEx = MediaExtractor()
    try {
        vEx.setDataSource(video.path)
        aEx.setDataSource(audio.path)
        val vi = findTrack(vEx, "video/")
        val ai = findTrack(aEx, "audio/")
        if (vi < 0 || ai < 0) error("Track tidak ditemukan")
        vEx.selectTrack(vi)
        aEx.selectTrack(ai)
        val vt = muxer.addTrack(vEx.getTrackFormat(vi))
        val at = muxer.addTrack(aEx.getTrackFormat(ai))
        muxer.start()

        val buf = ByteBuffer.allocate(4 shl 20)
        val info = MediaCodec.BufferInfo()
        var vDone = false
        var aDone = false
        while (!vDone || !aDone) {
            val useV = !vDone && (aDone || vEx.sampleTime <= aEx.sampleTime)
            val ex = if (useV) vEx else aEx
            val tr = if (useV) vt else at
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) {
                if (useV) vDone = true else aDone = true
                continue
            }
            val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, ex.sampleTime, flags)
            muxer.writeSampleData(tr, buf, info)
            ex.advance()
        }
        muxer.stop()
    } finally {
        try { vEx.release() } catch (e: Exception) { }
        try { aEx.release() } catch (e: Exception) { }
        try { muxer.release() } catch (e: Exception) { }
    }
}

private fun findTrack(ex: MediaExtractor, prefix: String): Int {
    for (i in 0 until ex.trackCount) {
        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
        if (mime != null && mime.startsWith(prefix)) return i
    }
    return -1
}

/**
 * Bitrate audio (bps) yang dipakai: pilihan pengguna, dibatasi ke batas encoder AAC
 * (sekitar 6144 bit per saluran per frame) agar encoder tidak menolak. Contoh: mono 320 Kbps
 * melebihi batas AAC-LC sehingga diturunkan otomatis.
 * [audioCodec]: 0 = AAC-LC, 1 = AAC_ELD, 2 = HE-AAC.
 */
fun effectiveAudioBitrate(kbps: Int, channels: Int, sampleRate: Int, audioCodec: Int): Int {
    val perChannelMax = when (audioCodec) {
        2 -> 6144.0 * (sampleRate / 2.0) / 1024.0 // HE-AAC: inti memakai separuh sample rate
        1 -> 6144.0 * sampleRate / 512.0          // AAC_ELD
        else -> 6144.0 * sampleRate / 1024.0      // AAC-LC
    }
    val max = (perChannelMax * channels * 0.98).toInt()
    return minOf(kbps * 1000, max)
}

/**
 * Filter mikrofon sederhana (ringan di CPU):
 * - high-pass ~90 Hz untuk membuang dengung rendah dan hembusan napas
 * - noise gate (opsional) yang meredam mikrofon saat tidak ada yang bicara
 * Sampel berskala int16 (-32768..32767), diproses per frame (satu sampel per saluran).
 */
private class MicFilter(sampleRate: Int, private val channels: Int, private val gate: Boolean) {
    private val hpA = (1.0 / (1.0 + 2.0 * Math.PI * 90.0 / sampleRate)).toFloat()
    private val prevX = FloatArray(channels)
    private val prevY = FloatArray(channels)
    private var env = 0f
    private var gain = 1f
    private var open = true
    private val envDecay = exp(-1.0 / (0.12 * sampleRate)).toFloat()
    private val attackCoef = (1.0 - exp(-1.0 / (0.005 * sampleRate))).toFloat()
    private val releaseCoef = (1.0 - exp(-1.0 / (0.08 * sampleRate))).toFloat()

    fun process(frame: FloatArray) {
        var peak = 0f
        for (c in 0 until channels) {
            val x = frame[c]
            val y = hpA * (prevY[c] + x - prevX[c])
            prevX[c] = x
            prevY[c] = y
            frame[c] = y
            val a = abs(y)
            if (a > peak) peak = a
        }
        if (!gate) return
        env = if (peak > env) peak else env * envDecay
        if (open && env < CLOSE_THRESHOLD) open = false
        else if (!open && env > OPEN_THRESHOLD) open = true
        val target = if (open) 1f else FLOOR_GAIN
        gain += (target - gain) * (if (target > gain) attackCoef else releaseCoef)
        for (c in 0 until channels) frame[c] *= gain
    }

    private companion object {
        const val OPEN_THRESHOLD = 500f   // sekitar -36 dBFS
        const val CLOSE_THRESHOLD = 300f  // sekitar -40 dBFS
        const val FLOOR_GAIN = 0.05f
    }
}

/**
 * Mode streamer: menurunkan volume suara game (menjadi 30%) saat mikrofon terdeteksi sedang bicara,
 * lalu menaikkannya perlahan setelah Anda diam.
 */
private class Ducker(sampleRate: Int) {
    private var env = 0f
    private var speaking = false
    private var gain = 1f
    private val envDecay = exp(-1.0 / (0.12 * sampleRate)).toFloat()
    private val attackCoef = (1.0 - exp(-1.0 / (0.02 * sampleRate))).toFloat()
    private val releaseCoef = (1.0 - exp(-1.0 / (0.3 * sampleRate))).toFloat()

    /** [peak]: level mikrofon pada frame ini (skala int16). Mengembalikan pengali volume game. */
    fun next(peak: Float): Float {
        env = if (peak > env) peak else env * envDecay
        if (!speaking && env > OPEN_THRESHOLD) speaking = true
        else if (speaking && env < CLOSE_THRESHOLD) speaking = false
        val target = if (speaking) DUCK_GAIN else 1f
        gain += (target - gain) * (if (target < gain) attackCoef else releaseCoef)
        return gain
    }

    private companion object {
        const val OPEN_THRESHOLD = 900f
        const val CLOSE_THRESHOLD = 500f
        const val DUCK_GAIN = 0.3f
    }
}

/**
 * Pengubah suara tanpa library tambahan.
 * Bayi, Wanita, Paman, Orang Tua, Monster: pengubah nada (pitch) real-time dengan dua titik baca
 * pada buffer lingkar yang saling memudar (jendela Hann, 40 ms). Robot: ring modulator + comb filter.
 * Sampel berskala int16, diproses per frame (satu sampel per saluran).
 */
private class VoiceChanger(sampleRate: Int, private val channels: Int, private val mode: Int) {
    private val ratio = when (mode) {
        VOICE_BABY -> 1.7f      // sekitar +9 semiton
        VOICE_WOMAN -> 1.26f    // sekitar +4 semiton
        VOICE_UNCLE -> 0.84f    // sekitar -3 semiton
        VOICE_OLD -> 0.93f      // sekitar -1,2 semiton, ditambah getar dan suara redup
        VOICE_MONSTER -> 0.6f   // sekitar -9 semiton, ditambah geraman dan distorsi
        else -> 1f
    }
    private val win = max(256, (0.04 * sampleRate).toInt())
    private val size: Int = run {
        var s = 1
        while (s < win * 2) s = s shl 1
        s
    }
    private val mask = size - 1
    private val bufs = Array(channels) { FloatArray(size) }
    private var w = 0
    private var p = 0f

    private val twoPi = (2.0 * Math.PI).toFloat()
    private var vibPhase = 0f
    private val vibInc = twoPi * 5.5f / sampleRate
    private var modPhase = 0f
    private val modInc = when (mode) {
        VOICE_ROBOT -> twoPi * 70f / sampleRate
        VOICE_MONSTER -> twoPi * 32f / sampleRate
        else -> 0f
    }
    private val lowPass = FloatArray(channels)
    private val lpA = (1.0 - exp(-2.0 * Math.PI * 3200.0 / sampleRate)).toFloat()
    private val combLen = max(8, (0.006 * sampleRate).toInt())
    private val comb = Array(channels) { FloatArray(combLen) }
    private var ci = 0

    private fun tap(b: FloatArray, pos: Float): Float {
        val fl = floor(pos)
        val i0 = fl.toInt()
        val frac = pos - fl
        val a = b[i0 and mask]
        val c = b[(i0 + 1) and mask]
        return a + (c - a) * frac
    }

    fun process(frame: FloatArray) {
        if (mode == VOICE_ROBOT) {
            val m = sin(modPhase)
            modPhase += modInc
            if (modPhase > twoPi) modPhase -= twoPi
            for (c in 0 until channels) {
                val x = frame[c]
                val y = (0.2f * x + 0.95f * x * m) * 1.4f + 0.45f * comb[c][ci]
                comb[c][ci] = y
                frame[c] = y
            }
            ci++
            if (ci >= combLen) ci = 0
            return
        }

        var r = ratio
        var vib = 0f
        if (mode == VOICE_OLD) {
            vib = sin(vibPhase)
            vibPhase += vibInc
            if (vibPhase > twoPi) vibPhase -= twoPi
            r *= 1f + 0.04f * vib
        }

        for (c in 0 until channels) bufs[c][w] = frame[c]
        p += (1f - r) / win
        if (p >= 1f) p -= 1f else if (p < 0f) p += 1f
        var pb = p + 0.5f
        if (pb >= 1f) pb -= 1f
        val ga = 0.5f - 0.5f * cos(twoPi * p)
        val gb = 1f - ga
        val da = p * win + 1f
        val db = pb * win + 1f
        for (c in 0 until channels) {
            frame[c] = ga * tap(bufs[c], w - da) + gb * tap(bufs[c], w - db)
        }
        w = (w + 1) and mask

        if (mode == VOICE_OLD) {
            val amp = 0.85f + 0.15f * vib
            for (c in 0 until channels) {
                lowPass[c] += lpA * (frame[c] - lowPass[c])
                frame[c] = lowPass[c] * amp
            }
        } else if (mode == VOICE_MONSTER) {
            val g = 0.65f + 0.35f * sin(modPhase)
            modPhase += modInc
            if (modPhase > twoPi) modPhase -= twoPi
            for (c in 0 until channels) {
                val x = frame[c] * g / 32768f
                frame[c] = tanh(x * 1.6f) * 32768f * 0.9f
            }
        }
    }
}
