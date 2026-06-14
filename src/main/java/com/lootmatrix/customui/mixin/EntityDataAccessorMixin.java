package com.lootmatrix.customui.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.data.EntityDataAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mixin to allow /data command to modify player data on Mohist and other hybrid servers.
 * Blocked tags are stripped and feedback is sent to the target player.
 */
@Mixin(EntityDataAccessor.class)
public abstract class EntityDataAccessorMixin {

    @Unique
    private static Field customui$entityField;

    @Unique
    private Entity customui$getEntity() {
        try {
            if (customui$entityField == null) {
                // Try to find the entity field by type
                for (Field field : EntityDataAccessor.class.getDeclaredFields()) {
                    if (Entity.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        customui$entityField = field;
                        break;
                    }
                }
            }
            if (customui$entityField != null) {
                return (Entity) customui$entityField.get(this);
            }
        } catch (Exception e) {
            // Silently fail
        }
        return null;
    }

    @Unique
    private static final Set<String> customui$BLOCKED_TAGS = Set.of(
            "OnGround", "UUID",
            "CustomName", "CustomNameVisible",
            "Passengers", "RootVehicle",
            "DeathTime", "sleeping_pos",
            "active_effects", "ActiveEffects", "Brain",
            "locator_bar_icon",
            "XpP", "XpLevel", "XpTotal", "XpSeed",
            "ShoulderEntityLeft", "ShoulderEntityRight",
            "LastDeathLocation",
            "playerGameType", "previousPlayerGameType",
            "recipeBook",
            "Dimension",
            "ender_pearls"
    );

    @Inject(method = {"setData(Lnet/minecraft/nbt/CompoundTag;)V", "m_7603_(Lnet/minecraft/nbt/CompoundTag;)V"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void customui$allowPlayerDataModification(CompoundTag tag, CallbackInfo ci) {
        Entity entity = customui$getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUUID();

        // Lazy diff: only serialize if any blocked key exists in input
        CompoundTag currentNbt = null;
        List<String> blockedKeys = null;

        for (String key : tag.getAllKeys()) {
            if (!customui$BLOCKED_TAGS.contains(key)) {
                continue;
            }
            if (currentNbt == null) {
                currentNbt = new CompoundTag();
                player.saveWithoutId(currentNbt);
                blockedKeys = new ArrayList<>();
            }
            Tag incoming = tag.get(key);
            Tag existing = currentNbt.get(key);
            if (incoming != null && !incoming.equals(existing)) {
                blockedKeys.add(key);
            }
        }

        // Send feedback to target player only if blocked tags were attempted
        if (blockedKeys != null && !blockedKeys.isEmpty() && player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal(
                    "§c[CustomUI] 以下标签不允许被修改: " + String.join(", ", blockedKeys)));
        }

        // Build filtered tag
        CompoundTag filteredTag = new CompoundTag();
        for (String key : tag.getAllKeys()) {
            if (customui$BLOCKED_TAGS.contains(key)) {
                continue;
            }
            Tag value = tag.get(key);
            if (value != null) {
                filteredTag.put(key, value);
            }
        }

        // Preserve UUID
        filteredTag.putUUID("UUID", uuid);

        // Load only if there's data beyond UUID
        if (filteredTag.size() > 1) {
            player.load(filteredTag);
        }

        ci.cancel();
    }
}
