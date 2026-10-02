package com.pencilcam.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================================
 * Video files in File mode
 *
 * Three pieces live here:
 *   VideoInfo     : reads a clip's size, rotation, and length
 *   VideoPlayer   : loops the clip on screen (no sound) so you can dial in the look
 *   VideoExporter : saves a sketched copy of the clip, sound included
 *
 * How a video gets drawn: Android's decoder (MediaCodec) unpacks the clip
 * one frame at a time and sends each frame into a Surface the renderer
 * gave us. From there it's the same as the camera: the renderer turns the
 * frame upright, runs the sketch passes, and puts it on screen.
 *
 * How saving works: playback on screen runs on the wall clock and can skip
 * frames. Saving can't skip anything, so the exporter works in lock step.
 * It sends one frame, waits until the renderer has drawn it into the
 * encoder, then sends the next. The phone works through it as fast as it
 * can, which may be slower or faster than real time. Each saved frame keeps
 * its original time stamp, so the result plays at the right speed. The
 * sound is copied straight across from the original without touching it.
 * ============================================================================
 */

/** Size, rotation, and length of a video clip. */
data class VideoInfo(val width: Int, val height: Int, val rotation: Int, val durationUs: Long) {
    companion object {
        /** Read the basics of a clip. Returns null if it isn't a video we can open. */
        fun read(ctx: Context, uri: Uri): VideoInfo? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(ctx, uri)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (w <= 0 || h <= 0) null else VideoInfo(w, h, rot, ms * 1000)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read video info", e); null
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }
    }
}

private const val TAG = "ViewSmith"

/** Find the first track whose type starts with prefix ("video/" or "audio/"). -1 if none. */
private fun findTrack(ex: MediaExtractor, prefix: String): Int {
    for (i in 0 until ex.trackCount) {
        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(prefix)) return i
    }
    return -1
}

/**
 * Set up a decoder for the clip's video track, sending frames to surface.
 * Rotation is forced to 0 here because the renderer turns the picture
 * upright itself. If the decoder did it too, the picture would turn twice.
 */
private fun openDecoder(ex: MediaExtractor, track: Int, surface: Surface): MediaCodec {
    val fmt = ex.getTrackFormat(track)
    fmt.setInteger(MediaFormat.KEY_ROTATION, 0)
    val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    dec.configure(fmt, surface, null, 0)
    dec.start()
    return dec
}

/** Hand the decoder one more chunk of the file. Returns true once the end of the file is reached. */
private fun feedDecoder(dec: MediaCodec, ex: MediaExtractor): Boolean {
    val i = dec.dequeueInputBuffer(10_000)
    if (i < 0) return false
    val buf = dec.getInputBuffer(i) ?: return false
    val n = ex.readSampleData(buf, 0)
    return if (n < 0) {
        dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); true
    } else {
        dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance(); false
    }
}

// ============================================================================
// VideoPlayer: loops a clip on screen, paced to real time
// ============================================================================
class VideoPlayer(private val ctx: Context, private val uri: Uri, private val surface: Surface) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "ViewSmith-player").also { it.start() }
    }

    /** Stop and wait for the decoder to let go of the Surface. */
    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    private fun loop() {
        var ex: MediaExtractor? = null
        var dec: MediaCodec? = null
        try {
            ex = MediaExtractor().apply { setDataSource(ctx, uri, null) }
            val track = findTrack(ex, "video/")
            if (track < 0) return
            ex.selectTrack(track)
            dec = openDecoder(ex, track, surface)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var baseNs = -1L        // wall clock time when the first frame of this pass was shown
            var basePts = 0L        // that frame's own time stamp
            while (running) {
                if (!inputDone) inputDone = feedDecoder(dec, ex)
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (info.size > 0) {
                    // Hold each frame until its moment comes, so it plays at normal speed
                    if (baseNs < 0) { baseNs = System.nanoTime(); basePts = info.presentationTimeUs }
                    val due = baseNs + (info.presentationTimeUs - basePts) * 1000
                    var wait = due - System.nanoTime()
                    while (running && wait > 0) {
                        Thread.sleep(min(wait / 1_000_000, 20L).coerceAtLeast(1L))
                        wait = due - System.nanoTime()
                    }
                    dec.releaseOutputBuffer(o, running)          // true = show it
                } else dec.releaseOutputBuffer(o, false)
                if (eos) {
                    // End of the clip: rewind and go again
                    ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    dec.flush()
                    inputDone = false; baseNs = -1L
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video playback stopped", e)
        } finally {
            try { dec?.stop() } catch (_: Exception) {}
            try { dec?.release() } catch (_: Exception) {}
            try { ex?.release() } catch (_: Exception) {}
        }
    }
}

// ============================================================================
// VideoExporter: saves a sketched copy of the clip to Movies/ViewSmith
// ============================================================================
/**
 * @param decodeSurface the renderer's video Surface (the player must be stopped first)
 * @param procW,procH   the size the renderer draws this clip at while saving
 * @param short         that size's short side (the Video resolution setting, or the clip's own if smaller)
 * @param onProgress    0 to 100, called on the main thread
 * @param onDone        (saved?, message for the user), called on the main thread
 */
class VideoExporter(
    private val ctx: Context,
    private val uri: Uri,
    private val decodeSurface: Surface,
    private val renderer: SketchRenderer,
    private val procW: Int,
    private val procH: Int,
    private val short: Int,
    private val durationUs: Long,
    private val onProgress: (Int) -> Unit,
    private val onDone: (Boolean, String) -> Unit
) {
    @Volatile private var cancelled = false            // you tapped the dial to stop the save
    @Volatile private var finished = false             // the save is wrapping up, drain thread should quit
    private val main = Handler(Looper.getMainLooper())

    // Shared between the export thread and the encoder drain thread
    private var muxer: MediaMuxer? = null
    @Volatile private var muxerStarted = false
    @Volatile private var videoOut = -1
    @Volatile private var audioOut = -1
    @Volatile private var videoSamples = 0
    @Volatile private var drainError: Exception? = null

    fun start() { Thread({ run() }, "ViewSmith-export").start() }
    /** Stop the save. The half-made file gets thrown away. */
    fun cancel() { cancelled = true }

    private fun run() {
        var vEx: MediaExtractor? = null
        var aEx: MediaExtractor? = null
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var encSurface: Surface? = null
        var outUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var drain: Thread? = null
        var rendererHooked = false
        var ok = false
        var lostSound = false

        try {
            // ---- 1. Open the clip's video track
            vEx = MediaExtractor().apply { setDataSource(ctx, uri, null) }
            val vTrack = findTrack(vEx, "video/")
            if (vTrack < 0) throw IllegalStateException("no video track")
            vEx.selectTrack(vTrack)
            val vFmt = vEx.getTrackFormat(vTrack)
            val fps = frameRate(vFmt)

            // ---- 2. Set up the H.264 encoder. It takes its input from a Surface
            // that the renderer draws into.
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val caps = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            val size = pickEncoderSize(caps, procW, procH)
            // Bitrate scales with the size, same rule as recording (see EncoderCheck.bitrate)
            val top = min(100_000_000, caps.bitrateRange.upper)
            val bitrate = (size.width.toLong() * size.height * fps / 5).coerceIn(min(4_000_000, top).toLong(), top.toLong()).toInt()
            val eFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(eFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encSurface = enc.createInputSurface()
            enc.start()

            // ---- 3. Make the new file in the gallery. IS_PENDING hides it until it's finished.
            val name = "ViewSmith_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ViewSmith")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outUri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("no output file")
            pfd = ctx.contentResolver.openFileDescriptor(outUri, "rw") ?: throw IllegalStateException("can't open output")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ---- 4. Find the sound track, if there is one
            aEx = MediaExtractor().apply { setDataSource(ctx, uri, null) }
            val aTrack = findTrack(aEx, "audio/")
            val aFmt = if (aTrack >= 0) { aEx.selectTrack(aTrack); aEx.getTrackFormat(aTrack) } else null

            // ---- 5. Point the renderer at the encoder
            val frames = LinkedBlockingQueue<Long>()
            val ready = CountDownLatch(1)
            var readyOk = false
            renderer.startExport(encSurface, size.width, size.height, short,
                onFrame = { ns -> frames.offer(ns) },
                onReady = { good -> readyOk = good; ready.countDown() })
            if (!ready.await(3, TimeUnit.SECONDS) || !readyOk) throw IllegalStateException("GPU couldn't connect to the encoder")
            rendererHooked = true

            // ---- 6. A second thread collects the encoder's output and writes it to the file
            val encoder = enc
            drain = Thread({ drainEncoder(encoder, aFmt) }, "ViewSmith-drain").also { it.start() }

            // ---- 7. Decode the clip one frame at a time, waiting for each to be drawn
            dec = openDecoder(vEx, vTrack, decodeSurface)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var firstPts = -1L
            var lastPct = -1
            while (!cancelled && drainError == null) {
                if (!inputDone) inputDone = feedDecoder(dec, vEx)
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (info.size > 0) {
                    val ns = info.presentationTimeUs * 1000
                    frames.clear()
                    renderer.exportExpectNs = ns
                    dec.releaseOutputBuffer(o, true)
                    // Wait until the renderer says this frame went into the encoder.
                    // If it never shows (a dropped frame), move on after 3 seconds.
                    val deadline = System.nanoTime() + 3_000_000_000L
                    while (!cancelled) {
                        val got = frames.poll(200, TimeUnit.MILLISECONDS)
                        if (got == ns || System.nanoTime() > deadline) break
                    }
                    if (firstPts < 0) firstPts = info.presentationTimeUs
                    if (durationUs > 0) {
                        val pct = ((info.presentationTimeUs - firstPts) * 100 / durationUs).toInt().coerceIn(0, 99)
                        if (pct != lastPct) { lastPct = pct; main.post { onProgress(pct) } }
                    }
                } else dec.releaseOutputBuffer(o, false)
                if (eos) break
            }
            renderer.exportExpectNs = -1L
            drainError?.let { throw it }
            if (cancelled) throw InterruptedException("canceled")

            // ---- 8. Tell the encoder that's the last frame, and wait for it to finish writing
            enc.signalEndOfInputStream()
            drain.join(15_000)
            drainError?.let { throw it }
            if (!muxerStarted || videoSamples == 0) throw IllegalStateException("no video frames were written")

            // ---- 9. Unhook the renderer from the encoder
            stopRenderer(); rendererHooked = false

            // ---- 10. Copy the sound across as-is
            if (aFmt != null) {
                if (audioOut >= 0) copyAudio(aEx, aFmt) else lostSound = true
            }
            muxer?.stop()
            ok = !cancelled
        } catch (e: InterruptedException) {
            // canceled: the cleanup below throws the half-made file away
        } catch (e: Exception) {
            Log.e(TAG, "Video save failed", e)
        } finally {
            finished = true                                // stops the drain thread if it's still going
            renderer.exportExpectNs = -1L
            if (rendererHooked) stopRenderer()
            try { drain?.join(2000) } catch (_: Exception) {}
            try { dec?.stop() } catch (_: Exception) {}
            try { dec?.release() } catch (_: Exception) {}
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            try { encSurface?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { vEx?.release() } catch (_: Exception) {}
            try { aEx?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            outUri?.let { u ->
                try {
                    if (ok) {
                        val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        ctx.contentResolver.update(u, v, null, null)
                    } else ctx.contentResolver.delete(u, null, null)
                } catch (_: Exception) {}
            }
        }

        val msg = when {
            ok && lostSound -> "Saved to Movies/ViewSmith (this clip's sound format couldn't be copied, so it's silent)"
            ok -> "Saved to Movies/ViewSmith"
            cancelled -> "Video save canceled."
            else -> "Couldn't save the video."
        }
        main.post { onDone(ok, msg) }
    }

    /** Unhook the renderer from the encoder and wait (briefly) until it has. */
    private fun stopRenderer() {
        val done = CountDownLatch(1)
        renderer.stopExport { done.countDown() }
        done.await(2, TimeUnit.SECONDS)
    }

    /**
     * Runs on its own thread: pull finished chunks out of the encoder and
     * write them to the file. The file can only be started once the encoder
     * reports its exact output format, so the tracks are added here.
     */
    private fun drainEncoder(enc: MediaCodec, aFmt: MediaFormat?) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!cancelled && !finished) {
                val o = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val m = muxer!!
                        videoOut = m.addTrack(enc.outputFormat)
                        // Some sound formats can't go in an MP4. Then the video saves without sound.
                        if (aFmt != null) audioOut = try { m.addTrack(aFmt) } catch (e: Exception) { -1 }
                        m.start(); muxerStarted = true
                    }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)
                        // "Codec config" chunks are setup data the muxer already got with the format
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (buf != null && info.size > 0 && muxerStarted) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer!!.writeSampleData(videoOut, buf, info)
                            videoSamples++
                        }
                        enc.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        } catch (e: Exception) {
            drainError = e
        }
    }

    /** Copy every sound chunk from the original clip into the new file, unchanged. */
    private fun copyAudio(aEx: MediaExtractor, aFmt: MediaFormat) {
        val m = muxer ?: return
        val cap = if (aFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) aFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        val buf = ByteBuffer.allocateDirect(max(cap, 256 * 1024))
        val info = MediaCodec.BufferInfo()
        aEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            val n = aEx.readSampleData(buf, 0)
            if (n < 0) break
            val flags = if ((aEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, n, aEx.sampleTime, flags)
            m.writeSampleData(audioOut, buf, info)
            aEx.advance()
        }
    }

    /** The clip's frame rate, or 30 if it doesn't say. Some files store it as a whole number, some as a decimal. */
    private fun frameRate(f: MediaFormat): Int {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 30
        val r = try { f.getInteger(MediaFormat.KEY_FRAME_RATE) }
                catch (e: Exception) { try { f.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() } catch (_: Exception) { 30 } }
        return r.coerceIn(1, 60)
    }

    /**
     * The encoder size: same as the drawing if the encoder accepts it,
     * otherwise shrink 20% at a time (same shape) until it does.
     */
    private fun pickEncoderSize(caps: MediaCodecInfo.VideoCapabilities, w0: Int, h0: Int): Size {
        var w = w0; var h = h0
        repeat(8) {
            val aw = w - w % caps.widthAlignment
            val ah = h - h % caps.heightAlignment
            if (aw > 0 && ah > 0 && caps.isSizeSupported(aw, ah)) return Size(aw, ah)
            w = (w * 0.8f).toInt(); h = (h * 0.8f).toInt()
        }
        return Size(w0, h0)
    }
}
