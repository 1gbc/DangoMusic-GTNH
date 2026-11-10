package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.module.Module
import me.sorihiko.dangomusicgtnh.module.impls.music.DynamicIslandModule
import me.sorihiko.dangomusicgtnh.module.impls.music.LyricsModule
import me.sorihiko.dangomusicgtnh.module.impls.music.NearbyModule
import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule
import me.sorihiko.dangomusicgtnh.module.impls.music.SongCardModule
import me.sorihiko.dangomusicgtnh.module.values.BooleanValue
import me.sorihiko.dangomusicgtnh.module.values.ColorValue
import me.sorihiko.dangomusicgtnh.module.values.FloatValue
import me.sorihiko.dangomusicgtnh.module.values.IntValue
import me.sorihiko.dangomusicgtnh.module.values.TextValue
import me.sorihiko.dangomusicgtnh.music.LyricFonts
import me.sorihiko.dangomusicgtnh.module.values.ModuleValue
import me.sorihiko.dangomusicgtnh.music.MusicScreen
import me.sorihiko.dangomusicgtnh.platform.Keys
import me.sorihiko.dangomusicgtnh.platform.allowedPlayerKey
import me.sorihiko.dangomusicgtnh.ui.GlassStyle
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.utils.Color

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 设置页（侧栏齿轮）：两个标签 Lyrics / Music & HUD，每个标签是若干分组卡片，两列瀑布流排开。
 * 直接读写各模块的 @ModuleValue，改了立刻生效，ConfigManager 会自动存盘。
 */
internal class SettingsPage(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui

    private enum class Ctl { TOGGLE, SLIDER, KEY, LANGUAGE, COLOR, FONT, CHOICE }

    /**
     * 一行设置。两种“暂时用不上”：
     *  - [show]：不满足时整行收起（子选项跟着上面的开关 / 选项走，比如发光关了就不显示发光强度）
     *  - [dep]：不满足时变暗但还在（受别的分组、别的标签页影响的，比如原声模式下的环境音效）
     * scale：显示值 = 实际值 × scale（百分比的选项是 100），手动输入时按显示值换算回去
     */
    private class Row(
        val label: String, val desc: String, val value: Any, val ctl: Ctl,
        val dep: (() -> Boolean)?, val fmt: (Any) -> String, val scale: Float = 1f, val key: String = "",
        val show: (() -> Boolean)? = null,
    )

    /**
     * owner：所属模块（模块关掉时整组变暗）；toggle：组标题右边显示模块总开关。
     * [show]：不满足时整组收起（比如只用驾驶舱歌词时 3D 歌词的几组）；
     * [dim]：不满足时整组变暗，标题旁边换成 [note] 说明原因
     */
    private class Group(
        val title: String, val sub: String, val owner: Module, val toggle: Boolean, val rows: List<Row>,
        val show: (() -> Boolean)? = null,
        val dim: (() -> Boolean)? = null,
        val note: (() -> String?)? = null,
    )

    /** 用 ◀ ▶ 切换的选项：设置名 → 每个值的显示名（从最小值开始） */
    private val choiceLabels = mapOf(
        "lyricColors" to listOf("Custom color", "Cover color", "Cover gradient", "Rainbow"),
        "ktvLyricColors" to listOf("Custom color", "Cover color", "Cover gradient", "Rainbow"),
        "audioOutput" to listOf("Auto", "Java Sound", "Game audio (OpenAL)"),
        "uiStyle" to listOf("Classic", "Continuous corners"),
        "lyricStyle" to listOf("3D world lyrics", "Cockpit lyrics", "Both", "Hidden"),
        "sidePosition" to listOf("Left middle", "Left bottom", "Right middle"),
        "sideColors" to listOf("Cover color", "Custom color", "Cover gradient", "Rainbow", "Aurora"),
        "shareQuality" to listOf("Standard (128k)", "Higher (192k)", "High (320k)"),
    )

    /** 显示成百分比的选项 */
    private val percentKeys = setOf(
        "volume", "glowRadius", "posX", "posY",
        "sideOpacity", "sideTint", "sideBrightness", "sideOtherLines", "sideGlowStrength", "sideRefraction",
        "sideInertiaStrength",
    )

    private val tabs: List<List<Group>> by lazy { buildSettings() }

    /** 本帧画了设置列表的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()
    // 平滑滚动（逻辑像素）
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f

    // 滑条拖动
    private var dragValue: Any? = null
    private var dragTrack = LayoutBounds()

    // 点数值手动输入
    private var editRow: Row? = null
    private var editText = ""
    private var editRect = LayoutBounds()

    // 正在录快捷键的那一项，以及本帧所有快捷键按钮的区域（点别处就取消录制）
    private var pendingModifierKey = Keys.NONE
    var bindingKey: IntValue? = null
        set(value) {
            field = value
            pendingModifierKey = Keys.NONE
        }
    private val keyButtons = mutableListOf<LayoutBounds>()
    private val keyResetButtons = mutableListOf<Hit>()

    val editing: Boolean get() = editRow != null || hexEditing

    // ---------------------------------------------------------------- 取色器（颜色选项点开后的小面板）

    /** 正在改的颜色；null = 没打开 */
    private var picker: ColorValue? = null
    /** 这个颜色那一行在屏幕上的位置（面板贴着它弹出来） */
    private var pickerAnchor = LayoutBounds()
    /** 色相 0..360、饱和度 0..1、亮度 0..1 */
    private var pickH = 0f
    private var pickS = 0f
    private var pickV = 1f
    /** 正在拖哪一条：0 没有，1 色相，2 饱和度，3 亮度 */
    private var pickerDrag = 0
    private val pickerBars = arrayOf(LayoutBounds(), LayoutBounds(), LayoutBounds())
    /** 本帧取色器（和颜色那一行）占的点击区域：点到这些以外的地方就收起面板 */
    private val pickerHits = mutableListOf<LayoutBounds>()
    private var hexEditing = false
    private var hexText = ""
    private val presets = intArrayOf(0xF7E1D7, 0xFFFFFF, 0xFFB3C8, 0xFF5C8A, 0xFFD27A, 0xA8E6CF, 0x8EC5FF, 0xC5A3FF)


    // 标签切换：红色选中块的位置（会被拉长一点，像液体）、内容过渡的进度、从哪个标签切过来、旧标签的滚动位置
    private val tabPill = Spring(300f, 23f, ScreenState.settingsTab.toFloat())
    private val tabFade = Spring(210f, 24f, 1f)
    private var tabFrom = -1
    private var tabFromPos = 0f

    /** 每个设置项的亮度（变暗 / 变亮的渐变） */
    private val rowFade = HashMap<Row, Spring>()
    /** 语言：红色选中块的位置 */
    private val langPill = HashMap<IntValue, Spring>()

    // ---------------------------------------------------------------- 每帧

    fun tick() {
        val key = pendingModifierKey
        val binding = bindingKey
        if (binding != null && key != Keys.NONE && !DangoClient.platform.isKeyDown(key)) {
            module.rebindKey(binding, key)
            bindingKey = null
        }
        pos = ui.approach(pos, target, 70f)
        // 滑条：按住拖动
        if (dragValue != null) {
            if (DangoClient.platform.isMouseDown(0)) applyDrag() else dragValue = null
        }
        if (pickerDrag != 0) {
            if (DangoClient.platform.isMouseDown(0)) dragPicker() else pickerDrag = 0
        }
    }

    fun beginFrame() {
        pickerHits.clear()
        fontHits.clear()
        fontAnchorSeen = false
        keyButtons.clear()
        keyResetButtons.clear()
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
    }

    fun scroll(wheel: Int) {
        if (fontOpen && fontPanel.contains(ui.mx, ui.my)) {
            fontScroll = (fontScroll + (if (wheel > 0) -66f else 66f)).coerceIn(0f, fontMaxScroll)
            return
        }
        target = (target + (if (wheel > 0) -60f else 60f) * (if (DangoClient.platform.ctrlDown) 4f else 1f)).coerceIn(0f, maxScroll)
    }

    fun stopDrag() {
        dragValue = null
    }

    // ---------------------------------------------------------------- 数值输入

    private fun numberText(r: Row): String = when (val v = r.value) {
        is IntValue -> (v.value * r.scale).roundToInt().toString()
        is FloatValue -> {
            val x = v.value * r.scale
            if (r.scale != 1f || v.decimals == 0) x.roundToInt().toString() else "%.${v.decimals}f".format(x)
        }
        else -> ""
    }

    private fun startEdit(r: Row) {
        if (editRow === r) return
        commitEdit()
        editRow = r
        editText = numberText(r)
        screen.blurTextFields()
        bindingKey = null
    }

    /** 输入的数字写回去（超出范围的自动夹到范围内，乱输的忽略） */
    fun commitEdit() {
        val r = editRow ?: return
        editRow = null
        val n = editText.trim().replace(',', '.').toFloatOrNull() ?: return
        when (val v = r.value) {
            is IntValue -> v.setValue((n / r.scale).roundToInt())
            is FloatValue -> v.setValue(n / r.scale)
        }
    }

    /** 点到别处时：收起快捷键录制和数值输入 */
    fun onClickElsewhere(hit: Hit?) {
        if (picker != null && (hit == null || pickerHits.none { it === hit.first })) closePicker()
        if (fontOpen && (hit == null || fontHits.none { it === hit.first })) fontOpen = false
        if (bindingKey != null && (hit == null || hit.first !in keyButtons)) bindingKey = null
        if (editRow != null && (hit == null || hit.first != editRect.intersect(area))) commitEdit()
    }

    // ---------------------------------------------------------------- 选项列表

    private fun fieldsOf(m: Module): LinkedHashMap<String, Pair<ModuleValue, Any>> {
        val out = LinkedHashMap<String, Pair<ModuleValue, Any>>()
        for (f in m.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            f.isAccessible = true
            val v = f.get(m) ?: continue
            out[f.name] = ann to v
        }
        return out
    }

    private fun fmtValue(v: Any): String = when (v) {
        is IntValue -> v.value.toString()
        is FloatValue -> "%.${v.decimals}f".format(v.value)
        else -> ""
    }

    private fun percent(v: Any): String = "${((v as FloatValue).value * 100f).roundToInt()}%"

    private fun buildSettings(): List<List<Group>> {
        val mm = DangoMusic.instance.moduleManager

        /** 一个模块的设置；take(...) 按名字取出若干行，剩下的最后放进 "Other" */
        class Src(
            val m: Module,
            /** 子选项 → 它上面的开关：开关关着时这一行收起 */
            val deps: Map<String, String> = emptyMap(),
            val fmts: Map<String, (Any) -> String> = emptyMap(),
            /** 收起的条件不是某个开关、而是某个选项的取值：设置名 → 什么时候显示 */
            val shows: Map<String, () -> Boolean> = emptyMap(),
            /** 受别的分组 / 标签页影响、用不上时变暗的行：设置名 → 什么时候有意义 */
            val conds: Map<String, () -> Boolean> = emptyMap(),
        ) {
            val all = fieldsOf(m)
            val left = LinkedHashMap(all)
            fun take(names: List<String>? = null): List<Row> = (names ?: left.keys.toList()).mapNotNull { k ->
                val (ann, v) = left.remove(k) ?: return@mapNotNull null
                val ctl = when {
                    k == "language" -> Ctl.LANGUAGE
                    k == "lyricFont" || k == "sideFont" -> Ctl.FONT
                    k in choiceLabels -> Ctl.CHOICE
                    v is BooleanValue -> Ctl.TOGGLE
                    k == "openKey" || (k.startsWith("key") && v is IntValue) -> Ctl.KEY
                    v is ColorValue -> Ctl.COLOR
                    v is IntValue || v is FloatValue -> Ctl.SLIDER
                    else -> return@mapNotNull null
                }
                val parent = deps[k]?.let { all[it]?.second as? BooleanValue }
                val custom = shows[k]
                val show: (() -> Boolean)? = when {
                    parent != null && custom != null -> ({ parent.get() && custom() })
                    parent != null -> ({ parent.get() })
                    else -> custom
                }
                // 歌词字体这一行同时管"平滑字体"开关（像素字体 = 关）
                val value = if (ctl == Ctl.FONT) {
                    FontChoice(v as TextValue, all.getValue(if (k == "lyricFont") "smoothFont" else "sideSmoothFont").second as BooleanValue)
                } else v
                Row(ann.name.ifEmpty { k }, ann.desc, value, ctl, conds[k], fmts[k] ?: ::fmtValue, if (k in percentKeys) 100f else 1f, k, show)
            }
        }

        fun bool(m: Module, name: String) = fieldsOf(m).getValue(name).second as BooleanValue
        val degrees = { v: Any -> "${(v as FloatValue).value.roundToInt()}°" }
        val signed = { v: Any -> (v as FloatValue).value.roundToInt().let { if (it > 0) "+$it" else "$it" } }

        // ---------------------------------------------------------------- 歌词
        // “歌词样式”是唯一的总开关：3D 世界 / 驾驶舱 / 两者 / 不显示；用不上的那几组整组收起
        val lm = mm.get(LyricsModule::class.java)
        val style = lm.lyricStyle
        val world = { style.value == 0 || style.value == 2 }
        val side = { style.value == 1 || style.value == 2 }
        val anyLyrics = { style.value != 3 }
        val colors = lm.lyricColors
        val ly = Src(
            lm,
            deps = mapOf(
                "glowNightOnly" to "glow", "glowStrength" to "glow", "glowRadius" to "glow",
                "wiggleFps" to "wiggle", "wiggleAmount" to "wiggle",
                "flashSize" to "textFlash",
                "ktvLyricColors" to "ktvMode", "ktvInstrumental" to "ktvMode", "ktvBaseColor" to "ktvMode",
                "ktvBaseShadow" to "ktvMode", "ktvSungShadow" to "ktvMode",
                "danceAuto" to "dance", "danceTap" to "dance", "danceSpin" to "dance", "danceAmount" to "dance", "danceOffset" to "dance",
                "translationAnimation" to "translation", "translationWiggle" to "translation", "translationColor" to "translation",
                "showCover" to "nowPlaying",
                "sideRefraction" to "sideGlass",
                "sideInertiaStrength" to "sideInertia",
            ),
            fmts = mapOf(
                "spread" to degrees,
                "glowRadius" to ::percent,
                "danceOffset" to { v: Any -> "${(v as IntValue).value} ms" },
                "sideTilt" to degrees, "sidePitch" to degrees, "sideItalic" to degrees,
                "sideOffsetX" to signed, "sideOffsetY" to signed,
            ),
            shows = mapOf(
                // 拆分歌词改的是歌词数据本身（歌词页也受影响），不随样式收起
                "translation" to anyLyrics, "autoContrast" to anyLyrics,
                "sideColor" to { lm.sideColors.value == 1 },
                // 音译 3D 和驾驶舱都有；翻译的动画只有 3D 歌词有
                "romanization" to anyLyrics, "translationAnimation" to world, "translationWiggle" to world,
                "textColor" to { colors.value == 0 },
                "translationColor" to { colors.value == 0 },
                "rainbowSpeed" to { colors.value == 3 },
                "ktvSungColor" to { lm.ktvMode.get() && lm.ktvLyricColors.value == 0 },
                "sideGlowStrength" to { lm.sideGlow.get() || lm.sideTextGlow.get() },
            ),
        )
        ly.left.remove("smoothFont")   // 合进了"歌词字体"那一行
        ly.left.remove("sideSmoothFont") // 合进了"驾驶舱字体"那一行
        ly.left.remove("worldMode")    // 由“歌词样式”统一管（旧存档里关着的话，启动时换算成“不显示”）
        ly.left.remove("styleBeforeOff") // 快捷键内部记的值
        val lyrics = mutableListOf(
            Group("Lyrics", "Style and translation", lm, false,
                ly.take(listOf("lyricStyle", "autoContrast", "translation", "romanization", "translationAnimation", "translationWiggle", "splitLines"))),
            Group("Cockpit lyrics", "Position and tilt", lm, false, ly.take(listOf(
                "sidePosition", "sideOffsetX", "sideOffsetY", "sideWidth", "sideLines", "sideTextSize", "sideLineSpacing",
                "sideTilt", "sidePitch", "sideItalic", "sideGuide", "sideInertia", "sideInertiaStrength",
            )), show = side),
            Group("Cockpit look", "Font and colors", lm, false, ly.take(listOf(
                "sideFont", "sideColors", "sideColor", "sideTint", "sideBrightness", "sideOpacity", "sideOtherLines",
            )), show = side),
            Group("Cockpit glow", "Glow and glass", lm, false, ly.take(listOf(
                "sideGlow", "sideTextGlow", "sideGlowStrength", "sideGlass", "sideRefraction",
            )), show = side),
            Group("3D lyrics", "Font and colors", lm, false,
                ly.take(listOf("scale", "maxLyricLines", "lyricFont", "lyricColors", "textColor", "translationColor", "rainbowSpeed")), show = world),
            Group("3D position", "Where the lyrics float", lm, false,
                ly.take(listOf("distance", "facePlayer", "keepAway", "stayOnScreen", "aboveGround", "avoidBlocks", "spread")), show = world),
            Group("Glow", "Deep Glow", lm, false, ly.take(listOf("glow", "glowNightOnly", "glowStrength", "glowRadius")), show = world),
            Group("Motion", "Animation", lm, false, ly.take(listOf("wiggle", "wiggleFps", "wiggleAmount", "wordPop", "textFlash", "flashSize")), show = world),
            Group("KTV mode", "Line-by-line highlight", lm, false, ly.take(listOf(
                "ktvMode", "ktvInstrumental", "ktvLyricColors", "ktvBaseColor", "ktvSungColor", "ktvBaseShadow", "ktvSungShadow",
            )), show = world),
            Group("Dance", "Mobs on the beat", lm, false, ly.take(listOf("dance", "danceAuto", "danceTap", "danceSpin", "danceAmount", "danceOffset"))),
        )
        // 左上角的原版风格"正在播放"和 Song card 是同一类东西，放到 Music & HUD 里
        val nowPlaying = Group("Song info", "Top left, classic style", lm, false, ly.take(listOf("nowPlaying", "showCover")))
        ly.take().let { if (it.isNotEmpty()) lyrics += Group("Other", "More", lm, false, it) }

        // ---------------------------------------------------------------- 音乐与 HUD
        val original = bool(module, "originalAudio")
        val player = Src(module,
            deps = mapOf("envRain" to "envAudio", "envStrength" to "envAudio", "envRays" to "envAudio"),
            fmts = mapOf("volume" to ::percent, "envStrength" to ::percent),
            // 原声模式绕过音量均衡，开着时均衡这一行收起
            shows = mapOf("normalize" to { !original.get() }))
        player.left.remove("shuffle")   // 随机播放用播放栏上的按钮就够了，设置页不再显示（状态照样保存）
        player.left.remove("repeatOne") // 单曲循环同样通过播放栏控制
        player.left.remove("musicSource") // 音源通过侧栏切换。
        player.left.remove("audioQuality") // 网易云音质从底部播放栏选择。
        for (k in listOf("windowX", "windowY", "windowW", "windowH", "lyricsPageSize", "lyricsPageTranslation", "lyricsPageRomanization")) {
            player.left.remove(k) // 在窗口或歌词页内调整。
        }
        if (!DangoMusic.instance.platform.hasGameAudio) player.left.remove("audioOutput") // 1.8.9 只有 Java Sound
        val islandModule = mm.get(DynamicIslandModule::class.java)
        val island = Src(islandModule, deps = mapOf("notifyTime" to "songNotify"), fmts = mapOf(
            "posX" to ::percent, "posY" to ::percent,
            "notifyTime" to { v: Any -> "%.1f s".format((v as FloatValue).value) },
        ))
        val mergeCard = bool(islandModule, "mergeCoverProgress")
        val showMusic = bool(islandModule, "showMusic")
        // 和 DynamicIslandModule.showingMergedCard 一致：岛开着、显示正在播放、并且合并了，卡片才不单独显示
        val merged = { islandModule.enabled && showMusic.get() && mergeCard.get() }
        val card = Src(mm.get(SongCardModule::class.java), fmts = mapOf("posX" to ::percent, "posY" to ::percent))
        val playback = player.take(listOf("volume", "originalAudio", "normalize", "playbackFades", "resumeSession"))
        val environment = player.take(listOf("envAudio", "envRain", "envStrength", "envRays"))
        val looks = player.take(listOf("uiStyle", "language"))
        val advanced = player.take(listOf("audioOutput", "audioBuffer"))
        val music = listOf(
            Group("Player", "Playback", module, false, playback),
            Group("Environment audio", "Sound around you", module, false, environment,
                dim = { original.get() }, note = { if (original.get()) "Off while Original audio is on" else null }),
            Group("Dynamic Island", "Overlay", islandModule, true, island.take(listOf(
                "glass", "showMusic", "mergeCoverProgress", "songNotify", "notifyTime", "scale", "posX", "posY",
            )) + island.take()),
            Group("Cover progress HUD", "Separate position when not merged", card.m, true,
                card.take(listOf("scale", "showPaused", "posX", "posY")) + card.take(),
                dim = merged, note = { if (merged()) "Merged into the Dynamic Island" else null }),
            nowPlaying,
            Group("Interface", "Look and language", module, false, looks),
            Group("Advanced", "Output and buffering", module, false, advanced),
            Group("Hotkeys", "Click, then press a key · Backspace clears", module, false, player.take()),
        )

        // ---------------------------------------------------------------- 多人
        val nearbyModule = mm.get(NearbyModule::class.java)
        val shareMusic = bool(nearbyModule, "shareMusic")
        val shareGroup = bool(nearbyModule, "shareGroup")
        val nearby = Src(nearbyModule,
            deps = mapOf("resumeAfterGroup" to "groupTakeover"),
            // 音质对附近共享和听歌房共享都有效，两个都关着时收起
            shows = mapOf("shareQuality" to { shareMusic.get() || shareGroup.get() }),
            // 驾驶舱歌词和 3D 投影互斥：只用驾驶舱时不投影自己的歌词；用驾驶舱时别人的歌词显示在自己的列表里
            conds = mapOf("shareLyrics" to { style.value != 1 }, "seeLyrics" to { !side() && style.value != 3 }))
        val nearbyGroups = mutableListOf(
            Group("Nearby music", "Players within the server radius", nearbyModule, false,
                nearby.take(listOf("shareMusic", "shareQuality", "hearNearby"))),
            Group("Nearby lyrics", "Line projection over the host", nearbyModule, false,
                nearby.take(listOf("shareLyrics", "seeLyrics")),
                note = {
                    when (style.value) {
                        1 -> "Cockpit lyrics: yours aren't projected, others' show in your list"
                        2 -> "Others' lyrics show in your cockpit list"
                        3 -> "Lyric style is set to Hidden"
                        else -> null
                    }
                }),
            Group("Music group", "Distance-free listening", nearbyModule, false,
                nearby.take(listOf("shareGroup", "groupTakeover", "resumeAfterGroup"))),
        )
        // 收听听歌房由进出房间决定，不再单独给开关；“在房间里放歌时共享”只在这里保留一份（听歌房页面不再重复）
        nearby.left.remove("hearGroup")
        nearby.take().let { if (it.isNotEmpty()) nearbyGroups += Group("Other", "More", nearbyModule, false, it) }
        return listOf(lyrics, music, nearbyGroups)
    }

    // ---------------------------------------------------------------- 绘制

    fun draw(page: LayoutBounds) {
        ui.headerAnim {
            ui.text(I18n.text("Settings"), page.x, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            ui.text(I18n.text("Changes apply instantly and are saved automatically"), page.x, page.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            drawTabs(page)
            drawResetButton(page)
        }

        val top = page.y + 54f
        val view = LayoutBounds(page.x, top, page.width, page.bottom - top)
        area = view
        val tab = ScreenState.settingsTab.coerceIn(0, tabs.size - 1)
        val contentH = layoutHeight(tabs[tab], view)
        maxScroll = max(0f, contentH + ui.bottomInset - view.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)

        // 切标签：旧内容往一边滑走淡出，新内容从另一边滑进来淡入（弹簧，略带回弹）
        val t = tabFade.tick(1f)
        val from = tabFrom
        ui.beginClip(view)
        if (from >= 0 && from != tab && t < 0.999f) {
            val dir = if (tab > from) 1f else -1f
            val out = (1f - t * 1.6f).coerceIn(0f, 1f)
            if (out > 0.01f) {
                // 旧内容只是画出来，点击区域不保留
                val keep = ui.hits.size
                ui.shifted(-dir * 36f * t.coerceIn(0f, 1f), 0f, out) { drawTab(tabs[from], view, tabFromPos, animate = false) }
                while (ui.hits.size > keep) ui.hits.removeAt(ui.hits.size - 1)
            }
            ui.shifted(dir * 36f * (1f - t), 0f, t.coerceIn(0f, 1f)) { drawTab(tabs[tab], view, pos, animate = false) }
        } else {
            tabFrom = -1
            drawTab(tabs[tab], view, pos, animate = true)
        }
        ui.endClip()

        // 滚动条（只显示位置）
        if (maxScroll > 0f && contentH > 0f) {
            val th = max(30f, view.height * view.height / contentH)
            val ty = view.y + (view.height - th) * (pos / maxScroll)
            ui.ctx.drawShape(LayoutBounds(view.right - 4f, ty, 4f, th), ShapeStyle(Color(1f, 1f, 1f, 0.30f), 2f))
        }
        if (picker != null || fontChoice != null) ui.mt.flush(ui.scale)
        drawPicker(view)
        drawFontList(view)
    }

    // ---------------------------------------------------------------- 取色器

    private fun rgb(c: Int) = Color((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f, 1f)

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return ((r * 255f).roundToInt() shl 16) or ((g * 255f).roundToInt() shl 8) or (b * 255f).roundToInt()
    }

    /** 从颜色算回色相 / 饱和度 / 亮度（灰色时保留原来的色相，拖饱和度不会跳） */
    private fun setPick(c: Int) {
        val r = (c shr 16 and 255) / 255f
        val g = (c shr 8 and 255) / 255f
        val b = (c and 255) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        pickV = mx
        pickS = if (mx <= 0f) 0f else d / mx
        if (d > 0f) {
            pickH = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (pickH < 0f) pickH += 360f
        }
    }

    private fun openPicker(v: ColorValue) {
        commitEdit()
        bindingKey = null
        picker = v
        hexEditing = false
        setPick(v.value)
    }

    fun closePicker() {
        if (hexEditing) applyHex()
        picker = null
        pickerDrag = 0
        hexEditing = false
    }

    private fun applyHex() {
        hexEditing = false
        val v = picker ?: return
        val c = ColorValue.parse(hexText) ?: return
        v.setValue(c)
        setPick(c)
    }

    private fun dragPicker() {
        val v = picker ?: return
        val bar = pickerBars[pickerDrag - 1]
        if (bar.width <= 0f) return
        val p = ((ui.mx - bar.x) / bar.width).coerceIn(0f, 1f)
        when (pickerDrag) {
            1 -> pickH = p * 360f
            2 -> pickS = p
            3 -> pickV = p
        }
        v.setValue(hsv(pickH, pickS, pickV))
    }

    /** 在设置列表上面画取色器：预览 + 十六进制、色相 / 饱和度 / 亮度三条、几个常用色；点击区域放在最前面，优先处理 */
    private fun drawPicker(view: LayoutBounds) {
        val v = picker ?: return
        val ctx = ui.ctx
        val w = 224f
        val h = 178f
        var px = pickerAnchor.right - w
        var py = pickerAnchor.bottom + 2f
        if (py + h > view.bottom) py = pickerAnchor.y - h - 2f
        px = px.coerceIn(view.x, view.right - w - 8f)
        py = py.coerceIn(view.y, max(view.y, view.bottom - h))
        val panel = LayoutBounds(px, py, w, h)
        ui.registerPopup(panel)
        ctx.drawShape(panel, ShapeStyle(Color(0.11f, 0.11f, 0.13f, 0.98f), 12f, 1f, Color(1f, 1f, 1f, 0.14f)))
        val wasPopup = ui.inPopup
        ui.inPopup = true
        val hits = ArrayList<Hit>()
        val x = px + 12f
        val iw = w - 24f
        var y = py + 12f

        // 预览 + 十六进制（点一下可以直接输入）+ 恢复默认
        ctx.drawShape(LayoutBounds(x, y, 28f, 28f), ShapeStyle(rgb(v.value), 8f, 1f, Color(1f, 1f, 1f, 0.35f)))
        val hexBox = LayoutBounds(x + 36f, y + 2f, 86f, 24f)
        ui.card(hexBox, if (hexEditing) Theme.CARD_HOVER else Theme.CARD, 6f)
        if (hexEditing) ctx.drawShape(hexBox, ShapeStyle(Color(0f, 0f, 0f, 0f), 6f, 1f, RED))
        val hexShown = if (hexEditing) "#" + hexText + (if (caretVisible()) "|" else "") else v.hex()
        ui.text(hexShown, hexBox.x + 8f, hexBox.y + 12f - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        hits += hexBox to {
            if (!hexEditing) {
                hexEditing = true
                hexText = v.hex().removePrefix("#")
            }
        }
        val reset = I18n.text("Reset")
        val resetBox = LayoutBounds(px + w - 12f - ui.width(reset, 11f, Theme.FONT_MEDIUM) - 16f, y + 2f, ui.width(reset, 11f, Theme.FONT_MEDIUM) + 16f, 24f)
        ui.card(resetBox, if (ui.hover(resetBox)) Theme.CARD_HOVER else Theme.CARD, 6f)
        ui.text(reset, resetBox.x + 8f, resetBox.y + 12f - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        hits += resetBox to {
            v.reset()
            setPick(v.value)
            hexEditing = false
        }
        y += 40f

        // 三条：色相（彩虹）、饱和度（灰 → 纯色）、亮度（黑 → 颜色）；拖动或点一下
        val segs = intArrayOf(36, 20, 20)
        for (bi in 0..2) {
            val bar = LayoutBounds(x, y, iw, 12f)
            pickerBars[bi] = bar
            val n = segs[bi]
            val sw = bar.width / n
            for (k in 0 until n) {
                val t = (k + 0.5f) / n
                val c = when (bi) {
                    0 -> hsv(t * 360f, 1f, 1f)
                    1 -> hsv(pickH, t, pickV.coerceAtLeast(0.15f))
                    else -> hsv(pickH, pickS, t)
                }
                val radius = if (k == 0 || k == n - 1) 6f else 0f
                ctx.drawShape(LayoutBounds(bar.x + k * sw, bar.y, sw + 0.6f, bar.height), ShapeStyle(rgb(c), radius))
            }
            val knob = when (bi) { 0 -> pickH / 360f; 1 -> pickS; else -> pickV }
            val kx = bar.x + bar.width * knob.coerceIn(0f, 1f)
            ctx.drawShape(LayoutBounds(kx - 7f, bar.y - 1f, 14f, 14f), ShapeStyle(rgb(v.value), 7f, 2f, Color(1f, 1f, 1f, 1f)))
            hits += bar.expand(4f, 6f) to {
                pickerDrag = bi + 1
                hexEditing = false
                dragPicker()
            }
            y += 26f
        }

        // 常用色
        y += 4f
        presets.forEachIndexed { i, c ->
            val sb = LayoutBounds(x + i * (iw - 18f) / (presets.size - 1), y, 18f, 18f)
            val sel = v.value == c
            if (sel) ctx.drawShape(sb.expand(3f, 3f), ShapeStyle(Color(0f, 0f, 0f, 0f), 12f, 1.5f, Theme.TEXT_PRIMARY))
            ctx.drawShape(sb, ShapeStyle(rgb(c), 9f, 1f, Color(1f, 1f, 1f, 0.25f)))
            hits += sb.expand(3f, 3f) to {
                v.setValue(c)
                setPick(c)
                hexEditing = false
            }
        }

        // 面板本身吃掉点击（点空白处不会点到下面的选项）；点击区域插到最前面，比设置列表优先
        ui.inPopup = wasPopup
        hits += panel to {}
        for (hh in hits) pickerHits += hh.first
        ui.hits.addAll(0, hits)
    }

    // 两列瀑布流：每组放进当前较矮的一列。组 / 行收起、展开，以及因此换列、上下挪动，都用弹簧过渡
    private val gap = 14f
    private val rowH = 40f
    private val headH = 36f

    /** 一组这一帧的位置：col 是列号（过渡时是小数），y 相对内容顶部，vis 是整组的显示程度 */
    private class Placed(val g: Group, val col: Float, val y: Float, val h: Float, val vis: Float)

    private val groupVis = HashMap<Group, Spring>()
    private val groupCol = HashMap<Group, Spring>()
    private val groupY = HashMap<Group, Spring>()
    private val rowVis = HashMap<Row, Spring>()

    private fun place(groups: List<Group>, view: LayoutBounds): List<Placed> {
        val cols = if (view.width >= 560f) 2 else 1
        val colY = FloatArray(cols)
        return groups.map { g ->
            val shown = g.show?.invoke() != false
            val vis = groupVis.getOrPut(g) { Spring(240f, 28f, if (shown) 1f else 0f) }.tick(if (shown) 1f else 0f)
            var rows = 0f
            var targetRows = 0
            for (r in g.rows) {
                val on = shown && r.show?.invoke() != false
                if (on) targetRows++
                rows += rowVis.getOrPut(r) { Spring(260f, 30f, if (on) 1f else 0f) }.tick(if (on) 1f else 0f)
            }
            val h = (headH + 6f) * vis + rows * rowH
            val colS = groupCol.getOrPut(g) { Spring(200f, 26f) }
            val yS = groupY.getOrPut(g) { Spring(200f, 26f) }
            if (shown) {
                // 按收起 / 展开之后的高度排，不按过渡中的高度排：排列不会跟着动画来回跳
                var c = 0
                for (i in 1 until cols) if (colY[i] < colY[c] - 0.5f) c = i
                val ty = colY[c]
                colY[c] += headH + 6f + targetRows * rowH + gap
                if (vis < 0.02f) {
                    // 从完全收起的状态出现：直接在新位置出现，不从别处飞过来
                    colS.x = c.toFloat(); colS.v = 0f
                    yS.x = ty; yS.v = 0f
                }
                Placed(g, colS.tick(c.toFloat()), yS.tick(ty), h, vis)
            } else {
                // 收起中：原地淡出
                Placed(g, colS.tick(colS.x), yS.tick(yS.x), h, vis)
            }
        }
    }

    private fun layoutHeight(groups: List<Group>, view: LayoutBounds): Float =
        max(0f, place(groups, view).maxOfOrNull { if (it.vis > 0.02f) it.y + it.h else 0f } ?: 0f)

    /** 画一个标签的全部分组；[animate] 时每组按进页面的时间依次升起 */
    private fun drawTab(groups: List<Group>, view: LayoutBounds, scroll: Float, animate: Boolean) {
        val cols = if (view.width >= 560f) 2 else 1
        val colW = (view.width - 12f - gap * (cols - 1)) / cols
        val placed = place(groups, view)
        placed.forEachIndexed { gi, p ->
            if (p.vis < 0.02f) return@forEachIndexed
            val b = LayoutBounds(view.x + p.col * (colW + gap), view.y + p.y - scroll, colW, p.h)
            if (b.bottom < view.y || b.y > view.bottom) return@forEachIndexed
            if (animate) ui.itemAnim(gi) { drawGroup(b, p, view) } else drawGroup(b, p, view)
        }
    }

    /** 标签：Lyrics / Music & HUD。红色选中块用弹簧滑过去，滑动时被拉长一点；文字颜色跟着渐变 */
    private fun drawTabs(page: LayoutBounds) {
        val names = listOf(I18n.text("Lyrics"), I18n.text("Music & HUD"), I18n.text("Multiplayer"))
        val tabW = 96f
        val bar = LayoutBounds(page.right - tabW * names.size - 8f, page.y + 2f, tabW * names.size + 8f, 32f)
        ui.card(bar, Theme.CARD, 11f)
        val p = tabPill.tick(ScreenState.settingsTab.toFloat())
        names.forEachIndexed { i, _ ->
            val b = LayoutBounds(bar.x + 4f + i * tabW, bar.y + 4f, tabW, 24f)
            if (i != ScreenState.settingsTab && ui.hover(b)) ui.card(b, Theme.CARD_HOVER, 8f)
        }
        val stretch = kotlin.math.min(18f, kotlin.math.abs(tabPill.v) * 6f)
        val px = bar.x + 4f + p.coerceIn(-0.06f, names.size - 0.94f) * tabW - (if (tabPill.v > 0f) stretch else 0f)
        ui.ctx.drawShape(LayoutBounds(px, bar.y + 4f, tabW + stretch, 24f), ShapeStyle(RED, 8f))
        names.forEachIndexed { i, n ->
            val b = LayoutBounds(bar.x + 4f + i * tabW, bar.y + 4f, tabW, 24f)
            val on = (1f - kotlin.math.abs(p - i)).coerceIn(0f, 1f)
            val w = ui.width(n, 12f, Theme.FONT_SEMIBOLD)
            ui.text(n, b.x + (b.width - w) / 2f, b.y + 12f - 12f * 0.39f - 0.5f, 12f,
                mixColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, on), Theme.FONT_SEMIBOLD)
            ui.hits += b to {
                if (ScreenState.settingsTab != i) {
                    commitEdit()
                    tabFrom = ScreenState.settingsTab
                    tabFromPos = pos
                    tabFade.x = 0f
                    tabFade.v = 0f
                    ScreenState.settingsTab = i
                    resetScroll()
                    bindingKey = null
                    closeFontListNow()
                    closePicker()
                }
            }
        }
    }

    private fun drawGroup(b: LayoutBounds, p: Placed, clip: LayoutBounds) {
        val g = p.g
        val ctx = ui.ctx
        // 整组出现 / 收起时一起淡入淡出（平方：收起时先淡掉，再让出位置）
        val groupAlpha = p.vis * p.vis
        val fadedGroup = groupAlpha < 0.999f
        if (fadedGroup) ctx.pushOpacity(groupAlpha)
        try {
            ctx.drawShape(b, ShapeStyle(Color(1f, 1f, 1f, 0.05f), 14f))
            val x = b.x + 14f
            val iw = b.width - 28f
            val title = I18n.text(g.title)
            val titleW = ui.width(title, 13f, Theme.FONT_SEMIBOLD)
            ui.text(title, x, b.y + 13f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            // 有说明（比如“已合并进灵动岛”）时代替副标题，说明这组为什么用不上
            val note = g.note?.invoke()
            val subW = iw - titleW - 8f - (if (g.toggle) ui.switchWidth + 10f else 0f)
            if (subW > 20f) {
                ui.text(ui.trim(I18n.text(note ?: g.sub), subW, 10f), x + titleW + 8f, b.y + 14.5f, 10f,
                    if (note != null) Theme.TEXT_SECONDARY else Theme.TEXT_TERTIARY)
            }
            if (g.toggle) {
                val m = g.owner
                ui.toggleSwitch(LayoutBounds(b.right - 14f - ui.switchWidth, b.y + 9f, ui.switchWidth, 20f), m.enabled, clip, key = m) { m.enabled = !m.enabled }
            }
            var y = b.y + headH
            val groupDim = !g.owner.enabled || g.dim?.invoke() == true
            for (r in g.rows) {
                val rv = rowVis[r]?.x ?: 1f
                if (rv < 0.02f) continue
                // 依赖的开关 / 整个模块关掉时变暗：渐变过去，不是一下子变
                val dim = groupDim || (r.dep != null && !r.dep.invoke())
                val target = if (dim) 0.4f else 1f
                val alpha = rowFade.getOrPut(r) { Spring(170f, 26f, target) }.tick(target).coerceIn(0.4f, 1f)
                // 展开 / 收起：位置先让出来，字再淡入；还没完全展开的行不接受点击
                val appear = ((rv - 0.4f) / 0.6f).coerceIn(0f, 1f)
                val a = alpha * appear
                if (a > 0.01f) {
                    val faded = a < 0.999f
                    if (faded) ctx.pushOpacity(a)
                    val keep = ui.hits.size
                    try {
                        ctx.drawShape(LayoutBounds(x, y, iw, 1f), ShapeStyle(Color(1f, 1f, 1f, 0.06f)))
                        drawRow(LayoutBounds(x, y, iw, rowH), r, clip)
                    } finally {
                        if (faded) ctx.popOpacity()
                    }
                    if (rv < 0.9f) while (ui.hits.size > keep) ui.hits.removeAt(ui.hits.size - 1)
                }
                y += rowH * rv
            }
        } finally {
            if (fadedGroup) ctx.popOpacity()
        }
    }

    private fun drawRow(b: LayoutBounds, r: Row, clip: LayoutBounds) {
        val ctlW = when (r.ctl) {
            Ctl.TOGGLE -> ui.switchWidth
            Ctl.SLIDER -> 166f
            Ctl.KEY -> 130f
            Ctl.LANGUAGE -> 142f
            Ctl.COLOR -> 92f
            Ctl.FONT -> 166f
            Ctl.CHOICE -> 144f
        }
        val textW = b.width - ctlW - 14f
        ui.text(ui.trim(I18n.text(r.label), textW, 12f, Theme.FONT_MEDIUM), b.x, b.y + 8f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        if (r.desc.isNotEmpty()) ui.text(ui.trim(I18n.text(r.desc), textW, 9.5f), b.x, b.y + 24f, 9.5f, Theme.TEXT_TERTIARY)
        val cy = b.y + b.height / 2f
        val cx = b.right - ctlW
        when (r.ctl) {
            Ctl.TOGGLE -> {
                val v = r.value as BooleanValue
                ui.toggleSwitch(LayoutBounds(cx, cy - 10f, ui.switchWidth, 20f), v.get(), clip, key = v) { v.toggle() }
            }
            Ctl.SLIDER -> if (r.key == "maxLyricLines") {
                val v = r.value as IntValue
                val max = module.lyrics()?.worldLyricLimit() ?: 1
                val current = v.value.coerceAtMost(max)
                stepper(clip, cx, cy, current.toString(), max > 1) { delta ->
                    v.setValue((current + delta).coerceIn(1, max))
                }
            } else drawSlider(b, r, clip, cx, cy)
            Ctl.KEY -> drawKeyButton(r.value as IntValue, clip, cx, cy)
            Ctl.COLOR -> {
                // 颜色块 + 十六进制值，点一下弹出取色器
                val v = r.value as ColorValue
                val open = picker === v
                val box = LayoutBounds(cx - 4f, cy - 13f, 96f, 26f)
                if (open || ui.hover(box)) ui.card(box, Theme.CARD_HOVER, 7f)
                ui.ctx.drawShape(LayoutBounds(cx, cy - 9f, 18f, 18f), ShapeStyle(rgb(v.value), 6f, 1f, Color(1f, 1f, 1f, 0.35f)))
                ui.text(v.hex(), cx + 26f, cy - 11f * 0.39f - 0.5f, 11f, if (open) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
                if (open) pickerAnchor = b
                val hit = box.intersect(clip)
                pickerHits += hit
                ui.hits += hit to { if (picker === v) closePicker() else openPicker(v) }
            }
            Ctl.FONT -> drawFontPicker(r.value as FontChoice, clip, cx, cy)
            Ctl.CHOICE -> {
                val v = r.value as IntValue
                val labels = choiceLabels.getValue(r.key)
                stepper(clip, cx, cy, I18n.text(labels[(v.value - v.minRange).coerceIn(0, labels.size - 1)]), labels.size > 1) { delta ->
                    v.setValue(v.minRange + Math.floorMod(v.value - v.minRange + delta, labels.size))
                }
            }
            Ctl.LANGUAGE -> {
                // 红色选中块用弹簧滑到另一个语言上
                val v = r.value as IntValue
                val sel = langPill.getOrPut(v) { Spring(320f, 24f, v.value.coerceAtLeast(0).toFloat()) }.tick(v.value.coerceAtLeast(0).toFloat())
                listOf("English", "中文").forEachIndexed { i, _ ->
                    val button = LayoutBounds(cx + i * 74f, cy - 12f, 68f, 24f)
                    ui.card(button, if (ui.hover(button) && v.value != i) Theme.CARD_HOVER else Theme.CARD, 7f)
                }
                if (v.value >= 0) ui.card(LayoutBounds(cx + sel * 74f, cy - 12f, 68f, 24f), RED, 7f)
                listOf("English", "中文").forEachIndexed { i, label ->
                    val button = LayoutBounds(cx + i * 74f, cy - 12f, 68f, 24f)
                    ui.text(label, button.x + (button.width - ui.width(label, 11f, Theme.FONT_MEDIUM)) / 2f,
                        cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                    ui.hits += button.intersect(clip) to {
                        v.setValue(i)
                        module.applyLanguage()
                    }
                }
            }
        }
    }

    /** 选项按钮：◀ 当前选项 ▶，点箭头往前 / 往后切 */
    private fun stepper(clip: LayoutBounds, cx: Float, cy: Float, label: String, enabled: Boolean, step: (Int) -> Unit) {
        val prev = LayoutBounds(cx, cy - 11f, 22f, 22f)
        val next = LayoutBounds(cx + 122f, cy - 11f, 22f, 22f)
        for ((button, delta) in listOf(prev to -1, next to 1)) {
            ui.card(button, if (ui.hover(button)) Theme.CARD_HOVER else Theme.CARD, 6f)
            PlayerIcons.draw(ui.ctx, if (delta < 0) PlayerIcons.chevronLeft else PlayerIcons.chevronRight, button.x + 11f, cy, 13f,
                if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY)
            ui.hits += button.intersect(clip) to { step(delta) }
        }
        val shown = ui.trim(label, 94f, 11f, Theme.FONT_MEDIUM)
        ui.text(shown, cx + 72f - ui.width(shown, 11f, Theme.FONT_MEDIUM) / 2f, cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
    }

    /** 字体这一行的值：字体 id（见 [LyricFonts]）+ 平滑字体开关（只有 3D 歌词有像素字体，驾驶舱是 null） */
    private class FontChoice(val font: TextValue, val smooth: BooleanValue?)

    /** 字体弹出列表：哪一行打开的、贴着哪个按钮、列表滚到哪 */
    private var fontChoice: FontChoice? = null
    private var fontAnchor = LayoutBounds()
    private var fontPanel = LayoutBounds()
    private var fontScroll = 0f
    private var fontMaxScroll = 0f
    /** 这一帧画到了打开列表的那一行 */
    private var fontAnchorSeen = false
    private val fontHits = mutableListOf<LayoutBounds>()

    private fun fontName(choice: FontChoice): String {
        val id = choice.font.value
        return when {
            choice.smooth?.get() == false -> I18n.text("Pixel font")
            id.isEmpty() -> I18n.text(if (choice.font === cockpitFont) "Default bold" else "Smooth font")
            !LyricFonts.exists(id) -> LyricFonts.label(id) + I18n.text(" (missing)")
            else -> LyricFonts.label(id)
        }
    }

    /** 字体：左边小文件夹按钮打开我们的字体文件夹，右边按钮点开字体列表 */
    private fun drawFontPicker(choice: FontChoice, clip: LayoutBounds, cx: Float, cy: Float) {
        val box = LayoutBounds(cx + 22f, cy - 12f, 144f, 24f)
        val open = fontChoice === choice && fontOpen
        ui.card(box, if (open || ui.hover(box)) Theme.CARD_HOVER else Theme.CARD, 7f)
        val shown = ui.trim(fontName(choice), 116f, 11f, Theme.FONT_MEDIUM)
        ui.text(shown, box.x + 9f, cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        PlayerIcons.draw(ui.ctx, if (open) PlayerIcons.chevronUp else PlayerIcons.chevronDown, box.right - 12f, cy, 12f, Theme.TEXT_SECONDARY)
        if (fontChoice === choice && box.y + box.height / 2f in clip.y..clip.bottom) {
            fontAnchor = box
            fontAnchorSeen = true
        }
        val hit = box.intersect(clip)
        fontHits += hit
        ui.hits += hit to {
            if (fontChoice === choice && fontOpen) {
                fontOpen = false
            } else {
                commitEdit()
                closePicker()
                bindingKey = null
                if (fontChoice !== choice) {
                    // 换了一行（或者是新打开）：从这个按钮重新展开
                    fontMotion.snap(false)
                    fontLastNanos = 0L
                }
                fontChoice = choice
                fontOpen = true
                fontAnchor = box
                fontScroll = 0f
                scrollToSelected = true
            }
        }
        // 文件夹图标：一个小标签 + 一个圆角方块
        val folder = LayoutBounds(cx, cy - 11f, 18f, 22f)
        val tint = if (ui.hover(folder)) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
        ui.card(LayoutBounds(folder.x + 2f, cy - 5f, 6f, 3f), tint, 1f)
        ui.card(LayoutBounds(folder.x + 2f, cy - 3f, 14f, 9f), tint, 2f)
        ui.hits += folder.intersect(clip) to {
            // 打不开文件夹（安卓等）：在底部提示栏告诉玩家往哪放
            if (!LyricFonts.openFolder())
                Library.showStatus(I18n.text("Fonts folder: ") + LyricFonts.folder.absolutePath + I18n.text(" (put .ttf / .otf files here)"))
        }
    }

    /** 打开列表时滚到选中的那一项（只滚一次） */
    private var scrollToSelected = false

    /** 一项：分组标题（header）或者一个字体 */
    private class FontItem(val label: String, val pixel: Boolean, val id: String, val header: Boolean)

    /** 列表正在展开 / 展开着；false 时如果 [fontChoice] 还在，就是正在收起 */
    private var fontOpen = false
    /** 弹出动画：和播放栏的菜单同一套（从按钮展开 / 收回，收起时一起淡出） */
    private val fontMotion = PopupMotion()
    private var fontLastNanos = 0L

    /** 马上收起（切标签、恢复默认时），不播动画 */
    private fun closeFontListNow() {
        fontChoice = null
        fontOpen = false
        fontMotion.snap(false)
        fontLastNanos = 0L
    }

    /**
     * 字体列表：从下拉按钮里展开的弹出菜单，外观和播放栏的菜单一样（经典风格浅色玻璃，连续圆角风格跟随那边的设计，见 PopupLook）。
     * 顶上是标题和刷新按钮；像素字体 / 默认，然后按“字体文件夹 / 其它模组 / 系统字体”分组；滚轮滚动，点一项选中
     */
    private fun drawFontList(view: LayoutBounds) {
        val choice = fontChoice ?: return
        // 打开它的那一行滚出视野 / 切走了：直接收起
        if (!fontAnchorSeen) {
            closeFontListNow()
            return
        }
        val now = System.nanoTime()
        // 好一阵子没画（离开了设置页）：回来时不带着旧状态冒出来
        if (fontLastNanos != 0L && now - fontLastNanos > 1_000_000_000L) {
            closeFontListNow()
            return
        }
        val dt = if (fontLastNanos == 0L) 0.016f else ((now - fontLastNanos) / 1e9f).coerceIn(0f, 0.05f)
        fontLastNanos = now
        fontMotion.step(fontOpen, dt)
        if (!fontOpen && !fontMotion.visible) {
            closeFontListNow()
            return
        }

        val items = ArrayList<FontItem>()
        items += FontItem(I18n.text("Pixel font"), true, "", false)
        items += FontItem(I18n.text(if (choice.font === cockpitFont) "Default bold" else "Smooth font"), false, "", false)
        val entries = LyricFonts.entries()
        for ((kind, title) in listOf(
            LyricFonts.Kind.OWN to "Fonts folder",
            LyricFonts.Kind.MOD to "Other mods",
            LyricFonts.Kind.SYSTEM to "System fonts",
        )) {
            val group = entries.filter { it.kind == kind }
            if (group.isEmpty()) continue
            items += FontItem(I18n.text(title), false, "", true)
            for (e in group) items += FontItem(if (kind == LyricFonts.Kind.MOD) "${e.label} · ${e.source}" else e.label, false, e.id, false)
        }

        val itemH = 28f
        val headerH = 34f
        val bottomLimit = view.bottom - ui.bottomInset
        val w = 260f
        val listH = items.sumOf { (if (it.header) 24.0 else itemH.toDouble()) }.toFloat()
        val h = (headerH + listH + 8f).coerceAtMost(360f)
        var top = fontAnchor.bottom + 6f
        if (top + h > bottomLimit) top = fontAnchor.y - h - 6f
        top = top.coerceAtMost(bottomLimit - h).coerceAtLeast(view.y)
        val right = fontAnchor.right.coerceAtMost(view.right - 8f).coerceAtLeast(view.x + w)
        val full = LayoutBounds(right - w, top, w, h)
        val p = fontMotion.progress.coerceIn(0f, 1f)
        val menu = fontMotion.bounds(fontAnchor, full)
        fontPanel = menu
        fontMaxScroll = max(0f, listH + 8f - (h - headerH))

        // 已经排队的文字刚画完，把现在的画面截成玻璃背景：菜单透出的是模糊的设置页，而不是盖着一堆字
        val ctx = ui.ctx
        ctx.recaptureBackdrop()
        ctx.pushOpacity(p)
        PopupLook.panel(ctx, menu)
        ctx.popOpacity()
        // 鼠标在列表上时，下面的设置行不显示悬停；列表里的照常
        ui.registerPopup(menu)
        val wasPopup = ui.inPopup
        ui.inPopup = true

        // 内容跟着菜单展开淡入、往上浮一点；收起时一起淡出
        val content = fontMotion.contentOpacity
        val lift = 10f * (1f - p)
        val interactive = fontOpen && p > 0.6f
        val hits = ArrayList<Hit>()
        ui.beginClip(menu)
        ctx.pushOpacity(content)
        try {
            // 标题 + 刷新按钮
            ui.text(I18n.text("Fonts"), full.x + 16f, full.y + 11f + lift, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            val refresh = LayoutBounds(full.right - 38f, full.y + 6f + lift, 26f, 22f)
            val refreshHover = interactive && ui.hover(refresh)
            if (refreshHover) ui.card(refresh, PopupLook.hover, 7f)
            PlayerIcons.draw(ctx, PlayerIcons.refresh, refresh.x + refresh.width / 2f, refresh.y + refresh.height / 2f, 15f,
                if (refreshHover) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
            if (interactive) hits += refresh to {
                // 下一帧重新扫目录可能要一会儿，别被当成“离开了设置页”而收起
                fontLastNanos = 0L
                LyricFonts.refresh()
                Library.showStatus(I18n.text("Font list refreshed"))
            }
            ctx.drawShape(LayoutBounds(full.x + 12f, full.y + headerH - 1f + lift, full.width - 24f, 1f), ShapeStyle(PopupLook.divider, 0.5f))
        } finally {
            ctx.popOpacity()
            ui.endClip()
        }

        val list = LayoutBounds(menu.x, max(menu.y, full.y + headerH), menu.width, max(0f, menu.bottom - (full.y + headerH)))
        if (scrollToSelected) {
            scrollToSelected = false
            var yy = 4f
            for (it in items) {
                if (!it.header && isSelected(choice, it)) {
                    fontScroll = (yy - (h - headerH) / 2f + itemH).coerceIn(0f, fontMaxScroll)
                    break
                }
                yy += if (it.header) 24f else itemH
            }
        }
        fontScroll = fontScroll.coerceIn(0f, fontMaxScroll)
        ui.beginClip(list)
        ctx.pushOpacity(content)
        try {
            var y = full.y + headerH + 4f - fontScroll + lift
            for (item in items) {
                val rowH = if (item.header) 24f else itemH
                if (y + rowH < list.y || y > list.bottom) {
                    y += rowH
                    continue
                }
                if (item.header) {
                    ctx.drawShape(LayoutBounds(full.x + 12f, y + 4f, full.width - 24f, 1f), ShapeStyle(PopupLook.divider, 0.5f))
                    ui.text(item.label, full.x + 16f, y + 9f, 9.5f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
                } else {
                    val row = LayoutBounds(full.x + 6f, y + 1f, full.width - 12f, rowH - 2f)
                    val on = isSelected(choice, item)
                    val hover = interactive && ui.hover(row) && list.contains(ui.mx, ui.my)
                    if (on) ui.card(row, PopupLook.selected, PopupLook.rowRadius(row.height))
                    else if (hover) ui.card(row, PopupLook.hover, PopupLook.rowRadius(row.height))
                    val label = ui.trim(item.label, row.width - 40f, 12f, if (on) Theme.FONT_SEMIBOLD else Theme.FONT_MEDIUM)
                    ui.text(label, row.x + 12f, row.y + row.height / 2f - 12f * 0.39f - 0.5f, 12f,
                        Theme.TEXT_PRIMARY, if (on) Theme.FONT_SEMIBOLD else Theme.FONT_MEDIUM)
                    if (on) PlayerIcons.draw(ctx, PlayerIcons.check, row.right - 16f, row.y + row.height / 2f, 14f, RED)
                    if (interactive) hits += row.intersect(list) to {
                        if (item.pixel) {
                            choice.smooth?.set(false)
                            choice.font.value = ""
                        } else {
                            choice.smooth?.set(true)
                            choice.font.value = item.id
                        }
                        fontOpen = false
                    }
                }
                y += rowH
            }
            // 滚动条
            if (fontMaxScroll > 0f) {
                val total = listH + 8f
                val th = max(24f, list.height * list.height / total)
                val ty = list.y + (list.height - th) * (fontScroll / fontMaxScroll)
                ctx.drawShape(LayoutBounds(menu.right - 6f, ty, 3f, th), ShapeStyle(Color(1f, 1f, 1f, 0.28f), 1.5f))
            }
        } finally {
            ctx.popOpacity()
            ui.endClip()
        }
        ui.inPopup = wasPopup
        // 菜单里的空白处也算菜单：点了不穿透到下面的设置（菜单盖在上面，点击排在最前面）；展开动画中途也一样
        if (fontOpen && p > 0.05f) hits += menu to {}
        for (hh in hits) fontHits += hh.first
        ui.hits.addAll(0, hits)
    }

    /** 驾驶舱字体那一行的值（列表里“默认”显示成“默认粗体”） */
    private val cockpitFont: TextValue? by lazy {
        try {
            fieldsOf(DangoMusic.instance.moduleManager.get(LyricsModule::class.java))["sideFont"]?.second as? TextValue
        } catch (_: Exception) {
            null
        }
    }


    private fun isSelected(choice: FontChoice, item: FontItem): Boolean = when {
        item.pixel -> choice.smooth?.get() == false
        choice.smooth?.get() == false -> false
        else -> choice.font.value == item.id
    }

    // ---------------------------------------------------------------- 恢复默认

    /** 第一次点之后 3 秒内再点一次才真的恢复（防止手滑） */
    private var resetArmedUntil = 0L

    /** 页头右边（标签左侧）：恢复默认设置 */
    private fun drawResetButton(page: LayoutBounds) {
        val tabsX = page.right - 96f * 3 - 8f
        val armed = System.currentTimeMillis() < resetArmedUntil
        val label = I18n.text(if (armed) "Click again to confirm" else "Reset to defaults")
        val w = ui.width(label, 11f, Theme.FONT_SEMIBOLD) + 22f
        val b = LayoutBounds(tabsX - w - 10f, page.y + 6f, w, 24f)
        ui.card(b, if (armed) RED else if (ui.hover(b)) Theme.CARD_HOVER else Theme.CARD, 8f)
        ui.text(label, b.x + 11f, b.y + 12f - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.hits += b to {
            if (System.currentTimeMillis() < resetArmedUntil) {
                resetArmedUntil = 0L
                resetAll()
                Library.showStatus(I18n.text("All settings are back to their defaults"))
            } else {
                resetArmedUntil = System.currentTimeMillis() + 3000L
            }
        }
    }

    /**
     * 所有标签里的设置恢复默认：开关、滑条、选项、颜色、字体、快捷键，以及灵动岛 / 封面进度这些组标题上的模块开关。
     * 界面语言不动（恢复成别的语言反而找不到按钮）
     */
    private fun resetAll() {
        commitEdit()
        closePicker()
        closeFontListNow()
        bindingKey = null
        for (tab in tabs) for (g in tab) {
            if (g.toggle && g.owner.enabled != g.owner.enabledByDefault) g.owner.enabled = g.owner.enabledByDefault
            for (r in g.rows) {
                when (val v = r.value) {
                    is BooleanValue -> v.reset()
                    is IntValue -> when (r.ctl) {
                        Ctl.LANGUAGE -> Unit
                        Ctl.KEY -> module.resetKeyBinding(v)
                        else -> v.reset()
                    }
                    is FloatValue -> v.reset()
                    is ColorValue -> v.reset()
                    is FontChoice -> {
                        v.font.reset()
                        v.smooth?.reset()
                    }
                }
            }
        }
    }

    private fun drawSlider(b: LayoutBounds, r: Row, clip: LayoutBounds, cx: Float, cy: Float) {
        val ctx = ui.ctx
        val track = LayoutBounds(cx + 2f, cy - 2f, 108f, 4f)
        val p = when (val v = r.value) {
            is IntValue -> v.normalize()
            is FloatValue -> v.normalize()
            else -> 0f
        }.coerceIn(0f, 1f)
        val active = dragValue === r.value
        if (active) dragTrack = track
        ctx.drawShape(track, ShapeStyle(Theme.TRACK, 2f))
        ctx.drawShape(LayoutBounds(track.x, track.y, track.width * p, track.height), ShapeStyle(RED, 2f))
        val k = if (active || ui.hover(track.expand(6f, 10f))) 14f else 11f
        ctx.drawShape(LayoutBounds(track.x + track.width * p - k / 2f, cy - k / 2f, k, k), ShapeStyle(Color(1f, 1f, 1f, 1f), k / 2f))
        // 数值：点一下可以直接输入
        val vb = LayoutBounds(b.right - 46f, cy - 10f, 46f, 20f)
        if (editRow === r) {
            editRect = vb
            ui.card(vb, Theme.CARD_HOVER, 6f)
            ctx.drawShape(vb, ShapeStyle(Color(0f, 0f, 0f, 0f), 6f, 1f, RED))
            val caret = if (caretVisible()) "|" else ""
            val et = editText + caret
            ui.text(et, vb.right - 6f - ui.width(et, 11f, Theme.FONT_SEMIBOLD), cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        } else {
            val s = r.fmt(r.value)
            val sw = ui.width(s, 11f, Theme.FONT_SEMIBOLD)
            val hot = ui.hover(vb)
            if (hot) ui.card(LayoutBounds(b.right - sw - 6f, cy - 10f, sw + 6f, 20f), Theme.CARD_HOVER, 6f)
            ui.text(s, b.right - sw - (if (hot) 3f else 0f), cy - 11f * 0.39f - 0.5f, 11f,
                if (active) RED else if (hot) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        ui.hits += vb.intersect(clip) to { startEdit(r) }
        ui.hits += track.expand(8f, 12f).intersect(clip) to {
            dragValue = r.value
            dragTrack = track
            applyDrag()
        }
    }

    private fun drawKeyButton(v: IntValue, clip: LayoutBounds, cx: Float, cy: Float) {
        val kb = LayoutBounds(cx, cy - 12f, 72f, 24f)
        val binding = bindingKey === v
        ui.card(kb, if (binding) RED.copy(a = 0.22f) else if (ui.hover(kb)) Theme.CARD_HOVER else Theme.CARD, 7f)
        if (binding) ui.ctx.drawShape(kb, ShapeStyle(Color(0f, 0f, 0f, 0f), 7f, 1f, RED))
        val name = when {
            binding -> I18n.text("Press a key")
            v.value == 0 -> I18n.text("None")
            else -> {
                val label = module.keyDisplayName(v) ?: Keys.name(v.value) ?: "#${v.value}"
                val key = label.substringAfterLast(" + ")
                val arrow = when (key.uppercase(java.util.Locale.ROOT)) {
                    "LEFT" -> "←"
                    "RIGHT" -> "→"
                    "UP" -> "↑"
                    "DOWN" -> "↓"
                    else -> key
                }
                label.substringBeforeLast(" + ", "").let { if (it.isEmpty()) arrow else "$it+$arrow" }
            }
        }
        val size = (11f * (kb.width - 10f) / ui.width(name, 11f, Theme.FONT_SEMIBOLD).coerceAtLeast(1f)).coerceIn(8f, 11f)
        val s = ui.trim(name, kb.width - 10f, size, Theme.FONT_SEMIBOLD)
        ui.text(s, kb.x + (kb.width - ui.width(s, size, Theme.FONT_SEMIBOLD)) / 2f, cy - size * 0.39f - 0.5f, size,
            if (binding) RED else if (v.value == 0) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val hb = kb.intersect(clip)
        keyButtons += hb
        ui.hits += hb to { bindingKey = if (bindingKey === v) null else v }

        val reset = LayoutBounds(kb.right + 6f, kb.y, 52f, kb.height)
        ui.card(reset, if (ui.hover(reset)) Theme.CARD_HOVER else Theme.CARD, 7f)
        val label = I18n.text("Reset")
        ui.text(label, reset.x + (reset.width - ui.width(label, 11f, Theme.FONT_MEDIUM)) / 2f,
            cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        val resetHit: Hit = reset.intersect(clip) to {
            bindingKey = null
            module.resetKeyBinding(v)
        }
        keyResetButtons += resetHit
        ui.hits += resetHit
    }

    /** 重置优先于鼠标录制，避免点击重置时反而绑定成鼠标左键。 */
    fun resetKeyAt(x: Int, y: Int): Boolean {
        val hit = keyResetButtons.firstOrNull { it.first.contains(x, y) } ?: return false
        commitEdit()
        screen.blurTextFields()
        hit.second.invoke()
        return true
    }

    private fun applyDrag() {
        val v = dragValue ?: return
        if (dragTrack.width <= 0f) return
        val p = ((ui.mx - dragTrack.x) / dragTrack.width).coerceIn(0f, 1f)
        when (v) {
            is IntValue -> v.setValue((v.minRange + p * (v.maxRange - v.minRange)).roundToInt())
            is FloatValue -> v.setValue(v.minRange + p * (v.maxRange - v.minRange))
        }
    }

    // ---------------------------------------------------------------- 按键

    /** 正在输入数值或录快捷键时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        // 取色器里输入十六进制：Enter 确定，Esc 取消
        if (hexEditing) {
            when {
                keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> applyHex()
                keyCode == Keys.ESCAPE -> hexEditing = false
                keyCode == Keys.BACK -> if (hexText.isNotEmpty()) hexText = hexText.dropLast(1)
                DangoClient.platform.ctrlDown && keyCode == Keys.A -> hexText = ""
                (typedChar.isDigit() || typedChar.lowercaseChar() in 'a'..'f') && hexText.length < 6 -> hexText += typedChar.uppercaseChar()
            }
            return true
        }
        if (fontOpen && keyCode == Keys.ESCAPE) {
            fontOpen = false
            return true
        }
        if (picker != null && keyCode == Keys.ESCAPE) {
            closePicker()
            return true
        }
        // 正在输入数值：Enter 确定，Esc 取消
        if (editRow != null) {
            when {
                keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> commitEdit()
                keyCode == Keys.ESCAPE -> editRow = null
                keyCode == Keys.BACK -> if (editText.isNotEmpty()) editText = editText.dropLast(1)
                DangoClient.platform.ctrlDown && keyCode == Keys.A -> editText = ""
                (typedChar.isDigit() || typedChar == '.' || typedChar == ',' || typedChar == '-') && editText.length < 10 ->
                    editText += typedChar
            }
            return true
        }
        // 正在录按键：Esc 取消；Backspace / Delete 清空。
        val v = bindingKey ?: return false
        when {
            keyCode == Keys.ESCAPE -> {}
            (keyCode == Keys.BACK || keyCode == Keys.DELETE) && v.minRange <= 0 -> module.rebindKey(v, Keys.NONE)
            DangoClient.platform.isPlayerKeyModifier(keyCode) -> {
                pendingModifierKey = keyCode
                return true
            }
            keyCode == Keys.NONE -> return true
            else -> module.rebindKey(v, keyCode, DangoClient.platform.activePlayerKeyModifier())
        }
        bindingKey = null
        return true
    }

    fun bindMouse(button: Int): Boolean {
        val value = bindingKey ?: return false
        // 左键 / 右键不能绑（绑上以后界面点不了），照常当点击处理
        if (allowedPlayerKey(Keys.mouse(button)) == Keys.NONE) return false
        module.rebindKey(value, Keys.mouse(button), DangoClient.platform.activePlayerKeyModifier())
        bindingKey = null
        return true
    }
}
