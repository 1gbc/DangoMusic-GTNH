package me.sorihiko.dangomusicgtnh.shader.impls.ui

import me.sorihiko.dangomusicgtnh.shader.GlShader
import me.sorihiko.dangomusicgtnh.ui.Size
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.UiStyle

import org.lwjgl.BufferUtils
import org.lwjgl.util.vector.Matrix4f

class ImageShader : GlShader(
    "ui/image.vert",
    "ui/image.frag"
){
    private val buffer = BufferUtils.createFloatBuffer(16)

    override fun uniformsFields(): Array<String> {
        return arrayOf(
            "u_Transform",
            "u_ScreenSize",
            "u_Texture",
            "u_Opacity",
            "u_Bounds",
            "u_Radius",
            "u_Continuous",
        )
    }

    fun setup(mat4: Matrix4f, screenSize: Size, opacity: Float,bounds: LayoutBounds,radius: Float){
        buffer.clear()
        mat4.store(buffer)
        buffer.flip()
        setUniformMat4("u_Transform", buffer)

        setUniform2f("u_ScreenSize", screenSize.width, screenSize.height)
        setUniform1i("u_Texture", 0)
        setUniform1f("u_Opacity", opacity)
        setUniform4f("u_Bounds",bounds.x,bounds.y,bounds.width,bounds.height)
        setUniform1f("u_Radius",radius)
        setUniform1f("u_Continuous",if(UiStyle.continuous) 1f else 0f)
    }
}
