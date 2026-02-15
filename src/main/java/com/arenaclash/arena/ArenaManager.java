package com.arenaclash.arena;

import com.arenaclash.ai.CombatSystem;
import com.arenaclash.ai.DamageNumberManager;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Central arena manager that coordinates:
 * - Arena definition loading
 * - Structure creation and management
 * - Mob deployment and tracking
 * - Combat system ticking
 * - Battle state and win conditions
 *
 * One instance per game, created when PREPARATION phase starts.
 */
public class ArenaManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-Arena");

    private ServerWorld world;
    private ArenaDefinition definition;
    private CombatSystem combatSystem;

    // Structures
    private final List<ArenaStructure> allStructures = new ArrayList<>();
    private final Map<TeamSide, ArenaStructure> thrones = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, List<ArenaStructure>> towers = new EnumMap<>(TeamSide.class);

    // Mobs
    private final List<ArenaMob> allMobs = new ArrayList<>();
    private final Map<TeamSide, List<ArenaMob>> teamMobs = new EnumMap<>(TeamSide.class);

    // Lanes
    private final Map<Lane.LaneId, Lane> lanes = new EnumMap<>(Lane.LaneId.class);

    // State
    private boolean battleActive = false;
    private int battleTick = 0;

    public ArenaManager(ServerWorld world, ArenaDefinition definition) {
        this.world = world;
        this.definition = definition;
        this.combatSystem = new CombatSystem(world, definition);

        // Initialize team maps
        for (TeamSide team : TeamSide.values()) {
            towers.put(team, new ArrayList<>());
            teamMobs.put(team, new ArrayList<>());
        }

        // Create lanes
        for (var entry : definition.getLanes().entrySet()) {
            lanes.put(entry.getKey(), new Lane(entry.getKey(), entry.getValue()));
        }

        // Create structures
        initStructures();
    }

    /**
     * No-arg constructor for backwards compatibility with GameManager.
     * Must call initialize() later to set world.
     */
    public ArenaManager() {
        this.world = null;
        this.definition = null;
        this.combatSystem = null;
        for (TeamSide team : TeamSide.values()) {
            towers.put(team, new ArrayList<>());
            teamMobs.put(team, new ArrayList<>());
        }
    }

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    private void initStructures() {
        for (TeamSide team : TeamSide.values()) {
            // Throne
            ArenaDefinition.StructureDef throneDef = definition.getThrone(team);
            if (throneDef != null) {
                ArenaStructure throne = new ArenaStructure(
                        ArenaStructure.StructureType.THRONE, team, throneDef, world);
                thrones.put(team, throne);
                allStructures.add(throne);
            }

            // Towers
            for (ArenaDefinition.StructureDef towerDef : definition.getTowers(team)) {
                ArenaStructure tower = new ArenaStructure(
                        ArenaStructure.StructureType.TOWER, team, towerDef, world);
                towers.get(team).add(tower);
                allStructures.add(tower);
            }
        }
    }

    // ========================================================================
    // MOB DEPLOYMENT
    // ========================================================================

    /**
     * Deploy a mob card onto the arena at the specified lane and slot.
     * Returns true if deployment succeeded.
     */
    public boolean deployMob(MobCard card, TeamSide team, Lane.LaneId laneId, int slotIndex) {
        Lane lane = lanes.get(laneId);
        if (lane == null) {
            LOGGER.warn("Cannot deploy: lane {} not found", laneId);
            return false;
        }

        ArenaDefinition.LaneDef laneDef = lane.getDefinition();
        List<BlockPos> slots = team == TeamSide.PLAYER1
                ? laneDef.deploymentSlotsP1
                : laneDef.deploymentSlotsP2;

        if (slotIndex < 0 || slotIndex >= slots.size()) {
            LOGGER.warn("Cannot deploy: slot {} out of range (max {})", slotIndex, slots.size());
            return false;
        }

        BlockPos slotPos = slots.get(slotIndex);
        Vec3d spawnPos = Vec3d.ofCenter(slotPos).add(0, 1, 0);

        // Look up mob definition
        MobCardDefinition mobDef = MobCardRegistry.getById(card.getMobId());
        if (mobDef == null) {
            LOGGER.warn("Cannot deploy: unknown card {}", card.getMobId());
            return false;
        }

        // Spawn the entity
        EntityType<?> entityType = mobDef.entityType();

        var entity = entityType.create(world);
        if (!(entity instanceof MobEntity mobEntity)) {
            LOGGER.warn("Cannot deploy: {} is not a MobEntity", card.getMobId());
            if (entity != null) entity.discard();
            return false;
        }

        // Position and setup
        mobEntity.setPosition(spawnPos.x, spawnPos.y, spawnPos.z);

        // Apply card stats
        int level = card.getLevel();
        float maxHp = (float) mobDef.getHP(level);
        mobEntity.setHealth(maxHp);

        // Tag with ArenaClash metadata
        CombatSystem.tagMob(mobEntity, team, laneId, level);

        // Also add command tags for client-side detection
        mobEntity.addCommandTag(CombatSystem.TAG_MOB);
        mobEntity.addCommandTag("team_" + team.name());
        mobEntity.addCommandTag("lane_" + laneId.name());

        // Custom name for visibility
        Formatting teamColor = team == TeamSide.PLAYER1 ? Formatting.BLUE : Formatting.RED;
        mobEntity.setCustomName(Text.literal(mobDef.displayName())
                .formatted(teamColor)
                .append(Text.literal(" Lv." + level).formatted(Formatting.GRAY)));
        mobEntity.setCustomNameVisible(false); // HP bar renderer handles display

        // Spawn in world
        world.spawnEntity(mobEntity);

        // Create ArenaMob wrapper for AI control
        ArenaMob arenaMob = new ArenaMob(mobEntity, team, lane);
        allMobs.add(arenaMob);
        teamMobs.get(team).add(arenaMob);

        LOGGER.info("Deployed {} (Lv.{}) for {} on {} lane slot {}",
                mobDef.displayName(), level, team, laneId, slotIndex);
        return true;
    }

    // ========================================================================
    // BATTLE TICK
    // ========================================================================

    /**
     * Start the battle. Called when both players ring their bells.
     */
    public void startBattle() {
        battleActive = true;
        battleTick = 0;
        LOGGER.info("Battle started! {} P1 mobs vs {} P2 mobs",
                teamMobs.get(TeamSide.PLAYER1).size(),
                teamMobs.get(TeamSide.PLAYER2).size());
    }

    /**
     * Main tick during BATTLE phase. Called every server tick.
     */
    public void tick() {
        if (!battleActive) return;
        battleTick++;

        // Tick damage numbers
        combatSystem.getDamageNumbers().tick();

        // Tick structures (tower/throne attacks)
        for (ArenaStructure structure : allStructures) {
            structure.tick(combatSystem.getDamageNumbers());
        }

        // Tick all alive mobs
        Iterator<ArenaMob> it = allMobs.iterator();
        while (it.hasNext()) {
            ArenaMob mob = it.next();
            if (mob.isDead()) {
                it.remove();
                teamMobs.get(mob.getTeam()).remove(mob);
                continue;
            }

            // Movement & targeting
            mob.tick();

            // Combat (attack if in range)
            combatSystem.tickMobCombat(mob.getEntity());
        }
    }

    /**
     * Stop the battle (round end or game over).
     */
    public void stopBattle() {
        battleActive = false;
    }

    // ========================================================================
    // WIN CONDITION CHECKS
    // ========================================================================

    /**
     * Check if either team's throne is destroyed.
     */
    public TeamSide checkThroneDestroyed() {
        for (var entry : thrones.entrySet()) {
            if (entry.getValue().isDestroyed()) {
                return entry.getKey(); // This team's throne was destroyed — they lose
            }
        }
        return null;
    }

    /**
     * Calculate round score based on surviving structures and mobs.
     */
    public Map<TeamSide, Integer> calculateScore() {
        Map<TeamSide, Integer> scores = new EnumMap<>(TeamSide.class);

        for (TeamSide team : TeamSide.values()) {
            int score = 0;

            // Surviving mobs
            score += teamMobs.get(team).size() * 10;

            // Surviving towers
            for (ArenaStructure tower : towers.get(team)) {
                if (!tower.isDestroyed()) score += 25;
            }

            // Throne HP bonus
            ArenaStructure throne = thrones.get(team);
            if (throne != null && !throne.isDestroyed()) {
                score += (int) (throne.getHpFraction() * 50);
            }

            scores.put(team, score);
        }

        return scores;
    }

    // ========================================================================
    // CLEANUP
    // ========================================================================

    /**
     * Remove all spawned mobs and reset for next round.
     */
    public void cleanup() {
        battleActive = false;

        // Remove all arena mobs
        for (ArenaMob mob : allMobs) {
            if (!mob.isDead()) {
                mob.getEntity().discard();
            }
        }
        allMobs.clear();
        for (TeamSide team : TeamSide.values()) {
            teamMobs.get(team).clear();
        }

        // Clear damage numbers
        if (combatSystem != null) {
            combatSystem.getDamageNumbers().clear();
        }
    }

    // ========================================================================
    // ADDITIONAL METHODS (GameManager compatibility)
    // ========================================================================

    /**
     * Spawn visual markers for structures (used during PREPARATION phase).
     */
    public void spawnStructureMarkers() {
        // In the new system, structures are defined by blocks in the template world
        // No additional markers needed — the map IS the visual
        LOGGER.info("Structure markers: using template world blocks (no marker spawning needed)");
    }

    /**
     * Place a mob card at a deployment slot (legacy API).
     */
    public boolean placeMob(TeamSide team, Lane.LaneId laneId, int slotIndex, MobCard card) {
        return deployMob(card, team, laneId, slotIndex);
    }

    /**
     * Remove a mob from a deployment slot (before battle starts).
     */
    public MobCard removeMob(TeamSide team, Lane.LaneId laneId, int slotIndex) {
        // Find and remove mob at this slot
        Iterator<ArenaMob> it = teamMobs.get(team).iterator();
        while (it.hasNext()) {
            ArenaMob mob = it.next();
            if (mob.getLane().getId() == laneId) {
                mob.getEntity().discard();
                it.remove();
                allMobs.remove(mob);
                // Return the card — in practice, GameManager handles card inventory
                return null;
            }
        }
        return null;
    }

    /**
     * Order all mobs of a team to retreat (bell ring during battle).
     */
    public void orderRetreat(TeamSide team) {
        for (ArenaMob mob : teamMobs.get(team)) {
            mob.orderRetreat();
        }
    }

    /**
     * Get all structures (towers + thrones).
     */
    public List<ArenaStructure> getStructures() {
        return Collections.unmodifiableList(allStructures);
    }

    /**
     * Get XP earned by each team this round.
     */
    public Map<TeamSide, Integer> getExperienceEarned() {
        Map<TeamSide, Integer> xp = new EnumMap<>(TeamSide.class);
        for (TeamSide team : TeamSide.values()) {
            int earned = 0;
            // XP from kills (each dead enemy mob = 5 XP)
            TeamSide enemy = team.opponent();
            int enemyDead = 0;
            for (ArenaMob mob : allMobs) {
                if (mob.isDead() && mob.getTeam() == enemy) enemyDead++;
            }
            earned += enemyDead * 5;

            // XP from structure damage
            for (ArenaStructure structure : allStructures) {
                if (structure.getTeam() == enemy) {
                    double damageDealt = structure.getMaxHp() - structure.getCurrentHp();
                    earned += (int) (damageDealt * 0.5);
                }
            }

            xp.put(team, earned);
        }
        return xp;
    }

    /**
     * Recover mobs that survived the battle (returned to base).
     */
    public List<MobCard> recoverReturnedMobs(TeamSide team) {
        // Surviving mobs can be recovered as cards for next round
        List<MobCard> recovered = new ArrayList<>();
        // In new system, mob recovery is handled differently
        // For now, return empty list
        return recovered;
    }

    /**
     * Get lanes map (for GameManager compatibility).
     */
    public Map<Lane.LaneId, Lane> getLanesMap() {
        return Collections.unmodifiableMap(lanes);
    }

    /**
     * Full reset — clear everything including structures.
     */
    public void fullReset() {
        cleanup();
        allStructures.clear();
        thrones.clear();
        for (TeamSide team : TeamSide.values()) {
            towers.get(team).clear();
        }
        lanes.clear();
        battleTick = 0;
    }

    // ========================================================================
    // BATTLE RESULT (for GameManager compatibility)
    // ========================================================================

    public record BattleResult(Type type, TeamSide winner) {
        public enum Type {
            THRONE_DESTROYED,
            ALL_MOBS_DEAD,
            TIMEOUT
        }
    }

    /**
     * Alias for tick() — called by GameManager during battle phase.
     */
    public void tickBattle() {
        tick();
    }

    /**
     * Check if the battle has ended.
     * Returns null if battle still ongoing.
     */
    public BattleResult checkBattleEnd() {
        // Check throne destruction
        TeamSide destroyed = checkThroneDestroyed();
        if (destroyed != null) {
            return new BattleResult(BattleResult.Type.THRONE_DESTROYED, destroyed.opponent());
        }

        // Check if all mobs on one side are dead
        boolean p1HasMobs = !teamMobs.get(TeamSide.PLAYER1).isEmpty();
        boolean p2HasMobs = !teamMobs.get(TeamSide.PLAYER2).isEmpty();

        if (!p1HasMobs && !p2HasMobs) {
            // Both sides wiped — score-based
            return new BattleResult(BattleResult.Type.ALL_MOBS_DEAD, null);
        }
        if (!p1HasMobs) {
            return new BattleResult(BattleResult.Type.ALL_MOBS_DEAD, TeamSide.PLAYER2);
        }
        if (!p2HasMobs) {
            return new BattleResult(BattleResult.Type.ALL_MOBS_DEAD, TeamSide.PLAYER1);
        }

        return null; // Battle still ongoing
    }

    /**
     * Initialize with a world (compatibility with old API).
     * Used when ArenaManager is created before ArenaDefinition is loaded.
     */
    public void initialize(ServerWorld world) {
        // No-op for new system — constructor handles initialization
    }

    // ========================================================================
    // GETTERS
    // ========================================================================

    public ArenaDefinition getDefinition() { return definition; }
    public CombatSystem getCombatSystem() { return combatSystem; }
    public boolean isBattleActive() { return battleActive; }
    public int getBattleTick() { return battleTick; }

    public List<ArenaMob> getMobs(TeamSide team) {
        return Collections.unmodifiableList(teamMobs.get(team));
    }

    public ArenaStructure getThrone(TeamSide team) { return thrones.get(team); }
    public List<ArenaStructure> getTowers(TeamSide team) {
        return Collections.unmodifiableList(towers.get(team));
    }

    public List<ArenaStructure> getAllStructures() {
        return Collections.unmodifiableList(allStructures);
    }

    public Lane getLane(Lane.LaneId id) { return lanes.get(id); }
}
