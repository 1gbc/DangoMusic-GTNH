package me.sorihiko.dangomusicgtnh.client;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

final class ClientSettings {

    final boolean mainMenuButton;
    final float initialVolume;
    private final Configuration config;

    ClientSettings(File directory) {
        config = new Configuration(new File(directory, "settings.cfg"));
        config.load();
        mainMenuButton = config.getBoolean("mainMenuButton", "client", true,
            "Show the DangoMusic button on the main menu.");
        initialVolume = config.getFloat("volume", "client", 0.2f, 0.0f, 1.0f, "DangoMusic playback volume.");
        if (config.hasChanged()) {
            config.save();
        }
    }

    void saveVolume(float volume) {
        if (Float.compare(volume, initialVolume) != 0) {
            config.get("client", "volume", 0.2).set((double) volume);
            config.save();
        }
    }
}

