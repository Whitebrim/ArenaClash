package com.arenaclash.mixin;

import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevent XP collection during survival phase.
 * XP in this game mode is earned only through arena combat.
 */
@Mixin(ExperienceOrbEntity.class)
public class ExperienceOrbMixin {

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void arenaclash$preventXPCollection(PlayerEntity player, CallbackInfo ci) {
        GameManager gm = GameManager.getInstance();
        if (gm.isGameActive() && gm.getPhase() == GamePhase.SURVIVAL) {
            // Discard the XP orb entirely
            ((ExperienceOrbEntity)(Object)this).discard();
            ci.cancel();
        }
    }
}
