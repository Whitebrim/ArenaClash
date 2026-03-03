package com.arenaclash.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents ArenaClash projectiles from colliding with arena entities via vanilla logic.
 *
 * <h3>Why this is necessary</h3>
 * All arena mob damage is handled by {@code ProjectileTracker}, which checks
 * projectile-to-target proximity each tick and applies damage when close enough.
 * Vanilla collision must be suppressed because:
 * <ul>
 *   <li><b>Arrows</b> ({@code PersistentProjectileEntity}): vanilla {@code onEntityHit}
 *       calls {@code entity.damage()} → our {@code ArenaMobDamageMixin} returns false →
 *       BUT vanilla treats {@code damage() == false} the same as invulnerable and
 *       <em>deflects the arrow</em> (reverses velocity). The arrow bounces away and
 *       {@code ProjectileTracker} never registers a hit.</li>
 *   <li><b>Explosive projectiles</b> ({@code FireballEntity}, {@code WitherSkullEntity}):
 *       subclasses call {@code super.onCollision()} then {@code this.discard()}.
 *       Our HEAD inject on {@code ProjectileEntity.onCollision} cancels the super,
 *       but the subclass code continues and discards the entity. For these types,
 *       {@code ProjectileTracker} relies on its large hitRadius (3.0 blocks) to catch
 *       the projectile 1-2 ticks before vanilla collision fires — same behavior as before.</li>
 * </ul>
 *
 * <h3>Interception strategy</h3>
 * Two injection points for defense in depth:
 * <ol>
 *   <li>{@code onCollision(HitResult)} — main entry point called from {@code tick()}.
 *       Cancels before entity hits are dispatched to {@code onEntityHit}.</li>
 *   <li>{@code onEntityHit(EntityHitResult)} — belt-and-suspenders catch for subclasses
 *       that might invoke entity hit processing through a different path.</li>
 * </ol>
 *
 * Block collisions are NOT affected — projectiles still stop at walls and ground.
 */
@Mixin(ProjectileEntity.class)
public class ProjectileCollisionMixin {

    /**
     * Primary interception: cancel onCollision entirely for entity hits on arena entities.
     * This prevents the dispatch to onEntityHit where arrow deflection lives.
     */
    @Inject(method = "onCollision", at = @At("HEAD"), cancellable = true)
    private void arenaclash$skipArenaEntityCollision(HitResult hitResult, CallbackInfo ci) {
        if (hitResult.getType() != HitResult.Type.ENTITY) return;

        Entity self = (Entity) (Object) this;
        if (!isArenaProjectile(self)) return;

        EntityHitResult entityHit = (EntityHitResult) hitResult;
        if (isArenaEntity(entityHit.getEntity())) {
            ci.cancel();
        }
    }

    /**
     * Secondary interception: cancel onEntityHit if it's somehow reached directly.
     * This catches edge cases where a subclass calls onEntityHit without going
     * through the standard onCollision dispatch, or where a subclass's onCollision
     * override calls super.onCollision() and then processes the entity hit result
     * independently.
     */
    @Inject(method = "onEntityHit", at = @At("HEAD"), cancellable = true)
    private void arenaclash$skipArenaEntityHit(EntityHitResult entityHitResult, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!isArenaProjectile(self)) return;

        if (isArenaEntity(entityHitResult.getEntity())) {
            ci.cancel();
        }
    }

    private static boolean isArenaProjectile(Entity entity) {
        return entity.getCommandTags().contains("arenaclash_mob_projectile")
                || entity.getCommandTags().contains("arenaclash_tower_arrow");
    }

    private static boolean isArenaEntity(Entity entity) {
        return entity.getCommandTags().contains("arenaclash_mob")
                || entity.getCommandTags().contains("arenaclash_mob_hp")
                || entity.getCommandTags().contains("arenaclash_dmg_number")
                || entity.getCommandTags().contains("arenaclash_vex");
    }
}
