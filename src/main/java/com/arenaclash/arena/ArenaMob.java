package com.arenaclash.arena;

import com.arenaclash.ai.CombatSystem;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Controls a single mob deployed on the arena.
 *
 * Movement strategy:
 * 1. Follow waypoints using Minecraft's navigation system
 * 2. If navigation fails (stuck), fallback to teleport
 * 3. Each tick, clamp position to lane boundaries
 * 4. When enemy in range, stop and engage combat
 *
 * The AI doesn't use vanilla AI goals. Instead we clear all goals
 * and drive behavior purely from this class via tick().
 */
public class ArenaMob {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-Mob");

    private static final double WAYPOINT_REACH_DIST = 2.0;
    private static final double STUCK_THRESHOLD = 0.05;  // Min movement per second
    private static final int STUCK_CHECK_INTERVAL = 20;   // Check stuck every second
    private static final double AGGRO_RANGE = 10.0;
    private static final double MOVEMENT_SPEED = 0.3;

    private final MobEntity entity;
    private final TeamSide team;
    private final Lane lane;
    private final List<Vec3d> waypoints;

    private int currentWaypointIndex = 0;
    private int stuckTicks = 0;
    private Vec3d lastCheckedPos;
    private boolean isEngagingTarget = false;
    private LivingEntity currentTarget = null;

    public ArenaMob(MobEntity entity, TeamSide team, Lane lane) {
        this.entity = entity;
        this.team = team;
        this.lane = lane;
        this.waypoints = lane.getWaypointsForTeam(team);
        this.lastCheckedPos = entity.getPos();

        // Disable vanilla AI and set up for our control
        setupAI();
    }

    /**
     * Set up the mob for arena combat.
     * Clears vanilla AI goals so we have full control.
     */
    private void setupAI() {
        // Don't disable AI entirely — we want physics, animations, etc.
        entity.setAiDisabled(false);

        // Clear all existing goals
        if (entity.getNavigation() != null) {
            entity.getNavigation().stop();
        }

        // Make mob persistent (don't despawn)
        entity.setPersistent();

        // Set custom name visible for debug/HP display
        entity.setCustomNameVisible(true);
    }

    /**
     * Main tick — called each server tick during BATTLE phase.
     */
    public void tick() {
        if (entity.isDead() || entity.isRemoved()) return;

        // Lane boundary enforcement
        enforceLaneBounds();

        // Check for enemies in range
        LivingEntity target = findNearestEnemy();

        if (target != null) {
            // Enemy found — stop and face them
            isEngagingTarget = true;
            currentTarget = target;
            entity.getNavigation().stop();
            entity.lookAtEntity(target, 30.0f, 30.0f);
            // Combat is handled by CombatSystem.tickMobCombat()
        } else {
            // No enemy — advance along waypoints
            isEngagingTarget = false;
            currentTarget = null;
            advanceAlongWaypoints();
        }

        // Stuck detection
        checkStuck();
    }

    /**
     * Clamp mob position to lane boundaries.
     * Called every tick to prevent mobs from wandering off.
     */
    private void enforceLaneBounds() {
        Vec3d pos = entity.getPos();
        Vec3d clamped = lane.clampToLane(pos);

        if (clamped.squaredDistanceTo(pos) > 0.01) {
            entity.setPosition(clamped.x, entity.getY(), clamped.z);
        }
    }

    /**
     * Navigate to the next waypoint.
     */
    private void advanceAlongWaypoints() {
        if (waypoints.isEmpty() || currentWaypointIndex >= waypoints.size()) return;

        Vec3d target = waypoints.get(currentWaypointIndex);
        double distSq = entity.getPos().squaredDistanceTo(target);

        // Check if we've reached the current waypoint
        if (distSq < WAYPOINT_REACH_DIST * WAYPOINT_REACH_DIST) {
            currentWaypointIndex++;
            if (currentWaypointIndex >= waypoints.size()) {
                // Reached end of lane — stay here and fight
                return;
            }
            target = waypoints.get(currentWaypointIndex);
        }

        // Navigate to waypoint
        navigateTo(target);
    }

    /**
     * Navigate to a position using Minecraft's pathfinding.
     * Falls back to direct movement or teleport if pathfinding fails.
     */
    private void navigateTo(Vec3d target) {
        BlockPos targetBlock = BlockPos.ofFloored(target);

        // Try native pathfinding
        Path path = entity.getNavigation().findPathTo(targetBlock, 1);
        if (path != null) {
            entity.getNavigation().startMovingAlong(path, MOVEMENT_SPEED);
        } else {
            // Fallback: direct movement toward target
            Vec3d dir = target.subtract(entity.getPos()).normalize().multiply(MOVEMENT_SPEED * 0.1);
            entity.setVelocity(dir.x, entity.getVelocity().y, dir.z);
            entity.velocityModified = true;
        }
    }

    /**
     * Find nearest enemy mob within aggro range.
     */
    private LivingEntity findNearestEnemy() {
        TeamSide enemyTeam = team.opponent();
        LivingEntity nearest = null;
        double nearestDist = AGGRO_RANGE * AGGRO_RANGE;

        for (Entity e : entity.getWorld().getOtherEntities(entity, entity.getBoundingBox().expand(AGGRO_RANGE))) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living.isDead() || living.isRemoved()) continue;

            String entTeam = CombatSystem.getTag(living, CombatSystem.TAG_TEAM);
            if (entTeam == null || !entTeam.equals(enemyTeam.name())) continue;

            double dist = entity.squaredDistanceTo(living);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = living;
            }
        }

        return nearest;
    }

    /**
     * Detect if mob is stuck and teleport to next waypoint if so.
     */
    private void checkStuck() {
        stuckTicks++;
        if (stuckTicks < STUCK_CHECK_INTERVAL) return;
        stuckTicks = 0;

        Vec3d currentPos = entity.getPos();
        double moved = currentPos.squaredDistanceTo(lastCheckedPos);
        lastCheckedPos = currentPos;

        // If barely moved and not fighting, we're stuck
        if (moved < STUCK_THRESHOLD * STUCK_THRESHOLD && !isEngagingTarget) {
            // Teleport forward to next waypoint
            if (currentWaypointIndex < waypoints.size()) {
                Vec3d wp = waypoints.get(currentWaypointIndex);
                entity.requestTeleport(wp.x, wp.y, wp.z);
                LOGGER.debug("Mob {} teleported to waypoint {} (stuck)", entity.getType().getTranslationKey(), currentWaypointIndex);
            }
        }
    }

    // ========================================================================
    // GETTERS
    // ========================================================================

    public MobEntity getEntity() { return entity; }
    public TeamSide getTeam() { return team; }
    public Lane getLane() { return lane; }
    public boolean isEngagingTarget() { return isEngagingTarget; }
    public LivingEntity getCurrentTarget() { return currentTarget; }
    public boolean isDead() { return entity.isDead() || entity.isRemoved(); }

    /**
     * Get the current waypoint index (progress along the lane).
     */
    public int getCurrentWaypointIndex() { return currentWaypointIndex; }

    /**
     * Force retarget — used for retreat command (bell ring during battle).
     */
    public void orderRetreat() {
        // Reverse waypoints: go back to start
        currentWaypointIndex = 0;
        isEngagingTarget = false;
        currentTarget = null;
    }
}
