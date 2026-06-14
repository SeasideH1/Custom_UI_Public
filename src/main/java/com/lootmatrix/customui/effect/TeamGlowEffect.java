package com.lootmatrix.customui.effect;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.client.extensions.common.IClientMobEffectExtensions;

import java.util.function.Consumer;

/**
 * Team Glow effect - gives green glow outline to same-team players.
 *
 * Behavior:
 *   - Only affects players
 *   - Only visible to players on the same team (or both teamless)
 *   - If the entity has vanilla Glowing effect, vanilla takes priority
 *   - Uses custom rendering independent of vanilla glow system
 *
 * This effect is applied via potion/command and uses custom outline rendering.
 */
public class TeamGlowEffect extends MobEffect {

    // Vanilla glowing effect icon texture
    private static final ResourceLocation GLOWING_ICON = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/mob_effect/glowing.png");

    public TeamGlowEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x55FF55); // Green color
    }

    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {
        // No per-tick action needed - glow is handled via rendering events
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return false; // No periodic tick needed
    }

    /**
     * Initialize client-side properties, including the effect icon.
     * Uses the vanilla glowing effect icon for visual consistency.
     */
    @Override
    public void initializeClient(Consumer<IClientMobEffectExtensions> consumer) {
        consumer.accept(new IClientMobEffectExtensions() {
            @Override
            public boolean renderInventoryIcon(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen,
                                                 GuiGraphics guiGraphics, int x, int y, int blitOffset) {
                // Use vanilla glowing icon
                guiGraphics.blit(GLOWING_ICON, x, y, 0, 0, 18, 18, 18, 18);
                return true;
            }

            @Override
            public boolean renderInventoryText(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen,
                                                GuiGraphics guiGraphics, int x, int y, int blitOffset) {
                // Use default text rendering
                return false;
            }
        });
    }

    /**
     * Check if a viewer should see the target glowing from this effect.
     * Returns true if:
     *   - Both are players
     *   - Target has TEAM_GLOW effect
     *   - Target does NOT have vanilla GLOWING effect
     *   - Both are on the same team (or both have no team)
     *   - Includes self (viewer == target) for third-person view
     */
    public static boolean shouldShowTeamGlow(Player viewer, Player target) {
        if (viewer == null || target == null) return false;

        // Check if our effect is registered (may not be on some hybrid servers)
        var teamGlowEffect = com.lootmatrix.customui.registry.ModEffects.TEAM_GLOW;
        if (!teamGlowEffect.isPresent()) {
            return false;
        }

        // Check if target has our effect
        if (!target.hasEffect(teamGlowEffect.get())) {
            return false;
        }

        // If target has vanilla glowing, let vanilla handle it
        if (target.hasEffect(MobEffects.GLOWING)) {
            return false;
        }

        // Self always sees own glow (for third-person)
        if (viewer == target) {
            return true;
        }

        // Check same team
        return isSameTeam(viewer, target);
    }

    /**
     * Check if a viewer and target should be treated as same team or self.
     */
    public static boolean isSameTeamOrSelf(Player viewer, Player target) {
        if (viewer == null || target == null) return false;
        if (viewer == target) return true;
        return isSameTeam(viewer, target);
    }

    /**
     * Check if two players are on the same team.
     * Players with no team are considered same team.
     */
    private static boolean isSameTeam(Player a, Player b) {
        if (a == b) return true;

        PlayerTeam teamA = a.getTeam() instanceof PlayerTeam pt ? pt : null;
        PlayerTeam teamB = b.getTeam() instanceof PlayerTeam pt ? pt : null;

        // Both no team = same team
        if (teamA == null && teamB == null) return true;
        // One has team, other doesn't = different
        if (teamA == null || teamB == null) return false;
        // Compare team names
        return teamA.getName().equals(teamB.getName());
    }
}
