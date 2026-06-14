package com.lootmatrix.customui.server;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.DamageIndicatorPacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-side event handler for player damage indicators.
 * Sends damage indicator packets to players when they take damage.
 *
 * Uses multiple event types for better compatibility with Mohist and other hybrid servers:
 * - LivingDamageEvent: Final damage after all modifiers (preferred)
 * - LivingHurtEvent: Damage before armor/effects reduction (fallback)
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerDamageIndicatorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerDamageIndicatorHandler.class);

    // Track which players have already received an indicator for this damage tick
    // This prevents duplicate indicators when multiple events fire for the same damage
    private static final Map<UUID, Long> recentIndicators = new WeakHashMap<>();
    private static final long INDICATOR_COOLDOWN_MS = 50; // 50ms cooldown between indicators for same player

    // Static initializer to confirm class loading
    static {
        // Handler registered via annotation
    }

    /**
     * Manual registration method - call this from Main if automatic registration fails
     */
    public static void register() {
        // LOGGER.info("[PlayerDamageIndicatorHandler] Manual registration called");
        // The class is already registered via @Mod.EventBusSubscriber
        // This method exists just to force class loading
    }

    /**
     * Listen for LivingDamageEvent (final damage after all modifications).
     * This is the preferred event as it contains the actual damage dealt.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        float damage = event.getAmount();
        if (damage <= 0) {
            return;
        }

//        LOGGER.debug("[PlayerDamageIndicatorHandler] LivingDamageEvent: Player {} took {} damage from source: {}",
//            player.getName().getString(), damage, event.getSource().type().msgId());

        sendDamageIndicator(player, event.getSource(), damage, "LivingDamageEvent");
    }

    /**
     * Listen for LivingHurtEvent as fallback (damage before armor reduction).
     * This event fires earlier in the damage pipeline and may work better on Mohist.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        float damage = event.getAmount();
        if (damage <= 0) {
            return;
        }

//        LOGGER.debug("[PlayerDamageIndicatorHandler] LivingHurtEvent: Player {} hurt for {} damage from source: {}",
//            player.getName().getString(), damage, event.getSource().type().msgId());

        // Only send if LivingDamageEvent hasn't already sent an indicator recently
        sendDamageIndicator(player, event.getSource(), damage, "LivingHurtEvent");
    }

    /**
     * Send damage indicator to player, with deduplication to prevent multiple indicators
     * for the same damage instance.
     */
    private static void sendDamageIndicator(ServerPlayer player, DamageSource source, float damage, String eventType) {
        UUID playerId = player.getUUID();
        long currentTime = System.currentTimeMillis();

        // Check if we recently sent an indicator to this player
        Long lastIndicatorTime = recentIndicators.get(playerId);
        if (lastIndicatorTime != null && (currentTime - lastIndicatorTime) < INDICATOR_COOLDOWN_MS) {
//            LOGGER.debug("[PlayerDamageIndicatorHandler] Skipping {} indicator for {} - recent indicator exists",
//                eventType, player.getName().getString());
            return;
        }

        // Record this indicator
        recentIndicators.put(playerId, currentTime);

        Vec3 sourcePos = getDamageSourcePosition(source, player);
        // LOGGER.debug("[PlayerDamageIndicatorHandler] {} - Damage source position: {}", eventType, sourcePos);

        try {
            DamageIndicatorPacket packet = new DamageIndicatorPacket(sourcePos, damage);
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
            // LOGGER.debug("[PlayerDamageIndicatorHandler] {} - Sent DamageIndicatorPacket to {} successfully",
            //     eventType, player.getName().getString());
        } catch (Exception e) {
//            LOGGER.error("[PlayerDamageIndicatorHandler] {} - Failed to send DamageIndicatorPacket to {}: {}",
//                eventType, player.getName().getString(), e.getMessage(), e);
        }
    }

    /**
     * Determines the position of the damage source.
     * Prioritizes the original attacker (e.g., player who shot an arrow) over the direct entity (e.g., arrow).
     * @return The position of the damage source, or null if unknown/environmental
     */
    private static Vec3 getDamageSourcePosition(DamageSource source, Player player) {
        // First priority: Check for indirect/causing attacker (e.g., player who shot an arrow)
        // This is the "true" source of damage
        Entity causingEntity = source.getEntity();
        if (causingEntity != null && causingEntity != player) {
            return causingEntity.position();
        }

        // Second priority: Check for direct entity attacker (e.g., zombie, or arrow if no causing entity)
        Entity directEntity = source.getDirectEntity();
        if (directEntity != null && directEntity != player) {
            return directEntity.position();
        }

        // Third priority: Check for source location from damage source (e.g., explosion center)
        Vec3 sourceLocation = source.getSourcePosition();
        if (sourceLocation != null) {
            return sourceLocation;
        }

        // Environmental damage or unknown source - return null (no indicator shown)
        // Examples: fall damage, suffocation, drowning, fire, void, etc.
        return null;
    }
}

