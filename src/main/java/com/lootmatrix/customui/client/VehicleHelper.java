package com.lootmatrix.customui.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Helper class for detecting Superbwarfare vehicles and their weapon capabilities.
 * Uses reflection to avoid hard dependencies on Superbwarfare mod.
 */
public class VehicleHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(VehicleHelper.class);

    private static final String SUPERBWARFARE_MOD_ID = "superbwarfare";

    // Cached mod presence
    private static Boolean superbwarfareLoaded = null;

    // Reflection cache
    private static Class<?> vehicleEntityClass = null;
    private static Method hasWeaponMethod = null;
    private static Method hasWeaponWithSeatMethod = null;
    private static Method getSeatIndexMethod = null;
    private static boolean reflectionInitialized = false;

    /**
     * Check if Superbwarfare mod is loaded.
     */
    public static boolean isSuperbwarfareLoaded() {
        if (superbwarfareLoaded == null) {
            superbwarfareLoaded = ModList.get().isLoaded(SUPERBWARFARE_MOD_ID);
        }
        return superbwarfareLoaded;
    }

    /**
     * Initialize reflection for Superbwarfare vehicle classes.
     */
    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        if (!isSuperbwarfareLoaded()) return;

        try {
            // Try to load VehicleEntity class
            String[] classNames = {
                "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
                "com.superbwarfare.entity.vehicle.base.VehicleEntity"
            };

            for (String className : classNames) {
                try {
                    vehicleEntityClass = Class.forName(className);
                    LOGGER.debug("[VehicleHelper] Found VehicleEntity class: {}", className);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }

            if (vehicleEntityClass != null) {
                // Get hasWeapon() method
                try {
                    hasWeaponMethod = vehicleEntityClass.getMethod("hasWeapon");
                    LOGGER.debug("[VehicleHelper] Found hasWeapon() method");
                } catch (NoSuchMethodException e) {
                    LOGGER.debug("[VehicleHelper] hasWeapon() method not found");
                }

                // Get hasWeapon(int) method
                try {
                    hasWeaponWithSeatMethod = vehicleEntityClass.getMethod("hasWeapon", int.class);
                    LOGGER.debug("[VehicleHelper] Found hasWeapon(int) method");
                } catch (NoSuchMethodException e) {
                    LOGGER.debug("[VehicleHelper] hasWeapon(int) method not found");
                }

                // Get getSeatIndex(Entity) method
                try {
                    getSeatIndexMethod = vehicleEntityClass.getMethod("getSeatIndex", Entity.class);
                    LOGGER.debug("[VehicleHelper] Found getSeatIndex(Entity) method");
                } catch (NoSuchMethodException e) {
                    LOGGER.debug("[VehicleHelper] getSeatIndex(Entity) method not found");
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[VehicleHelper] Reflection initialization failed", e);
        }
    }

    /**
     * Check if the player is riding a Superbwarfare vehicle.
     */
    public static boolean isRidingSuperbwarfareVehicle(Player player) {
        if (player == null || !player.isPassenger()) return false;
        if (!isSuperbwarfareLoaded()) return false;

        initReflection();

        if (vehicleEntityClass == null) return false;

        Entity vehicle = player.getVehicle();
        return vehicle != null && vehicleEntityClass.isInstance(vehicle);
    }

    /**
     * Check if the vehicle the player is riding has a weapon slot for the player's seat.
     *
     * @param player The player riding the vehicle
     * @return true if the player's seat has a weapon, false otherwise
     */
    public static boolean hasWeaponSlotForPlayer(Player player) {
        if (player == null || !player.isPassenger()) return false;
        if (!isSuperbwarfareLoaded()) return false;

        initReflection();

        if (vehicleEntityClass == null) return false;

        Entity vehicle = player.getVehicle();
        if (vehicle == null || !vehicleEntityClass.isInstance(vehicle)) return false;

        try {
            // First try to get the seat index and check if that seat has a weapon
            if (getSeatIndexMethod != null && hasWeaponWithSeatMethod != null) {
                int seatIndex = (int) getSeatIndexMethod.invoke(vehicle, player);
                boolean hasWeapon = (boolean) hasWeaponWithSeatMethod.invoke(vehicle, seatIndex);
                LOGGER.debug("[VehicleHelper] Player seat index: {}, hasWeapon: {}", seatIndex, hasWeapon);
                return hasWeapon;
            }

            // Fallback: check if vehicle has any weapon
            if (hasWeaponMethod != null) {
                boolean hasWeapon = (boolean) hasWeaponMethod.invoke(vehicle);
                LOGGER.debug("[VehicleHelper] Vehicle hasWeapon: {}", hasWeapon);
                return hasWeapon;
            }
        } catch (Exception e) {
            LOGGER.debug("[VehicleHelper] Error checking weapon slot", e);
        }

        return false;
    }

    /**
     * Check if the vehicle the player is riding has any weapon at all.
     */
    public static boolean vehicleHasAnyWeapon(Player player) {
        if (player == null || !player.isPassenger()) return false;
        if (!isSuperbwarfareLoaded()) return false;

        initReflection();

        if (vehicleEntityClass == null) return false;

        Entity vehicle = player.getVehicle();
        if (vehicle == null || !vehicleEntityClass.isInstance(vehicle)) return false;

        try {
            if (hasWeaponMethod != null) {
                return (boolean) hasWeaponMethod.invoke(vehicle);
            }
        } catch (Exception e) {
            LOGGER.debug("[VehicleHelper] Error checking if vehicle has any weapon", e);
        }

        return false;
    }

    /**
     * Get the seat index of a player in a vehicle.
     * Returns -1 if not in a vehicle or error.
     */
    public static int getPlayerSeatIndex(Player player) {
        if (player == null || !player.isPassenger()) return -1;
        if (!isSuperbwarfareLoaded()) return -1;

        initReflection();

        if (vehicleEntityClass == null || getSeatIndexMethod == null) return -1;

        Entity vehicle = player.getVehicle();
        if (vehicle == null || !vehicleEntityClass.isInstance(vehicle)) return -1;

        try {
            return (int) getSeatIndexMethod.invoke(vehicle, player);
        } catch (Exception e) {
            LOGGER.debug("[VehicleHelper] Error getting seat index", e);
        }

        return -1;
    }
}

