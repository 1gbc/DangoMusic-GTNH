package me.sorihiko.dangomusicgtnh.music

import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.DangoMusic
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File
import java.util.Locale

/**
 * 歌词能用的字体，三个来源，都不依赖别的模组：
 *  1. 我们自己的字体文件夹 config/dangomusicgtnh/fonts/（放 .ttf / .otf / .ttc 就能选）
 *  2. 其它模组的字体目录：有就一起列出来，没有就跳过（目前是 Angelica 的 config/angelica/fonts、fontfiles；
 *     玩家也可以在 config/dangomusicgtnh/font-folders.txt 里一行写一个目录）
 *  3. 系统里装的字体（和 Angelica 字体配置、Qz-UILib 看到的是同一批）
 *
 * 热重载：目录每两秒重新扫一次，新放进去的字体直接出现在列表里；选中的字体文件被替换时也会重新加载。
 *
 * 字体的 id（存在设置里、也会发给联机的其他人）：
 *  - ""                  默认字体
 *  - "名字.ttf"           我们自己的字体文件夹（旧版本存的就是这样，保持兼容）
 *  - "mod:<目录>/<文件>"   其它模组的字体目录（<目录> 是相对游戏目录的路径，或者 font-folders.txt 里写的绝对路径）
 *  - "sys:<字体族>"        系统字体（英文族名）
 */
object LyricFonts {
    enum class Kind { OWN, MOD, SYSTEM }

    class Entry(val id: String, val label: String, val kind: Kind, val source: String, val file: File?)

    /** 我们自己的字体文件夹 */
    val folder: File get() = DangoMusic.instance.clientDir.resolve("fonts").toFile()

    private val gameDir: File get() = DangoMusic.instance.clientDir.toAbsolutePath().parent?.parent?.toFile() ?: File(".")

    /** 其它模组的字体目录（相对游戏目录） → 显示的来源名 */
    private val modFolders = listOf(
        "config/angelica/fonts" to "Angelica",
        "fontfiles" to "Angelica",
    )

    /** 玩家自己加的目录：一行一个，# 开头是注释 */
    private val extraFoldersFile: File get() = DangoMusic.instance.clientDir.resolve("font-folders.txt").toFile()

    private val extensions = listOf(".ttf", ".otf", ".ttc")

    private var cached = emptyList<Entry>()
    private var scannedAt = 0L

    /** 系统字体族（英文名 → 本地化名）；第一次用时在后台查一遍，查到之前列表里先没有系统字体 */
    @Volatile private var systemFamilies: List<Pair<String, String>>? = null
    private var systemQueried = false
    /** 上次扫描时系统字体已经查好了（没查好的话查好后马上再扫一次） */
    private var systemInCache = false

    /** 所有能选的字体（自己的 → 其它模组 → 系统），两秒内重复调用直接用上次的结果 */
    fun entries(): List<Entry> {
        val now = System.currentTimeMillis()
        if (now - scannedAt > 2000 || (!systemInCache && systemFamilies != null)) {
            scannedAt = now
            systemInCache = systemFamilies != null
            if (!folder.exists()) ensureFolder()
            val out = ArrayList<Entry>()
            for (f in fontFiles(folder)) out += Entry(f.name, f.name.substringBeforeLast('.'), Kind.OWN, "", f)
            for ((dir, source) in otherFolders()) {
                for (f in fontFiles(dir.second)) {
                    out += Entry("mod:${dir.first}/${f.name}", f.name.substringBeforeLast('.'), Kind.MOD, source, f)
                }
            }
            querySystemFonts()
            systemFamilies?.forEach { (english, local) -> out += Entry("sys:$english", local, Kind.SYSTEM, "", null) }
            cached = out
        }
        return cached
    }

    /** 马上重新扫一遍（设置里的刷新按钮）：新放进去的字体立刻出现，之前加载失败的也重新试 */
    fun refresh() {
        scannedAt = 0L
        loaded.clear()
        systemQueried = false
    }

    /** 设置里显示的名字 */
    fun label(id: String): String {
        if (id.isEmpty()) return ""
        entries().firstOrNull { it.id == id }?.let { return if (it.kind == Kind.MOD) "${it.label} · ${it.source}" else it.label }
        return when {
            id.startsWith("sys:") -> id.removePrefix("sys:")
            else -> id.substringAfterLast('/').substringBeforeLast('.')
        }
    }

    /** 这个 id 现在还找得到吗（文件被删了 / 系统里没装这个字体 → 按默认字体画） */
    fun exists(id: String): Boolean = id.isEmpty() || resolveFile(id)?.isFile == true ||
        (id.startsWith("sys:") && (systemFamilies?.any { it.first == id.removePrefix("sys:") } ?: true))

    private val loaded = HashMap<String, Font?>()

    /**
     * 加载字体，返回 (字体, 版本标记)。字体为 null = 用默认字体。
     * 版本标记包含文件的修改时间和大小：文件被替换后标记变了，画字那边就会重新生成字形（热重载）。
     * 只在渲染线程调用。
     */
    fun load(id: String): Pair<Font?, String?> {
        if (id.isEmpty()) return null to null
        if (id.startsWith("sys:")) {
            val family = id.removePrefix("sys:")
            val key = "sys:$family"
            return cached(key) {
                val font = Font(family, Font.PLAIN, 1)
                // 系统里没有这个字体族时 Java 会悄悄换成 Dialog：当作没选，用默认字体
                if (font.family.equals(family, ignoreCase = true) || font.getFamily(Locale.ENGLISH).equals(family, ignoreCase = true)) font else null
            } to key
        }
        val file = resolveFile(id) ?: return null to null
        if (!file.isFile) return null to null
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        return cached(key) { createFont(file) } to key
    }

    /** 加载过的字体（失败的也记下来，是 null），不会每帧重复读文件 */
    private inline fun cached(key: String, make: () -> Font?): Font? {
        if (loaded.containsKey(key)) return loaded[key]
        if (loaded.size > 64) loaded.clear()
        val font = make()
        loaded[key] = font
        return font
    }

    /**
     * id → 字体文件。只认扫描出来的列表里真有的那些（自己的文件夹、其它模组的目录、font-folders.txt 里写的目录）：
     * 字体 id 也会从联机的其他玩家那里传过来，不能拿它去读任意路径
     */
    private fun resolveFile(id: String): File? = when {
        id.startsWith("sys:") || id.isEmpty() -> null
        id.startsWith("mod:") -> entries().firstOrNull { it.id == id }?.file
        id.contains('/') || id.contains('\\') || id.contains("..") -> null
        else -> File(folder, id).takeIf { f -> f.parentFile == folder || f.parentFile?.absoluteFile == folder.absoluteFile }
    }

    private fun createFont(file: File): Font? = try {
        val fonts = try {
            // .ttc 里有好几个字体：Java 9+ 才有 createFonts，用反射调（编译目标是 Java 8）
            @Suppress("UNCHECKED_CAST")
            (Font::class.java.getMethod("createFonts", File::class.java).invoke(null, file) as Array<Font>).toList()
        } catch (_: NoSuchMethodException) {
            listOf(Font.createFont(Font.TRUETYPE_FONT, file))
        }
        fonts.firstOrNull { it.canDisplay('中') } ?: fonts.firstOrNull()
    } catch (e: Throwable) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        DangoLogger.warn("Dango Music: could not load font {}: {}", file.name, cause.toString())
        null
    }.also { if (it != null) DangoLogger.info("Dango Music: loaded font {} from {}", it.fontName, file.name) }

    /** 其它模组的目录 + font-folders.txt 里写的目录：(写进 id 的目录名, 实际目录) → 来源名 */
    private fun otherFolders(): List<Pair<Pair<String, File>, String>> {
        val out = ArrayList<Pair<Pair<String, File>, String>>()
        for ((rel, source) in modFolders) {
            val dir = File(gameDir, rel)
            if (dir.isDirectory) out += (rel to dir) to source
        }
        try {
            val f = extraFoldersFile
            if (f.isFile) {
                f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                    val dir = File(line).let { if (it.isAbsolute) it else File(gameDir, line) }
                    if (dir.isDirectory) out += (line.replace('\\', '/') to dir) to dir.name
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun fontFiles(dir: File): List<File> = try {
        dir.listFiles()?.filter { f -> f.isFile && extensions.any { f.name.lowercase().endsWith(it) } }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun querySystemFonts() {
        if (systemQueried) return
        systemQueried = true
        Thread({
            val families = try {
                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                val locale = Locale.getDefault()
                // getAllFonts 每个字体只建一个 Font 对象，不会读整个文件；同一族只留一个
                ge.allFonts.asSequence()
                    .map { it.getFamily(Locale.ENGLISH) to it.getFamily(locale) }
                    .filter { (en, _) -> !en.startsWith(".") && en !in LOGICAL }
                    .distinctBy { it.first.lowercase() }
                    .sortedBy { it.second.lowercase() }
                    .toList()
            } catch (e: Throwable) {
                DangoLogger.warn("Dango Music: could not list system fonts: {}", e.toString())
                emptyList()
            }
            systemFamilies = families
        }, "DangoMusic-fonts").apply { isDaemon = true }.start()
    }

    private val LOGICAL = setOf("Dialog", "DialogInput", "Monospaced", "Serif", "SansSerif")

    fun ensureFolder() {
        try { folder.mkdirs() } catch (_: Exception) { }
    }

    /**
     * 用系统的文件管理器打开字体文件夹；打不开（安卓启动器里没有文件管理器命令）返回 false，让界面把路径告诉玩家。
     * 游戏里 Java 的桌面功能是关着的（headless），所以和原版打开资源包文件夹一样，直接调系统命令
     */
    fun openFolder(): Boolean {
        ensureFolder()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            "win" in os -> listOf("explorer.exe", folder.absolutePath)
            "mac" in os -> listOf("open", folder.absolutePath)
            else -> listOf("xdg-open", folder.absolutePath)
        }
        return try {
            ProcessBuilder(command).start()
            true
        } catch (e: Exception) {
            DangoLogger.warn("Could not open the fonts folder {}: {}", folder, e.toString())
            false
        }
    }
}
