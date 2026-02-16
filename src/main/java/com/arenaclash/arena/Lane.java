package com.arenaclash.arena;

import com.arenaclash.card.MobCard;
import com.arenaclash.game.TeamSide;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Represents one of the 3 lanes (LEFT, CENTER, RIGHT) on the arena.
 * Now includes lane bounds for mob confinement.
 */
public class Lane {
    public enum LaneId { LEFT, CENTER, RIGHT }

    private final LaneId id;
    private final int centerX;
    private final int width;

    // Lane confinement bounds
    private double boundsMinX, boundsMaxX, boundsMinZ, boundsMaxZ;
    private boolean boundsSet = false;

    // Deployment slots per team
    private final Map<TeamSide, List<DeploymentSlot>> deploymentSlots = new EnumMap<>(TeamSide.class);

    // Waypoints from P1 side to P2 side
    private final List<BlockPos> waypointsP1toP2 = new ArrayList<>();
    // Waypoints from P2 side to P1 side (separate for correct targeting)
    private final List<BlockPos> waypointsP2toP1 = new ArrayList<>();
    private boolean hasDirectionalWaypoints = false;

    public Lane(LaneId id, int centerX, int width) {
        this.id = id;
        this.centerX = centerX;
        this.width = width;
        deploymentSlots.put(TeamSide.PLAYER1, new ArrayList<>());
        deploymentSlots.put(TeamSide.PLAYER2, new ArrayList<>());
    }

    public LaneId getId() { return id; }
    public int getCenterX() { return centerX; }
    public int getWidth() { return width; }

    /**
     * Set the lane bounds for mob confinement.
     */
    public void setBounds(double minX, double maxX, double minZ, double maxZ) {
        this.boundsMinX = minX;
        this.boundsMaxX = maxX;
        this.boundsMinZ = minZ;
        this.boundsMaxZ = maxZ;
        this.boundsSet = true;
    }

    public boolean hasBounds() { return boundsSet; }
    public double getBoundsMinX() { return boundsMinX; }
    public double getBoundsMaxX() { return boundsMaxX; }
    public double getBoundsMinZ() { return boundsMinZ; }
    public double getBoundsMaxZ() { return boundsMaxZ; }

    public void addDeploymentSlot(TeamSide team, BlockPos pos) {
        deploymentSlots.get(team).add(new DeploymentSlot(pos));
    }

    public List<DeploymentSlot> getDeploymentSlots(TeamSide team) {
        return deploymentSlots.get(team);
    }

    public void addWaypoint(BlockPos pos) {
        waypointsP1toP2.add(pos);
    }

    public void clearWaypoints() {
        waypointsP1toP2.clear();
        waypointsP2toP1.clear();
        hasDirectionalWaypoints = false;
    }

    /**
     * Set separate waypoints for each team direction.
     * This ensures P2 mobs target P1 structures correctly (not reversed P1→P2 path).
     */
    public void setDirectionalWaypoints(List<BlockPos> p1toP2, List<BlockPos> p2toP1) {
        waypointsP1toP2.clear();
        waypointsP1toP2.addAll(p1toP2);
        waypointsP2toP1.clear();
        waypointsP2toP1.addAll(p2toP1);
        hasDirectionalWaypoints = true;
    }

    /**
     * Get waypoints from the perspective of a team.
     * If directional waypoints are set, returns the correct direction.
     * Otherwise falls back to reversing P1→P2 waypoints.
     */
    public List<BlockPos> getWaypoints(TeamSide team) {
        if (team == TeamSide.PLAYER1) {
            return Collections.unmodifiableList(waypointsP1toP2);
        } else {
            if (hasDirectionalWaypoints && !waypointsP2toP1.isEmpty()) {
                return Collections.unmodifiableList(waypointsP2toP1);
            }
            // Fallback: reverse P1→P2
            List<BlockPos> reversed = new ArrayList<>(waypointsP1toP2);
            Collections.reverse(reversed);
            return reversed;
        }
    }

    /**
     * A single deployment slot on a lane where a mob can be placed.
     */
    public static class DeploymentSlot {
        private final BlockPos position;
        private MobCard placedCard;
        private UUID spawnedMobId;

        public DeploymentSlot(BlockPos position) {
            this.position = position;
        }

        public BlockPos getPosition() { return position; }
        public MobCard getPlacedCard() { return placedCard; }
        public UUID getSpawnedMobId() { return spawnedMobId; }
        public boolean isEmpty() { return placedCard == null; }

        public void placeCard(MobCard card) { this.placedCard = card; }

        public MobCard removeCard() {
            MobCard card = this.placedCard;
            this.placedCard = null;
            this.spawnedMobId = null;
            return card;
        }

        public void setSpawnedMobId(UUID id) { this.spawnedMobId = id; }

        public void clear() {
            this.placedCard = null;
            this.spawnedMobId = null;
        }
    }
}
