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
 * 侧边滚动歌词（歌词样式“侧边列表”），座舱 HUD 的感觉：没有背景板，字本身是透明的玻璃。
 * 玻璃透出（折射后的）游戏画面，里面透出封面色的光，外面一圈光晕，踩到节拍时亮一下。
 * 整列可以透视倾斜（像座舱侧边的屏，朝屏幕中间转）、前后倾斜，字可以斜体。
 * 歌词一行行向上滚，当前行放大、逐字点亮。
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
        /** 0.1–1：玻璃有多浓，越低越通透 */
        val opacity: Float,
        /** 玻璃里面发光 */
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
        /** 0..1，踩点时变大 */
        val beat: Float,
        /** 现在该不该显示（暂停也显示；聊天栏打开时淡出） */
        val visible: Boolean,
    )

    private var lastNanos = 0L

    /** 完全淡出了（不用驾驶舱样式时就不必每帧准备歌词数据） */
    val idle: Boolean get() = appear < 0.01f
    private var appear = 0f
    private var last: Frame? = null
    private var lastKey: String? = null
    private var scroll = Float.NaN
    private var scrollV = 0f
    private var glowR = 0.95f
    private var glowG = 0.65f
    private var glowB = 0.76f

    /** 截断结果缓存（文字、字号、宽度都不变时不用每帧重新量） */
    private val trimCache = HashMap<String, String>()

    /** 这一帧画字用的东西（draw 里设好，各个画行的函数共用） */
    private lateinit var tc: TextCanvas
    private var italicTan = 0f
    private var alignRight = false

    fun draw(e: HudRenderEvent?, frame: Frame?, s: Settings) {
        val platform = DangoClient.platform
        if (platform.windowWidth <= 0 || platform.windowHeight <= 0) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        // 出现 / 消失 0.2 秒渐变；消失时用最后一帧淡出
        if (frame != null) last = frame
        val f = last ?: return
        val want = if (frame != null && frame.visible) 1f else 0f
        appear += (want - appear) * (1f - exp(-dt / 0.18f))
        if (appear < 0.01f) {
            if (frame == null) last = null
            return
        }

        // 行：第 0 行是歌名，后面是歌词
        val rows = f.lines.size
        val target = (f.index + 1).coerceIn(0, rows).toFloat()
        if (scroll.isNaN() || f.key != lastKey || abs(target - scroll) > 4f) {
            // 换歌、拖进度：直接到位，不从头滚过来
            scroll = target
            scrollV = 0f
            if (f.key != lastKey) trimCache.clear()
            lastKey = f.key
        } else {
            // 略带回弹的弹簧，换行时整列平滑上移
            scrollV += ((target - scroll) * 150f - scrollV * 22f) * dt
            scroll += scrollV * dt
        }

        // 发光的颜色跟着封面主色慢慢过渡；封面很暗时提亮，玻璃里的光才看得出来
        var tr = ((f.accent shr 16) and 0xFF) / 255f
        var tg = ((f.accent shr 8) and 0xFF) / 255f
        var tb = (f.accent and 0xFF) / 255f
        val peak = maxOf(tr, tg, tb)
        if (peak < 0.7f) {
            val lift = 0.7f / peak.coerceAtLeast(0.05f)
            tr = (tr * lift).coerceAtMost(1f)
            tg = (tg * lift).coerceAtMost(1f)
            tb = (tb * lift).coerceAtMost(1f)
        }
        val kc = 1f - exp(-dt / 0.4f)
        glowR += (tr - glowR) * kc
        glowG += (tg - glowG) * kc
        glowB += (tb - glowB) * kc

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
            glowR, glowG, glowB,
            opacity = s.opacity.coerceIn(0.05f, 1f),
            refraction = 8f * s.refraction.coerceIn(0f, 1f) * k,
            emission = if (s.glow) (0.25f + 1.0f * strength) * pulse else 0f,
            halo = if (s.halo) (0.2f + 1.0f * strength) * pulse else 0f,
            frost = 0.12f,
            solid = !s.glass,
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
                drawHeader(f, area, cy, ts, emph, fade)
                continue
            }
            val line = f.lines[r - 1]
            val tr = if (s.translation) line.translation?.takeIf { it.isNotBlank() } else null
            val size = otherSize + (currentSize - otherSize) * emph
            // 按最大字号截断：滚动时字号在变，截出来的字不跟着跳
            val text = trim(line.text, area.width, currentSize, CjkStyle.BOLD)
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
                drawSung(text, area, baseline, size, sung, fade)
            } else {
                // 刚唱完的那行往上滚时慢慢暗下去
                val lit = 0.28f + 0.6f * emph * emph
                drawRun(text, area, baseline, size, CjkStyle.BOLD, lit, (0.42f + 0.58f * emph) * fade)
            }
            if (tr != null) {
                val trText = trim(tr, area.width, translationSize, CjkStyle.PLAIN)
                drawRun(trText, area, top + size + gap + translationSize * 0.86f, translationSize, CjkStyle.PLAIN,
                    0.22f + 0.2f * emph, (0.35f + 0.4f * emph) * fade)
            }
        }
    }

    /** 第 0 行：歌名 + 歌手（第一句之前、或没有歌词时就停在这里） */
    private fun drawHeader(f: Frame, area: LayoutBounds, cy: Float, ts: Float, emph: Float, fade: Float) {
        val titleSize = 16f * ts
        val artistSize = 11.5f * ts
        val gap = 4f * ts
        val hasArtist = f.artist.isNotBlank()
        val top = cy - (if (hasArtist) titleSize + gap + artistSize else titleSize) / 2f
        val title = trim("♪ " + f.title, area.width, titleSize, CjkStyle.BOLD)
        drawRun(title, area, top + titleSize * 0.86f, titleSize, CjkStyle.BOLD, 0.45f + 0.4f * emph, (0.55f + 0.45f * emph) * fade)
        if (hasArtist) {
            drawRun(trim(f.artist, area.width, artistSize, CjkStyle.PLAIN), area, top + titleSize + gap + artistSize * 0.86f,
                artistSize, CjkStyle.PLAIN, 0.3f, (0.4f + 0.3f * emph) * fade)
        }
    }

    /** 当前行逐字点亮：唱过的字最亮，正在唱的那个字按进度变亮，没唱到的偏暗 */
    private fun drawSung(text: String, area: LayoutBounds, baseline: Float, size: Float, sung: Float, fade: Float) {
        val style = CjkStyle.BOLD
        val dim = 0.42f
        // sung 按非空白字符计数，换算成字符串里的位置
        val whole = sung.coerceAtLeast(0f).toInt()
        val frac = sung.coerceAtLeast(0f) - whole
        var counted = 0
        var cut = text.length
        for ((i, c) in text.withIndex()) {
            if (c.isWhitespace()) continue
            if (counted == whole) {
                cut = i
                break
            }
            counted++
        }
        // 不能切在代理对中间（emoji 等）：落在低位代理上就退一格，整个字算“正在唱”
        if (cut in 1 until text.length && Character.isLowSurrogate(text[cut])) cut--
        val end = when {
            cut >= text.length -> cut
            Character.isHighSurrogate(text[cut]) && cut + 1 < text.length -> cut + 2
            else -> cut + 1
        }
        val done = text.substring(0, cut)
        val cur = text.substring(cut, end)
        val rest = text.substring(end)
        val wDone = tc.smoothWidth(done, size, style)
        val wCur = tc.smoothWidth(cur, size, style)
        val x0 = lineX(area, wDone + wCur + tc.smoothWidth(rest, size, style))
        row(x0, baseline) {
            if (done.isNotEmpty()) ink(done, 0f, size, style, 1f, fade)
            if (cur.isNotEmpty()) ink(cur, wDone, size, style, dim + (1f - dim) * frac, fade)
            if (rest.isNotEmpty()) ink(rest, wDone + wCur, size, style, dim, 0.85f * fade)
        }
    }

    /** 一段同样亮度的字（左对齐 / 右侧时右对齐） */
    private fun drawRun(text: String, area: LayoutBounds, baseline: Float, size: Float, style: Int, lit: Float, alpha: Float) {
        if (text.isEmpty() || alpha <= 0.005f) return
        val x0 = lineX(area, tc.smoothWidth(text, size, style))
        row(x0, baseline) { ink(text, 0f, size, style, lit, alpha) }
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
     * 遮罩颜色约定：alpha = 玻璃覆盖，rgb = 发光多少。
     * 出现 / 消失的渐变乘在覆盖上：玻璃、光晕一起淡，做不了玻璃直接画字时也一样会淡出
     */
    private fun ink(text: String, x: Float, size: Float, style: Int, lit: Float, alpha: Float) {
        val l = lit.coerceIn(0f, 1f)
        tc.color(l, l, l, (alpha * appear).coerceIn(0f, 1f))
        tc.smoothText(text, x, 0f, size, style)
    }

    /** 引导线：外侧一条两头淡出的细线，当前行的位置有一个亮刻度（跟着滚动走） */
    private fun drawGuide(pass: GlassTextPass, f: Frame, area: LayoutBounds, center: Float, rowH: Float) {
        val gx = if (alignRight) area.right + 14f else area.x - 14f
        val lw = 1.4f
        val halfH = area.height / 2f
        val a = appear
        pass.bar(gx - lw / 2f, area.y, lw, halfH, 0.35f, 0f, 0.5f * a)
        pass.bar(gx - lw / 2f, center, lw, halfH, 0.35f, 0.5f * a, 0f)
        val cy = center + ((f.index + 1).toFloat() - scroll) * rowH
        if (abs(cy - center) < halfH) {
            val tw = 3f
            val th = rowH * 0.42f
            pass.bar(gx - tw / 2f, cy - th / 2f, tw, th, 1f, 0.95f * a, 0.95f * a)
        }
    }

    /** 按 [size] 截断到 [maxW] 以内（结果缓存，换歌时清空） */
    private fun trim(s: String, maxW: Float, size: Float, style: Int): String {
        val key = "$style|${size.toBits()}|${maxW.toBits()}|$s"
        trimCache[key]?.let { return it }
        val out = if (tc.smoothWidth(s, size, style) <= maxW) s else {
            var end = s.length
            while (end > 0 && tc.smoothWidth(s.substring(0, end) + "...", size, style) > maxW) end--
            // 不切在代理对中间
            if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
            s.substring(0, end) + "..."
        }
        if (trimCache.size > 512) trimCache.clear()
        trimCache[key] = out
        return out
    }
}
