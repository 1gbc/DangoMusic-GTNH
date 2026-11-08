package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.RenderContext
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.utils.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Playback controls with a stable optical size, independent of symbol fonts. */
internal object PlayerIcons {
    private class Pen(val ctx: RenderContext, val color: Color) {
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float = 2.4f) {
            ctx.pushTranslate(x1, y1, 0f)
            ctx.pushRotate(Math.toDegrees(atan2(y2-y1, x2-x1).toDouble()).toFloat(), 0f, 0f, 1f)
            ctx.drawShape(LayoutBounds(-stroke/2f, -stroke/2f, hypot(x2-x1, y2-y1)+stroke, stroke), ShapeStyle(color, stroke/2f))
            ctx.popTransform(); ctx.popTransform()
        }
        fun arc(x: Float, y: Float, r: Float, from: Double, to: Double) {
            for (i in 0 until 10) {
                val a = Math.toRadians(from + (to-from)*i/10)
                val b = Math.toRadians(from + (to-from)*(i+1)/10)
                line(x+(cos(a)*r).toFloat(), y+(sin(a)*r).toFloat(), x+(cos(b)*r).toFloat(), y+(sin(b)*r).toFloat(), 2.4f)
            }
        }
        fun arrow(x: Float, y: Float, direction: Float) {
            line(x-3f*direction, y-3f, x, y)
            line(x, y, x-3f*direction, y+3f)
        }
    }

    fun mode(ctx: RenderContext, mode: MusicPlayer.Mode, cx: Float, cy: Float, size: Float, color: Color) {
        ctx.pushTranslate(cx, cy, 0f); ctx.pushScale(size/20f, size/20f, 1f)
        val p = Pen(ctx, color)
        if (mode == MusicPlayer.Mode.SHUFFLE) {
            p.line(-8f,-5f,-5f,-5f); p.line(-5f,-5f,5f,5f); p.line(5f,5f,8f,5f); p.arrow(8f,5f,1f)
            // Leave a small gap at the crossing so the two paths stay legible.
            p.line(-8f,5f,-5f,5f); p.line(-5f,5f,-2f,2f)
            p.line(2f,-2f,5f,-5f); p.line(5f,-5f,8f,-5f); p.arrow(8f,-5f,1f)
        } else {
            p.line(-5f,-5f,7f,-5f); p.arrow(7f,-5f,1f)
            p.arc(-5f,-1f,4f,180.0,270.0); p.line(-9f,-1f,-9f,1f)
            p.line(5f,5f,-7f,5f); p.arrow(-7f,5f,-1f)
            p.arc(5f,1f,4f,0.0,90.0); p.line(9f,-1f,9f,1f)
            if (mode == MusicPlayer.Mode.REPEAT_ONE) {
                p.line(-1f,-1.5f,0.5f,-2.5f,1.2f); p.line(0.5f,-2.5f,0.5f,2.5f,1.2f)
            }
        }
        ctx.popTransform(); ctx.popTransform()
    }

    fun speaker(ctx: RenderContext, cx: Float, cy: Float, color: Color, scale: Float = 1f, waves: Int = 2, muted: Boolean = false) {
        ctx.pushTranslate(cx, cy, 0f); ctx.pushScale(scale, scale, 1f)
        val p = Pen(ctx, color)
        p.line(-7f,-3f,-3f,-3f); p.line(-7f,-3f,-7f,3f); p.line(-7f,3f,-3f,3f)
        p.line(-3f,-3f,2f,-7f); p.line(2f,-7f,2f,7f); p.line(2f,7f,-3f,3f)
        if (muted) {
            p.line(6f,-3f,11f,3f); p.line(6f,3f,11f,-3f)
        } else {
            if (waves >= 1) p.arc(1f,0f,6f,-48.0,48.0)
            if (waves >= 2) p.arc(1f,0f,10f,-48.0,48.0)
        }
        ctx.popTransform(); ctx.popTransform()
    }
}
