package com.lootmatrix.customui.client.glass;

import com.lootmatrix.customui.client.hud.GuiTemplateScreen;
import com.lootmatrix.customui.client.title.TitleSceneManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LanguageSelectScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Decides where the frosted-glass widget skin applies.
 *
 * Out of world: the whole menu family (title scene look). In world: a
 * whitelist of menus that sit directly over gameplay (pause/options family
 * and GUI template screens) — never arbitrary mod screens. The HUD editor is
 * deliberately excluded: its dense tool buttons stay flat for readability.
 */
@OnlyIn(Dist.CLIENT)
public final class GlassTheme {

    private GlassTheme() {}

    public static boolean buttonsActive() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (screen == null || !TitleSceneManager.glassButtonsConfigured()) {
            return false;
        }
        if (minecraft.level == null) {
            return true;
        }
        return screen instanceof PauseScreen
                || screen instanceof OptionsScreen
                || screen instanceof OptionsSubScreen
                || screen instanceof LanguageSelectScreen
                || screen instanceof PackSelectionScreen
                || screen instanceof ShareToLanScreen
                || screen instanceof GuiTemplateScreen;
    }
}
