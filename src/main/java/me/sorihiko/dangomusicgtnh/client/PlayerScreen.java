package me.sorihiko.dangomusicgtnh.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

final class PlayerScreen extends GuiScreen {

    private final GuiScreen parent;

    PlayerScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.add(new GuiButton(0, width / 2 - 60, height / 2 + 23, 120, 20, I18n.format("gui.done")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("dangomusicgtnh.title"), width / 2, height / 2 - 20, 0xFFFFFF);
        drawCenteredString(fontRendererObj, I18n.format("dangomusicgtnh.phase3"), width / 2, height / 2, 0xCCCCCC);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

