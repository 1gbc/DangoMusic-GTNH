package me.sorihiko.dangomusicgtnh.client;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

final class ClientSettings {

    final boolean mainMenuButton;

    ClientSettings(File directory) {
        Configuration config = new Configuration(new File(directory, "settings.cfg"));
        config.load();
        mainMenuButton = config.getBoolean("mainMenuButton", Configuration.CATEGORY_CLIENT, true,
            "Show the DangoMusic button on the main menu.");
        if (config.hasChanged()) {
            config.save();
        }
    }
}

