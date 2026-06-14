package com.lootmatrix.customui.hud;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves entity selector bindings to display strings (server + client preview).
 */
public final class HudEntityValueResolver {

    private static final ConcurrentHashMap<String, EntitySelector> SELECTOR_CACHE = new ConcurrentHashMap<>();

    private HudEntityValueResolver() {}

    @Nullable
    private static EntitySelector parseSelector(String selector) {
        EntitySelector cached = SELECTOR_CACHE.get(selector);
        if (cached != null) return cached;
        try {
            EntitySelector parsed = new EntitySelectorParser(new StringReader(selector), true).parse();
            SELECTOR_CACHE.put(selector, parsed);
            return parsed;
        } catch (CommandSyntaxException ignored) {
            return null;
        }
    }

    /** Drop cached selectors after template reload (bindings may change). */
    public static void clearSelectorCache() {
        SELECTOR_CACHE.clear();
    }

    @Nullable
    public static Entity resolveFirst(String selector, CommandSourceStack source) {
        if (selector.isEmpty() || source == null) return null;
        EntitySelector entitySelector = parseSelector(selector);
        if (entitySelector == null) return null;
        try {
            List<? extends Entity> found = entitySelector.findEntities(source);
            return found.isEmpty() ? null : found.get(0);
        } catch (CommandSyntaxException ignored) {
            return null;
        }
    }

    public static String readValue(HudEntityBinding binding, CommandSourceStack source) {
        Entity entity = resolveFirst(binding.selector, source);
        if (entity == null) return "";
        return readField(entity, binding, source);
    }

    /**
     * Numeric readout for PROGRESS bindings. Returns {@link Float#NaN} when the
     * field is non-numeric or the entity is missing.
     */
    public static float readNumeric(HudEntityBinding binding, CommandSourceStack source) {
        Entity entity = resolveFirst(binding.selector, source);
        if (entity == null) return Float.NaN;
        if (binding.field == HudEntityBinding.Field.NBT) {
            return parseNumericTag(readNbtTag(entity, binding.nbtPath));
        }
        Float value = readNumericField(entity, binding, source);
        return value != null ? value : Float.NaN;
    }

    public static String readField(Entity entity, HudEntityBinding binding, CommandSourceStack source) {
        if (binding.field == HudEntityBinding.Field.NBT) {
            return tagToString(readNbtTag(entity, binding.nbtPath));
        }
        Float numeric = readNumericField(entity, binding, source);
        if (numeric != null) {
            if (binding.field == HudEntityBinding.Field.BABY
                    || binding.field == HudEntityBinding.Field.ON_GROUND
                    || binding.field == HudEntityBinding.Field.ENTITY_ID
                    || binding.field == HudEntityBinding.Field.FOOD
                    || binding.field == HudEntityBinding.Field.SATURATION
                    || binding.field == HudEntityBinding.Field.XP_LEVEL
                    || binding.field == HudEntityBinding.Field.ARMOR
                    || binding.field == HudEntityBinding.Field.FIRE_TICKS
                    || binding.field == HudEntityBinding.Field.AIR
                    || binding.field == HudEntityBinding.Field.HEALTH_PCT) {
                return Integer.toString(Math.round(numeric));
            }
            return formatOneDecimal(numeric);
        }
        return readStringField(entity, binding, source);
    }

    @Nullable
    private static Float readNumericField(Entity entity, HudEntityBinding binding, CommandSourceStack source) {
        return switch (binding.field) {
            case HEALTH -> entity instanceof LivingEntity living ? living.getHealth() : null;
            case MAX_HEALTH -> entity instanceof LivingEntity living ? living.getMaxHealth() : null;
            case HEALTH_PCT -> {
                if (!(entity instanceof LivingEntity living)) yield null;
                float max = living.getMaxHealth();
                yield max > 0f ? (living.getHealth() / max) * 100f : 0f;
            }
            case ARMOR -> entity instanceof LivingEntity living ? (float) living.getArmorValue() : null;
            case ABSORPTION -> entity instanceof LivingEntity living ? living.getAbsorptionAmount() : null;
            case FOOD -> entity instanceof Player player ? (float) player.getFoodData().getFoodLevel() : null;
            case SATURATION -> entity instanceof Player player ? player.getFoodData().getSaturationLevel() : null;
            case XP_LEVEL -> entity instanceof Player player ? (float) player.experienceLevel : null;
            case ENTITY_ID -> (float) entity.getId();
            case BABY -> entity instanceof AgeableMob ageable && ageable.isBaby() ? 1f : 0f;
            case ON_GROUND -> entity.onGround() ? 1f : 0f;
            case POS_X -> (float) entity.getX();
            case POS_Y -> (float) entity.getY();
            case POS_Z -> (float) entity.getZ();
            case YAW -> entity.getYRot();
            case PITCH -> entity.getXRot();
            case DISTANCE -> {
                Entity viewer = source != null ? source.getEntity() : null;
                yield viewer != null ? viewer.distanceTo(entity) : null;
            }
            case SPEED -> {
                Vec3 motion = entity.getDeltaMovement();
                yield (float) Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            }
            case FIRE_TICKS -> (float) entity.getRemainingFireTicks();
            case AIR -> (float) entity.getAirSupply();
            default -> null;
        };
    }

    private static String readStringField(Entity entity, HudEntityBinding binding, CommandSourceStack source) {
        return switch (binding.field) {
            case NAME -> entity.getDisplayName().getString();
            case CUSTOM_NAME -> entity.hasCustomName() && entity.getCustomName() != null
                    ? entity.getCustomName().getString() : "";
            case TYPE -> ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString();
            case UUID -> entity.getUUID().toString();
            case DIMENSION -> entity.level().dimension().location().toString();
            case TEAM -> entity.getTeam() != null ? entity.getTeam().getName() : "";
            case GAMEMODE -> {
                if (entity instanceof ServerPlayer serverPlayer) {
                    yield serverPlayer.gameMode.getGameModeForPlayer().getName();
                }
                if (entity instanceof Player player) {
                    yield player.isSpectator() ? "spectator"
                            : player.isCreative() ? "creative" : "survival";
                }
                yield "";
            }
            case SCOREBOARD_NAME -> entity instanceof Player player ? player.getScoreboardName() : "";
            default -> "";
        };
    }

    @Nullable
    private static Tag readNbtTag(Entity entity, @Nullable String path) {
        if (path == null || path.isEmpty()) return null;
        CompoundTag root = new CompoundTag();
        entity.saveWithoutId(root);
        Tag current = root;
        for (String part : path.split("\\.")) {
            if (part.isEmpty()) return null;
            if (!(current instanceof CompoundTag compound)) return null;
            current = compound.get(part);
            if (current == null) return null;
        }
        return current;
    }

    private static float parseNumericTag(@Nullable Tag tag) {
        if (tag instanceof NumericTag numeric) {
            return numeric.getAsFloat();
        }
        if (tag instanceof StringTag stringTag) {
            try {
                return Float.parseFloat(stringTag.getAsString());
            } catch (NumberFormatException ignored) {
                return Float.NaN;
            }
        }
        return Float.NaN;
    }

    private static String tagToString(@Nullable Tag tag) {
        if (tag == null) return "";
        if (tag instanceof NumericTag numeric) {
            float value = numeric.getAsFloat();
            if (Math.abs(value - Math.round(value)) < 0.05f) {
                return Integer.toString(Math.round(value));
            }
            return formatOneDecimal(value);
        }
        if (tag instanceof StringTag stringTag) {
            return stringTag.getAsString();
        }
        return tag.getAsString();
    }

    private static String formatOneDecimal(float value) {
        int scaled = Math.round(value * 10f);
        int whole = scaled / 10;
        int frac = Math.abs(scaled % 10);
        return whole + "." + frac;
    }
}
