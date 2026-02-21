package com.arenaclash.arena;

import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

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
    private final Box boundingBox;
    private double maxHP;
    private double currentHP;
    private UUID markerEntityId;
    private Lane.LaneId associatedLane;
    private int attackCooldownRemaining = 0;

    public ArenaStructure(StructureType type, TeamSide owner, BlockPos position, Box boundingBox) {
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
    public Box getBoundingBox() { return boundingBox; }
    public double getMaxHP() { return maxHP; }
    public double getCurrentHP() { return currentHP; }
    public boolean isDestroyed() { return currentHP <= 0; }
    public Lane.LaneId getAssociatedLane() { return associatedLane; }
    public void setAssociatedLane(Lane.LaneId lane) { this.associatedLane = lane; }

    public void spawnMarker(ServerWorld world) {
        removeMarker(world);
        ArmorStandEntity marker = new ArmorStandEntity(world,
                position.getX() + 0.5, position.getY() + 6.0, position.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomNameVisible(true);
        marker.setSilent(true);
        marker.setSmall(true);
        marker.addCommandTag("arenaclash_structure");
        marker.addCommandTag("arenaclash_marker");
        marker.addCommandTag("struct_" + owner.name() + "_" + type.name());
        world.spawnEntity(marker);
        this.markerEntityId = marker.getUuid();
        updateMarkerName(world);
    }

    public void removeMarker(ServerWorld world) {
        if (markerEntityId != null) {
            Entity e = world.getEntity(markerEntityId);
            if (e != null) e.discard();
            markerEntityId = null;
        }
    }

    public boolean damage(double amount, ServerWorld world) {
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
     * Tick structure - towers shoot real arrows, throne does AoE shockwave.
     */
    public void tick(ServerWorld world, List<ArenaMob> enemyMobs) {
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

            double dist = e.getBlockPos().getSquaredDistance(position);
            if (dist <= range * range) {
                inRange.add(mob);
                if (dist < closestDist) { closestDist = dist; closest = mob; }
            }
        }

        if (closest == null) return;

        if (type == StructureType.TOWER) {
            shootArrowAtTarget(world, closest, damage);
        } else {
            performThroneAoE(world, inRange, damage);
        }
        attackCooldownRemaining = cooldown;
    }

    /**
     * Tower shoots a real Arrow entity at the target mob.
     * Arrow now despawns after 30 ticks instead of bouncing.
     */
    private void shootArrowAtTarget(ServerWorld world, ArenaMob target, double damage) {
        Entity targetEntity = target.getEntity(world);
        if (targetEntity == null) return;

        Vec3d shootFrom = new Vec3d(position.getX() + 0.5, position.getY() + 8.0, position.getZ() + 0.5);
        Vec3d shootTo = targetEntity.getPos().add(0, targetEntity.getHeight() * 0.6, 0);
        Vec3d direction = shootTo.subtract(shootFrom).normalize();

        ArrowEntity arrow = new ArrowEntity(world, shootFrom.x, shootFrom.y, shootFrom.z,
                new ItemStack(Items.ARROW), null);
        arrow.setVelocity(direction.x, direction.y + 0.1, direction.z, 2.0f, 1.0f);
        arrow.setDamage(0); // Visual only, we apply damage ourselves
        arrow.pickupType = ArrowEntity.PickupPermission.DISALLOWED;
        arrow.setCritical(true);
        arrow.addCommandTag("arenaclash_tower_arrow");
        // Set a short life so arrows despawn quickly after visual impact
        arrow.age = 1140; // Arrow despawns at age 1200, so this gives 60 ticks (~3 sec) before despawn
        world.spawnEntity(arrow);

        // Apply damage directly (arrow is visual only)
        target.takeDamage(damage, world);

        // Show visual damage feedback on the target mob
        if (targetEntity instanceof LivingEntity living) {
            living.hurtTime = 10;
            living.maxHurtTime = 10;
        }

        // Spawn floating damage number
        ArenaMob.spawnDamageNumber(world, targetEntity.getPos().add(0, targetEntity.getHeight() + 0.3, 0), damage);

        // Muzzle flash particles
        world.spawnParticles(ParticleTypes.FLAME, shootFrom.x, shootFrom.y, shootFrom.z, 3, 0.1, 0.1, 0.1, 0.02);

        world.playSound(null, position.getX(), position.getY() + 8, position.getZ(),
                SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.2f);
    }

    /**
     * Throne does an AoE shockwave attack with expanding particle ring.
     */
    private void performThroneAoE(ServerWorld world, List<ArenaMob> targets, double damage) {
        Vec3d center = new Vec3d(position.getX() + 0.5, position.getY() + 1.0, position.getZ() + 0.5);

        for (ArenaMob mob : targets) {
            mob.takeDamage(damage, world);
            Entity e = mob.getEntity(world);
            if (e != null) {
                Vec3d dir = e.getPos().subtract(center).normalize();
                double kb = 0.6;
                e.requestTeleport(e.getX() + dir.x * kb, e.getY(), e.getZ() + dir.z * kb);
                // Show visual damage feedback
                if (e instanceof LivingEntity living) {
                    living.hurtTime = 10;
                    living.maxHurtTime = 10;
                }
                // Spawn floating damage number
                ArenaMob.spawnDamageNumber(world, e.getPos().add(0, e.getHeight() + 0.3, 0), damage);
            }
        }

        // Shockwave particle ring (team colored)
        GameConfig cfg = GameConfig.get();
        double radius = cfg.throneAoeRange;
        DustParticleEffect dust = owner == TeamSide.PLAYER1
                ? new DustParticleEffect(new org.joml.Vector3f(0.2f, 0.5f, 1.0f), 1.5f)
                : new DustParticleEffect(new org.joml.Vector3f(1.0f, 0.2f, 0.2f), 1.5f);

        for (int i = 0; i < 32; i++) {
            double angle = (Math.PI * 2) * i / 32;
            double px = center.x + Math.cos(angle) * radius * 0.7;
            double pz = center.z + Math.sin(angle) * radius * 0.7;
            world.spawnParticles(dust, px, center.y + 0.5, pz, 1, 0, 0, 0, 0);
        }
        world.spawnParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.5, center.z, 3, 0.5, 0.3, 0.5, 0.02);
        world.playSound(null, position.getX(), position.getY(), position.getZ(),
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 0.7f, 1.4f);
    }

    private void onDestroy(ServerWorld world) {
        destroyAllBlocks(world);
        removeMarker(world);
        Vec3d center = Vec3d.ofCenter(position).add(0, 2, 0);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.CLOUD, center.x, center.y, center.z, 20, 1, 1, 1, 0.1);
        world.playSound(null, position.getX(), position.getY(), position.getZ(),
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 2.0f, 0.5f);
    }

    public boolean coversLane(Lane.LaneId laneId) {
        if (type == StructureType.THRONE) return true;
        if (associatedLane == null) return false;
        return associatedLane == laneId || laneId == Lane.LaneId.CENTER;
    }

    /**
     * Update HP display marker with colored bar.
     */
    public void updateMarkerName(ServerWorld world) {
        if (isDestroyed()) return;
        Entity entity = markerEntityId != null ? world.getEntity(markerEntityId) : null;

        if (entity == null || !entity.isAlive()) {
            // Re-spawn marker if it was despawned
            ArmorStandEntity marker = new ArmorStandEntity(world,
                    position.getX() + 0.5, position.getY() + 6.0, position.getZ() + 0.5);
            marker.setInvisible(true);
            marker.setInvulnerable(true);
            marker.setNoGravity(true);
            marker.setCustomNameVisible(true);
            marker.setSilent(true);
            marker.setSmall(true);
            marker.addCommandTag("arenaclash_structure");
            marker.addCommandTag("arenaclash_marker");
            world.spawnEntity(marker);
            this.markerEntityId = marker.getUuid();
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

        String typeName = type == StructureType.THRONE ? "THRONE" : "TOWER";
        entity.setCustomName(Text.literal(teamColor + label + " " + typeName + " " + bar + " " + hpColor + hp + "\u00A77/" + max));
        entity.setCustomNameVisible(true);
    }

    private void degradeBlocks(ServerWorld world, double chance) {
        var rand = world.getRandom();
        int minX = (int) boundingBox.minX, minY = (int) boundingBox.minY, minZ = (int) boundingBox.minZ;
        int maxX = (int) boundingBox.maxX, maxY = (int) boundingBox.maxY, maxZ = (int) boundingBox.maxZ;
        for (int x = minX; x <= maxX; x++)
            for (int y = maxY; y >= minY; y--)
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.getBlockState(pos).isAir() && rand.nextFloat() < chance)
                        world.breakBlock(pos, false);
                }
    }

    private void destroyAllBlocks(ServerWorld world) {
        int minX = (int) boundingBox.minX, minY = (int) boundingBox.minY, minZ = (int) boundingBox.minZ;
        int maxX = (int) boundingBox.maxX, maxY = (int) boundingBox.maxY, maxZ = (int) boundingBox.maxZ;
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    world.breakBlock(new BlockPos(x, y, z), false);
    }
}
