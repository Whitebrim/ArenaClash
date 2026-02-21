package com.arenaclash.client.mixin;

import com.arenaclash.client.ArenaClashClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Suppress the vanilla Tab player list overlay when an ArenaClash game is active.
 * Tab is rebound to open the card/deployment GUI instead.
 */
@Mixin(PlayerListHud.class)
public class PlayerListMixin {

    @ModifyVariable(method = "setVisible", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean arenaclash$suppressPlayerList(boolean visible) {
        String phase = ArenaClashClient.currentPhase;
        if (phase != null && !"LOBBY".equals(phase)) {
            return false;
        }
        return visible;
    }
}
