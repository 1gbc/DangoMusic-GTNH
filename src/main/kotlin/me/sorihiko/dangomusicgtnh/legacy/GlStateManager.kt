package me.sorihiko.dangomusicgtnh.legacy

import net.minecraft.client.renderer.OpenGlHelper
import org.lwjgl.opengl.GL11

/** Minecraft 1.7.10 uses the compatibility-profile GL calls directly. */
internal object GlStateManager {
    fun pushMatrix() = GL11.glPushMatrix()
    fun popMatrix() = GL11.glPopMatrix()
    fun translate(x: Double, y: Double, z: Double) = GL11.glTranslated(x, y, z)
    fun scale(x: Float, y: Float, z: Float) = GL11.glScalef(x, y, z)
    fun color(r: Float, g: Float, b: Float, a: Float) = GL11.glColor4f(r, g, b, a)
    fun bindTexture(texture: Int) = GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
    fun setActiveTexture(unit: Int) = OpenGlHelper.setActiveTexture(unit)
    fun enableTexture2D() = GL11.glEnable(GL11.GL_TEXTURE_2D)
    fun disableTexture2D() = GL11.glDisable(GL11.GL_TEXTURE_2D)
    fun enableBlend() = GL11.glEnable(GL11.GL_BLEND)
    fun disableBlend() = GL11.glDisable(GL11.GL_BLEND)
    fun enableAlpha() = GL11.glEnable(GL11.GL_ALPHA_TEST)
    fun disableAlpha() = GL11.glDisable(GL11.GL_ALPHA_TEST)
    fun enableDepth() = GL11.glEnable(GL11.GL_DEPTH_TEST)
    fun disableDepth() = GL11.glDisable(GL11.GL_DEPTH_TEST)
    fun enableCull() = GL11.glEnable(GL11.GL_CULL_FACE)
    fun disableCull() = GL11.glDisable(GL11.GL_CULL_FACE)
    fun enableFog() = GL11.glEnable(GL11.GL_FOG)
    fun disableFog() = GL11.glDisable(GL11.GL_FOG)
    fun enableLighting() = GL11.glEnable(GL11.GL_LIGHTING)
    fun disableLighting() = GL11.glDisable(GL11.GL_LIGHTING)
    fun depthMask(enabled: Boolean) = GL11.glDepthMask(enabled)
    fun shadeModel(mode: Int) = GL11.glShadeModel(mode)
    fun tryBlendFuncSeparate(src: Int, dst: Int, srcAlpha: Int, dstAlpha: Int) =
        OpenGlHelper.glBlendFunc(src, dst, srcAlpha, dstAlpha)
}
