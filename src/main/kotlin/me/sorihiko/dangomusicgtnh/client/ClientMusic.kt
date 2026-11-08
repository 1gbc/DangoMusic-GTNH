package me.sorihiko.dangomusicgtnh.client

import cpw.mods.fml.common.FMLCommonHandler
import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.legacy.LegacyGfx
import me.sorihiko.dangomusicgtnh.legacy.LegacyKeyBindings
import me.sorihiko.dangomusicgtnh.legacy.LegacyMusicScreen
import me.sorihiko.dangomusicgtnh.legacy.mc
import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.NativeLogin
import me.sorihiko.dangomusicgtnh.music.NearbyAudioPlayer
import me.sorihiko.dangomusicgtnh.music.NeteaseLogin
import me.sorihiko.dangomusicgtnh.network.NearbyClient
import me.sorihiko.dangomusicgtnh.platform.Platform
import me.sorihiko.dangomusicgtnh.platform.ListenerEnvironment
import me.sorihiko.dangomusicgtnh.platform.PlayerAction
import me.sorihiko.dangomusicgtnh.platform.PlayerKeyBinding
import me.sorihiko.dangomusicgtnh.platform.PlayerPose
import net.minecraft.client.Minecraft
import net.minecraft.block.Block
import net.minecraft.block.BlockLeaves
import net.minecraft.block.material.Material
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.util.MathHelper
import net.minecraft.util.MovingObjectPosition
import net.minecraft.util.Vec3
import net.minecraftforge.common.MinecraftForge
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import java.io.File
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object ClientMusic {
    private val controls = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { command -> Thread(command, "DangoMusic-controls").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    @JvmStatic
    fun start(directory: File, initialVolume: Float) {
        val hasSettings = directory.resolve("settings.json").isFile
        mc = Minecraft.getMinecraft()
        DangoClient.start(ClientPlatform(directory.toPath()), LegacyGfx)
        NearbyClient.init()
        FMLCommonHandler.instance().bus().register(NearbyClient)
        if (!hasSettings) setVolume(initialVolume)
        LegacyKeyBindings.register()
        FMLCommonHandler.instance().bus().register(ClientRenderBridge)
        MinecraftForge.EVENT_BUS.register(ClientRenderBridge)
        MinecraftForge.EVENT_BUS.register(DanceRenderHook)
        // 续播在 NeteaseMusicModule.onEnable 里触发：模块到第 3 个 tick 才启用，这里还太早
        Runtime.getRuntime().addShutdownHook(Thread({
            controls.shutdownNow()
            NeteaseLogin.cancel()
            NativeLogin.cancel()
            NearbyAudioPlayer.stop()
            MusicPlayer.shutdown()
        }, "DangoMusic-shutdown"))
    }

    fun control(action: () -> Unit) = controls.execute { action() }

    fun setVolume(value: Float) {
        DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java).setVolume(value)
    }

    @JvmStatic
    fun open(parent: GuiScreen?) {
        val screen = if (ClientRenderBridge.graphicsReady) LegacyMusicScreen(parent) else PlayerScreen(parent)
        mc.displayGuiScreen(screen)
    }
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
    override val fps get() = client.debug?.substringBefore(' ')?.toIntOrNull() ?: 0
    override val fov get() = client.gameSettings.fovSetting
    override val pingMs get() =
        client.netHandler?.playerInfoList?.firstOrNull { it.name == playerName }?.responseTime ?: 0
    override val skyDarken get() = client.theWorld?.skylightSubtracted
    override val player get() = client.thePlayer?.let { player ->
        PlayerPose(player.posX, player.posY + player.eyeHeight, player.posZ, player.rotationYaw, player.rotationPitch)
    }
    override val dimension get() = client.thePlayer?.dimension ?: 0

    private val absorption = IdentityHashMap<Block, Float>()
    private val soundDirections = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0),
        intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        intArrayOf(1, 1, 1), intArrayOf(1, 1, -1), intArrayOf(-1, 1, 1), intArrayOf(-1, 1, -1),
        intArrayOf(1, -1, 1), intArrayOf(1, -1, -1), intArrayOf(-1, -1, 1), intArrayOf(-1, -1, -1)
    )

    override fun soundAbsorption(x: Int, y: Int, z: Int): Float {
        val world = client.theWorld ?: return -1f
        if (!world.blockExists(x, y, z)) return -1f
        val block = world.getBlock(x, y, z)
        return absorption.getOrPut(block) {
            val material = block.material
            if (block is BlockLeaves || material == Material.leaves || material.isLiquid || !material.blocksMovement()) -1f
            else when (block.stepSound) {
                Block.soundTypeStone, Block.soundTypePiston -> 0.03f
                Block.soundTypeMetal, Block.soundTypeAnvil -> 0.04f
                Block.soundTypeGlass -> 0.05f
                Block.soundTypeWood, Block.soundTypeLadder -> 0.12f
                Block.soundTypeGrass -> 0.3f
                Block.soundTypeGravel, Block.soundTypeSand -> 0.35f
                Block.soundTypeSnow -> 0.5f
                Block.soundTypeCloth -> 0.7f
                else -> 0.1f
            }
        }
    }

    override fun clearFraction(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): Double {
        val world = client.theWorld ?: return 1.0
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val len2 = dx * dx + dy * dy + dz * dz
        if (len2 < 1e-8) return 1.0
        // 起点会被 func_147447_a 改写，每次都要新建；忽略没有碰撞箱的方块（草、花、火把），不停在液体上
        val hit = try {
            world.func_147447_a(
                Vec3.createVectorHelper(x0, y0, z0), Vec3.createVectorHelper(x1, y1, z1),
                false, true, false,
            )
        } catch (_: RuntimeException) {
            null
        } ?: return 1.0
        if (hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return 1.0
        val v = hit.hitVec ?: return 1.0
        val t = ((v.xCoord - x0) * dx + (v.yCoord - y0) * dy + (v.zCoord - z0) * dz) / len2
        return t.coerceIn(0.0, 1.0)
    }

    override val environment: ListenerEnvironment?
        get() {
            val player = client.thePlayer ?: return null
            val world = client.theWorld ?: return null
            if (!MusicPlayer.active) return null
            val eyeY = player.posY + player.eyeHeight
            val x = MathHelper.floor_double(player.posX)
            val y = MathHelper.floor_double(eyeY)
            val z = MathHelper.floor_double(player.posZ)
            if (!world.blockExists(x, y, z)) return null
            val hits = soundDirections.count { direction ->
                val scale = if (direction.all { it != 0 }) 0.57735 else 1.0
                (1..6).any { step ->
                    soundAbsorption(
                        MathHelper.floor_double(player.posX + direction[0] * step * scale),
                        MathHelper.floor_double(eyeY + direction[1] * step * scale),
                        MathHelper.floor_double(player.posZ + direction[2] * step * scale)
                    ) >= 0f
                }
            }
            val sky = world.canBlockSeeTheSky(x, y, z)
            val rain = if (sky && world.isRaining && world.getBiomeGenForCoords(x, z).canSpawnLightningBolt()) {
                if (world.isThundering) 1f else 0.5f
            } else 0f
            return ListenerEnvironment(player.isInsideOfMaterial(Material.water), sky, hits / soundDirections.size.toFloat(), rain)
        }

    override fun isMouseDown(button: Int) = Mouse.isButtonDown(button)
    override fun syncKeyBindings(bindings: Collection<PlayerKeyBinding>) = LegacyKeyBindings.sync(bindings)
    override fun isPlayerKeyModifier(keyCode: Int) = LegacyKeyBindings.isModifierKey(keyCode)
    override fun activePlayerKeyModifier() = LegacyKeyBindings.activeModifier()
    override fun playerKeyModifierActive(action: PlayerAction) = LegacyKeyBindings.modifierActive(action)
    override fun playerKeyDisplayName(action: PlayerAction) = LegacyKeyBindings.displayName(action)
    override fun isKeyDown(key: Int): Boolean = when {
        key in -100..-1 -> Mouse.isButtonDown(key + 100)
        key in 1 until Keyboard.KEYBOARD_SIZE -> Keyboard.isKeyDown(key)
        else -> false
    }

    override fun openMusicScreen() {
        ClientMusic.open(client.currentScreen)
    }
}
