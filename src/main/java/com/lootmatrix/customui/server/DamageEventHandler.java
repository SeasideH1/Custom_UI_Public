package com.lootmatrix.customui.server;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.DamageNumberPacket;
import com.lootmatrix.customui.network.KillMessagePacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side event handler for tracking player damage and sending packets to clients.
 * Listens for damage events and death events to track damage dealt by players.
 *
 * Damage calculation:
 * - Actual damage is always capped at the entity's remaining health
 * - This ensures accurate damage statistics without over-counting
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DamageEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DamageEventHandler.class);
    private static boolean taczTrackingRegistered;

    // Static initializer to confirm class loading
    static {
        // Handler registered via annotation
    }

    /**
     * Manual registration method - call this from Main if automatic registration fails
     */
    public static void register() {
        // The class is already registered via @Mod.EventBusSubscriber
        // This method exists just to force class loading
    }

    // Track the last damage source player for each entity (for death events)
    // Using WeakHashMap to avoid memory leaks
    private static final Map<LivingEntity, ServerPlayer> lastDamageSourcePlayer = new WeakHashMap<>();
    // Store the ACTUAL damage dealt (capped at entity's remaining health)
    private static final Map<LivingEntity, Float> lastActualDamageAmount = new WeakHashMap<>();
    // Track if last hit was a critical hit
    private static final Map<LivingEntity, Boolean> lastWasCritical = new WeakHashMap<>();
    // Track if last TACZ hit was a headshot (keyed by victim entity)
    private static final Map<LivingEntity, Boolean> lastTaczHeadshot = new WeakHashMap<>();
    private static final Map<LivingEntity, Long> lastTaczHeadshotTick = new WeakHashMap<>();
    private static final AtomicLong KILL_EVENT_COUNTER = new AtomicLong();

    // ==================== TACZ headshot tracking ====================

    /**
     * Register TACZ headshot tracking if TACZ is available.
     * Called from Main during mod setup.
     */
    public static void initTaczTracking() {
        if (taczTrackingRegistered) {
            return;
        }
        taczTrackingRegistered = true;
        // TaczKnockbackHandler self-registers via @Mod.EventBusSubscriber so it also
        // works in the trimmed client jar (singleplayer/LAN) where this class is absent.
        try {
            @SuppressWarnings("unchecked")
            Class<? extends net.minecraftforge.eventbus.api.Event> hurtPreEventClass =
                    (Class<? extends net.minecraftforge.eventbus.api.Event>)
                            Class.forName("com.tacz.guns.api.event.common.EntityHurtByGunEvent$Pre");
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    EventPriority.LOWEST,
                    false,
                    hurtPreEventClass,
                    TaczHeadshotTracker::onHurtByGun
            );
            LOGGER.debug("[CustomUI] Registered optional TaCZ headshot listener");
        } catch (ClassNotFoundException ignored) {
            LOGGER.debug("[CustomUI] TaCZ headshot event unavailable");
        } catch (LinkageError | RuntimeException e) {
            LOGGER.debug("[CustomUI] TaCZ optional headshot listener unavailable: {}", e.toString());
        }
    }

    /** Called by TaczHeadshotTracker to record headshot info. */
    public static void setTaczHeadshot(LivingEntity victim, boolean headshot) {
        lastTaczHeadshot.put(victim, headshot);
        lastTaczHeadshotTick.put(victim, victim.level().getGameTime());
    }

    /** Check if the last TACZ hit on this entity was a headshot. */
    private static boolean wasTaczHeadshot(LivingEntity victim) {
        Boolean hs = lastTaczHeadshot.get(victim);
        return hs != null && hs;
    }

    private static boolean hasTaczHeadshotRecord(LivingEntity victim) {
        return lastTaczHeadshot.containsKey(victim);
    }

    private static boolean hasRecentTaczHeadshotRecord(LivingEntity victim) {
        Long tick = lastTaczHeadshotTick.get(victim);
        if (tick == null) {
            return false;
        }
        long currentTick = victim.level().getGameTime();
        return currentTick >= tick && currentTick - tick <= 1L;
    }

    private static void clearTaczHeadshotRecord(LivingEntity victim) {
        lastTaczHeadshot.remove(victim);
        lastTaczHeadshotTick.remove(victim);
    }

    /**
     * Listen for damage events to track player damage.
     * Uses LOWEST priority to ensure we get the final damage amount after all modifications.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        LivingEntity target = event.getEntity();

        // Skip if damage is 0 or negative
        float damage = event.getAmount();
        if (damage <= 0) {
            return;
        }

        // Check if the damage source is from a player
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            // Don't track self-damage
            if (target == player) {
                return;
            }

            // Calculate actual damage (capped at entity's current health)
            // This ensures we don't over-count damage when killing an entity
            float entityHealth = target.getHealth();
            float actualDamage = Math.min(damage, entityHealth);

            // Check if this is a critical hit (falling attack)
            boolean isCritical = isCriticalHit(player);
            boolean isTaczDamage = hasRecentTaczHeadshotRecord(target)
                    && !KillCauseResolver.isExplicitOtherDamageSource(
                            event.getSource().getMsgId(),
                            describeEntity(event.getSource().getDirectEntity()));
            boolean isHeadshot = isTaczDamage ? wasTaczHeadshot(target) : isCritical;
            if (!isTaczDamage && hasTaczHeadshotRecord(target)) {
                clearTaczHeadshotRecord(target);
            }

            // Store the damage source info for potential death event
            lastDamageSourcePlayer.put(target, player);
            // Store ACTUAL damage, not raw damage
            lastActualDamageAmount.put(target, actualDamage);
            // Store critical hit status
            lastWasCritical.put(target, isCritical);

            // Check if this damage will kill the entity
            boolean willKill = actualDamage >= entityHealth;

            // Send damage packet to the player (non-kill damage)
            // Note: If entity dies, the death event will send the kill packet
            if (!willKill) {
                sendDamagePacket(player, target.getUUID(), actualDamage, false, isCritical, isHeadshot, 0L);
            }
        }
    }

    /**
     * Check if a player is in critical hit state.
     * Critical hit: falling, not on ground, not in water, not climbing, not riding
     */
    private static boolean isCriticalHit(ServerPlayer player) {
        return player.fallDistance > 0.0f
            && !player.onGround()
            && !player.isInWater()
            && !player.onClimbable()
            && !player.isPassenger()
            && !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
            && !player.isSprinting();
    }

    /**
     * Listen for death events to send kill confirmation.
     * Uses LOWEST priority to ensure we process after all other handlers.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity target = event.getEntity();

        // Check if a player was the last to damage this entity
        ServerPlayer player = lastDamageSourcePlayer.get(target);
        Float actualDamage = lastActualDamageAmount.get(target);
        Boolean wasCritical = lastWasCritical.get(target);

        Entity killer = event.getSource().getEntity();
        KillFeedback killFeedback = resolveKillFeedback(killer, target, event);
        long killEventId = 0L;

        if (player != null && actualDamage != null) {
            killEventId = nextKillEventId();
            // Send kill packet with the ACTUAL damage amount (already capped in damage event)
            sendDamagePacket(player, target.getUUID(), actualDamage, true, wasCritical != null && wasCritical,
                    killFeedback.isHeadshot, killEventId);

            // Clean up tracking maps
            lastDamageSourcePlayer.remove(target);
            lastActualDamageAmount.remove(target);
            lastWasCritical.remove(target);
        } else if (killer instanceof ServerPlayer sourcePlayer) {
            killEventId = nextKillEventId();
            // Fallback: if death source is a player but wasn't tracked in damage event
            // This can happen with instant kill effects - check for critical hit
            boolean isCritical = isCriticalHit(sourcePlayer);
            sendDamagePacket(sourcePlayer, target.getUUID(), 0, true, isCritical,
                    killFeedback.isHeadshot, killEventId);
        }

        // Send kill message to all players if either killer or victim is a player
        sendKillMessages(killer, target, killFeedback);
    }

    /**
     * Send kill message packets to all players.
     * Only sends if either killer or victim is a player.
     */
    private static void sendKillMessages(Entity killer, LivingEntity victim, KillFeedback killFeedback) {
        boolean killerIsPlayer = killer instanceof Player;
        boolean victimIsPlayer = victim instanceof Player;

        if (!killerIsPlayer && !victimIsPlayer) return;

        String killerName = "";
        if (killer instanceof Player killerPlayer && killer != victim) {
            killerName = killerPlayer.getDisplayName().getString();
        }

        String victimName = victim.getDisplayName().getString();
        UUID killerUuid = killer instanceof Player killerPlayerForUuid && killer != victim ? killerPlayerForUuid.getUUID() : null;
        UUID victimUuid = victim instanceof Player ? victim.getUUID() : null;

        IconResult iconResult = killFeedback.iconResult;

        LOGGER.debug("[KillMessage] Resolved icon: path={}, type={}", iconResult.path, iconResult.type);

        if (victim.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            for (ServerPlayer onlinePlayer : serverLevel.getServer().getPlayerList().getPlayers()) {
                boolean killerIsTeammate = killer instanceof Player kp && isSameTeam(onlinePlayer, kp);
                boolean victimIsTeammate = victim instanceof Player vp && isSameTeam(onlinePlayer, vp);
                // For AllyPlayer mode: check if the killer/victim is this specific player (use UUID comparison)
                boolean killerIsLocalPlayer = killer != null && killer.getUUID().equals(onlinePlayer.getUUID());
                boolean victimIsLocalPlayer = victim.getUUID().equals(onlinePlayer.getUUID());

                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> onlinePlayer),
                        new KillMessagePacket(killerName, victimName, iconResult.path,
                                killerIsTeammate, victimIsTeammate, killFeedback.isHeadshot, iconResult.type,
                                killerIsLocalPlayer, victimIsLocalPlayer, killerUuid, victimUuid));
            }
        }
    }

    /** Result of icon resolution: path + type. */
    private static class IconResult {
        final String path;
        final byte type;
        IconResult(String path, byte type) { this.path = path; this.type = type; }
    }

    private static class KillFeedback {
        final IconResult iconResult;
        final boolean isHeadshot;

        KillFeedback(IconResult iconResult, boolean isHeadshot) {
            this.iconResult = iconResult;
            this.isHeadshot = isHeadshot;
        }
    }

    private static KillFeedback resolveKillFeedback(Entity killer, LivingEntity victim, LivingDeathEvent event) {
        IconResult iconResult = resolveIcon(killer, victim, event);
        boolean isHeadshot = iconResult.type == KillMessagePacket.ICON_TACZ
                ? wasTaczHeadshot(victim)
                : killer instanceof ServerPlayer sp && isCriticalHit(sp);
        clearTaczHeadshotRecord(victim);
        return new KillFeedback(iconResult, isHeadshot);
    }

    /**
     * Resolve icon path and type from the actual damage source first.
     */
    private static IconResult resolveIcon(Entity killer, LivingEntity victim, LivingDeathEvent event) {
        var source = event.getSource();
        String msgId = source.getMsgId();
        String directEntityId = describeEntity(source.getDirectEntity());

        String heldTaczIconPath = null;
        String heldSbwIconPath = null;
        if (killer instanceof Player killerPlayer) {
            net.minecraft.world.item.ItemStack held = killerPlayer.getMainHandItem();
            heldTaczIconPath = getHeldTaczIconPath(held);
            heldSbwIconPath = getHeldSbwIconPath(held);
        }

        boolean recentTaczHit = hasRecentTaczHeadshotRecord(victim);
        KillCauseResolver.Result result = KillCauseResolver.resolve(
                msgId,
                directEntityId,
                heldTaczIconPath,
                heldSbwIconPath,
                recentTaczHit
        );
        return new IconResult(result.path(), result.type());
    }

    private static String getHeldTaczIconPath(net.minecraft.world.item.ItemStack held) {
        if (held.isEmpty()) {
            return null;
        }
        try {
            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            if (iGunClass.isInstance(held.getItem())) {
                Object iGun = held.getItem();
                net.minecraft.resources.ResourceLocation gunId =
                        (net.minecraft.resources.ResourceLocation)
                                iGunClass.getMethod("getGunId", net.minecraft.world.item.ItemStack.class)
                                        .invoke(iGun, held);
                if (gunId != null) {
                    return gunId.getNamespace() + ":textures/gun/hud/" + gunId.getPath() + ".png";
                }
            }
        } catch (Throwable e) {
            LOGGER.debug("[DamageEventHandler] TACZ gun detection failed: {}", e.getMessage());
        }
        return null;
    }

    private static String getHeldSbwIconPath(net.minecraft.world.item.ItemStack held) {
        if (held.isEmpty()) {
            return null;
        }
        try {
            Class<?> gunItemClass = Class.forName("com.atsuishio.superbwarfare.item.gun.GunItem");
            if (gunItemClass.isInstance(held.getItem())) {
                net.minecraft.resources.ResourceLocation icon =
                        (net.minecraft.resources.ResourceLocation)
                                gunItemClass.getMethod("getGunIcon", net.minecraft.world.item.ItemStack.class)
                                        .invoke(held.getItem(), held);
                if (icon != null) {
                    return icon.toString();
                }
                net.minecraft.resources.ResourceLocation itemId =
                        net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(held.getItem());
                if (itemId != null && "superbwarfare".equals(itemId.getNamespace())) {
                    return "superbwarfare:textures/gun_icon/" + itemId.getPath() + "_icon.png";
                }
            }
        } catch (Throwable e) {
            LOGGER.debug("[DamageEventHandler] SBW gun detection failed: {}", e.getMessage());
        }
        return null;
    }

    private static String describeEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        net.minecraft.resources.ResourceLocation entityId =
                net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        String className = entity.getClass().getName();
        return entityId == null ? className : entityId + " " + className;
    }

    /**
     * Check if two players are on the same team.
     */
    private static boolean isSameTeam(Player player1, Player player2) {
        if (player1 == null || player2 == null) return false;
        if (player1 == player2) return true;

        var team1 = player1.getTeam();
        var team2 = player2.getTeam();

        // Both no team = same team (no-team group)
        if (team1 == null && team2 == null) return true;

        // One has team, other doesn't = different
        if (team1 == null || team2 == null) return false;

        return team1.getName().equals(team2.getName());
    }

    /**
     * Send a damage packet to a specific player.
     *
     * @param player     The player to send the packet to
     * @param entityUUID UUID of the damaged/killed entity
     * @param damage     Amount of damage dealt (actual damage, capped at entity health)
     * @param isKill     Whether this damage killed the entity
     * @param isCritical Whether this was a critical hit
     */
    private static void sendDamagePacket(ServerPlayer player, UUID entityUUID, float damage, boolean isKill,
                                         boolean isCritical, boolean isHeadshot, long killEventId) {
        DamageNumberPacket packet = new DamageNumberPacket(entityUUID, damage, isKill, isCritical,
                isHeadshot, killEventId);
        ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static long nextKillEventId() {
        return KILL_EVENT_COUNTER.incrementAndGet();
    }
}
