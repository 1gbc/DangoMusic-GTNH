package me.sorihiko.dangomusicgtnh.client;

import java.io.File;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import me.sorihiko.dangomusicgtnh.CommonProxy;
import me.sorihiko.dangomusicgtnh.DangoMusicGtnh;

public final class ClientProxy extends CommonProxy {

    private ClientSettings settings;
    private GuiScreenEvent.InitGuiEvent.Pre menuBeforeInit;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        File directory = new File(event.getModConfigurationDirectory(), DangoMusicGtnh.ID);
        settings = new ClientSettings(directory);
        ClientMusic.start(directory, settings.initialVolume);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void menuInitStarted(GuiScreenEvent.InitGuiEvent.Pre event) {
        menuBeforeInit = settings.mainMenuButton && isMainMenu(event.gui) ? event : null;
    }

    @SubscribeEvent
    public void menuInitFinished(GuiScreenEvent.InitGuiEvent.Post event) {
        GuiScreenEvent.InitGuiEvent.Pre menu = menuBeforeInit;
        menuBeforeInit = null;
        if (menu != null && menu.gui == Minecraft.getMinecraft().currentScreen) {
            // Custom Main Menu replaces the Post event's screen and button list.
            menu.buttonList.add(new MenuButton(menu.gui.height));
        }
    }

    @SubscribeEvent
    public void menuAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.button instanceof MenuButton && isMainMenu(event.gui)) {
            event.setCanceled(true);
            ClientMusic.open(event.gui);
        }
    }

    private static boolean isMainMenu(GuiScreen screen) {
        return screen instanceof GuiMainMenu
            || screen.getClass().getName().equals("lumien.custommainmenu.gui.GuiCustom");
    }

    private static final class MenuButton extends GuiButton {

        private MenuButton(int screenHeight) {
            super(-738, 5, screenHeight - 25, 100, 20, I18n.format("dangomusicgtnh.menu"));
        }
    }
}

