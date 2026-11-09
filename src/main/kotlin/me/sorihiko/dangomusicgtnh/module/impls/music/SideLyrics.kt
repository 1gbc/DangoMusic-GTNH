package me.sorihiko.dangomusicgtnh.module.impls.music

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.events.HudRenderEvent
import me.sorihiko.dangomusicgtnh.gfx.CjkStyle
import me.sorihiko.dangomusicgtnh.gfx.GlassTextPass
import me.sorihiko.dangomusicgtnh.gfx.GlassTextStyle
import me.sorihiko.dangomusicgtnh.gfx.GlassTextView
import me.sorihiko.dangomusicgtnh.gfx.TextCanvas
import me.sorihiko.dangomusicgtnh.lyrics.LyricLine
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.tan

/**
 * 侧边滚动歌词（歌词样式“驾驶舱歌词”），座舱 HUD 的感觉：没有背景板，字自己发光（也可以是透明的玻璃字）。
 * 颜色可以跟着封面主色走、自己选、用封面渐变、彩虹或者流动的极光；光晕用每个字自己的颜色，踩到节拍时亮一下。
 * 整列可以透视倾斜（像座舱侧边的屏，朝屏幕中间转）、前后倾斜，字可以斜体。
 * 歌词一行行向上滚，当前行放大、逐字点亮；按住预览键滚轮可以翻看前后的歌词。
 *
 * 只负责画：歌词、当前行、进度和设置都由 [LyricsModule] 每帧传进来。玻璃效果见 [GlassTextPass]。
 */
internal class SideLyrics {
    class Settings(
        /** 0 = 左侧居中, 1 = 左下, 2 = 右侧居中 */
        val position: Int,
        /** 在锚点基础上再挪多少（逻辑像素）：x 正数往右，y 正数往上 */
        val offsetX: Float,
        val offsetY: Float,
        /** 一行最宽多少（逻辑像素），太长的截成 "..." */
        val width: Float,
        val lines: Int,
        val textSize: Float,
        /** 字是玻璃；关掉 = 实心的发光字 */
        val glass: Boolean,
        /** 0.1–1：字有多浓，越低越通透 */
        val opacity: Float,
        /** 字自己发光 */
        val glow: Boolean,
        val glowStrength: Float,
        /** 字外面的光晕 */
        val halo: Boolean,
        val translation: Boolean,
        /** 透视倾斜（度）：外侧靠近、往屏幕中间退远 */
        val tilt: Float,
        /** 前后倾斜（度）：正数上边退远 */
        val pitch: Float,
        /** 斜体（度） */
        val italic: Float,
        /** 0..1 折射强弱 */
        val refraction: Float,
        /** 列表旁边一条发光细线，当前行有个刻度 */
        val guide: Boolean,
        /** 配色：SIDE_COVER / SIDE_CUSTOM / SIDE_GRADIENT / SIDE_RAINBOW / SIDE_AURORA */
        val colors: Int,
        /** 自定义颜色（0xRRGGBB） */
        val color: Int,
        /** 0..1：字本身染上多少颜色 */
        val tint: Float,
        /** 字的整体亮度（1 = 正常） */
        val brightness: Float,
        /** 0.1..1：当前行以外的行有多明显 */
        val otherLines: Float,
        /** 颜色和后面的景色太接近时自动提亮 / 压暗 */
        val contrast: Boolean,
        /** 驾驶舱字体的版本标记：变了（换字体 / 字体文件被替换）就重新量宽度 */
        val fontKey: String?,
        /** 用游戏自带的像素字体（和 3D 歌词的像素字体一样） */
        val pixel: Boolean,
    )

    class Frame(
        val key: String,
        val title: String,
        val artist: String,
        val lines: List<LyricLine>,
        /** 当前行；-1 = 第一句之前（显示歌名那一行） */
        val index: Int,
        val positionMs: Long,
        /** 封面主色（取低 24 位 RGB，高位的透明度忽略） */
        val accent: Int,
        /** 封面的几种代表色（ARGB），最显眼的在前；没有封面时是空的 */
        val palette: List<Int>,
        /** 0..1，踩点时变大 */
        val beat: Float,
        /** 现在该不该显示（暂停也显示；聊天栏打开时淡出） */
        val visible: Boolean,
        /** 预览：翻到了第几行（0 = 歌名那一行，第 n 句歌词是 n），-1 = 不在预览，跟着正在唱的那一行 */
        val previewRow: Int,
    )

    private var lastNanos = 0L

    /** 完全淡出了（不用驾驶舱样式时就不必每帧准备歌词数据） */
    val idle: Boolean get() = appear < 0.01f
    private var appear = 0f
    private var last: Frame? = null
    private var lastKey: String? = null
    private var lastFontKey: String? = null
    private var scroll = Float.NaN
    private var scrollV = 0f
    private var glowR = 0.95f
    private var glowG = 0.65f
    private var glowB = 0.76f

    /** 截断结果缓存（文字、字号、宽度、字体都不变时不用每帧重新量） */
    private val trimCache = HashMap<String, String>()

    /** 这一帧画字用的东西（draw 里设好，各个画行的函数共用） */
    private lateinit var tc: TextCanvas
    private var italicTan = 0f
    private var alignRight = false
    private var colorMode = SIDE_COVER
    private var customR = 1f
    private var customG = 1f
    private var customB = 1f
    private var palette: List<Int> = emptyList()
    private var timeSec = 0f
    private var pixel = false
    /** 刚结束预览的时间：之后一小会儿滚回去也要平滑滚，不直接跳 */
    private var previewEndedNanos = Long.MIN_VALUE / 2
    private var wasPreview = false
    private val rgb = FloatArray(3)

    fun draw(e: HudRenderEvent?, frame: Frame?, s: Settings) {
        val platform = DangoClient.platform
        if (platform.windowWidth <= 0 || platform.windowHeight <= 0) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        timeSec = ((now / 1_000_000L) % 3_600_000L) / 1000f

        // 出现 / 消失 0.2 秒渐变；消失时用最后一帧淡出
        if (frame != null) last = frame
        val f = last ?: return
        val want = if (frame != null && frame.visible) 1f else 0f
        appear += (want - appear) * (1f - exp(-dt / 0.18f))
        if (appear < 0.01f) {
            if (frame == null) last = null
            return
        }
        pixel = s.pixel
        val fontKey = if (s.pixel) "pixel" else s.fontKey
        if (fontKey != lastFontKey) {
            lastFontKey = fontKey
            trimCache.clear()
        }

        // 行：第 0 行是歌名，后面是歌词；预览时中间那一行换成翻到的那一行（固定的行号，不跟着唱的进度走）
        val rows = f.lines.size
        val previewing = f.previewRow >= 0
        if (wasPreview && !previewing) previewEndedNanos = now
        wasPreview = previewing
        val target = (if (previewing) f.previewRow else f.index + 1).coerceIn(0, rows).toFloat()
        // 预览中、刚退出预览的 2 秒里都平滑滚动；其他时候跳得太远（换歌、拖进度）直接到位
        val smooth = previewing || now - previewEndedNanos < 2_000_000_000L
        if (scroll.isNaN() || f.key != lastKey || (!smooth && abs(target - scroll) > 4f)) {
            // 换歌、拖进度：直接到位，不从头滚过来（预览时一直平滑滚动）
            scroll = target
            scrollV = 0f
            if (f.key != lastKey) trimCache.clear()
            lastKey = f.key
        } else {
            // 略带回弹的弹簧，换行时整列平滑上移
            scrollV += ((target - scroll) * 150f - scrollV * 22f) * dt
            scroll += scrollV * dt
        }

        // 封面主色慢慢过渡；封面很暗时提亮，光才看得出来
        normalized((f.accent shr 16 and 0xFF) / 255f, (f.accent shr 8 and 0xFF) / 255f, (f.accent and 0xFF) / 255f, rgb)
        val kc = 1f - exp(-dt / 0.4f)
        glowR += (rgb[0] - glowR) * kc
        glowG += (rgb[1] - glowG) * kc
        glowB += (rgb[2] - glowB) * kc
        colorMode = s.colors
        normalized((s.color shr 16 and 0xFF) / 255f, (s.color shr 8 and 0xFF) / 255f, (s.color and 0xFF) / 255f, rgb)
        customR = rgb[0]; customG = rgb[1]; customB = rgb[2]
        palette = f.palette

        // ---------------------------------------------------------------- 布局（逻辑像素，按窗口缩放）
        val k = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val ts = s.textSize.coerceIn(0.6f, 2f)
        val rowH = (if (s.translation) 44f else 32f) * ts
        val visibleRows = s.lines.coerceIn(3, 11)
        alignRight = s.position == 2
        val w = s.width.coerceAtMost(lw * 0.45f)
        val h = (visibleRows * rowH).coerceAtMost(lh - 16f)
        val margin = 36f
        val anchorX = if (alignRight) lw - w - margin else margin
        val anchorY = when (s.position) {
            1 -> lh - h - 70f          // 左下：留出快捷栏 / 聊天栏最下面一行
            else -> (lh - h) / 2f
        }
        // 偏移之后仍然整块留在屏幕里（引导线在外侧再多占 16）
        val x = (anchorX + s.offsetX).coerceIn(margin, (lw - w - margin).coerceAtLeast(margin))
        val y = (anchorY - s.offsetY).coerceIn(8f, (lh - h - 8f).coerceAtLeast(8f))
        val area = LayoutBounds(x, y, w, h)
        if (e?.overlapsPlayerList(area, k) == true) return
        val center = y + h / 2f

        // 透视的支点在外侧边上：外侧不动，往屏幕中间的那一侧退远（不会被推出屏幕）
        val tilt = s.tilt.coerceIn(-45f, 45f)
        val view = GlassTextView(
            pivotX = (if (alignRight) area.right else area.x) * k,
            pivotY = center * k,
            yaw = if (alignRight) -tilt else tilt,
            pitch = s.pitch.coerceIn(-35f, 35f),
        )
        // begin 成功之后一定要走到 end，所以能出错的准备工作都放在 begin 前面
        tc = DangoClient.gfx.text
        italicTan = tan(Math.toRadians(s.italic.coerceIn(-10f, 25f).toDouble())).toFloat()
        val beat = f.beat.coerceIn(0f, 1f)
        val strength = s.glowStrength.coerceIn(0f, 1f)
        val pulse = 0.8f + 0.25f * beat
        val style = GlassTextStyle(
            opacity = s.opacity.coerceIn(0.05f, 1f),
            refraction = 8f * s.refraction.coerceIn(0f, 1f) * k,
            emission = if (s.glow) (0.25f + 1.0f * strength) * pulse else 0f,
            halo = if (s.halo) (0.2f + 1.0f * strength) * pulse else 0f,
            frost = 0.12f,
            solid = !s.glass,
            tintText = s.tint,
            brightness = s.brightness,
            contrast = s.contrast,
        )
        val pass = DangoClient.gfx.glassText
        if (!pass.begin(view)) return
        try {
            tc.push()
            try {
                tc.scale(k, k)
                if (s.guide) drawGuide(pass, f, area, center, rowH)
                drawRows(f, s, area, center, rowH, visibleRows, ts)
            } finally {
                tc.pop()
            }
        } finally {
            pass.end(style)
        }
    }

    private fun drawRows(f: Frame, s: Settings, area: LayoutBounds, center: Float, rowH: Float, visibleRows: Int, ts: Float) {
        val currentSize = 21f * ts
        val otherSize = 15f * ts
        val translationSize = 11f * ts
        val rows = f.lines.size
        val half = visibleRows / 2f
        val first = floor(scroll - half - 1f).toInt().coerceAtLeast(0)
        val lastRow = ceil(scroll + half + 1f).toInt().coerceAtMost(rows)
        val other = s.otherLines.coerceIn(0.1f, 1f)
        val otherLit = 0.25f + 0.5f * other
        // 预览时中间那一行最亮（像当前行一样），其他行按“其它行”的深浅变淡
        val previewing = f.previewRow >= 0 && f.previewRow != f.index + 1
        val centerLit = if (previewing) 1f else 0.88f
        for (r in first..lastRow) {
            val d = r - scroll
            val dist = abs(d)
            // 越靠边越淡，最外一行几乎透明：没有硬边
            val edge = ((half + 0.35f - dist) / 1.6f).coerceIn(0f, 1f)
            val fade = edge * edge * (3f - 2f * edge)
            if (fade <= 0.01f) continue
            // 0..1：离中间越近越接近“当前行”的样子（滚动时连续变化）
            val emph = (1f - dist).coerceIn(0f, 1f)
            val cy = center + d * rowH
            if (r == 0) {
                drawHeader(f, area, cy, ts, emph, fade, other)
                continue
            }
            val line = f.lines[r - 1]
            val tr = if (s.translation) line.translation?.takeIf { it.isNotBlank() } else null
            val size = otherSize + (currentSize - otherSize) * emph
            // 按最大字号截断：滚动时字号在变，截出来的字不跟着跳
            val text = trim(line.text, area.width, currentSize, CjkStyle.SIDE)
            val gap = 4f * ts
            val blockH = if (tr != null) size + gap + translationSize else size
            val top = cy - blockH / 2f
            val baseline = top + size * 0.86f
            if (r - 1 == f.index) {
                val next = f.lines.getOrNull(r)?.timeMs ?: Long.MAX_VALUE
                var sung = line.sungCharacters(f.positionMs, next)
                if (text != line.text) {
                    // 太长被截成 "..." 时，按截断后能看到的字数换算进度，省略号保持暗色
                    val full = line.text.count { !it.isWhitespace() }.coerceAtLeast(1)
                    val shownCount = text.removeSuffix("...").count { !it.isWhitespace() }
                    sung = (sung * shownCount / full).coerceAtMost(shownCount.toFloat())
                }
                // 预览别的行时，正在唱的这一行也按“其它行”变淡，好分清中间那行
                val keep = if (previewing) other + (1f - other) * emph else 1f
                drawLine(text, area, baseline, size, r, fade * keep, sung)
            } else {
                // 刚唱完的那行往上滚时慢慢暗下去
                val lit = otherLit + (centerLit - otherLit) * emph * emph
                drawLine(text, area, baseline, size, r, (other + (1f - other) * emph) * fade, null, lit)
            }
            if (tr != null) {
                val trText = trim(tr, area.width, translationSize, CjkStyle.SIDE)
                drawLine(trText, area, top + size + gap + translationSize * 0.86f, translationSize, r,
                    (0.35f + 0.4f * emph) * fade * (0.6f + 0.4f * other), null, 0.22f + 0.2f * emph)
            }
        }
    }

    /** 第 0 行：歌名 + 歌手（第一句之前、或没有歌词时就停在这里） */
    private fun drawHeader(f: Frame, area: LayoutBounds, cy: Float, ts: Float, emph: Float, fade: Float, other: Float) {
        val titleSize = 16f * ts
        val artistSize = 11.5f * ts
        val gap = 4f * ts
        val hasArtist = f.artist.isNotBlank()
        val top = cy - (if (hasArtist) titleSize + gap + artistSize else titleSize) / 2f
        val title = trim("♪ " + f.title, area.width, titleSize, CjkStyle.SIDE)
        drawLine(title, area, top + titleSize * 0.86f, titleSize, 0, (other + (1f - other) * emph) * fade, null, 0.45f + 0.4f * emph)
        if (hasArtist) {
            drawLine(trim(f.artist, area.width, artistSize, CjkStyle.SIDE), area, top + titleSize + gap + artistSize * 0.86f,
                artistSize, 0, (0.4f + 0.3f * emph) * fade, null, 0.3f)
        }
    }

    /**
     * 画一行字（左对齐 / 右侧时右对齐），逐字上色。
     * [sung] 不为 null = 当前行逐字点亮：唱过的字最亮，正在唱的那个字按进度变亮，没唱到的偏暗；
     * 否则整行都是 [lit] 的亮度
     */
    private fun drawLine(text: String, area: LayoutBounds, baseline: Float, size: Float, row: Int, alpha: Float, sung: Float?, lit: Float = 1f) {
        if (text.isEmpty() || alpha <= 0.005f) return
        val style = CjkStyle.SIDE
        val width = textWidth(text, size)
        val x0 = lineX(area, width)
        val dim = 0.42f
        val whole = sung?.coerceAtLeast(0f)?.toInt() ?: 0
        val frac = (sung?.coerceAtLeast(0f) ?: 0f) - whole
        row(x0, baseline) {
            var pen = 0f
            var counted = 0
            var i = 0
            while (i < text.length) {
                // 代理对（emoji 等）当一个字
                val n = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length) 2 else 1
                val ch = text.substring(i, i + n)
                val cw = textWidth(ch, size)
                if (!ch[0].isWhitespace()) {
                    var l = lit
                    var a = alpha
                    if (sung != null) {
                        when {
                            counted < whole -> l = 1f
                            counted == whole -> l = dim + (1f - dim) * frac
                            else -> { l = dim; a = alpha * 0.85f }
                        }
                    }
                    charColor((pen + cw / 2f) / width.coerceAtLeast(1f), row)
                    ink(ch, pen, size, style, l, a)
                    counted++
                }
                pen += cw
                i += n
            }
        }
    }

    private fun lineX(area: LayoutBounds, width: Float): Float = if (alignRight) area.right - width else area.x

    /** 在一行的基线上画：斜体按这一行的基线错切，所以每行自己倒，整列还是直的 */
    private inline fun row(x: Float, baseline: Float, body: () -> Unit) {
        tc.push()
        try {
            tc.translate(x, baseline)
            if (italicTan != 0f) tc.skewX(italicTan)
            body()
        } finally {
            tc.pop()
        }
    }

    /**
     * 遮罩颜色约定：alpha = 字的覆盖，rgb = 颜色（[rgb]，最亮通道是 1）× 亮度。
     * 出现 / 消失的渐变乘在覆盖上：字、光晕一起淡，做不了玻璃直接画字时也一样会淡出
     */
    private fun ink(text: String, x: Float, size: Float, style: Int, lit: Float, alpha: Float) {
        val l = lit.coerceIn(0f, 1f)
        val a = (alpha * appear).coerceIn(0f, 1f)
        if (pixel) {
            // 像素字体：游戏自带的字（8 像素高，基线在第 7 行），按字号缩放；颜色走 ARGB
            val k = size / PIXEL_EM
            val argb = ((a * 255f).toInt().coerceIn(4, 255) shl 24) or
                ((rgb[0] * l * 255f).toInt().coerceIn(0, 255) shl 16) or
                ((rgb[1] * l * 255f).toInt().coerceIn(0, 255) shl 8) or
                (rgb[2] * l * 255f).toInt().coerceIn(0, 255)
            tc.push()
            try {
                tc.translate(x, 0f)
                tc.scale(k, k)
                tc.pixelText(text, 0f, -7f, argb, false)
            } finally {
                tc.pop()
            }
            return
        }
        tc.color(rgb[0] * l, rgb[1] * l, rgb[2] * l, a)
        tc.smoothText(text, x, 0f, size, style)
    }

    /** 一段字的宽度（像素字体或驾驶舱的平滑字体） */
    private fun textWidth(s: String, size: Float): Float =
        if (pixel) tc.pixelWidth(s) * size / PIXEL_EM else tc.smoothWidth(s, size, CjkStyle.SIDE)

    /** 一个字的颜色写进 [rgb]（最亮通道调成 1）。[u] 是字在这一行里的位置（0 左，1 右） */
    private fun charColor(u: Float, row: Int) {
        when (colorMode) {
            SIDE_CUSTOM -> { rgb[0] = customR; rgb[1] = customG; rgb[2] = customB }
            SIDE_GRADIENT -> if (palette.size >= 2) paletteAt(u.coerceIn(0f, 1f) * (palette.size - 1), false) else accent()
            SIDE_RAINBOW -> hsv(frac(u * 0.55f + row * 0.06f + timeSec * 0.12f), 0.72f)
            SIDE_AURORA -> if (palette.size >= 2) {
                paletteAt(frac(u * 0.6f - timeSec * 0.08f + row * 0.05f) * palette.size, true)
            } else {
                // 只有一种封面色：在它和旁边的色相之间来回流动
                accent()
                val t = (kotlin.math.sin((u * 3f - timeSec * 0.9f + row * 0.4f).toDouble()).toFloat() + 1f) / 2f
                val r0 = rgb[0]; val g0 = rgb[1]; val b0 = rgb[2]
                rgb[0] = r0 + (b0 - r0) * t * 0.6f
                rgb[1] = g0 + (r0 - g0) * t * 0.6f
                rgb[2] = b0 + (g0 - b0) * t * 0.6f
                normalized(rgb[0], rgb[1], rgb[2], rgb)
            }
            else -> accent()
        }
    }

    private fun accent() {
        rgb[0] = glowR; rgb[1] = glowG; rgb[2] = glowB
    }

    /** 封面色板上第 [at] 个位置（小数在相邻两个颜色之间插值）；[wrap] = 最后一个接回第一个 */
    private fun paletteAt(at: Float, wrap: Boolean) {
        val n = palette.size
        val i = floor(at).toInt()
        val t = at - i
        val a = palette[((i % n) + n) % n]
        val b = palette[if (wrap) (((i + 1) % n) + n) % n else (i + 1).coerceAtMost(n - 1)]
        normalized(
            mix((a shr 16 and 0xFF) / 255f, (b shr 16 and 0xFF) / 255f, t),
            mix((a shr 8 and 0xFF) / 255f, (b shr 8 and 0xFF) / 255f, t),
            mix((a and 0xFF) / 255f, (b and 0xFF) / 255f, t),
            rgb,
        )
    }

    private fun hsv(h: Float, s: Float) {
        val hh = frac(h) * 6f
        val i = hh.toInt()
        val f = hh - i
        val p = 1f - s
        val q = 1f - s * f
        val t = 1f - s * (1f - f)
        when (i) {
            0 -> { rgb[0] = 1f; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = 1f; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = 1f; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = 1f }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = 1f }
            else -> { rgb[0] = 1f; rgb[1] = p; rgb[2] = q }
        }
    }

    /** 引导线：外侧一条两头淡出的细线，当前行的位置有一个亮刻度（跟着滚动走，预览时能看出正在唱哪儿） */
    private fun drawGuide(pass: GlassTextPass, f: Frame, area: LayoutBounds, center: Float, rowH: Float) {
        val gx = if (alignRight) area.right + 14f else area.x - 14f
        val lw = 1.4f
        val halfH = area.height / 2f
        val a = appear
        charColor(0.5f, f.index + 1)
        val line = 0.35f
        pass.bar(gx - lw / 2f, area.y, lw, halfH, rgb[0] * line, rgb[1] * line, rgb[2] * line, 0f, 0.5f * a)
        pass.bar(gx - lw / 2f, center, lw, halfH, rgb[0] * line, rgb[1] * line, rgb[2] * line, 0.5f * a, 0f)
        val cy = center + ((f.index + 1).toFloat() - scroll) * rowH
        if (abs(cy - center) < halfH) {
            val tw = 3f
            val th = rowH * 0.42f
            pass.bar(gx - tw / 2f, cy - th / 2f, tw, th, rgb[0], rgb[1], rgb[2], 0.95f * a, 0.95f * a)
        }
    }

    /** 按 [size] 截断到 [maxW] 以内（结果缓存，换歌、换字体时清空） */
    private fun trim(s: String, maxW: Float, size: Float, style: Int): String {
        val key = "$style|${size.toBits()}|${maxW.toBits()}|$s"
        trimCache[key]?.let { return it }
        val out = if (textWidth(s, size) <= maxW) s else {
            var end = s.length
            while (end > 0 && textWidth(s.substring(0, end) + "...", size) > maxW) end--
            // 不切在代理对中间
            if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
            s.substring(0, end) + "..."
        }
        if (trimCache.size > 512) trimCache.clear()
        trimCache[key] = out
        return out
    }

    private companion object {
        /** 像素字体按这个字号缩放（游戏的字 8 像素高，和平滑字体的字号看起来差不多大） */
        const val PIXEL_EM = 9f

        fun frac(x: Float): Float = x - floor(x)
        fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        /** 调成最亮的通道是 1（很暗 / 黑色当白色）：遮罩里要靠这个分出颜色和亮度 */
        fun normalized(r: Float, g: Float, b: Float, out: FloatArray) {
            val peak = maxOf(r, g, b)
            if (peak < 0.05f) {
                out[0] = 1f; out[1] = 1f; out[2] = 1f
                return
            }
            out[0] = (r / peak).coerceIn(0f, 1f)
            out[1] = (g / peak).coerceIn(0f, 1f)
            out[2] = (b / peak).coerceIn(0f, 1f)
        }
    }
}
