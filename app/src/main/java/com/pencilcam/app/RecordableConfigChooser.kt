package com.pencilcam.app

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * ============================================================================
 * RecordableConfigChooser
 * When GLSurfaceView starts up it has to pick a pixel format for the GPU
 * (an "EGL config"). This picks one that is also marked "recordable", which
 * means it can draw into a video encoder as well as the screen. Without it,
 * recording and saving videos fail on a lot of phones.
 *
 * Asks for 8 bits each of red, green, blue, and alpha with OpenGL ES 3.
 * If no recordable one exists, it falls back to the same thing without
 * the recordable flag, so the app still runs (just without video).
 * ============================================================================
 */
class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val EGL_RECORDABLE_ANDROID = 0x3142
        val EGL_OPENGL_ES3_BIT = 0x40
        val base = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT
        )
        for (attribs in listOf(base + intArrayOf(EGL_RECORDABLE_ANDROID, 1, EGL10.EGL_NONE), base + intArrayOf(EGL10.EGL_NONE))) {
            val configs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
            if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0 && configs[0] != null) return configs[0]!!
        }
        throw IllegalStateException("No usable EGL config")
    }
}
