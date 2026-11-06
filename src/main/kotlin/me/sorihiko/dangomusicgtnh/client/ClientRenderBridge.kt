package me.sorihiko.dangomusicgtnh.client

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.TickEvent as ForgeTickEvent
import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.events.HudRenderEvent
import me.sorihiko.dangomusicgtnh.events.TickEvent
import me.sorihiko.dangomusicgtnh.events.WorldRenderEndEvent
import me.sorihiko.dangomusicgtnh.legacy.LegacyGl
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.client.event.RenderWorldLastEvent

internal object ClientRenderBridge {
    private var graphicsAttempted = false
    private var playerListResolution: ScaledResolution? = null
    var graphicsReady = false
        private set

    @SubscribeEvent
    fun clientTick(event: ForgeTickEvent.ClientTickEvent) {
        if (event.phase != ForgeTickEvent.Phase.START) return
        if (!graphicsAttempted) {
            graphicsAttempted = true
            try {
                LegacyGl.init()
                graphicsReady = true
                DangoLogger.info("GTNH glass renderer initialized")
            } catch (error: Exception) {
                DangoLogger.error("GTNH glass renderer initialization failed: {}", error)
            } catch (error: LinkageError) {
                DangoLogger.error("GTNH glass renderer binding is unavailable: {}", error)
            }
        }
        DangoMusic.callEvent(TickEvent)
    }

    @SubscribeEvent
    fun menu(event: GuiScreenEvent.DrawScreenEvent.Post) {
        if (graphicsReady && DangoClient.platform.inMainMenu) DangoClient.renderMainMenuNowPlaying()
    }

    @SubscribeEvent
    fun overlay(event: RenderGameOverlayEvent.Post) {
        if (!graphicsReady) return
        when (event.type) {
            RenderGameOverlayEvent.ElementType.PLAYER_LIST -> playerListResolution = event.resolution
            RenderGameOverlayEvent.ElementType.ALL -> {
                val drawn = playerListResolution === event.resolution
                playerListResolution = null
                DangoMusic.callEvent(HudRenderEvent(if (drawn) PlayerListOverlay.bounds(event.resolution) else null))
            }
            else -> Unit
        }
    }

    @SubscribeEvent
    fun world(event: RenderWorldLastEvent) {
        if (!graphicsReady) return
        val eyeY = Minecraft.getMinecraft().thePlayer?.let { player ->
            player.lastTickPosY + (player.posY - player.lastTickPosY) * event.partialTicks + player.eyeHeight
        }
        DangoMusic.callEvent(WorldRenderEndEvent(event.partialTicks, eyeY))
    }
}
