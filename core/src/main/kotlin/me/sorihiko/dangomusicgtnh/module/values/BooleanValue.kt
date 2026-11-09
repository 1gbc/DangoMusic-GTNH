package me.sorihiko.dangomusicgtnh.module.values

class BooleanValue(
    private var flag: Boolean,
) {
    /** 默认值（设置页“恢复默认”用） */
    val default: Boolean = flag

    fun get(): Boolean = flag
    fun set(flag: Boolean) {
        this.flag = flag
    }
    fun toggle() {
        set(!flag)
    }
    fun reset() = set(default)
}
