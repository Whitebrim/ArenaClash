package com.arenaclash.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks ALL vanilla damage for ArenaClash arena mobs.
 *
 * <p>This replaces the previous approach of using {@code entity.setInvulnerable(true)},
 * which had a critical side effect: {@code PersistentProjectileEntity.onEntityHit}
 * deflects arrows off invulnerable entities by reversing the arrow's velocity.
 * Even worse, this deflection also triggers when {@code damage()} returns false
 * (not just when {@code isInvulnerableTo()} returns true), meaning any form of
 * damage blocking at the {@code damage()} level causes the same bounce behavior.
 *
 * <p>By cancelling {@code hurt()} at HEAD for arena-tagged mobs, we prevent:
 * <ul>
 *   <li>Fire/burn damage (blaze DOT, sun burn handled by ArenaMob separately)</li>
 *   <li>Fall damage</li>
 *   <li>Explosion damage from vanilla sources</li>
 *   <li>Cactus, berry bush, and other environmental damage</li>
 *   <li>Status effect damage (poison, wither — if they somehow get applied)</li>
 *   <li>Player melee damage (prevents spectators from interfering)</li>
 * </ul>
 *
 * <p>Custom ArenaClash damage is applied via {@code ArenaMob.takeDamage()} which calls
 * {@code LivingEntity.setHealth()} directly — this never goes through {@code hurt()},
 * so it is completely unaffected by this mixin.
 *
 * <p>Note: This mixin works together with {@code ProjectileCollisionMixin} which
 * prevents projectiles from being consumed by vanilla collision logic (so that
 * {@code ProjectileTracker} can handle hit detection by proximity).
 */
@Mixin(LivingEntity.class)
public class ArenaMobDamageMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void arenaclash$blockVanillaDamage(DamageSource source, float amount,
                                                CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.getTags().contains("arenaclash_mob")) {
            cir.setReturnValue(false);
        }
    }
}
