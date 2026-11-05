package me.sorihiko.dangomusicgtnh.shader

import me.sorihiko.dangomusicgtnh.utils.Color

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

import java.nio.ByteBuffer
import java.nio.FloatBuffer

abstract class GlShader(
    val vertPath: String,
    val fragPath: String,
) {
    private var programId: Int = 0
    private var uniforms = mutableMapOf<String, Int>()

    fun linkProgram() {
        if (programId != 0) return

        val vertex = compileShader(vertPath, GL20.GL_VERTEX_SHADER)
        try {
            val fragment = compileShader(fragPath, GL20.GL_FRAGMENT_SHADER)
            try {
                val program = GL20.glCreateProgram()
                GL20.glAttachShader(program, vertex)
                GL20.glAttachShader(program, fragment)
                GL20.glLinkProgram(program)
                if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                    val log = GL20.glGetProgramInfoLog(program, 1024)
                    GL20.glDeleteProgram(program)
                    error("Shader program linking failed ($vertPath, $fragPath): $log")
                }
                programId = program
            } finally {
                GL20.glDeleteShader(fragment)
            }
        } finally {
            GL20.glDeleteShader(vertex)
        }

        uniformsFields().forEach {
            getUniformId(it)
        }
    }

    open fun uniformsFields(): Array<String> {
        return emptyArray()
    }

    fun getProgramID(): Int = programId

    fun bind() {
        GL20.glUseProgram(programId)
    }

    fun unbind() {
        GL20.glUseProgram(0)
    }

    private fun getUniformId(name: String): Int {
        if (uniforms.containsKey(name)) return uniforms[name]!!
        val i = GL20.glGetUniformLocation(programId, name)
        uniforms[name] = i
        return i
    }

    private fun compileShader(srcPath: String, type: Int): Int {
        val source = this.javaClass.getResourceAsStream("/assets/dangomusicgtnh/shaders/$srcPath")
            ?.use { it.readBytes() } ?: error("Shader resource is missing: $srcPath")
        val buffer = ByteBuffer.allocateDirect(source.size)
        buffer.put(source)
        buffer.flip()

        val shaderId = GL20.glCreateShader(type)
        GL20.glShaderSource(shaderId, buffer)
        GL20.glCompileShader(shaderId)
        if (GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(shaderId, 1024)
            GL20.glDeleteShader(shaderId)
            error("Shader compilation failed ($srcPath): $log")
        }
        return shaderId
    }

    protected fun setUniform2f(field: String, a: Float, b: Float) {
        GL20.glUniform2f(
            getUniformId(field),
            a, b
        )
    }

    protected fun setUniform4f(field: String, a: Float, b: Float, c: Float, d: Float) {
        GL20.glUniform4f(
            getUniformId(field),
            a, b, c, d
        )
    }

    protected fun setUniformColor(field: String, color: Color) {
        setUniform4f(field, color.r, color.g, color.b, color.a)
    }

    protected fun setUniform1f(field: String, a: Float) {
        GL20.glUniform1f(
            getUniformId(field),
            a
        )
    }

    protected fun setUniform1i(field: String, a: Int) {
        GL20.glUniform1i(getUniformId(field), a)
    }

    protected fun setUniformMat4(field: String, mat4Buffer: FloatBuffer) {
        GL20.glUniformMatrix4(
            getUniformId(field),
            false,
            mat4Buffer
        )
    }
}
