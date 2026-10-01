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
 * VideoRecorder
 * Wraps Android's MediaRecorder. We DRAW into its input Surface with OpenGL
 * (the sketch), and it handles the hard parts: H.264 video, AAC microphone
 * audio, and writing the MP4 into Movies/PencilCam in your gallery.
 * ============================================================================
 */
class VideoRecorder(private val ctx: Context) {
    var surface: Surface? = null; private set
    private var mr: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    /**
     * @param w,h      video size in pixels (must match what the GPU draws)
     * @param rotation degrees clockwise the player should turn it (how you held the phone)
     * @param audio    record the microphone too?
     */
    fun prepare(w: Int, h: Int, rotation: Int, audio: Boolean): Boolean {
        return try {
            val name = "PencilCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PencilCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            pfd = ctx.contentResolver.openFileDescriptor(uri!!, "w") ?: return false

            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            // ORDER MATTERS with MediaRecorder: sources, then format, then encoders/settings
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
            Log.e("PencilCam", "Recorder prepare failed", e); abandon(); false
        }
    }

    fun start() { mr?.start() }

    /** Stops and finalizes the file. Returns true if a video was saved. */
    fun stop(): Boolean {
        var ok = false
        try { mr?.stop(); ok = true } catch (e: Exception) { Log.w("PencilCam", "stop failed (too short?)", e) }
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

    /** Throw away a recording that never started. */
    fun abandon() {
        try { mr?.release() } catch (_: Exception) {}
        mr = null; surface = null
        try { pfd?.close() } catch (_: Exception) {}
        uri?.let { ctx.contentResolver.delete(it, null, null) }
        uri = null
    }
}
