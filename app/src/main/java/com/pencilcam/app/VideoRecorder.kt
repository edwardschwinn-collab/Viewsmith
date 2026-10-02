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
     * @param w,h      video size in pixels (has to match what the renderer draws)
     * @param rotation degrees clockwise a player should turn it (how the phone was held)
     * @param audio    record the microphone too?
     */
    fun prepare(w: Int, h: Int, rotation: Int, audio: Boolean): Boolean {
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
            r.setVideoEncodingBitRate(10_000_000)
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
