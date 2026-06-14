package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.client.render.RenderResourceCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Custom health bar overlay that renders at the bottom center of the screen.
 * Uses Blaze3D for sub-pixel precision rendering.
 * Features:
 * - Experience icon (grayscale) on the far left
 * - Experience level number
 * - Vertical separator line
 * - Plus icon on the left of health section
 * - Health progress bar in the middle (with sub-pixel precision)
 * - Health number on the right
 * - Smooth interpolation animations
 * - Color changes based on health state
 * - Damage indicator animation
 */
public class CustomHealthOverlay implements IGuiOverlay {

    // ==================== Configurable Size Parameters ====================
    // Health bar size parameters (base reference) - now using float for precision
    public static float HEALTH_BAR_WIDTH = 60.0f;        // Width of the health bar
    public static float HEALTH_BAR_HEIGHT = 3.0f;        // Height of the health bar (thinner)

    // Scale factor based on health bar height (original height was 10)
    private static final float BASE_HEIGHT = 10.0f;

    // Plus icon size parameters (will be scaled based on health bar height)
    public static float PLUS_ICON_SCALE = 2.0f;     // Additional scale multiplier for plus icon (larger)

    // Health number parameters
    public static float NUMBER_SCALE = 2.5f;        // Additional scale multiplier for number (larger)

    // Layout parameters - now using float for sub-pixel positioning
    public static float ELEMENT_SPACING = 3.0f;           // Spacing between elements (scaled)
    public static float VERTICAL_OFFSET = 22.0f;          // Offset from bottom of screen (same as hotbar position)

    // Sub-pixel offset for fine-tuning position (range: -1.0 to 1.0)
    public static float BAR_Y_OFFSET = -0.5f;             // Fine-tune vertical position of the bar
    public static float PLUS_Y_OFFSET = -0.5f;             // Fine-tune vertical position of plus icon

    // ==================== Experience Section Parameters ====================
    // Vertical separator line parameters
    public static float SEPARATOR_WIDTH = 1.0f;           // Width of the vertical separator line
    public static float SEPARATOR_HEIGHT = 10.0f;         // Height of the vertical separator line
    public static float SEPARATOR_ALPHA = 0.65f;          // Alpha for separator (65% opacity)
    public static float SEPARATOR_SPACING = 4.0f;         // Spacing around the separator

    // Experience level number parameters (uses same scale as health number for consistency)
    public static float EXP_NUMBER_SCALE = NUMBER_SCALE;  // Scale for experience level number (matches health number)

    // Experience icon parameters (container slot 18 item)
    public static float EXP_ICON_SIZE = 12.0f;            // Size of the experience icon
    public static float EXP_ICON_SPACING = 2.0f;          // Spacing between icon and level number
    public static float EXP_ICON_OFFSET_X = 1.0f;         // Offset to shift icon right (reduced by 1 pixel)

    // ==================== Animation Speed Parameters ====================
    // All animation speeds are in "units per second" for frame-rate independence

    // Health bar interpolation - how fast the health bar catches up to actual health
    // Lower value = smoother/slower animation, Higher value = faster animation
    public static float HEALTH_LERP_SPEED = 8.0f;   // Smoother interpolation

    // Damage indicator delay - how long the damage bar stays at full size before starting to fade (in seconds)
    public static float DAMAGE_INDICATOR_DELAY = 0.3f;   // 0.3 second delay (shortened for responsiveness)

    // Damage indicator fade speed - how fast the red damage portion shrinks
    // Value represents percentage of max health per second
    public static float DAMAGE_FADE_SPEED = 1.5f;   // Faster fade for quicker health bar response

    // Recovery color duration - how long the yellow color shows after healing (in seconds)
    public static float RECOVERY_COLOR_DURATION = 0.5f;

    // Animation smoothness control - higher = smoother interpolation
    public static float ANIMATION_SMOOTHNESS = 1.0f;  // Multiplier for animation smoothness

    // ==================== Frame Rate Control ====================
    // These ensure consistent animation regardless of frame rate
    public static float MIN_DELTA_TIME = 0.0001f;   // Minimum delta (10000 FPS cap)
    public static float MAX_DELTA_TIME = 0.1f;      // Maximum delta (10 FPS floor) - prevents huge jumps on lag spikes

    // Color constants (ARGB format)
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_RED = 0xFFFF4444;
    private static final int COLOR_YELLOW = 0xFFFFFF44;
    private static final int COLOR_DAMAGE_RED = 0x99FF0000; // Semi-transparent red
    private static final int COLOR_BACKGROUND = 0x80000000; // Semi-transparent black

    // Health threshold
    private static final float LOW_HEALTH_THRESHOLD = 0.4f; // 40%

    // ==================== Custom Icon Configuration ====================
    // Mod ID for resource location
    private static final String MOD_ID = "customui";

    // Custom icon texture base path
    private static final String ICON_TEXTURE_PATH = "textures/gui/icons/";

    // ==================== Font Rendering Configuration ====================
    // Modern UI automatically provides anti-aliased font rendering
    // No custom font configuration needed - just use mc.font

    // Enable/disable text drop shadow
    public static boolean TEXT_DROP_SHADOW = true;


    // Map of item IDs to their custom icon texture names
    // Key: full item ID (e.g., "minecraft:feather")
    // Value: texture file name without extension (e.g., "feather")
    private static final Map<String, String> CUSTOM_ICON_MAP = new HashMap<>();

    // GC optimization: cached string representations
    private int cachedExpLevel = -1;
    private String cachedExpLevelText = "0";
    private int cachedMaxHealthInt = -1;
    private String cachedMaxHealthText = "0";

    // Map of item IDs to their custom PLUS icon texture names (for container slot 27)
    // Key: full item ID (e.g., "minecraft:golden_apple")
    // Value: texture file name without extension (e.g., "golden_apple_plus")
    private static final Map<String, String> PLUS_ICON_MAP = new HashMap<>();

    static {
        // Initialize custom icon mappings
        // Feather - 羽毛
        CUSTOM_ICON_MAP.put("minecraft:feather", "feather");
        // Chest Minecart - 运输矿车
        CUSTOM_ICON_MAP.put("minecraft:chest_minecart", "chest_minecart");
        // Recovery Compass - 追溯指针
        CUSTOM_ICON_MAP.put("minecraft:recovery_compass", "recovery_compass");
        // 反载具地雷
        CUSTOM_ICON_MAP.put("superbwarfare:tm_62", "tm_62");
        // 阔剑
        CUSTOM_ICON_MAP.put("superbwarfare:claymore_mine", "claymore_mine");

        // Add more custom icon mappings here as needed:
        // CUSTOM_ICON_MAP.put("minecraft:item_id", "texture_name");
        // CUSTOM_ICON_MAP.put("modid:item_id", "texture_name");

        // Initialize PLUS icon mappings (container slot 27)
        // When the item in slot 27 matches, use a custom texture instead of the default plus icon
        // Example: PLUS_ICON_MAP.put("minecraft:golden_apple", "golden_apple_plus");
        PLUS_ICON_MAP.put("minecraft:chainmail_chestplate", "plus_level_2");
        PLUS_ICON_MAP.put("minecraft:diamond_chestplate", "plus_level_3");

        // Add more plus icon mappings here as needed
    }

    public static Set<ResourceLocation> getCustomIconTextures() {
        Set<ResourceLocation> textures = new LinkedHashSet<>();
        for (String iconName : CUSTOM_ICON_MAP.values()) {
            textures.add(RenderResourceCache.getOrCreate(MOD_ID, ICON_TEXTURE_PATH + iconName + ".png"));
        }
        for (String iconName : PLUS_ICON_MAP.values()) {
            textures.add(RenderResourceCache.getOrCreate(MOD_ID, ICON_TEXTURE_PATH + iconName + ".png"));
        }
        return textures;
    }

    // Animation state
    private float displayedHealth = -1;
    private float previousHealth = -1;
    private float previousMaxHealth = -1; // Track max health changes for sync
    private float damageIndicator = 0;
    private float damageIndicatorDelayTimer = 0; // Timer for delay before damage indicator starts fading
    private long lastUpdateTimeNanos = -1;  // Use nanos for higher precision
    private float recoveryColorTimer = 0; // Timer for showing recovery color

    // Experience level 100 effect animation state
    private int previousExpLevel = -1;           // Previous experience level for detecting change to 100
    private float exp100EffectTimer = 0;         // Timer for the circular expansion effect
    private boolean exp100EffectActive = false;  // Whether the effect is currently playing

    // Experience level 100 fade animation state (fade out then fade in)
    private float exp100FadeTimer = 0;           // Timer for fade animation
    private boolean exp100FadeActive = false;    // Whether fade animation is active
    private boolean exp100FadingOut = true;      // True = fading out, False = fading in
    private int exp100FadeCycle = 0;             // Current fade cycle (0 to EXP_100_FADE_CYCLES-1)

    // ==================== Experience 100 Effect Configuration ====================
    // Duration of the circular expansion effect (in seconds)
    public static float EXP_100_EFFECT_DURATION = 0.8f;
    // Maximum radius of the expanding circle
    public static float EXP_100_EFFECT_MAX_RADIUS = 30.0f;
    // Starting radius of the circle
    public static float EXP_100_EFFECT_START_RADIUS = 2.0f;
    // Effect color (white)
    private static final int EXP_100_EFFECT_COLOR = 0xFFFFFFFF;
    // Ring thickness
    public static float EXP_100_EFFECT_RING_WIDTH = 2.0f;

    // ==================== Experience 100 Fade Animation Configuration ====================
    // Duration of fade out phase (in seconds)
    public static float EXP_100_FADE_OUT_DURATION = 0.5f;
    // Duration of fade in phase (in seconds)
    public static float EXP_100_FADE_IN_DURATION = 0.5f;
    // Number of fade cycles (fade out + fade in = 1 cycle)
    public static int EXP_100_FADE_CYCLES = 5;
    // Base alpha multiplier for icon and hotkey when exp=100 (80% = 0.8)
    public static float EXP_100_BASE_ALPHA = 0.8f;
    // Icon scale reduction when exp=100 (10% smaller = 0.9)
    public static float EXP_100_ICON_SCALE = 0.9f;

    // ==================== Hotkey Hint Configuration ====================
    // Background color for hotkey hint (pure white)
    private static final int HOTKEY_BG_COLOR = 0xFFFFFFFF;
    // Text color for hotkey hint (black)
    private static final int HOTKEY_TEXT_COLOR = 0xFF000000;
    // Padding around hotkey text
    public static float HOTKEY_PADDING = 2.0f;
    // Spacing between exp icon and hotkey hint
    public static float HOTKEY_SPACING = 4.0f;
    // Scale for hotkey text (smaller than normal)
    public static float HOTKEY_SCALE = 0.75f;

    // ==================== Economy Display Configuration ====================
    // Economy display position (independent, left of health bar)
    public static float ECONOMY_SPACING = 8.0f;          // Spacing between economy and exp section
    public static float ECONOMY_TEXT_SCALE = 0.8f;       // Scale for economy text
    public static float ECONOMY_UNDERLINE_HEIGHT = 1.0f; // Height of the underline
    public static float ECONOMY_EFFECT_DURATION = 0.8f;  // Duration of economy change effect (seconds)
    public static float ECONOMY_EFFECT_HEIGHT = 40.0f;   // Max height of the effect glow (increased)

    // Economy colors
    private static final int ECONOMY_TEXT_COLOR = 0xFFFFFFFF;     // White
    private static final int ECONOMY_INCREASE_COLOR = 0xFF00FF00;  // Green
    private static final int ECONOMY_DECREASE_COLOR = 0xFFFF0000;  // Red
    private static final int ECONOMY_UNDERLINE_COLOR = 0xFFFFFFFF; // White

    // Economy animation state
    private int previousEconomy = -1;           // Previous economy value for detecting changes
    private float economyEffectTimer = 0;       // Timer for economy change effect
    private boolean economyEffectActive = false; // Whether the effect is active
    private boolean economyIncreased = false;    // True if economy increased, false if decreased

    // ==================== Air/Oxygen Display Configuration ====================
    // Air display is shown to the left of experience level, similar style to exp level
    public static float AIR_NUMBER_SCALE = 2.5f;         // Scale for air number (same as exp number)
    public static float AIR_SPACING = 4.0f;              // Spacing between air display and exp section
    public static float AIR_LOW_THRESHOLD = 0.2f;        // Threshold for low air warning (20%)
    public static float AIR_FLASH_SPEED = 8.0f;          // Speed of red flash animation (Hz)
    public static float AIR_FADE_SPEED = 2.0f;           // Speed of fade in/out animation
    public static float AIR_MIN_ALPHA = 0.0f;            // Minimum alpha when fading out (invisible)
    public static float AIR_MAX_ALPHA = 1.0f;            // Maximum alpha when visible

    // Air display colors
    private static final int AIR_TEXT_COLOR = 0xFFFFFFFF;      // White for normal
    private static final int AIR_LOW_COLOR = 0xFFFF4444;       // Red for low air warning

    // Air animation state
    private float airDisplayAlpha = 0.0f;       // Current alpha of air display (0 = hidden, 1 = visible)
    private float airFlashTimer = 0.0f;         // Timer for red flash animation

    // Cached values for performance (avoid recalculating every frame)
    private float cachedPlusIconSize = -1;
    private float cachedPlusArmWidth = -1;
    private float cachedPlusArmLength = -1;
    private float cachedScaleFactor = -1;
    private float lastBarHeight = -1;
    private float lastPlusScale = -1;

    /**
     * Calculate the scale factor based on health bar height.
     */
    private float getScaleFactor() {
        if (cachedScaleFactor < 0 || lastBarHeight != HEALTH_BAR_HEIGHT) {
            lastBarHeight = HEALTH_BAR_HEIGHT;
            cachedScaleFactor = HEALTH_BAR_HEIGHT / BASE_HEIGHT;
        }
        return cachedScaleFactor;
    }

    /**
     * Update cached plus icon sizes if parameters changed.
     */
    private void updatePlusIconCache() {
        if (lastPlusScale != PLUS_ICON_SCALE || lastBarHeight != HEALTH_BAR_HEIGHT) {
            lastPlusScale = PLUS_ICON_SCALE;
            lastBarHeight = HEALTH_BAR_HEIGHT;
            float scale = getScaleFactor();
            cachedPlusIconSize = Math.max(4.0f, 16.0f * scale * PLUS_ICON_SCALE);
            cachedPlusArmWidth = Math.max(1.0f, 4.0f * scale * PLUS_ICON_SCALE);
            cachedPlusArmLength = Math.max(2.0f, 12.0f * scale * PLUS_ICON_SCALE);
        }
    }

    /**
     * Get scaled plus icon size.
     */
    private float getScaledPlusIconSize() {
        updatePlusIconCache();
        return cachedPlusIconSize;
    }

    /**
     * Get scaled plus icon arm width.
     */
    private float getScaledPlusArmWidth() {
        return cachedPlusArmWidth;
    }

    /**
     * Get scaled plus icon arm length.
     */
    private float getScaledPlusArmLength() {
        return cachedPlusArmLength;
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (BackgroundGuard.shouldSkip()) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null || mc.options.hideGui) {
            return;
        }

        // Only show in Survival and Adventure mode
        if (mc.gameMode != null) {
            GameType gameType = mc.gameMode.getPlayerMode();
            if (gameType != GameType.SURVIVAL && gameType != GameType.ADVENTURE) {
                return;
            }
        }

        // Get current health values
        float currentHealth = player.getHealth();
        float maxHealth = player.getMaxHealth();

        // Initialize on first render
        if (displayedHealth < 0) {
            displayedHealth = currentHealth;
            previousHealth = currentHealth;
            previousMaxHealth = maxHealth;
            damageIndicator = currentHealth;
            lastUpdateTimeNanos = System.nanoTime();
        }

        // Detect max health changes (e.g., attribute modifiers, effects)
        // Clamp all tracked values to prevent stale data beyond the new max
        if (previousMaxHealth > 0 && Math.abs(maxHealth - previousMaxHealth) > 0.01f) {
            displayedHealth = Math.min(displayedHealth, maxHealth);
            damageIndicator = Math.min(damageIndicator, maxHealth);
            previousHealth = Math.min(previousHealth, maxHealth);
            // If max health decreased significantly, snap to current health
            if (maxHealth < previousMaxHealth) {
                displayedHealth = Math.min(displayedHealth, currentHealth);
                damageIndicator = Math.min(damageIndicator, currentHealth);
            }
            previousMaxHealth = maxHealth;
        }

        // Calculate delta time for frame-rate independent animation (using nanos for precision)
        long currentTimeNanos = System.nanoTime();
        float deltaTime = (currentTimeNanos - lastUpdateTimeNanos) / 1_000_000_000.0f;
        lastUpdateTimeNanos = currentTimeNanos;

        // Clamp deltaTime using configurable parameters for consistent animation across frame rates
        deltaTime = Math.max(MIN_DELTA_TIME, Math.min(deltaTime, MAX_DELTA_TIME));

        // Detect health changes
        boolean isRecovering = currentHealth > previousHealth;
        boolean isDamaged = currentHealth < previousHealth;

        // Update recovery color timer
        if (isRecovering) {
            recoveryColorTimer = RECOVERY_COLOR_DURATION;
        } else if (recoveryColorTimer > 0) {
            recoveryColorTimer = Math.max(0, recoveryColorTimer - deltaTime);
        }

        // Handle damage indicator - set to previous health when damaged
        if (isDamaged) {
            // Only update if previous health was higher (actual damage taken)
            if (previousHealth > damageIndicator) {
                damageIndicator = previousHealth;
            }
            // Reset delay timer whenever damage is taken
            damageIndicatorDelayTimer = DAMAGE_INDICATOR_DELAY;
        }

        // Update delay timer
        if (damageIndicatorDelayTimer > 0) {
            damageIndicatorDelayTimer = Math.max(0, damageIndicatorDelayTimer - deltaTime);
        }

        // Fade damage indicator over time (shrink towards displayed health)
        // Only start fading after delay timer expires
        // Use time-based linear fade for consistent speed across frame rates
        if (damageIndicator > displayedHealth && damageIndicatorDelayTimer <= 0) {
            // Fade by a fixed percentage of max health per second
            float fadeAmount = maxHealth * DAMAGE_FADE_SPEED * deltaTime;
            damageIndicator = Math.max(displayedHealth, damageIndicator - fadeAmount);
        }

        // Smooth interpolation of displayed health using exponential decay
        // This ensures frame-rate independent smooth animation
        // Formula: newValue = target + (current - target) * e^(-speed * deltaTime)
        // Simplified: newValue = lerp(current, target, 1 - e^(-speed * deltaTime))
        float healthDiff = currentHealth - displayedHealth;
        if (Math.abs(healthDiff) > 0.001f) {  // Higher precision threshold
            // Exponential interpolation factor - ensures same visual result regardless of frame rate
            float effectiveSpeed = HEALTH_LERP_SPEED * ANIMATION_SMOOTHNESS;
            float lerpFactor = 1.0f - (float) Math.exp(-effectiveSpeed * deltaTime);
            displayedHealth += healthDiff * lerpFactor;

            // Snap to target if very close to avoid floating point issues
            if (Math.abs(currentHealth - displayedHealth) < 0.01f) {
                displayedHealth = currentHealth;
            }
        } else {
            displayedHealth = currentHealth;
        }

        // Store current as previous for next frame
        previousHealth = currentHealth;

        // Check if at full health and animation is complete
        boolean isFullHealth = currentHealth >= maxHealth;
        boolean animationComplete = Math.abs(displayedHealth - currentHealth) < 0.1f
                && damageIndicator <= displayedHealth
                && recoveryColorTimer <= 0;

        // Determine if we should hide the bar (no animation, immediate hide)
        boolean isHidingBar = isFullHealth && animationComplete;

        // Determine health bar color (yellow has highest priority, then red, then white)
        int barColor;
        float healthPercent = currentHealth / maxHealth;
        if (recoveryColorTimer > 0) {
            // Yellow for recovery (highest priority)
            barColor = COLOR_YELLOW;
        } else if (healthPercent < LOW_HEALTH_THRESHOLD) {
            // Red for low health
            barColor = COLOR_RED;
        } else {
            // White for normal
            barColor = COLOR_WHITE;
        }

        // Get scaled sizes (now float for precision)
        float plusIconSize = getScaledPlusIconSize();
        float scaledSpacing = Math.max(2.0f, ELEMENT_SPACING * getScaleFactor());

        // Calculate positions - center at the bottom of screen (original hotbar position)
        // Using float for sub-pixel precision
        float centerY = screenHeight - VERTICAL_OFFSET - HEALTH_BAR_HEIGHT / 2.0f;

        // Get experience level
        int expLevel = player.experienceLevel;

        // Detect when experience level changes to 100 (trigger effect)
        if (previousExpLevel != -1 && previousExpLevel != 100 && expLevel == 100) {
            // Start the circular expansion effect
            exp100EffectActive = true;
            exp100EffectTimer = 0;
            // Start the fade animation (fade out then fade in)
            exp100FadeActive = true;
            exp100FadeTimer = 0;
            exp100FadingOut = true;
            exp100FadeCycle = 0;  // Start from first cycle
        }

        // Interrupt fade animation if exp level is no longer 100
        if (exp100FadeActive && expLevel != 100) {
            exp100FadeActive = false;
            exp100FadeTimer = 0;
            exp100FadeCycle = 0;
        }
        previousExpLevel = expLevel;

        // Update experience 100 effect animation
        if (exp100EffectActive) {
            exp100EffectTimer += deltaTime;
            if (exp100EffectTimer >= EXP_100_EFFECT_DURATION) {
                exp100EffectActive = false;
                exp100EffectTimer = 0;
            }
        }

        // Update experience 100 fade animation
        if (exp100FadeActive) {
            exp100FadeTimer += deltaTime;
            if (exp100FadingOut) {
                // Fading out phase
                if (exp100FadeTimer >= EXP_100_FADE_OUT_DURATION) {
                    // Switch to fading in
                    exp100FadingOut = false;
                    exp100FadeTimer = 0;
                }
            } else {
                // Fading in phase
                if (exp100FadeTimer >= EXP_100_FADE_IN_DURATION) {
                    // Completed one cycle
                    exp100FadeCycle++;
                    if (exp100FadeCycle >= EXP_100_FADE_CYCLES) {
                        // All cycles complete, animation done
                        exp100FadeActive = false;
                        exp100FadeTimer = 0;
                        exp100FadeCycle = 0;
                    } else {
                        // Start next cycle (fade out again)
                        exp100FadingOut = true;
                        exp100FadeTimer = 0;
                    }
                }
            }
        }

        // Calculate fade alpha for exp100 animation
        // When exp=100, base alpha is 80% (EXP_100_BASE_ALPHA), then the fade animation modulates it
        float exp100FadeAlpha = 1.0f;
        if (expLevel == 100) {
            if (exp100FadeActive) {
                if (exp100FadingOut) {
                    // Fade out: 1.0 -> 0.0
                    exp100FadeAlpha = 1.0f - (exp100FadeTimer / EXP_100_FADE_OUT_DURATION);
                } else {
                    // Fade in: 0.0 -> 1.0
                    exp100FadeAlpha = exp100FadeTimer / EXP_100_FADE_IN_DURATION;
                }
                exp100FadeAlpha = Math.max(0.0f, Math.min(1.0f, exp100FadeAlpha));
            }
            // Apply base 80% alpha
            exp100FadeAlpha *= EXP_100_BASE_ALPHA;
        }

        // Get item from container slot 18 (armor slot or specific inventory slot)
        ItemStack expIconItem = player.getInventory().getItem(18);

        // Check if exp section should be shown (only when slot 18 has an item)
        boolean showExpSection = !expIconItem.isEmpty();

        // Get item from container slot 27 for plus icon customization
        ItemStack plusIconItem = player.getInventory().getItem(27);

        // Get item from container slot 9 for economy display
        ItemStack economyItem = player.getInventory().getItem(9);
        int economyValue = getEconomyValue(economyItem);
        boolean showEconomy = economyValue >= 0; // -1 means don't show

        // Update economy effect animation
        updateEconomyEffect(economyValue, deltaTime);

        // ==================== Air/Oxygen Display Logic ====================
        // Get player's air supply (max is usually 300 ticks = 15 seconds)
        // Air can go negative when drowning (damage ticks), so we clamp to 0
        int maxAir = player.getMaxAirSupply();
        int rawAir = player.getAirSupply();
        int currentAir = Math.max(0, rawAir);

        // Determine if player is underwater (air not full)
        boolean isUnderwater = rawAir < maxAir;

        // Update air display alpha (fade in/out animation)
        // Only update if underwater or currently visible
        if (isUnderwater) {
            // Fade in when underwater
            airDisplayAlpha = Math.min(AIR_MAX_ALPHA, airDisplayAlpha + deltaTime * AIR_FADE_SPEED);
        } else if (airDisplayAlpha > 0.0f) {
            // Fade out when at full air (100%)
            airDisplayAlpha = Math.max(AIR_MIN_ALPHA, airDisplayAlpha - deltaTime * AIR_FADE_SPEED);
        }

        // Determine if air display should be rendered
        boolean shouldShowAir = !AlphaFadeHelper.shouldSkipRender(airDisplayAlpha);

        // Only calculate air percentage and flash if we need to display
        float airPercent = 1.0f;
        int airDisplayPercent = 100;
        float airTextWidth = 0;
        float airNumberScale = 0;

        if (shouldShowAir) {
            // Calculate air percentage, clamped to 0-1 range
            airPercent = maxAir > 0 ? Math.max(0.0f, Math.min(1.0f, (float) currentAir / maxAir)) : 1.0f;

            // Update air flash timer for low air warning
            if (airPercent < AIR_LOW_THRESHOLD) {
                airFlashTimer += deltaTime * AIR_FLASH_SPEED;
            } else {
                airFlashTimer = 0;
            }

            // Calculate air display percentage (0-100), clamped
            airDisplayPercent = Math.max(0, Math.min(100, Math.round(airPercent * 100)));

            // Calculate air text width for positioning
            airNumberScale = getScaleFactor() * AIR_NUMBER_SCALE;
            String airText = airDisplayPercent + "%";
            airTextWidth = mc.font.width(airText) * airNumberScale;
        } else {
            // Reset flash timer when not visible
            airFlashTimer = 0;
        }

        // Calculate experience section width (only if showing)
        float expLevelNumberScale = getScaleFactor() * EXP_NUMBER_SCALE;
        // GC optimization: cache expLevel text
        if (expLevel != cachedExpLevel) {
            cachedExpLevel = expLevel;
            cachedExpLevelText = String.valueOf(expLevel);
        }
        String expLevelText = cachedExpLevelText;
        float expLevelWidth = mc.font.width(expLevelText) * expLevelNumberScale;
        float expSectionWidth = showExpSection
            ? (EXP_ICON_SIZE + EXP_ICON_SPACING + expLevelWidth + SEPARATOR_SPACING + SEPARATOR_WIDTH + SEPARATOR_SPACING)
            : 0;

        // Calculate health section width
        // GC optimization: cache maxHealth text
        int maxHealthCeil = (int) Math.ceil(maxHealth);
        if (maxHealthCeil != cachedMaxHealthInt) {
            cachedMaxHealthInt = maxHealthCeil;
            cachedMaxHealthText = String.valueOf(maxHealthCeil);
        }
        float healthNumberWidth = mc.font.width(cachedMaxHealthText) + 2.0f;
        float healthSectionWidth = plusIconSize + scaledSpacing + HEALTH_BAR_WIDTH + scaledSpacing + healthNumberWidth;
        float healthSectionWidthHidden = plusIconSize + scaledSpacing + healthNumberWidth;

        // Calculate total width based on whether bar is hidden
        // Note: Air section width is NOT included in total width calculation
        // because air display position is calculated relative to exp section
        float totalWidth;
        if (isHidingBar) {
            totalWidth = expSectionWidth + healthSectionWidthHidden;
        } else {
            totalWidth = expSectionWidth + healthSectionWidth;
        }

        // Calculate starting X to center the entire UI (health bar centered)
        float startX = (screenWidth - totalWidth) / 2.0f;

        // Calculate positions for experience section (left side, only if showing)
        float expIconX = startX;
        float expLevelX = expIconX + EXP_ICON_SIZE + EXP_ICON_SPACING;
        float separatorX = expLevelX + expLevelWidth + SEPARATOR_SPACING;

        // Air display is always anchored to the left of exp icon with AIR_SPACING gap.
        // expIconX marks the start of the exp section regardless of whether it's visible.
        float airDisplayX = expIconX - AIR_SPACING - airTextWidth;

        // Calculate positions for health section (right side of separator, or at start if no exp section)
        float healthSectionStartX = showExpSection ?
            (separatorX + SEPARATOR_WIDTH + SEPARATOR_SPACING) : startX;
        float plusX = healthSectionStartX;
        float barX = plusX + plusIconSize + scaledSpacing;
        float numberX;

        if (isHidingBar) {
            numberX = plusX + plusIconSize + scaledSpacing;
        } else {
            numberX = barX + HEALTH_BAR_WIDTH + scaledSpacing;
        }

        // Sub-pixel precision positioning
        float plusY = centerY - plusIconSize / 2.0f + PLUS_Y_OFFSET;
        float barY = centerY - HEALTH_BAR_HEIGHT / 2.0f + BAR_Y_OFFSET;
        float separatorY = centerY - SEPARATOR_HEIGHT / 2.0f;

        // Determine alpha for health elements
        float plusAlpha = isHidingBar ? 0.7f : 0.95f;
        float numberAlpha = isHidingBar ? 0.7f : 0.95f;

        // Determine alpha for experience elements
        // Experience icon and number are semi-transparent only when exp level is NOT 100
        float expAlpha = (expLevel != 100) ? 0.7f : 0.95f;

        // Apply fade animation alpha only to the icon and hotkey when exp level is 100
        float expIconAlpha = expAlpha;
        if (expLevel == 100) {
            expIconAlpha = exp100FadeAlpha;  // This already includes EXP_100_BASE_ALPHA
        }

        // Use sub-pixel precision rendering with Blaze3D vertex buffers
        renderWithSubPixelPrecision(guiGraphics, plusX, plusY, barX, barY, numberX, centerY,
                plusIconSize, maxHealth, displayedHealth, damageIndicator,
                barColor, plusAlpha, numberAlpha, isHidingBar, currentHealth,
                expIconX, expLevelX, separatorX, separatorY, expLevel, expIconItem, expLevelNumberScale, expIconAlpha,
                plusIconItem, exp100FadeAlpha, exp100FadeActive, expAlpha,
                economyValue, showEconomy, showExpSection, screenWidth,
                // Air display parameters
                airDisplayX, airDisplayPercent, airPercent, airDisplayAlpha, airFlashTimer, airNumberScale, shouldShowAir);
    }

    /**
     * Render with sub-pixel precision using Blaze3D vertex buffers.
     * This allows floating-point coordinates for smoother positioning.
     */
    private void renderWithSubPixelPrecision(GuiGraphics guiGraphics, float plusX, float plusY, float barX, float barY,
                                              float numberX, float centerY, float plusIconSize, float maxHealth,
                                              float displayedHealth, float damageIndicator, int barColor,
                                              float plusAlpha, float numberAlpha, boolean isHidingBar, float currentHealth,
                                              float expIconX, float expLevelX, float separatorX, float separatorY,
                                              int expLevel, ItemStack expIconItem, float expLevelScale, float expIconAlpha,
                                              ItemStack plusIconItem, float exp100FadeAlpha, boolean exp100FadeActive,
                                              float expNumberAlpha,
                                              int economyValue, boolean showEconomy, boolean showExpSection,
                                              int screenWidth,
                                              // Air display parameters
                                              float airDisplayX, int airDisplayPercent, float airPercent,
                                              float airAlpha, float airFlashTimer, float airNumberScale, boolean showAir) {
        Minecraft mc = Minecraft.getInstance();

        // Apply UI sway effect
        UISwayHelper swayHelper = UISwayHelper.getInstance();
        float swayOffsetX = swayHelper.getOffsetX();
        float swayOffsetY = swayHelper.getOffsetY();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(swayOffsetX, swayOffsetY, 0);
        String customPlusIcon = resolveCustomPlusIconName(plusIconItem);

        // Render economy display independently (fixed position, left side of screen center)
        if (showEconomy) {
            // Independent position for economy - left of the health bar area
            float economyX = screenWidth / 2.0f - 100.0f; // Fixed position left of center (moved 20px more)
            renderEconomyDisplay(guiGraphics, economyX, centerY, economyValue, mc.font);
        }

        // Render air/oxygen display (to the left of experience level)
        if (showAir && airAlpha > 0.01f) {
            renderAirDisplay(guiGraphics, airDisplayX, centerY, airDisplayPercent, airPercent,
                           airAlpha, airFlashTimer, airNumberScale);
        }

        // Only render exp section if showExpSection is true
        if (showExpSection) {
            // Calculate icon scale: 10% smaller when exp=100
            float iconScale = (expLevel == 100) ? EXP_100_ICON_SCALE : 1.0f;

            // Render experience icon with experience-specific alpha (fade applies here)
            // Apply offset to shift icon slightly to the right
            renderExpIcon(guiGraphics, expIconX + EXP_ICON_OFFSET_X, centerY, expIconItem, expIconAlpha, iconScale);

            // Render experience level number with experience-specific alpha (no fade)
            renderExpLevelNumber(guiGraphics, expLevelX, centerY, expLevel, expLevelScale, expNumberAlpha);

            // Render hotkey hint when exp level is 100 (fade applies here)
            if (expLevel == 100) {
                // exp100FadeAlpha already includes EXP_100_BASE_ALPHA (80%)
                // Pass the offset icon position for proper alignment
                renderHotkeyHint(guiGraphics, expIconX + EXP_ICON_OFFSET_X, centerY, exp100FadeAlpha);
            }

            // Render experience 100 circular expansion effect (if active)
            if (exp100EffectActive) {
                // Calculate center position of the experience level number
                float textWidth = mc.font.width(cachedExpLevelText) * expLevelScale;
                float effectCenterX = expLevelX + textWidth / 2.0f;

                renderExp100Effect(guiGraphics, effectCenterX, centerY);
            }
        }

        // Frosted-glass track for the health bar itself (bar only, not the panel);
        // must run before the batched builder below opens.
        boolean glassBar = !isHidingBar && com.lootmatrix.customui.client.glass.GlassPanelRenderer.drawHudPanel(
                guiGraphics, barX, barY, HEALTH_BAR_WIDTH, HEALTH_BAR_HEIGHT,
                HEALTH_BAR_HEIGHT * 0.5f, 0x50101418, 0x24FFFFFF, 1f);

        if (showExpSection || customPlusIcon == null || !isHidingBar) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            Matrix4f matrix = guiGraphics.pose().last().pose();

            if (showExpSection) {
                addSeparatorVertices(bufferBuilder, matrix, separatorX, separatorY);
            }
            if (customPlusIcon == null) {
                addPlusIconVertices(bufferBuilder, matrix, plusX, plusY, plusAlpha, plusIconSize);
            }
            if (!isHidingBar) {
                addHealthBarVertices(bufferBuilder, matrix, barX, barY, maxHealth, displayedHealth,
                        damageIndicator, barColor, glassBar);
            }

            BufferUploader.drawWithShader(bufferBuilder.end());
            RenderSystem.disableBlend();
        }

        if (customPlusIcon != null) {
            float customIconSize = plusIconSize;
            float iconY = plusY + (plusIconSize - customIconSize) / 2.0f;
            renderCustomIconScaled(guiGraphics, plusX, iconY, customPlusIcon, plusAlpha, customIconSize);
        }

        // Render health number with sub-pixel precision
        renderHealthNumber(guiGraphics, numberX, centerY, currentHealth, numberAlpha);

        // Restore matrix after sway offset
        guiGraphics.pose().popPose();
    }

    private String resolveCustomPlusIconName(ItemStack plusIconItem) {
        if (plusIconItem.isEmpty()) {
            return null;
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(plusIconItem.getItem());
        String itemIdString = itemId != null ? itemId.toString() : "";
        return PLUS_ICON_MAP.get(itemIdString);
    }

    private void addSeparatorVertices(BufferBuilder bufferBuilder, Matrix4f matrix, float x, float y) {
        addColoredQuad(bufferBuilder, matrix, x, y, x + SEPARATOR_WIDTH, y + SEPARATOR_HEIGHT,
                1.0f, 1.0f, 1.0f, SEPARATOR_ALPHA);
    }

    private void addPlusIconVertices(BufferBuilder bufferBuilder, Matrix4f matrix,
                                     float x, float y, float alpha, float iconSize) {
        int color = applyAlpha(COLOR_WHITE, alpha);
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;

        float centerX = x + iconSize / 2.0f;
        float centerY = y + iconSize / 2.0f;
        float halfArmWidth = getScaledPlusArmWidth() / 2.0f;
        float halfArmLength = getScaledPlusArmLength() / 2.0f;

        addColoredQuad(bufferBuilder, matrix,
                centerX - halfArmLength, centerY - halfArmWidth,
                centerX + halfArmLength, centerY + halfArmWidth, r, g, b, a);
        addColoredQuad(bufferBuilder, matrix,
                centerX - halfArmWidth, centerY - halfArmLength,
                centerX + halfArmWidth, centerY - halfArmWidth, r, g, b, a);
        addColoredQuad(bufferBuilder, matrix,
                centerX - halfArmWidth, centerY + halfArmWidth,
                centerX + halfArmWidth, centerY + halfArmLength, r, g, b, a);
    }

    private void addHealthBarVertices(BufferBuilder bufferBuilder, Matrix4f matrix, float x, float y,
                                      float maxHealth, float displayedHealth, float damageIndicator,
                                      int barColor, boolean glassBackdrop) {
        float barRight = x + HEALTH_BAR_WIDTH;
        float barBottom = y + HEALTH_BAR_HEIGHT;

        if (!glassBackdrop) {
            float bgR = ((COLOR_BACKGROUND >> 16) & 0xFF) / 255.0f;
            float bgG = ((COLOR_BACKGROUND >> 8) & 0xFF) / 255.0f;
            float bgB = (COLOR_BACKGROUND & 0xFF) / 255.0f;
            float bgA = ((COLOR_BACKGROUND >> 24) & 0xFF) / 255.0f;
            addColoredQuad(bufferBuilder, matrix, x, y, barRight, barBottom, bgR, bgG, bgB, bgA);
        }

        float healthPercent = Mth.clamp(displayedHealth / maxHealth, 0.0f, 1.0f);
        float healthWidth = healthPercent * HEALTH_BAR_WIDTH;
        if (damageIndicator > displayedHealth + 0.01f) {
            float damagePercent = Mth.clamp(damageIndicator / maxHealth, 0.0f, 1.0f);
            float damageWidth = damagePercent * HEALTH_BAR_WIDTH;
            if (damageWidth > healthWidth) {
                float dmgR = ((COLOR_DAMAGE_RED >> 16) & 0xFF) / 255.0f;
                float dmgG = ((COLOR_DAMAGE_RED >> 8) & 0xFF) / 255.0f;
                float dmgB = (COLOR_DAMAGE_RED & 0xFF) / 255.0f;
                float dmgA = ((COLOR_DAMAGE_RED >> 24) & 0xFF) / 255.0f;
                addColoredQuad(bufferBuilder, matrix, x + healthWidth, y, x + damageWidth, barBottom, dmgR, dmgG, dmgB, dmgA);
            }
        }

        if (healthWidth > 0.0f) {
            float fillR = ((barColor >> 16) & 0xFF) / 255.0f;
            float fillG = ((barColor >> 8) & 0xFF) / 255.0f;
            float fillB = (barColor & 0xFF) / 255.0f;
            float fillA = ((barColor >> 24) & 0xFF) / 255.0f;
            addColoredQuad(bufferBuilder, matrix, x, y, x + healthWidth, barBottom, fillR, fillG, fillB, fillA);
        }
    }

    private void addColoredQuad(BufferBuilder bufferBuilder, Matrix4f matrix,
                                float left, float top, float right, float bottom,
                                float r, float g, float b, float a) {
        bufferBuilder.vertex(matrix, left, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, top, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, left, top, 0).color(r, g, b, a).endVertex();
    }

    /**
     * Render plus icon or custom icon based on container slot 27 item.
     * If the item in slot 27 has a custom icon defined in PLUS_ICON_MAP, renders that texture.
     * Otherwise, renders the default plus icon.
     * Custom icons are scaled proportionally to match the iconSize.
     */
    private void renderPlusIconOrCustom(GuiGraphics guiGraphics, float x, float y, float alpha, float iconSize, ItemStack plusIconItem) {
        if (!plusIconItem.isEmpty()) {
            // Get item ID to check for custom plus icon
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(plusIconItem.getItem());
            String itemIdString = itemId != null ? itemId.toString() : "";

            // Check if this item has a custom plus icon
            String customIconName = PLUS_ICON_MAP.get(itemIdString);

            if (customIconName != null) {
                // Render custom icon texture instead of plus, scaled proportionally to iconSize
                // Center the icon vertically within the iconSize area
                float customIconSize = iconSize;  // Use the same size as the plus icon would be
                float iconY = y + (iconSize - customIconSize) / 2.0f;  // Center vertically
                renderCustomIconScaled(guiGraphics, x, iconY, customIconName, alpha, customIconSize);
                return;
            }
        }

        // Default: render the standard plus icon
        renderPlusIconSubPixel(guiGraphics, x, y, alpha, iconSize);
    }

    /**
     * Render hotkey hint to the left of the experience icon when exp level is 100.
     * Shows the hotbar.5 keybind in a white square with black text.
     * Extracts only the English/alphanumeric part of the key name.
     * For long text, width expands but height stays fixed. Text is truncated if too long.
     * Background is 20% smaller than original, but text size is unchanged.
     */
    private void renderHotkeyHint(GuiGraphics guiGraphics, float expIconX, float centerY, float alpha) {
        Minecraft mc = Minecraft.getInstance();

        // Get the hotbar.5 keybind (index 4, since hotbar slots are 0-8)
        String fullKeyName = mc.options.keyHotbarSlots[4].getTranslatedKeyMessage().getString();

        // Extract only the English/alphanumeric part from the key name
        String keyName = extractEnglishPart(fullKeyName);
        if (keyName.isEmpty()) {
            keyName = fullKeyName; // Fallback to full name if no English part found
        }

        // Apply size reduction for background only (81% of original = 0.9 * 0.9)
        // Text uses original scale (HOTKEY_SCALE), background is scaled smaller
        float bgScaleMultiplier = 0.81f;  // 10% + 10% reduction = 81% of original

        Font font = mc.font;

        // Maximum width to prevent overly long hints
        float maxBgWidth = 20.0f;

        // Truncate key name if too long
        String displayKeyName = keyName;
        float textWidth = font.width(displayKeyName) * HOTKEY_SCALE;
        float textHeight = font.lineHeight * HOTKEY_SCALE;
        float minWidthForText = textWidth + HOTKEY_PADDING * 2;

        // Check if text needs truncation
        if (minWidthForText * bgScaleMultiplier > maxBgWidth) {
            while (displayKeyName.length() > 1) {
                displayKeyName = displayKeyName.substring(0, displayKeyName.length() - 1);
                textWidth = font.width(displayKeyName) * HOTKEY_SCALE;
                minWidthForText = textWidth + HOTKEY_PADDING * 2;
                if (minWidthForText * bgScaleMultiplier <= maxBgWidth) {
                    break;
                }
            }
        }

        // Calculate background dimensions with additional scaling
        // - Height is always fixed based on text height (square baseline)
        float baseHeight = textHeight + HOTKEY_PADDING * 2;
        float bgHeight = baseHeight * bgScaleMultiplier;
        float bgWidth;
        if (textWidth > textHeight) {
            // Long text: width expands but is capped
            bgWidth = Math.min(maxBgWidth, minWidthForText * bgScaleMultiplier);
        } else {
            // Normal/short text: make it a square, but ensure it fits text
            bgWidth = Math.max(bgHeight, Math.min(maxBgWidth, minWidthForText * bgScaleMultiplier));
        }

        // Position to the left of the icon, vertically centered with the icon
        float hintX = expIconX - HOTKEY_SPACING - bgWidth;
        float hintY = centerY - bgHeight / 2.0f;

        // Apply alpha to colors
        int bgColor = applyAlpha(HOTKEY_BG_COLOR, alpha);
        int textColor = applyAlpha(HOTKEY_TEXT_COLOR, alpha);

        // Render white background rectangle
        Matrix4f matrix = guiGraphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float bgR = ((bgColor >> 16) & 0xFF) / 255.0f;
        float bgG = ((bgColor >> 8) & 0xFF) / 255.0f;
        float bgB = (bgColor & 0xFF) / 255.0f;
        float bgA = ((bgColor >> 24) & 0xFF) / 255.0f;

        float bgLeft = hintX;
        float bgRight = hintX + bgWidth;
        float bgTop = hintY;
        float bgBottom = hintY + bgHeight - 1.0f;  // Shrink bottom by 1 pixel

        bufferBuilder.vertex(matrix, bgLeft, bgBottom, 0).color(bgR, bgG, bgB, bgA).endVertex();
        bufferBuilder.vertex(matrix, bgRight, bgBottom, 0).color(bgR, bgG, bgB, bgA).endVertex();
        bufferBuilder.vertex(matrix, bgRight, bgTop, 0).color(bgR, bgG, bgB, bgA).endVertex();
        bufferBuilder.vertex(matrix, bgLeft, bgTop, 0).color(bgR, bgG, bgB, bgA).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();

        // Render black text centered in the background
        float scaledTextWidth = textWidth;
        float scaledTextHeight = textHeight;
        float textOffsetX = (bgWidth - scaledTextWidth) / 2.0f;
        float textOffsetY = (bgHeight - scaledTextHeight) / 2.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(hintX + textOffsetX, hintY + textOffsetY, 0);
        guiGraphics.pose().scale(HOTKEY_SCALE, HOTKEY_SCALE, 1.0f);
        guiGraphics.drawString(font, displayKeyName, 0, 0, textColor, false);
        guiGraphics.pose().popPose();
    }

    /**
     * Extract the English/alphanumeric part from a key name.
     * This handles cases where the key name contains localized text.
     * For example: "Key 5" -> "5", "Taste 5" -> "5", "5" -> "5"
     *
     * @param keyName The full key name (possibly localized)
     * @return The extracted English/alphanumeric part, or empty string if none found
     */
    private String extractEnglishPart(String keyName) {
        if (keyName == null || keyName.isEmpty()) {
            return "";
        }


        // Try to extract alphanumeric characters (letters and digits)
        StringBuilder result = new StringBuilder();
        for (char c : keyName.toCharArray()) {
            // Include ASCII letters and digits
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                result.append(c);
            }
        }

        // If we got a result, return it
        if (result.length() > 0) {
            return result.toString();
        }

        // Fallback: return the original string
        return keyName;
    }

    /**
     * Render experience icon from container slot 18.
     * If the item has a custom icon defined in CUSTOM_ICON_MAP, renders the custom texture.
     * Otherwise, renders the vanilla item icon in its original form.
     *
     * @param alpha Transparency value (0.0 = fully transparent, 1.0 = fully opaque)
     * @param iconScale Scale multiplier for the icon (1.0 = normal size, 0.9 = 10% smaller)
     */
    private void renderExpIcon(GuiGraphics guiGraphics, float x, float y, ItemStack itemStack, float alpha, float iconScale) {
        if (itemStack.isEmpty()) {
            return;
        }

        // Apply icon scaling
        float scaledIconSize = EXP_ICON_SIZE * iconScale;
        // Center the scaled icon at the original position
        float iconX = x + (EXP_ICON_SIZE - scaledIconSize) / 2.0f;
        float iconY = y - scaledIconSize / 2.0f;

        // Get item ID to check for custom icon
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
        String itemIdString = itemId != null ? itemId.toString() : "";

        // Check if this item has a custom icon
        String customIconName = CUSTOM_ICON_MAP.get(itemIdString);

        if (customIconName != null) {
            // Render custom icon texture
            renderCustomIconScaled(guiGraphics, iconX, iconY, customIconName, alpha, scaledIconSize);
        } else {
            // Render vanilla item icon in its original form
            renderVanillaItemIconScaled(guiGraphics, iconX, iconY, itemStack, alpha, scaledIconSize);
        }
    }

    /**
     * Render a custom icon texture from the mod's resources.
     * The texture should be located at: assets/customui/textures/gui/icons/{iconName}.png
     *
     * @param iconName The name of the icon file (without .png extension)
     * @param alpha Transparency value
     */
    private void renderCustomIcon(GuiGraphics guiGraphics, float x, float y, String iconName, float alpha) {
        ResourceLocation texture = RenderResourceCache.getOrCreate(MOD_ID, ICON_TEXTURE_PATH + iconName + ".png");

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);

        // Draw the texture at the specified position and size
        Matrix4f matrix = guiGraphics.pose().last().pose();

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        float right = x + EXP_ICON_SIZE;
        float bottom = y + EXP_ICON_SIZE;

        // UV coordinates for full texture (0,0 to 1,1)
        bufferBuilder.vertex(matrix, x, bottom, 0).uv(0, 1).endVertex();
        bufferBuilder.vertex(matrix, right, bottom, 0).uv(1, 1).endVertex();
        bufferBuilder.vertex(matrix, right, y, 0).uv(1, 0).endVertex();
        bufferBuilder.vertex(matrix, x, y, 0).uv(0, 0).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    /**
     * Render vanilla item icon in its original form (with colors).
     * Uses GuiGraphics built-in method to avoid depth buffer issues.
     *
     * @param alpha Transparency value
     */
    private void renderVanillaItemIcon(GuiGraphics guiGraphics, float x, float y, ItemStack itemStack, float alpha) {
        // Apply alpha via shader color
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);

        // Scale to desired size (default item is 16x16)
        float scale = EXP_ICON_SIZE / 16.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        // Use built-in method which handles state properly
        guiGraphics.renderItem(itemStack, 0, 0);

        guiGraphics.pose().popPose();

        // Reset shader color
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /**
     * Render a custom icon texture with custom size.
     *
     * @param iconName The name of the icon file (without .png extension)
     * @param alpha Transparency value
     * @param size The size to render the icon at
     */
    private void renderCustomIconScaled(GuiGraphics guiGraphics, float x, float y, String iconName, float alpha, float size) {
        ResourceLocation texture = RenderResourceCache.getOrCreate(MOD_ID, ICON_TEXTURE_PATH + iconName + ".png");

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);

        Matrix4f matrix = guiGraphics.pose().last().pose();

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        float right = x + size;
        float bottom = y + size;

        bufferBuilder.vertex(matrix, x, bottom, 0).uv(0, 1).endVertex();
        bufferBuilder.vertex(matrix, right, bottom, 0).uv(1, 1).endVertex();
        bufferBuilder.vertex(matrix, right, y, 0).uv(1, 0).endVertex();
        bufferBuilder.vertex(matrix, x, y, 0).uv(0, 0).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    /**
     * Render vanilla item icon with custom size.
     * Uses GuiGraphics built-in method to avoid depth buffer issues.
     *
     * @param alpha Transparency value
     * @param size The size to render the icon at
     */
    private void renderVanillaItemIconScaled(GuiGraphics guiGraphics, float x, float y, ItemStack itemStack, float alpha, float size) {
        // Apply alpha via shader color
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);

        // Scale to desired size (default item is 16x16)
        float scale = size / 16.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        // Use built-in method which handles state properly
        guiGraphics.renderItem(itemStack, 0, 0);

        guiGraphics.pose().popPose();

        // Reset shader color
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /**
     * Render experience level number.
     * Uses separate font configuration for experience display.
     *
     * @param alpha Transparency value (0.0 = fully transparent, 1.0 = fully opaque)
     */
    private void renderExpLevelNumber(GuiGraphics guiGraphics, float x, float y, int level, float scale, float alpha) {
        Minecraft mc = Minecraft.getInstance();

        // Add "%" symbol after the number
        String text = level + "";

        // Use Modern UI's font (mc.font) - it provides anti-aliasing automatically
        Font font = mc.font;

        // Apply alpha to color
        int color = applyAlpha(COLOR_WHITE, alpha);

        // Center vertically
        float textHeight = font.lineHeight * scale;
        float textY = y - textHeight / 2.0f;

        // Apply scaling transformation with sub-pixel precision
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, textY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        // Render text with Modern UI anti-aliased font
        guiGraphics.drawString(font, text, 0, 0, color, TEXT_DROP_SHADOW);

        guiGraphics.pose().popPose();
    }

    /**
     * Render the circular expansion effect when experience level reaches 100.
     * The effect is a ring that expands outward from the center while fading out.
     *
     * @param centerX The X center of the effect
     * @param centerY The Y center of the effect
     */
    private void renderExp100Effect(GuiGraphics guiGraphics, float centerX, float centerY) {
        // Calculate animation progress (0.0 to 1.0)
        float progress = exp100EffectTimer / EXP_100_EFFECT_DURATION;
        progress = Math.min(1.0f, Math.max(0.0f, progress));

        // Use easing function for smoother animation (ease-out quad)
        float easedProgress = 1.0f - (1.0f - progress) * (1.0f - progress);

        // Calculate current radius (expands from start to max)
        float currentRadius = EXP_100_EFFECT_START_RADIUS +
                (EXP_100_EFFECT_MAX_RADIUS - EXP_100_EFFECT_START_RADIUS) * easedProgress;

        // Calculate alpha (fades out as it expands)
        // Start at full opacity, fade to 0 by the end
        float alpha = 1.0f - progress;

        // Extract color components
        float r = ((EXP_100_EFFECT_COLOR >> 16) & 0xFF) / 255.0f;
        float g = ((EXP_100_EFFECT_COLOR >> 8) & 0xFF) / 255.0f;
        float b = (EXP_100_EFFECT_COLOR & 0xFF) / 255.0f;

        // Render the ring
        renderRing(guiGraphics, centerX, centerY, currentRadius, EXP_100_EFFECT_RING_WIDTH, r, g, b, alpha);
    }

    /**
     * Render a ring (hollow circle) using line segments.
     *
     * @param centerX Center X position
     * @param centerY Center Y position
     * @param radius Radius of the ring
     * @param thickness Thickness of the ring
     * @param r Red component (0-1)
     * @param g Green component (0-1)
     * @param b Blue component (0-1)
     * @param alpha Alpha component (0-1)
     */
    private void renderRing(GuiGraphics guiGraphics, float centerX, float centerY,
                            float radius, float thickness, float r, float g, float b, float alpha) {
        Matrix4f matrix = guiGraphics.pose().last().pose();

        // Set up rendering state
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // Number of segments for the circle (more = smoother)
        int segments = 32;
        float angleStep = (float) (2.0 * Math.PI / segments);

        float innerRadius = radius - thickness / 2.0f;
        float outerRadius = radius + thickness / 2.0f;

        // Ensure inner radius is not negative
        innerRadius = Math.max(0, innerRadius);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i <= segments; i++) {
            float angle = i * angleStep;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            // Outer vertex
            float outerX = centerX + outerRadius * cos;
            float outerY = centerY + outerRadius * sin;
            bufferBuilder.vertex(matrix, outerX, outerY, 0).color(r, g, b, alpha).endVertex();

            // Inner vertex
            float innerX = centerX + innerRadius * cos;
            float innerY = centerY + innerRadius * sin;
            bufferBuilder.vertex(matrix, innerX, innerY, 0).color(r, g, b, alpha).endVertex();
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    /**
     * Render vertical separator line with 65% opacity.
     */
    private void renderSeparator(GuiGraphics guiGraphics, float x, float y) {
        Matrix4f matrix = guiGraphics.pose().last().pose();

        // Set up rendering state
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // White color with 65% alpha
        float r = 1.0f;
        float g = 1.0f;
        float b = 1.0f;
        float a = SEPARATOR_ALPHA;

        float right = x + SEPARATOR_WIDTH;
        float bottom = y + SEPARATOR_HEIGHT;

        bufferBuilder.vertex(matrix, x, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, y, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, x, y, 0).color(r, g, b, a).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    /**
     * Render plus icon with sub-pixel precision using Blaze3D vertex buffers.
     */
    private void renderPlusIconSubPixel(GuiGraphics guiGraphics, float x, float y, float alpha, float iconSize) {
        int color = applyAlpha(COLOR_WHITE, alpha);

        float centerX = x + iconSize / 2.0f;
        float centerY = y + iconSize / 2.0f;

        float halfArmWidth = getScaledPlusArmWidth() / 2.0f;
        float halfArmLength = getScaledPlusArmLength() / 2.0f;

        Matrix4f matrix = guiGraphics.pose().last().pose();

        // Set up rendering state
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Extract color components
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;

        // Draw horizontal arm (full length) with sub-pixel coordinates
        float hLeft = centerX - halfArmLength;
        float hRight = centerX + halfArmLength;
        float hTop = centerY - halfArmWidth;
        float hBottom = centerY + halfArmWidth;

        bufferBuilder.vertex(matrix, hLeft, hBottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, hRight, hBottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, hRight, hTop, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, hLeft, hTop, 0).color(r, g, b, a).endVertex();

        // Draw vertical arm top part (above horizontal)
        float vtLeft = centerX - halfArmWidth;
        float vtRight = centerX + halfArmWidth;
        float vtTop = centerY - halfArmLength;
        float vtBottom = centerY - halfArmWidth;

        bufferBuilder.vertex(matrix, vtLeft, vtBottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, vtRight, vtBottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, vtRight, vtTop, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, vtLeft, vtTop, 0).color(r, g, b, a).endVertex();

        // Draw vertical arm bottom part (below horizontal)
        float vbLeft = centerX - halfArmWidth;
        float vbRight = centerX + halfArmWidth;
        float vbTop = centerY + halfArmWidth;
        float vbBottom = centerY + halfArmLength;

        bufferBuilder.vertex(matrix, vbLeft, vbBottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, vbRight, vbBottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, vbRight, vbTop, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, vbLeft, vbTop, 0).color(r, g, b, a).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    /**
     * Render health bar with sub-pixel precision using Blaze3D vertex buffers.
     */
    private void renderHealthBarSubPixel(GuiGraphics guiGraphics, float x, float y, float maxHealth,
                                          float displayedHealth, float damageIndicator, int barColor) {
        // Frosted-glass track (bar only); falls back to the flat quad below
        boolean glassBar = com.lootmatrix.customui.client.glass.GlassPanelRenderer.drawHudPanel(
                guiGraphics, x, y, HEALTH_BAR_WIDTH, HEALTH_BAR_HEIGHT,
                HEALTH_BAR_HEIGHT * 0.5f, 0x50101418, 0x24FFFFFF, 1f);

        Matrix4f matrix = guiGraphics.pose().last().pose();

        // Set up rendering state
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();

        float barRight = x + HEALTH_BAR_WIDTH;
        float barBottom = y + HEALTH_BAR_HEIGHT;

        if (!glassBar) {
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            // Extract background color components
            float bgR = ((COLOR_BACKGROUND >> 16) & 0xFF) / 255.0f;
            float bgG = ((COLOR_BACKGROUND >> 8) & 0xFF) / 255.0f;
            float bgB = (COLOR_BACKGROUND & 0xFF) / 255.0f;
            float bgA = ((COLOR_BACKGROUND >> 24) & 0xFF) / 255.0f;

            // Draw background with sub-pixel coordinates
            bufferBuilder.vertex(matrix, x, barBottom, 0).color(bgR, bgG, bgB, bgA).endVertex();
            bufferBuilder.vertex(matrix, barRight, barBottom, 0).color(bgR, bgG, bgB, bgA).endVertex();
            bufferBuilder.vertex(matrix, barRight, y, 0).color(bgR, bgG, bgB, bgA).endVertex();
            bufferBuilder.vertex(matrix, x, y, 0).color(bgR, bgG, bgB, bgA).endVertex();

            BufferUploader.drawWithShader(bufferBuilder.end());
        }

        // Calculate fill widths with floating point precision
        float healthPercent = Mth.clamp(displayedHealth / maxHealth, 0.0f, 1.0f);
        float healthWidth = healthPercent * HEALTH_BAR_WIDTH;

        // Draw damage indicator (semi-transparent red portion showing damage taken)
        if (damageIndicator > displayedHealth + 0.01f) {
            float damagePercent = Mth.clamp(damageIndicator / maxHealth, 0.0f, 1.0f);
            float damageWidth = damagePercent * HEALTH_BAR_WIDTH;
            if (damageWidth > healthWidth) {
                bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

                float dmgR = ((COLOR_DAMAGE_RED >> 16) & 0xFF) / 255.0f;
                float dmgG = ((COLOR_DAMAGE_RED >> 8) & 0xFF) / 255.0f;
                float dmgB = (COLOR_DAMAGE_RED & 0xFF) / 255.0f;
                float dmgA = ((COLOR_DAMAGE_RED >> 24) & 0xFF) / 255.0f;

                float dmgLeft = x + healthWidth;
                float dmgRight = x + damageWidth;

                bufferBuilder.vertex(matrix, dmgLeft, barBottom, 0).color(dmgR, dmgG, dmgB, dmgA).endVertex();
                bufferBuilder.vertex(matrix, dmgRight, barBottom, 0).color(dmgR, dmgG, dmgB, dmgA).endVertex();
                bufferBuilder.vertex(matrix, dmgRight, y, 0).color(dmgR, dmgG, dmgB, dmgA).endVertex();
                bufferBuilder.vertex(matrix, dmgLeft, y, 0).color(dmgR, dmgG, dmgB, dmgA).endVertex();

                BufferUploader.drawWithShader(bufferBuilder.end());
            }
        }

        // Draw health fill (on top) with sub-pixel precision
        if (healthWidth > 0.0f) {
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            float fillR = ((barColor >> 16) & 0xFF) / 255.0f;
            float fillG = ((barColor >> 8) & 0xFF) / 255.0f;
            float fillB = (barColor & 0xFF) / 255.0f;
            float fillA = ((barColor >> 24) & 0xFF) / 255.0f;

            float fillRight = x + healthWidth;

            bufferBuilder.vertex(matrix, x, barBottom, 0).color(fillR, fillG, fillB, fillA).endVertex();
            bufferBuilder.vertex(matrix, fillRight, barBottom, 0).color(fillR, fillG, fillB, fillA).endVertex();
            bufferBuilder.vertex(matrix, fillRight, y, 0).color(fillR, fillG, fillB, fillA).endVertex();
            bufferBuilder.vertex(matrix, x, y, 0).color(fillR, fillG, fillB, fillA).endVertex();

            BufferUploader.drawWithShader(bufferBuilder.end());
        }

        RenderSystem.disableBlend();
    }

    /**
     * Renders the health number (ceiling value) with scaling and sub-pixel positioning.
     * Uses Modern UI's font (mc.font) which provides anti-aliasing automatically.
     */
    private void renderHealthNumber(GuiGraphics guiGraphics, float x, float y, float health, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        int displayValue = (int) Math.ceil(health);
        String text = String.valueOf(displayValue);

        int color = applyAlpha(COLOR_WHITE, alpha);

        // Calculate scale for the number based on health bar size
        float scale = getScaleFactor() * NUMBER_SCALE;

        // Use Modern UI's font (mc.font) - it provides anti-aliasing automatically
        Font font = mc.font;

        // Center vertically with the bar
        float textHeight = font.lineHeight * scale;
        float textY = y - textHeight / 2.0f;

        // Apply scaling transformation with sub-pixel precision
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, textY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        // Render text with Modern UI anti-aliased font
        guiGraphics.drawString(font, text, 0, 0, color, TEXT_DROP_SHADOW);

        guiGraphics.pose().popPose();
    }


    /**
     * Get economy value from the item in container slot 9.
     * Returns -1 if not a valid economy item.
     * @param item The item from slot 9
     * @return Economy value (item count) or -1 if not valid
     */
    private int getEconomyValue(ItemStack item) {
        if (item.isEmpty()) {
            return -1;
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item.getItem());
        if (itemId == null) {
            return -1;
        }

        String itemIdString = itemId.toString();

        // Black stained glass pane = 0
        if (itemIdString.equals("minecraft:black_stained_glass_pane")) {
            return 0;
        }

        // Lime stained glass pane = item count
        if (itemIdString.equals("minecraft:lime_stained_glass_pane")) {
            return item.getCount();
        }

        // Other items = don't show
        return -1;
    }

    /**
     * Update economy effect animation.
     */
    private void updateEconomyEffect(int currentEconomy, float deltaTime) {
        // Check for economy change
        if (previousEconomy >= 0 && currentEconomy >= 0 && currentEconomy != previousEconomy) {
            economyEffectActive = true;
            economyEffectTimer = 0;
            economyIncreased = currentEconomy > previousEconomy;
        }

        // Update effect timer
        if (economyEffectActive) {
            economyEffectTimer += deltaTime;
            if (economyEffectTimer >= ECONOMY_EFFECT_DURATION) {
                economyEffectActive = false;
                economyEffectTimer = 0;
            }
        }

        // Update previous value
        if (currentEconomy >= 0) {
            previousEconomy = currentEconomy;
        }
    }

    /**
     * Render economy display with underline and change effect.
     */
    private void renderEconomyDisplay(GuiGraphics guiGraphics, float x, float y, int value, Font font) {
        String text = value + "$";
        float textWidth = font.width(text) * ECONOMY_TEXT_SCALE;
        float textHeight = font.lineHeight * ECONOMY_TEXT_SCALE;

        // Center vertically
        float textY = y - textHeight / 2.0f;

        // Render text
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, textY, 0);
        guiGraphics.pose().scale(ECONOMY_TEXT_SCALE, ECONOMY_TEXT_SCALE, 1.0f);
        guiGraphics.drawString(font, text, 0, 0, ECONOMY_TEXT_COLOR, false);
        guiGraphics.pose().popPose();

        // Calculate underline position (below text)
        float underlineY = textY + textHeight + 1;

        // Render underline (white line, no shadow)
        Matrix4f matrix = guiGraphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float r = 1.0f, g = 1.0f, b = 1.0f, a = 1.0f;

        // Draw underline
        bufferBuilder.vertex(matrix, x, underlineY, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, x, underlineY + ECONOMY_UNDERLINE_HEIGHT, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, x + textWidth, underlineY + ECONOMY_UNDERLINE_HEIGHT, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, x + textWidth, underlineY, 0).color(r, g, b, a).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());

        // Render change effect (glow going up for increase, down for decrease)
        if (economyEffectActive) {
            renderEconomyChangeEffect(guiGraphics, x, underlineY, textWidth, economyIncreased);
        }

        RenderSystem.disableBlend();
    }

    /**
     * Render economy change effect (rectangular vertical gradient glow).
     * The glow has the same width as the underline and extends upward.
     */
    private void renderEconomyChangeEffect(GuiGraphics guiGraphics, float x, float underlineY, float width, boolean isIncrease) {
        // Calculate effect progress (0.0 to 1.0)
        float progress = economyEffectTimer / ECONOMY_EFFECT_DURATION;

        // Effect height grows then shrinks with easing
        float heightProgress;
        if (progress < 0.2f) {
            // Quick grow phase (ease out)
            float t = progress / 0.2f;
            heightProgress = 1.0f - (1.0f - t) * (1.0f - t);
        } else {
            // Slow shrink phase (ease in)
            float t = (progress - 0.2f) / 0.8f;
            heightProgress = 1.0f - t * t;
        }

        float effectHeight = ECONOMY_EFFECT_HEIGHT * heightProgress;

        // Alpha with smooth fade
        float alpha = (1.0f - progress * progress) * 0.9f;

        // Color based on increase/decrease
        int color = isIncrease ? ECONOMY_INCREASE_COLOR : ECONOMY_DECREASE_COLOR;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        Matrix4f matrix = guiGraphics.pose().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Rectangular glow going upward with gradient (bottom bright, top transparent)
        // Same width as underline
        float topY = underlineY - effectHeight;

        // Draw a simple rectangle with vertical gradient
        // Bottom vertices (full alpha at underline)
        bufferBuilder.vertex(matrix, x, underlineY, 0).color(r, g, b, alpha).endVertex();
        bufferBuilder.vertex(matrix, x + width, underlineY, 0).color(r, g, b, alpha).endVertex();
        // Top vertices (transparent)
        bufferBuilder.vertex(matrix, x + width, topY, 0).color(r, g, b, 0.0f).endVertex();
        bufferBuilder.vertex(matrix, x, topY, 0).color(r, g, b, 0.0f).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    /**
     * Render air/oxygen display with percentage.
     * Features:
     * - Shows air percentage (0-100%)
     * - Flashes red when air < 20%
     * - Fades in when underwater, fades out when at 100% air
     * - Positioned to the left of experience level number
     *
     * @param x X position for the air text
     * @param y Y center position
     * @param airPercent Air percentage (0-100)
     * @param airRatio Air ratio (0.0-1.0) for color calculation
     * @param alpha Overall alpha for fade in/out
     * @param flashTimer Timer for red flash animation
     * @param scale Text scale
     */
    private void renderAirDisplay(GuiGraphics guiGraphics, float x, float y, int airPercent,
                                  float airRatio, float alpha, float flashTimer, float scale) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Build air text with percentage symbol
        String text = airPercent + "%";

        // Calculate text dimensions
        float textHeight = font.lineHeight * scale;
        float textY = y - textHeight / 2.0f;

        // Determine text color based on air level
        int baseColor;
        if (airRatio < AIR_LOW_THRESHOLD) {
            // Low air - flash between white and red
            // Use sine wave for smooth flashing
            float flashIntensity = (float) (Math.sin(flashTimer * Math.PI * 2) * 0.5 + 0.5);

            // Interpolate between white and red
            int r = (int) (255 * (1 - flashIntensity) + ((AIR_LOW_COLOR >> 16) & 0xFF) * flashIntensity);
            int g = (int) (255 * (1 - flashIntensity) + ((AIR_LOW_COLOR >> 8) & 0xFF) * flashIntensity);
            int b = (int) (255 * (1 - flashIntensity) + (AIR_LOW_COLOR & 0xFF) * flashIntensity);
            baseColor = 0xFF000000 | (r << 16) | (g << 8) | b;
        } else {
            // Normal - white color
            baseColor = AIR_TEXT_COLOR;
        }

        // Apply fade alpha to color
        int color = applyAlpha(baseColor, alpha);

        // Render text with scaling
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, textY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        // Render text with Modern UI anti-aliased font
        guiGraphics.drawString(font, text, 0, 0, color, TEXT_DROP_SHADOW);

        guiGraphics.pose().popPose();
    }

    /**
     * Applies alpha value to a color with easing to prevent flicker at low values.
     */
    private int applyAlpha(int color, float alpha) {
        int originalAlpha = (color >> 24) & 0xFF;
        float normalized = (originalAlpha / 255f) * alpha;
        float eased = AlphaFadeHelper.smoothAlpha(normalized);
        int newAlpha = AlphaFadeHelper.clampAlphaInt((int) (eased * 255f));
        // If the original intent was fully transparent, keep it transparent
        if (alpha <= 0f || originalAlpha == 0) return color & 0x00FFFFFF;
        return (newAlpha << 24) | (color & 0x00FFFFFF);
    }
}

