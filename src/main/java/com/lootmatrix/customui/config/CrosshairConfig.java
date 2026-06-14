package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuration for custom crosshair and hit feedback overlay.
 * Controls crosshair appearance, hit marker animation, and camera correction.
 */
public class CrosshairConfig {

    public static final ForgeConfigSpec SPEC;
    public static final CrosshairConfig INSTANCE;

    static {
        Pair<CrosshairConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(CrosshairConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== Master Toggle ====================
    public final ForgeConfigSpec.BooleanValue enabled;

    // ==================== Crosshair Bars (+ shape) ====================
    public final ForgeConfigSpec.DoubleValue crosshairBarLength;
    public final ForgeConfigSpec.DoubleValue crosshairBarWidth;
    public final ForgeConfigSpec.DoubleValue crosshairGap;
    public final ForgeConfigSpec.DoubleValue crosshairOpacity;
    public final ForgeConfigSpec.DoubleValue crosshairCornerRadius;

    // ==================== ADS Fade ====================
    public final ForgeConfigSpec.BooleanValue adsFadeEnabled;

    // ==================== Vanilla Charge Shrink ====================
    public final ForgeConfigSpec.BooleanValue chargeShrinkEnabled;
    public final ForgeConfigSpec.DoubleValue chargeMinGapMultiplier;
    public final ForgeConfigSpec.DoubleValue chargeOpacity;

    // ==================== Hit Feedback Bars ====================
    public final ForgeConfigSpec.DoubleValue hitBarLength;
    public final ForgeConfigSpec.DoubleValue hitBarWidth;
    public final ForgeConfigSpec.DoubleValue hitBarDistance;
    public final ForgeConfigSpec.DoubleValue hitBarBaseAngle;
    public final ForgeConfigSpec.DoubleValue hitBarRotationAmount;
    public final ForgeConfigSpec.DoubleValue hitBarRotationReturnSpeed;
    public final ForgeConfigSpec.DoubleValue hitBarScaleStart;
    public final ForgeConfigSpec.DoubleValue hitBarScaleEnd;
    public final ForgeConfigSpec.DoubleValue hitBarAnimDuration;
    public final ForgeConfigSpec.DoubleValue hitBarFadeDelay;
    public final ForgeConfigSpec.DoubleValue hitBarFadeDuration;
    public final ForgeConfigSpec.DoubleValue hitBarCornerRadius;
    public final ForgeConfigSpec.DoubleValue hitBarHeadshotExtWidth;
    public final ForgeConfigSpec.DoubleValue hitBarHeadshotExtLength;
    public final ForgeConfigSpec.DoubleValue hitBarKillExpandSpeed;
    public final ForgeConfigSpec.DoubleValue hitBarKillFadeDuration;

    // ==================== Damage Indicator (directional arcs) ====================
    public final ForgeConfigSpec.DoubleValue indicatorInnerRadius;
    public final ForgeConfigSpec.DoubleValue indicatorOuterRadius;

    // ==================== Camera Correction ====================
    public final ForgeConfigSpec.BooleanValue cameraCorrectionEnabled;
    public final ForgeConfigSpec.DoubleValue cameraCorrectionStrength;
    public final ForgeConfigSpec.BooleanValue taczBobOverride;
    public final ForgeConfigSpec.DoubleValue taczHurtBobScale;

    // ==================== Vanilla Hit Feedback ====================
    public final ForgeConfigSpec.BooleanValue vanillaHitFeedbackEnabled;

    // ==================== Projectile Trajectory ====================
    public final ForgeConfigSpec.BooleanValue trajectoryEnabled;
    public final ForgeConfigSpec.DoubleValue trajectoryLineWidth;
    public final ForgeConfigSpec.IntValue trajectoryMaxSteps;
    public final ForgeConfigSpec.DoubleValue trajectoryColorR;
    public final ForgeConfigSpec.DoubleValue trajectoryColorG;
    public final ForgeConfigSpec.DoubleValue trajectoryColorB;
    public final ForgeConfigSpec.DoubleValue trajectoryAlpha;
    public final ForgeConfigSpec.DoubleValue trajectoryImpactCircleRadius;

    private CrosshairConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Custom Crosshair & Hit Feedback Configuration")
                .push("crosshair");

        enabled = builder
                .comment("Master switch for custom crosshair system")
                .define("enabled", true);

        // Crosshair bars
        builder.comment("Crosshair Bar Settings (+ shaped bars around center)").push("bars");

        crosshairBarLength = builder
                .comment("Length of each crosshair bar (pixels)")
                .defineInRange("barLength", 4.0, 2.0, 30.0);

        crosshairBarWidth = builder
                .comment("Width/thickness of each crosshair bar (pixels)")
                .defineInRange("barWidth", 1.0, 0.5, 5.0);

        crosshairGap = builder
                .comment("Gap from center to start of each bar (pixels)")
                .defineInRange("gap", 1.7, 0.0, 20.0);

        crosshairOpacity = builder
                .comment("Opacity of crosshair bars (0.0-1.0)")
                .defineInRange("opacity", 0.8, 0.1, 1.0);

        crosshairCornerRadius = builder
                .comment("Corner rounding radius of crosshair bars (pixels)")
                .defineInRange("cornerRadius", 0.0, 0.0, 3.0);

        builder.pop();

        // ADS fade
        builder.comment("ADS (Aim Down Sight) Fade Settings").push("adsFade");

        adsFadeEnabled = builder
                .comment("Fade out crosshair when aiming down sights with TACZ weapons")
                .define("enabled", true);

        builder.pop();

        // Vanilla item charge
        builder.comment("Vanilla Item Charge Settings (bow, crossbow, trident)").push("charge");

        chargeShrinkEnabled = builder
                .comment("Shrink crosshair gap and change opacity when charging vanilla items")
                .define("enabled", true);

        chargeMinGapMultiplier = builder
                .comment("Minimum gap multiplier at full charge (0.0 = fully closed, 1.0 = no shrink)")
                .defineInRange("minGapMultiplier", 0.0, 0.0, 1.0);

        chargeOpacity = builder
                .comment("Crosshair opacity at full charge (0.0-1.0)")
                .defineInRange("chargeOpacity", 0.7, 0.1, 1.0);

        builder.pop();

        // Hit feedback
        builder.comment("Hit Feedback Bar Settings (vertical bars on hit/kill)").push("hitFeedback");

        hitBarLength = builder
                .comment("Length of hit feedback bar (pixels)")
                .defineInRange("barLength", 6.0, 4.0, 30.0);

        hitBarWidth = builder
                .comment("Width of hit feedback bar (pixels)")
                .defineInRange("barWidth", 0.9, 0.3, 5.0);

        hitBarDistance = builder
                .comment("Distance from center to hit bar center (pixels, 0 = at center)")
                .defineInRange("distance", 7.7, 0.0, 30.0);

        hitBarBaseAngle = builder
                .comment("Base/rest angle of hit bars (degrees, 45 = diagonal)")
                .defineInRange("baseAngle", 45.0, 0.0, 90.0);

        hitBarRotationAmount = builder
                .comment("Rotation amount on hit trigger (degrees)")
                .defineInRange("rotationAmount", 5.0, 1.0, 45.0);

        hitBarRotationReturnSpeed = builder
                .comment("Speed at which rotation returns to base angle (higher = faster)")
                .defineInRange("rotationReturnSpeed", 15.0, 4.0, 30.0);

        hitBarScaleStart = builder
                .comment("Initial scale on hit (1.8 = 180%)")
                .defineInRange("scaleStart", 1.50, 1.0, 2.5);

        hitBarScaleEnd = builder
                .comment("Final scale after animation (1.0 = 100%)")
                .defineInRange("scaleEnd", 1.0, 0.5, 1.5);

        hitBarAnimDuration = builder
                .comment("Duration of the hit feedback appear animation (seconds)")
                .defineInRange("animDuration", 0.15, 0.05, 0.5);

        hitBarFadeDelay = builder
                .comment("Time after hit before feedback starts fading (seconds)")
                .defineInRange("fadeDelay", 0.3, 0.1, 2.0);

        hitBarFadeDuration = builder
                .comment("Duration of the fade-out (seconds)")
                .defineInRange("fadeDuration", 0.2, 0.05, 1.0);

        hitBarCornerRadius = builder
                .comment("Corner rounding radius for hit feedback bars (pixels)")
                .defineInRange("cornerRadius", 0.5, 0.0, 3.0);

        hitBarHeadshotExtWidth = builder
                .comment("Width of the thin headshot extension bar at the far side (pixels)")
                .defineInRange("headshotExtWidth", 0.35, 0.1, 3.0);

        hitBarHeadshotExtLength = builder
                .comment("Length of the thin headshot extension bar at the far side (pixels)")
                .defineInRange("headshotExtLength", 3.5, 1.0, 15.0);

        hitBarKillExpandSpeed = builder
                .comment("Kill feedback expansion speed (pixels per second)")
                .defineInRange("killExpandSpeed", 45.0, 5.0, 100.0);

        hitBarKillFadeDuration = builder
                .comment("Kill feedback fade-out duration (seconds)")
                .defineInRange("killFadeDuration", 0.4, 0.1, 2.0);

        builder.pop();

        // Damage indicator arcs
        builder.comment("Directional Damage Indicator Settings").push("indicator");

        indicatorInnerRadius = builder
                .comment("Inner radius of directional damage arc (pixels from center)")
                .defineInRange("innerRadius", 60.0, 20.0, 200.0);

        indicatorOuterRadius = builder
                .comment("Outer radius of directional damage arc (pixels from center)")
                .defineInRange("outerRadius", 100.0, 40.0, 300.0);

        builder.pop();

        // Camera correction
        builder.comment("Camera Correction Settings").push("cameraCorrection");

        cameraCorrectionEnabled = builder
                .comment("Correct crosshair position for vanilla view bobbing and hurt shake")
                .define("enabled", true);

        cameraCorrectionStrength = builder
                .comment("Strength of camera correction (0.0 = no correction, 1.0 = full correction)")
                .defineInRange("strength", 0.2, 0.0, 2.0);

        taczBobOverride = builder
                .comment("When holding a TACZ gun, disable walk bob correction (TACZ cancels vanilla bobView for guns)")
                .define("taczBobOverride", true);

        taczHurtBobScale = builder
                .comment("Hurt bob correction scale when hit by TACZ gun (TACZ reduces hurt bob to ~5%)")
                .defineInRange("taczHurtBobScale", 0.05, 0.0, 1.0);

        builder.pop();

        // Vanilla hit feedback
        builder.comment("Vanilla Weapon Hit Feedback Settings").push("vanillaHitFeedback");

        vanillaHitFeedbackEnabled = builder
                .comment("Show crosshair hit/kill feedback when hitting entities with vanilla weapons (sword, bow, etc.)")
                .define("enabled", true);

        builder.pop();

        // Projectile trajectory
        builder.comment("Projectile Trajectory Prediction Settings (bow, trident)").push("trajectory");

        trajectoryEnabled = builder
                .comment("Show predicted trajectory line when charging bow/trident")
                .define("enabled", true);

        trajectoryLineWidth = builder
                .comment("Width of the trajectory line (pixels)")
                .defineInRange("lineWidth", 4.0, 0.5, 10.0);

        trajectoryMaxSteps = builder
                .comment("Maximum simulation steps for trajectory prediction (each step = 1 tick)")
                .defineInRange("maxSteps", 300, 50, 600);

        trajectoryColorR = builder
                .comment("Trajectory line color - Red component (0.0-1.0)")
                .defineInRange("colorR", 0.2, 0.0, 1.0);

        trajectoryColorG = builder
                .comment("Trajectory line color - Green component (0.0-1.0)")
                .defineInRange("colorG", 0.6, 0.0, 1.0);

        trajectoryColorB = builder
                .comment("Trajectory line color - Blue component (0.0-1.0)")
                .defineInRange("colorB", 1.0, 0.0, 1.0);

        trajectoryAlpha = builder
                .comment("Trajectory line opacity (0.0-1.0)")
                .defineInRange("alpha", 0.8, 0.1, 1.0);

        trajectoryImpactCircleRadius = builder
                .comment("Radius of the impact point circle (blocks)")
                .defineInRange("impactCircleRadius", 0.5, 0.1, 2.0);

        builder.pop();

        builder.pop();
    }
}












