package com.arenaclash.mixin;

import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevent XP orbs from spawning at all during survival phase.
 */
@Mixin(ServerWorld.class)
public class ServerWorldMixin {

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void arenaclash$preventXPSpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof ExperienceOrbEntity) {
            // Check dedicated server game state OR singleplayer bridge flag
            GameManager gm = GameManager.getInstance();
            boolean isSurvival = (gm.isGameActive() && gm.getPhase() == GamePhase.SURVIVAL)
                    || com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive;
            if (isSurvival) {
                cir.setReturnValue(false);
            }
        }
    }
}
