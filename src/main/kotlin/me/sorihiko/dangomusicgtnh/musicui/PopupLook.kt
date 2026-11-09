package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.ui.GlassStyle
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.RenderContext
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.UiStyle
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.utils.Color

/**
 * 弹出菜单的外观，两种界面风格各一套（动画都用 [PopupMotion]，两种风格一样）：
 *  - 连续圆角：和播放栏同一种玻璃（连续圆角那边的设计）
 *  - 经典：浅色磨砂玻璃，比窗口亮一档，细亮边；选中项是半透明白底 + 强调色
 * 播放顺序 / 音量 / 音质、侧栏的音源菜单、设置页的字体列表都用这里的颜色，保证同一种风格下长得一样。
 */
internal object PopupLook {
    /** 连续圆角：播放栏的玻璃，圆角放大 */
    val CONTINUOUS_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 24f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.10f), 14f, Vec2(0f, -3f)))

    /** 经典：浅色磨砂玻璃（透出的背景更亮、更白），配窗口里的白字 */
    val CLASSIC_GLASS = GlassStyle(
        radius = 12f,
        tint = Color(1f, 1f, 1f, 0.16f),
        maxLuma = 0.56f,
        saturation = 1.3f,
        frost = 0.92f,
        refraction = 12f,
        bevel = 12f,
        dispersion = 0.25f,
        highlight = 1.25f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.22f), 18f, Vec2(0f, -4f)),
    )

    /** 菜单底：玻璃 + 细亮边；圆角跟着当前大小走（展开动画里不会变形） */
    fun panel(ctx: RenderContext, b: LayoutBounds) {
        if (UiStyle.continuous) {
            ctx.drawGlass(b, CONTINUOUS_GLASS.copy(radius = kotlin.math.min(CONTINUOUS_GLASS.radius, b.height / 2f)))
        } else {
            val r = kotlin.math.min(CLASSIC_GLASS.radius, b.height / 2f)
            val edge = kotlin.math.min(12f, kotlin.math.min(b.width, b.height) / 3f)
            ctx.drawGlass(b, CLASSIC_GLASS.copy(radius = r, bevel = edge, refraction = edge))
            ctx.drawShape(b, ShapeStyle(Color(0f, 0f, 0f, 0f), r, 1f, Color(1f, 1f, 1f, 0.28f)))
        }
    }

    /** 选中的那一项的底色 */
    val selected: Color get() = if (UiStyle.continuous) Color(1f, 1f, 1f, 0.16f) else Color(1f, 1f, 1f, 0.24f)

    /** 鼠标悬停的底色 */
    val hover: Color get() = if (UiStyle.continuous) Theme.CARD_HOVER else Color(1f, 1f, 1f, 0.12f)

    /** 分隔线 */
    val divider: Color get() = if (UiStyle.continuous) Color(1f, 1f, 1f, 0.08f) else Color(1f, 1f, 1f, 0.18f)

    /** 一行的圆角：连续圆角是胶囊，经典是普通圆角 */
    fun rowRadius(height: Float): Float = if (UiStyle.continuous) height / 2f else 7f
}
