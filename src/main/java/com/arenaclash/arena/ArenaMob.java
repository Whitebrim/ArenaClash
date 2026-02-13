package com.arenaclash.arena;

import com.arenaclash.ai.CombatSystem;
import com.arenaclash.ai.LanePathfinder;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/**
 * Manages a mob entity on the arena. Handles custom pathfinding and combat.
 * This is NOT an entity class - it's a controller/wrapper around vanilla entities.
 */
public class ArenaMob {
    public enum MobState {
        IDLE,           // Waiting in deployment slot
        ADVANCING,      // Moving along lane toward enemy
        FIGHTING,       // Engaged in combat with enemy mob or structure
        RETREATING,     // Returning to base (bell command)
        DEAD            // Killed in combat
    }

    private final UUID ownerId;         // Owning player
    private final TeamSide team;
    private final MobCard sourceCard;
    private final Lane.LaneId lane;
    private final BlockPos startSlotPos;

    private UUID entityId;              // The actual MC entity UUID
    private MobState state = MobState.IDLE;

    // Stats (from card)
    private double maxHP;
    private double currentHP;
    private double moveSpeed;           // blocks per second
    private double attackDamage;
    private int attackCooldown;
    private int attackCooldownRemaining = 0;

    // Pathfinding
    private List<BlockPos> waypoints;
    private int currentWaypointIndex = 0;

    // Combat target
    private UUID targetEntityId;
    private ArenaStructure targetStructure;

    public ArenaMob(UUID ownerId, TeamSide team, MobCard card, Lane.LaneId lane, BlockPos startSlotPos) {
        this.ownerId = ownerId;
        this.team = team;
        this.sourceCard = card;
        this.lane = lane;
        this.startSlotPos = startSlotPos;

        // Set stats from card
        this.maxHP = card.getHP();
        this.currentHP = maxHP;
        this.moveSpeed = card.getSpeed();
        this.attackDamage = card.getAttack();
        this.attackCooldown = card.getAttackCooldown();
    }

    // === Getters ===
    public UUID getOwnerId() { return ownerId; }
    public TeamSide getTeam() { return team; }
    public MobCard getSourceCard() { return sourceCard; }
    public Lane.LaneId getLane() { return lane; }
    public UUID getEntityId() { return entityId; }
    public MobState getState() { return state; }
    public double getCurrentHP() { return currentHP; }
    public double getMaxHP() { return maxHP; }
    public boolean isDead() { return state == MobState.DEAD || currentHP <= 0; }
    public BlockPos getStartSlotPos() { return startSlotPos; }

    /**
     * Spawn the actual entity in the world at the given position.
     */
    public void spawn(ServerWorld world, BlockPos pos) {
        MobCardDefinition def = sourceCard.getDefinition();
        if (def == null) return;

        Entity entity = def.entityType().create(world);
        if (entity == null) return;

        entity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);

        if (entity instanceof MobEntity mobEntity) {
            // Disable vanilla AI
            mobEntity.setAiDisabled(true);
            mobEntity.setPersistent();

            // Set HP to our custom value
            // Note: we track HP ourselves, but set entity HP high so it doesn't die from vanilla damage
            mobEntity.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH)
                    .setBaseValue(maxHP);
            mobEntity.setHealth((float) maxHP);
        }

        // Tag for identification
        entity.addCommandTag("arenaclash_mob");
        entity.addCommandTag("team_" + team.name());
        entity.addCommandTag("lane_" + lane.name());

        // Custom name showing HP
        updateEntityName(entity);

        world.spawnEntity(entity);
        this.entityId = entity.getUuid();
        this.state = MobState.IDLE;
    }

    /**
     * Start advancing along the lane.
     */
    public void startAdvancing(List<BlockPos> waypoints) {
        this.waypoints = waypoints;
        this.currentWaypointIndex = 0;
        this.state = MobState.ADVANCING;
    }

    /**
     * Order retreat back to start position.
     */
    public void startRetreating() {
        if (state == MobState.DEAD) return;
        this.state = MobState.RETREATING;
        // Reverse waypoints to go back
        if (waypoints != null) {
            // Set waypoint index to navigate back
            // We'll walk backward through waypoints
        }
    }

    /**
     * Main tick - called every server tick during battle.
     */
    public void tick(ServerWorld world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        if (isDead()) return;

        Entity entity = getEntity(world);
        if (entity == null) {
            state = MobState.DEAD;
            return;
        }

        if (attackCooldownRemaining > 0) attackCooldownRemaining--;

        switch (state) {
            case ADVANCING -> tickAdvancing(world, entity, allMobs, structures);
            case FIGHTING -> tickFighting(world, entity, allMobs, structures);
            case RETREATING -> tickRetreating(world, entity, allMobs);
            case IDLE, DEAD -> {}
        }

        updateEntityName(entity);
    }

    private void tickAdvancing(ServerWorld world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        GameConfig cfg = GameConfig.get();

        // Check for nearby enemies to fight
        ArenaMob nearestEnemy = findNearestEnemy(world, entity, allMobs, cfg.mobAggroRange);
        ArenaStructure nearestStructure = findNearestEnemyStructure(entity, structures, cfg.mobAggroRange);

        if (nearestEnemy != null) {
            targetEntityId = nearestEnemy.getEntityId();
            targetStructure = null;
            state = MobState.FIGHTING;
            return;
        }

        if (nearestStructure != null && isStructureBlocking(nearestStructure, entity)) {
            targetStructure = nearestStructure;
            targetEntityId = null;
            state = MobState.FIGHTING;
            return;
        }

        // Move toward next waypoint
        moveTowardWaypoint(entity);
    }

    private void tickFighting(ServerWorld world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        // Check if target is still valid
        if (targetEntityId != null) {
            ArenaMob targetMob = findMobByEntityId(allMobs, targetEntityId);
            if (targetMob == null || targetMob.isDead()) {
                // Target dead, resume advancing
                targetEntityId = null;
                state = MobState.ADVANCING;
                return;
            }

            Entity targetEntity = targetMob.getEntity(world);
            if (targetEntity == null) {
                targetEntityId = null;
                state = MobState.ADVANCING;
                return;
            }

            double dist = entity.squaredDistanceTo(targetEntity);
            double attackRange = 2.5; // melee range

            if (dist > attackRange * attackRange) {
                // Move toward target
                moveToward(entity, targetEntity.getPos());
            } else if (attackCooldownRemaining <= 0) {
                // Attack!
                CombatSystem.performAttack(this, targetMob, world);
                attackCooldownRemaining = attackCooldown;
            }
        } else if (targetStructure != null) {
            if (targetStructure.isDestroyed()) {
                targetStructure = null;
                state = MobState.ADVANCING;
                return;
            }

            double dist = entity.getBlockPos().getSquaredDistance(targetStructure.getPosition());
            double attackRange = 2.5;

            if (dist > attackRange * attackRange) {
                moveToward(entity, Vec3d.ofCenter(targetStructure.getPosition()));
            } else if (attackCooldownRemaining <= 0) {
                CombatSystem.attackStructure(this, targetStructure, world);
                attackCooldownRemaining = attackCooldown;
            }
        } else {
            state = MobState.ADVANCING;
        }
    }

    private void tickRetreating(ServerWorld world, Entity entity, List<ArenaMob> allMobs) {
        GameConfig cfg = GameConfig.get();

        // Attack enemies along the way
        ArenaMob nearestEnemy = findNearestEnemy(world, entity, allMobs, cfg.mobAggroRange * 0.5);
        if (nearestEnemy != null) {
            Entity targetEntity = nearestEnemy.getEntity(world);
            if (targetEntity != null) {
                double dist = entity.squaredDistanceTo(targetEntity);
                if (dist <= 2.5 * 2.5 && attackCooldownRemaining <= 0) {
                    CombatSystem.performAttack(this, nearestEnemy, world);
                    attackCooldownRemaining = attackCooldown;
                }
            }
        }

        // Move back toward start slot
        double distToHome = entity.getBlockPos().getSquaredDistance(startSlotPos);
        if (distToHome <= 2.0 * 2.0) {
            // Arrived home - this mob can be recovered
            state = MobState.IDLE;
            return;
        }

        moveToward(entity, Vec3d.ofCenter(startSlotPos));
    }

    /**
     * Take damage from another source.
     */
    public void takeDamage(double amount, ServerWorld world) {
        this.currentHP -= amount;
        if (currentHP <= 0) {
            currentHP = 0;
            die(world);
        }
    }

    private void die(ServerWorld world) {
        state = MobState.DEAD;
        Entity entity = getEntity(world);
        if (entity != null) {
            entity.discard();
        }
    }

    /**
     * Get the entity from the world.
     */
    public Entity getEntity(ServerWorld world) {
        if (entityId == null) return null;
        return world.getEntity(entityId);
    }

    private void moveTowardWaypoint(Entity entity) {
        if (waypoints == null || currentWaypointIndex >= waypoints.size()) return;

        BlockPos target = waypoints.get(currentWaypointIndex);
        double dist = entity.getBlockPos().getSquaredDistance(target);

        if (dist <= 1.5 * 1.5) {
            currentWaypointIndex++;
            if (currentWaypointIndex >= waypoints.size()) return;
            target = waypoints.get(currentWaypointIndex);
        }

        moveToward(entity, Vec3d.ofCenter(target));
    }

    private void moveToward(Entity entity, Vec3d target) {
        Vec3d current = entity.getPos();
        Vec3d direction = target.subtract(current).normalize();
        double speed = moveSpeed / 20.0; // Convert blocks/sec to blocks/tick

        Vec3d velocity = new Vec3d(
                direction.x * speed,
                entity.getVelocity().y, // Preserve Y velocity for gravity
                direction.z * speed
        );
        entity.setVelocity(velocity);
        entity.velocityModified = true;

        // Face movement direction
        if (entity instanceof LivingEntity living) {
            float yaw = (float) (Math.atan2(-direction.x, direction.z) * (180.0 / Math.PI));
            living.setYaw(yaw);
            living.setHeadYaw(yaw);
            living.setBodyYaw(yaw);
        }
    }

    public void applyKnockback(Vec3d direction, double strength) {
        // Applied via CombatSystem
    }

    private ArenaMob findNearestEnemy(ServerWorld world, Entity self, List<ArenaMob> allMobs, double range) {
        ArenaMob nearest = null;
        double nearestDist = range * range;

        for (ArenaMob mob : allMobs) {
            if (mob.getTeam() == this.team || mob.isDead()) continue;
            Entity other = mob.getEntity(world);
            if (other == null) continue;
            double dist = self.squaredDistanceTo(other);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = mob;
            }
        }
        return nearest;
    }

    private ArenaStructure findNearestEnemyStructure(Entity self, List<ArenaStructure> structures, double range) {
        ArenaStructure nearest = null;
        double nearestDist = range * range;

        for (ArenaStructure structure : structures) {
            if (structure.getOwner() == this.team || structure.isDestroyed()) continue;
            double dist = self.getBlockPos().getSquaredDistance(structure.getPosition());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = structure;
            }
        }
        return nearest;
    }

    private boolean isStructureBlocking(ArenaStructure structure, Entity self) {
        // Check if the structure is on our path (within the lane)
        if (structure.getType() == ArenaStructure.StructureType.THRONE) return true;
        return structure.coversLane(this.lane);
    }

    private ArenaMob findMobByEntityId(List<ArenaMob> mobs, UUID entityId) {
        for (ArenaMob mob : mobs) {
            if (entityId.equals(mob.getEntityId())) return mob;
        }
        return null;
    }

    private void updateEntityName(Entity entity) {
        String name = sourceCard.getDefinition().displayName()
                + " Lv." + sourceCard.getLevel()
                + " §c" + (int) currentHP + "/" + (int) maxHP;
        Formatting color = team == TeamSide.PLAYER1 ? Formatting.BLUE : Formatting.RED;
        entity.setCustomName(Text.literal(name).formatted(color));
        entity.setCustomNameVisible(true);
    }
}
