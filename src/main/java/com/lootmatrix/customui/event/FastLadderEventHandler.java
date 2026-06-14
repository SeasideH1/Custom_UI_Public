package com.lootmatrix.customui.event;

import com.lootmatrix.customui.block.FastLadderBlock;
import com.lootmatrix.customui.server.MohistCompatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Event handler to make players climb faster on FastLadderBlock.
 * Uses PlayerTickEvent to boost vertical velocity when on a fast ladder.
 * Only affects players, not other entities.
 */
@Mod.EventBusSubscriber(modid = "customui", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FastLadderEventHandler {

    /**
     * The climb speed multiplier for fast ladders.
     * Vanilla climbing speed is ~0.2 per tick, we boost to 0.4 (2x).
     */
    private static final double MAX_CLIMB_SPEED = 0.4;

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // Only process at the end of the tick to modify after vanilla processing
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Player player = event.player;

        // Skip if not climbing
        if (!player.onClimbable()) {
            return;
        }

        // Check if on fast ladder
        BlockPos pos = player.blockPosition();
        BlockState state = player.level().getBlockState(pos);

        if (!(state.getBlock() instanceof FastLadderBlock)) {
            return;
        }

        // Sneaking: do not boost climb speed, use vanilla speed
        // Use isCrouching() to detect actual sneak state, not raw key input
        if (player.isCrouching()) {
            return;
        }

        boolean hasMoveInput = Math.abs(player.zza) > 1.0e-3f || Math.abs(player.xxa) > 1.0e-3f;
        if (!hasMoveInput) {
            return;
        }

        Vec3 motion = player.getDeltaMovement();

        // Only boost if there's vertical movement
        if (motion.y == 0) {
            return;
        }

        // Apply the speed multiplier
        double boostedY = motion.y * FastLadderBlock.CLIMB_SPEED_MULTIPLIER;

        // Cap at reasonable limits (2x the normal climbing speed)
        if (boostedY > 0) {
            boostedY = Math.min(boostedY, MAX_CLIMB_SPEED);
        } else {
            boostedY = Math.max(boostedY, -MAX_CLIMB_SPEED);
        }

        if (player instanceof ServerPlayer) {
            MohistCompatUtil.setMotion(player, motion.x, boostedY, motion.z);
        } else {
            player.setDeltaMovement(motion.x, boostedY, motion.z);
        }
    }
}
