package me.sorihiko.dangomusicgtnh.client

import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.MusicSources
import me.sorihiko.dangomusicgtnh.music.NativeLogin
import me.sorihiko.dangomusicgtnh.music.NeteaseLogin
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiTextField
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class PlayerScreen(private val parent: GuiScreen?) : GuiScreen() {
    private val requests = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "DangoMusic-search").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private var source = MusicSources.selected
    private var tracks = emptyList<NeteaseTrack>()
    private var searchTask: Future<List<NeteaseTrack>>? = null
    private var notice = "Search for music or sign in using a QR code"
    private var firstTrack = 0
    private var loginVisible = false
    private lateinit var query: GuiTextField
    private lateinit var providerButton: GuiButton
    private lateinit var pauseButton: GuiButton
    private val qrTextures = mutableMapOf<String, Pair<BufferedImage, ResourceLocation>>()
    private var left = 0
    private var panelWidth = 0
    private val rowHeight = 16
    private val listTop = 69
    private val rowCount get() = ((height - 76 - listTop) / rowHeight).coerceAtLeast(1)

    override fun initGui() {
        val previousQuery = if (::query.isInitialized) query.text else ""
        panelWidth = (width - 16).coerceAtMost(440)
        left = (width - panelWidth) / 2
        providerButton = addButton(0, left, 25, 76, source.label)
        addButton(1, left + panelWidth - 103, 25, 50, "Search")
        addButton(2, left + panelWidth - 50, 25, 50, "QR Login")
        query = GuiTextField(fontRendererObj, left + 80, 26, (panelWidth - 187).coerceAtLeast(12), 18)
        query.maxStringLength = 128
        query.text = previousQuery
        query.isFocused = true
        val names = listOf("Prev", "Pause", "Next", "Vol −", "Vol +", "Done")
        val cell = panelWidth / names.size
        names.forEachIndexed { index, label ->
            val button = addButton(index + 3, left + index * cell, height - 25, cell - 1, label)
            if (index == 1) pauseButton = button
        }
    }

    private fun addButton(id: Int, x: Int, y: Int, size: Int, label: String): GuiButton =
        GuiButton(id, x, y, size, 20, label).also { buttonList.add(it) }

    override fun actionPerformed(button: GuiButton) {
        when (button.id) {
            0 -> {
                val choices = MusicSource.values()
                source = choices[(source.ordinal + 1) % choices.size]
                MusicSources.selected = source
                providerButton.displayString = source.label
                cancelLogin()
                searchTask?.cancel(true)
                searchTask = null
                tracks = emptyList()
                firstTrack = 0
                notice = "Search ${source.label}"
            }
            1 -> search()
            2 -> {
                if (loginVisible) cancelLogin()
                else {
                    loginVisible = true
                    if (source == MusicSource.NETEASE) NeteaseLogin.start() else NativeLogin.start(source)
                }
            }
            3 -> PlaybackView.previous()
            4 -> PlaybackView.togglePause()
            5 -> PlaybackView.next()
            6 -> ClientMusic.setVolume(MusicPlayer.volume - 0.05f)
            7 -> ClientMusic.setVolume(MusicPlayer.volume + 0.05f)
            8 -> mc.displayGuiScreen(parent)
        }
    }

    private fun search() {
        val keyword = query.text.trim()
        if (keyword.isEmpty()) return
        cancelLogin()
        val provider = source
        searchTask?.cancel(true)
        requests.purge()
        tracks = emptyList()
        firstTrack = 0
        notice = "Searching ${provider.label}…"
        searchTask = requests.submit<List<NeteaseTrack>> { MusicSources.api(provider).search(keyword) }
    }

    override fun updateScreen() {
        query.updateCursorCounter()
        val pending = searchTask
        if (pending != null && pending.isDone) {
            searchTask = null
            try {
                tracks = pending.get()
                notice = if (tracks.isEmpty()) "No results" else "${tracks.size} tracks"
            } catch (_: Exception) {
                notice = "Search failed. Check the network and try again."
            }
        }
    }

    override fun keyTyped(character: Char, keyCode: Int) {
        when (keyCode) {
            Keyboard.KEY_ESCAPE -> if (loginVisible) cancelLogin() else mc.displayGuiScreen(parent)
            Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> if (!loginVisible) search()
            else -> if (!loginVisible) query.textboxKeyTyped(character, keyCode)
        }
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val wheel = Mouse.getEventDWheel()
        if (!loginVisible && wheel != 0) {
            firstTrack = (firstTrack - wheel.sign()).coerceIn(0, (tracks.size - rowCount).coerceAtLeast(0))
        }
    }

    private fun Int.sign() = if (this > 0) 1 else -1

    override fun mouseClicked(x: Int, y: Int, button: Int) {
        super.mouseClicked(x, y, button)
        if (mc.currentScreen !== this || loginVisible) return
        query.mouseClicked(x, y, button)
        if (button != 0 || x !in left until left + panelWidth) return
        if (y in listTop until listTop + rowCount * rowHeight) {
            val index = firstTrack + (y - listTop) / rowHeight
            val result = tracks
            if (index in result.indices) ClientMusic.control { MusicPlayer.playQueue(result, index) }
        } else if (y in height - 46..height - 36) {
            val snap = PlaybackView.snapshot()
            if (!snap.remote && snap.durationMs > 0) {
                PlaybackView.seek(snap.durationMs * (x - left) / panelWidth)
            }
        }
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        drawDefaultBackground()
        drawCenteredString(fontRendererObj, "DangoMusic GTNH Edition", width / 2, 9, 0xFFFFFF)
        query.drawTextBox()
        if (loginVisible) drawLogin() else drawResults(mouseX, mouseY)
        val snap = PlaybackView.snapshot()
        val playing = snap.track
        val suffix = if (snap.sourceLabel.isNotEmpty()) "  [${snap.sourceLabel}]" else ""
        val now = if (playing == null) "Stopped" else "${playing.name}  ${formatTime(snap.positionMs)} / ${formatTime(snap.durationMs)}$suffix"
        drawString(fontRendererObj, fontRendererObj.trimStringToWidth(now, panelWidth - 60), left, height - 66, 0xFFFFFF)
        drawString(fontRendererObj, "Vol ${(MusicPlayer.volume * 100).toInt()}%", left + panelWidth - 55, height - 66, 0xC0C0C0)
        drawString(fontRendererObj, fontRendererObj.trimStringToWidth(snap.message, panelWidth), left, height - 54, 0xC0C0C0)
        val end = left + panelWidth
        val elapsed = snap.positionMs.coerceIn(0, snap.durationMs.coerceAtLeast(0))
        val progress = elapsed * panelWidth / snap.durationMs.coerceAtLeast(1)
        drawRect(left, height - 44, end, height - 38, 0xFF3C3C3C.toInt())
        drawRect(left, height - 44, left + progress.toInt(), height - 38, 0xFF59A8FF.toInt())
        pauseButton.displayString = if (snap.playing) "Pause" else "Resume"
        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun drawResults(mouseX: Int, mouseY: Int) {
        drawString(fontRendererObj, notice, left, 54, 0xCCCCCC)
        for (index in firstTrack until minOf(tracks.size, firstTrack + rowCount)) {
            val top = listTop + (index - firstTrack) * rowHeight
            val hover = mouseX in left until left + panelWidth && mouseY in top until top + rowHeight
            if (hover) drawRect(left, top, left + panelWidth, top + rowHeight, 0x885A84B5.toInt())
            val track = tracks[index]
            val title = "${track.name}  ·  ${track.artists.joinToString(", ")}"
            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(title, panelWidth - 6), left + 3, top + 4, 0xFFFFFF)
        }
    }

    private fun drawLogin() {
        val netease = source == MusicSource.NETEASE
        val state = if (netease) NeteaseLogin.state else NativeLogin.state
        val image = if (netease) NeteaseLogin.qrImage else NativeLogin.qrImage
        drawString(fontRendererObj, "${source.label} QR login · $state", left, 54, 0xFFFFFF)
        if (state == NeteaseLogin.State.SUCCESS) return
        val size = (height - 143).coerceIn(32, 110)
        drawQr("main", image, if (source == MusicSource.KUGOU) width / 2 - size - 8 else (width - size) / 2, 68, size)
        if (source == MusicSource.KUGOU) drawQr("lite", NativeLogin.liteImage, width / 2 + 8, 68, size)
    }

    private fun drawQr(key: String, image: BufferedImage?, x: Int, y: Int, size: Int) {
        if (image == null) return
        val previous = qrTextures[key]
        val texture = if (previous != null && previous.first === image) previous.second else {
            previous?.let { mc.textureManager.deleteTexture(it.second) }
            mc.textureManager.getDynamicTextureLocation("dangomusicgtnh_qr", DynamicTexture(image))
                .also { qrTextures[key] = image to it }
        }
        mc.textureManager.bindTexture(texture)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        Gui.func_146110_a(x, y, 0f, 0f, size, size, size.toFloat(), size.toFloat())
    }

    private fun cancelLogin() {
        if (loginVisible) {
            NeteaseLogin.cancel()
            NativeLogin.cancel()
            loginVisible = false
        }
        qrTextures.values.forEach { mc.textureManager.deleteTexture(it.second) }
        qrTextures.clear()
    }

    override fun onGuiClosed() {
        searchTask?.cancel(true)
        requests.shutdownNow()
        cancelLogin()
    }

    override fun doesGuiPauseGame() = false
}
