package me.sorihiko.dangomusicgtnh.events

import me.sorihiko.dangomusicgtnh.event.Event

/**
 * 游戏里（没开界面时）的一次鼠标输入：滚轮或按键。处理的人把 [consumed] 设成 true，这次输入就不再交给游戏
 * （不切换快捷栏、不挖方块 / 攻击）。
 */
class GameMouseEvent(
    /** 滚轮：正数向上，0 = 不是滚轮 */
    val wheel: Int,
    /** 按键：0 左键、1 右键…；-1 = 不是按键 */
    val button: Int,
    val pressed: Boolean,
) : Event {
    var consumed = false
}
