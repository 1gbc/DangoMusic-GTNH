package me.sorihiko.dangomusicgtnh.module.values

class BooleanValue(
    private var flag: Boolean,
) {
    fun get(): Boolean = flag
    fun set(flag: Boolean) {
        this.flag = flag
    }
    fun toggle() {
        set(!flag)
    }
}