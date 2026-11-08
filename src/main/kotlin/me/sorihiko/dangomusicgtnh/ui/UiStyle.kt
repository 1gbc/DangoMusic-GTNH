package me.sorihiko.dangomusicgtnh.ui

/**
 * 界面风格（设置 → 音乐与 HUD → 界面风格，随 settings.json 保存）。
 * - 经典（默认）：普通圆角，开关是胶囊底座 + 圆形圆钮。
 * - 连续圆角：形状 / 玻璃着色器和封面遮罩用舒展的连续曲率圆角，胶囊开关配圆形圆钮。
 */
object UiStyle {
    @Volatile
    var continuous = false
}
