package com.arenaclash.game;

import com.arenaclash.arena.ArenaBuilder;
import com.arenaclash.arena.ArenaManager;
import com.arenaclash.arena.ArenaStructure;
import com.arenaclash.arena.Lane;
import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.config.GameConfig;
import com.arenaclash.network.NetworkHandler;
import com.arenaclash.world.WorldManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * Central game state manager. Controls the flow:
 * LOBBY -> SURVIVAL -> PREPARATION -> BATTLE -> ROUND_END -> (repeat) -> GAME_OVER
 */
public class GameManager {
    private static GameManager INSTANCE;

    private MinecraftServer server;
    private WorldManager worldManager;
    private ArenaManager arenaManager;

    private GamePhase phase = GamePhase.LOBBY;
    private int currentRound = 0;
    private int phaseTicksRemaining = 0;
    private int dayTickCounter = 0; // For accelerated day cycle

    private final Map<UUID, PlayerGameData> playerData = new HashMap<>();
    private boolean gameActive = false;

    // Track cumulative damage across rounds for victory conditions
    private final Map<TeamSide, Double> cumulativeThroneDamage = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Integer> cumulativeTowersDestroyed = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Double> cumulativeTowerDamage = new EnumMap<>(TeamSide.class);

    private GameManager() {
        cumulativeThroneDamage.put(TeamSide.PLAYER1, 0.0);
        cumulativeThroneDamage.put(TeamSide.PLAYER2, 0.0);
        cumulativeTowersDestroyed.put(TeamSide.PLAYER1, 0);
        cumulativeTowersDestroyed.put(TeamSide.PLAYER2, 0);
        cumulativeTowerDamage.put(TeamSide.PLAYER1, 0.0);
        cumulativeTowerDamage.put(TeamSide.PLAYER2, 0.0);
    }

    public static GameManager getInstance() {
        if (INSTANCE == null) INSTANCE = new GameManager();
        return INSTANCE;
    }

    public void init(MinecraftServer server) {
        this.server = server;
        this.worldManager = new WorldManager(server);
        this.arenaManager = new ArenaManager();
    }

    // === Game Start ===

    /**
     * Start a new game with the given players.
     */
    public String startGame(ServerPlayerEntity player1, ServerPlayerEntity player2) {
        if (gameActive) return "Game already in progress! Use /ac reset first.";

        GameConfig cfg = GameConfig.get();
        long seed = cfg.gameSeed != 0 ? cfg.gameSeed : new Random().nextLong();

        // Assign teams
        PlayerGameData p1Data = new PlayerGameData(player1.getUuid(), TeamSide.PLAYER1);
        PlayerGameData p2Data = new PlayerGameData(player2.getUuid(), TeamSide.PLAYER2);
        playerData.put(player1.getUuid(), p1Data);
        playerData.put(player2.getUuid(), p2Data);

        // Create worlds
        worldManager.createPlayerWorlds(seed);

        // Initialize arena
        arenaManager.initialize(worldManager.getArenaWorld());

        // Build the physical arena structure
        ArenaBuilder.buildArena(worldManager.getArenaWorld());

        // Reset cumulative stats
        for (TeamSide t : TeamSide.values()) {
            cumulativeThroneDamage.put(t, 0.0);
            cumulativeTowersDestroyed.put(t, 0);
            cumulativeTowerDamage.put(t, 0.0);
        }

        gameActive = true;
        currentRound = 1;

        // Start survival phase
        startSurvivalPhase();

        broadcastMessage("§6§l=== ARENA CLASH STARTED ===", Formatting.GOLD);
        broadcastMessage("§eRound 1 - Survival Phase (" + cfg.round1Days + " day(s))", Formatting.YELLOW);

        return "Game started! Seed: " + seed;
    }

    // === Phase Transitions ===

    private void startSurvivalPhase() {
        phase = GamePhase.SURVIVAL;
        GameConfig cfg = GameConfig.get();
        phaseTicksRemaining = cfg.getSurvivalDurationTicks(currentRound);
        dayTickCounter = 0;

        // Teleport players to their worlds
        for (PlayerGameData data : playerData.values()) {
            ServerPlayerEntity player = getPlayer(data.getPlayerId());
            if (player != null) {
                data.setReadyForBattle(false);
                worldManager.teleportToSurvival(player, data.getTeam());

                // Disable XP collection (handled by mixin/event)
                player.experienceLevel = 0;
                player.experienceProgress = 0;
                player.totalExperience = 0;
            }
        }

        syncGameState();
    }

    private void startPreparationPhase() {
        phase = GamePhase.PREPARATION;
        GameConfig cfg = GameConfig.get();
        phaseTicksRemaining = cfg.preparationTimeTicks;

        // Initialize arena structures for this round (if round 1)
        if (currentRound == 1) {
            arenaManager.spawnStructureMarkers();
        }

        // Teleport players to arena
        for (PlayerGameData data : playerData.values()) {
            ServerPlayerEntity player = getPlayer(data.getPlayerId());
            if (player != null) {
                data.saveSurvivalPosition(player);
                data.setReadyForBattle(false);
                worldManager.teleportToArena(player, data.getTeam());
            }
        }

        broadcastMessage("§e⚔ Preparation Phase! Place your mobs on the lanes!", Formatting.YELLOW);
        broadcastMessage("§7Ring the bell when ready, or wait for timer.", Formatting.GRAY);

        syncGameState();
        syncAllCards();
    }

    private void startBattlePhase() {
        phase = GamePhase.BATTLE;
        phaseTicksRemaining = 20 * 60 * 5; // 5 minute max battle time

        arenaManager.startBattle();

        broadcastMessage("§c§l⚔ BATTLE START! ⚔", Formatting.RED);
        syncGameState();
    }

    private void endRound(ArenaManager.BattleResult result) {
        phase = GamePhase.ROUND_END;

        // Accumulate damage stats
        // Track how much damage each team DEALT to the OPPONENT's structures
        for (TeamSide team : TeamSide.values()) {
            TeamSide opponent = team.opponent();
            ArenaStructure throne = arenaManager.getThrone(opponent);
            if (throne != null) {
                double dmg = throne.getMaxHP() - throne.getCurrentHP();
                cumulativeThroneDamage.merge(team, dmg, Double::sum);
            }
            for (ArenaStructure tower : arenaManager.getTowers(opponent)) {
                if (tower.isDestroyed()) {
                    cumulativeTowersDestroyed.merge(team, 1, Integer::sum);
                }
                double dmg = tower.getMaxHP() - tower.getCurrentHP();
                cumulativeTowerDamage.merge(team, dmg, Double::sum);
            }
        }

        // Award experience from kills
        Map<TeamSide, Integer> xpEarned = arenaManager.getExperienceEarned();
        for (PlayerGameData data : playerData.values()) {
            int xp = xpEarned.getOrDefault(data.getTeam(), 0);
            data.addExperience(xp);
        }

        // Recover retreated mobs
        for (PlayerGameData data : playerData.values()) {
            List<MobCard> recovered = arenaManager.recoverReturnedMobs(data.getTeam());
            for (MobCard card : recovered) {
                data.getCardInventory().addCard(card);
            }
            if (!recovered.isEmpty()) {
                ServerPlayerEntity player = getPlayer(data.getPlayerId());
                if (player != null) {
                    player.sendMessage(Text.literal("§a" + recovered.size() + " mobs returned safely!"));
                }
            }
        }

        // Check instant win
        if (result != null && result.type() == ArenaManager.BattleResult.Type.THRONE_DESTROYED) {
            endGame(result.winner());
            return;
        }

        // Clean up arena mobs (keep structures)
        arenaManager.cleanup();

        // Announce round results
        broadcastMessage("§6Round " + currentRound + " Complete!", Formatting.GOLD);

        GameConfig cfg = GameConfig.get();
        if (currentRound >= cfg.maxRounds) {
            // Game over - determine winner by cumulative stats
            TeamSide winner = determineWinner();
            endGame(winner);
        } else {
            // Next round
            currentRound++;
            broadcastMessage("§eNext round starts in 10 seconds...", Formatting.YELLOW);
            phaseTicksRemaining = 200; // 10 second pause before next round
        }
    }

    private void endGame(TeamSide winner) {
        phase = GamePhase.GAME_OVER;
        gameActive = false;

        if (winner != null) {
            PlayerGameData winnerData = getPlayerDataByTeam(winner);
            String winnerName = winnerData != null ?
                    getPlayerName(winnerData.getPlayerId()) : winner.name();
            broadcastMessage("§6§l=== " + winnerName + " WINS! ===", Formatting.GOLD);
        } else {
            broadcastMessage("§6§l=== DRAW! ===", Formatting.GOLD);
        }

        // Show final stats
        broadcastMessage("§7--- Final Stats ---", Formatting.GRAY);
        for (TeamSide team : TeamSide.values()) {
            broadcastMessage("§7" + team.name() + ": Throne Dmg: " +
                    String.format("%.1f", cumulativeThroneDamage.get(team)) +
                    " | Towers Destroyed: " + cumulativeTowersDestroyed.get(team) +
                    " | Tower Dmg: " + String.format("%.1f", cumulativeTowerDamage.get(team)),
                    Formatting.GRAY);
        }

        // Teleport players back
        for (PlayerGameData data : playerData.values()) {
            ServerPlayerEntity player = getPlayer(data.getPlayerId());
            if (player != null) {
                // Teleport to arena center for now
                broadcastMessage("§eUse /ac reset to start a new game.", Formatting.YELLOW);
            }
        }
    }

    /**
     * Determine winner based on cumulative damage (used after max rounds).
     */
    private TeamSide determineWinner() {
        // 1. Most throne damage
        double p1ThroneDmg = cumulativeThroneDamage.get(TeamSide.PLAYER1);
        double p2ThroneDmg = cumulativeThroneDamage.get(TeamSide.PLAYER2);
        if (p1ThroneDmg > p2ThroneDmg) return TeamSide.PLAYER1;
        if (p2ThroneDmg > p1ThroneDmg) return TeamSide.PLAYER2;

        // 2. Most towers destroyed
        int p1Towers = cumulativeTowersDestroyed.get(TeamSide.PLAYER1);
        int p2Towers = cumulativeTowersDestroyed.get(TeamSide.PLAYER2);
        if (p1Towers > p2Towers) return TeamSide.PLAYER1;
        if (p2Towers > p1Towers) return TeamSide.PLAYER2;

        // 3. Most tower damage
        double p1TowerDmg = cumulativeTowerDamage.get(TeamSide.PLAYER1);
        double p2TowerDmg = cumulativeTowerDamage.get(TeamSide.PLAYER2);
        if (p1TowerDmg > p2TowerDmg) return TeamSide.PLAYER1;
        if (p2TowerDmg > p1TowerDmg) return TeamSide.PLAYER2;

        // 4. Draw
        return null;
    }

    // === Main Tick ===

    /**
     * Called every server tick. Main game loop.
     */
    public void tick() {
        if (!gameActive) return;

        switch (phase) {
            case SURVIVAL -> tickSurvival();
            case PREPARATION -> tickPreparation();
            case BATTLE -> tickBattle();
            case ROUND_END -> tickRoundEnd();
            default -> {}
        }
    }

    private void tickSurvival() {
        phaseTicksRemaining--;

        // Accelerated day cycle
        GameConfig cfg = GameConfig.get();
        dayTickCounter++;
        int tickRatio = 24000 / cfg.dayDurationTicks; // How many vanilla ticks per our tick
        for (PlayerGameData data : playerData.values()) {
            var world = worldManager.getWorld(data.getTeam(), WorldManager.DimensionType.OVERWORLD);
            if (world != null) {
                // Advance time faster
                long currentTime = world.getTimeOfDay();
                world.setTimeOfDay(currentTime + tickRatio - 1); // -1 because vanilla already adds 1
            }
        }

        // Sync timer every second
        if (phaseTicksRemaining % 20 == 0) {
            syncGameState();
        }

        // Timer warnings
        if (phaseTicksRemaining == 600) { // 30 seconds left
            broadcastMessage("§c30 seconds until arena!", Formatting.RED);
        } else if (phaseTicksRemaining == 200) {
            broadcastMessage("§c10 seconds!", Formatting.RED);
        } else if (phaseTicksRemaining <= 60 && phaseTicksRemaining % 20 == 0) {
            broadcastMessage("§c" + (phaseTicksRemaining / 20) + "...", Formatting.RED);
        }

        if (phaseTicksRemaining <= 0) {
            startPreparationPhase();
        }
    }

    private void tickPreparation() {
        phaseTicksRemaining--;

        if (phaseTicksRemaining % 20 == 0) {
            syncGameState();
        }

        // Check if both players are ready (rang bell)
        boolean allReady = playerData.values().stream().allMatch(PlayerGameData::isReadyForBattle);
        if (allReady) {
            broadcastMessage("§aBoth players ready!", Formatting.GREEN);
            startBattlePhase();
            return;
        }

        if (phaseTicksRemaining <= 0) {
            startBattlePhase();
        }
    }

    private void tickBattle() {
        phaseTicksRemaining--;

        // Tick arena (mob AI, structure attacks)
        arenaManager.tickBattle();

        // Check battle end
        ArenaManager.BattleResult result = arenaManager.checkBattleEnd();
        if (result != null) {
            endRound(result);
            return;
        }

        // Battle timeout
        if (phaseTicksRemaining <= 0) {
            endRound(new ArenaManager.BattleResult(
                    ArenaManager.BattleResult.Type.TIMEOUT, null));
        }
    }

    private void tickRoundEnd() {
        phaseTicksRemaining--;
        if (phaseTicksRemaining <= 0) {
            // Start next survival phase
            startSurvivalPhase();
        }
    }

    // === Player Actions ===

    /**
     * Called when a player kills a mob in survival.
     * Creates a card and adds it to their inventory.
     */
    public void onMobKilled(ServerPlayerEntity player, EntityType<?> entityType) {
        if (phase != GamePhase.SURVIVAL) return;
        PlayerGameData data = playerData.get(player.getUuid());
        if (data == null) return;

        MobCardDefinition def = MobCardRegistry.getByEntityType(entityType);
        if (def == null) return; // Not a registered mob

        MobCard card = new MobCard(def.id());
        data.getCardInventory().addCard(card);

        // Send card obtained notification (totem animation)
        ServerPlayNetworking.send(player,
                new NetworkHandler.CardObtained(def.id(), def.displayName()));

        player.sendMessage(Text.literal("§a+ " + def.displayName() + " card obtained!"));

        // Sync card inventory
        syncCards(player);
    }

    /**
     * Handle card placement request from client.
     */
    public void handlePlaceCard(ServerPlayerEntity player, UUID cardId, Lane.LaneId laneId, int slotIndex) {
        if (phase != GamePhase.PREPARATION) return;
        PlayerGameData data = playerData.get(player.getUuid());
        if (data == null) return;

        MobCard card = data.getCardInventory().getCard(cardId);
        if (card == null) {
            player.sendMessage(Text.literal("§cCard not found!"));
            return;
        }

        boolean success = arenaManager.placeMob(data.getTeam(), laneId, slotIndex, card);
        if (success) {
            data.getCardInventory().removeCard(cardId);
            player.sendMessage(Text.literal("§aPlaced " + card.getDefinition().displayName()
                    + " on " + laneId + " lane"));
            syncCards(player);
            syncDeploymentSlots(player);
        } else {
            player.sendMessage(Text.literal("§cSlot is occupied!"));
        }
    }

    /**
     * Handle card removal request from client.
     */
    public void handleRemoveCard(ServerPlayerEntity player, Lane.LaneId laneId, int slotIndex) {
        if (phase != GamePhase.PREPARATION) return;
        PlayerGameData data = playerData.get(player.getUuid());
        if (data == null) return;

        MobCard card = arenaManager.removeMob(data.getTeam(), laneId, slotIndex);
        if (card != null) {
            data.getCardInventory().addCard(card);
            player.sendMessage(Text.literal("§eRemoved " + card.getDefinition().displayName()));
            syncCards(player);
            syncDeploymentSlots(player);
        }
    }

    /**
     * Handle bell ring from player.
     */
    public void handleBellRing(ServerPlayerEntity player) {
        PlayerGameData data = playerData.get(player.getUuid());
        if (data == null) return;

        if (phase == GamePhase.PREPARATION) {
            data.setReadyForBattle(true);
            broadcastMessage("§e" + player.getName().getString() + " is ready!", Formatting.YELLOW);
            // Play bell sound
            player.getServerWorld().playSound(null, player.getBlockPos(),
                    SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 2.0f, 1.0f);
        } else if (phase == GamePhase.BATTLE) {
            // Retreat command
            arenaManager.orderRetreat(data.getTeam());
            player.sendMessage(Text.literal("§e⚐ Retreat ordered! Your mobs are returning."));
        }
    }

    // === Sync Methods ===

    private void syncGameState() {
        for (PlayerGameData data : playerData.values()) {
            ServerPlayerEntity player = getPlayer(data.getPlayerId());
            if (player != null) {
                ServerPlayNetworking.send(player,
                        new NetworkHandler.GameStateSync(phase.name(), phaseTicksRemaining, currentRound));
            }
        }
    }

    public void syncCards(ServerPlayerEntity player) {
        PlayerGameData data = playerData.get(player.getUuid());
        if (data == null) return;
        ServerPlayNetworking.send(player,
                new NetworkHandler.CardInventorySync(data.getCardInventory().toNbt()));
    }

    private void syncAllCards() {
        for (PlayerGameData data : playerData.values()) {
            ServerPlayerEntity player = getPlayer(data.getPlayerId());
            if (player != null) syncCards(player);
        }
    }

    private void syncDeploymentSlots(ServerPlayerEntity player) {
        PlayerGameData data = playerData.get(player.getUuid());
        if (data == null) return;

        NbtCompound slotsData = new NbtCompound();
        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            Lane lane = arenaManager.getLanes().get(laneId);
            if (lane == null) continue;
            NbtCompound laneNbt = new NbtCompound();
            var slots = lane.getDeploymentSlots(data.getTeam());
            for (int i = 0; i < slots.size(); i++) {
                Lane.DeploymentSlot slot = slots.get(i);
                NbtCompound slotNbt = new NbtCompound();
                slotNbt.putBoolean("empty", slot.isEmpty());
                if (!slot.isEmpty()) {
                    slotNbt.put("card", slot.getPlacedCard().toNbt());
                }
                laneNbt.put("slot_" + i, slotNbt);
            }
            slotsData.put(laneId.name(), laneNbt);
        }

        ServerPlayNetworking.send(player, new NetworkHandler.DeploymentSlotSync(slotsData));
    }

    // === Reset ===

    public String resetGame() {
        // Clear arena blocks
        if (worldManager != null) {
            ArenaBuilder.clearArena(worldManager.getArenaWorld());
        }
        arenaManager.fullReset();
        if (worldManager != null) {
            worldManager.deleteAllWorlds();
        }
        playerData.clear();
        gameActive = false;
        phase = GamePhase.LOBBY;
        currentRound = 0;
        cumulativeThroneDamage.put(TeamSide.PLAYER1, 0.0);
        cumulativeThroneDamage.put(TeamSide.PLAYER2, 0.0);
        cumulativeTowersDestroyed.put(TeamSide.PLAYER1, 0);
        cumulativeTowersDestroyed.put(TeamSide.PLAYER2, 0);
        cumulativeTowerDamage.put(TeamSide.PLAYER1, 0.0);
        cumulativeTowerDamage.put(TeamSide.PLAYER2, 0.0);
        return "Game reset! All worlds deleted.";
    }

    // === Utility ===

    private ServerPlayerEntity getPlayer(UUID id) {
        return server.getPlayerManager().getPlayer(id);
    }

    private String getPlayerName(UUID id) {
        ServerPlayerEntity player = getPlayer(id);
        return player != null ? player.getName().getString() : "Unknown";
    }

    private PlayerGameData getPlayerDataByTeam(TeamSide team) {
        return playerData.values().stream()
                .filter(d -> d.getTeam() == team)
                .findFirst().orElse(null);
    }

    private void broadcastMessage(String message, Formatting color) {
        if (server == null) return;
        server.getPlayerManager().broadcast(
                Text.literal(message).formatted(color), false);
    }

    // === Getters ===
    public GamePhase getPhase() { return phase; }
    public int getCurrentRound() { return currentRound; }
    public int getPhaseTicksRemaining() { return phaseTicksRemaining; }
    public boolean isGameActive() { return gameActive; }
    public WorldManager getWorldManager() { return worldManager; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public PlayerGameData getPlayerData(UUID playerId) { return playerData.get(playerId); }
    public Map<UUID, PlayerGameData> getAllPlayerData() { return playerData; }
}
