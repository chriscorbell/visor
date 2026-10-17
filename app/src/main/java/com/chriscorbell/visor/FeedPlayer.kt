package com.chriscorbell.visor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

enum class FeedState { CONNECTING, LIVE, RECONNECTING }

/**
 * Copied from camview's Desk display app (github.com/chriscorbell/camview).
 *
 * Plays the Relay's desk stream: fragmented MP4 over HTTP, decoded straight to
 * a Surface.
 *
 * The Camera stamps frames when it sends them, not when it captures them, so
 * after every Keyframe stall a burst arrives stamped almost together. Frames
 * are therefore paced by their own arrival rate instead of their timestamps,
 * behind a buffer that grows just enough to cover the worst recent stall and
 * slowly shrinks back when there's slack.
 */
class FeedPlayer(
    private val url: String,
    private val listener: Listener,
    /** visor shows the Camera silently, so it never decodes the sound at all. */
    private val playAudio: Boolean = true,
) {
    interface Listener {
        fun onState(state: FeedState)
        fun onVideoSize(width: Int, height: Int)
    }

    @Volatile var muted = true
        set(value) {
            field = value
            audioHandler.post { audioTrack?.setVolume(if (value) 0f else 1f) }
        }

    private val videoThread = HandlerThread("video").apply { start() }
    private val videoHandler = Handler(videoThread.looper)
    private val audioThread = HandlerThread("audio").apply { start() }
    private val audioHandler = Handler(audioThread.looper)

    @Volatile private var running = false
    private var network: Thread? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var state = FeedState.CONNECTING

    // Video, owned by videoThread.
    private var surface: Surface? = null
    private var videoFormat: MediaFormat? = null
    private var videoCodec: MediaCodec? = null
    private val pendingVideo = ArrayDeque<Fmp4Reader.Sample>()
    private val freeInputs = ArrayDeque<Int>()
    private val pacer = Pacer()

    // Audio, owned by audioThread.
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val pendingAudio = ArrayDeque<Fmp4Reader.Sample>()
    private val freeAudioInputs = ArrayDeque<Int>()
    private var audioGeneration = 0
    private var audioFramesWritten = 0L
    private var audioFrameBytes = 2
    private var audioRate = 16000

    fun attach(surface: Surface) {
        videoHandler.post {
            this.surface = surface
            videoFormat?.let { configureVideo(it) }
        }
    }

    fun detach() {
        // The surface dies when this returns, so the codec must let go of it first.
        val done = java.util.concurrent.CountDownLatch(1)
        videoHandler.post {
            releaseVideo()
            surface = null
            done.countDown()
        }
        done.await()
    }

    fun start() {
        if (running) return
        running = true
        network = Thread(::run, "feed").apply { start() }
    }

    fun stop() {
        running = false
        connection?.disconnect()
        network?.interrupt()
        network = null
        audioHandler.post { releaseAudio() }
    }

    fun release() {
        stop()
        videoHandler.post { releaseVideo() }
        videoThread.quitSafely()
        audioThread.quitSafely()
    }

    // ---- network ---------------------------------------------------------------

    private fun run() {
        var failures = 0
        while (running) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = FIRST_FRAME_MS
                    useCaches = false
                }
                connection = conn
                lastDataAt = System.nanoTime()
                gotFrame = false
                videoHandler.postDelayed(watchdog, 500)
                val reader = Fmp4Reader(conn.inputStream)
                val tracks = reader.readInit()
                val video = tracks.firstOrNull { it.format.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                val audio = if (!playAudio) null
                else tracks.firstOrNull { it.format.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                videoHandler.post { startVideo(video?.format) }
                audioHandler.post { startAudio(audio?.format) }
                var first = true
                while (running) {
                    val sample = reader.next()
                    if (sample.track === video) {
                        lastDataAt = System.nanoTime()
                        if (first && !sample.isKey) continue // decoding must start at a keyframe
                        first = false
                        gotFrame = true
                        failures = 0
                        videoHandler.post { queueVideo(sample) }
                    } else if (sample.track === audio) {
                        audioHandler.post { queueAudio(sample) }
                    }
                }
            } catch (e: IOException) {
                if (running) Log.i(TAG, "Feed dropped: ${e.message}")
            } catch (e: RuntimeException) {
                if (running) Log.w(TAG, "Feed failed", e)
            } finally {
                videoHandler.removeCallbacks(watchdog)
                connection?.disconnect()
                connection = null
            }
            if (!running) break
            if (state == FeedState.LIVE) setState(FeedState.RECONNECTING)
            try {
                Thread.sleep(RETRY_MS[minOf(failures++, RETRY_MS.lastIndex)])
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    @Volatile private var lastDataAt = 0L
    @Volatile private var gotFrame = false

    /**
     * Drops a connection whose video has gone quiet. A Keyframe stall is
     * ~0.3 s, so 2.5 s of silence is an outage; a new connection gets longer,
     * since it waits for the stream's next keyframe.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val limitMs = if (gotFrame) STALL_MS else FIRST_FRAME_MS
            if ((System.nanoTime() - lastDataAt) / 1_000_000 > limitMs) connection?.disconnect()
            else videoHandler.postDelayed(this, 500)
        }
    }

    @Synchronized
    private fun setState(next: FeedState) {
        if (state == next) return
        state = next
        listener.onState(next)
    }

    // ---- video -------------------------------------------------------------------

    private fun startVideo(format: MediaFormat?) {
        pendingVideo.clear()
        pacer.reset()
        if (format == null) return
        val old = videoFormat
        videoFormat = format
        // Same stream shape: keep the decoder, so the last frame stays on screen
        // until the new connection's first keyframe replaces it.
        if (videoCodec == null || old == null || !sameVideo(old, format)) configureVideo(format)
    }

    private fun configureVideo(format: MediaFormat) {
        releaseVideo()
        val surface = surface ?: return
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        // This tablet's decoders accept 4K at configure() and then crash on the
        // first frame, so ask first.
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        if (name == null) {
            Log.e(TAG, "No decoder for $mime ${width}x$height")
            return
        }
        val codec = try {
            MediaCodec.createByCodecName(name)
        } catch (e: Exception) {
            Log.w(TAG, "Video decoder unavailable", e)
            connection?.disconnect()
            return
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (codec !== videoCodec) return
                freeInputs.addLast(index)
                feedVideo()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (codec !== videoCodec) return
                codec.releaseOutputBuffer(index, pacer.renderAt(System.nanoTime()))
                setState(FeedState.LIVE)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                listener.onVideoSize(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Video decoder failed", e)
                if (codec !== videoCodec) return
                releaseVideo()
                connection?.disconnect()
            }
        }, videoHandler)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        try {
            videoCodec = codec
            codec.configure(format, surface, null, 0)
            codec.start()
        } catch (e: RuntimeException) {
            // Another app can hold the hardware decoder; try again on the next connection.
            Log.w(TAG, "Video decoder didn't start", e)
            releaseVideo()
            connection?.disconnect()
            return
        }
        listener.onVideoSize(width, height)
    }

    private fun queueVideo(sample: Fmp4Reader.Sample) {
        pendingVideo.addLast(sample)
        feedVideo()
    }

    private fun feedVideo() {
        val codec = videoCodec ?: return pendingVideo.clear()
        try {
            while (pendingVideo.isNotEmpty() && freeInputs.isNotEmpty()) {
                val sample = pendingVideo.removeFirst()
                val index = freeInputs.removeFirst()
                codec.getInputBuffer(index)!!.put(sample.data)
                pacer.arrived(System.nanoTime())
                codec.queueInputBuffer(index, 0, sample.data.size, sample.timeUs, 0)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Video decoder rejected a frame", e)
            releaseVideo()
            connection?.disconnect()
        }
    }

    private fun releaseVideo() {
        freeInputs.clear()
        videoCodec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        videoCodec = null
    }

    private fun sameVideo(a: MediaFormat, b: MediaFormat) =
        a.getString(MediaFormat.KEY_MIME) == b.getString(MediaFormat.KEY_MIME) &&
            a.getInteger(MediaFormat.KEY_WIDTH) == b.getInteger(MediaFormat.KEY_WIDTH) &&
            a.getInteger(MediaFormat.KEY_HEIGHT) == b.getInteger(MediaFormat.KEY_HEIGHT) &&
            a.getByteBuffer("csd-0") == b.getByteBuffer("csd-0")

    /**
     * Schedules decoded frames at a steady cadence behind an adaptive buffer.
     * A frame that isn't ready by its slot is shown the moment it is, and the
     * schedule shifts back by the shortfall. When every frame in a window had
     * spare time, playback runs a hair fast until the spare time is gone.
     */
    private class Pacer {
        private var lastRenderAt = 0L
        private var intervalNs = 40_000_000L
        private var lastArrival = 0L
        private var windowStart = 0L
        private var windowMinSlack = Long.MAX_VALUE
        private var speedupNs = 0L

        fun reset() {
            lastRenderAt = 0L
            lastArrival = 0L
            windowStart = 0L
            windowMinSlack = Long.MAX_VALUE
            speedupNs = 0L
        }

        /** Tracks the true frame rate from arrivals; bursts and stalls average out. */
        fun arrived(now: Long) {
            if (lastArrival != 0L) {
                val gap = (now - lastArrival).coerceIn(0L, 250_000_000L)
                intervalNs += (gap - intervalNs) / 128
            }
            lastArrival = now
        }

        fun renderAt(ready: Long): Long {
            val slot = if (lastRenderAt == 0L) ready + START_BUFFER_NS else lastRenderAt + intervalNs - speedupNs
            val at = max(slot, ready)
            val slack = at - ready
            if (windowStart == 0L) windowStart = ready
            windowMinSlack = minOf(windowMinSlack, slack)
            if (ready - windowStart > WINDOW_NS) {
                if (BuildConfig.DEBUG) Log.i(TAG, "buffer ${(lastRenderAt - ready + intervalNs) / 1_000_000} ms, min slack ${windowMinSlack / 1_000_000} ms, frame ${intervalNs / 100_000 / 10.0} ms")
                speedupNs = if (windowMinSlack > MARGIN_NS) SPEEDUP_NS else 0L
                windowStart = ready
                windowMinSlack = Long.MAX_VALUE
            }
            lastRenderAt = at
            return at
        }

        private companion object {
            const val START_BUFFER_NS = 100_000_000L
            const val WINDOW_NS = 4_000_000_000L
            const val MARGIN_NS = 20_000_000L
            const val SPEEDUP_NS = 2_000_000L
        }
    }

    // ---- audio -------------------------------------------------------------------

    private fun startAudio(format: MediaFormat?) {
        releaseAudio()
        if (format == null) return
        val codec = runCatching { MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!) }
            .getOrElse { Log.w(TAG, "No audio decoder", it); return }
        val gen = ++audioGeneration
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (gen != audioGeneration) return
                freeAudioInputs.addLast(index)
                feedAudio()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (gen != audioGeneration) return
                val pcm = codec.getOutputBuffer(index)!!
                writeAudio(pcm, info.size)
                codec.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = openTrack(format)

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(TAG, "Audio decoder failed", e)
            }
        }, audioHandler)
        try {
            audioCodec = codec
            codec.configure(format, null, null, 0)
            codec.start()
            openTrack(format)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Audio didn't start; playing without it", e)
            releaseAudio()
        }
    }

    private fun openTrack(format: MediaFormat) {
        audioTrack?.release()
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(channels).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            // Room for a Keyframe stall's worth of audio arriving in a burst.
            .setBufferSizeInBytes(rate * 2 * (if (channels == AudioFormat.CHANNEL_OUT_MONO) 1 else 2))
            .build()
        track.setVolume(if (muted) 0f else 1f)
        track.play()
        audioTrack = track
        audioRate = rate
        audioFrameBytes = 2 * (if (channels == AudioFormat.CHANNEL_OUT_MONO) 1 else 2)
        audioFramesWritten = 0
        // Video waits behind a buffer that covers the Keyframe stall, so start
        // the sound the same distance behind to keep them in step.
        val lead = ByteArray((rate * AUDIO_DELAY_MS / 1000) * audioFrameBytes)
        audioFramesWritten += track.write(lead, 0, lead.size, AudioTrack.WRITE_NON_BLOCKING) / audioFrameBytes
    }

    private fun writeAudio(pcm: java.nio.ByteBuffer, size: Int) {
        val track = audioTrack ?: return
        // After a long stall the backlog would put sound behind the picture; drop it.
        val queued = audioFramesWritten - (track.playbackHeadPosition.toLong() and 0xffffffffL)
        if (queued > audioRate * AUDIO_MAX_MS / 1000) return
        audioFramesWritten += track.write(pcm, size, AudioTrack.WRITE_NON_BLOCKING) / audioFrameBytes
    }

    private fun queueAudio(sample: Fmp4Reader.Sample) {
        pendingAudio.addLast(sample)
        feedAudio()
    }

    private fun feedAudio() {
        val codec = audioCodec ?: return pendingAudio.clear()
        try {
            while (pendingAudio.isNotEmpty() && freeAudioInputs.isNotEmpty()) {
                val sample = pendingAudio.removeFirst()
                val index = freeAudioInputs.removeFirst()
                codec.getInputBuffer(index)!!.put(sample.data)
                codec.queueInputBuffer(index, 0, sample.data.size, sample.timeUs, 0)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Audio decoder rejected a frame; playing without sound", e)
            releaseAudio()
        }
    }

    private fun releaseAudio() {
        audioGeneration++
        pendingAudio.clear()
        freeAudioInputs.clear()
        audioCodec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioCodec = null
        audioTrack?.release()
        audioTrack = null
    }

    private companion object {
        const val TAG = "camview.feed"
        const val STALL_MS = 2500
        const val FIRST_FRAME_MS = 8000
        const val AUDIO_DELAY_MS = 300
        const val AUDIO_MAX_MS = 700
        val RETRY_MS = longArrayOf(500, 1000, 2000, 5000)
    }
}
