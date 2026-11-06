package me.sorihiko.dangomusicgtnh.module.impls.music

import me.sorihiko.dangomusicgtnh.musicui.Library
import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.event.EventHandler
import me.sorihiko.dangomusicgtnh.events.TickEvent
import me.sorihiko.dangomusicgtnh.events.HudRenderEvent
import me.sorihiko.dangomusicgtnh.events.WorldRenderEndEvent
import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.lyrics.Lyrics
import me.sorihiko.dangomusicgtnh.module.Category
import me.sorihiko.dangomusicgtnh.module.Module
import me.sorihiko.dangomusicgtnh.module.ModuleMeta
import me.sorihiko.dangomusicgtnh.module.values.BooleanValue
import me.sorihiko.dangomusicgtnh.module.values.FloatValue
import me.sorihiko.dangomusicgtnh.module.values.IntValue
import me.sorihiko.dangomusicgtnh.module.values.ModuleValue
import me.sorihiko.dangomusicgtnh.music.EnvironmentAudio
import me.sorihiko.dangomusicgtnh.music.RoomAcoustics
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.NeteaseApi
import me.sorihiko.dangomusicgtnh.music.NeteaseQuality
import me.sorihiko.dangomusicgtnh.music.MusicSources
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.platform.Keys
import me.sorihiko.dangomusicgtnh.platform.allowedPlayerKey
import me.sorihiko.dangomusicgtnh.platform.PlayerAction
import me.sorihiko.dangomusicgtnh.platform.PlayerKeyBinding

/**
 * 内置网易云播放器。按 Open key（默认 M）打开播放器界面，播放自己的网易云歌单。
 * 登录：把网易云网页版的 MUSIC_U Cookie 填进 config/dangomusic/netease_cookie.txt
 * 开着 Lyrics 模块时，会优先用这里的播放进度、歌曲 ID 和封面。
 */
@ModuleMeta(Category.MUSIC, "NeteaseMusic")
class NeteaseMusicModule : Module() {

    @ModuleValue("Music source", "Select a music provider")
    private val musicSource = IntValue(0, 2, 0)

    fun selectSource(source: MusicSource) {
        musicSource.setValue(source.ordinal)
        MusicSources.selected = source
    }

    @ModuleValue("Volume", "Playback volume")
    private val volume = FloatValue(0f, 1f, 0.2f, 2)

    @ModuleValue("Audio output", "Where the music plays through")
    // 0 自动（安卓用游戏的 OpenAL，电脑用 Java Sound），1 Java Sound，2 游戏的 OpenAL
    private val audioOutput = IntValue(0, 2, 0)

    @ModuleValue("NetEase audio quality", "")
    private val audioQuality = IntValue(0, NeteaseQuality.values().lastIndex, NeteaseQuality.LOSSLESS.ordinal)

    internal val preferredQuality get() = NeteaseQuality.values()[audioQuality.value]

    internal fun selectQuality(quality: NeteaseQuality) {
        audioQuality.setValue(quality.ordinal)
        ClientMusic.control { MusicPlayer.setNeteaseQuality(quality) }
    }

    // 播放器窗口的位置和大小：占屏幕的比例（换分辨率 / GUI 缩放也不会跑到屏幕外）；宽为 0 = 默认大小。
    // 拖窗口边缘时改，设置页里不显示
    @ModuleValue("Window X", "")
    private val windowX = FloatValue(0f, 1f, 0f, 4)
    @ModuleValue("Window Y", "")
    private val windowY = FloatValue(0f, 1f, 0f, 4)
    @ModuleValue("Window width", "")
    private val windowW = FloatValue(0f, 1f, 0f, 4)
    @ModuleValue("Window height", "")
    private val windowH = FloatValue(0f, 1f, 0f, 4)

    @ModuleValue("Lyrics page font size", "")
    internal val lyricsPageSize = IntValue(14, 30, 22)
    @ModuleValue("Lyrics page translation", "")
    internal val lyricsPageTranslation = BooleanValue(true)
    @ModuleValue("Lyrics page romanization", "")
    internal val lyricsPageRomanization = BooleanValue(false)

    /** 玩家拖过的窗口位置大小 [x, y, 宽, 高]（占屏幕比例）；没拖过是 null */
    fun windowFrame(): FloatArray? =
        if (windowW.value <= 0f || windowH.value <= 0f) null
        else floatArrayOf(windowX.value, windowY.value, windowW.value, windowH.value)

    /** 记下窗口位置大小；null = 恢复默认 */
    fun setWindowFrame(frame: FloatArray?) {
        if (frame == null) {
            windowW.setValue(0f)
            windowH.setValue(0f)
            return
        }
        windowX.setValue(frame[0]); windowY.setValue(frame[1]); windowW.setValue(frame[2]); windowH.setValue(frame[3])
    }

    @ModuleValue("Language", "Choose the player interface language")
    // -1 表示尚未选择界面语言。
    private val language = IntValue(-1, 1, -1)

    fun applyLanguage() {
        if (language.value < 0) language.setValue(I18n.CHINESE)
        I18n.language = language.value
    }

    @ModuleValue("Shuffle", "Play songs in random order")
    private val shuffle = BooleanValue(false)

    @ModuleValue("Repeat one", "Repeat the current song when it finishes")
    private val repeatOne = BooleanValue(false)

    @ModuleValue("Open key", "Key that opens the player")
    val openKey = IntValue(-100, 255, Keys.M)

    // 其它快捷键：默认都不绑定（0 = 无），在播放器设置里自己设
    @ModuleValue("Play / Pause", "Hotkey to play or pause")
    private val keyPause = IntValue(-100, 255, 0)

    @ModuleValue("Previous", "Hotkey for the previous song")
    private val keyPrev = IntValue(-100, 255, 0)

    @ModuleValue("Stop playback", "Hotkey to stop playback")
    private val keyStop = IntValue(-100, 255, 0)

    @ModuleValue("Next", "Hotkey for the next song")
    private val keyNext = IntValue(-100, 255, 0)

    @ModuleValue("Volume up", "Hotkey to raise the volume by 5%")
    private val keyVolUp = IntValue(-100, 255, 0)

    @ModuleValue("Volume down", "Hotkey to lower the volume by 5%")
    private val keyVolDown = IntValue(-100, 255, 0)

    @ModuleValue("Toggle lyrics", "Hotkey to turn lyrics on or off")
    private val keyLyrics = IntValue(-100, 255, 0)

    @ModuleValue("Volume normalization", "Keep songs at a similar loudness")
    private val normalize = BooleanValue(true)

    @ModuleValue("Original audio", "Bypass volume normalization and in-game environment effects")
    private val originalAudio = BooleanValue(false)

    @ModuleValue("Playback fades", "Brief fades when switching, pausing or resuming songs")
    private val playbackFades = BooleanValue(true)

    @ModuleValue("Environment audio", "Echo follows the room you are in (size and materials), muffled underwater")
    private val envAudio = BooleanValue(true)

    @ModuleValue("Rain", "A little quieter in rain and thunder")
    private val envRain = BooleanValue(true)

    @ModuleValue("Environment audio strength", "How strong the environment effects are")
    private val envStrength = FloatValue(0f, 1f, 1f, 2)

    @ModuleValue("Show sound rays", "Debug: draw the rays used to measure the room, and its numbers")
    private val envRays = BooleanValue(false)


    private val keyBindings by lazy {
        listOf(
            PlayerKeyBinding(PlayerAction.OPEN, openKey), PlayerKeyBinding(PlayerAction.PAUSE, keyPause),
            PlayerKeyBinding(PlayerAction.PREVIOUS, keyPrev), PlayerKeyBinding(PlayerAction.NEXT, keyNext),
            PlayerKeyBinding(PlayerAction.VOLUME_UP, keyVolUp), PlayerKeyBinding(PlayerAction.VOLUME_DOWN, keyVolDown),
            PlayerKeyBinding(PlayerAction.LYRICS, keyLyrics), PlayerKeyBinding(PlayerAction.STOP, keyStop),
        ).associateBy { it.action }
    }

    fun resetKeyBinding(value: IntValue) {
        val binding = keyBindings.values.firstOrNull { it.value === value } ?: return
        binding.rebind(binding.action.defaultKey)
    }

    fun rebindKey(value: IntValue, keyCode: Int, modifier: String? = null) {
        keyBindings.values.firstOrNull { it.value === value }?.rebind(keyCode, modifier)
    }

    fun keyDisplayName(value: IntValue): String? {
        val binding = keyBindings.values.firstOrNull { it.value === value } ?: return null
        return DangoMusic.instance.platform.playerKeyDisplayName(binding.action)
    }

    private var wasDown = false

    /** 界面用打开键关闭时，消费这次按下，防止同一 tick 又从游戏里打开。 */
    fun consumeOpenKey() { wasDown = true }
    /** 每个快捷键上一刻是否按着（只在按下的瞬间触发一次） */
    private val hotkeyDown = HashMap<PlayerAction, Boolean>()

    private val hotkeys: List<Pair<PlayerAction, () -> Unit>> by lazy {
        listOf(
            PlayerAction.STOP to { Library.stopPlayback() },
            PlayerAction.PAUSE to { ClientMusic.control { MusicPlayer.togglePause() } },
            PlayerAction.PREVIOUS to { ClientMusic.control { MusicPlayer.prev() } },
            PlayerAction.NEXT to { ClientMusic.control { MusicPlayer.next() } },
            PlayerAction.VOLUME_UP to { changeVolume(0.05f) },
            PlayerAction.VOLUME_DOWN to { changeVolume(-0.05f) },
            PlayerAction.LYRICS to { lyrics()?.toggleLyrics() },
        )
    }

    override fun onEnable() {
        NeteaseApi.ensureCookieFile()
        ClientMusic.control {
            MusicPlayer.repeatOne = repeatOne.get()
            MusicPlayer.shuffle = shuffle.get() && !repeatOne.get()
            MusicPlayer.setNeteaseQuality(preferredQuality)
        }
    }

    override fun onDisable() {
        ClientMusic.control { MusicPlayer.stop() }
    }

    fun setVolume(v: Float) {
        volume.setValue(v)
    }

    private fun changeVolume(d: Float) {
        volume.setValue((volume.value + d).coerceIn(0f, 1f))
        MusicPlayer.volume = volume.value
    }

    fun selectPlaybackMode(mode: MusicPlayer.Mode) {
        ClientMusic.control {
            MusicPlayer.selectMode(mode)
            shuffle.set(mode == MusicPlayer.Mode.SHUFFLE)
            repeatOne.set(mode == MusicPlayer.Mode.REPEAT_ONE)
        }
    }

    /** 播放器界面里按键时调用：是某个快捷键就执行并返回 true */
    fun handleHotkey(keyCode: Int): Boolean {
        val hit = hotkeys.firstOrNull { matchesKey(it.first, keyCode) } ?: return false
        hit.second()
        return true
    }

    fun matchesOpenKey(keyCode: Int) = matchesKey(PlayerAction.OPEN, keyCode)

    private fun matchesKey(action: PlayerAction, keyCode: Int): Boolean =
        allowedPlayerKey(keyCode) != Keys.NONE && keyBindings.getValue(action).value.value == keyCode &&
            DangoMusic.instance.platform.playerKeyModifierActive(action)

    internal fun lyrics(): LyricsModule? = try {
        DangoMusic.instance.moduleManager.get(LyricsModule::class.java)
    } catch (_: Exception) {
        null
    }

    private var tickCount = 0

    /** 调试：把测房间用的射线画在世界里（绿 → 黄 → 橙 → 红 → 紫 = 第几次反弹，灰 = 射出去没碰到东西） */
    @EventHandler
    fun onWorldRender(e: WorldRenderEndEvent) {
        RoomAcoustics.debugRays = enabled && envAudio.get() && envRays.get()
        if (!RoomAcoustics.debugRays) return
        val segments = RoomAcoustics.rays
        if (segments.isEmpty()) return
        val gfx = DangoClient.gfx
        gfx.guarded {
            val world = gfx.world
            val pen = gfx.text
            world.begin()
            fun facing(rx: Double, ry: Double, rz: Double): FloatArray = floatArrayOf(
                Math.toDegrees(kotlin.math.atan2(-rx, rz)).toFloat(),
                Math.toDegrees(kotlin.math.atan2(-ry, kotlin.math.sqrt(rx * rx + rz * rz))).toFloat())
            // 碰到方块的点：一个朝向镜头的小 "+"
            fun mark(x: Double, y: Double, z: Double, argb: Int) {
                val rx = x - world.cameraX; val ry = y - world.cameraY; val rz = z - world.cameraZ
                val f = facing(rx, ry, rz)
                // 远近看起来差不多大
                world.push(rx, ry, rz, f[0], f[1], (0.0015 * kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)).toFloat().coerceIn(0.004f, 0.03f))
                pen.pixelText("+", -pen.pixelWidth("+") / 2f + 0.5f, -4f, argb, false)
                world.pop()
            }
            // 一段线：在线段中点放一块朝向镜头的面，把 "-" 沿线段方向转过去、拉长到线段在这块面上的长度
            fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, argb: Int) {
                val mx = (x1 + x2) / 2 - world.cameraX; val my = (y1 + y2) / 2 - world.cameraY; val mz = (z1 + z2) / 2 - world.cameraZ
                val dist = kotlin.math.sqrt(mx * mx + my * my + mz * mz)
                if (dist < 1e-3) return
                val vx = mx / dist; val vy = my / dist; val vz = mz / dist
                // 镜头看过去的右方向和下方向
                val rX = -vz; val rZ = vx
                val rLen = kotlin.math.sqrt(rX * rX + rZ * rZ).coerceAtLeast(1e-6)
                val rightX = rX / rLen; val rightZ = rZ / rLen
                // 上方向 = 右 × 视线
                val upX = -vy * rightZ; val upY = rightZ * vx - rightX * vz; val upZ = rightX * vy
                val dx = x2 - x1; val dy = y2 - y1; val dz = z2 - z1
                val along = dx * rightX + dz * rightZ
                val across = -(dx * upX + dy * upY + dz * upZ)
                val length = kotlin.math.sqrt(along * along + across * across)
                if (length < 1e-3) return
                val f = facing(mx, my, mz)
                world.push(mx, my, mz, f[0], f[1], 1f / 16f)
                pen.push()
                pen.rotate(Math.toDegrees(kotlin.math.atan2(across, along)).toFloat())
                // "-" 是 5 像素宽、1 像素高；线粗随距离变一点，远处也看得见
                pen.scale((length * 16 / 5).toFloat(), (0.4 + dist * 0.02).toFloat().coerceAtMost(1.5f))
                pen.pixelText("-", -2.5f, -4.5f, argb, false)
                pen.pop()
                world.pop()
            }
            var i = 0
            while (i + 6 < segments.size) {
                val bounce = segments[i + 6].toInt()
                val color = if (bounce < 0) 0x60A0A0A0 else RAY_COLORS[bounce.coerceAtMost(RAY_COLORS.size - 1)]
                val x1 = segments[i].toDouble(); val y1 = segments[i + 1].toDouble(); val z1 = segments[i + 2].toDouble()
                var x2 = segments[i + 3].toDouble(); var y2 = segments[i + 4].toDouble(); var z2 = segments[i + 5].toDouble()
                if (bounce < 0) {
                    // 射出去没碰到的只画前 6 格，不然满天都是线
                    val len = kotlin.math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1))
                    if (len > 6) { val k = 6 / len; x2 = x1 + (x2 - x1) * k; y2 = y1 + (y2 - y1) * k; z2 = z1 + (z2 - z1) * k }
                }
                line(x1, y1, z1, x2, y2, z2, color)
                if (bounce >= 0) mark(x2, y2, z2, color or 0xFF000000.toInt())
                i += 7
            }
        }
    }

    @EventHandler
    fun onHud(e: HudRenderEvent) {
        if (!RoomAcoustics.debugRays) return
        val room = RoomAcoustics.room ?: return
        val lines = listOf(
            "Room: enclosure %.2f".format(room.enclosure),
            "Decay %.2fs  early %.0fms".format(room.decaySeconds, room.earlyDelayMs),
            "Absorption %.2f  pan %+.2f".format(room.absorption, room.pan),
        )
        DangoClient.gfx.guarded {
            val hud = DangoClient.gfx.hud
            hud.begin()
            lines.forEachIndexed { n, line -> hud.text(line, 4f, 60f + n * 10f, 0xFFFFFFFF.toInt(), true) }
            hud.end()
        }
    }

    @EventHandler
    fun onTick(e: TickEvent) {
        // 即使播放器被关闭，也要初始化并保存界面语言。
        applyLanguage()
        MusicSources.selected = MusicSource.values()[musicSource.value]
        DangoMusic.instance.platform.syncKeyBindings(keyBindings.values)
        if (!enabled) return
        // 歌词模块一直开着（显示与否由 3D lyrics 控制）；等配置读回之后再检查
        if (++tickCount > 5) lyrics()?.let { if (!it.enabled) it.enabled = true }
        MusicPlayer.volume = volume.value
        val mode = audioOutput.value
        if (MusicPlayer.outputMode != mode) ClientMusic.control { MusicPlayer.outputMode = mode }
        MusicPlayer.normalize = normalize.get()
        MusicPlayer.originalAudio = originalAudio.get()
        MusicPlayer.playbackFades = playbackFades.get()

        // 环境音效：设置和玩家周围的环境交给解码线程
        EnvironmentAudio.enabled = envAudio.get() && !originalAudio.get()
        EnvironmentAudio.rain = envRain.get()
        EnvironmentAudio.strength = envStrength.value
        // 房间回声：只在放歌时测（要发射线读方块）
        RoomAcoustics.tick(DangoMusic.instance.platform, EnvironmentAudio.enabled && MusicPlayer.state == MusicPlayer.State.PLAYING)
        EnvironmentAudio.update(try { DangoMusic.instance.platform.environment } catch (_: Exception) { null }, RoomAcoustics.room)

        // 打开键额外支持主菜单；其他播放快捷键仍只在世界内响应。
        val platform = DangoMusic.instance.platform
        val inGame = platform.inGame
        val down = try { platform.isKeyDown(openKey.value) } catch (_: Exception) { false }
        val open = down && !wasDown && (inGame || platform.inMainMenu) &&
            platform.playerKeyModifierActive(PlayerAction.OPEN)
        wasDown = down
        if (open) platform.openMusicScreen()

        for ((key, action) in hotkeys) {
            val code = keyBindings.getValue(key).value.value
            val now = code != Keys.NONE && try { platform.isKeyDown(code) } catch (_: Exception) { false }
            if (now && hotkeyDown[key] != true && inGame && platform.playerKeyModifierActive(key)) action()
            hotkeyDown[key] = now
        }
    }
}

/** 调试射线的颜色：第 0~4 次反弹 */
private val RAY_COLORS = intArrayOf(0xC055FF55.toInt(), 0xC0FFFF55.toInt(), 0xC0FFAA33.toInt(), 0xC0FF5555.toInt(), 0xC0CC66FF.toInt())
