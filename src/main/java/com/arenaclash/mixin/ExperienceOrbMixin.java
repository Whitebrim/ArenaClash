package com.arenaclash.mixin;

import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevent XP collection during survival phase.
 * XP in this game mode is earned only through arena combat.
 */
@Mixin(ExperienceOrb.class)
public class ExperienceOrbMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void arenaclash$preventXPCollection(Player player, CallbackInfo ci) {
        GameManager gm = GameManager.getInstance();
        boolean isSurvival = (gm.isGameActive() && gm.getPhase() == GamePhase.SURVIVAL)
                || com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive;
        if (isSurvival) {
            // Discard the XP orb entirely
            ((ExperienceOrb)(Object)this).discard();
            ci.cancel();
        }
    }
}
