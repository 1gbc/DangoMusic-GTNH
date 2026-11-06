package me.sorihiko.dangomusicgtnh.gfx

import kotlin.math.atan2
import kotlin.math.sqrt

/** 沿用游戏的角度约定：yaw 为水平转角，pitch 向下为正，单位为度。 */
data class ViewAngles(val yaw: Float, val pitch: Float) {
    companion object {
        /** 固定管线视图矩阵的第三行指向镜头后方，取反后才是观察方向。 */
        @JvmStatic
        @JvmOverloads
        fun fromViewBack(x: Double, y: Double, z: Double, rightX: Double = 0.0, rightZ: Double = 0.0): ViewAngles? {
            val lengthSquared = x * x + y * y + z * z
            if (!lengthSquared.isFinite() || lengthSquared < 1e-12) return null
            val horizontal = sqrt(x * x + z * z)
            // At the poles the horizontal component of the forward vector vanishes.
            val yaw = if (horizontal < 0.001 && rightX * rightX + rightZ * rightZ > 1e-6) {
                atan2(-rightZ, -rightX)
            } else atan2(x, -z)
            return ViewAngles(
                Math.toDegrees(yaw).toFloat(),
                Math.toDegrees(atan2(y, horizontal)).toFloat(),
            )
        }
    }
}
