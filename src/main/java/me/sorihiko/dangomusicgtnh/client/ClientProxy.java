package me.sorihiko.dangomusicgtnh.client;

import java.io.File;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

import me.sorihiko.dangomusicgtnh.CommonProxy;
import me.sorihiko.dangomusicgtnh.DangoMusicGtnh;

public final class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        File directory = new File(event.getModConfigurationDirectory(), DangoMusicGtnh.ID);
        ClientSettings settings = new ClientSettings(directory);
        ClientMusic.start(directory, settings.initialVolume);
    }
}

