package me.sorihiko.dangomusicgtnh.events

import me.sorihiko.dangomusicgtnh.event.Event
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds

/** Forge 绘制完游戏 HUD 后发出，玩家列表范围使用窗口像素。 */
class HudRenderEvent(private val playerList: LayoutBounds? = null) : Event {
    internal fun overlapsPlayerList(bounds: LayoutBounds, scale: Float): Boolean {
        val list = playerList ?: return false
        return bounds.left * scale < list.right && bounds.right * scale > list.left &&
            bounds.top * scale < list.bottom && bounds.bottom * scale > list.top
    }
}
