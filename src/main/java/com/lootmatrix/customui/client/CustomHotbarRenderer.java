package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Custom hotbar renderer that draws the hotbar on the right side of the screen.
 * This cancels the vanilla hotbar rendering and renders our own in the new position.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class CustomHotbarRenderer {

    @SuppressWarnings("deprecation")
    private static final ResourceLocation WIDGETS_LOCATION = new ResourceLocation("minecraft", "textures/gui/widgets.png");

    // Hotbar position offset from right edge
    public static int HOTBAR_RIGHT_PADDING = 10;

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderHotbarPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() == VanillaGuiOverlay.HOTBAR.type()) {
            if (BackgroundGuard.shouldSkip()) return;
            Minecraft mc = Minecraft.getInstance();

            // Don't render custom hotbar in spectator mode - let vanilla handle it (which should be hidden)
            if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
                event.setCanceled(true);
                return;
            }

            // In Creative mode, let vanilla render centered hotbar
            if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.CREATIVE) {
                // Don't cancel - let vanilla render
                return;
            }

            // Cancel vanilla hotbar rendering for other modes
            event.setCanceled(true);

            // Render our custom positioned hotbar (right-aligned)
            renderCustomHotbar(event.getGuiGraphics(), event.getPartialTick());
        }
    }

    private static void renderCustomHotbar(GuiGraphics guiGraphics, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Hotbar is 182 pixels wide, 22 pixels tall
        // Position at the right side
        int hotbarX = screenWidth - 182 - HOTBAR_RIGHT_PADDING;
        int hotbarY = screenHeight - 22;

        // Get selected slot
        int selectedSlot = player.getInventory().selected;

        // Render hotbar background
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        guiGraphics.blit(WIDGETS_LOCATION, hotbarX, hotbarY, 0, 0, 182, 22);

        // Render selection highlight
        guiGraphics.blit(WIDGETS_LOCATION, hotbarX - 1 + selectedSlot * 20, hotbarY - 1, 0, 22, 24, 22);

        // Render items in hotbar
        int itemX = hotbarX + 3;
        int itemY = hotbarY + 3;

        for (int i = 0; i < 9; i++) {
            int x = itemX + i * 20;
            ItemStack itemStack = player.getInventory().items.get(i);
            if (!itemStack.isEmpty()) {
                guiGraphics.renderItem(player, itemStack, x, itemY, i + 1);
            }
        }

        for (int i = 0; i < 9; i++) {
            int x = itemX + i * 20;
            ItemStack itemStack = player.getInventory().items.get(i);
            if (!itemStack.isEmpty()) {
                guiGraphics.renderItemDecorations(mc.font, itemStack, x, itemY);
            }
        }

        // Render offhand item
        ItemStack offhandStack = player.getOffhandItem();
        if (!offhandStack.isEmpty()) {
            int offhandX = hotbarX - 29;
            int offhandY = hotbarY;

            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            guiGraphics.blit(WIDGETS_LOCATION, offhandX, offhandY, 24, 22, 29, 24);
            guiGraphics.renderItem(player, offhandStack, offhandX + 6, offhandY + 4, 10);
            guiGraphics.renderItemDecorations(mc.font, offhandStack, offhandX + 6, offhandY + 4);
        }

        BufferBuilder cooldownBuf = null;
        Matrix4f matrix = null;
        for (int i = 0; i < 9; i++) {
            int x = itemX + i * 20;
            ItemStack itemStack = player.getInventory().items.get(i);
            if (itemStack.isEmpty()) {
                continue;
            }

            float cooldown = player.getCooldowns().getCooldownPercent(itemStack.getItem(), mc.getFrameTime());
            if (cooldown <= 0.0F) {
                continue;
            }
            if (cooldownBuf == null) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                cooldownBuf = Tesselator.getInstance().getBuilder();
                cooldownBuf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                matrix = guiGraphics.pose().last().pose();
            }
            int cooldownHeight = (int) (16.0F * cooldown);
            addColoredQuad(cooldownBuf, matrix, x, itemY + 16 - cooldownHeight, x + 16, itemY + 16, 0x7FFFFFFF);
        }

        if (!offhandStack.isEmpty()) {
            float cooldown = player.getCooldowns().getCooldownPercent(offhandStack.getItem(), mc.getFrameTime());
            if (cooldown > 0.0F) {
                if (cooldownBuf == null) {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.setShader(GameRenderer::getPositionColorShader);
                    cooldownBuf = Tesselator.getInstance().getBuilder();
                    cooldownBuf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                    matrix = guiGraphics.pose().last().pose();
                }
                int cooldownHeight = (int) (16.0F * cooldown);
                int offhandItemX = hotbarX - 29 + 6;
                int offhandItemY = hotbarY + 4;
                addColoredQuad(cooldownBuf, matrix, offhandItemX, offhandItemY + 16 - cooldownHeight, offhandItemX + 16, offhandItemY + 16, 0x7FFFFFFF);
            }
        }

        if (cooldownBuf != null) {
            BufferUploader.drawWithShader(cooldownBuf.end());
            RenderSystem.disableBlend();
        }
    }

    private static void addColoredQuad(BufferBuilder bufferBuilder, Matrix4f matrix,
                                       float left, float top, float right, float bottom, int color) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        bufferBuilder.vertex(matrix, left, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, top, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, left, top, 0).color(r, g, b, a).endVertex();
    }
}



