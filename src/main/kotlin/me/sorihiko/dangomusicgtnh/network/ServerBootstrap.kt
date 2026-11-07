package me.sorihiko.dangomusicgtnh.network

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import java.io.File

/** Server-only entry point; never loaded on a client. */
object ServerBootstrap {
    @JvmStatic
    fun init(event: FMLPreInitializationEvent) {
        ServerConfig.load(File(event.modConfigurationDirectory, "dangomusicgtnh/server.cfg"))
        FMLCommonHandler.instance().bus().register(ServerEvents)
    }

    @JvmStatic
    fun serverStarting(event: FMLServerStartingEvent) {
        event.registerServerCommand(CommandDangoMusic())
    }
}
