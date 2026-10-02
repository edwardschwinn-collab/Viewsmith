package com.pencilcam.app

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * ============================================================================
 * Picks a GPU drawing setup that is also allowed to feed the video encoder
 * ("recordable"). Without this, drawing into the recorder's surface fails.
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
