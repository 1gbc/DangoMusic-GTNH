package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.ui.LayoutBounds

/** Reversible, frame-time driven spring: opening, closing and reopening share the same state. */
internal class PopupMotion {
    private val spring = Spring(420f, 36f)
    val progress: Float get() = spring.x.coerceIn(0f, 1.015f)
    val visible: Boolean get() = progress > 0.002f
    val contentOpacity: Float get() = ((progress - 0.15f) / 0.65f).coerceIn(0f, 1f)
    fun step(open: Boolean, dt: Float) = spring.step(if (open) 1f else 0f, dt.coerceIn(0f,0.05f))
    fun snap(open: Boolean) { spring.x=if(open) 1f else 0f; spring.v=0f }
    fun bounds(anchor: LayoutBounds, target: LayoutBounds): LayoutBounds {
        val p = progress
        return LayoutBounds(anchor.x+(target.x-anchor.x)*p, anchor.y+(target.y-anchor.y)*p,
            anchor.width+(target.width-anchor.width)*p, anchor.height+(target.height-anchor.height)*p)
    }
}
