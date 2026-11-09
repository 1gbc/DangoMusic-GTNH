package me.sorihiko.dangomusicgtnh.module.values

class IntValue(
    val minRange: Int,
    val maxRange: Int,
    value: Int = minRange,
) {

    var value: Int = minRange
        private set

    /** 默认值（设置页“恢复默认”用） */
    val default: Int = value.coerceIn(minRange, maxRange)

    init {
        setValue(value)
    }

    fun reset() = setValue(default)

    fun setValue(value: Int) {
        this.value = value.coerceIn(minRange, maxRange)
    }

    fun normalize(): Float {
        return (value - minRange).toFloat() /
                (maxRange - minRange)
    }
}