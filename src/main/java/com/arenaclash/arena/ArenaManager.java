package com.arenaclash.arena;

import com.arenaclash.card.MobCard;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.*;

/**
 * Central manager for the arena. Handles setup, mob placement, battle ticking.
 *
 * KEY FIX: Waypoints now route side lanes through enemy tower first, then throne.
 * Center lane routes directly to enemy throne. Mobs no longer target spawn points.
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
    private final Map<TeamSide, Integer> experienceEarned = new EnumMap<>(TeamSide.class);

    // Bell positions for each team
    private final Map<TeamSide, BlockPos> bellPositions = new EnumMap<>(TeamSide.class);

    // Build zone bounds for each team
    private final Map<TeamSide, Box> buildZones = new EnumMap<>(TeamSide.class);

    private boolean battleActive = false;
    private int battleTickCount = 0;

    public ArenaManager() {
        for (TeamSide t : TeamSide.values()) {
            throneDamageDealt.put(t, 0.0);
            towersDestroyed.put(t, 0);
            towerDamageDealt.put(t, 0.0);
            experienceEarned.put(t, 0);
        }
    }

    public void initialize(ServerWorld world) {
        this.arenaWorld = world;
        setupLanes();
        setupStructures();
        setupBellPositions();
        setupBuildZones();
        generateWaypoints();
    }

    /**
     * Set up the three lanes with deployment slots.
     * Waypoints are generated separately AFTER structures are created.
     */
    private void setupLanes() {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int sep = cfg.laneSeparation;
        int laneW = cfg.laneWidth;

        int leftX = cx - sep;
        int centerX = cx;
        int rightX = cx + sep;

        int p1DeployZ = cz - halfLen;
        int p2DeployZ = cz + halfLen;

        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            int laneX = switch (laneId) {
                case LEFT -> leftX;
                case CENTER -> centerX;
                case RIGHT -> rightX;
            };

            Lane lane = new Lane(laneId, laneX, laneW);

            // Set lane bounds for mob confinement
            lane.setBounds(
                    laneX - laneW / 2.0,
                    laneX + laneW / 2.0,
                    Math.min(p1DeployZ, p2DeployZ) - 15, // Include base area
                    Math.max(p1DeployZ, p2DeployZ) + 15
            );

            // Deployment slots (2x2 grid = 4 slots) for P1
            lane.addDeploymentSlot(TeamSide.PLAYER1, new BlockPos(laneX - 1, y, p1DeployZ));
            lane.addDeploymentSlot(TeamSide.PLAYER1, new BlockPos(laneX, y, p1DeployZ));
            lane.addDeploymentSlot(TeamSide.PLAYER1, new BlockPos(laneX - 1, y, p1DeployZ + 1));
            lane.addDeploymentSlot(TeamSide.PLAYER1, new BlockPos(laneX, y, p1DeployZ + 1));

            // Deployment slots for P2
            lane.addDeploymentSlot(TeamSide.PLAYER2, new BlockPos(laneX - 1, y, p2DeployZ));
            lane.addDeploymentSlot(TeamSide.PLAYER2, new BlockPos(laneX, y, p2DeployZ));
            lane.addDeploymentSlot(TeamSide.PLAYER2, new BlockPos(laneX - 1, y, p2DeployZ - 1));
            lane.addDeploymentSlot(TeamSide.PLAYER2, new BlockPos(laneX, y, p2DeployZ - 1));

            // Waypoints will be generated after structures are set up
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

        // P1 Throne
        ArenaStructure p1Throne = new ArenaStructure(
                ArenaStructure.StructureType.THRONE, TeamSide.PLAYER1,
                new BlockPos(cx, y, p1BaseZ),
                new Box(cx - 2, y, p1BaseZ - 2, cx + 2, y + 5, p1BaseZ + 2));
        structures.add(p1Throne);

        // P1 Left Tower
        ArenaStructure p1LeftTower = new ArenaStructure(
                ArenaStructure.StructureType.TOWER, TeamSide.PLAYER1,
                new BlockPos(cx - sep, y, p1BaseZ + 3),
                new Box(cx - sep - 1, y, p1BaseZ + 2, cx - sep + 1, y + 4, p1BaseZ + 4));
        p1LeftTower.setAssociatedLane(Lane.LaneId.LEFT);
        structures.add(p1LeftTower);

        // P1 Right Tower
        ArenaStructure p1RightTower = new ArenaStructure(
                ArenaStructure.StructureType.TOWER, TeamSide.PLAYER1,
                new BlockPos(cx + sep, y, p1BaseZ + 3),
                new Box(cx + sep - 1, y, p1BaseZ + 2, cx + sep + 1, y + 4, p1BaseZ + 4));
        p1RightTower.setAssociatedLane(Lane.LaneId.RIGHT);
        structures.add(p1RightTower);

        // P2 Throne
        ArenaStructure p2Throne = new ArenaStructure(
                ArenaStructure.StructureType.THRONE, TeamSide.PLAYER2,
                new BlockPos(cx, y, p2BaseZ),
                new Box(cx - 2, y, p2BaseZ - 2, cx + 2, y + 5, p2BaseZ + 2));
        structures.add(p2Throne);

        // P2 Left Tower
        ArenaStructure p2LeftTower = new ArenaStructure(
                ArenaStructure.StructureType.TOWER, TeamSide.PLAYER2,
                new BlockPos(cx - sep, y, p2BaseZ - 3),
                new Box(cx - sep - 1, y, p2BaseZ - 4, cx - sep + 1, y + 4, p2BaseZ - 2));
        p2LeftTower.setAssociatedLane(Lane.LaneId.LEFT);
        structures.add(p2LeftTower);

        // P2 Right Tower
        ArenaStructure p2RightTower = new ArenaStructure(
                ArenaStructure.StructureType.TOWER, TeamSide.PLAYER2,
                new BlockPos(cx + sep, y, p2BaseZ - 3),
                new Box(cx + sep - 1, y, p2BaseZ - 4, cx + sep + 1, y + 4, p2BaseZ - 2));
        p2RightTower.setAssociatedLane(Lane.LaneId.RIGHT);
        structures.add(p2RightTower);
    }

    /**
     * Set up bell positions near thrones (not on mob paths).
     */
    private void setupBellPositions() {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;

        int p1BaseZ = cz - halfLen - 10;
        int p2BaseZ = cz + halfLen + 10;

        // Bells placed to the side of thrones, not blocking mob paths
        bellPositions.put(TeamSide.PLAYER1, new BlockPos(cx + 3, y + 1, p1BaseZ));
        bellPositions.put(TeamSide.PLAYER2, new BlockPos(cx + 3, y + 1, p2BaseZ));
    }

    /**
     * Set up build zones (behind throne for each team).
     */
    private void setupBuildZones() {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int sep = cfg.laneSeparation;
        int arenaHalfWidth = sep + cfg.laneWidth + 10;

        int p1BaseZ = cz - halfLen - 10;
        int p2BaseZ = cz + halfLen + 10;

        // P1 build zone: behind P1 throne
        buildZones.put(TeamSide.PLAYER1, new Box(
                cx - arenaHalfWidth, y, p1BaseZ - 5,
                cx + arenaHalfWidth, y + 10, p1BaseZ - 1));

        // P2 build zone: behind P2 throne
        buildZones.put(TeamSide.PLAYER2, new Box(
                cx - arenaHalfWidth, y, p2BaseZ + 1,
                cx + arenaHalfWidth, y + 10, p2BaseZ + 5));
    }

    /**
     * Generate proper waypoints for each lane.
     * KEY FIX: Side lanes route to enemy TOWER first, then THRONE.
     * Center lane routes directly to enemy THRONE.
     * Generates separate waypoints for P1→P2 and P2→P1 directions.
     */
    private void generateWaypoints() {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int sep = cfg.laneSeparation;

        int p1DeployZ = cz - halfLen;
        int p2DeployZ = cz + halfLen;
        int p1BaseZ = cz - halfLen - 10;
        int p2BaseZ = cz + halfLen + 10;

        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            Lane lane = lanes.get(laneId);
            if (lane == null) continue;
            lane.clearWaypoints();

            int laneX = lane.getCenterX();

            if (laneId == Lane.LaneId.CENTER) {
                // Center lane: direct path to enemy throne
                // P1→P2 waypoints
                List<BlockPos> p1wp = new ArrayList<>();
                for (int z = p1DeployZ; z <= p2BaseZ; z += 2) {
                    p1wp.add(new BlockPos(laneX, y, z));
                }
                p1wp.add(new BlockPos(cx, y, p2BaseZ));

                // P2→P1 waypoints
                List<BlockPos> p2wp = new ArrayList<>();
                for (int z = p2DeployZ; z >= p1BaseZ; z -= 2) {
                    p2wp.add(new BlockPos(laneX, y, z));
                }
                p2wp.add(new BlockPos(cx, y, p1BaseZ));

                lane.setDirectionalWaypoints(p1wp, p2wp);
            } else {
                // Side lanes: deploy → mid-lane → enemy tower → enemy throne
                int towerX = (laneId == Lane.LaneId.LEFT) ? cx - sep : cx + sep;

                // P1→P2 waypoints: toward P2 tower then P2 throne
                List<BlockPos> p1wp = new ArrayList<>();
                for (int z = p1DeployZ; z <= p2BaseZ - 5; z += 2) {
                    p1wp.add(new BlockPos(laneX, y, z));
                }
                p1wp.add(new BlockPos(towerX, y, p2BaseZ - 3));
                int stepsToThrone = Math.abs(towerX - cx) / 2 + 1;
                for (int i = 1; i <= stepsToThrone; i++) {
                    double t = (double) i / stepsToThrone;
                    int x = (int) (towerX + (cx - towerX) * t);
                    p1wp.add(new BlockPos(x, y, p2BaseZ));
                }
                p1wp.add(new BlockPos(cx, y, p2BaseZ));

                // P2→P1 waypoints: toward P1 tower then P1 throne
                List<BlockPos> p2wp = new ArrayList<>();
                for (int z = p2DeployZ; z >= p1BaseZ + 5; z -= 2) {
                    p2wp.add(new BlockPos(laneX, y, z));
                }
                p2wp.add(new BlockPos(towerX, y, p1BaseZ + 3));
                for (int i = 1; i <= stepsToThrone; i++) {
                    double t = (double) i / stepsToThrone;
                    int x = (int) (towerX + (cx - towerX) * t);
                    p2wp.add(new BlockPos(x, y, p1BaseZ));
                }
                p2wp.add(new BlockPos(cx, y, p1BaseZ));

                lane.setDirectionalWaypoints(p1wp, p2wp);
            }
        }
    }

    /**
     * Place bell blocks in the arena world.
     */
    public void placeBells() {
        if (arenaWorld == null) return;
        for (var entry : bellPositions.entrySet()) {
            BlockPos bellPos = entry.getValue();
            arenaWorld.setBlockState(bellPos, Blocks.BELL.getDefaultState());
        }
    }

    /**
     * Check if a block position is a bell for a specific team.
     */
    public TeamSide getBellTeam(BlockPos pos) {
        for (var entry : bellPositions.entrySet()) {
            if (entry.getValue().equals(pos)) return entry.getKey();
        }
        return null;
    }

    public BlockPos getBellPosition(TeamSide team) {
        return bellPositions.get(team);
    }

    /**
     * Check if a position is within a team's build zone.
     */
    public boolean isInBuildZone(TeamSide team, BlockPos pos) {
        Box zone = buildZones.get(team);
        if (zone == null) return false;
        return zone.contains(pos.getX(), pos.getY(), pos.getZ());
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
     * Remove a mob from a deployment slot.
     */
    public MobCard removeMob(TeamSide team, Lane.LaneId laneId, int slotIndex) {
        Lane lane = lanes.get(laneId);
        if (lane == null) return null;
        List<Lane.DeploymentSlot> slots = lane.getDeploymentSlots(team);
        if (slotIndex < 0 || slotIndex >= slots.size()) return null;
        return slots.get(slotIndex).removeCard();
    }

    /**
     * Start the battle - spawn all placed mobs, set lane bounds, begin advance.
     */
    public void startBattle() {
        if (arenaWorld == null) return;
        battleActive = true;
        battleTickCount = 0;

        removeDivider();

        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            Lane lane = lanes.get(laneId);
            for (TeamSide team : TeamSide.values()) {
                for (Lane.DeploymentSlot slot : lane.getDeploymentSlots(team)) {
                    if (slot.isEmpty()) continue;

                    MobCard card = slot.getPlacedCard();
                    ArenaMob arenaMob = new ArenaMob(null, team, card, laneId, slot.getPosition());

                    arenaMob.spawn(arenaWorld, slot.getPosition());
                    slot.setSpawnedMobId(arenaMob.getEntityId());

                    // Set lane bounds for confinement
                    if (lane.hasBounds()) {
                        arenaMob.setLaneBounds(
                                lane.getBoundsMinX(), lane.getBoundsMaxX(),
                                lane.getBoundsMinZ(), lane.getBoundsMaxZ());
                    }

                    // Generate proper waypoints for this mob's direction
                    List<BlockPos> waypoints = lane.getWaypoints(team);
                    arenaMob.startAdvancing(waypoints);
                    activeMobs.add(arenaMob);
                }
            }
        }
    }

    /**
     * Remove center divider.
     */
    private void removeDivider() {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int sep = cfg.laneSeparation;
        int arenaHalfWidth = sep + cfg.laneWidth + 10;

        for (int x = cx - arenaHalfWidth; x <= cx + arenaHalfWidth; x++) {
            BlockPos pos = new BlockPos(x, y, cz);
            if (!arenaWorld.getBlockState(pos).isAir()) {
                arenaWorld.setBlockState(pos, Blocks.AIR.getDefaultState());
            }
        }
    }

    /**
     * Rebuild divider between rounds.
     */
    public void rebuildDivider() {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int sep = cfg.laneSeparation;
        int arenaHalfWidth = sep + cfg.laneWidth + 10;

        for (int x = cx - arenaHalfWidth; x <= cx + arenaHalfWidth; x++) {
            arenaWorld.setBlockState(new BlockPos(x, y, cz), Blocks.TINTED_GLASS.getDefaultState());
        }
    }

    /**
     * Tick the battle - update all mobs and structures.
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

        // Refresh HP markers periodically
        if (battleTickCount % 10 == 0) {
            for (ArenaStructure structure : structures) {
                structure.updateMarkerName(arenaWorld);
            }
        }
        battleTickCount++;

        // Track experience from kills
        activeMobs.stream()
                .filter(ArenaMob::isDead)
                .forEach(mob -> {
                    TeamSide killer = mob.getTeam().opponent();
                    experienceEarned.merge(killer, 1, Integer::sum);
                });

        // Tick floating damage numbers
        ArenaMob.tickDamageNumbers(arenaWorld);
    }

    /**
     * Check if the battle is over.
     */
    public BattleResult checkBattleEnd() {
        for (ArenaStructure s : structures) {
            if (s.getType() == ArenaStructure.StructureType.THRONE && s.isDestroyed()) {
                return new BattleResult(BattleResult.Type.THRONE_DESTROYED, s.getOwner().opponent());
            }
        }

        boolean anyActive = activeMobs.stream()
                .anyMatch(m -> !m.isDead() && m.getState() != ArenaMob.MobState.IDLE);
        if (!anyActive) return new BattleResult(BattleResult.Type.ALL_MOBS_DEAD, null);

        return null;
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
     */
    public List<MobCard> recoverReturnedMobs(TeamSide team) {
        List<MobCard> recovered = new ArrayList<>();
        for (ArenaMob mob : activeMobs) {
            if (mob.getTeam() == team && mob.getState() == ArenaMob.MobState.IDLE) {
                recovered.add(mob.getSourceCard());
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
        if (arenaWorld != null) {
            for (ArenaMob mob : activeMobs) {
                mob.removeEntity(arenaWorld);
            }
            // Clean up floating damage numbers
            List<Entity> toRemove = new ArrayList<>();
            for (Entity e : arenaWorld.iterateEntities()) {
                if (e.getCommandTags().contains("arenaclash_dmg_number")
                        || e.getCommandTags().contains("arenaclash_tower_arrow")) {
                    toRemove.add(e);
                }
            }
            toRemove.forEach(Entity::discard);
        }
        activeMobs.clear();

        for (Lane lane : lanes.values()) {
            for (TeamSide team : TeamSide.values()) {
                for (Lane.DeploymentSlot slot : lane.getDeploymentSlots(team)) {
                    slot.clear();
                }
            }
        }

        if (arenaWorld != null) {
            for (ArenaStructure structure : structures) {
                structure.removeMarker(arenaWorld);
            }
            rebuildDivider();
        }
    }

    /**
     * Full reset - also resets structures HP.
     */
    public void fullReset() {
        cleanup();
        structures.clear();
        lanes.clear();
        bellPositions.clear();
        buildZones.clear();
        for (TeamSide t : TeamSide.values()) {
            throneDamageDealt.put(t, 0.0);
            towersDestroyed.put(t, 0);
            towerDamageDealt.put(t, 0.0);
            experienceEarned.put(t, 0);
        }
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

    public ArenaStructure getThrone(TeamSide team) {
        return structures.stream()
                .filter(s -> s.getType() == ArenaStructure.StructureType.THRONE && s.getOwner() == team)
                .findFirst().orElse(null);
    }

    public List<ArenaStructure> getTowers(TeamSide team) {
        return structures.stream()
                .filter(s -> s.getType() == ArenaStructure.StructureType.TOWER && s.getOwner() == team)
                .toList();
    }

    public record BattleResult(Type type, TeamSide winner) {
        public enum Type {
            THRONE_DESTROYED,
            ALL_MOBS_DEAD,
            TIMEOUT
        }
    }
}
