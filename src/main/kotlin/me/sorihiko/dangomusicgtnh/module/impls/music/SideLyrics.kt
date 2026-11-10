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
import kotlin.math.tanh

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
        /** 一行最宽多少（逻辑像素）。太长的行两头用 "..." 收起，正在唱的那行跟着进度往左滚 */
        val width: Float,
        val lines: Int,
        val textSize: Float,
        /** 行距倍数（1 = 默认）；再小也不会让相邻两行叠在一起 */
        val lineSpacing: Float,
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
        /** 音译（有的话画在原文下面、翻译上面，和 3D 歌词的顺序一样） */
        val romanization: Boolean,
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
        /** 惯性强度 0.1..1；0 = 关 */
        val inertia: Float,
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
    /** 整行宽度缓存（同上，换歌、换字体时清空） */
    private val widthCache = HashMap<String, Float>()

    /**
     * 太长的行往左滚了多少（按当前行字号量的逻辑像素），按行号记。
     * 正在唱的行跟着进度滚，唱完 / 不是当前行了就慢慢滚回开头；换歌时清空
     */
    private val shifts = HashMap<Int, Float>()
    private var frameDt = 0.016f

    /** 行距：开关翻译 / 音译、换到有没有音译的歌、拖行距时平滑过渡，不跳 */
    private var rowHSmooth = Float.NaN

    // ---- 惯性（见 updateSway）
    private var swayYaw = Float.NaN
    private var swayPitch = 0f
    private var swayCamY = 0.0
    /** 平滑后的转动速度（度 / 秒）和上下速度（格 / 秒） */
    private var turnYaw = 0f
    private var turnPitch = 0f
    private var rise = 0f
    /** 整列这一帧的偏移（逻辑像素）和弹簧速度 */
    private var swayX = 0f
    private var swayY = 0f
    private var swayVX = 0f
    private var swayVY = 0f

    /** 这首歌有没有音译 / 翻译（按歌词列表记，列表换了才重新看） */
    private var annLines: List<LyricLine>? = null
    private var annPhonetic = false
    private var annTranslation = false

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
        // 隔了一阵子才又画（列表隐藏过、游戏卡了）：惯性从头算，不把这段时间里转过的角度当成一下甩过去
        val gap = lastNanos == 0L || now - lastNanos > 250_000_000L
        lastNanos = now
        frameDt = dt
        timeSec = ((now / 1_000_000L) % 3_600_000L) / 1000f

        // 出现 / 消失 0.2 秒渐变；消失时用最后一帧淡出
        if (frame != null) last = frame
        val f = last ?: return
        val want = if (frame != null && frame.visible) 1f else 0f
        appear += (want - appear) * (1f - exp(-dt / 0.18f))
        if (appear < 0.01f) {
            if (frame == null) last = null
            // 看不见的这段时间不跟踪视角：再出现时惯性从头算，不会被这段时间转过的角度踢一下
            swayYaw = Float.NaN
            return
        }
        pixel = s.pixel
        val fontKey = if (s.pixel) "pixel" else s.fontKey
        if (fontKey != lastFontKey) {
            lastFontKey = fontKey
            trimCache.clear()
            widthCache.clear()
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
            if (f.key != lastKey) {
                trimCache.clear()
                widthCache.clear()
                shifts.clear()
                rowHSmooth = Float.NaN
            }
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
        // 行距按这首歌实际有的注释行算：开了音译 / 翻译但这首歌没有，就不留空行
        if (f.lines !== annLines) {
            // 歌词列表换了（换歌、切换自动拆句）：行号对应的句子变了，记的滚动位置作废
            if (annLines != null) shifts.clear()
            annLines = f.lines
            annPhonetic = f.lines.any { !it.romanization.isNullOrBlank() }
            annTranslation = f.lines.any { !it.translation.isNullOrBlank() }
        }
        val annotations = (if (s.romanization && annPhonetic) 1 else 0) + (if (s.translation && annTranslation) 1 else 0)
        val nominal = (32f + 12f * annotations) * ts * s.lineSpacing.coerceIn(0.7f, 1.6f)
        // 再紧也不让当前行（大字）和旁边一行的字叠在一起
        val minPitch = ((CURRENT_SIZE + OTHER_SIZE) / 2f + annotations * (ANN_GAP + ANN_SIZE) + 2f) * ts
        val rowTarget = maxOf(nominal, minPitch)
        rowHSmooth = if (rowHSmooth.isNaN()) rowTarget else rowHSmooth + (rowTarget - rowHSmooth) * (1f - exp(-dt / 0.2f))
        val rowH = rowHSmooth
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
        // 惯性偏移加在留在屏幕里的位置之后：只是暂时晃一下，最多二十几像素，边距（36）够用
        updateSway(dt, s.inertia, gap)
        val area = LayoutBounds(x + swayX, y + swayY, w, h)
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
        val currentSize = CURRENT_SIZE * ts
        val otherSize = OTHER_SIZE * ts
        val annSize = ANN_SIZE * ts
        val annGap = ANN_GAP * ts
        val rows = f.lines.size
        val half = visibleRows / 2f
        val first = floor(scroll - half - 1f).toInt().coerceAtLeast(0)
        val lastRow = ceil(scroll + half + 1f).toInt().coerceAtMost(rows)
        // 滚出视野的行不再记滚动位置：再滚回来时从行首开始（正在唱的那行会再平滑滚到唱到的地方）
        if (shifts.isNotEmpty()) shifts.keys.removeIf { it / 3 < first || it / 3 > lastRow }
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
            // 原文下面先音译、再翻译（和 3D 歌词一样）
            val ph = if (s.romanization) line.romanization?.takeIf { it.isNotBlank() } else null
            val tr = if (s.translation) line.translation?.takeIf { it.isNotBlank() } else null
            val size = otherSize + (currentSize - otherSize) * emph
            val annCount = (if (ph != null) 1 else 0) + (if (tr != null) 1 else 0)
            val blockH = size + annCount * (annGap + annSize)
            val top = cy - blockH / 2f
            val baseline = top + size * 0.86f
            val isCurrent = r - 1 == f.index
            val sung = if (isCurrent) line.sungCharacters(f.positionMs, f.lines.getOrNull(r)?.timeMs ?: Long.MAX_VALUE) else null
            // 刚唱完的那行往上滚时慢慢暗下去
            val lit = if (isCurrent) 1f else otherLit + (centerLit - otherLit) * emph * emph
            // 预览别的行时，正在唱的这一行也按“其它行”变淡，好分清中间那行
            val alpha = if (isCurrent) fade * (if (previewing) other + (1f - other) * emph else 1f)
            else (other + (1f - other) * emph) * fade
            // 太不太长按最大字号量：滚动时字号在变，能看到的字不跟着变
            val fullW = fullWidth(line.text, currentSize)
            if (fullW <= area.width) {
                shifts.remove(r * 3)
                drawLine(line.text, area, baseline, size, r, alpha, sung, lit)
            } else {
                val shift = updateShift(r * 3, sung?.let { sungX(line.text, it, currentSize) }, fullW, area.width)
                drawWindowed(line.text, area, baseline, size, size / currentSize, r, alpha, sung, lit, shift, fullW)
            }
            if (annCount > 0) {
                val annAlpha = (0.35f + 0.4f * emph) * fade * (0.6f + 0.4f * other)
                val annLit = 0.22f + 0.2f * emph
                // 音译、翻译太长时也跟着这一句唱到的比例滚
                val progress = sung?.let { it / line.text.count { c -> !c.isWhitespace() }.coerceAtLeast(1) }
                var y = top + size + annGap
                if (ph != null) {
                    drawAnnotation(ph, r, 1, area, y + annSize * 0.86f, annSize, annAlpha, annLit, progress)
                    y += annSize + annGap
                }
                if (tr != null) drawAnnotation(tr, r, 2, area, y + annSize * 0.86f, annSize, annAlpha, annLit, progress)
            }
        }
    }

    /**
     * 惯性：转视角时整列往反方向滞后一点，跳起 / 落下时上下沉浮，停下后带一点回弹归位（像头盔显示器跟着头晃）。
     * 偏多少看转得多快（有上限，猛甩鼠标也只偏这么多），由一个略欠阻尼的弹簧带着走。
     * 视角和镜头高度每帧都是新的（镜头高度按帧插值过），所以低帧率时也不会一顿一顿的
     */
    private fun updateSway(dt: Float, strength: Float, gap: Boolean) {
        val p = DangoClient.platform.player
        if (strength <= 0f || p == null) {
            swayYaw = Float.NaN
            swayX = 0f; swayY = 0f; swayVX = 0f; swayVY = 0f
            turnYaw = 0f; turnPitch = 0f; rise = 0f
            return
        }
        val camY = DangoClient.gfx.world.cameraY
        if (!p.yaw.isFinite() || !p.pitch.isFinite() || camY.isNaN()) {
            swayYaw = Float.NaN
            turnYaw = 0f; turnPitch = 0f; rise = 0f
        } else if (gap || swayYaw.isNaN()) {
            turnYaw = 0f; turnPitch = 0f; rise = 0f
        } else if (dt > 0f) {
            var dYaw = (p.yaw - swayYaw) % 360f
            if (dYaw > 180f) dYaw -= 360f
            if (dYaw < -180f) dYaw += 360f
            val dPitch = p.pitch - swayPitch
            val dRise = (camY - swayCamY).toFloat()
            if (abs(dYaw) > 60f || abs(dPitch) > 60f || abs(dRise) > 3f || dRise.isNaN()) {
                // 一帧转了 60 度以上 / 挪了 3 格以上：传送、重生、换维度、切换视角，不算，免得整列被甩飞
                turnYaw = 0f; turnPitch = 0f; rise = 0f
            } else {
                // 鼠标是一帧一帧动的：速度先平滑一下，再拿去推弹簧
                val kt = 1f - exp(-dt / 0.06f)
                turnYaw += (dYaw / dt - turnYaw) * kt
                turnPitch += (dPitch / dt - turnPitch) * kt
                rise += (dRise / dt - rise) * (1f - exp(-dt / 0.08f))
            }
        }
        swayYaw = p.yaw
        swayPitch = p.pitch
        swayCamY = camY

        // 往右转（yaw 变大）→ 往左偏；低头（pitch 变大）→ 往上偏；往上跳 → 往下沉，落地时回弹一下
        val reach = SWAY_PX * strength.coerceIn(0f, 1f)
        val tx = -reach * tanh(turnYaw / 220f)
        val ty = (-0.7f * tanh(turnPitch / 220f) + 0.6f * tanh(rise / 6f)) * reach
        var left = dt
        while (left > 1e-5f) {
            val h = min(left, 1f / 240f)
            left -= h
            swayVX += (SWAY_K * (tx - swayX) - SWAY_C * swayVX) * h
            swayVY += (SWAY_K * (ty - swayY) - SWAY_C * swayVY) * h
            swayX += swayVX * h
            swayY += swayVY * h
        }
        // 保险：数值出问题时归零，不让列表飞走
        if (!swayX.isFinite() || !swayY.isFinite()) {
            swayX = 0f; swayY = 0f; swayVX = 0f; swayVY = 0f
        }
    }

    /** 音译 / 翻译一行：放得下直接画，放不下和原文一样两头收起、当前行跟着进度滚。[slot] 1 = 音译，2 = 翻译 */
    private fun drawAnnotation(
        text: String, row: Int, slot: Int, area: LayoutBounds, baseline: Float, size: Float,
        alpha: Float, lit: Float, progress: Float?,
    ) {
        val fullW = fullWidth(text, size)
        if (fullW <= area.width) {
            shifts.remove(row * 3 + slot)
            drawLine(text, area, baseline, size, row, alpha, null, lit)
            return
        }
        val shift = updateShift(row * 3 + slot, progress?.let { it.coerceIn(0f, 1f) * fullW }, fullW, area.width)
        drawWindowed(text, area, baseline, size, 1f, row, alpha, null, lit, shift, fullW)
    }

    /**
     * 太长的行这一帧往左滚多少（和 [fullW]、[win] 同一个字号下量的）：[at] 是要留在视野里的位置（唱到哪儿了），
     * 让它停在窗口 55% 左右；null = 不是当前行，回到开头。都是平滑跟过去的，唱完的那行往上滚时也是慢慢滚回开头，不会一下跳。
     * [key] = 行号 × 3 + 第几段（0 原文，1 音译，2 翻译）
     */
    private fun updateShift(key: Int, at: Float?, fullW: Float, win: Float): Float {
        val maxShift = (fullW - win).coerceAtLeast(0f)
        val target = if (at == null) 0f else (at - win * 0.55f).coerceIn(0f, maxShift)
        val from = (shifts[key] ?: 0f).coerceIn(0f, maxShift)
        val next = from + (target - from) * (1f - exp(-frameDt / 0.3f))
        if (target == 0f && next < 0.5f) {
            shifts.remove(key)
            return 0f
        }
        shifts[key] = next
        return next
    }

    /** 唱到的位置离行首多远（[sung] 是唱过的字数，小数部分是正在唱的那个字唱了多少） */
    private fun sungX(text: String, sung: Float, size: Float): Float {
        val s = sung.coerceAtLeast(0f)
        val whole = s.toInt()
        val frac = s - whole
        var pen = 0f
        var counted = 0
        var i = 0
        while (i < text.length) {
            val n = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length) 2 else 1
            val cw = textWidth(text.substring(i, i + n), size)
            if (!text[i].isWhitespace()) {
                if (counted == whole) return pen + cw * frac
                counted++
            }
            pen += cw
            i += n
        }
        return pen
    }

    /**
     * 画一行放不下的字：只画窗口里的那段，两头被收起的地方各放一个暗的 "..."，
     * 字移进 / 移出 "..." 时按半个字的距离淡入淡出（不裁字形，所以不会有被切一半的字）。
     * 长度都按当前行字号量，[scale] = 这一帧字号 / 当前行字号，所以字号变的时候能看到的还是那几个字
     */
    private fun drawWindowed(
        text: String, area: LayoutBounds, baseline: Float, size: Float, scale: Float, row: Int,
        alpha: Float, sung: Float?, lit: Float, shiftAtCurrent: Float, fullAtCurrent: Float,
    ) {
        if (text.isEmpty() || alpha <= 0.005f) return
        val style = CjkStyle.SIDE
        val win = area.width * scale
        val full = fullAtCurrent * scale
        val maxShift = (full - win).coerceAtLeast(0f)
        val shift = (shiftAtCurrent * scale).coerceIn(0f, maxShift)
        val ell = "..."
        val ellW = textWidth(ell, size)
        val ellSpace = ellW + size * 0.12f
        // 开始往左滚时左边的 "..." 才慢慢出现；滚到最后右边的 "..." 慢慢消失
        val leftAmt = (shift / ellW.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val rightAmt = ((maxShift - shift) / ellW.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val leftEdge = ellSpace * leftAmt
        val rightEdge = win - ellSpace * rightAmt
        val dim = 0.42f
        val whole = sung?.coerceAtLeast(0f)?.toInt() ?: 0
        val frac = (sung?.coerceAtLeast(0f) ?: 0f) - whole
        row(lineX(area, win), baseline) {
            var pen = 0f
            var counted = 0
            var i = 0
            while (i < text.length) {
                val n = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length) 2 else 1
                val ch = text.substring(i, i + n)
                val cw = textWidth(ch, size)
                if (!ch[0].isWhitespace()) {
                    val x = pen - shift
                    val half = (cw * 0.5f).coerceAtLeast(0.5f)
                    // 那一边的 "..." 完全出来以后，字在碰到它之前就淡完，不会叠在 "..." 上；
                    // 那一边没有 "..." 时（行首 / 行尾）字不淡
                    val inL = ((x - leftEdge) / half + (1f - leftAmt)).coerceIn(0f, 1f)
                    val inR = ((rightEdge - x - cw) / half + (1f - rightAmt)).coerceIn(0f, 1f)
                    val m = min(inL, inR)
                    if (m > 0.01f) {
                        var l = lit
                        var a = alpha
                        if (sung != null) {
                            when {
                                counted < whole -> l = 1f
                                counted == whole -> l = dim + (1f - dim) * frac
                                else -> { l = dim; a = alpha * 0.85f }
                            }
                        }
                        charColor((pen + cw / 2f) / full.coerceAtLeast(1f), row)
                        ink(ch, x, size, style, l, a * m * m * (3f - 2f * m))
                    }
                    counted++
                }
                pen += cw
                i += n
            }
            val ellLit = if (sung != null) dim else lit * 0.85f
            if (leftAmt > 0.01f) {
                charColor(0f, row)
                ink(ell, 0f, size, style, ellLit, alpha * 0.85f * leftAmt)
            }
            if (rightAmt > 0.01f) {
                charColor(1f, row)
                ink(ell, win - ellW, size, style, ellLit, alpha * 0.85f * rightAmt)
            }
        }
    }

    /** 整行的宽度（缓存，换歌、换字体时清空） */
    private fun fullWidth(s: String, size: Float): Float {
        val key = "${size.toBits()}|$s"
        widthCache[key]?.let { return it }
        val w = textWidth(s, size)
        if (widthCache.size > 512) widthCache.clear()
        widthCache[key] = w
        return w
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

        /** 字号（逻辑像素，再乘“字号”设置）：当前行、其它行、音译和翻译；注释行和上一行之间的间隔 */
        const val CURRENT_SIZE = 21f
        const val OTHER_SIZE = 15f
        const val ANN_SIZE = 11f
        const val ANN_GAP = 4f

        /** 惯性：强度 1 时最多偏多少（逻辑像素）；弹簧约 2.4 Hz、阻尼比约 0.55（停下时轻轻回弹一下） */
        const val SWAY_PX = 26f
        const val SWAY_K = 227f
        const val SWAY_C = 16.6f

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
