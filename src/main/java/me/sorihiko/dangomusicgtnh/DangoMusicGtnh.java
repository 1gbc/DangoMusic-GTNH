package me.sorihiko.dangomusicgtnh;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = DangoMusicGtnh.ID,
    name = "DangoMusic GTNH Edition",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public final class DangoMusicGtnh {

    public static final String ID = "dangomusicgtnh";

    @SidedProxy(
        clientSide = "me.sorihiko.dangomusicgtnh.client.ClientProxy",
        serverSide = "me.sorihiko.dangomusicgtnh.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}

