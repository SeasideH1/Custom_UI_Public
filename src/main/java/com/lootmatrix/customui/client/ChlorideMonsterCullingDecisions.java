package com.lootmatrix.customui.client;

public final class ChlorideMonsterCullingDecisions {

    private ChlorideMonsterCullingDecisions() {
    }

    public static boolean shouldRenderMonster(boolean passesFrustumCheck,
                                              int renderChunks,
                                              double dx,
                                              double dy,
                                              double dz) {
        if (!passesFrustumCheck) {
            return false;
        }

        double renderBlocks = Math.max(0, renderChunks) * 16.0;
        double maxDistSq = renderBlocks * renderBlocks;
        double distSq = dx * dx + dy * dy + dz * dz;
        return distSq <= maxDistSq;
    }
}
