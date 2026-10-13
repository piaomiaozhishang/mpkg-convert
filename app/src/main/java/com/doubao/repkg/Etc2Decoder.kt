package com.doubao.repkg

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes ETC2_EAC (Wallpaper Engine tex Format=5) using the GPU.
 *
 * Creates a headless OpenGL ES 3.0 context (pbuffer) on the calling thread,
 * uploads the compressed blocks as a GL_COMPRESSED_RGBA8_ETC2_EAC texture,
 * renders it through an FBO and reads back standard RGBA pixels.
 *
 * Must be created, [decode]d and [close]d all on the same background thread.
 */
class Etc2Decoder : Closeable {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val vertexShader = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() {
            // NO V flip here. glReadPixels returns rows starting at the window's
            // bottom-left, i.e. bottom-up; Bitmap row 0 is the top. Those two
            // already cancel out, so a plain UV mapping yields an upright bitmap.
            // (Flipping V here -- as an earlier version did -- double-flips and
            //  produces an upside-down export. A flip is only needed when the
            //  image is shown on screen, not when it is read back.)
            vUv = vec2(aPos.x * 0.5 + 0.5, aPos.y * 0.5 + 0.5);
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D sTex;
        void main() {
            gl_FragColor = texture2D(sTex, vUv);
        }
    """.trimIndent()

    init {
        createContext()
    }

    private fun createContext() {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) error("无法获取 EGL Display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) error("无法初始化 EGL")

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            || configs[0] == null
        ) {
            EGL14.eglTerminate(dpy)
            error("设备不支持 OpenGL ES 3.0 离屏上下文")
        }
        val config = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        val ctx = EGL14.eglCreateContext(dpy, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(dpy)
            error("无法创建 OpenGL ES 3.0 上下文")
        }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val surf = EGL14.eglCreatePbufferSurface(dpy, config, surfaceAttribs, 0)
        if (surf == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
            error("无法创建 EGL Pbuffer")
        }

        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglTerminate(dpy)
            error("无法绑定 EGL 上下文")
        }

        display = dpy; context = ctx; surface = surf
    }

    fun decode(etc2Data: ByteArray, width: Int, height: Int): Bitmap {
        // --- Upload the compressed ETC2 texture ---
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val texBuf = ByteBuffer.allocateDirect(etc2Data.size)
            .order(ByteOrder.nativeOrder())
            .put(etc2Data)
            .also { it.position(0) }
        GLES30.glCompressedTexImage2D(
            GLES20.GL_TEXTURE_2D, 0,
            GLES30.GL_COMPRESSED_RGBA8_ETC2_EAC,
            width, height, 0, etc2Data.size, texBuf
        )
        checkGlError("上传 ETC2 纹理")

        // --- Shader program ---
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vertexShader))
        GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader))
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            error("着色器程序链接失败: $log")
        }
        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)

        // --- FBO with an RGBA color texture ---
        val fboTex = IntArray(1)
        GLES20.glGenTextures(1, fboTex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTex[0], 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            error("FBO 不完整（尺寸 ${width}x${height} 可能超出设备限制）")
        }
        GLES20.glViewport(0, 0, width, height)

        // --- Draw a full-screen quad ---
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        val quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            .also { it.position(0) }
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("渲染 ETC2")

        // --- Read back pixels ---
        val pixelBuf = ByteBuffer.allocateDirect(width * height * 4)
            .order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf)
        checkGlError("读取像素")
        pixelBuf.position(0)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixelBuf)

        // --- Cleanup GL resources ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        GLES20.glDeleteTextures(1, tex, 0)
        GLES20.glDeleteTextures(1, fboTex, 0)
        GLES20.glDeleteProgram(program)

        return bitmap
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("着色器编译失败: $log")
        }
        return shader
    }

    private fun checkGlError(where: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) error("GL 错误 @$where: 0x${err.toString(16)}")
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        /** EGLExt.EGL_OPENGL_ES3_BIT_KHR. */
        const val EGLES3_BIT = 0x0040
    }
}
