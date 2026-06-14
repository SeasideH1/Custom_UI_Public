package com.lootmatrix.customui.hud;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

import javax.annotation.Nullable;

/**
 * Parsed GUI element condition: "scoreboard:objective[:holder] OP value"
 * with OP one of {@code >= <= > < == !=}. The same parsed form is evaluated
 * client-side (against the synced scoreboard cache) for instant feedback and
 * server-side (against the real scoreboard) for anti-cheat validation.
 */
public final class HudCondition {

    public enum Op { GE, LE, GT, LT, EQ, NE }

    public final HudScoreboardBinding binding;
    public final Op op;
    public final int value;

    private HudCondition(HudScoreboardBinding binding, Op op, int value) {
        this.binding = binding;
        this.op = op;
        this.value = value;
    }

    /** Parse a condition string; null when blank or malformed. */
    @Nullable
    public static HudCondition parse(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Find the operator (two-char ops first)
        String[] ops = {">=", "<=", "==", "!=", ">", "<"};
        Op[] opValues = {Op.GE, Op.LE, Op.EQ, Op.NE, Op.GT, Op.LT};
        for (int i = 0; i < ops.length; i++) {
            int idx = s.indexOf(ops[i]);
            if (idx < 0) continue;
            String left = s.substring(0, idx).trim();
            String right = s.substring(idx + ops[i].length()).trim();
            HudScoreboardBinding binding = HudScoreboardBinding.parse(left);
            if (binding == null) return null;
            try {
                return new HudCondition(binding, opValues[i], Integer.parseInt(right));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public boolean test(int score) {
        return switch (op) {
            case GE -> score >= value;
            case LE -> score <= value;
            case GT -> score > value;
            case LT -> score < value;
            case EQ -> score == value;
            case NE -> score != value;
        };
    }

    /** Authoritative server-side check against the real scoreboard. */
    public boolean evaluateServer(Scoreboard scoreboard, ServerPlayer player) {
        String holder = binding.isSelf() ? player.getScoreboardName() : binding.holder;
        Objective objective = scoreboard.getObjective(binding.objective);
        int score = 0;
        if (objective != null && scoreboard.hasPlayerScore(holder, objective)) {
            score = scoreboard.getOrCreatePlayerScore(holder, objective).getScore();
        }
        return test(score);
    }
}
