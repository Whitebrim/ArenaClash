package com.arenaclash.ai;

import com.arenaclash.arena.ArenaMob;
import com.arenaclash.arena.ArenaStructure;
import com.arenaclash.config.GameConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

/**
 * Custom combat system for arena battles.
 * Handles damage calculation, knockback, and special weapon behaviors.
 * Replaces vanilla combat entirely for arena mobs.
 */
public class CombatSystem {

    /**
     * Perform a melee attack from attacker to defender.
     */
    public static void performAttack(ArenaMob attacker, ArenaMob defender, ServerWorld world) {
        if (attacker.isDead() || defender.isDead()) return;

        Entity attackerEntity = attacker.getEntity(world);
        Entity defenderEntity = defender.getEntity(world);
        if (attackerEntity == null || defenderEntity == null) return;

        double damage = attacker.getSourceCard().getAttack();
        // MVP: no weapon modifiers. In full version, check equipped weapon card.

        // Apply damage
        defender.takeDamage(damage, world);

        // Knockback
        applyKnockback(attackerEntity, defenderEntity);

        // Effects
        spawnDamageParticles(world, defenderEntity.getPos());
        playAttackSound(world, defenderEntity.getPos());

        // Sync entity health for visual
        if (defenderEntity instanceof LivingEntity living) {
            living.setHealth((float) Math.max(1, defender.getCurrentHP()));
        }
    }

    /**
     * Attack a structure (tower or throne).
     */
    public static void attackStructure(ArenaMob attacker, ArenaStructure structure, ServerWorld world) {
        if (attacker.isDead() || structure.isDestroyed()) return;

        double damage = attacker.getSourceCard().getAttack();
        // MVP: no weapon modifiers

        boolean destroyed = structure.damage(damage, world);

        Entity attackerEntity = attacker.getEntity(world);
        if (attackerEntity != null) {
            spawnDamageParticles(world, Vec3d.ofCenter(structure.getPosition()));
            playAttackSound(world, Vec3d.ofCenter(structure.getPosition()));
        }
    }

    /**
     * Apply knockback from attacker to defender.
     */
    private static void applyKnockback(Entity attacker, Entity defender) {
        GameConfig cfg = GameConfig.get();
        Vec3d direction = defender.getPos().subtract(attacker.getPos()).normalize();

        double kbX = direction.x * cfg.knockbackStrength;
        double kbY = 0.1 + cfg.knockbackStrength * 0.2;
        double kbZ = direction.z * cfg.knockbackStrength;

        Vec3d currentVel = defender.getVelocity();
        defender.setVelocity(
                currentVel.x + kbX,
                currentVel.y + kbY,
                currentVel.z + kbZ
        );
        defender.velocityModified = true;
    }

    /**
     * Spawn damage indicator particles.
     */
    private static void spawnDamageParticles(ServerWorld world, Vec3d pos) {
        world.spawnParticles(
                ParticleTypes.DAMAGE_INDICATOR,
                pos.x, pos.y + 1.0, pos.z,
                5,    // count
                0.3, 0.3, 0.3,  // spread
                0.1   // speed
        );
    }

    /**
     * Play attack sound.
     */
    private static void playAttackSound(ServerWorld world, Vec3d pos) {
        world.playSound(null,
                pos.x, pos.y, pos.z,
                SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                SoundCategory.HOSTILE,
                1.0f, 0.8f + world.getRandom().nextFloat() * 0.4f);
    }
}
