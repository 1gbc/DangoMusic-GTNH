package me.sorihiko.dangomusicgtnh.i18n

import java.util.Locale

/** 播放器的界面语言独立于 Minecraft。 */
object I18n {
    const val ENGLISH = 0
    const val CHINESE = 1

    @Volatile var language = CHINESE
        set(value) { field = value.coerceIn(ENGLISH, CHINESE) }

    private val chinese: Map<String, String> by lazy {
        I18n::class.java.getResourceAsStream("/assets/dangomusicgtnh/lang/zh_cn.tsv")!!
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                    val parts = line.split('\t', limit = 2)
                    require(parts.size == 2) { "Invalid translation: $line" }
                    parts[0] to parts[1]
                }
            }
    }
    private val english: Map<String, String> by lazy { chinese.entries.associate { it.value to it.key } }

    /** 仅传入界面文案，不传入歌名、歌手、歌词或用户输入。 */
    fun text(source: String): String =
        if (language == CHINESE) chinese[source] ?: source else english[source] ?: source

    fun format(source: String, vararg args: Any): String = String.format(Locale.ROOT, text(source), *args)

    /** 后台状态可能在语言切换前产生，因此绘制时再翻译，保留错误详情。 */
    fun message(source: String): String {
        val stopped = " · Auto-skip stopped after repeated playback failures."
        if (source.endsWith(stopped)) return message(source.removeSuffix(stopped)) + " · " + text(stopped.removePrefix(" · "))
        val exact = text(source)
        if (exact != source) return exact
        for (prefix in messagePrefixes) {
            if (source.startsWith(prefix)) return text(prefix) + source.substring(prefix.length)
        }
        for ((pattern, template) in messagePatterns) {
            val match = pattern.matchEntire(source) ?: continue
            return format(template, match.groupValues[1])
        }
        return source
    }

    private val messagePrefixes = listOf("加载失败：", "播放失败：", "Load failed: ", "Playback failed: ")
    private val messagePatterns = listOf(
        Regex("""Kugou rejected the login \(error (-?\d+)\)\. Refresh the QR code and try again\.""") to "Kugou rejected the login (error %s). Refresh the QR code and try again.",
    )
}
