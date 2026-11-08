package me.sorihiko.dangomusicgtnh.music

/** Applies the transition to PCM, including on devices that apply gain when data is queued. */
internal class FadingAudioOutput(
    private val output: AudioOutput,
    private val rate: Int,
    private val channels: Int,
    fadeIn: Boolean,
    private val active: () -> Boolean,
    private val paused: () -> Boolean,
    private val enabled: () -> Boolean,
    /** 开着淡入淡出时声卡里最多排多少毫秒（以前固定 60 ms，GTNH 一次 GC 停顿就会断音） */
    private val leadMs: () -> Int = { 60 },
) : AudioOutput by output {
    private val frameBytes = channels * 2
    private val fadeFrames = (rate * 160 / 1000).coerceAtLeast(1)
    private val chunkFrames = (rate / 100).coerceAtLeast(1)
    private val faded = ByteArray(chunkFrames * frameBytes)
    private var gain = if (fadeIn) 0f else 1f
    private var initialGain = gain
    private var targetGain = 1f
    private var fadeFrame = if (fadeIn) 0 else fadeFrames
    private var submittedFrames = 0L
    private var lastPosition = 0L
    private var positionChangedAt = System.nanoTime()

    @Synchronized
    fun fadeTo(target: Float) {
        if (target == targetGain) return
        initialGain = gain
        targetGain = target
        fadeFrame = if (gain == target) fadeFrames else 0
    }

    val fadeOutPlayed: Boolean get() = synchronized(this) {
        targetGain == 0f && fadeFrame == fadeFrames && positionMicros >= submittedMicros
    }

    private val submittedMicros: Long get() = submittedFrames * 1_000_000L / rate

    override fun write(data: ByteArray, offset: Int, length: Int) {
        require(length % frameBytes == 0 && offset >= 0 && length >= 0 && offset <= data.size - length)
        val end = offset + length
        var cursor = offset
        while (cursor < end && active()) {
            if (paused()) {
                Thread.sleep(5)
                continue
            }
            val useFades = enabled()
            var frames = (end - cursor) / frameBytes
            val scale = synchronized(this) {
                if (!useFades && targetGain == 1f) {
                    gain = 1f
                    fadeFrame = fadeFrames
                }
                if (targetGain == 0f) frames = minOf(frames, fadeFrames - fadeFrame)
                if (useFades) {
                    // Keep enough headroom for the device, while letting the next ramp reach it promptly.
                    frames = if (leadIsFull()) 0 else minOf(frames, chunkFrames)
                }
                val scaling = frames > 0 && fadeFrame < fadeFrames
                if (scaling) {
                    frames = minOf(frames, chunkFrames)
                    applyFade(data, cursor, frames)
                }
                submittedFrames += frames
                scaling
            }
            val bytes = frames * frameBytes
            if (bytes == 0) {
                // Hold the remaining PCM at the pause point until resumed or superseded.
                Thread.sleep(5)
                continue
            }
            if (scale) output.write(faded, 0, bytes) else output.write(data, cursor, bytes)
            cursor += bytes
        }
    }

    private fun leadIsFull(): Boolean {
        val position = positionMicros
        val now = System.nanoTime()
        val full = submittedMicros - position >= leadMs().coerceIn(40, 1500) * 1000L
        if (!full || position != lastPosition) {
            lastPosition = position
            positionChangedAt = now
        }
        // Some devices only report progress after a larger buffer has been primed.
        return full && now - positionChangedAt < 90_000_000L
    }

    private fun applyFade(data: ByteArray, offset: Int, frames: Int) {
        var cursor = 0
        repeat(frames) {
            repeat(channels) {
                val index = offset + cursor
                val sample = ((data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)).toShort()
                val adjusted = (sample * gain).toInt()
                faded[cursor++] = adjusted.toByte()
                faded[cursor++] = (adjusted shr 8).toByte()
            }
            if (fadeFrame < fadeFrames) {
                fadeFrame++
                gain = initialGain + (targetGain - initialGain) * fadeFrame / fadeFrames
            }
        }
    }
}
