package me.sorihiko.dangomusicgtnh.client

import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.NativeLogin
import me.sorihiko.dangomusicgtnh.music.NeteaseLogin
import me.sorihiko.dangomusicgtnh.platform.Platform
import me.sorihiko.dangomusicgtnh.platform.PlayerPose
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

internal object ClientMusic {
    private val controls = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { command -> Thread(command, "DangoMusic-controls").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    @JvmStatic
    fun start(directory: File, initialVolume: Float, saveVolume: Consumer<Float>) {
        DangoMusic.start(ClientPlatform(directory.toPath()), emptyList())
        MusicPlayer.volume = initialVolume
        Runtime.getRuntime().addShutdownHook(Thread({
            controls.shutdownNow()
            NeteaseLogin.cancel()
            NativeLogin.cancel()
            MusicPlayer.shutdown()
            saveVolume.accept(MusicPlayer.volume)
        }, "DangoMusic-shutdown"))
    }

    fun control(action: () -> Unit) = controls.execute { action() }
}

private class ClientPlatform(override val configDir: Path) : Platform {
    private val client get() = Minecraft.getMinecraft()
    override val playerName get() = client.session.username
    override val languageCode get() = client.gameSettings?.language
    override val windowWidth get() = client.displayWidth
    override val windowHeight get() = client.displayHeight
    override val mouseX get() = Mouse.getX()
    override val mouseY get() = windowHeight - Mouse.getY() - 1
    override val ctrlDown get() = GuiScreen.isCtrlKeyDown()
    override var clipboard: String
        get() = GuiScreen.getClipboardString()
        set(value) {
            GuiScreen.setClipboardString(value)
        }

    override val inGame get() = client.currentScreen == null && client.theWorld != null
    override val inMainMenu get() = client.currentScreen is GuiMainMenu ||
        client.currentScreen?.javaClass?.name == "lumien.custommainmenu.gui.GuiCustom"
    override val chatOpen get() = client.currentScreen is GuiChat
    override val guiScale get() = ScaledResolution(client, windowWidth, windowHeight).scaleFactor
    override val fps get() = 0
    override val fov get() = client.gameSettings.fovSetting
    override val pingMs get() = 0
    override val player get() = client.thePlayer?.let { player ->
        PlayerPose(player.posX, player.posY + player.eyeHeight, player.posZ, player.rotationYaw, player.rotationPitch)
    }

    override fun isMouseDown(button: Int) = Mouse.isButtonDown(button)
    override fun isKeyDown(key: Int): Boolean = when {
        key in -100..-1 -> Mouse.isButtonDown(key + 100)
        key in 1 until Keyboard.KEYBOARD_SIZE -> Keyboard.isKeyDown(key)
        else -> false
    }

    override fun openMusicScreen() {
        client.displayGuiScreen(PlayerScreen(client.currentScreen))
    }
}
