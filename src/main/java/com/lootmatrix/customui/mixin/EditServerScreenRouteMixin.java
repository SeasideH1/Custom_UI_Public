package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.route.ServerRouteScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "线路" button to the vanilla Edit Server screen that opens the
 * multi-route manager (alternate addresses + auto best-route selection).
 */
@Mixin(EditServerScreen.class)
public abstract class EditServerScreenRouteMixin extends Screen {

    protected EditServerScreenRouteMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void customui$addRouteButton(CallbackInfo ci) {
        addRenderableWidget(Button.builder(Component.literal("线路…"), button -> {
            if (minecraft != null) {
                ServerData serverData = ((EditServerScreenAccessor) this).customui$getServerData();
                minecraft.setScreen(new ServerRouteScreen(
                        (Screen) (Object) this, serverData.name, serverData.ip));
            }
        }).bounds(width - 58, 6, 52, 20).build());
    }
}
