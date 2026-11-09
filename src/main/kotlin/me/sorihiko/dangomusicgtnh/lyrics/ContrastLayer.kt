package me.sorihiko.dangomusicgtnh.lyrics

import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.gfx.ContrastPass
import me.sorihiko.dangomusicgtnh.legacy.LegacyGfx
import me.sorihiko.dangomusicgtnh.legacy.mc
import me.sorihiko.dangomusicgtnh.ui.glass.GlassBackdrop
import me.sorihiko.dangomusicgtnh.ui.glass.GlassTarget
import me.sorihiko.dangomusicgtnh.utils.render.GlCaps

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GLContext

/**
 * 近似色自动反色（3D 歌词用）：
 *   1. 截一张当前画面（字后面的景色）
 *   2. 歌词照常画，但画到一张离屏画布上
 *   3. 合成回去时逐个像素比较：字的颜色和后面的景色太接近，就往相反的明暗方向推（背景亮压暗，背景暗提亮），
 *      色相保留；差得够多的地方原样不动。很暗的像素是字的阴影 / 描边，也不动
 *
 * 显卡做不了（没有 GL 3.0 / 着色器编译失败）时 [begin] 返回 false，歌词照常直接画。只能在渲染线程调用。
 */
class ContrastLayer : ContrastPass {

    private companion object {
        const val VERT = """
#version 120
void main() {
    gl_TexCoord[0] = gl_MultiTexCoord0;
    gl_Position = gl_Vertex;
}
"""

        const val COMPOSITE = """
#version 120
uniform sampler2D mask;
uniform sampler2D scene;
uniform vec2 screen;
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
    vec2 uv = gl_FragCoord.xy / screen;
    vec4 m = texture2D(mask, uv);
    if (m.a < 0.003) discard;
    vec3 c = clamp(m.rgb / m.a, 0.0, 1.0);
    vec3 bg = texture2D(scene, uv).rgb;
    float lc = luma(c);
    float d = max(length(c - bg) * 0.7, abs(lc - luma(bg)) * 1.6);
    float sim = 1.0 - smoothstep(0.18, 0.42, d);
    // 阴影、描边（很暗）不动
    sim *= smoothstep(0.08, 0.16, lc);
    vec3 target = luma(bg) > 0.45 ? c * 0.22 : mix(c, vec3(1.0), 0.75);
    vec3 outc = mix(c, target, sim);
    gl_FragColor = vec4(outc * m.a, m.a);
}
"""
    }

    private var program = 0
    private var failed = false
    private val backdrop by lazy { GlassBackdrop() }
    private val mask = GlassTarget()

    private var savedFbo = 0
    private val savedViewport = BufferUtils.createIntBuffer(16)
    private val savedClear = BufferUtils.createFloatBuffer(16)
    private var active = false

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        val bytes = src.toByteArray(Charsets.UTF_8)
        val source = BufferUtils.createByteBuffer(bytes.size)
        source.put(bytes).flip()
        GL20.glShaderSource(id, source)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(id, 4096)
            GL20.glDeleteShader(id)
            error("Contrast shader compile failed: $log")
        }
        return id
    }

    private fun usable(): Boolean {
        if (failed) return false
        if (!GLContext.getCapabilities().OpenGL30) {
            failed = true
            DangoLogger.warn("Auto contrast: OpenGL 3.0 not available, lyrics are drawn as usual")
            return false
        }
        if (program != 0) return true
        return try {
            val vs = compile(GL20.GL_VERTEX_SHADER, VERT)
            val fs = compile(GL20.GL_FRAGMENT_SHADER, COMPOSITE)
            val p = GL20.glCreateProgram()
            GL20.glAttachShader(p, vs)
            GL20.glAttachShader(p, fs)
            GL20.glLinkProgram(p)
            GL20.glDeleteShader(vs)
            GL20.glDeleteShader(fs)
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                error("Contrast shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
            }
            program = p
            true
        } catch (e: Throwable) {
            DangoLogger.warn("Auto contrast disabled: {}", e.toString())
            failed = true
            false
        }
    }

    override fun begin(): Boolean {
        if (active || !usable()) return false
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return false
        savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        savedViewport.clear()
        GL11.glGetInteger(GL11.GL_VIEWPORT, savedViewport)
        val prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        return try {
            backdrop.capture(frost = false)
            mask.ensure(w, h)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex)
            mask.bind()
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            savedClear.clear()
            GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, savedClear)
            GL11.glClearColor(0f, 0f, 0f, 0f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            GL11.glClearColor(savedClear.get(0), savedClear.get(1), savedClear.get(2), savedClear.get(3))
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST)
            // 画字时 alpha 也要叠起来（不是覆盖），合成时才能算出每个像素真正的颜色
            LegacyGfx.accumulateAlpha = true
            active = true
            true
        } catch (t: Throwable) {
            DangoLogger.warn("Auto contrast disabled after error: {}", t.toString())
            failed = true
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo)
            restoreViewport()
            false
        }
    }

    override fun end() {
        if (!active) return
        active = false
        LegacyGfx.accumulateAlpha = false
        val caps = GlCaps.save()
        val prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE1)
        val prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo)
            restoreViewport()
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glPushMatrix()
            GL11.glLoadIdentity()
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPushMatrix()
            GL11.glLoadIdentity()
            try {
                GL20.glUseProgram(program)
                GL20.glUniform1i(GL20.glGetUniformLocation(program, "mask"), 0)
                GL20.glUniform1i(GL20.glGetUniformLocation(program, "scene"), 1)
                GL20.glUniform2f(GL20.glGetUniformLocation(program, "screen"), mask.width.toFloat(), mask.height.toFloat())
                GL13.glActiveTexture(GL13.GL_TEXTURE1)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdrop.softTexture)
                GL13.glActiveTexture(GL13.GL_TEXTURE0)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, mask.texture)
                GL11.glEnable(GL11.GL_BLEND)
                GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
                GL11.glBegin(GL11.GL_QUADS)
                GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(-1f, -1f)
                GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(1f, -1f)
                GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(1f, 1f)
                GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(-1f, 1f)
                GL11.glEnd()
            } finally {
                GL11.glMatrixMode(GL11.GL_PROJECTION)
                GL11.glPopMatrix()
                GL11.glMatrixMode(GL11.GL_MODELVIEW)
                GL11.glPopMatrix()
            }
        } catch (t: Throwable) {
            DangoLogger.warn("Auto contrast disabled after error: {}", t.toString())
            failed = true
        } finally {
            GL20.glUseProgram(prevProgram)
            GL13.glActiveTexture(GL13.GL_TEXTURE1)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0)
            GL13.glActiveTexture(prevActive)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GlCaps.restore(caps)
        }
    }

    private fun restoreViewport() {
        GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
    }
}
