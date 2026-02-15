package com.arenaclash.arena;

import com.arenaclash.ai.CombatSystem;
import com.arenaclash.ai.DamageNumberManager;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Represents a structure on the arena (Tower or Throne).
 *
 * TOWERS:
 * - Fire real ArrowEntity projectiles at nearest enemy on associated lane
 * - Arrows spawn from top of tower, fly with arc
 * - Visual: arrow entity visible to all players
 * - Sound: entity.arrow.shoot
 *
 * THRONES:
 * - AoE shockwave attack: expanding ring of colored particles
 * - Damage falloff by distance from throne center
 * - Sound: entity.warden.sonic_boom (low volume)
 * - Knockback away from throne
 */
public class ArenaStructure {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-Structure");

    public enum StructureType {
        TOWER, THRONE
    }

    private final StructureType type;
    private final TeamSide team;
    private final ArenaDefinition.StructureDef definition;
    private final ServerWorld world;

    private double currentHp;
    private int attackCooldownRemaining = 0;
    private boolean destroyed = false;

    // Throne AoE ring animation
    private int aoeTick = -1;
    private static final int AOE_RING_DURATION = 10; // Ticks for ring expansion

    public ArenaStructure(StructureType type, TeamSide team, ArenaDefinition.StructureDef definition, ServerWorld world) {
        this.type = type;
        this.team = team;
        this.definition = definition;
        this.world = world;
        this.currentHp = definition.hp;
    }

    /**
     * Main tick — handles attack cooldown and targeting.
     */
    public void tick(DamageNumberManager damageNumbers) {
        if (destroyed) return;

        // Tick AoE ring animation
        if (aoeTick >= 0) {
            tickAoeRing();
        }

        // Attack cooldown
        if (attackCooldownRemaining > 0) {
            attackCooldownRemaining--;
            return;
        }

        // Find target and attack
        LivingEntity target = findTarget();
        if (target != null) {
            if (type == StructureType.TOWER) {
                fireArrow(target);
            } else {
                fireAoeShockwave(damageNumbers);
            }
            attackCooldownRemaining = definition.attackCooldown;
        }
    }

    /**
     * Tower: Fire a real arrow at the target.
     */
    private void fireArrow(LivingEntity target) {
        // Arrow spawns from top of structure
        Vec3d spawnPos = new Vec3d(
                definition.position.getX() + 0.5,
                definition.boundsMax.getY() + 0.5,
                definition.position.getZ() + 0.5
        );

        Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.5, 0);
        Vec3d direction = targetPos.subtract(spawnPos);
        double dist = direction.length();
        direction = direction.normalize();

        // Add arc: more upward velocity for longer distances
        double arcBonus = Math.min(dist * 0.02, 0.3);

        ArrowEntity arrow = new ArrowEntity(world, spawnPos.x, spawnPos.y, spawnPos.z, new net.minecraft.item.ItemStack(net.minecraft.item.Items.ARROW), null);
        arrow.setPosition(spawnPos);
        arrow.setVelocity(direction.x, direction.y + arcBonus, direction.z, 1.8f, 1.0f);
        arrow.setDamage(definition.attackDamage);
        arrow.pickupType = ArrowEntity.PickupPermission.DISALLOWED;

        world.spawnEntity(arrow);

        // Sound
        world.playSound(null, definition.position, SoundEvents.ENTITY_ARROW_SHOOT,
                SoundCategory.BLOCKS, 1.0f, 0.9f + world.random.nextFloat() * 0.2f);
    }

    /**
     * Throne: AoE shockwave attack with expanding particle ring.
     */
    private void fireAoeShockwave(DamageNumberManager damageNumbers) {
        Vec3d center = Vec3d.ofCenter(definition.position).add(0, 1, 0);
        double range = definition.attackRange;
        TeamSide enemyTeam = team.opponent();

        // Start AoE ring animation
        aoeTick = 0;

        // Sound: Warden sonic boom at low volume
        world.playSound(null, definition.position, SoundEvents.ENTITY_WARDEN_SONIC_BOOM,
                SoundCategory.BLOCKS, 0.4f, 1.5f);

        // Damage all enemies in range
        for (Entity entity : world.getOtherEntities(null,
                net.minecraft.util.math.Box.of(center, range * 2, range * 2, range * 2))) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isDead() || living.isRemoved()) continue;

            String entTeam = CombatSystem.getTag(living, CombatSystem.TAG_TEAM);
            if (entTeam == null || !entTeam.equals(enemyTeam.name())) continue;

            double dist = living.getPos().distanceTo(center);
            if (dist > range) continue;

            // Damage falloff: full at center, 30% at edge
            float dmg = (float) (definition.attackDamage * (1.0 - 0.7 * (dist / range)));
            living.damage(world.getDamageSources().magic(), dmg);

            // Knockback away from throne
            double dx = living.getX() - center.x;
            double dz = living.getZ() - center.z;
            double kbDist = Math.sqrt(dx * dx + dz * dz);
            if (kbDist > 0.01) {
                living.takeKnockback(0.5, -dx / kbDist, -dz / kbDist);
            }

            damageNumbers.spawn(living.getPos(), dmg, DamageNumberManager.DamageType.AOE);
        }
    }

    /**
     * Tick the AoE ring particle animation.
     */
    private void tickAoeRing() {
        if (aoeTick >= AOE_RING_DURATION) {
            aoeTick = -1;
            return;
        }

        Vec3d center = Vec3d.ofCenter(definition.position).add(0, 0.5, 0);
        double progress = (double) aoeTick / AOE_RING_DURATION;
        double radius = definition.attackRange * progress;

        // Ring color based on team
        Vector3f color = team == TeamSide.PLAYER1
                ? new Vector3f(0.2f, 0.5f, 1.0f)   // Blue for P1
                : new Vector3f(1.0f, 0.2f, 0.2f);  // Red for P2
        DustParticleEffect dust = new DustParticleEffect(color, 1.5f);

        // Spawn particles in a ring
        int particleCount = (int) (radius * 8);
        for (int i = 0; i < particleCount; i++) {
            double angle = 2 * Math.PI * i / particleCount;
            double px = center.x + Math.cos(angle) * radius;
            double pz = center.z + Math.sin(angle) * radius;
            world.spawnParticles(dust, px, center.y, pz, 1, 0, 0.1, 0, 0);
        }

        aoeTick++;
    }

    /**
     * Find nearest enemy in range.
     */
    private LivingEntity findTarget() {
        TeamSide enemyTeam = team.opponent();
        Vec3d center = Vec3d.ofCenter(definition.position);
        double range = definition.attackRange;

        LivingEntity nearest = null;
        double nearestDist = range * range;

        for (Entity entity : world.getOtherEntities(null,
                net.minecraft.util.math.Box.of(center, range * 2, range * 2, range * 2))) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isDead() || living.isRemoved()) continue;

            String entTeam = CombatSystem.getTag(living, CombatSystem.TAG_TEAM);
            if (entTeam == null || !entTeam.equals(enemyTeam.name())) continue;

            double dist = living.squaredDistanceTo(center.x, center.y, center.z);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = living;
            }
        }

        return nearest;
    }

    /**
     * Apply damage to this structure.
     */
    public void takeDamage(double damage, LivingEntity attacker, DamageNumberManager damageNumbers) {
        if (destroyed) return;

        currentHp -= damage;
        damageNumbers.spawn(Vec3d.ofCenter(definition.position).add(0, 2, 0),
                (float) damage, DamageNumberManager.DamageType.PHYSICAL);

        if (currentHp <= 0) {
            destroy();
        }
    }

    /**
     * Destroy this structure with visual effects.
     */
    private void destroy() {
        destroyed = true;
        currentHp = 0;

        Vec3d center = Vec3d.ofCenter(definition.position);

        // Explosion particles
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 1, center.z, 3, 1, 1, 1, 0);
        world.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, center.x, center.y + 2, center.z, 20, 2, 2, 2, 0.05);

        // Block debris particles (smoke + breaking)
        world.spawnParticles(ParticleTypes.SMOKE, center.x, center.y + 1, center.z, 40, 2, 2, 2, 0.1);

        // Sound
        world.playSound(null, definition.position, SoundEvents.ENTITY_GENERIC_EXPLODE.value(),
                SoundCategory.BLOCKS, 2.0f, 0.5f);
        world.playSound(null, definition.position, SoundEvents.BLOCK_ANVIL_DESTROY,
                SoundCategory.BLOCKS, 1.5f, 0.7f);

        LOGGER.info("{} {} destroyed!", team.name(), type.name());
    }

    // ========================================================================
    // GETTERS
    // ========================================================================

    public StructureType getType() { return type; }
    public TeamSide getTeam() { return team; }
    public double getCurrentHp() { return currentHp; }
    public double getMaxHp() { return definition.hp; }
    public boolean isDestroyed() { return destroyed; }
    public BlockPos getPosition() { return definition.position; }
    public ArenaDefinition.StructureDef getDefinition() { return definition; }

    public double getHpFraction() {
        return Math.max(0, currentHp / definition.hp);
    }
}
