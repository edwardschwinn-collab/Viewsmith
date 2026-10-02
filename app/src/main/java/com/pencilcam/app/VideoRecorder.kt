package com.pencilcam.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * VideoRecorder (Video mode)
 * Records the live sketch to an MP4 in Movies/ViewSmith.
 *
 * Android's MediaRecorder does the heavy lifting: H.264 video, AAC sound
 * from the microphone, and writing the file. Normally it would film the
 * camera directly. Here it's set to take its picture from a Surface
 * instead, and the renderer draws the finished sketch into that Surface,
 * so what gets recorded is the drawing, not the raw camera.
 * ============================================================================
 */
class VideoRecorder(private val ctx: Context) {
    /** The renderer draws into this while recording. */
    var surface: Surface? = null; private set
    private var mr: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    /**
     * Get everything ready, but don't start yet.
     * @param w,h      video frame size in pixels (the renderer draws to fit it)
     * @param rotation degrees clockwise a player should turn it (how the phone was held)
     * @param audio    record the microphone too?
     * @param bitrate  video bits per second (bigger sizes need more)
     */
    fun prepare(w: Int, h: Int, rotation: Int, audio: Boolean, bitrate: Int): Boolean {
        return try {
            // Make the gallery entry first. IS_PENDING keeps it hidden until it's finished.
            val name = "ViewSmith_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ViewSmith")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            pfd = ctx.contentResolver.openFileDescriptor(uri!!, "w") ?: return false

            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            // MediaRecorder is fussy about order: sources first, then the
            // file format, then encoders and their settings.
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(pfd!!.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, h)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(bitrate)
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(48_000)
                r.setAudioEncodingBitRate(128_000)
            }
            r.setOrientationHint(rotation)
            r.prepare()
            mr = r
            surface = r.surface
            true
        } catch (e: Exception) {
            Log.e("ViewSmith", "Recorder prepare failed", e); abandon(); false
        }
    }

    fun start() { mr?.start() }

    /** Stop and finish the file. Returns true if a video was saved. */
    fun stop(): Boolean {
        var ok = false
        // stop() throws if almost nothing was recorded (a tap-tap). Then there's no file to keep.
        try { mr?.stop(); ok = true } catch (e: Exception) { Log.w("ViewSmith", "stop failed (too short?)", e) }
        mr?.release(); mr = null; surface = null
        try { pfd?.close() } catch (_: Exception) {}
        uri?.let { u ->
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                ctx.contentResolver.update(u, v, null, null)
            } else ctx.contentResolver.delete(u, null, null)
        }
        return ok
    }

    /** Throw away a recording that never got going. */
    fun abandon() {
        try { mr?.release() } catch (_: Exception) {}
        mr = null; surface = null
        try { pfd?.close() } catch (_: Exception) {}
        uri?.let { ctx.contentResolver.delete(it, null, null) }
        uri = null
    }
}

/**
 * ============================================================================
 * EncoderCheck
 * Asks the phone what its H.264 video encoder can actually do, before
 * recording or saving, so I never hand it a size it will choke on. Every
 * phone is different: a Pixel, a Samsung, and a budget phone all report
 * different limits, so nothing here assumes a particular phone.
 *
 * Android lists every codec in MediaCodecList. I pick the first H.264
 * ENCODER, preferring a hardware one (the fast chip, not the slow software
 * fallback), and read its VideoCapabilities: allowed widths, heights,
 * frame rates, and bitrates.
 * ============================================================================
 */
object EncoderCheck {
    /** An encoder size that works. turned = the encoder only takes it wide, so feed the drawing sideways. */
    class Fit(val w: Int, val h: Int, val turned: Boolean)

    /** The capabilities of the phone's preferred H.264 encoder, or null if it can't be found. */
    fun avcCaps(): android.media.MediaCodecInfo.VideoCapabilities? = try {
        val all = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", ignoreCase = true) } }
        val pick = all.firstOrNull { it.isHardwareAccelerated } ?: all.firstOrNull()
        pick?.getCapabilitiesForType("video/avc")?.videoCapabilities
    } catch (e: Exception) { null }

    /**
     * Can the encoder take an upright w x h picture at fps frames per second?
     * Tries it as-is first, then sideways (some encoders only accept frames
     * wider than tall). Returns null if neither works. If the phone won't
     * say, assume it's fine and let the recorder try.
     */
    fun fit(w: Int, h: Int, fps: Int = 30): Fit? {
        val c = avcCaps() ?: return Fit(w and 1.inv(), h and 1.inv(), false)
        // Some encoders want sizes in multiples of 2, some of 16. Round down
        // to that first (1080 becomes 1072 on a multiple-of-16 encoder).
        fun down(v: Int, a: Int) = (v - v % a).coerceAtLeast(a)
        val uw = down(w, c.widthAlignment); val uh = down(h, c.heightAlignment)
        if (ok(c, uw, uh, fps)) return Fit(uw, uh, false)
        val sw = down(h, c.widthAlignment); val sh = down(w, c.heightAlignment)
        if (ok(c, sw, sh, fps)) return Fit(sw, sh, true)
        return null
    }

    private fun ok(c: android.media.MediaCodecInfo.VideoCapabilities, w: Int, h: Int, fps: Int): Boolean =
        try { c.isSizeSupported(w, h) && c.areSizeAndRateSupported(w, h, fps.toDouble()) }
        catch (e: Exception) { false }

    /**
     * Bitrate that scales with the picture: about 0.2 bits per pixel per
     * frame. Sketches are full of fine strokes and paper grain, which need
     * more bits than ordinary video to stay clean. That works out to about
     * 5.5 Mbps at 720p, 12 at 1080p, 22 at 1440p, and 50 at 4K, capped at
     * whatever the encoder says it can handle.
     */
    fun bitrate(w: Int, h: Int, fps: Int = 30): Int {
        val want = (w.toLong() * h * fps / 5).coerceIn(4_000_000L, 100_000_000L).toInt()
        val top = try { avcCaps()?.bitrateRange?.upper } catch (e: Exception) { null } ?: return want
        return want.coerceAtMost(top)
    }
}
