package me.sorihiko.dangomusicgtnh.platform

import me.sorihiko.dangomusicgtnh.module.values.IntValue
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

enum class PlayerAction(val id: String, val defaultKey: Int = Keys.NONE) {
    OPEN("open_player", Keys.M), PAUSE("play_pause"), PREVIOUS("previous"), NEXT("next"),
    VOLUME_UP("volume_up"), VOLUME_DOWN("volume_down"), LYRICS("toggle_lyrics"), STOP("stop_playback"),
    /** 按住时滚轮翻看驾驶舱歌词（默认左 Alt） */
    LYRIC_PREVIEW("lyric_preview", Keys.LMENU);

    val translationKey: String get() = "key.dangomusicgtnh.$id"
}

class PlayerKeyBinding(val action: PlayerAction, val value: IntValue) {
    internal var rebound: NativePlayerKey? = null
        private set

    fun rebind(key: Int, modifier: String? = null) {
        value.setValue(allowedPlayerKey(key))
        rebound = NativePlayerKey(value.value, modifier.takeIf { value.value != Keys.NONE && value.value == key })
    }

    internal fun markSynced() { rebound = null }
}

data class NativePlayerKey(val code: Int, val modifier: String? = null)

/**
 * 鼠标左键 / 右键不能当快捷键：绑上以后界面里每次点击都被当成快捷键吃掉，什么都点不了。
 * 原版按键设置里绑的、旧配置里存的，都当成没绑定。
 */
fun allowedPlayerKey(key: Int): Int = if (key == Keys.mouse(0) || key == Keys.mouse(1)) Keys.NONE else key

/** 对接实际原版按键；首次优先采用原版已保存值，没有时迁移模组已有绑定。 */
class KeyBindingLink(
    private val setting: PlayerKeyBinding,
    private val readNative: () -> NativePlayerKey,
    private val writeNative: (NativePlayerKey) -> Unit,
    savedNative: NativePlayerKey?,
) {
    private var previous: NativePlayerKey

    init {
        val initial = setting.rebound ?: savedNative
        previous = acceptNative(initial ?: NativePlayerKey(setting.value.value))
        setting.markSynced()
        if (previous != readNative()) writeNative(previous)
    }

    /** 有修改时返回 true，调用方批量刷新原版映射并保存。 */
    fun sync(): Boolean {
        val configured = allowedPlayerKey(setting.value.value)
        if (configured != setting.value.value) setting.value.setValue(configured)
        val native = readNative()
        return when {
            setting.rebound != null || configured != previous.code -> {
                val target = setting.rebound?.takeIf { it.code == configured } ?: NativePlayerKey(configured)
                if (native != target) writeNative(target)
                previous = target
                setting.markSynced()
                native != target
            }
            native != previous -> {
                previous = acceptNative(native)
                if (previous != native) writeNative(previous)
                true
            }
            else -> false
        }
    }

    private fun acceptNative(native: NativePlayerKey): NativePlayerKey {
        setting.value.setValue(allowedPlayerKey(native.code))
        val code = setting.value.value
        return if (code == native.code && code != Keys.NONE) native else NativePlayerKey(code)
    }
}

/** 只读 Dango Music 的原版绑定项，不重新加载整个 options.txt。 */
fun savedPlayerKeys(file: Path): Map<String, String> = try {
    if (!Files.exists(file)) emptyMap() else Files.readAllLines(file, StandardCharsets.UTF_8)
        .filter { it.startsWith("key_key.dangomusicgtnh.") }
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon < 0) null else line.substring(4, colon) to line.substring(colon + 1)
        }.toMap()
} catch (_: Exception) {
    emptyMap()
}
