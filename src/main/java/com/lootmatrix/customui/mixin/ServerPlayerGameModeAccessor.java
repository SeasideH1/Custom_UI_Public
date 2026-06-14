package com.lootmatrix.customui.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccessor {

    @Accessor("gameModeForPlayer")
    GameType customui$getGameModeForPlayerField();

    @Accessor("gameModeForPlayer")
    void customui$setGameModeForPlayerField(GameType gameType);

    @Accessor("previousGameModeForPlayer")
    void customui$setPreviousGameModeForPlayerField(GameType gameType);
}