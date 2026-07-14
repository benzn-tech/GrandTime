package com.benzn.grandtime.capture.camera2

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 相机外部 OES 纹理 → GL 透传绘制到 N 个目标 Surface(编码器输入面 + 可选预览面)。
 * 目标增删/绘制全在自有 GL 线程串行执行,故预览挂/摘不动相机会话、不中断编码器喂帧。
 */
class GlRecordPipeline {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTexId = 0
    private var program = 0
    private var uTexMatrix = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var cameraTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)
    private val targets = LinkedHashMap<Surface, EGLSurface>()

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val texQuad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    fun start(cameraW: Int, cameraH: Int, onCameraTextureReady: (SurfaceTexture) -> Unit) {
        thread = HandlerThread("gl-record").apply { start() }
        handler = Handler(thread.looper)
        handler.post {
            initEgl()
            oesTexId = createOesTexture()
            program = buildProgram()
            val st = SurfaceTexture(oesTexId).apply {
                setDefaultBufferSize(cameraW, cameraH)
                setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
            }
            cameraTexture = st
            onCameraTextureReady(st)
        }
    }

    fun addTarget(surface: Surface) = handler.post {
        if (targets.containsKey(surface)) return@post
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val win = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        targets[surface] = win
    }

    fun removeTarget(surface: Surface) = handler.post {
        targets.remove(surface)?.let { EGL14.eglDestroySurface(eglDisplay, it) }
    }

    private fun drawFrame() {
        val st = cameraTexture ?: return
        runCatching { st.updateTexImage() }
        st.getTransformMatrix(stMatrix)
        for ((_, win) in targets) {
            if (win == EGL14.EGL_NO_SURFACE) continue
            EGL14.eglMakeCurrent(eglDisplay, win, win, eglContext)
            val ww = IntArray(1); EGL14.eglQuerySurface(eglDisplay, win, EGL14.EGL_WIDTH, ww, 0)
            val hh = IntArray(1); EGL14.eglQuerySurface(eglDisplay, win, EGL14.EGL_HEIGHT, hh, 0)
            GLES20.glViewport(0, 0, ww[0], hh[0])
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texQuad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
            EGL14.eglSwapBuffers(eglDisplay, win)
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val cfgAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, cfgs, 0, 1, num, 0)
        eglConfig = cfgs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec4 aPosition; attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix; varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = (uTexMatrix * aTexCoord).xy; }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        aPosition = GLES20.glGetAttribLocation(p, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(p, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(p, "uTexMatrix")
        return p
    }

    fun release() {
        handler.post {
            for ((_, win) in targets) EGL14.eglDestroySurface(eglDisplay, win)
            targets.clear()
            cameraTexture?.release(); cameraTexture = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY   // 幂等:二次 release 时上面 if 为假,不重复销毁
            eglContext = EGL14.EGL_NO_CONTEXT
            pbuffer = EGL14.EGL_NO_SURFACE
            thread.quitSafely()
        }
    }
}
