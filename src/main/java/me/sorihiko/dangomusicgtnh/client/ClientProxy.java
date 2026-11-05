package me.sorihiko.dangomusicgtnh.client;

import java.io.File;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import me.sorihiko.dangomusicgtnh.CommonProxy;
import me.sorihiko.dangomusicgtnh.DangoMusicGtnh;

public final class ClientProxy extends CommonProxy {

    private final KeyBinding openPlayer =
        new KeyBinding("key.dangomusicgtnh.open", Keyboard.KEY_M, "key.categories.dangomusicgtnh");
    private ClientSettings settings;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        settings = new ClientSettings(new File(event.getModConfigurationDirectory(), DangoMusicGtnh.ID));
        ClientRegistry.registerKeyBinding(openPlayer);
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void keyInput(InputEvent.KeyInputEvent event) {
        Minecraft client = Minecraft.getMinecraft();
        if (openPlayer.isPressed() && client.currentScreen == null && client.theWorld != null) {
            client.displayGuiScreen(new PlayerScreen(null));
        }
    }

    @SubscribeEvent
    public void menuInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (settings.mainMenuButton && isMainMenu(event.gui)) {
            event.buttonList.add(new MenuButton(event.gui.height));
        }
    }

    @SubscribeEvent
    public void menuAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.button instanceof MenuButton && isMainMenu(event.gui)) {
            event.setCanceled(true);
            Minecraft.getMinecraft().displayGuiScreen(new PlayerScreen(event.gui));
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

