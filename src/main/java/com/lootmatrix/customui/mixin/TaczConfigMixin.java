package com.lootmatrix.customui.mixin;

import com.tacz.guns.config.common.OtherConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = OtherConfig.class, remap = false)
public abstract class TaczConfigMixin {
    @Shadow
    public static ForgeConfigSpec.DoubleValue SERVER_HITBOX_OFFSET;
    @Shadow
    public static ForgeConfigSpec.BooleanValue SERVER_HITBOX_LATENCY_FIX;
    @Shadow
    public static ForgeConfigSpec.DoubleValue SERVER_HITBOX_LATENCY_MAX_SAVE_MS;

    /**
     * @author CustomUI
     * @reason Remove min/max limits for SERVER_HITBOX_LATENCY_MAX_SAVE_MS config
     */
    @Overwrite
    private static void serverConfig(ForgeConfigSpec.Builder builder) {
        System.out.println("[TaczConfigMixin] Overwriting serverConfig method");

        builder.comment("DEV: Server hitbox offset (If the hitbox is ahead, fill in a negative number)");
        SERVER_HITBOX_OFFSET = builder.defineInRange("ServerHitboxOffset", 3.0, -Double.MAX_VALUE, Double.MAX_VALUE);

        builder.comment("Server hitbox latency fix");
        SERVER_HITBOX_LATENCY_FIX = builder.define("ServerHitboxLatencyFix", true);

        builder.comment("The maximum latency (in milliseconds) for the server hitbox latency fix saved");
        // 修改：移除 250ms 的下限限制，改为 0.0
        SERVER_HITBOX_LATENCY_MAX_SAVE_MS = builder.defineInRange("ServerHitboxLatencyMaxSaveMs", 1000.0, 0.0, Double.MAX_VALUE);
    }
}
