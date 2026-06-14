package com.lootmatrix.customui.client;

import com.lootmatrix.customui.client.render.RenderResourceCache;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for detecting TACZ and Superbwarfare weapons and retrieving ammo information.
 * Uses reflection to avoid hard dependencies on these mods.
 */
public class GunAmmoHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(GunAmmoHelper.class);

    // Mod IDs
    private static final String TACZ_MOD_ID = "tacz";
    private static final String SUPERBWARFARE_MOD_ID = "superbwarfare";

    // Cached mod presence
    private static Boolean taczLoaded = null;
    private static Boolean superbwarfareLoaded = null;

    // TACZ reflection cache
    private static Class<?> taczIGunClass = null;
    private static Method taczGetCurrentAmmoMethod = null;
    private static Method taczGetGunIdMethod = null;
    private static Method taczGetReloadProgressMethod = null;
    private static Method taczGetReloadProgressMethod2 = null;
    private static Method taczIsReloadingMethod = null;
    private static Method taczIsReloadingMethod2 = null;
    private static boolean taczReflectionInitialized = false;
    private static Method taczHasBulletInBarrelMethod = null;
    private static Method taczGetFireModeMethod = null;
    private static Method taczGetAttachmentIdMethod = null;
    // GC optimization: cached useDummyAmmo/getDummyAmmoAmount (eliminates per-call getMethods loop)
    private static Method taczUseDummyAmmoMethod = null;
    private static Method taczGetDummyAmmoAmountMethod = null;

    // GC optimization: cached TimelessAPI reflection (eliminates per-frame Class.forName)
    private static Class<?> taczTimelessApiClass = null;
    private static Method taczGetCommonGunIndexMethod = null;
    private static boolean taczTimelessApiInitialized = false;

    // GC optimization: cached AttachmentType reflection
    private static Class<?> taczAttachmentTypeClass = null;
    private static Object taczExtendedMagEnumValue = null;
    private static boolean taczAttachmentTypeInitialized = false;

    // GC optimization: cached IAmmoBox reflection
    private static Class<?> taczIAmmoBoxClass = null;
    private static Method taczIsAmmoBoxOfGunMethod = null;
    private static Method taczGetAmmoCountMethod = null;
    private static Method taczIsCreativeMethod = null;
    private static Method taczIsAllTypeCreativeMethod = null;
    private static boolean taczAmmoBoxInitialized = false;

    // GC optimization: cached IAmmo reflection
    private static Class<?> taczIAmmoClass = null;
    private static Method taczIsAmmoOfGunMethod = null;
    private static boolean taczAmmoInitialized = false;
    private static final Map<ResourceLocation, Boolean> taczOpenBoltCache = new HashMap<>();
    private static final Map<ResourceLocation, Integer> taczBaseMagazineCapacityCache = new HashMap<>();

    private static Class<?> taczGunOperatorClass = null;
    private static Method taczOperatorFromLivingEntityMethod = null;
    private static Method taczOperatorGetReloadStateMethod = null;
    private static Class<?> taczReloadStateClass = null;
    private static Object taczReloadStateNotReloading = null;
    private static boolean taczOperatorReflectionInitialized = false;

    // Superbwarfare reflection cache
    private static Class<?> superbwarfareGunItemClass = null;
    private static boolean superbwarfareReflectionInitialized = false;

    public static boolean isTaczLoaded() {
        if (taczLoaded == null) {
            taczLoaded = ModList.get().isLoaded(TACZ_MOD_ID);
        }
        return taczLoaded;
    }

    public static boolean isSuperbwarfareLoaded() {
        if (superbwarfareLoaded == null) {
            superbwarfareLoaded = ModList.get().isLoaded(SUPERBWARFARE_MOD_ID);
        }
        return superbwarfareLoaded;
    }

    private static void initTaczReflection() {
        if (taczReflectionInitialized) return;
        taczReflectionInitialized = true;
        if (!isTaczLoaded()) return;

        try {
            taczIGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            for (Method method : taczIGunClass.getMethods()) {
                String name = method.getName();
                if (name.equals("getCurrentAmmoCount") && method.getParameterCount() == 1) {
                    taczGetCurrentAmmoMethod = method;
                }
                if (name.equals("getGunId") && method.getParameterCount() == 1) {
                    taczGetGunIdMethod = method;
                }
                if (name.equals("hasBulletInBarrel") && method.getParameterCount() == 1) {
                    taczHasBulletInBarrelMethod = method;
                }
                if (name.equals("getFireMode") && method.getParameterCount() == 1) {
                    taczGetFireModeMethod = method;
                }
                if (name.equals("getAttachmentId") && method.getParameterCount() == 2) {
                    taczGetAttachmentIdMethod = method;
                }
                if (name.equals("isReloading")) {
                    if (method.getParameterCount() == 1) {
                        taczIsReloadingMethod = method;
                    } else if (method.getParameterCount() == 2) {
                        taczIsReloadingMethod2 = method;
                    }
                }
                if (name.equals("getReloadProgress") || name.equals("getReloadingProgress")) {
                    if (method.getParameterCount() == 1) {
                        taczGetReloadProgressMethod = method;
                    } else if (method.getParameterCount() == 2) {
                        taczGetReloadProgressMethod2 = method;
                    }
                }
                if (name.equals("useDummyAmmo") && method.getParameterCount() == 1) {
                    taczUseDummyAmmoMethod = method;
                }
                if (name.equals("getDummyAmmoAmount") && method.getParameterCount() == 1) {
                    taczGetDummyAmmoAmountMethod = method;
                }
            }
//            LOGGER.info("[GunAmmoHelper] TACZ reflection initialized");
        } catch (Throwable e) {
//            LOGGER.debug("[GunAmmoHelper] TACZ reflection failed", e);
        }
    }

    private static void initTaczOperatorReflection() {
        if (taczOperatorReflectionInitialized) return;
        taczOperatorReflectionInitialized = true;
        if (!isTaczLoaded()) return;

        try {
            taczGunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
            LOGGER.debug("[GunAmmoHelper] Found IGunOperator class");

            for (Method method : taczGunOperatorClass.getMethods()) {
                String name = method.getName();
                if (name.equals("fromLivingEntity") && method.getParameterCount() == 1) {
                    taczOperatorFromLivingEntityMethod = method;
                }
                // TACZ uses getSynReloadState() to get reload state
                if (name.equals("getSynReloadState") && method.getParameterCount() == 0) {
                    taczOperatorGetReloadStateMethod = method;
                    LOGGER.debug("[GunAmmoHelper] Found getSynReloadState method: {}", method);
                }
            }

            // Log all methods for debugging at DEBUG level
            LOGGER.debug("[GunAmmoHelper] IGunOperator methods:");
            for (Method method : taczGunOperatorClass.getMethods()) {
                if (method.getDeclaringClass() == taczGunOperatorClass) {
                    LOGGER.debug("  - {}({} params)", method.getName(), method.getParameterCount());
                }
            }

            // Also try to find ReloadState enum
            try {
                taczReloadStateClass = Class.forName("com.tacz.guns.api.entity.ReloadState");
                LOGGER.debug("[GunAmmoHelper] Found ReloadState class");

                // Get enum constants
                if (taczReloadStateClass.isEnum()) {
                    for (Object constant : taczReloadStateClass.getEnumConstants()) {
                        String constantName = ((Enum<?>) constant).name();
                        LOGGER.debug("[GunAmmoHelper] ReloadState.{}", constantName);
                        if (constantName.equals("NOT_RELOADING") || constantName.equals("NONE")) {
                            taczReloadStateNotReloading = constant;
                        }
                    }
                }
            } catch (Throwable e) {
                LOGGER.debug("[GunAmmoHelper] ReloadState class not found, will check by name");
            }
        } catch (Throwable e) {
            LOGGER.debug("[GunAmmoHelper] Failed to init IGunOperator: {}", e.getMessage());
            taczGunOperatorClass = null;
            taczOperatorFromLivingEntityMethod = null;
            taczOperatorGetReloadStateMethod = null;
        }
    }

    private static void initSuperbwarfareReflection() {
        if (superbwarfareReflectionInitialized) return;
        superbwarfareReflectionInitialized = true;
        if (!isSuperbwarfareLoaded()) return;

        try {
            String[] classes = {"com.atsuishio.superbwarfare.item.gun.GunItem", "com.superbwarfare.item.gun.GunItem"};
            for (String className : classes) {
                try {
                    superbwarfareGunItemClass = Class.forName(className);
                    break;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            // ignore
        }
    }

    private static Object getTaczOperator(Player player) {
        if (player == null) {
            return null;
        }
        initTaczOperatorReflection();
        try {
            if (taczGunOperatorClass != null && taczGunOperatorClass.isInstance(player)) {
                return player;
            }
            if (taczOperatorFromLivingEntityMethod != null) {
                return taczOperatorFromLivingEntityMethod.invoke(null, player);
            }
        } catch (Throwable ignored) {
            // Unsupported TaCZ build, fall back to other ammo/reload sources.
        }
        return null;
    }

    public static boolean isTaczGun(ItemStack stack) {
        if (stack.isEmpty() || !isTaczLoaded()) return false;
        initTaczReflection();
        if (taczIGunClass == null) return false;
        try {
            return taczIGunClass.isInstance(stack.getItem());
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isSuperbwarfareGun(ItemStack stack) {
        if (stack.isEmpty() || !isSuperbwarfareLoaded()) return false;
        initSuperbwarfareReflection();

        // Check by class
        if (superbwarfareGunItemClass != null) {
            try {
                if (superbwarfareGunItemClass.isInstance(stack.getItem())) return true;
            } catch (Throwable ignored) {}
        }

        // Fallback: check by namespace
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId != null && itemId.getNamespace().equals(SUPERBWARFARE_MOD_ID)) {
            String path = itemId.getPath();
            return path.contains("gun") || path.contains("rifle") || path.contains("pistol") ||
                   path.contains("smg") || path.contains("shotgun") || path.contains("sniper");
        }
        return false;
    }

    public static boolean isGun(ItemStack stack) {
        return isTaczGun(stack) || isSuperbwarfareGun(stack);
    }

    /**
     * Get total ammo string for display.
     * For TACZ guns: shows current magazine ammo count (0 is shown as "0")
     */
    public static String getTotalAmmoString(ItemStack stack) {
        if (isTaczGun(stack)) {
            int current = getTaczCurrentAmmo(stack);
            // Always show the number, including 0
            return String.valueOf(Math.max(0, current));
        }

        if (isSuperbwarfareGun(stack)) {
            int ammo = getSuperbwarfareAmmo(stack);
            if (ammo >= 0) {
                return String.valueOf(ammo);
            }
            return "∞";
        }

        return "";
    }

    /**
     * Get TACZ current ammo count.
     * Follows TACZ's exact logic:
     * ammoCount = getCurrentAmmoCount + (hasBulletInBarrel && bolt != OPEN_BOLT ? 1 : 0)
     */
    public static int getTaczCurrentAmmo(ItemStack stack) {
        if (!isTaczGun(stack)) return 0;

        try {
            initTaczReflection();
            if (taczIGunClass == null) return 0;

            // Get gun ID for accessing GunData
            ResourceLocation gunId = getTaczGunId(stack);
            if (gunId == null) return 0;

            // Get current ammo count from magazine
            int ammoCount = 0;
            if (taczGetCurrentAmmoMethod != null) {
                Object result = taczGetCurrentAmmoMethod.invoke(stack.getItem(), stack);
                if (result instanceof Number) {
                    ammoCount = ((Number) result).intValue();
                }
            }

            // GC optimization: use cached method instead of per-call getMethods() loop
            boolean hasBulletInBarrel = false;
            if (taczHasBulletInBarrelMethod != null) {
                Object barrelResult = taczHasBulletInBarrelMethod.invoke(stack.getItem(), stack);
                if (barrelResult instanceof Boolean) {
                    hasBulletInBarrel = (Boolean) barrelResult;
                }
            }

            // Get bolt type from GunData
            if (hasBulletInBarrel) {
                boolean isOpenBolt = isTaczOpenBolt(gunId);
                if (!isOpenBolt) {
                    ammoCount += 1;
                }
            }

            return ammoCount;
        } catch (Exception e) {
//            LOGGER.debug("[GunAmmoHelper] TACZ reflection ammo failed", e);
        }

        // Fallback: read from NBT (simplified, may not be 100% accurate)
        if (!stack.hasTag()) return 0;
        CompoundTag tag = stack.getTag();
        if (tag == null) return 0;

        int ammoCount = 0;
        if (tag.contains("gun")) {
            CompoundTag gunTag = tag.getCompound("gun");
            if (gunTag.contains("current_ammo_count")) {
                ammoCount = gunTag.getInt("current_ammo_count");
            }
            // For NBT fallback, we can't easily determine bolt type, so just add barrel bullet
            if (gunTag.contains("has_bullet_in_barrel") && gunTag.getBoolean("has_bullet_in_barrel")) {
                ammoCount += 1;
            }
        }

        return ammoCount;
    }

    /**
     * Check if gun has OPEN_BOLT type.
     * OPEN_BOLT guns don't count the barrel bullet separately.
     */
    // GC optimization: lazy-init TimelessAPI reflection (shared by isTaczOpenBolt and getTaczMagazineCapacity)
    private static void initTimelessApiReflection() {
        if (taczTimelessApiInitialized) return;
        taczTimelessApiInitialized = true;
        try {
            taczTimelessApiClass = Class.forName("com.tacz.guns.api.TimelessAPI");
            for (Method m : taczTimelessApiClass.getMethods()) {
                if (m.getName().equals("getCommonGunIndex") && m.getParameterCount() == 1) {
                    taczGetCommonGunIndexMethod = m;
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    // GC optimization: lazy-init AttachmentType reflection
    private static void initAttachmentTypeReflection() {
        if (taczAttachmentTypeInitialized) return;
        taczAttachmentTypeInitialized = true;
        try {
            taczAttachmentTypeClass = Class.forName("com.tacz.guns.api.item.attachment.AttachmentType");
            for (Object enumConstant : taczAttachmentTypeClass.getEnumConstants()) {
                if (enumConstant.toString().equals("EXTENDED_MAG")) {
                    taczExtendedMagEnumValue = enumConstant;
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    // GC optimization: lazy-init IAmmoBox reflection
    private static void initAmmoBoxReflection() {
        if (taczAmmoBoxInitialized) return;
        taczAmmoBoxInitialized = true;
        try {
            taczIAmmoBoxClass = Class.forName("com.tacz.guns.api.item.IAmmoBox");
            for (Method m : taczIAmmoBoxClass.getMethods()) {
                String name = m.getName();
                if (name.equals("isAmmoBoxOfGun") && m.getParameterCount() == 2) taczIsAmmoBoxOfGunMethod = m;
                if (name.equals("getAmmoCount") && m.getParameterCount() == 1) taczGetAmmoCountMethod = m;
                if (name.equals("isCreative") && m.getParameterCount() == 1) taczIsCreativeMethod = m;
                if (name.equals("isAllTypeCreative") && m.getParameterCount() == 1) taczIsAllTypeCreativeMethod = m;
            }
        } catch (Throwable ignored) {}
    }

    // GC optimization: lazy-init IAmmo reflection
    private static void initAmmoReflection() {
        if (taczAmmoInitialized) return;
        taczAmmoInitialized = true;
        try {
            taczIAmmoClass = Class.forName("com.tacz.guns.api.item.IAmmo");
            for (Method m : taczIAmmoClass.getMethods()) {
                if (m.getName().equals("isAmmoOfGun") && m.getParameterCount() == 2) {
                    taczIsAmmoOfGunMethod = m;
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isTaczOpenBolt(ResourceLocation gunId) {
        Boolean cached = taczOpenBoltCache.get(gunId);
        if (cached != null) {
            return cached;
        }

        try {
            initTimelessApiReflection();
            if (taczGetCommonGunIndexMethod == null) return false;

            Object optionalGunIndex = taczGetCommonGunIndexMethod.invoke(null, gunId);
            if (optionalGunIndex != null) {
                Method isPresentMethod = optionalGunIndex.getClass().getMethod("isPresent");
                Method getMethod = optionalGunIndex.getClass().getMethod("get");

                if ((Boolean) isPresentMethod.invoke(optionalGunIndex)) {
                    Object gunIndex = getMethod.invoke(optionalGunIndex);
                    Method getGunDataMethod = gunIndex.getClass().getMethod("getGunData");
                    Object gunData = getGunDataMethod.invoke(gunIndex);

                    if (gunData != null) {
                        Method getBoltMethod = gunData.getClass().getMethod("getBolt");
                        Object bolt = getBoltMethod.invoke(gunData);
                        if (bolt != null) {
                            boolean openBolt = bolt.toString().equals("OPEN_BOLT");
                            taczOpenBoltCache.put(gunId, openBolt);
                            return openBolt;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // LOGGER.debug("[GunAmmoHelper] Error checking bolt type", e);
        }
        return false;
    }

    /**
     * Get TACZ magazine capacity (max ammo).
     * Uses TimelessAPI to get gun index and extended magazine info.
     */
    public static int getTaczMagazineCapacity(ItemStack stack) {
        if (!isTaczGun(stack)) return -1;

        long now = System.currentTimeMillis();
        if (cachedMagazineCapacityGunStack == stack && now - cachedMagazineCapacityAtMs <= MAG_CAPACITY_CACHE_WINDOW_MS) {
            return cachedMagazineCapacityValue;
        }

        try {
            // Get gun ID first
            ResourceLocation gunId = getTaczGunId(stack);
            if (gunId == null) {
//                LOGGER.debug("[GunAmmoHelper] Cannot get magazine capacity: gun ID is null");
                return -1;
            }

            // GC optimization: use cached TimelessAPI reflection
            initTimelessApiReflection();
            Method getCommonGunIndexMethod = taczGetCommonGunIndexMethod;

            if (getCommonGunIndexMethod == null) {
//                LOGGER.debug("[GunAmmoHelper] Cannot get magazine capacity: getCommonGunIndex method not found");
                return -1;
            }

            Object optionalGunIndex = getCommonGunIndexMethod.invoke(null, gunId);
            // Optional.isPresent() and Optional.get()
            if (optionalGunIndex == null) {
//                LOGGER.debug("[GunAmmoHelper] Cannot get magazine capacity: optionalGunIndex is null");
                return -1;
            }

            Method isPresentMethod = optionalGunIndex.getClass().getMethod("isPresent");
            Method getMethod = optionalGunIndex.getClass().getMethod("get");

            if (!(Boolean) isPresentMethod.invoke(optionalGunIndex)) {
//                LOGGER.debug("[GunAmmoHelper] Cannot get magazine capacity: gun index not present for {}", gunId);
                return -1;
            }

            Object gunIndex = getMethod.invoke(optionalGunIndex);

            // Get GunData from gunIndex
            Method getGunDataMethod = gunIndex.getClass().getMethod("getGunData");
            Object gunData = getGunDataMethod.invoke(gunIndex);

            if (gunData == null) {
//                LOGGER.debug("[GunAmmoHelper] Cannot get magazine capacity: gunData is null for {}", gunId);
                return -1;
            }

            // Get ammo amount from GunData
            Method getAmmoAmountMethod = gunData.getClass().getMethod("getAmmoAmount");
            Object ammoAmount = getAmmoAmountMethod.invoke(gunData);

            if (!(ammoAmount instanceof Number)) {
//                LOGGER.debug("[GunAmmoHelper] Cannot get magazine capacity: ammoAmount is not a number for {}", gunId);
                return -1;
            }

            int baseCapacity = taczBaseMagazineCapacityCache.getOrDefault(gunId, Integer.MIN_VALUE);
            if (baseCapacity == Integer.MIN_VALUE) {
                baseCapacity = ((Number) ammoAmount).intValue();
                taczBaseMagazineCapacityCache.put(gunId, baseCapacity);
            }

            // Check for extended magazine attachment
            int extendedMagLevel = getTaczExtendedMagLevel(stack);
            if (extendedMagLevel > 0) {
                // Get extended magazine bonus from GunData
                try {
                    Method getExtendedMagLevelMethod = gunData.getClass().getMethod("getExtendedMagLevel");
                    Object extendedMagLevels = getExtendedMagLevelMethod.invoke(gunData);

                    if (extendedMagLevels instanceof int[]) {
                        int[] levels = (int[]) extendedMagLevels;
                        if (extendedMagLevel <= levels.length && extendedMagLevel > 0) {
                            int value = baseCapacity + levels[extendedMagLevel - 1];
                            cachedMagazineCapacityGunStack = stack;
                            cachedMagazineCapacityValue = value;
                            cachedMagazineCapacityAtMs = now;
                            return value;
                        }
                    }
                } catch (NoSuchMethodException e) {
//                    LOGGER.debug("[GunAmmoHelper] No getExtendedMagLevel method found, using base capacity");
                }
            }

            cachedMagazineCapacityGunStack = stack;
            cachedMagazineCapacityValue = baseCapacity;
            cachedMagazineCapacityAtMs = now;
            return baseCapacity;
        } catch (Exception e) {
//            LOGGER.debug("[GunAmmoHelper] Error getting magazine capacity: {}", e.getMessage());
        }

        // Fallback: return -1 to indicate unknown capacity
        return -1;
    }

    /**
     * Get TACZ extended magazine level (0 = none, 1-3 = level).
     */
    private static int getTaczExtendedMagLevel(ItemStack stack) {
        try {
            // GC optimization: use cached reflection
            initTaczReflection();
            initAttachmentTypeReflection();
            Object extendedMagType = taczExtendedMagEnumValue;

            if (extendedMagType != null) {
                Method getAttachmentIdMethod = taczGetAttachmentIdMethod;

                if (getAttachmentIdMethod != null) {
                    Object attachmentId = getAttachmentIdMethod.invoke(stack.getItem(), stack, extendedMagType);
                    if (attachmentId != null) {
                        String idStr = attachmentId.toString();
                        // Parse level from attachment ID (e.g., "tacz:extended_mag_1" -> 1)
                        if (idStr.contains("extended_mag_")) {
                            try {
                                String levelStr = idStr.substring(idStr.lastIndexOf("_") + 1);
                                return Integer.parseInt(levelStr);
                            } catch (NumberFormatException ignored) {}
                        }
                        // Has extended mag but unknown level
                        if (!idStr.isEmpty() && !idStr.equals("minecraft:air")) {
                            return 1;
                        }
                    }
                }
            }
        } catch (Exception e) {
//            LOGGER.debug("[GunAmmoHelper] Error getting extended mag level", e);
        }
        return 0;
    }

    /**
     * Get TACZ reserve ammo count.
     * Following TACZ's original logic:
     * 1. If gun uses dummy ammo -> return dummy ammo amount
     * 2. If gun has infinite ammo -> return 9999
     * 3. Otherwise -> count ammo items + ammo boxes in inventory
     */
    public static int getTaczReserveAmmo(ItemStack gunStack, Player player) {
        if (player == null) return 0;
        if (!isTaczGun(gunStack)) return 0;

        long now = System.currentTimeMillis();
        int selectedSlot = player.getInventory().selected;
        int signature = computeInventoryAmmoSignature(player);
        if (cachedReserveAmmoGunStack == gunStack
                && cachedReserveAmmoSlot == selectedSlot
                && cachedReserveAmmoSignature == signature
                && now - cachedReserveAmmoAtMs <= RESERVE_AMMO_CACHE_WINDOW_MS) {
            return cachedReserveAmmoValue;
        }

        // Check if gun uses dummy ammo (virtual backup ammo)
        boolean usesDummyAmmo = false;
        int dummyAmmo = 0;

        try {
            initTaczReflection();
            if (taczIGunClass != null) {
                // GC optimization: use cached reflection methods instead of per-call getMethods() loop
                if (taczUseDummyAmmoMethod != null) {
                    Object result = taczUseDummyAmmoMethod.invoke(gunStack.getItem(), gunStack);
                    if (result instanceof Boolean) {
                        usesDummyAmmo = (Boolean) result;
                    }
                }

                if (usesDummyAmmo && taczGetDummyAmmoAmountMethod != null) {
                    Object result = taczGetDummyAmmoAmountMethod.invoke(gunStack.getItem(), gunStack);
                    if (result instanceof Number) {
                        dummyAmmo = ((Number) result).intValue();
                    }
                    cacheReserveAmmo(gunStack, selectedSlot, signature, dummyAmmo, now);
                    return dummyAmmo;
                }
            }
        } catch (Exception e) {
//            LOGGER.debug("[GunAmmoHelper] Error checking dummy ammo via reflection", e);
        }

        // Check for infinite ammo
        if (isTaczInfiniteAmmo(gunStack)) {
            cacheReserveAmmo(gunStack, selectedSlot, signature, 9999, now);
            return 9999;
        }

        // Count physical ammo in inventory using TACZ's IAmmo and IAmmoBox interfaces
        int count = countAmmoBoxAmmo(player, gunStack);
        cacheReserveAmmo(gunStack, selectedSlot, signature, count, now);

//        LOGGER.debug("[GunAmmoHelper] Reserve ammo count: {}", count);
        return count;
    }

    private static void cacheReserveAmmo(ItemStack gunStack, int selectedSlot, int signature, int value, long now) {
        cachedReserveAmmoGunStack = gunStack;
        cachedReserveAmmoSlot = selectedSlot;
        cachedReserveAmmoSignature = signature;
        cachedReserveAmmoValue = value;
        cachedReserveAmmoAtMs = now;
    }

    private static int computeInventoryAmmoSignature(Player player) {
        int signature = player.getInventory().selected;
        for (ItemStack stack : player.getInventory().items) {
            signature = 31 * signature + System.identityHashCode(stack.getItem());
            signature = 31 * signature + stack.getCount();
            signature = 31 * signature + stack.getDamageValue();
            // Include NBT hashCode: ammo boxes / dummy ammo store counts inside NBT.
            // Without this, consuming ammo from a box doesn't invalidate the cache.
            CompoundTag tag = stack.getTag();
            signature = 31 * signature + (tag != null ? tag.hashCode() : 0);
        }
        return signature;
    }

    /**
     * Get TACZ dummy ammo (virtual backup ammo stored in the gun).
     */
    public static int getTaczDummyAmmo(ItemStack gunStack) {
        if (!isTaczGun(gunStack)) return 0;

        // Try reflection first
        try {
            initTaczReflection();
            if (taczIGunClass != null) {
                // GC optimization: use cached reflection methods instead of per-call getMethods() loop
                if (taczUseDummyAmmoMethod != null && taczGetDummyAmmoAmountMethod != null) {
                    Object usesDummy = taczUseDummyAmmoMethod.invoke(gunStack.getItem(), gunStack);
                    if (usesDummy instanceof Boolean && (Boolean) usesDummy) {
                        Object amount = taczGetDummyAmmoAmountMethod.invoke(gunStack.getItem(), gunStack);
                        if (amount instanceof Number) {
                            return ((Number) amount).intValue();
                        }
                    }
                }
            }
        } catch (Exception e) {
//            LOGGER.debug("[GunAmmoHelper] TACZ dummy ammo reflection failed", e);
        }

        // Fallback: read from NBT
        if (!gunStack.hasTag()) return 0;
        CompoundTag tag = gunStack.getTag();
        if (tag == null) return 0;

        if (tag.contains("gun")) {
            CompoundTag gunTag = tag.getCompound("gun");
            if (gunTag.contains("dummy_ammo_count")) {
                return gunTag.getInt("dummy_ammo_count");
            }
            if (gunTag.contains("dummyAmmoAmount")) {
                return gunTag.getInt("dummyAmmoAmount");
            }
        }

        return 0;
    }

    /**
     * Check if TACZ gun has infinite ammo.
     */
    private static boolean isTaczInfiniteAmmo(ItemStack gunStack) {
        if (!gunStack.hasTag()) return false;
        CompoundTag tag = gunStack.getTag();
        if (tag == null) return false;

        if (tag.contains("gun")) {
            CompoundTag gunTag = tag.getCompound("gun");
            if (gunTag.contains("infinite_ammo")) {
                return gunTag.getBoolean("infinite_ammo");
            }
        }
        return false;
    }

    /**
     * Count ammo from ammo boxes in player inventory using TACZ's IAmmoBox interface.
     */
    private static int countAmmoBoxAmmo(Player player, ItemStack gunStack) {
        int count = 0;
        try {
            // GC optimization: use cached reflection
            initAmmoBoxReflection();
            Class<?> iAmmoBoxClass = taczIAmmoBoxClass;
            Method isAmmoBoxOfGunMethod = taczIsAmmoBoxOfGunMethod;
            Method getAmmoCountMethod = taczGetAmmoCountMethod;
            Method isCreativeMethod = taczIsCreativeMethod;
            Method isAllTypeCreativeMethod = taczIsAllTypeCreativeMethod;
            if (iAmmoBoxClass == null) return 0;

            if (isAmmoBoxOfGunMethod != null && getAmmoCountMethod != null) {
                for (ItemStack invStack : player.getInventory().items) {
                    if (!invStack.isEmpty() && iAmmoBoxClass.isInstance(invStack.getItem())) {
                        // Check if this ammo box is for our gun
                        Object isForGun = isAmmoBoxOfGunMethod.invoke(invStack.getItem(), gunStack, invStack);
                        if (isForGun instanceof Boolean && (Boolean) isForGun) {
                            // Check for creative ammo box (infinite ammo)
                            if (isCreativeMethod != null && isAllTypeCreativeMethod != null) {
                                Object isCreative = isCreativeMethod.invoke(invStack.getItem(), invStack);
                                Object isAllCreative = isAllTypeCreativeMethod.invoke(invStack.getItem(), invStack);
                                if ((isCreative instanceof Boolean && (Boolean) isCreative) ||
                                    (isAllCreative instanceof Boolean && (Boolean) isAllCreative)) {
                                    return 9999; // Creative ammo box = infinite
                                }
                            }

                            Object ammoCount = getAmmoCountMethod.invoke(invStack.getItem(), invStack);
                            if (ammoCount instanceof Number) {
                                count += ((Number) ammoCount).intValue();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // IAmmoBox not available or reflection failed
        }

        // Also count IAmmo items
        count += countAmmoItems(player, gunStack);

        return count;
    }

    /**
     * Count ammo items in player inventory using TACZ's IAmmo interface.
     */
    private static int countAmmoItems(Player player, ItemStack gunStack) {
        int count = 0;
        try {            // GC optimization: use cached reflection
            initAmmoReflection();
            Class<?> iAmmoClass = taczIAmmoClass;
            Method isAmmoOfGunMethod = taczIsAmmoOfGunMethod;
            if (iAmmoClass == null || isAmmoOfGunMethod == null) return 0;

            {
                for (ItemStack invStack : player.getInventory().items) {
                    if (!invStack.isEmpty() && iAmmoClass.isInstance(invStack.getItem())) {
                        Object isForGun = isAmmoOfGunMethod.invoke(invStack.getItem(), gunStack, invStack);
                        if (isForGun instanceof Boolean && (Boolean) isForGun) {
                            count += invStack.getCount();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // IAmmo not available or reflection failed
        }
        return count;
    }

    /**
     * Get TACZ gun fire mode string.
     * Returns: "AUTO", "SEMI", "BURST" etc.
     */
    public static String getTaczFireMode(ItemStack stack) {
        if (!isTaczGun(stack)) return "";

        long now = System.currentTimeMillis();
        if (cachedFireModeGunStack == stack && now - cachedFireModeAtMs <= FIRE_MODE_CACHE_WINDOW_MS) {
            return cachedFireModeValue;
        }

        try {
            // Use reflection to get fire mode
            // GC optimization: use cached reflection
            initTaczReflection();
            Method getFireModeMethod = taczGetFireModeMethod;

            if (getFireModeMethod != null) {
                Object fireMode = getFireModeMethod.invoke(stack.getItem(), stack);
                if (fireMode != null) {
                    String modeName = fireMode.toString();
                    // Convert enum name to display string
                    switch (modeName) {
                        case "AUTO":
                            cachedFireModeValue = "AUTO";
                            break;
                        case "SEMI":
                            cachedFireModeValue = "SEMI";
                            break;
                        case "BURST":
                            cachedFireModeValue = "BURST";
                            break;
                        default:
                            cachedFireModeValue = modeName;
                            break;
                    }
                    cachedFireModeGunStack = stack;
                    cachedFireModeAtMs = now;
                    return cachedFireModeValue;
                }
            }
        } catch (Exception e) {
//            LOGGER.debug("[GunAmmoHelper] Error getting fire mode", e);
        }

        // Fallback: read from NBT
        if (stack.hasTag()) {
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains("gun")) {
                CompoundTag gunTag = tag.getCompound("gun");
                if (gunTag.contains("fire_mode")) {
                    String mode = gunTag.getString("fire_mode");
                    if (!mode.isEmpty()) {
                        cachedFireModeGunStack = stack;
                        cachedFireModeValue = mode.toUpperCase();
                        cachedFireModeAtMs = now;
                        return cachedFireModeValue;
                    }
                }
            }
        }

        cachedFireModeGunStack = stack;
        cachedFireModeValue = "SEMI";
        cachedFireModeAtMs = now;
        return cachedFireModeValue; // Default
    }

    /**
     * Get Superbwarfare ammo count from NBT.
     */
    private static int getSuperbwarfareAmmo(ItemStack stack) {
        if (!stack.hasTag()) return -1;
        CompoundTag tag = stack.getTag();
        if (tag == null) return -1;

        // Superbwarfare common NBT keys
        String[] keys = {"ammo", "Ammo", "GunAmmo", "magazine", "bullets"};
        for (String key : keys) {
            if (tag.contains(key)) {
                return tag.getInt(key);
            }
        }

        return -1;
    }

    /**
     * Get the gun ID for TACZ guns.
     */
    public static ResourceLocation getTaczGunId(ItemStack stack) {
        if (!isTaczGun(stack)) return null;

        // Try reflection first
        initTaczReflection();
        if (taczGetGunIdMethod != null) {
            try {
                Object result = taczGetGunIdMethod.invoke(stack.getItem(), stack);
                if (result instanceof ResourceLocation) {
//                    LOGGER.debug("[GunAmmoHelper] Got gun ID via reflection: {}", result);
                    return (ResourceLocation) result;
                }
            } catch (Exception e) {
//                LOGGER.debug("[GunAmmoHelper] Reflection failed for getGunId", e);
            }
        }

        // Read from NBT
        if (stack.hasTag()) {
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains("gun")) {
                CompoundTag gunTag = tag.getCompound("gun");
                if (gunTag.contains("gun_id")) {
                    String id = gunTag.getString("gun_id");
                    if (!id.isEmpty()) {
                        ResourceLocation parsed = ResourceLocation.tryParse(id);
                        if (parsed != null) {
                            return parsed;
                        }
                    }
                }
                if (gunTag.contains("id")) {
                    String id = gunTag.getString("id");
                    if (!id.isEmpty()) {
                        ResourceLocation parsed = ResourceLocation.tryParse(id);
                        if (parsed != null) {
                            return parsed;
                        }
                    }
                }
            }
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
//        LOGGER.debug("[GunAmmoHelper] Using item registry ID as gun ID: {}", itemId);
        return itemId;
    }

    /**
     * Get the HUD texture path for a TACZ gun.
     * Returns null if no texture is found.
     */
    public static ResourceLocation getGunHudTexture(ItemStack stack) {
        if (!isTaczGun(stack)) return null;

        ResourceLocation gunId = getTaczGunId(stack);
        if (gunId == null) {
//            LOGGER.debug("[GunAmmoHelper] Cannot get gun ID for HUD texture");
            return null;
        }

        // TACZ HUD texture path: assets/<namespace>/textures/gun/hud/<path>.png
        ResourceLocation hudTexture = ResourceLocation.fromNamespaceAndPath(
                gunId.getNamespace(), "textures/gun/hud/" + gunId.getPath() + ".png");
//        LOGGER.debug("[GunAmmoHelper] Looking for HUD texture: {}", hudTexture);

        // Verify texture exists
        if (textureExists(hudTexture)) {
//            LOGGER.debug("[GunAmmoHelper] HUD texture found: {}", hudTexture);
            return hudTexture;
        }

//        LOGGER.debug("[GunAmmoHelper] HUD texture not found: {}", hudTexture);
        return null;
    }

    /**
     * Check if a texture resource exists.
     */
    public static boolean textureExists(ResourceLocation texture) {
        return RenderResourceCache.exists(texture);
    }

    // Debug flag for reload progress
    private static boolean debugReloadLogged = false;
    private static boolean reloadStateMethodsLogged = false;

    // Track reload state (simplified — renderer owns progress timing)
    private static boolean isInReload = false;
    private static int reloadingSlot = -1;
    private static int lastCompletedSlot = -1;
    private static long reloadStartTimeMs = 0L;  // Wall-clock ms when reload started
    private static String reloadPhase = "";
    private static long phaseMaxCountDownMs = 0L; // Max countdown sampled for current phase

    // Cached ReloadState methods for performance
    private static Method cachedGetStateTypeMethod = null;
    private static Method cachedGetCountDownMethod = null;
    private static boolean reloadStateMethodsCached = false;
    private static final long RESERVE_AMMO_CACHE_WINDOW_MS = 100L;
    private static ItemStack cachedReserveAmmoGunStack = ItemStack.EMPTY;
    private static int cachedReserveAmmoSlot = -1;
    private static int cachedReserveAmmoSignature = Integer.MIN_VALUE;
    private static int cachedReserveAmmoValue = 0;
    private static long cachedReserveAmmoAtMs = 0L;
    private static final long FIRE_MODE_CACHE_WINDOW_MS = 75L;
    private static ItemStack cachedFireModeGunStack = ItemStack.EMPTY;
    private static String cachedFireModeValue = "SEMI";
    private static long cachedFireModeAtMs = 0L;
    private static final long MAG_CAPACITY_CACHE_WINDOW_MS = 250L;
    private static ItemStack cachedMagazineCapacityGunStack = ItemStack.EMPTY;
    private static int cachedMagazineCapacityValue = -1;
    private static long cachedMagazineCapacityAtMs = 0L;

    /**
     * Get TACZ reload progress (0-1) for the main reload phase ONLY (excluding feed/finishing time).
     * Implementation detail: we treat the "FEEDING" state as the main reload time.
     * - While in FEEDING: progress = 1 - countDown / maxCountDown
     * - When not reloading: returns -1
     * - When reloading but not in FEEDING: returns 1 (bar completed, waiting for ammo update/flash)
     */
    public static float getReloadProgress(ItemStack stack, int slotIndex) {
        if (!isTaczGun(stack)) return -1f;

        Player player = Minecraft.getInstance().player;
        if (player == null) return -1f;

        int querySlot = slotIndex >= 0 ? slotIndex : player.getInventory().selected;
        int currentSelectedSlot = player.getInventory().selected;

        try {
            Object operator = getTaczOperator(player);
            if (operator != null && taczOperatorGetReloadStateMethod != null) {
                Object reloadState = taczOperatorGetReloadStateMethod.invoke(operator);
                if (reloadState == null) return -1f;

                    // Cache method references on first use
                    if (!reloadStateMethodsCached) {
                        Class<?> reloadStateClass = reloadState.getClass();
                        for (Method m : reloadStateClass.getMethods()) {
                            if (m.getName().equals("getStateType") && m.getParameterCount() == 0) {
                                cachedGetStateTypeMethod = m;
                            } else if (m.getName().equals("getCountDown") && m.getParameterCount() == 0) {
                                cachedGetCountDownMethod = m;
                            }
                        }
                        reloadStateMethodsCached = true;
                    }

                    Method getStateTypeMethod = cachedGetStateTypeMethod;
                    Method getCountDownMethod = cachedGetCountDownMethod;

                    String stateTypeName = "UNKNOWN";
                    boolean isReloading = false;
                    if (getStateTypeMethod != null) {
                        Object stateType = getStateTypeMethod.invoke(reloadState);
                        if (stateType != null) {
                            stateTypeName = stateType.toString();
                            isReloading = !stateTypeName.contains("NOT_RELOADING") && !stateTypeName.equals("NONE");
                        }
                    }

                    long countDown = 0L;
                    if (getCountDownMethod != null) {
                        Object cd = getCountDownMethod.invoke(reloadState);
                        if (cd instanceof Number n) {
                            countDown = n.longValue();
                        }
                    }
                    if (countDown < 0) countDown = 0;

                    if (!isReloading) {
                        if (isInReload) {
                            lastCompletedSlot = reloadingSlot;
                        }
                        isInReload = false;
                        reloadingSlot = -1;
                        reloadStartTimeMs = 0L;
                        reloadPhase = "";
                        phaseMaxCountDownMs = 0L;
                        return -1f;
                    }

                    if (!isInReload) {
                        isInReload = true;
                        reloadingSlot = currentSelectedSlot;
                        reloadStartTimeMs = System.currentTimeMillis();
                        reloadPhase = stateTypeName;
                        phaseMaxCountDownMs = Math.max(1L, countDown);
                    } else if (reloadingSlot != querySlot) {
                        return -1f;
                    }

                    // Phase change: reset max countdown capture
                    if (!stateTypeName.equals(reloadPhase)) {
                        reloadPhase = stateTypeName;
                        phaseMaxCountDownMs = Math.max(1L, countDown);
                    } else if (countDown > phaseMaxCountDownMs) {
                        // Some implementations may jump up; keep the max
                        phaseMaxCountDownMs = countDown;
                    }

                    // Main reload phase: FEEDING — show progress bar
                    if (stateTypeName.contains("FEEDING")) {
                        float max = (float) Math.max(1L, phaseMaxCountDownMs);
                        float p = 1f - ((float) countDown / max);
                        return Mth.clamp(p, 0f, 1f);
                    }

                    // FINISHING phase (feed time): reload complete, hide bar
                    // Return -1 to signal "not showing bar" but still in reload
                    if (stateTypeName.contains("FINISHING")) {
                        return -1f;
                    }

                    // Other non-FEEDING phases (e.g. STARTING, EMPTY_RELOAD):
                    // Return 0 so the bar starts from the beginning
                    return 0f;
            }
        } catch (Throwable e) {
            if (!debugReloadLogged) {
                LOGGER.warn("[GunAmmoHelper] ReloadState error: {}", e.getMessage());
                debugReloadLogged = true;
            }
        }

        // Method 2: Try IGun interface methods (older TACZ versions)
        try {
            initTaczReflection();
            if (taczIGunClass != null) {
                ResourceLocation gunId = getTaczGunId(stack);
                Boolean reloading = asBoolean(invokeReloadMethod(taczIsReloadingMethod, stack.getItem(), stack, player, gunId));
                if (reloading == null) {
                    reloading = asBoolean(invokeReloadMethod(taczIsReloadingMethod2, stack.getItem(), stack, player, gunId));
                }
                if (reloading != null && !reloading) {
                    return -1f;
                }

                Number progress = asNumber(invokeReloadMethod(taczGetReloadProgressMethod, stack.getItem(), stack, player, gunId));
                if (progress == null) {
                    progress = asNumber(invokeReloadMethod(taczGetReloadProgressMethod2, stack.getItem(), stack, player, gunId));
                }
                if (progress != null) {
                    return Mth.clamp(progress.floatValue(), 0f, 1f);
                }
            }
        } catch (Throwable ignored) {
        }

        // Method 3: Fall back to NBT data
        if (!stack.hasTag()) return -1f;
        CompoundTag tag = stack.getTag();
        if (tag == null) return -1f;

        CompoundTag gunTag = tag.contains("gun") ? tag.getCompound("gun") : tag;

        boolean reloading = false;
        if (gunTag.contains("is_reloading")) {
            reloading = gunTag.getBoolean("is_reloading");
        } else if (gunTag.contains("reloading")) {
            reloading = gunTag.getBoolean("reloading");
        } else if (gunTag.contains("reload_ticks")) {
            reloading = gunTag.getInt("reload_ticks") > 0;
        }

        if (!reloading) return -1f;

        if (gunTag.contains("reload_progress")) {
            return Mth.clamp(gunTag.getFloat("reload_progress"), 0f, 1f);
        }

        int ticks = gunTag.contains("reload_ticks") ? gunTag.getInt("reload_ticks") : -1;
        int totalTicks = gunTag.contains("reload_total_ticks") ? gunTag.getInt("reload_total_ticks") : -1;
        if (ticks >= 0 && totalTicks > 0) {
            return Mth.clamp(ticks / (float) totalTicks, 0f, 1f);
        }

        int time = gunTag.contains("reload_time") ? gunTag.getInt("reload_time") : -1;
        int duration = gunTag.contains("reload_duration") ? gunTag.getInt("reload_duration") : -1;
        if (time >= 0 && duration > 0) {
            return Mth.clamp(time / (float) duration, 0f, 1f);
        }

        return 0f;
    }

    /**
     * Convenience overload that uses the currently selected slot.
     */
    public static float getReloadProgress(ItemStack stack) {
        return getReloadProgress(stack, -1);
    }

    public static int getReloadingSlot() {
        return reloadingSlot;
    }

    public static long getReloadStartTimeMs() {
        return reloadStartTimeMs;
    }

    public static boolean consumeReloadCompletedForSlot(int slotIndex) {
        if (lastCompletedSlot == slotIndex) {
            lastCompletedSlot = -1;
            return true;
        }
        return false;
    }

    private static Object invokeReloadMethod(Method method, Object target, ItemStack stack, Player player, ResourceLocation gunId) {
        if (method == null) return null;
        try {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0) {
                return method.invoke(target);
            }
            if (params.length == 1) {
                Class<?> p0 = params[0];
                if (p0.isAssignableFrom(ItemStack.class)) {
                    return method.invoke(target, stack);
                }
                if (player != null && (p0.isAssignableFrom(Player.class) || p0.isAssignableFrom(player.getClass()))) {
                    return method.invoke(target, player);
                }
                if (gunId != null && p0.isAssignableFrom(ResourceLocation.class)) {
                    return method.invoke(target, gunId);
                }
                return null;
            }
            if (params.length == 2) {
                Class<?> p0 = params[0];
                Class<?> p1 = params[1];
                if (p0.isAssignableFrom(ItemStack.class) && player != null && p1.isAssignableFrom(Player.class)) {
                    return method.invoke(target, stack, player);
                }
                if (player != null && p0.isAssignableFrom(Player.class) && p1.isAssignableFrom(ItemStack.class)) {
                    return method.invoke(target, player, stack);
                }
                if (gunId != null && player != null && p0.isAssignableFrom(ResourceLocation.class) && p1.isAssignableFrom(Player.class)) {
                    return method.invoke(target, gunId, player);
                }
                if (gunId != null && player != null && p0.isAssignableFrom(Player.class) && p1.isAssignableFrom(ResourceLocation.class)) {
                    return method.invoke(target, player, gunId);
                }
                if (gunId != null && p0.isAssignableFrom(ResourceLocation.class) && p1.isAssignableFrom(ItemStack.class)) {
                    return method.invoke(target, gunId, stack);
                }
                if (gunId != null && p0.isAssignableFrom(ItemStack.class) && p1.isAssignableFrom(ResourceLocation.class)) {
                    return method.invoke(target, stack, gunId);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    private static Number asNumber(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        return null;
    }
}
