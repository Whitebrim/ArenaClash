package com.arenaclash.arena;

import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Tracks in-flight projectiles and applies damage when they reach their target.
 *
 * Instead of dealing damage instantly when a ranged attack fires (with the projectile
 * being purely visual), this system monitors projectile entities each tick and applies
 * damage only when the projectile gets close enough to its target. This creates proper
 * timing where you see the arrow/fireball fly and then the damage number appears on hit.
 *
 * Supports single-target hits (arrows, fireballs) and AOE hits (ghast fireballs).
 */
public class ProjectileTracker {

    /**
     * Effect type applied when a projectile hits its target.
     */
    public enum HitEffect {
        /** No extra effect, just damage */
        NONE,
        /** Blaze fireball: set target on fire for DOT */
        BLAZE_FIRE,
        /** Ghast fireball: AOE explosion damage like creeper */
        GHAST_AOE,
        /** Wither skull: apply wither status effect */
        WITHER_EFFECT,
        /** Cave spider arrow/melee proxy: poison */
        POISON
    }

    /**
     * A projectile currently in flight being tracked.
     */
    public static class TrackedProjectile {
        public final UUID projectileEntityId;
        public final TeamSide attackerTeam;
        public final ArenaMob targetMob;          // null if targeting structure
        public final ArenaStructure targetStructure; // null if targeting mob
        public final double damage;
        public final HitEffect hitEffect;
        public final double hitRadius;
        public final double aoeRadius;
        public final boolean isTowerProjectile;
        public int age = 0;
        public final int maxAge;

        public TrackedProjectile(UUID projectileEntityId, TeamSide attackerTeam,
                                 ArenaMob targetMob, ArenaStructure targetStructure,
                                 double damage, HitEffect hitEffect,
                                 double hitRadius, double aoeRadius,
                                 boolean isTowerProjectile, int maxAge) {
            this.projectileEntityId = projectileEntityId;
            this.attackerTeam = attackerTeam;
            this.targetMob = targetMob;
            this.targetStructure = targetStructure;
            this.damage = damage;
            this.hitEffect = hitEffect;
            this.hitRadius = hitRadius;
            this.aoeRadius = aoeRadius;
            this.isTowerProjectile = isTowerProjectile;
            this.maxAge = maxAge;
        }
    }

    private final List<TrackedProjectile> trackedProjectiles = new ArrayList<>();

    /**
     * Register a projectile targeting a mob.
     */
    public void trackMobProjectile(UUID projectileId, TeamSide attackerTeam,
                                   ArenaMob target, double damage,
                                   HitEffect effect, double hitRadius, double aoeRadius) {
        trackedProjectiles.add(new TrackedProjectile(
                projectileId, attackerTeam, target, null,
                damage, effect, hitRadius, aoeRadius,
                false, 100 // 5 seconds max flight time
        ));
    }

    /**
     * Register a projectile targeting a structure (tower/throne).
     */
    public void trackStructureProjectile(UUID projectileId, TeamSide attackerTeam,
                                         ArenaStructure target, double damage,
                                         HitEffect effect, double hitRadius) {
        trackedProjectiles.add(new TrackedProjectile(
                projectileId, attackerTeam, null, target,
                damage, effect, hitRadius, 0,
                false, 100
        ));
    }

    /**
     * Register a tower arrow targeting a mob.
     */
    public void trackTowerArrow(UUID arrowId, TeamSide towerOwner,
                                ArenaMob target, double damage) {
        trackedProjectiles.add(new TrackedProjectile(
                arrowId, towerOwner, target, null,
                damage, HitEffect.NONE, 2.0, 0,
                true, 100
        ));
    }

    /**
     * Tick all tracked projectiles. Check if they've reached their targets.
     */
    public void tick(ServerWorld world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        Iterator<TrackedProjectile> it = trackedProjectiles.iterator();
        while (it.hasNext()) {
            TrackedProjectile tp = it.next();
            tp.age++;

            // Safety: remove if too old
            if (tp.age > tp.maxAge) {
                Entity proj = world.getEntity(tp.projectileEntityId);
                if (proj != null) proj.discard();
                it.remove();
                continue;
            }

            Entity proj = world.getEntity(tp.projectileEntityId);

            // Projectile entity gone (despawned/discarded) — remove tracking
            if (proj == null || !proj.isAlive()) {
                it.remove();
                continue;
            }

            if (tp.targetMob != null) {
                // Targeting a mob
                if (tp.targetMob.isDead()) {
                    // Target died before projectile arrived — discard projectile
                    proj.discard();
                    it.remove();
                    continue;
                }

                Entity targetEntity = tp.targetMob.getEntity(world);
                if (targetEntity == null) {
                    proj.discard();
                    it.remove();
                    continue;
                }

                // Check distance — use horizontal + vertical proximity
                double dist = proj.squaredDistanceTo(targetEntity);
                if (dist <= tp.hitRadius * tp.hitRadius) {
                    // HIT! Apply damage and effects
                    applyMobHit(tp, proj, targetEntity, world, allMobs, structures);
                    proj.discard();
                    it.remove();
                }
            } else if (tp.targetStructure != null) {
                // Targeting a structure
                if (tp.targetStructure.isDestroyed()) {
                    proj.discard();
                    it.remove();
                    continue;
                }

                Vec3d sPos = Vec3d.ofCenter(tp.targetStructure.getPosition()).add(0, 2, 0);
                double dist = proj.getPos().squaredDistanceTo(sPos);
                if (dist <= (tp.hitRadius + 2.0) * (tp.hitRadius + 2.0)) {
                    // HIT structure
                    applyStructureHit(tp, proj, world, allMobs, structures);
                    proj.discard();
                    it.remove();
                }
            }
        }
    }

    /**
     * Apply damage when a projectile hits a mob target.
     */
    private void applyMobHit(TrackedProjectile tp, Entity proj, Entity targetEntity,
                             ServerWorld world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        float dmg = (float) tp.damage;

        if (tp.hitEffect == HitEffect.GHAST_AOE) {
            // Ghast fireball: AOE explosion like creeper
            applyGhastAOE(tp, proj.getPos(), world, allMobs, structures);
        } else {
            // Single-target damage
            tp.targetMob.takeDamage(dmg, world);
            ArenaMob.spawnDamageNumber(world,
                    targetEntity.getPos().add(0, targetEntity.getHeight() + 0.3, 0), dmg);

            if (targetEntity instanceof LivingEntity living) {
                living.hurtTime = 10;
                living.maxHurtTime = 10;
            }

            // Visual particles on hit
            int pCount = Math.min((int)(dmg / 2) + 1, 5);
            world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR,
                    targetEntity.getX(), targetEntity.getBodyY(0.5), targetEntity.getZ(),
                    pCount, 0.3, 0.2, 0.3, 0.1);
        }

        // Apply hit effects
        switch (tp.hitEffect) {
            case BLAZE_FIRE -> targetEntity.setOnFireFor(3);
            case WITHER_EFFECT -> {
                if (targetEntity instanceof LivingEntity lt) {
                    lt.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.WITHER, 100, 1));
                }
            }
            default -> {}
        }
    }

    /**
     * Apply ghast fireball AOE explosion — damages all enemies in radius like creeper,
     * plus damages enemy structures in range.
     */
    private void applyGhastAOE(TrackedProjectile tp, Vec3d center,
                               ServerWorld world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        double explosionRadius = tp.aoeRadius > 0 ? tp.aoeRadius : 4.0;
        float explosionDamage = (float) tp.damage;

        // Damage all enemy mobs in radius
        for (ArenaMob mob : allMobs) {
            if (mob.getTeam() == tp.attackerTeam || mob.isDead()) continue;
            Entity e = mob.getEntity(world);
            if (e == null) continue;
            double dx = center.x - e.getX(), dz = center.z - e.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= explosionRadius) {
                // Damage falloff: full at center, zero at edge
                float dmg = (float) (explosionDamage * (1.0 - dist / explosionRadius));
                if (dmg < 0.5f) continue;
                mob.takeDamage(dmg, world);
                ArenaMob.spawnDamageNumber(world,
                        e.getPos().add(0, e.getHeight() + 0.3, 0), dmg);

                // Knockback away from explosion center
                if (dist > 0.01) {
                    Vec3d kb = e.getPos().subtract(center).normalize().multiply(1.2);
                    double nx = e.getX() + kb.x;
                    double nz = e.getZ() + kb.z;
                    e.requestTeleport(nx, e.getY(), nz);
                }

                if (e instanceof LivingEntity living) {
                    living.hurtTime = 10;
                    living.maxHurtTime = 10;
                }
            }
        }

        // Damage enemy structures in range
        for (ArenaStructure struct : structures) {
            if (struct.getOwner() == tp.attackerTeam || struct.isDestroyed()) continue;
            Vec3d sPos = Vec3d.ofCenter(struct.getPosition());
            double dx = center.x - sPos.x, dz = center.z - sPos.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= explosionRadius + 2.0) {
                struct.damage(explosionDamage * 1.5f, world);
                ArenaMob.spawnDamageNumber(world, sPos.add(0, 3, 0), explosionDamage * 1.5f);
            }
        }

        // Explosion visual and sound effects
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 0.5, center.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.CLOUD, center.x, center.y + 0.5, center.z, 15, 1.2, 0.8, 1.2, 0.1);
        world.spawnParticles(ParticleTypes.FLAME, center.x, center.y + 0.5, center.z, 10, 1.0, 0.6, 1.0, 0.05);
        world.playSound(null, center.x, center.y, center.z,
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 1.0f);
    }

    /**
     * Apply damage when a projectile hits a structure.
     */
    private void applyStructureHit(TrackedProjectile tp, Entity proj, ServerWorld world,
                                   List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        if (tp.hitEffect == HitEffect.GHAST_AOE) {
            // Ghast fireball hitting a structure also does AOE to nearby mobs
            applyGhastAOE(tp, proj.getPos(), world, allMobs, structures);
        } else {
            float dmg = (float) tp.damage;
            tp.targetStructure.damage(dmg, world);

            Vec3d sp = Vec3d.ofCenter(tp.targetStructure.getPosition()).add(0, 1, 0);
            world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, sp.x, sp.y, sp.z, 3, 0.5, 0.3, 0.5, 0.1);
            world.spawnParticles(ParticleTypes.SMOKE, sp.x, sp.y, sp.z, 3, 0.5, 0.5, 0.5, 0.02);
            ArenaMob.spawnDamageNumber(world, sp.add(0, 1.5, 0), dmg);
        }
    }

    /**
     * Clear all tracked projectiles and discard their entities.
     */
    public void cleanup(ServerWorld world) {
        for (TrackedProjectile tp : trackedProjectiles) {
            if (world != null) {
                Entity proj = world.getEntity(tp.projectileEntityId);
                if (proj != null) proj.discard();
            }
        }
        trackedProjectiles.clear();
    }

    public int getTrackedCount() {
        return trackedProjectiles.size();
    }
}
