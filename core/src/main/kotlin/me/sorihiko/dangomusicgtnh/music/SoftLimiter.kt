package me.sorihiko.dangomusicgtnh.music

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 写给声卡前的最后一道保护：音量均衡放大、环境混响叠加之后，超过门限的地方平滑压下去，
 * 而不是在 16 位上限处直接截掉（硬削波听起来就是“炸”、“破音”）。
 *
 * 左右声道联动的峰值限幅：某个样本超过门限时，增益立刻降到刚好让它落在门限上，之后约 [releaseMs]
 * 毫秒慢慢回到 1。正常电平的音乐增益一直是 1，完全不受影响。每次开始解码新建一个。
 */
internal class SoftLimiter(
    /** 门限（16 位刻度）：约 -1 dBFS，给后面的重采样 / 声卡留一点余量 */
    private val threshold: Float = 32767f * 0.89f,
    private val releaseMs: Float = 150f,
) {
    private var env = 1f
    private var coef = 0f
    private var rate = 0

    /** 处理交错存放的浮点样本（16 位刻度），结果写进 [out] */
    fun process(pcm: FloatArray, length: Int, channels: Int, sampleRate: Int, out: ShortArray) {
        if (sampleRate != rate && sampleRate > 0) {
            rate = sampleRate
            coef = 1f - exp(-1f / (releaseMs / 1000f * sampleRate))
        }
        val ch = channels.coerceIn(1, 2)
        var i = 0
        while (i + ch - 1 < length) {
            var peak = abs(pcm[i])
            if (ch == 2) peak = max(peak, abs(pcm[i + 1]))
            val need = if (peak > threshold) threshold / peak else 1f
            // 慢慢恢复，但不能超过这个样本需要的增益
            env = minOf(env + (1f - env) * coef, need)
            for (c in 0 until ch) out[i + c] = (pcm[i + c] * env).roundToInt().coerceIn(-32768, 32767).toShort()
            i += ch
        }
        // 声道数和长度对不上时剩下的零头（正常不会有）
        while (i < length) {
            out[i] = (pcm[i] * env).roundToInt().coerceIn(-32768, 32767).toShort()
            i++
        }
    }
}
