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
            GameManager gm = GameManager.getInstance();
            if (gm.isGameActive() && gm.getPhase() == GamePhase.SURVIVAL) {
                cir.setReturnValue(false);
            }
        }
    }
}
