package com.arenaclash.arena;

import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Tower/Throne structure with visual attacks.
 * Towers shoot real Arrow entities at enemies.
 * Throne does AoE shockwave with expanding particle ring.
 */
public class ArenaStructure {
    public enum StructureType { THRONE, TOWER }

    private final StructureType type;
    private final TeamSide owner;
    private final BlockPos position;
    private final AABB boundingBox;
    private double maxHP;
    private double currentHP;
    private UUID markerEntityId;
    private Lane.LaneId associatedLane;
    private int attackCooldownRemaining = 0;

    public ArenaStructure(StructureType type, TeamSide owner, BlockPos position, AABB boundingBox) {
        this.type = type;
        this.owner = owner;
        this.position = position;
        this.boundingBox = boundingBox;
        GameConfig cfg = GameConfig.get();
        this.maxHP = type == StructureType.THRONE ? cfg.throneHP : cfg.towerHP;
        this.currentHP = maxHP;
    }

    public StructureType getType() { return type; }
    public TeamSide getOwner() { return owner; }
    public BlockPos getPosition() { return position; }
    public AABB getBoundingBox() { return boundingBox; }
    public double getMaxHP() { return maxHP; }
    public double getCurrentHP() { return currentHP; }
    public boolean isDestroyed() { return currentHP <= 0; }
    public Lane.LaneId getAssociatedLane() { return associatedLane; }
    public void setAssociatedLane(Lane.LaneId lane) { this.associatedLane = lane; }

    public void spawnMarker(ServerLevel world) {
        removeMarker(world);
        ArmorStand marker = new ArmorStand(world,
                position.getX() + 0.5, position.getY() + 6.0, position.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomNameVisible(true);
        marker.setSilent(true);
        marker.setSmall(true);
        marker.addTag("arenaclash_structure");
        marker.addTag("arenaclash_marker");
        marker.addTag("struct_" + owner.name() + "_" + type.name());
        world.addFreshEntity(marker);
        this.markerEntityId = marker.getUUID();
        updateMarkerName(world);
    }

    public void removeMarker(ServerLevel world) {
        if (markerEntityId != null) {
            Entity e = world.getEntity(markerEntityId);
            if (e != null) e.discard();
            markerEntityId = null;
        }
    }

    public boolean damage(double amount, ServerLevel world) {
        this.currentHP = Math.max(0, currentHP - amount);
        updateMarkerName(world);
        if (currentHP <= 0) {
            onDestroy(world);
            return true;
        }
        if (currentHP < maxHP * 0.5) degradeBlocks(world, 0.08);
        return false;
    }

    /**
     * Tick structure - towers shoot tracked arrows, throne does AoE shockwave.
     */
    public void tick(ServerLevel world, List<ArenaMob> enemyMobs, ProjectileTracker projectileTracker) {
        if (isDestroyed()) return;
        if (attackCooldownRemaining > 0) { attackCooldownRemaining--; return; }

        GameConfig cfg = GameConfig.get();
        double range = type == StructureType.TOWER ? cfg.towerRange : cfg.throneAoeRange;
        double damage = type == StructureType.TOWER ? cfg.towerDamage : cfg.throneAoeDamage;
        int cooldown = type == StructureType.TOWER ? cfg.towerAttackCooldown : cfg.throneAttackCooldown;

        List<ArenaMob> inRange = new ArrayList<>();
        ArenaMob closest = null;
        double closestDist = Double.MAX_VALUE;

        for (ArenaMob mob : enemyMobs) {
            if (mob.isDead()) continue;
            Entity e = mob.getEntity(world);
            if (e == null) continue;

            // Towers only attack mobs on their lane or center lane
            if (type == StructureType.TOWER && !coversLane(mob.getLane())) continue;

            double dist = e.blockPosition().distSqr(position);
            if (dist <= range * range) {
                inRange.add(mob);
                if (dist < closestDist) { closestDist = dist; closest = mob; }
            }
        }

        if (closest == null) return;

        if (type == StructureType.TOWER) {
            shootArrowAtTarget(world, closest, damage, projectileTracker);
        } else {
            performThroneAoE(world, inRange, damage);
        }
        attackCooldownRemaining = cooldown;
    }

    /**
     * Tower shoots a real Arrow entity at the target mob.
     * Damage is now applied when the arrow reaches the target via ProjectileTracker.
     */
    private void shootArrowAtTarget(ServerLevel world, ArenaMob target, double damage, ProjectileTracker projectileTracker) {
        Entity targetEntity = target.getEntity(world);
        if (targetEntity == null) return;

        Vec3 shootFrom = new Vec3(position.getX() + 0.5, position.getY() + 8.0, position.getZ() + 0.5);
        Vec3 shootTo = targetEntity.position().add(0, targetEntity.getBbHeight() * 0.6, 0);
        Vec3 direction = shootTo.subtract(shootFrom).normalize();

        Arrow arrow = new Arrow(world, shootFrom.x, shootFrom.y, shootFrom.z,
                new ItemStack(Items.ARROW), null);
        arrow.shoot(direction.x, direction.y + 0.1, direction.z, 2.0f, 1.0f);
        arrow.setBaseDamage(0); // No vanilla damage, we handle it ourselves
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setCritArrow(true);
        arrow.addTag("arenaclash_tower_arrow");
        world.addFreshEntity(arrow);

        // Register with tracker — damage applied when arrow reaches target
        projectileTracker.trackTowerArrow(arrow.getUUID(), owner, target, damage);

        // Muzzle flash particles
        world.sendParticles(ParticleTypes.FLAME, shootFrom.x, shootFrom.y, shootFrom.z, 3, 0.1, 0.1, 0.1, 0.02);

        world.playSound(null, position.getX(), position.getY() + 8, position.getZ(),
                SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.0f, 1.2f);
    }

    /**
     * Throne does an AoE shockwave attack with expanding particle ring.
     */
    private void performThroneAoE(ServerLevel world, List<ArenaMob> targets, double damage) {
        Vec3 center = new Vec3(position.getX() + 0.5, position.getY() + 1.0, position.getZ() + 0.5);

        for (ArenaMob mob : targets) {
            mob.takeDamage(damage, world);
            Entity e = mob.getEntity(world);
            if (e != null) {
                Vec3 dir = e.position().subtract(center).normalize();
                double kb = 0.6;
                e.teleportTo(e.getX() + dir.x * kb, e.getY(), e.getZ() + dir.z * kb);
                // Show visual damage feedback
                if (e instanceof LivingEntity living) {
                    living.hurtTime = 10;
                    living.hurtDuration = 10;
                }
                // Spawn floating damage number
                ArenaMob.spawnDamageNumber(world, e.position().add(0, e.getBbHeight() + 0.3, 0), damage);
            }
        }

        // Shockwave particle ring (team colored)
        GameConfig cfg = GameConfig.get();
        double radius = cfg.throneAoeRange;
        DustParticleOptions dust = owner == TeamSide.PLAYER1
                ? new DustParticleOptions(new org.joml.Vector3f(0.2f, 0.5f, 1.0f), 1.5f)
                : new DustParticleOptions(new org.joml.Vector3f(1.0f, 0.2f, 0.2f), 1.5f);

        for (int i = 0; i < 32; i++) {
            double angle = (Math.PI * 2) * i / 32;
            double px = center.x + Math.cos(angle) * radius * 0.7;
            double pz = center.z + Math.sin(angle) * radius * 0.7;
            world.sendParticles(dust, px, center.y + 0.5, pz, 1, 0, 0, 0, 0);
        }
        world.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.5, center.z, 3, 0.5, 0.3, 0.5, 0.02);
        world.playSound(null, position.getX(), position.getY(), position.getZ(),
                SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.7f, 1.4f);
    }

    private void onDestroy(ServerLevel world) {
        destroyAllBlocks(world);
        removeMarker(world);
        Vec3 center = Vec3.atCenterOf(position).add(0, 2, 0);
        world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        world.sendParticles(ParticleTypes.CLOUD, center.x, center.y, center.z, 20, 1, 1, 1, 0.1);
        world.playSound(null, position.getX(), position.getY(), position.getZ(),
                SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 2.0f, 0.5f);
    }

    public boolean coversLane(Lane.LaneId laneId) {
        if (type == StructureType.THRONE) return true;
        if (associatedLane == null) return false;
        return associatedLane == laneId || laneId == Lane.LaneId.CENTER;
    }

    /**
     * Update HP display marker with colored bar.
     */
    public void updateMarkerName(ServerLevel world) {
        if (isDestroyed()) return;
        Entity entity = markerEntityId != null ? world.getEntity(markerEntityId) : null;

        if (entity == null || !entity.isAlive()) {
            // Re-spawn marker if it was despawned
            ArmorStand marker = new ArmorStand(world,
                    position.getX() + 0.5, position.getY() + 6.0, position.getZ() + 0.5);
            marker.setInvisible(true);
            marker.setInvulnerable(true);
            marker.setNoGravity(true);
            marker.setCustomNameVisible(true);
            marker.setSilent(true);
            marker.setSmall(true);
            marker.addTag("arenaclash_structure");
            marker.addTag("arenaclash_marker");
            world.addFreshEntity(marker);
            this.markerEntityId = marker.getUUID();
            entity = marker;
        }

        String label = (type == StructureType.THRONE ? "\u265B" : "\u2691");
        String teamColor = (owner == TeamSide.PLAYER1) ? "\u00A79" : "\u00A7c";
        int hp = (int) currentHP;
        int max = (int) maxHP;
        double pct = currentHP / maxHP;

        String hpColor = pct > 0.5 ? "\u00A7a" : pct > 0.25 ? "\u00A7e" : "\u00A7c";
        int filled = Math.max(0, (int) Math.ceil(pct * 20));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            bar.append(i < filled ? hpColor + "\u258B" : "\u00A78\u258B");
        }

        Component typeName = Component.translatable(type == StructureType.THRONE ? "arenaclash.structure.throne" : "arenaclash.structure.tower");
        MutableComponent displayName = Component.literal(teamColor + label + " ").append(typeName).append(Component.literal(" " + bar + " " + hpColor + hp + "\u00A77/" + max));
        entity.setCustomName(displayName);
        entity.setCustomNameVisible(true);
    }

    private void degradeBlocks(ServerLevel world, double chance) {
        var rand = world.getRandom();
        int minX = (int) boundingBox.minX, minY = (int) boundingBox.minY, minZ = (int) boundingBox.minZ;
        int maxX = (int) boundingBox.maxX, maxY = (int) boundingBox.maxY, maxZ = (int) boundingBox.maxZ;
        for (int x = minX; x <= maxX; x++)
            for (int y = maxY; y >= minY; y--)
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.getBlockState(pos).isAir() && rand.nextFloat() < chance)
                        world.destroyBlock(pos, false);
                }
    }

    private void destroyAllBlocks(ServerLevel world) {
        int minX = (int) boundingBox.minX, minY = (int) boundingBox.minY, minZ = (int) boundingBox.minZ;
        int maxX = (int) boundingBox.maxX, maxY = (int) boundingBox.maxY, maxZ = (int) boundingBox.maxZ;
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    world.destroyBlock(new BlockPos(x, y, z), false);
    }
}
