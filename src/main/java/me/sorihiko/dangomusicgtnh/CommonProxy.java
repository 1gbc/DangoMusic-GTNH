package me.sorihiko.dangomusicgtnh;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

import me.sorihiko.dangomusicgtnh.network.NetworkBootstrap;
import me.sorihiko.dangomusicgtnh.network.ServerBootstrap;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        NetworkBootstrap.init();
        if (FMLCommonHandler.instance().getSide().isServer()) {
            ServerBootstrap.init(event);
        }
    }
}
