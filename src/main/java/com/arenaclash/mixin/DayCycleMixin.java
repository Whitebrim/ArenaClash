package com.arenaclash.mixin;

import com.arenaclash.config.GameConfig;
import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import com.arenaclash.tcp.SingleplayerBridge;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Accelerates the day/night cycle during ArenaClash survival phase.
 * Vanilla MC day = 24000 ticks.  If config.dayDurationTicks = 6000,
 * we need to advance time 4x faster (add 3 extra ticks per tick).
 */
@Mixin(ServerWorld.class)
public class DayCycleMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void arenaclash$accelerateDayCycle(java.util.function.BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        // Check if we're in ArenaClash survival (dedicated server or singleplayer)
        GameManager gm = GameManager.getInstance();
        boolean isSurvival = (gm.isGameActive() && gm.getPhase() == GamePhase.SURVIVAL)
                || SingleplayerBridge.survivalPhaseActive;
        if (!isSurvival) return;

        ServerWorld world = (ServerWorld) (Object) this;

        // Only accelerate the overworld
        if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) return;

        // Calculate speed multiplier
        int dayDuration = SingleplayerBridge.dayDurationTicks;
        if (dayDuration <= 0) {
            // Fall back to config
            try {
                dayDuration = GameConfig.get().dayDurationTicks;
            } catch (Exception e) {
                dayDuration = 6000;
            }
        }

        if (dayDuration >= 24000) return; // No acceleration needed

        // Vanilla day = 24000 ticks. We want it in dayDuration ticks.
        // So each tick we advance by (24000/dayDuration - 1) extra ticks.
        int extraTicks = (24000 / dayDuration) - 1;
        if (extraTicks <= 0) return;

        // Advance world time
        long currentTime = world.getTimeOfDay();
        world.setTimeOfDay(currentTime + extraTicks);
    }
}
