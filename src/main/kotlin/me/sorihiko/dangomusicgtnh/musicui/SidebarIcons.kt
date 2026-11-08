package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.RenderContext
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.utils.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A consistent 16px navigation icon set, independent of installed symbol fonts. */
internal object SidebarIcons {
    fun draw(ctx: RenderContext, glyph: String, cx: Float, cy: Float, color: Color) {
        val stroke = 1.5f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            ctx.pushTranslate(cx + x1, cy + y1, 0f)
            ctx.pushRotate(Math.toDegrees(atan2(y2 - y1, x2 - x1).toDouble()).toFloat(), 0f, 0f, 1f)
            ctx.drawShape(LayoutBounds(-stroke / 2f, -stroke / 2f, hypot(x2 - x1, y2 - y1) + stroke, stroke), ShapeStyle(color, stroke / 2f))
            ctx.popTransform()
            ctx.popTransform()
        }
        fun outline(x: Float, y: Float, w: Float, h: Float, r: Float) =
            ctx.drawShape(LayoutBounds(cx + x, cy + y, w, h), ShapeStyle(Color.TRANSPARENT, r, stroke, color))
        fun dot(x: Float, y: Float, r: Float) =
            ctx.drawShape(LayoutBounds(cx + x - r, cy + y - r, r * 2f, r * 2f), ShapeStyle(color, r))
        fun arc(x: Float, y: Float, r: Float, from: Double, to: Double) {
            for (i in 0 until 8) {
                val a = Math.toRadians(from + (to - from) * i / 8)
                val b = Math.toRadians(from + (to - from) * (i + 1) / 8)
                line(x + (cos(a) * r).toFloat(), y + (sin(a) * r).toFloat(),
                    x + (cos(b) * r).toFloat(), y + (sin(b) * r).toFloat())
            }
        }
        when (glyph) {
            "⌂" -> {
                line(-7f, -0.5f, 0f, -6.5f); line(0f, -6.5f, 7f, -0.5f)
                line(-5.5f, -1f, -5.5f, 6f); line(5.5f, -1f, 5.5f, 6f)
                line(-5.5f, 6f, -1.8f, 6f); line(1.8f, 6f, 5.5f, 6f)
                line(-1.8f, 6f, -1.8f, 1f); line(-1.8f, 1f, 1.8f, 1f); line(1.8f, 1f, 1.8f, 6f)
            }
            "▤" -> {
                outline(-6.5f, 0f, 3f, 6f, 0.7f)
                outline(-1.5f, -6f, 3f, 12f, 0.7f)
                outline(3.5f, -3f, 3f, 9f, 0.7f)
            }
            "♫" -> {
                line(-3f, -4f, -3f, 4.5f); line(4.5f, -6f, 4.5f, 2.5f)
                line(-3f, -4f, 4.5f, -6f)
                dot(-4.5f, 4.5f, 2.3f); dot(3f, 2.5f, 2.3f)
            }
            "♬" -> {
                outline(-5.5f, -6f, 4f, 4f, 2f)
                outline(2f, -4.5f, 3.5f, 3.5f, 1.75f)
                outline(-7f, 1f, 7.5f, 5f, 2.5f)
                line(3f, 1f, 5.5f, 1f); line(5.5f, 1f, 7f, 3f); line(7f, 3f, 7f, 6f); line(7f, 6f, 3f, 6f)
            }
            "☁" -> {
                line(-4f, 5f, 4f, 5f)
                arc(4f, 1.5f, 3.5f, 90.0, -90.0)
                arc(0f, -2f, 4f, 0.0, -180.0)
                arc(-4f, 1.5f, 3.5f, -90.0, -270.0)
            }
            "⚙" -> {
                outline(-4.5f, -4.5f, 9f, 9f, 4.5f)
                outline(-1.5f, -1.5f, 3f, 3f, 1.5f)
                for (i in 0..7) {
                    val angle = i * Math.PI / 4
                    line((cos(angle) * 5).toFloat(), (sin(angle) * 5).toFloat(),
                        (cos(angle) * 7).toFloat(), (sin(angle) * 7).toFloat())
                }
            }
            "♥" -> {
                arc(-3f, -2f, 3f, 0.0, -210.0)
                line(-5.6f, -0.5f, 0f, 6f); line(0f, 6f, 5.6f, -0.5f)
                arc(3f, -2f, 3f, 30.0, -180.0)
            }
            else -> {
                outline(-6f, -6f, 12f, 12f, 2f)
                for (y in listOf(-3f, 0f, 3f)) line(-3f, y, 3f, y)
            }
        }
    }
}
