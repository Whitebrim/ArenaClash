package com.arenaclash.arena;

import com.arenaclash.card.MobCard;
import com.arenaclash.game.TeamSide;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Represents one of the 3 lanes (LEFT, CENTER, RIGHT) on the arena.
 */
public class Lane {
    public enum LaneId { LEFT, CENTER, RIGHT }

    private final LaneId id;
    private final int centerX;
    private final int width;

    // Deployment slots per team. Key = TeamSide, Value = list of slot positions
    private final Map<TeamSide, List<DeploymentSlot>> deploymentSlots = new EnumMap<>(TeamSide.class);

    // Waypoints along the lane for pathfinding (from P1 side to P2 side)
    private final List<BlockPos> waypointsP1toP2 = new ArrayList<>();

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

    public void addDeploymentSlot(TeamSide team, BlockPos pos) {
        deploymentSlots.get(team).add(new DeploymentSlot(pos));
    }

    public List<DeploymentSlot> getDeploymentSlots(TeamSide team) {
        return deploymentSlots.get(team);
    }

    public void addWaypoint(BlockPos pos) {
        waypointsP1toP2.add(pos);
    }

    /**
     * Get waypoints from the perspective of a team (P1 goes forward, P2 goes in reverse).
     */
    public List<BlockPos> getWaypoints(TeamSide team) {
        if (team == TeamSide.PLAYER1) {
            return Collections.unmodifiableList(waypointsP1toP2);
        } else {
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
        private UUID spawnedMobId;  // UUID of the entity spawned from this slot

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
