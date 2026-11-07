package me.sorihiko.dangomusicgtnh.network

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.PlayerEvent
import cpw.mods.fml.common.gameevent.TickEvent
import net.minecraft.entity.player.EntityPlayerMP

object ServerEvents {
    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.END) NearbyServer.tick()
    }

    @SubscribeEvent
    fun onPlayerLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.player as? EntityPlayerMP)?.let(NearbyServer::onLogout)
    }
}
