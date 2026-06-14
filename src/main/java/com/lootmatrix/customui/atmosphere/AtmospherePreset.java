package com.lootmatrix.customui.atmosphere;

import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;

/**
 * Data model for a complete atmosphere/environment preset.
 * Each section is nullable — if null, that aspect of the atmosphere is left unchanged (vanilla).
 */
public class AtmospherePreset {

    public final String id;
    public final int fadeInTicks;
    public final int fadeOutTicks;
    public final EasingType easing;

    @Nullable public final FogConfig fog;
    @Nullable public final SkyConfig sky;
    @Nullable public final SunConfig sun;
    @Nullable public final MoonConfig moon;
    @Nullable public final StarsConfig stars;
    @Nullable public final CloudConfig clouds;
    @Nullable public final AmbientConfig ambient;

    public AtmospherePreset(String id, int fadeInTicks, int fadeOutTicks, EasingType easing,
                            @Nullable FogConfig fog, @Nullable SkyConfig sky,
                            @Nullable SunConfig sun, @Nullable MoonConfig moon,
                            @Nullable StarsConfig stars, @Nullable CloudConfig clouds,
                            @Nullable AmbientConfig ambient) {
        this.id = id;
        this.fadeInTicks = fadeInTicks;
        this.fadeOutTicks = fadeOutTicks;
        this.easing = easing;
        this.fog = fog;
        this.sky = sky;
        this.sun = sun;
        this.moon = moon;
        this.stars = stars;
        this.clouds = clouds;
        this.ambient = ambient;
    }

    // ==================== Enums ====================

    public enum SkyType {
        VANILLA,  // Don't modify sky rendering
        COLOR,    // Solid color gradient (zenith → horizon)
        CUBEMAP   // 6-face cubemap texture
    }

    public enum EasingType {
        NONE, EASE_IN, EASE_OUT, EASE_IN_OUT,
        EASE_IN_CUBIC, EASE_OUT_CUBIC, EASE_IN_OUT_CUBIC;

        public static EasingType fromString(String s) {
            try { return valueOf(s.toUpperCase()); }
            catch (Exception e) { return EASE_IN_OUT; }
        }
    }

    public enum FogShapeType {
        SPHERE,
        CYLINDER;

        public static FogShapeType fromString(String value) {
            return "CYLINDER".equalsIgnoreCase(value) ? CYLINDER : SPHERE;
        }

        public int toNetworkId() {
            return this == CYLINDER ? 1 : 0;
        }

        public static FogShapeType fromNetworkId(int value) {
            return value == 1 ? CYLINDER : SPHERE;
        }
    }

    // ==================== Section Configs ====================

    public static class FogConfig {
        public final float r, g, b;
        public final float nearDistance, farDistance;
        public final FogShapeType shape;
        public final boolean overrideDensity;
        public final float density;

        public FogConfig(float r, float g, float b, float nearDistance, float farDistance,
                         FogShapeType shape, boolean overrideDensity, float density) {
            this.r = r; this.g = g; this.b = b;
            this.nearDistance = nearDistance;
            this.farDistance = farDistance;
            this.shape = shape;
            this.overrideDensity = overrideDensity;
            this.density = density;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeFloat(r); buf.writeFloat(g); buf.writeFloat(b);
            buf.writeFloat(nearDistance); buf.writeFloat(farDistance);
            buf.writeVarInt(shape.toNetworkId());
            buf.writeBoolean(overrideDensity);
            buf.writeFloat(density);
        }

        public static FogConfig decode(FriendlyByteBuf buf) {
            return new FogConfig(
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(),
                    FogShapeType.fromNetworkId(buf.readVarInt()),
                    buf.readBoolean(), buf.readFloat());
        }
    }

    public static class SkyConfig {
        public final SkyType type;
        public final float zenithR, zenithG, zenithB;
        public final float horizonR, horizonG, horizonB;
        // Cubemap textures (only for CUBEMAP type)
        public final String cubemapUp, cubemapDown, cubemapNorth, cubemapSouth, cubemapEast, cubemapWest;
        public final float cubemapRotationSpeed;

        public SkyConfig(SkyType type,
                         float zenithR, float zenithG, float zenithB,
                         float horizonR, float horizonG, float horizonB,
                         String cubemapUp, String cubemapDown, String cubemapNorth,
                         String cubemapSouth, String cubemapEast, String cubemapWest,
                         float cubemapRotationSpeed) {
            this.type = type;
            this.zenithR = zenithR; this.zenithG = zenithG; this.zenithB = zenithB;
            this.horizonR = horizonR; this.horizonG = horizonG; this.horizonB = horizonB;
            this.cubemapUp = cubemapUp; this.cubemapDown = cubemapDown;
            this.cubemapNorth = cubemapNorth; this.cubemapSouth = cubemapSouth;
            this.cubemapEast = cubemapEast; this.cubemapWest = cubemapWest;
            this.cubemapRotationSpeed = cubemapRotationSpeed;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(type.ordinal());
            buf.writeFloat(zenithR); buf.writeFloat(zenithG); buf.writeFloat(zenithB);
            buf.writeFloat(horizonR); buf.writeFloat(horizonG); buf.writeFloat(horizonB);
            buf.writeUtf(cubemapUp != null ? cubemapUp : "");
            buf.writeUtf(cubemapDown != null ? cubemapDown : "");
            buf.writeUtf(cubemapNorth != null ? cubemapNorth : "");
            buf.writeUtf(cubemapSouth != null ? cubemapSouth : "");
            buf.writeUtf(cubemapEast != null ? cubemapEast : "");
            buf.writeUtf(cubemapWest != null ? cubemapWest : "");
            buf.writeFloat(cubemapRotationSpeed);
        }

        public static SkyConfig decode(FriendlyByteBuf buf) {
            SkyType type = SkyType.values()[buf.readVarInt() % SkyType.values().length];
            float zR = buf.readFloat(), zG = buf.readFloat(), zB = buf.readFloat();
            float hR = buf.readFloat(), hG = buf.readFloat(), hB = buf.readFloat();
            String up = buf.readUtf(), dn = buf.readUtf(), n = buf.readUtf(),
                    s = buf.readUtf(), e = buf.readUtf(), w = buf.readUtf();
            float rot = buf.readFloat();
            return new SkyConfig(type, zR, zG, zB, hR, hG, hB,
                    up.isEmpty() ? null : up, dn.isEmpty() ? null : dn,
                    n.isEmpty() ? null : n, s.isEmpty() ? null : s,
                    e.isEmpty() ? null : e, w.isEmpty() ? null : w, rot);
        }
    }

    public static class SunConfig {
        public final boolean visible;
        public final String texture; // null = vanilla
        public final float scale;
        public final float r, g, b;

        public SunConfig(boolean visible, String texture, float scale, float r, float g, float b) {
            this.visible = visible; this.texture = texture;
            this.scale = scale; this.r = r; this.g = g; this.b = b;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(visible);
            buf.writeUtf(texture != null ? texture : "");
            buf.writeFloat(scale);
            buf.writeFloat(r); buf.writeFloat(g); buf.writeFloat(b);
        }

        public static SunConfig decode(FriendlyByteBuf buf) {
            boolean vis = buf.readBoolean();
            String tex = buf.readUtf(); if (tex.isEmpty()) tex = null;
            return new SunConfig(vis, tex, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    public static class MoonConfig {
        public final boolean visible;
        public final String texture; // null = vanilla
        public final float scale;
        public final float r, g, b;

        public MoonConfig(boolean visible, String texture, float scale, float r, float g, float b) {
            this.visible = visible; this.texture = texture;
            this.scale = scale; this.r = r; this.g = g; this.b = b;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(visible);
            buf.writeUtf(texture != null ? texture : "");
            buf.writeFloat(scale);
            buf.writeFloat(r); buf.writeFloat(g); buf.writeFloat(b);
        }

        public static MoonConfig decode(FriendlyByteBuf buf) {
            boolean vis = buf.readBoolean();
            String tex = buf.readUtf(); if (tex.isEmpty()) tex = null;
            return new MoonConfig(vis, tex, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    public static class StarsConfig {
        public final boolean visible;
        public final float density;    // multiplier (1.0 = vanilla ~1500 stars)
        public final float r, g, b;
        public final float brightness; // multiplier

        public StarsConfig(boolean visible, float density, float r, float g, float b, float brightness) {
            this.visible = visible; this.density = density;
            this.r = r; this.g = g; this.b = b; this.brightness = brightness;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(visible);
            buf.writeFloat(density);
            buf.writeFloat(r); buf.writeFloat(g); buf.writeFloat(b);
            buf.writeFloat(brightness);
        }

        public static StarsConfig decode(FriendlyByteBuf buf) {
            return new StarsConfig(buf.readBoolean(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    public static class CloudConfig {
        public final boolean visible;
        public final float height;
        public final float r, g, b;

        public CloudConfig(boolean visible, float height, float r, float g, float b) {
            this.visible = visible; this.height = height;
            this.r = r; this.g = g; this.b = b;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(visible);
            buf.writeFloat(height);
            buf.writeFloat(r); buf.writeFloat(g); buf.writeFloat(b);
        }

        public static CloudConfig decode(FriendlyByteBuf buf) {
            return new CloudConfig(buf.readBoolean(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    public static class AmbientConfig {
        public final float brightnessMultiplier;
        public final boolean nightVision;

        public AmbientConfig(float brightnessMultiplier, boolean nightVision) {
            this.brightnessMultiplier = brightnessMultiplier;
            this.nightVision = nightVision;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeFloat(brightnessMultiplier);
            buf.writeBoolean(nightVision);
        }

        public static AmbientConfig decode(FriendlyByteBuf buf) {
            return new AmbientConfig(buf.readFloat(), buf.readBoolean());
        }
    }

    // ==================== Network Encode/Decode ====================

    private static final int FLAG_FOG     = 1;
    private static final int FLAG_SKY     = 1 << 1;
    private static final int FLAG_SUN     = 1 << 2;
    private static final int FLAG_MOON    = 1 << 3;
    private static final int FLAG_STARS   = 1 << 4;
    private static final int FLAG_CLOUDS  = 1 << 5;
    private static final int FLAG_AMBIENT = 1 << 6;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeVarInt(fadeInTicks);
        buf.writeVarInt(fadeOutTicks);
        buf.writeVarInt(easing.ordinal());

        int flags = 0;
        if (fog     != null) flags |= FLAG_FOG;
        if (sky     != null) flags |= FLAG_SKY;
        if (sun     != null) flags |= FLAG_SUN;
        if (moon    != null) flags |= FLAG_MOON;
        if (stars   != null) flags |= FLAG_STARS;
        if (clouds  != null) flags |= FLAG_CLOUDS;
        if (ambient != null) flags |= FLAG_AMBIENT;
        buf.writeByte(flags);

        if (fog     != null) fog.encode(buf);
        if (sky     != null) sky.encode(buf);
        if (sun     != null) sun.encode(buf);
        if (moon    != null) moon.encode(buf);
        if (stars   != null) stars.encode(buf);
        if (clouds  != null) clouds.encode(buf);
        if (ambient != null) ambient.encode(buf);
    }

    public static AtmospherePreset decode(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        int fadeIn = buf.readVarInt();
        int fadeOut = buf.readVarInt();
        EasingType easing = EasingType.values()[buf.readVarInt() % EasingType.values().length];
        int flags = buf.readByte() & 0xFF;

        FogConfig fog       = (flags & FLAG_FOG)     != 0 ? FogConfig.decode(buf)     : null;
        SkyConfig sky       = (flags & FLAG_SKY)     != 0 ? SkyConfig.decode(buf)     : null;
        SunConfig sun       = (flags & FLAG_SUN)     != 0 ? SunConfig.decode(buf)     : null;
        MoonConfig moon     = (flags & FLAG_MOON)    != 0 ? MoonConfig.decode(buf)    : null;
        StarsConfig stars   = (flags & FLAG_STARS)   != 0 ? StarsConfig.decode(buf)   : null;
        CloudConfig clouds  = (flags & FLAG_CLOUDS)  != 0 ? CloudConfig.decode(buf)   : null;
        AmbientConfig ambient = (flags & FLAG_AMBIENT) != 0 ? AmbientConfig.decode(buf) : null;

        return new AtmospherePreset(id, fadeIn, fadeOut, easing, fog, sky, sun, moon, stars, clouds, ambient);
    }

    // ==================== Easing Math ====================

    public static float applyEasing(EasingType easing, float t) {
        return switch (easing) {
            case NONE -> t;
            case EASE_IN -> t * t;
            case EASE_OUT -> 1f - (1f - t) * (1f - t);
            case EASE_IN_OUT -> t < 0.5f ? 2f * t * t : 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f;
            case EASE_IN_CUBIC -> t * t * t;
            case EASE_OUT_CUBIC -> 1f - (1f - t) * (1f - t) * (1f - t);
            case EASE_IN_OUT_CUBIC -> t < 0.5f ? 4f * t * t * t
                    : 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f;
        };
    }
}
