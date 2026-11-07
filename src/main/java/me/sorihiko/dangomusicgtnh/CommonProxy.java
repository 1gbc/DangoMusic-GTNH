package me.sorihiko.dangomusicgtnh;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

import me.sorihiko.dangomusicgtnh.network.NetworkBootstrap;
import me.sorihiko.dangomusicgtnh.network.ServerBootstrap;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        NetworkBootstrap.init();
        if (FMLCommonHandler.instance().getSide().isServer()) {
            ServerBootstrap.init(event);
        }
    }

    public void serverStarting(FMLServerStartingEvent event) {
        // FMLServerStartingEvent also fires for the integrated server on a client.
        // Keep the server-only command (and classes) off the client side.
        if (FMLCommonHandler.instance().getSide().isServer()) {
            ServerBootstrap.serverStarting(event);
        }
    }
}
