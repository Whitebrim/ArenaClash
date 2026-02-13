package com.arenaclash.arena;

import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.entity.LivingEntity;

import java.util.*;

/**
 * Represents a destructible arena structure (Tower or Throne).
 * Uses an invisible armor stand as the "target" entity for mob AI.
 */
public class ArenaStructure {
    public enum StructureType { THRONE, TOWER }

    private final StructureType type;
    private final TeamSide owner;
    private final BlockPos position;
    private final Box boundingBox;      // Area of blocks that make up the structure
    private double maxHP;
    private double currentHP;
    private UUID markerEntityId;        // Invisible armor stand for targeting
    private Lane.LaneId associatedLane; // Which lane this tower covers (null for throne)

    // Attack state
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

    /**
     * Spawn the invisible armor stand marker entity for this structure.
     */
    public void spawnMarker(ServerWorld world) {
        ArmorStandEntity marker = new ArmorStandEntity(world, position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomName(Text.literal(
                (type == StructureType.THRONE ? "Throne" : "Tower") + " [" + owner.name() + "]"
        ));
        marker.setCustomNameVisible(true);
        marker.setSilent(true);
        // Make it not interactable by players
        marker.addCommandTag("arenaclash_structure");
        world.spawnEntity(marker);
        this.markerEntityId = marker.getUuid();
    }

    /**
     * Remove the marker entity from the world.
     */
    public void removeMarker(ServerWorld world) {
        if (markerEntityId != null) {
            Entity entity = world.getEntity(markerEntityId);
            if (entity != null) entity.discard();
        }
    }

    /**
     * Apply damage to this structure. Returns true if destroyed.
     */
    public boolean damage(double amount, ServerWorld world) {
        this.currentHP = Math.max(0, currentHP - amount);
        updateMarkerName(world);

        // Destroy some blocks proportionally to damage
        if (currentHP <= 0) {
            destroyAllBlocks(world);
            removeMarker(world);
            return true;
        } else {
            // Degrade blocks based on HP percentage
            degradeBlocks(world);
        }
        return false;
    }

    /**
     * Tick the structure - handle attacks on nearby enemies.
     */
    public void tick(ServerWorld world, List<ArenaMob> enemyMobs) {
        if (isDestroyed()) return;

        if (attackCooldownRemaining > 0) {
            attackCooldownRemaining--;
            return;
        }

        GameConfig cfg = GameConfig.get();
        double range = type == StructureType.TOWER ? cfg.towerRange : cfg.throneAoeRange;
        double damage = type == StructureType.TOWER ? cfg.towerDamage : cfg.throneAoeDamage;
        int cooldown = type == StructureType.TOWER ? cfg.towerAttackCooldown : cfg.throneAttackCooldown;

        // Find enemies in range
        ArenaMob target = null;
        double closestDist = Double.MAX_VALUE;
        List<ArenaMob> inRange = new ArrayList<>();

        for (ArenaMob mob : enemyMobs) {
            if (mob.isDead()) continue;
            Entity entity = mob.getEntity(world);
            if (entity == null) continue;

            double dist = entity.getBlockPos().getSquaredDistance(position);
            if (dist <= range * range) {
                inRange.add(mob);
                if (dist < closestDist) {
                    closestDist = dist;
                    target = mob;
                }
            }
        }

        if (target == null) return;

        if (type == StructureType.TOWER) {
            // Tower: single target, arrow projectile
            target.takeDamage(damage, world);
            // TODO: Spawn arrow particle/projectile for visual
        } else {
            // Throne: AoE damage to all in range
            for (ArenaMob mob : inRange) {
                mob.takeDamage(damage, world);
            }
        }

        attackCooldownRemaining = cooldown;
    }

    /**
     * Check if this tower covers a given lane (towers cover their own lane + center).
     */
    public boolean coversLane(Lane.LaneId laneId) {
        if (type == StructureType.THRONE) return true; // Throne covers everything nearby
        if (associatedLane == null) return false;
        return associatedLane == laneId || laneId == Lane.LaneId.CENTER;
    }

    private void updateMarkerName(ServerWorld world) {
        if (markerEntityId == null) return;
        Entity entity = world.getEntity(markerEntityId);
        if (entity != null) {
            String name = (type == StructureType.THRONE ? "Throne" : "Tower");
            entity.setCustomName(Text.literal(
                    name + " [" + owner.name() + "] HP: " + (int) currentHP + "/" + (int) maxHP
            ));
        }
    }

    private void degradeBlocks(ServerWorld world) {
        // Calculate HP percentage and break proportional blocks
        double hpPercent = currentHP / maxHP;
        // At 75% break some decorative blocks, at 50% more, at 25% even more
        // This is a simplified version - you can enhance the visual degradation
        if (hpPercent < 0.25) {
            breakRandomBlocks(world, 0.3);
        } else if (hpPercent < 0.5) {
            breakRandomBlocks(world, 0.15);
        } else if (hpPercent < 0.75) {
            breakRandomBlocks(world, 0.05);
        }
    }

    private void breakRandomBlocks(ServerWorld world, double chance) {
        net.minecraft.util.math.random.Random rand = world.getRandom();
        int minX = (int) boundingBox.minX, minY = (int) boundingBox.minY, minZ = (int) boundingBox.minZ;
        int maxX = (int) boundingBox.maxX, maxY = (int) boundingBox.maxY, maxZ = (int) boundingBox.maxZ;
        for (int x = minX; x <= maxX; x++) {
            for (int y = maxY; y >= minY; y--) { // Top down for visual effect
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.getBlockState(pos).isAir() && rand.nextFloat() < chance) {
                        world.breakBlock(pos, false);
                    }
                }
            }
        }
    }

    private void destroyAllBlocks(ServerWorld world) {
        int minX = (int) boundingBox.minX, minY = (int) boundingBox.minY, minZ = (int) boundingBox.minZ;
        int maxX = (int) boundingBox.maxX, maxY = (int) boundingBox.maxY, maxZ = (int) boundingBox.maxZ;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    world.breakBlock(pos, false);
                }
            }
        }
    }
}
