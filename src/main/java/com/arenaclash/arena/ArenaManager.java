package com.arenaclash.arena;

import com.arenaclash.ai.LanePathfinder;
import com.arenaclash.card.MobCard;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.*;

/**
 * Central manager for the arena. Handles setup, mob placement, battle ticking.
 */
public class ArenaManager {
    private ServerWorld arenaWorld;
    private final Map<Lane.LaneId, Lane> lanes = new EnumMap<>(Lane.LaneId.class);
    private final List<ArenaStructure> structures = new ArrayList<>();
    private final List<ArenaMob> activeMobs = new ArrayList<>();

    // Track damage dealt to structures for victory conditions
    private final Map<TeamSide, Double> throneDamageDealt = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Integer> towersDestroyed = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Double> towerDamageDealt = new EnumMap<>(TeamSide.class);

    // Experience earned per team during this battle
    private final Map<TeamSide, Integer> experienceEarned = new EnumMap<>(TeamSide.class);

    private boolean battleActive = false;

    public ArenaManager() {
        throneDamageDealt.put(TeamSide.PLAYER1, 0.0);
        throneDamageDealt.put(TeamSide.PLAYER2, 0.0);
        towersDestroyed.put(TeamSide.PLAYER1, 0);
        towersDestroyed.put(TeamSide.PLAYER2, 0);
        towerDamageDealt.put(TeamSide.PLAYER1, 0.0);
        towerDamageDealt.put(TeamSide.PLAYER2, 0.0);
        experienceEarned.put(TeamSide.PLAYER1, 0);
        experienceEarned.put(TeamSide.PLAYER2, 0);
    }

    /**
     * Initialize arena with world reference. Call after world is loaded.
     */
    public void initialize(ServerWorld world) {
        this.arenaWorld = world;
        setupLanes();
        setupStructures();
    }

    /**
     * Set up the three lanes with deployment slots and waypoints.
     * Arena layout (Z axis = forward direction):
     *
     *  P1 base at Z_MIN, P2 base at Z_MAX
     *  Lanes run in Z direction
     *  Left lane: X = centerX - laneSeparation
     *  Center lane: X = centerX
     *  Right lane: X = centerX + laneSeparation
     */
    private void setupLanes() {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int sep = cfg.laneSeparation;

        // Lane centers (X positions)
        int leftX = cx - sep;
        int centerX = cx;
        int rightX = cx + sep;

        // Z positions: P1 is at negative Z, P2 at positive Z
        int p1BaseZ = cz - halfLen - 10;  // Base is 10 blocks behind lane start
        int p2BaseZ = cz + halfLen + 10;
        int p1DeployZ = cz - halfLen;
        int p2DeployZ = cz + halfLen;

        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            int laneX = switch (laneId) {
                case LEFT -> leftX;
                case CENTER -> centerX;
                case RIGHT -> rightX;
            };

            Lane lane = new Lane(laneId, laneX, cfg.laneWidth);

            // Deployment slots (2x2 grid) for P1
            lane.addDeploymentSlot(TeamSide.PLAYER1, new BlockPos(laneX - 1, y, p1DeployZ));
            lane.addDeploymentSlot(TeamSide.PLAYER1, new BlockPos(laneX, y, p1DeployZ));
            lane.addDeploymentSlot(TeamSide.PLAYER1, new BlockPos(laneX - 1, y, p1DeployZ + 1));
            lane.addDeploymentSlot(TeamSide.PLAYER1, new BlockPos(laneX, y, p1DeployZ + 1));

            // Deployment slots for P2
            lane.addDeploymentSlot(TeamSide.PLAYER2, new BlockPos(laneX - 1, y, p2DeployZ));
            lane.addDeploymentSlot(TeamSide.PLAYER2, new BlockPos(laneX, y, p2DeployZ));
            lane.addDeploymentSlot(TeamSide.PLAYER2, new BlockPos(laneX - 1, y, p2DeployZ - 1));
            lane.addDeploymentSlot(TeamSide.PLAYER2, new BlockPos(laneX, y, p2DeployZ - 1));

            // Generate waypoints from P1 deploy zone to P2 deploy zone
            List<BlockPos> waypoints = LanePathfinder.generateLaneWaypoints(
                    new BlockPos(laneX, y, p1DeployZ),
                    new BlockPos(laneX, y, p2DeployZ),
                    y
            );
            for (BlockPos wp : waypoints) {
                lane.addWaypoint(wp);
            }

            lanes.put(laneId, lane);
        }
    }

    /**
     * Set up throne and tower structures for both teams.
     */
    private void setupStructures() {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int sep = cfg.laneSeparation;

        int p1BaseZ = cz - halfLen - 10;
        int p2BaseZ = cz + halfLen + 10;

        // P1 Throne (center, behind base)
        ArenaStructure p1Throne = new ArenaStructure(
                ArenaStructure.StructureType.THRONE, TeamSide.PLAYER1,
                new BlockPos(cx, y, p1BaseZ),
                new Box(cx - 2, y, p1BaseZ - 2, cx + 2, y + 5, p1BaseZ + 2)
        );
        structures.add(p1Throne);

        // P1 Left Tower
        ArenaStructure p1LeftTower = new ArenaStructure(
                ArenaStructure.StructureType.TOWER, TeamSide.PLAYER1,
                new BlockPos(cx - sep, y, p1BaseZ + 3),
                new Box(cx - sep - 1, y, p1BaseZ + 2, cx - sep + 1, y + 4, p1BaseZ + 4)
        );
        p1LeftTower.setAssociatedLane(Lane.LaneId.LEFT);
        structures.add(p1LeftTower);

        // P1 Right Tower
        ArenaStructure p1RightTower = new ArenaStructure(
                ArenaStructure.StructureType.TOWER, TeamSide.PLAYER1,
                new BlockPos(cx + sep, y, p1BaseZ + 3),
                new Box(cx + sep - 1, y, p1BaseZ + 2, cx + sep + 1, y + 4, p1BaseZ + 4)
        );
        p1RightTower.setAssociatedLane(Lane.LaneId.RIGHT);
        structures.add(p1RightTower);

        // P2 Throne
        ArenaStructure p2Throne = new ArenaStructure(
                ArenaStructure.StructureType.THRONE, TeamSide.PLAYER2,
                new BlockPos(cx, y, p2BaseZ),
                new Box(cx - 2, y, p2BaseZ - 2, cx + 2, y + 5, p2BaseZ + 2)
        );
        structures.add(p2Throne);

        // P2 Left Tower
        ArenaStructure p2LeftTower = new ArenaStructure(
                ArenaStructure.StructureType.TOWER, TeamSide.PLAYER2,
                new BlockPos(cx - sep, y, p2BaseZ - 3),
                new Box(cx - sep - 1, y, p2BaseZ - 4, cx - sep + 1, y + 4, p2BaseZ - 2)
        );
        p2LeftTower.setAssociatedLane(Lane.LaneId.LEFT);
        structures.add(p2LeftTower);

        // P2 Right Tower
        ArenaStructure p2RightTower = new ArenaStructure(
                ArenaStructure.StructureType.TOWER, TeamSide.PLAYER2,
                new BlockPos(cx + sep, y, p2BaseZ - 3),
                new Box(cx + sep - 1, y, p2BaseZ - 4, cx + sep + 1, y + 4, p2BaseZ - 2)
        );
        p2RightTower.setAssociatedLane(Lane.LaneId.RIGHT);
        structures.add(p2RightTower);
    }

    /**
     * Spawn marker entities for all structures.
     */
    public void spawnStructureMarkers() {
        if (arenaWorld == null) return;
        for (ArenaStructure structure : structures) {
            structure.spawnMarker(arenaWorld);
        }
    }

    /**
     * Place a mob card into a deployment slot.
     * Returns true if placement was successful.
     */
    public boolean placeMob(TeamSide team, Lane.LaneId laneId, int slotIndex, MobCard card) {
        Lane lane = lanes.get(laneId);
        if (lane == null) return false;

        List<Lane.DeploymentSlot> slots = lane.getDeploymentSlots(team);
        if (slotIndex < 0 || slotIndex >= slots.size()) return false;

        Lane.DeploymentSlot slot = slots.get(slotIndex);
        if (!slot.isEmpty()) return false;

        slot.placeCard(card);
        return true;
    }

    /**
     * Remove a mob from a deployment slot, returning the card.
     */
    public MobCard removeMob(TeamSide team, Lane.LaneId laneId, int slotIndex) {
        Lane lane = lanes.get(laneId);
        if (lane == null) return null;

        List<Lane.DeploymentSlot> slots = lane.getDeploymentSlots(team);
        if (slotIndex < 0 || slotIndex >= slots.size()) return null;

        return slots.get(slotIndex).removeCard();
    }

    /**
     * Start the battle - spawn all placed mobs and begin their advance.
     */
    public void startBattle() {
        if (arenaWorld == null) return;
        battleActive = true;

        // Spawn mobs from all deployment slots
        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            Lane lane = lanes.get(laneId);
            for (TeamSide team : TeamSide.values()) {
                for (Lane.DeploymentSlot slot : lane.getDeploymentSlots(team)) {
                    if (slot.isEmpty()) continue;

                    MobCard card = slot.getPlacedCard();
                    ArenaMob arenaMob = new ArenaMob(
                            null, // owner UUID set by GameManager
                            team, card, laneId, slot.getPosition()
                    );

                    arenaMob.spawn(arenaWorld, slot.getPosition());
                    slot.setSpawnedMobId(arenaMob.getEntityId());

                    // Start advancing
                    arenaMob.startAdvancing(lane.getWaypoints(team));
                    activeMobs.add(arenaMob);
                }
            }
        }
    }

    /**
     * Tick the battle - update all mobs and structures.
     * Called every server tick during BATTLE phase.
     */
    public void tickBattle() {
        if (!battleActive || arenaWorld == null) return;

        // Tick all active mobs
        for (ArenaMob mob : activeMobs) {
            if (!mob.isDead()) {
                mob.tick(arenaWorld, activeMobs, structures);
            }
        }

        // Tick structures (tower/throne attacks)
        for (ArenaStructure structure : structures) {
            List<ArenaMob> enemies = activeMobs.stream()
                    .filter(m -> m.getTeam() != structure.getOwner() && !m.isDead())
                    .toList();
            structure.tick(arenaWorld, enemies);
        }

        // Track experience from kills
        activeMobs.stream()
                .filter(ArenaMob::isDead)
                .forEach(mob -> {
                    TeamSide killer = mob.getTeam().opponent();
                    experienceEarned.merge(killer, 1, Integer::sum);
                });

        // Clean up dead mobs from active list (keep for XP tracking)
    }

    /**
     * Check if the battle is over.
     */
    public BattleResult checkBattleEnd() {
        // Check if any throne is destroyed
        for (ArenaStructure s : structures) {
            if (s.getType() == ArenaStructure.StructureType.THRONE && s.isDestroyed()) {
                TeamSide winner = s.getOwner().opponent();
                return new BattleResult(BattleResult.Type.THRONE_DESTROYED, winner);
            }
        }

        // Check if all mobs are dead or idle (returned)
        boolean anyActive = activeMobs.stream()
                .anyMatch(m -> !m.isDead() && m.getState() != ArenaMob.MobState.IDLE);

        if (!anyActive) {
            return new BattleResult(BattleResult.Type.ALL_MOBS_DEAD, null);
        }

        return null; // Battle continues
    }

    /**
     * Order all mobs of a team to retreat.
     */
    public void orderRetreat(TeamSide team) {
        for (ArenaMob mob : activeMobs) {
            if (mob.getTeam() == team && !mob.isDead()) {
                mob.startRetreating();
            }
        }
    }

    /**
     * Recover mobs that successfully returned to their slots.
     * Returns list of recovered cards.
     */
    public List<MobCard> recoverReturnedMobs(TeamSide team) {
        List<MobCard> recovered = new ArrayList<>();
        for (ArenaMob mob : activeMobs) {
            if (mob.getTeam() == team && mob.getState() == ArenaMob.MobState.IDLE) {
                recovered.add(mob.getSourceCard());
                // Remove the entity
                if (arenaWorld != null && mob.getEntity(arenaWorld) != null) {
                    mob.getEntity(arenaWorld).discard();
                }
            }
        }
        return recovered;
    }

    /**
     * Clean up all entities and reset for next round.
     */
    public void cleanup() {
        battleActive = false;

        // Remove all mob entities
        if (arenaWorld != null) {
            for (ArenaMob mob : activeMobs) {
                var entity = mob.getEntity(arenaWorld);
                if (entity != null) entity.discard();
            }
        }
        activeMobs.clear();

        // Clear deployment slots
        for (Lane lane : lanes.values()) {
            for (TeamSide team : TeamSide.values()) {
                for (Lane.DeploymentSlot slot : lane.getDeploymentSlots(team)) {
                    slot.clear();
                }
            }
        }

        // Remove structure markers
        if (arenaWorld != null) {
            for (ArenaStructure structure : structures) {
                structure.removeMarker(arenaWorld);
            }
        }
    }

    /**
     * Full reset - also resets structures HP.
     */
    public void fullReset() {
        cleanup();
        structures.clear();
        lanes.clear();
        throneDamageDealt.put(TeamSide.PLAYER1, 0.0);
        throneDamageDealt.put(TeamSide.PLAYER2, 0.0);
        towersDestroyed.put(TeamSide.PLAYER1, 0);
        towersDestroyed.put(TeamSide.PLAYER2, 0);
        towerDamageDealt.put(TeamSide.PLAYER1, 0.0);
        towerDamageDealt.put(TeamSide.PLAYER2, 0.0);
        experienceEarned.put(TeamSide.PLAYER1, 0);
        experienceEarned.put(TeamSide.PLAYER2, 0);
    }

    // === Getters ===
    public Map<Lane.LaneId, Lane> getLanes() { return lanes; }
    public List<ArenaStructure> getStructures() { return structures; }
    public List<ArenaMob> getActiveMobs() { return activeMobs; }
    public boolean isBattleActive() { return battleActive; }
    public Map<TeamSide, Double> getThroneDamageDealt() { return throneDamageDealt; }
    public Map<TeamSide, Integer> getTowersDestroyed() { return towersDestroyed; }
    public Map<TeamSide, Double> getTowerDamageDealt() { return towerDamageDealt; }
    public Map<TeamSide, Integer> getExperienceEarned() { return experienceEarned; }

    /**
     * Get throne for a specific team.
     */
    public ArenaStructure getThrone(TeamSide team) {
        return structures.stream()
                .filter(s -> s.getType() == ArenaStructure.StructureType.THRONE && s.getOwner() == team)
                .findFirst().orElse(null);
    }

    /**
     * Get towers for a specific team.
     */
    public List<ArenaStructure> getTowers(TeamSide team) {
        return structures.stream()
                .filter(s -> s.getType() == ArenaStructure.StructureType.TOWER && s.getOwner() == team)
                .toList();
    }

    /**
     * Result of a battle or round.
     */
    public record BattleResult(Type type, TeamSide winner) {
        public enum Type {
            THRONE_DESTROYED,   // Instant win
            ALL_MOBS_DEAD,      // Round over, check damage
            TIMEOUT             // Time ran out
        }
    }
}
