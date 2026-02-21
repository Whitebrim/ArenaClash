package com.arenaclash.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents arena mobs (tagged "arenaclash_mob") and spawner mobs
 * (tagged "arenaclash_spawner_mob") from mounting any vehicle
 * (boats, minecarts, horses, etc.).
 *
 * Also prevents any entity from mounting a boat/minecart if the VEHICLE
 * has the arena tag (e.g. a boat entity placed on arena that arena mobs
 * might walk into).
 */
@Mixin(Entity.class)
public class EntityRideMixin {

    @Inject(method = "startRiding(Lnet/minecraft/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true)
    private void arenaclash$preventArenaMobRiding(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;

        // Arena mobs cannot ride anything
        if (self.getCommandTags().contains("arenaclash_mob")) {
            cir.setReturnValue(false);
            return;
        }

        // Spawner-tagged mobs cannot ride anything (prevents them getting stuck in boats)
        if (self.getCommandTags().contains("arenaclash_spawner_mob")) {
            cir.setReturnValue(false);
            return;
        }

        // Nothing can ride arena-tagged vehicles either
        if (vehicle.getCommandTags().contains("arenaclash_mob")
                || vehicle.getCommandTags().contains("arenaclash_structure")) {
            cir.setReturnValue(false);
            return;
        }

        // Prevent arena mobs from becoming vehicles (e.g. spider jockeys, chicken jockeys)
        if (vehicle.getCommandTags().contains("arenaclash_spawner_mob")) {
            cir.setReturnValue(false);
        }
    }
}
