package com.lootmatrix.customui.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Death marker entity - a visual marker that displays at a location.
 * Shows a gray semi-transparent "X" with ripple effects and distance text.
 * Only visible to specified players.
 */
public class DeathMarkerEntity extends Entity {

    // Synched data for client rendering
    private static final EntityDataAccessor<Integer> DATA_DURATION = SynchedEntityData.defineId(DeathMarkerEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_AGE = SynchedEntityData.defineId(DeathMarkerEntity.class, EntityDataSerializers.INT);

    /** Set of player UUIDs who can see this marker */
    private final Set<UUID> viewers = new HashSet<>();

    /** Duration in ticks */
    private int duration = 100;

    /** Current age in ticks */
    private int age = 0;

    public DeathMarkerEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public DeathMarkerEntity(Level level, double x, double y, double z, int duration) {
        this(ModEntities.DEATH_MARKER.get(), level);
        this.setPos(x, y, z);
        this.duration = duration;
        this.entityData.set(DATA_DURATION, duration);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_DURATION, 100);
        this.entityData.define(DATA_AGE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        age++;
        this.entityData.set(DATA_AGE, age);

        // Remove when duration expires
        if (age >= duration) {
            this.discard();
        }
    }

    /**
     * Add a player who can see this marker.
     */
    public void addViewer(UUID playerUuid) {
        viewers.add(playerUuid);
    }

    /**
     * Check if a player can see this marker.
     */
    public boolean canPlayerSee(UUID playerUuid) {
        // If no viewers specified, everyone can see
        if (viewers.isEmpty()) {
            return true;
        }
        return viewers.contains(playerUuid);
    }

    /**
     * Get the set of viewers.
     */
    public Set<UUID> getViewers() {
        return viewers;
    }

    /**
     * Get duration in ticks.
     */
    public int getDuration() {
        return this.entityData.get(DATA_DURATION);
    }

    /**
     * Get current age in ticks.
     */
    public int getAge() {
        return this.entityData.get(DATA_AGE);
    }

    /**
     * Get age as a fraction of duration (0.0 to 1.0).
     */
    public float getAgeProgress() {
        int dur = getDuration();
        if (dur <= 0) return 1f;
        return (float) getAge() / dur;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.duration = tag.getInt("Duration");
        this.age = tag.getInt("Age");
        this.entityData.set(DATA_DURATION, duration);
        this.entityData.set(DATA_AGE, age);

        // Read viewers
        viewers.clear();
        if (tag.contains("Viewers")) {
            CompoundTag viewersTag = tag.getCompound("Viewers");
            for (String key : viewersTag.getAllKeys()) {
                try {
                    viewers.add(UUID.fromString(key));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Duration", duration);
        tag.putInt("Age", age);

        // Save viewers
        CompoundTag viewersTag = new CompoundTag();
        for (UUID uuid : viewers) {
            viewersTag.putBoolean(uuid.toString(), true);
        }
        tag.put("Viewers", viewersTag);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        // Render at long distances
        return distance < 40000; // ~200 blocks
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }
}


