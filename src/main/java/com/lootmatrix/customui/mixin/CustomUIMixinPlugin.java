package com.lootmatrix.customui.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

public final class CustomUIMixinPlugin implements IMixinConfigPlugin {

    private static final String TACZ_OTHER_CONFIG = "com.tacz.guns.config.common.OtherConfig";
    private static final String TACZ_ENTITY_KINETIC_BULLET = "com.tacz.guns.entity.EntityKineticBullet";
    private static final String TACZ_INACCURACY_TYPE = "com.tacz.guns.resource.pojo.data.gun.InaccuracyType";
    private static final String TACZ_GUN_OPERATOR = "com.tacz.guns.api.entity.IGunOperator";
    private static final String TACZ_RENDER_CROSSHAIR_EVENT = "com.tacz.guns.client.event.RenderCrosshairEvent";
    private static final String TACZ_GUN_HUD_OVERLAY = "com.tacz.guns.client.gui.overlay.GunHudOverlay";
    private static final String TACZ_SOUND_PLAY_MANAGER = "com.tacz.guns.client.sound.SoundPlayManager";
    private static final String TACZ_GUN_SOUND_INSTANCE = "com.tacz.guns.client.sound.GunSoundInstance";
    private static final String SUPERBWARFARE_DISPLAY_CONFIG = "com.atsuishio.superbwarfare.config.client.DisplayConfig";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("ChlorideMonsterCullingMixin")) {
            try {
                // Only apply when Chloride is actually present
                MixinService.getService().getBytecodeProvider()
                        .getClassNode("me.srrapero720.chloride.ChlorideConfig");
                return true;
            } catch (ClassNotFoundException e) {
                System.out.println("[CustomUI] Chloride not found, disabling ChlorideMonsterCullingMixin");
                return false;
            } catch (Exception e) {
                System.out.println("[CustomUI] Failed to inspect Chloride, keeping ChlorideMonsterCullingMixin enabled: "
                        + e.getClass().getSimpleName());
                return true;
            }
        }
        if (mixinClassName.endsWith("EntityDataAccessorMixin")) {
            try {
                // Mohist may use a context class loader that cannot resolve Minecraft classes here.
                // Ask Mixin's bytecode provider for the target class instead of using Class.forName.
                MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
                return true;
            } catch (ClassNotFoundException e) {
                System.out.println("[CustomUI] EntityDataAccessor not found, disabling EntityDataAccessorMixin");
                return false;
            } catch (Exception e) {
                System.out.println("[CustomUI] Failed to inspect " + targetClassName
                        + ", keeping EntityDataAccessorMixin enabled: " + e.getClass().getSimpleName());
                return true;
            }
        }
        Boolean superbwarfareDecision = shouldApplySuperbwarfareMixin(mixinClassName);
        if (superbwarfareDecision != null) {
            if (!superbwarfareDecision) {
                System.out.println("[CustomUI] Disabling incompatible optional mixin: " + mixinClassName);
            }
            return superbwarfareDecision;
        }
        Boolean taczDecision = shouldApplyTaczMixin(mixinClassName);
        if (taczDecision != null) {
            if (!taczDecision) {
                System.out.println("[CustomUI] Disabling incompatible optional mixin: " + mixinClassName);
            }
            return taczDecision;
        }
        return true;
    }

    private Boolean shouldApplySuperbwarfareMixin(String mixinClassName) {
        try {
            if (mixinClassName.endsWith("SuperbwarfareDisplayConfigMixin")
                    || mixinClassName.endsWith("SuperbwarfareConfigValueMixin")) {
                return hasField(SUPERBWARFARE_DISPLAY_CONFIG, "EXPLOSION_SCREEN_SHAKE",
                        "Lnet/minecraftforge/common/ForgeConfigSpec$IntValue;");
            }
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            System.out.println("[CustomUI] Failed to inspect optional Superb Warfare mixin target for "
                    + mixinClassName + ": " + e.getClass().getSimpleName());
            return false;
        }
        return null;
    }

    private Boolean shouldApplyTaczMixin(String mixinClassName) {
        try {
            if (mixinClassName.endsWith("TaczConfigMixin")) {
                return hasField(TACZ_OTHER_CONFIG, "SERVER_HITBOX_OFFSET", "Lnet/minecraftforge/common/ForgeConfigSpec$DoubleValue;")
                        && hasField(TACZ_OTHER_CONFIG, "SERVER_HITBOX_LATENCY_FIX", "Lnet/minecraftforge/common/ForgeConfigSpec$BooleanValue;")
                        && hasField(TACZ_OTHER_CONFIG, "SERVER_HITBOX_LATENCY_MAX_SAVE_MS", "Lnet/minecraftforge/common/ForgeConfigSpec$DoubleValue;")
                        && hasMethod(TACZ_OTHER_CONFIG, "serverConfig", "(Lnet/minecraftforge/common/ForgeConfigSpec$Builder;)V");
            }
            if (mixinClassName.endsWith("TaczEntityKineticBulletMixin")) {
                return hasMethod(TACZ_ENTITY_KINETIC_BULLET, "onHitEntity")
                        && methodInvokes(TACZ_ENTITY_KINETIC_BULLET, "onHitEntity",
                        "net/minecraft/world/entity/Entity", "setSecondsOnFire", "(I)V");
            }
            if (mixinClassName.endsWith("TaczInaccuracyTypeMixin")) {
                return hasMethod(TACZ_INACCURACY_TYPE, "getInaccuracyType",
                        "(Lnet/minecraft/world/entity/LivingEntity;)Lcom/tacz/guns/resource/pojo/data/gun/InaccuracyType;")
                        && hasMethod(TACZ_GUN_OPERATOR, "fromLivingEntity",
                        "(Lnet/minecraft/world/entity/LivingEntity;)Lcom/tacz/guns/api/entity/IGunOperator;")
                        && hasMethod(TACZ_GUN_OPERATOR, "getSynAimingProgress", "()F");
            }
            if (mixinClassName.endsWith("TaczCrosshairMixin")) {
                String desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lcom/mojang/blaze3d/platform/Window;)V";
                return hasMethod(TACZ_RENDER_CROSSHAIR_EVENT, "renderCrosshair", desc)
                        && hasMethod(TACZ_RENDER_CROSSHAIR_EVENT, "renderHitMarker", desc);
            }
            if (mixinClassName.endsWith("TaczGunHudMixin")) {
                return hasMethod(TACZ_GUN_HUD_OVERLAY, "render",
                        "(Lnet/minecraftforge/client/gui/overlay/ForgeGui;Lnet/minecraft/client/gui/GuiGraphics;FII)V");
            }
            if (mixinClassName.endsWith("TaczSoundPlayMixin")) {
                String hitDesc = "(Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;)V";
                String shootDesc = "(Lnet/minecraft/world/entity/LivingEntity;Lcom/tacz/guns/client/resource/GunDisplayInstance;Lcom/tacz/guns/resource/pojo/data/gun/GunData;)V";
                return hasMethod(TACZ_SOUND_PLAY_MANAGER, "playHeadHitSound", hitDesc)
                        && hasMethod(TACZ_SOUND_PLAY_MANAGER, "playFleshHitSound", hitDesc)
                        && hasMethod(TACZ_SOUND_PLAY_MANAGER, "playShootSound", shootDesc)
                        && hasMethod(TACZ_SOUND_PLAY_MANAGER, "playSilenceSound", shootDesc)
                        && hasMethod(TACZ_SOUND_PLAY_MANAGER, "playMessageSound",
                        "(Lcom/tacz/guns/network/message/ServerMessageSound;)V")
                        && !hasMethod(TACZ_GUN_SOUND_INSTANCE, "getSoundBuffer",
                        "()Lcom/mojang/blaze3d/audio/SoundBuffer;");
            }
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            System.out.println("[CustomUI] Failed to inspect optional TaCZ mixin target for " + mixinClassName
                    + ": " + e.getClass().getSimpleName());
            return false;
        }
        return null;
    }

    private static ClassNode getClassNode(String className) throws Exception {
        return MixinService.getService().getBytecodeProvider().getClassNode(className);
    }

    private static boolean hasField(String className, String name, String desc) throws Exception {
        ClassNode node = getClassNode(className);
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && desc.equals(field.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(String className, String name, String... descriptors) throws Exception {
        ClassNode node = getClassNode(className);
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name)) {
                continue;
            }
            if (descriptors.length == 0) {
                return true;
            }
            for (String desc : descriptors) {
                if (desc.equals(method.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean methodInvokes(String className, String methodName,
                                         String owner, String invokeName, String invokeDesc) throws Exception {
        ClassNode node = getClassNode(className);
        for (MethodNode method : node.methods) {
            if (!methodName.equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call
                        && owner.equals(call.owner)
                        && invokeName.equals(call.name)
                        && invokeDesc.equals(call.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
