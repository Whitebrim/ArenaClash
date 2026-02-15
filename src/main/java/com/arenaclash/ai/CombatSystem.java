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
 * Custom combat. Damage applied via entity.setHealth() so all HP sources
 * (fire, combat, structures) are consistent.
 */
public class CombatSystem {

    public static void performAttack(ArenaMob attacker, ArenaMob defender, ServerWorld world) {
        if (attacker.isDead() || defender.isDead()) return;
        // Fix 4: Don't attack if this mob deals no damage
        if (attacker.getAttackDamage() <= 0) return;
        Entity aEntity = attacker.getEntity(world);
        Entity dEntity = defender.getEntity(world);
        if (aEntity == null || dEntity == null) return;

        defender.takeDamage(attacker.getAttackDamage(), world);
        applyKnockback(aEntity, dEntity);
        spawnDamageParticles(world, dEntity.getPos());
        playAttackSound(world, dEntity.getPos());
    }

    public static void attackStructure(ArenaMob attacker, ArenaStructure structure, ServerWorld world) {
        if (attacker.isDead() || structure.isDestroyed()) return;
        // Fix 4: Don't attack structure if this mob deals no damage
        if (attacker.getAttackDamage() <= 0) return;
        structure.damage(attacker.getAttackDamage(), world);
        spawnDamageParticles(world, Vec3d.ofCenter(structure.getPosition()));
        playAttackSound(world, Vec3d.ofCenter(structure.getPosition()));
    }

    private static void applyKnockback(Entity attacker, Entity defender) {
        GameConfig cfg = GameConfig.get();
        Vec3d dir = defender.getPos().subtract(attacker.getPos()).normalize();
        double kb = cfg.knockbackStrength;
        Vec3d pos = defender.getPos();
        defender.requestTeleport(pos.x + dir.x * kb, pos.y, pos.z + dir.z * kb);
    }

    private static void spawnDamageParticles(ServerWorld world, Vec3d pos) {
        world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR,
                pos.x, pos.y + 1.0, pos.z, 5, 0.3, 0.3, 0.3, 0.1);
    }

    private static void playAttackSound(ServerWorld world, Vec3d pos) {
        world.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE,
                1.0f, 0.8f + world.getRandom().nextFloat() * 0.4f);
    }
}
