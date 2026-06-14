package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Debug key handler to test damage indicator rendering.
 * Press K to trigger a test damage indicator.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class DamageIndicatorDebugKey {


    // Simple key tracking without KeyMapping
    private static boolean wasKeyPressed = false;

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }

        // Check if K key is pressed (key code 75)
        if (event.getKey() == GLFW.GLFW_KEY_K && event.getAction() == GLFW.GLFW_PRESS) {
            if (!wasKeyPressed) {
                wasKeyPressed = true;

                DamageIndicatorOverlay overlay = DamageIndicatorOverlay.getInstance();
                Vec3 playerPos = mc.player.position();
                Vec3 lookDir = mc.player.getLookAngle();

                // Test: Add directional indicator from behind player
                Vec3 behindPlayer = playerPos.add(
                    -lookDir.x * 5,
                    0,
                    -lookDir.z * 5
                );
                overlay.addDamageIndicator(behindPlayer, 8.0f);
            }
        } else if (event.getKey() == GLFW.GLFW_KEY_K && event.getAction() == GLFW.GLFW_RELEASE) {
            wasKeyPressed = false;
        }
    }
}

