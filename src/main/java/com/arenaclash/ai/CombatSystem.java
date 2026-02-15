package com.arenaclash.ai;

import com.arenaclash.arena.ArenaDefinition;
import com.arenaclash.arena.Lane;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Combat system using real Minecraft damage/animations.
 *
 * Design philosophy (from ARENA_OVERHAUL.md):
 * - Real swingHand() for attack animations
 * - Real damage() for hurt effects (red flash, hurtTime)
 * - Real takeKnockback() clamped to lane
 * - Particle effects: DAMAGE_INDICATOR, CRIT on hit; SOUL, SMOKE on kill
 * - Mob-specific mechanics: Creeper AoE, Bee sting, Skeleton arrows, etc.
 * - Floating damage numbers via DamageNumberManager
 */
public class CombatSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-Combat");

    // Mob NBT tags
    public static final String TAG_TEAM = "arenaclash_team";
    public static final String TAG_LANE = "arenaclash_lane";
    public static final String TAG_MOB = "arenaclash_mob";
    public static final String TAG_LEVEL = "arenaclash_level";
    public static final String TAG_ATTACK_COOLDOWN = "arenaclash_atk_cd";
    public static final String TAG_CURRENT_TARGET = "arenaclash_target";
    public static final String TAG_SPECIAL_USED = "arenaclash_special";

    // Attack ranges by mob type
    private static final double DEFAULT_MELEE_RANGE = 2.5;
    private static final double SKELETON_RANGED_RANGE = 12.0;
    private static final double BLAZE_RANGED_RANGE = 14.0;
    private static final double GHAST_RANGED_RANGE = 16.0;
    private static final double WITCH_RANGED_RANGE = 10.0;
    private static final double ENDERMAN_TELEPORT_RANGE = 16.0;
    private static final double RAVAGER_CHARGE_RANGE = 6.0;

    // Cooldowns (ticks)
    private static final int DEFAULT_ATTACK_COOLDOWN = 20;
    private static final int WOLF_ATTACK_COOLDOWN = 10;     // Fast attacks
    private static final int SKELETON_SHOOT_COOLDOWN = 30;
    private static final int BLAZE_SHOOT_COOLDOWN = 40;
    private static final int WITCH_THROW_COOLDOWN = 50;
    private static final int ENDERMAN_TELEPORT_COOLDOWN = 60;
    private static final int RAVAGER_CHARGE_COOLDOWN = 80;

    private final ServerWorld world;
    private final ArenaDefinition arenaDef;
    private final DamageNumberManager damageNumbers;

    public CombatSystem(ServerWorld world, ArenaDefinition arenaDef) {
        this.world = world;
        this.arenaDef = arenaDef;
        this.damageNumbers = new DamageNumberManager(world);
    }

    public DamageNumberManager getDamageNumbers() { return damageNumbers; }

    /**
     * Main combat tick for a single mob.
     * Called each server tick during BATTLE phase.
     */
    public void tickMobCombat(LivingEntity mob) {
        if (mob.isDead() || mob.isRemoved()) return;

        NbtCompound data = mob.writeNbt(new NbtCompound());
        String teamStr = getTag(mob, TAG_TEAM);
        String laneStr = getTag(mob, TAG_LANE);
        if (teamStr == null || laneStr == null) return;

        TeamSide team = TeamSide.valueOf(teamStr);
        Lane.LaneId laneId = Lane.LaneId.valueOf(laneStr);

        // Decrement attack cooldown
        int cooldown = getIntTag(mob, TAG_ATTACK_COOLDOWN);
        if (cooldown > 0) {
            setIntTag(mob, TAG_ATTACK_COOLDOWN, cooldown - 1);
            return; // Still on cooldown
        }

        // Find target
        LivingEntity target = findTarget(mob, team, laneId);
        if (target == null) return;

        double distance = mob.squaredDistanceTo(target);
        double attackRange = getAttackRange(mob);

        if (distance <= attackRange * attackRange) {
            performAttack(mob, target, team, laneId);
        }
    }

    /**
     * Find the best target for this mob.
     * Priority: enemies on same lane, then structures.
     */
    private LivingEntity findTarget(LivingEntity mob, TeamSide team, Lane.LaneId laneId) {
        TeamSide enemyTeam = team.opponent();
        double searchRange = Math.max(getAttackRange(mob) + 4.0, 20.0);
        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : world.getOtherEntities(mob, mob.getBoundingBox().expand(searchRange))) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isDead() || living.isRemoved()) continue;

            String entTeam = getTag(living, TAG_TEAM);
            if (entTeam == null || !entTeam.equals(enemyTeam.name())) continue;

            // Prefer same-lane targets
            String entLane = getTag(living, TAG_LANE);
            double dist = mob.squaredDistanceTo(living);

            // Same lane bonus: treat as closer
            if (entLane != null && entLane.equals(laneId.name())) {
                dist *= 0.5;
            }

            if (dist < closestDist) {
                closestDist = dist;
                closest = living;
            }
        }

        return closest;
    }

    /**
     * Perform an attack from attacker to target.
     * Uses real Minecraft combat mechanics.
     */
    private void performAttack(LivingEntity attacker, LivingEntity target, TeamSide team, Lane.LaneId laneId) {
        // Mob-specific attack behaviors
        if (attacker instanceof CreeperEntity creeper) {
            performCreeperExplosion(creeper, target, team, laneId);
            return;
        }
        if (attacker instanceof BeeEntity bee) {
            performBeeAttack(bee, target, team, laneId);
            return;
        }
        if (attacker instanceof SkeletonEntity || attacker instanceof StrayEntity) {
            performSkeletonAttack(attacker, target, team, laneId);
            return;
        }
        if (attacker instanceof BlazeEntity) {
            performBlazeAttack(attacker, target, team, laneId);
            return;
        }
        if (attacker instanceof GhastEntity) {
            performGhastAttack(attacker, target, team, laneId);
            return;
        }
        if (attacker instanceof WitchEntity) {
            performWitchAttack(attacker, target, team, laneId);
            return;
        }
        if (attacker instanceof IronGolemEntity) {
            performGolemAttack(attacker, target, team, laneId);
            return;
        }
        if (attacker instanceof EndermanEntity) {
            performEndermanAttack(attacker, target, team, laneId);
            return;
        }
        if (attacker instanceof RavagerEntity) {
            performRavagerAttack(attacker, target, team, laneId);
            return;
        }
        if (attacker instanceof WolfEntity) {
            performWolfAttack(attacker, target, team, laneId);
            return;
        }

        // Default melee attack
        performMeleeAttack(attacker, target, team, laneId, DEFAULT_ATTACK_COOLDOWN);
    }

    // ========================================================================
    // STANDARD MELEE ATTACK
    // ========================================================================

    private void performMeleeAttack(LivingEntity attacker, LivingEntity target,
                                     TeamSide team, Lane.LaneId laneId, int cooldown) {
        float damage = getBaseDamage(attacker);

        // Swing arm animation (visible to all players)
        attacker.swingHand(Hand.MAIN_HAND, true);

        // Apply real damage — this triggers hurtTime (red flash), hurt sound
        DamageSource source = world.getDamageSources().mobAttack(attacker);
        boolean hit = target.damage(source, damage);

        if (hit) {
            // Lane-clamped knockback
            applyLaneKnockback(target, attacker, laneId, 0.4);

            // Hit particles
            spawnHitParticles(target.getPos());

            // Floating damage number
            damageNumbers.spawn(target.getPos(), damage, DamageNumberManager.DamageType.PHYSICAL);

            // Sound
            world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                    SoundCategory.HOSTILE, 0.7f, 0.8f + world.random.nextFloat() * 0.4f);
        }

        setIntTag(attacker, TAG_ATTACK_COOLDOWN, cooldown);

        // Check for kill
        if (target.isDead()) {
            onMobKill(attacker, target);
        }
    }

    // ========================================================================
    // MOB-SPECIFIC ATTACKS
    // ========================================================================

    /**
     * Creeper: AoE explosion on contact. Dies after.
     * No block damage — pure visual + entity damage.
     */
    private void performCreeperExplosion(CreeperEntity creeper, LivingEntity target,
                                          TeamSide team, Lane.LaneId laneId) {
        Vec3d pos = creeper.getPos();
        float baseDamage = getBaseDamage(creeper) * 2.5f; // AoE is stronger
        double aoeRadius = 4.0;

        // Visual explosion (no block damage)
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 1, pos.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.FLAME, pos.x, pos.y + 0.5, pos.z, 30, 1.5, 1.0, 1.5, 0.05);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 1, pos.z, 20, 1.5, 1.0, 1.5, 0.02);

        // Sound
        world.playSound(null, creeper.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(),
                SoundCategory.HOSTILE, 1.5f, 0.8f);

        // Damage all enemies in radius
        TeamSide enemyTeam = team.opponent();
        for (Entity entity : world.getOtherEntities(creeper, creeper.getBoundingBox().expand(aoeRadius))) {
            if (!(entity instanceof LivingEntity living)) continue;
            String entTeam = getTag(living, TAG_TEAM);
            if (entTeam == null || !entTeam.equals(enemyTeam.name())) continue;

            double dist = living.distanceTo(creeper);
            float dmg = (float) (baseDamage * (1.0 - dist / aoeRadius));
            if (dmg > 0) {
                living.damage(world.getDamageSources().explosion(creeper, creeper), dmg);
                applyLaneKnockback(living, creeper, laneId, 0.8);
                damageNumbers.spawn(living.getPos(), dmg, DamageNumberManager.DamageType.AOE);
            }
        }

        // Creeper dies
        creeper.kill();
    }

    /**
     * Bee: Sting attack with particle effect. Does NOT lose stinger (cooldown instead).
     */
    private void performBeeAttack(BeeEntity bee, LivingEntity target,
                                    TeamSide team, Lane.LaneId laneId) {
        float damage = getBaseDamage(bee);

        bee.swingHand(Hand.MAIN_HAND, true);
        boolean hit = target.damage(world.getDamageSources().sting(bee), damage);

        if (hit) {
            // Poison effect
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 60, 0));

            // Yellow pollen particles
            world.spawnParticles(ParticleTypes.FALLING_SPORE_BLOSSOM,
                    target.getX(), target.getY() + 1, target.getZ(), 8, 0.3, 0.3, 0.3, 0.02);

            applyLaneKnockback(target, bee, laneId, 0.2);
            damageNumbers.spawn(target.getPos(), damage, DamageNumberManager.DamageType.MAGIC);

            world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_BEE_STING,
                    SoundCategory.HOSTILE, 0.8f, 1.0f);
        }

        // Bee doesn't die — cooldown instead
        setIntTag(bee, TAG_ATTACK_COOLDOWN, DEFAULT_ATTACK_COOLDOWN);

        if (target.isDead()) onMobKill(bee, target);
    }

    /**
     * Skeleton/Stray: Shoots real arrow projectiles.
     */
    private void performSkeletonAttack(LivingEntity skeleton, LivingEntity target,
                                        TeamSide team, Lane.LaneId laneId) {
        // Spawn a real arrow with trajectory
        Vec3d start = skeleton.getPos().add(0, skeleton.getStandingEyeHeight(), 0);
        Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.6, 0);
        Vec3d direction = targetPos.subtract(start).normalize();

        ArrowEntity arrow = new ArrowEntity(world, skeleton, new ItemStack(Items.ARROW), null);
        arrow.setPosition(start);
        arrow.setVelocity(direction.x, direction.y + 0.1, direction.z, 1.6f, 2.0f);
        arrow.setDamage(getBaseDamage(skeleton));
        arrow.pickupType = ArrowEntity.PickupPermission.DISALLOWED;

        // Tag arrow so we know which team it belongs to
        NbtCompound arrowNbt = new NbtCompound();
        arrowNbt.putString(TAG_TEAM, team.name());
        arrow.writeCustomDataToNbt(arrowNbt);

        world.spawnEntity(arrow);

        // Sound
        world.playSound(null, skeleton.getBlockPos(), SoundEvents.ENTITY_SKELETON_SHOOT,
                SoundCategory.HOSTILE, 1.0f, 1.0f / (world.random.nextFloat() * 0.4f + 0.8f));

        setIntTag(skeleton, TAG_ATTACK_COOLDOWN, SKELETON_SHOOT_COOLDOWN);
    }

    /**
     * Blaze: Shoots fireballs.
     */
    private void performBlazeAttack(LivingEntity blaze, LivingEntity target,
                                     TeamSide team, Lane.LaneId laneId) {
        Vec3d start = blaze.getPos().add(0, blaze.getStandingEyeHeight(), 0);
        Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.5, 0);
        Vec3d dir = targetPos.subtract(start).normalize();

        SmallFireballEntity fireball = new SmallFireballEntity(world, blaze, new Vec3d(dir.x, dir.y, dir.z));
        fireball.setPosition(start.x, start.y, start.z);
        world.spawnEntity(fireball);

        // Fire particles along path
        world.spawnParticles(ParticleTypes.FLAME, start.x, start.y, start.z, 5, 0.2, 0.2, 0.2, 0.01);

        world.playSound(null, blaze.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT,
                SoundCategory.HOSTILE, 1.0f, 1.0f);

        setIntTag(blaze, TAG_ATTACK_COOLDOWN, BLAZE_SHOOT_COOLDOWN);
    }

    /**
     * Ghast: Shoots large fireballs.
     */
    private void performGhastAttack(LivingEntity ghast, LivingEntity target,
                                     TeamSide team, Lane.LaneId laneId) {
        Vec3d start = ghast.getPos().add(0, ghast.getStandingEyeHeight(), 0);
        Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.5, 0);
        Vec3d dir = targetPos.subtract(start).normalize();

        FireballEntity fireball = new FireballEntity(world, ghast, new Vec3d(dir.x, dir.y, dir.z), 1);
        fireball.setPosition(start.x, start.y, start.z);
        world.spawnEntity(fireball);

        world.playSound(null, ghast.getBlockPos(), SoundEvents.ENTITY_GHAST_SHOOT,
                SoundCategory.HOSTILE, 1.0f, 1.0f);

        setIntTag(ghast, TAG_ATTACK_COOLDOWN, BLAZE_SHOOT_COOLDOWN);
    }

    /**
     * Witch: Throws splash potion that creates AoE debuff zone.
     */
    private void performWitchAttack(LivingEntity witch, LivingEntity target,
                                     TeamSide team, Lane.LaneId laneId) {
        Vec3d targetPos = target.getPos();
        float damage = getBaseDamage(witch) * 0.5f;

        // Visual: purple particles arcing toward target
        witch.swingHand(Hand.MAIN_HAND, true);

        // AoE debuff zone at target location
        double aoeRadius = 3.0;
        TeamSide enemyTeam = team.opponent();

        // Purple particle burst at impact
        DustParticleEffect purple = new DustParticleEffect(new Vector3f(0.6f, 0.1f, 0.8f), 1.0f);
        world.spawnParticles(purple, targetPos.x, targetPos.y + 0.5, targetPos.z, 25, aoeRadius * 0.5, 0.3, aoeRadius * 0.5, 0.01);

        for (Entity entity : world.getOtherEntities(witch, target.getBoundingBox().expand(aoeRadius))) {
            if (!(entity instanceof LivingEntity living)) continue;
            String entTeam = getTag(living, TAG_TEAM);
            if (entTeam == null || !entTeam.equals(enemyTeam.name())) continue;

            living.damage(world.getDamageSources().magic(), damage);
            living.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
            damageNumbers.spawn(living.getPos(), damage, DamageNumberManager.DamageType.MAGIC);
        }

        world.playSound(null, witch.getBlockPos(), SoundEvents.ENTITY_WITCH_THROW,
                SoundCategory.HOSTILE, 1.0f, 1.0f);

        setIntTag(witch, TAG_ATTACK_COOLDOWN, WITCH_THROW_COOLDOWN);
    }

    /**
     * Iron Golem: Launches enemies upward with powerful knockback.
     */
    private void performGolemAttack(LivingEntity golem, LivingEntity target,
                                     TeamSide team, Lane.LaneId laneId) {
        float damage = getBaseDamage(golem) * 1.5f;

        golem.swingHand(Hand.MAIN_HAND, true);
        boolean hit = target.damage(world.getDamageSources().mobAttack(golem), damage);

        if (hit) {
            // Launch upward!
            target.setVelocity(target.getVelocity().add(0, 0.6, 0));
            target.velocityModified = true;
            applyLaneKnockback(target, golem, laneId, 1.2);

            spawnHitParticles(target.getPos());
            damageNumbers.spawn(target.getPos(), damage, DamageNumberManager.DamageType.PHYSICAL);

            world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK,
                    SoundCategory.HOSTILE, 1.0f, 0.8f);
        }

        setIntTag(golem, TAG_ATTACK_COOLDOWN, DEFAULT_ATTACK_COOLDOWN + 10);
        if (target.isDead()) onMobKill(golem, target);
    }

    /**
     * Enderman: Teleports to target with visual effects.
     */
    private void performEndermanAttack(LivingEntity enderman, LivingEntity target,
                                        TeamSide team, Lane.LaneId laneId) {
        // Teleport particles at origin
        world.spawnParticles(ParticleTypes.PORTAL, enderman.getX(), enderman.getY() + 1,
                enderman.getZ(), 20, 0.5, 1.0, 0.5, 0.1);

        // Teleport to near target
        Lane lane = new Lane(laneId, arenaDef.getLane(laneId));
        Vec3d teleportPos = target.getPos().add(
                (world.random.nextDouble() - 0.5) * 2,
                0,
                (world.random.nextDouble() - 0.5) * 2
        );
        teleportPos = lane.clampToLane(teleportPos);
        enderman.requestTeleport(teleportPos.x, teleportPos.y, teleportPos.z);

        // Teleport particles at destination
        world.spawnParticles(ParticleTypes.PORTAL, teleportPos.x, teleportPos.y + 1,
                teleportPos.z, 20, 0.5, 1.0, 0.5, 0.1);

        // Sound
        world.playSound(null, enderman.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT,
                SoundCategory.HOSTILE, 1.0f, 1.0f);

        // Then melee attack
        performMeleeAttack(enderman, target, team, laneId, ENDERMAN_TELEPORT_COOLDOWN);
    }

    /**
     * Wolf: Fast series of attacks.
     */
    private void performWolfAttack(LivingEntity wolf, LivingEntity target,
                                    TeamSide team, Lane.LaneId laneId) {
        performMeleeAttack(wolf, target, team, laneId, WOLF_ATTACK_COOLDOWN);
    }

    /**
     * Ravager: Charge attack with AoE knockback.
     */
    private void performRavagerAttack(LivingEntity ravager, LivingEntity target,
                                       TeamSide team, Lane.LaneId laneId) {
        float damage = getBaseDamage(ravager) * 2.0f;

        ravager.swingHand(Hand.MAIN_HAND, true);

        // AoE in front
        double aoeRadius = 3.0;
        TeamSide enemyTeam = team.opponent();
        for (Entity entity : world.getOtherEntities(ravager, ravager.getBoundingBox().expand(aoeRadius))) {
            if (!(entity instanceof LivingEntity living)) continue;
            String entTeam = getTag(living, TAG_TEAM);
            if (entTeam == null || !entTeam.equals(enemyTeam.name())) continue;

            living.damage(world.getDamageSources().mobAttack(ravager), damage);
            applyLaneKnockback(living, ravager, laneId, 1.5);
            damageNumbers.spawn(living.getPos(), damage, DamageNumberManager.DamageType.PHYSICAL);
        }

        // Ground slam particles
        world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                ravager.getX(), ravager.getY(), ravager.getZ(), 15, 2.0, 0.2, 2.0, 0.01);

        world.playSound(null, ravager.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ATTACK,
                SoundCategory.HOSTILE, 1.2f, 0.8f);

        setIntTag(ravager, TAG_ATTACK_COOLDOWN, RAVAGER_CHARGE_COOLDOWN);
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    /**
     * Apply knockback clamped to lane boundaries.
     */
    private void applyLaneKnockback(LivingEntity target, LivingEntity attacker,
                                     Lane.LaneId laneId, double strength) {
        double dx = target.getX() - attacker.getX();
        double dz = target.getZ() - attacker.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) {
            dx = 1; dz = 0; dist = 1;
        }

        target.takeKnockback(strength, -dx / dist, -dz / dist);

        // After knockback, schedule lane clamp on next tick
        // We store a flag so the ArenaMob tick can clamp it
        NbtCompound nbt = new NbtCompound();
        target.writeNbt(nbt);
        nbt.putBoolean("arenaclash_needs_clamp", true);
    }

    /**
     * Spawn hit particles at impact point.
     */
    private void spawnHitParticles(Vec3d pos) {
        world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, pos.x, pos.y + 1.0, pos.z, 3, 0.2, 0.2, 0.2, 0.1);
        world.spawnParticles(ParticleTypes.CRIT, pos.x, pos.y + 1.2, pos.z, 5, 0.3, 0.3, 0.3, 0.1);
    }

    /**
     * Called when a mob kills another mob.
     */
    private void onMobKill(LivingEntity killer, LivingEntity victim) {
        Vec3d pos = victim.getPos();

        // Death particles
        world.spawnParticles(ParticleTypes.SOUL, pos.x, pos.y + 0.5, pos.z, 8, 0.3, 0.5, 0.3, 0.05);
        world.spawnParticles(ParticleTypes.SMOKE, pos.x, pos.y + 0.5, pos.z, 10, 0.5, 0.5, 0.5, 0.02);

        // Kill sound
        world.playSound(null, victim.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.HOSTILE, 0.5f, 0.6f);
    }

    /**
     * Get base damage for a mob based on its type and level.
     */
    private float getBaseDamage(LivingEntity mob) {
        int level = getIntTag(mob, TAG_LEVEL);
        if (level <= 0) level = 1;

        float base = 3.0f; // Default
        if (mob instanceof ZombieEntity) base = 4.0f;
        else if (mob instanceof SkeletonEntity || mob instanceof StrayEntity) base = 3.5f;
        else if (mob instanceof CreeperEntity) base = 6.0f;
        else if (mob instanceof SpiderEntity) base = 3.0f;
        else if (mob instanceof BeeEntity) base = 2.0f;
        else if (mob instanceof BlazeEntity) base = 4.0f;
        else if (mob instanceof GhastEntity) base = 5.0f;
        else if (mob instanceof WitchEntity) base = 3.0f;
        else if (mob instanceof IronGolemEntity) base = 7.0f;
        else if (mob instanceof EndermanEntity) base = 5.0f;
        else if (mob instanceof WolfEntity) base = 2.5f;
        else if (mob instanceof RavagerEntity) base = 8.0f;
        else if (mob instanceof WitherSkeletonEntity) base = 5.0f;
        else if (mob instanceof PiglinEntity) base = 4.0f;

        // Scale with level
        return base + (level - 1) * 0.5f;
    }

    /**
     * Get attack range for a mob.
     */
    private double getAttackRange(LivingEntity mob) {
        if (mob instanceof SkeletonEntity || mob instanceof StrayEntity) return SKELETON_RANGED_RANGE;
        if (mob instanceof BlazeEntity) return BLAZE_RANGED_RANGE;
        if (mob instanceof GhastEntity) return GHAST_RANGED_RANGE;
        if (mob instanceof WitchEntity) return WITCH_RANGED_RANGE;
        if (mob instanceof EndermanEntity) return ENDERMAN_TELEPORT_RANGE;
        if (mob instanceof RavagerEntity) return RAVAGER_CHARGE_RANGE;
        return DEFAULT_MELEE_RANGE;
    }

    // ========================================================================
    // NBT TAG UTILITIES
    // ========================================================================

    public static String getTag(LivingEntity mob, String key) {
        NbtCompound nbt = new NbtCompound();
        mob.writeNbt(nbt);
        return nbt.contains(key) ? nbt.getString(key) : null;
    }

    public static int getIntTag(LivingEntity mob, String key) {
        NbtCompound nbt = new NbtCompound();
        mob.writeNbt(nbt);
        return nbt.contains(key) ? nbt.getInt(key) : 0;
    }

    public static void setTag(LivingEntity mob, String key, String value) {
        NbtCompound nbt = new NbtCompound();
        mob.writeNbt(nbt);
        nbt.putString(key, value);
        mob.readNbt(nbt);
    }

    public static void setIntTag(LivingEntity mob, String key, int value) {
        NbtCompound nbt = new NbtCompound();
        mob.writeNbt(nbt);
        nbt.putInt(key, value);
        mob.readNbt(nbt);
    }

    /**
     * Mark a mob as an ArenaClash mob with team, lane, and level info.
     */
    public static void tagMob(LivingEntity mob, TeamSide team, Lane.LaneId lane, int level) {
        NbtCompound nbt = new NbtCompound();
        mob.writeNbt(nbt);
        nbt.putBoolean(TAG_MOB, true);
        nbt.putString(TAG_TEAM, team.name());
        nbt.putString(TAG_LANE, lane.name());
        nbt.putInt(TAG_LEVEL, level);
        nbt.putInt(TAG_ATTACK_COOLDOWN, 0);
        mob.readNbt(nbt);
    }

    /**
     * Check if an entity is a tagged ArenaClash mob.
     */
    public static boolean isArenaMob(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        NbtCompound nbt = new NbtCompound();
        living.writeNbt(nbt);
        return nbt.getBoolean(TAG_MOB);
    }
}
