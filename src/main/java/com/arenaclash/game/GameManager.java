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
import com.arenaclash.tcp.ArenaClashTcpServer;
import com.arenaclash.tcp.SyncProtocol;
import com.arenaclash.tcp.TcpSession;
import com.arenaclash.world.WorldManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * Central game state manager with hybrid TCP + MC server architecture.
 *
 * Architecture:
 * - TCP connection is persistent: used for card sync, phase notifications, lobby
 * - MC server connection: only during PREPARATION + BATTLE phases
 * - Survival phase: players are in singleplayer, communicate via TCP only
 *
 * Flow:
 * LOBBY → (both TCP connected, /ac start) →
 * SURVIVAL (singleplayer, TCP sync) →
 * PREPARATION (MC server connect, place cards) →
 * BATTLE (MC server, watch fight) →
 * ROUND_END → SURVIVAL (MC disconnect, back to singleplayer) → ...
 */
public class GameManager {
    private static GameManager INSTANCE;

    private MinecraftServer server;
    private WorldManager worldManager;
    private ArenaManager arenaManager;
    private ArenaClashTcpServer tcpServer;

    private GamePhase phase = GamePhase.LOBBY;
    private int currentRound = 0;
    private int phaseTicksRemaining = 0;

    // Player sessions tracked by UUID
    private final Map<UUID, TeamSide> playerTeams = new HashMap<>();
    private final List<UUID> playerOrder = new ArrayList<>(); // [0]=P1, [1]=P2

    // Ready state for prep phase (by UUID)
    private final Set<UUID> readyPlayers = new HashSet<>();

    private boolean gameActive = false;
    private long currentGameSeed = 0;
    private int battleEndGraceTicks = -1; // Fix 5: grace period after all mobs dead before ending round

    // FIX 5: Pause state
    private boolean gamePaused = false;

    // FIX 7: World creation readiness tracking
    private final Set<UUID> worldReadyPlayers = new HashSet<>();
    private boolean waitingForWorlds = false;

    // Cumulative stats across rounds
    private final Map<TeamSide, Double> cumulativeThroneDamage = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Integer> cumulativeTowersDestroyed = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Double> cumulativeTowerDamage = new EnumMap<>(TeamSide.class);

    // Snapshots of structure damage from the PREVIOUS round, to avoid double-counting
    private final Map<TeamSide, Double> prevThroneDamageSnapshot = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Double> prevTowerDamageSnapshot = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Integer> prevTowersDestroyedSnapshot = new EnumMap<>(TeamSide.class);

    private GameManager() {}

    public static GameManager getInstance() {
        if (INSTANCE == null) INSTANCE = new GameManager();
        return INSTANCE;
    }

    public void init(MinecraftServer server) {
        this.server = server;
        this.worldManager = new WorldManager(server);
        this.arenaManager = new ArenaManager();
    }

    public void setTcpServer(ArenaClashTcpServer tcpServer) {
        this.tcpServer = tcpServer;
    }

    // ========================================================================
    // GAME START
    // ========================================================================

    /**
     * Start game via /ac start command. Requires 2 TCP-connected players.
     */
    public String startGame() {
        if (gameActive) return "Game already in progress! Use /ac reset first.";
        if (tcpServer == null || !tcpServer.hasTwoPlayers()) {
            return "Need 2 players connected via TCP! (" + (tcpServer != null ? tcpServer.getConnectedCount() : 0) + "/2)";
        }

        GameConfig cfg = GameConfig.get();

        // Assign teams to first 2 connected sessions
        List<TcpSession> sessions = new ArrayList<>(tcpServer.getSessions().values());
        TcpSession p1 = sessions.get(0);
        TcpSession p2 = sessions.get(1);

        p1.setTeam(TeamSide.PLAYER1);
        p2.setTeam(TeamSide.PLAYER2);
        playerTeams.put(p1.getPlayerUuid(), TeamSide.PLAYER1);
        playerTeams.put(p2.getPlayerUuid(), TeamSide.PLAYER2);
        playerOrder.clear();
        playerOrder.add(p1.getPlayerUuid());
        playerOrder.add(p2.getPlayerUuid());

        // Initialize arena world and build
        worldManager.createArenaWorld();
        arenaManager.initialize(worldManager.getArenaWorld());
        ArenaBuilder.buildArena(worldManager.getArenaWorld());

        // Disable natural mob spawning on arena world
        ServerWorld arenaWorld = worldManager.getArenaWorld();
        if (arenaWorld != null) {
            arenaWorld.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_SPAWNING).set(false, server);
        }

        // Reset stats
        for (TeamSide t : TeamSide.values()) {
            cumulativeThroneDamage.put(t, 0.0);
            cumulativeTowersDestroyed.put(t, 0);
            cumulativeTowerDamage.put(t, 0.0);
            prevThroneDamageSnapshot.put(t, 0.0);
            prevTowerDamageSnapshot.put(t, 0.0);
            prevTowersDestroyedSnapshot.put(t, 0);
        }

        gameActive = true;
        currentRound = 1;

        // Generate game seed
        GameConfig cfgSeed = GameConfig.get();
        long seed = cfgSeed.gameSeed != 0 ? cfgSeed.gameSeed : new java.util.Random().nextLong();
        this.currentGameSeed = seed;

        // Send game seed to all clients for singleplayer world creation
        tcpServer.broadcast(SyncProtocol.gameSeed(seed));

        // Start survival phase (players stay in singleplayer)
        startSurvivalPhase();

        tcpServer.broadcast(SyncProtocol.serverMessage("§6§l=== ARENA CLASH STARTED ==="));
        tcpServer.broadcast(SyncProtocol.serverMessage(
                "§eRound 1 - Survival Phase! Hunt mobs to get cards."));

        return "Game started! " + p1.getPlayerName() + " vs " + p2.getPlayerName();
    }

    /**
     * Legacy start with MC players (for backward compat / testing).
     */
    public String startGame(ServerPlayerEntity player1, ServerPlayerEntity player2) {
        // If TCP sessions exist for these players, use the new flow
        if (tcpServer != null) {
            TcpSession s1 = tcpServer.getSession(player1.getUuid());
            TcpSession s2 = tcpServer.getSession(player2.getUuid());
            if (s1 != null && s2 != null) {
                return startGame();
            }
        }
        return "Both players must be connected via TCP (Arena Clash button on title screen)!";
    }

    // ========================================================================
    // PHASE TRANSITIONS
    // ========================================================================

    private void startSurvivalPhase() {
        phase = GamePhase.SURVIVAL;
        GameConfig cfg = GameConfig.get();
        phaseTicksRemaining = cfg.getSurvivalDurationTicks(currentRound);
        readyPlayers.clear();

        // FIX 7: Wait for both players to create their worlds before starting timer
        worldReadyPlayers.clear();
        waitingForWorlds = true;

        // Sync inventory FROM arena server TO client (for restoring in singleplayer)
        saveAndSyncArenaInventories();

        // Tell clients: stay in singleplayer, survival phase started
        tcpServer.broadcast(SyncProtocol.phaseChange("SURVIVAL", currentRound, phaseTicksRemaining));

        // Send seed so clients can create/reload their world (Fix 3)
        tcpServer.broadcast(SyncProtocol.gameSeed(currentGameSeed));

        // If players are on MC server, tell them to disconnect
        tcpServer.broadcast(SyncProtocol.returnToSingle());

        // Clear inventories at round 1
        if (currentRound == 1) {
            for (UUID uuid : playerOrder) {
                TcpSession session = tcpServer.getSession(uuid);
                if (session != null) {
                    session.getCardInventory().clear();
                }
            }
        }

        syncAllCards();
    }

    private void startPreparationPhase() {
        phase = GamePhase.PREPARATION;
        GameConfig cfg = GameConfig.get();
        phaseTicksRemaining = cfg.preparationTimeTicks;
        readyPlayers.clear();

        // Initialize arena structures (round 1 only)
        if (currentRound == 1) {
            arenaManager.spawnStructureMarkers();
        }

        // Tell clients: connect to MC server for preparation
        String host = server.getServerIp();
        if (host == null || host.isEmpty()) host = "localhost";
        int mcPort = server.getServerPort();
        if (mcPort <= 0) mcPort = 25565;

        tcpServer.broadcast(SyncProtocol.phaseChange("PREPARATION", currentRound, phaseTicksRemaining));
        tcpServer.broadcast(SyncProtocol.connectToMc(host, mcPort));
        tcpServer.broadcast(SyncProtocol.serverMessage(
                "§e⚔ Preparation Phase! Connect to server to place your mobs!"));

        syncAllCards();

        // Fix 5: Sync empty deployment slots to all clients at start of preparation
        for (UUID uuid : playerOrder) {
            TcpSession session = tcpServer.getSession(uuid);
            if (session != null) {
                syncDeploymentSlots(session);
            }
        }
    }

    private void startBattlePhase() {
        phase = GamePhase.BATTLE;
        phaseTicksRemaining = -1; // Fix 5: No battle timeout (unlimited)
        battleEndGraceTicks = -1; // Reset grace period

        arenaManager.startBattle();

        tcpServer.broadcast(SyncProtocol.phaseChange("BATTLE", currentRound, phaseTicksRemaining));
        tcpServer.broadcast(SyncProtocol.serverMessage("§c§l⚔ BATTLE START! ⚔"));
        broadcastMc("§c§l⚔ BATTLE START! ⚔", Formatting.RED);
    }

    private void endRound(ArenaManager.BattleResult result) {
        phase = GamePhase.ROUND_END;

        // Accumulate damage stats - only count NEW damage since last round
        for (TeamSide team : TeamSide.values()) {
            TeamSide opponent = team.opponent();
            ArenaStructure throne = arenaManager.getThrone(opponent);
            if (throne != null) {
                double totalDmg = throne.getMaxHP() - throne.getCurrentHP();
                double prevDmg = prevThroneDamageSnapshot.getOrDefault(team, 0.0);
                double newDmg = totalDmg - prevDmg;
                if (newDmg > 0.01) { // Epsilon to avoid floating point noise
                    cumulativeThroneDamage.merge(team, newDmg, Double::sum);
                }
                prevThroneDamageSnapshot.put(team, totalDmg);
            }
            int currentDestroyed = 0;
            double totalTowerDmg = 0.0;
            for (ArenaStructure tower : arenaManager.getTowers(opponent)) {
                if (tower.isDestroyed()) currentDestroyed++;
                totalTowerDmg += tower.getMaxHP() - tower.getCurrentHP();
            }
            int prevDestroyed = prevTowersDestroyedSnapshot.getOrDefault(team, 0);
            int newDestroyed = currentDestroyed - prevDestroyed;
            if (newDestroyed > 0) {
                cumulativeTowersDestroyed.merge(team, newDestroyed, Integer::sum);
            }
            prevTowersDestroyedSnapshot.put(team, currentDestroyed);

            double prevTowerDmg = prevTowerDamageSnapshot.getOrDefault(team, 0.0);
            double newTowerDmg = totalTowerDmg - prevTowerDmg;
            if (newTowerDmg > 0.01) {
                cumulativeTowerDamage.merge(team, newTowerDmg, Double::sum);
            }
            prevTowerDamageSnapshot.put(team, totalTowerDmg);
        }

        // Award experience
        Map<TeamSide, Integer> xpEarned = arenaManager.getExperienceEarned();
        for (UUID uuid : playerOrder) {
            TcpSession session = tcpServer.getSession(uuid);
            if (session != null) {
                TeamSide team = session.getTeam();
                int xp = xpEarned.getOrDefault(team, 0);
                // Level up cards with XP (simplified)
            }
        }

        // Recover retreated mobs
        for (UUID uuid : playerOrder) {
            TcpSession session = tcpServer.getSession(uuid);
            if (session != null) {
                List<MobCard> recovered = arenaManager.recoverReturnedMobs(session.getTeam());
                for (MobCard card : recovered) {
                    session.getCardInventory().addCard(card);
                }
                if (!recovered.isEmpty()) {
                    session.send(SyncProtocol.serverMessage(
                            "§a" + recovered.size() + " mobs returned safely!"));
                }
            }
        }

        // Check instant win
        if (result != null && result.type() == ArenaManager.BattleResult.Type.THRONE_DESTROYED) {
            endGame(result.winner());
            return;
        }

        arenaManager.cleanup();

        tcpServer.broadcast(SyncProtocol.serverMessage("§6Round " + currentRound + " Complete!"));

        GameConfig cfg = GameConfig.get();
        if (currentRound >= cfg.maxRounds) {
            TeamSide winner = determineWinner();
            endGame(winner);
        } else {
            currentRound++;
            phaseTicksRemaining = 200; // 10 sec pause
            tcpServer.broadcast(SyncProtocol.serverMessage("§eNext round starts in 10 seconds..."));
        }
    }

    private void endGame(TeamSide winner) {
        phase = GamePhase.GAME_OVER;
        // gameActive stays true during GAME_OVER phase for tick processing

        String winnerName = "Draw";
        String loserName = "Draw";
        UUID winnerUuid = null;
        UUID loserUuid = null;

        if (winner != null && playerOrder.size() > winner.ordinal()) {
            winnerUuid = playerOrder.get(winner.ordinal());
            loserUuid = playerOrder.get(winner.opponent().ordinal());
            TcpSession winSession = tcpServer.getSession(winnerUuid);
            TcpSession loseSession = tcpServer.getSession(loserUuid);
            if (winSession != null) winnerName = winSession.getPlayerName();
            if (loseSession != null) loserName = loseSession.getPlayerName();
        }

        // Build detailed result info
        String details = buildGameResultDetails(winner);

        // FIX 4: Send game result with winner/loser details for proper end screen
        tcpServer.broadcast(SyncProtocol.gameResult(winnerName, details));
        tcpServer.broadcast(SyncProtocol.serverMessage("§6§l=== GAME OVER === Winner: " + winnerName));

        // FIX 4: Send title screen messages and spawn fireworks for winner
        if (server != null) {
            for (UUID uuid : playerOrder) {
                ServerPlayerEntity player = getPlayer(uuid);
                if (player == null) continue;
                TeamSide playerTeam = playerTeams.get(uuid);

                if (winner == null) {
                    // Draw
                    showTitle(player, "§e§lDRAW", "§7No clear winner");
                } else if (playerTeam == winner) {
                    // Winner
                    showTitle(player, "§a§lVICTORY!", "§6You defeated " + loserName + "!");
                    // Spawn fireworks around the winner
                    spawnFireworks(player, 10);
                } else {
                    // Loser
                    showTitle(player, "§c§lDEFEAT", "§7" + winnerName + " has won");
                }
            }
        }

        // Don't immediately return to singleplayer - let players see the result for 15 seconds
        phaseTicksRemaining = 300; // 15 seconds

        // Schedule return after delay (handled in tick)
    }

    private String buildGameResultDetails(TeamSide winner) {
        StringBuilder sb = new StringBuilder();
        double p1Throne = cumulativeThroneDamage.getOrDefault(TeamSide.PLAYER1, 0.0);
        double p2Throne = cumulativeThroneDamage.getOrDefault(TeamSide.PLAYER2, 0.0);
        int p1Towers = cumulativeTowersDestroyed.getOrDefault(TeamSide.PLAYER1, 0);
        int p2Towers = cumulativeTowersDestroyed.getOrDefault(TeamSide.PLAYER2, 0);
        sb.append(String.format("Throne Damage: P1=%.0f / P2=%.0f | ", p1Throne, p2Throne));
        sb.append(String.format("Towers Destroyed: P1=%d / P2=%d", p1Towers, p2Towers));
        return sb.toString();
    }

    private void showTitle(ServerPlayerEntity player, String title, String subtitle) {
        if (player == null || player.isDisconnected()) return;
        try {
            // Use Minecraft's built-in /title command via the server
            String playerName = player.getName().getString();
            String titleCmd = "title " + playerName + " title " + toJsonText(title);
            String subtitleCmd = "title " + playerName + " subtitle " + toJsonText(subtitle);
            String timesCmd = "title " + playerName + " times 10 100 30";

            server.getCommandManager().executeWithPrefix(
                    server.getCommandSource().withSilent(), timesCmd);
            server.getCommandManager().executeWithPrefix(
                    server.getCommandSource().withSilent(), subtitleCmd);
            server.getCommandManager().executeWithPrefix(
                    server.getCommandSource().withSilent(), titleCmd);
        } catch (Exception e) {
            // Fallback to chat message
            player.sendMessage(Text.literal(title));
            player.sendMessage(Text.literal(subtitle));
        }
    }

    private String toJsonText(String text) {
        // Convert §-formatted text to JSON text component
        return "{\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
    }

    private void spawnFireworks(ServerPlayerEntity player, int count) {
        if (player == null || player.isDisconnected()) return;
        net.minecraft.server.world.ServerWorld world = player.getServerWorld();
        for (int i = 0; i < count; i++) {
            double x = player.getX() + (world.getRandom().nextDouble() - 0.5) * 10;
            double y = player.getY() + 2 + world.getRandom().nextDouble() * 5;
            double z = player.getZ() + (world.getRandom().nextDouble() - 0.5) * 10;

            // Create a simple firework rocket entity
            net.minecraft.item.ItemStack fireworkStack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.FIREWORK_ROCKET);
            net.minecraft.entity.projectile.FireworkRocketEntity firework =
                    new net.minecraft.entity.projectile.FireworkRocketEntity(world, x, y, z, fireworkStack);

            // Stagger the fireworks over time
            final int delay = i * 10; // 0.5 seconds apart
            final net.minecraft.entity.projectile.FireworkRocketEntity fw = firework;
            new Thread(() -> {
                try { Thread.sleep(delay * 50L); } catch (InterruptedException ignored) {}
                server.execute(() -> {
                    try { world.spawnEntity(fw); } catch (Exception ignored) {}
                });
            }, "ArenaClash-Firework-" + i).start();
        }
    }

    private TeamSide determineWinner() {
        double epsilon = 0.01; // Ignore tiny floating point differences

        // Priority 1: Most throne damage
        double p1ThroneDmg = cumulativeThroneDamage.getOrDefault(TeamSide.PLAYER1, 0.0);
        double p2ThroneDmg = cumulativeThroneDamage.getOrDefault(TeamSide.PLAYER2, 0.0);
        if (p1ThroneDmg - p2ThroneDmg > epsilon) return TeamSide.PLAYER1;
        if (p2ThroneDmg - p1ThroneDmg > epsilon) return TeamSide.PLAYER2;

        // Priority 2: Most enemy towers destroyed
        int p1Towers = cumulativeTowersDestroyed.getOrDefault(TeamSide.PLAYER1, 0);
        int p2Towers = cumulativeTowersDestroyed.getOrDefault(TeamSide.PLAYER2, 0);
        if (p1Towers > p2Towers) return TeamSide.PLAYER1;
        if (p2Towers > p1Towers) return TeamSide.PLAYER2;

        // Priority 3: Most tower damage
        double p1TowerDmg = cumulativeTowerDamage.getOrDefault(TeamSide.PLAYER1, 0.0);
        double p2TowerDmg = cumulativeTowerDamage.getOrDefault(TeamSide.PLAYER2, 0.0);
        if (p1TowerDmg - p2TowerDmg > epsilon) return TeamSide.PLAYER1;
        if (p2TowerDmg - p1TowerDmg > epsilon) return TeamSide.PLAYER2;

        // Draw
        return null;
    }

    // ========================================================================
    // TICK
    // ========================================================================

    public void tick() {
        if (!gameActive) return;
        if (gamePaused) return;

        // FIX 7: If waiting for world creation, don't tick survival timer
        if (waitingForWorlds && phase == GamePhase.SURVIVAL) {
            return;
        }

        switch (phase) {
            case SURVIVAL -> tickSurvival();
            case PREPARATION -> tickPreparation();
            case BATTLE -> tickBattle();
            case ROUND_END -> tickRoundEnd();
            case GAME_OVER -> tickGameOver();
            default -> {}
        }
    }

    // === FIX 5: Pause/Continue/Skip commands ===

    public String pauseGame() {
        if (!gameActive) return "No game in progress!";
        if (gamePaused) return "Game is already paused!";
        gamePaused = true;
        tcpServer.broadcast(SyncProtocol.serverMessage("§e§l⏸ Game paused"));
        return "Game paused!";
    }

    public String continueGame() {
        if (!gameActive) return "No game in progress!";
        if (!gamePaused) return "Game is not paused!";
        gamePaused = false;
        tcpServer.broadcast(SyncProtocol.serverMessage("§a§l▶ Game resumed"));
        return "Game resumed!";
    }

    public String skipPhase() {
        if (!gameActive) return "No game in progress!";
        switch (phase) {
            case SURVIVAL -> {
                waitingForWorlds = false;
                phaseTicksRemaining = 0;
                tcpServer.broadcast(SyncProtocol.serverMessage("§cSurvival phase skipped!"));
                startPreparationPhase();
                return "Skipped to preparation phase!";
            }
            case PREPARATION -> {
                tcpServer.broadcast(SyncProtocol.serverMessage("§cPreparation phase skipped!"));
                startBattlePhase();
                return "Skipped to battle phase!";
            }
            case BATTLE -> {
                tcpServer.broadcast(SyncProtocol.serverMessage("§cBattle skipped!"));
                endRound(new ArenaManager.BattleResult(
                        ArenaManager.BattleResult.Type.ALL_MOBS_DEAD, null));
                return "Skipped battle!";
            }
            default -> {
                return "Cannot skip this phase: " + phase;
            }
        }
    }

    public boolean isGamePaused() { return gamePaused; }

    /**
     * Called when a player's pause state changes (from ESC menu).
     * If BOTH players are paused → pause the game.
     * If at least one player unpauses → resume the game.
     */
    public void updatePauseFromClients() {
        if (!gameActive) return;
        boolean allPaused = true;
        for (UUID uuid : playerOrder) {
            TcpSession session = tcpServer.getSession(uuid);
            if (session != null && session.isAlive() && !session.isPaused()) {
                allPaused = false;
                break;
            }
        }
        if (allPaused && !gamePaused) {
            gamePaused = true;
            tcpServer.broadcast(SyncProtocol.serverMessage("§e§l⏸ Game paused (both players paused)"));
        } else if (!allPaused && gamePaused) {
            gamePaused = false;
            tcpServer.broadcast(SyncProtocol.serverMessage("§a§l▶ Game resumed"));
        }
    }

    // === FIX 7: World creation readiness ===

    /**
     * Called when a player reports their singleplayer world is created and ready.
     */
    public void onPlayerWorldReady(UUID playerUuid) {
        if (!waitingForWorlds) return;
        worldReadyPlayers.add(playerUuid);
        tcpServer.broadcast(SyncProtocol.serverMessage(
                "§a" + worldReadyPlayers.size() + "/2 players ready"));
        if (worldReadyPlayers.size() >= 2) {
            waitingForWorlds = false;
            tcpServer.broadcast(SyncProtocol.serverMessage("§a§lBoth worlds created! Timer starting..."));
        }
    }

    // === FIX 10: Inventory sync ===

    /**
     * Called when a player sends their singleplayer inventory data.
     * Stored in session for use when they join the arena.
     */
    public void onInventorySync(TcpSession session, String itemsJson) {
        session.setSavedInventoryJson(itemsJson);
    }

    /**
     * Save each player's MC server inventory and send it via TCP so clients
     * can restore it when they return to singleplayer.
     */
    private void saveAndSyncArenaInventories() {
        ServerWorld arenaWorld = worldManager.getArenaWorld();
        if (arenaWorld == null) return;
        for (UUID uuid : playerOrder) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            TcpSession session = tcpServer.getSession(uuid);
            if (player != null && session != null) {
                try {
                    NbtCompound invNbt = new NbtCompound();
                    NbtList items = new NbtList();
                    player.getInventory().writeNbt(items);
                    invNbt.put("Items", items);
                    String invSnbt = invNbt.toString();
                    session.setSavedInventoryJson(invSnbt);
                    session.send(SyncProtocol.serverInventorySync(invSnbt));
                } catch (Exception e) {
                    // Ignore - player may not be on MC server
                }
            }
        }
    }

    private void tickSurvival() {
        phaseTicksRemaining--;

        // Sync timer every second via TCP
        if (phaseTicksRemaining % 20 == 0) {
            tcpServer.broadcast(SyncProtocol.timerSync(phaseTicksRemaining));
        }

        // Warnings
        if (phaseTicksRemaining == 600) {
            tcpServer.broadcast(SyncProtocol.serverMessage("§c30 seconds until arena!"));
        } else if (phaseTicksRemaining == 200) {
            tcpServer.broadcast(SyncProtocol.serverMessage("§c10 seconds!"));
        } else if (phaseTicksRemaining <= 60 && phaseTicksRemaining > 0 && phaseTicksRemaining % 20 == 0) {
            tcpServer.broadcast(SyncProtocol.serverMessage("§c" + (phaseTicksRemaining / 20) + "..."));
        }

        if (phaseTicksRemaining <= 0) {
            startPreparationPhase();
        }
    }

    private void tickPreparation() {
        phaseTicksRemaining--;

        if (phaseTicksRemaining % 20 == 0) {
            tcpServer.broadcast(SyncProtocol.timerSync(phaseTicksRemaining));
        }

        // Keep structure HP markers updated during preparation (every 2 seconds)
        if (phaseTicksRemaining % 40 == 0 && arenaManager != null && worldManager != null && worldManager.getArenaWorld() != null) {
            for (var structure : arenaManager.getStructures()) {
                structure.updateMarkerName(worldManager.getArenaWorld());
            }
        }

        // Check if both ready
        if (readyPlayers.size() >= 2) {
            tcpServer.broadcast(SyncProtocol.serverMessage("§aBoth players ready!"));
            startBattlePhase();
            return;
        }

        if (phaseTicksRemaining <= 0) {
            startBattlePhase();
        }
    }

    private void tickBattle() {
        // Fix 5: No countdown timeout for battle
        arenaManager.tickBattle();

        // Check for instant-win (throne destroyed)
        ArenaManager.BattleResult result = arenaManager.checkBattleEnd();
        if (result != null && result.type() == ArenaManager.BattleResult.Type.THRONE_DESTROYED) {
            endRound(result);
            return;
        }

        // ALL_MOBS_DEAD: start a grace period before ending the round
        if (result != null && result.type() == ArenaManager.BattleResult.Type.ALL_MOBS_DEAD) {
            if (battleEndGraceTicks < 0) {
                // Start the 10-second grace period
                battleEndGraceTicks = 200; // 10 seconds
                tcpServer.broadcast(SyncProtocol.serverMessage("§eAll mobs finished! Round ending in 10 seconds..."));
                broadcastMc("§eAll mobs finished! Round ending in 10 seconds...", Formatting.YELLOW);
                // Send grace timer to clients so they see a countdown
                tcpServer.broadcast(SyncProtocol.timerSync(battleEndGraceTicks));
            }
        } else {
            // Mobs are still active - reset grace timer if it was started
            if (battleEndGraceTicks > 0) {
                battleEndGraceTicks = -1;
            }
        }

        // Tick grace period
        if (battleEndGraceTicks > 0) {
            battleEndGraceTicks--;
            if (battleEndGraceTicks % 20 == 0 && battleEndGraceTicks > 0) {
                tcpServer.broadcast(SyncProtocol.timerSync(battleEndGraceTicks));
            }
            if (battleEndGraceTicks <= 0) {
                endRound(new ArenaManager.BattleResult(
                        ArenaManager.BattleResult.Type.ALL_MOBS_DEAD, null));
            }
        }
    }

    private void tickRoundEnd() {
        phaseTicksRemaining--;
        if (phaseTicksRemaining <= 0) {
            startSurvivalPhase();
        }
    }

    /**
     * FIX 4: Game over countdown - show results for 15 seconds then clean up.
     */
    private void tickGameOver() {
        phaseTicksRemaining--;
        if (phaseTicksRemaining % 20 == 0) {
            tcpServer.broadcast(SyncProtocol.timerSync(phaseTicksRemaining));
        }
        if (phaseTicksRemaining <= 0) {
            tcpServer.broadcast(SyncProtocol.returnToSingle());
            // Game is no longer active after this point
            gameActive = false;
            // Clean up stale reconnect-holdover sessions
            if (tcpServer != null) tcpServer.cleanupStaleSessions();
        }
    }

    // ========================================================================
    // TCP MESSAGE HANDLERS (called from ArenaClashTcpServer)
    // ========================================================================

    /**
     * Called when player kills a mob in singleplayer.
     * Card is already added to TcpSession by the TCP server.
     * This is called from the client-side event → TCP → server.
     */
    public void onMobKilled(ServerPlayerEntity player, EntityType<?> entityType) {
        // This is for MC server-side kills (during arena testing, etc.)
        if (phase != GamePhase.SURVIVAL) return;
        if (tcpServer == null) return;

        TcpSession session = tcpServer.getSession(player.getUuid());
        if (session == null) return;

        MobCardDefinition def = MobCardRegistry.getByEntityType(entityType);
        if (def == null) return;

        session.addCard(def.id());
        tcpServer.syncCards(session);
        session.send(SyncProtocol.serverMessage("§a+ " + def.displayName() + " card obtained!"));
    }

    public void onTcpReady(TcpSession session) {
        if (phase == GamePhase.PREPARATION) {
            readyPlayers.add(session.getPlayerUuid());
            tcpServer.broadcast(SyncProtocol.serverMessage(
                    "§e" + session.getPlayerName() + " is ready!"));
        }
    }

    public void handleTcpPlaceCard(TcpSession session, String cardIdStr, String laneIdStr, int slotIndex) {
        if (phase != GamePhase.PREPARATION) return;
        try {
            UUID cardId = UUID.fromString(cardIdStr);
            Lane.LaneId laneId = Lane.LaneId.valueOf(laneIdStr);
            TeamSide team = session.getTeam();

            // FIX 3: Mirror lane for PLAYER1 so "LEFT" from their perspective maps to RIGHT in world
            if (team == TeamSide.PLAYER1) {
                laneId = mirrorLane(laneId);
            }

            MobCard card = session.getCardInventory().getCard(cardId);
            if (card == null) {
                session.send(SyncProtocol.serverMessage("§cCard not found!"));
                return;
            }

            boolean success = arenaManager.placeMob(team, laneId, slotIndex, card);
            if (success) {
                session.getCardInventory().removeCard(cardId);
                session.send(SyncProtocol.serverMessage(
                        "§aPlaced " + card.getDefinition().displayName() + " on " + laneIdStr));
                tcpServer.syncCards(session);
                syncDeploymentSlots(session);
            } else {
                session.send(SyncProtocol.serverMessage("§cSlot is occupied!"));
            }
        } catch (Exception e) {
            session.send(SyncProtocol.serverMessage("§cInvalid card placement request"));
        }
    }

    public void handleTcpRemoveCard(TcpSession session, String laneIdStr, int slotIndex) {
        if (phase != GamePhase.PREPARATION) return;
        try {
            Lane.LaneId laneId = Lane.LaneId.valueOf(laneIdStr);
            TeamSide team = session.getTeam();

            // FIX 3: Mirror lane for PLAYER1
            if (team == TeamSide.PLAYER1) {
                laneId = mirrorLane(laneId);
            }

            MobCard card = arenaManager.removeMob(team, laneId, slotIndex);
            if (card != null) {
                session.getCardInventory().addCard(card);
                session.send(SyncProtocol.serverMessage(
                        "§eRemoved " + card.getDefinition().displayName()));
                tcpServer.syncCards(session);
                syncDeploymentSlots(session);
            }
        } catch (Exception e) {
            session.send(SyncProtocol.serverMessage("§cInvalid remove request"));
        }
    }

    /**
     * FIX 3: Mirror lane IDs for PLAYER2 so their perspective matches their UI.
     * PLAYER2 faces opposite direction, so their LEFT is world RIGHT.
     */
    private Lane.LaneId mirrorLane(Lane.LaneId laneId) {
        return switch (laneId) {
            case LEFT -> Lane.LaneId.RIGHT;
            case RIGHT -> Lane.LaneId.LEFT;
            case CENTER -> Lane.LaneId.CENTER;
        };
    }

    public void handleTcpBellRing(TcpSession session) {
        if (phase == GamePhase.PREPARATION) {
            readyPlayers.add(session.getPlayerUuid());
            tcpServer.broadcast(SyncProtocol.serverMessage(
                    "§e" + session.getPlayerName() + " is ready!"));
        } else if (phase == GamePhase.BATTLE) {
            arenaManager.orderRetreat(session.getTeam());
            session.send(SyncProtocol.serverMessage("§e⚐ Retreat ordered!"));
        }
    }

    // Also handle MC-connected player actions during prep/battle
    public void handlePlaceCard(ServerPlayerEntity player, UUID cardId, Lane.LaneId laneId, int slotIndex) {
        if (phase != GamePhase.PREPARATION) return;
        TcpSession session = tcpServer != null ? tcpServer.getSession(player.getUuid()) : null;
        if (session == null) return;
        handleTcpPlaceCard(session, cardId.toString(), laneId.name(), slotIndex);
    }

    public void handleRemoveCard(ServerPlayerEntity player, Lane.LaneId laneId, int slotIndex) {
        if (phase != GamePhase.PREPARATION) return;
        TcpSession session = tcpServer != null ? tcpServer.getSession(player.getUuid()) : null;
        if (session == null) return;
        handleTcpRemoveCard(session, laneId.name(), slotIndex);
    }

    public void handleBellRing(ServerPlayerEntity player) {
        TcpSession session = tcpServer != null ? tcpServer.getSession(player.getUuid()) : null;
        if (session == null) return;
        handleTcpBellRing(session);
    }

    // ========================================================================
    // SYNC
    // ========================================================================

    private void syncAllCards() {
        if (tcpServer == null) return;
        for (UUID uuid : playerOrder) {
            TcpSession session = tcpServer.getSession(uuid);
            if (session != null) tcpServer.syncCards(session);
        }
    }

    public void syncCards(ServerPlayerEntity player) {
        if (tcpServer == null) return;
        TcpSession session = tcpServer.getSession(player.getUuid());
        if (session != null) tcpServer.syncCards(session);

        // Also sync via MC networking if player is on server
        PlayerGameData data = getPlayerData(player.getUuid());
        if (data != null) {
            ServerPlayNetworking.send(player,
                    new NetworkHandler.CardInventorySync(data.getCardInventory().toNbt()));
        }
    }

    private void syncDeploymentSlots(TcpSession session) {
        // Build slot data as JSON and send via TCP
        NbtCompound slotsData = new NbtCompound();
        TeamSide team = session.getTeam();
        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            Lane lane = arenaManager.getLanes().get(laneId);
            if (lane == null) continue;
            NbtCompound laneNbt = new NbtCompound();
            var slots = lane.getDeploymentSlots(team);
            for (int i = 0; i < slots.size(); i++) {
                Lane.DeploymentSlot slot = slots.get(i);
                NbtCompound slotNbt = new NbtCompound();
                slotNbt.putBoolean("empty", slot.isEmpty());
                if (!slot.isEmpty()) {
                    slotNbt.put("card", slot.getPlacedCard().toNbt());
                }
                laneNbt.put("slot_" + i, slotNbt);
            }
            // FIX 3: Mirror lane names for PLAYER1 so their UI shows correctly
            Lane.LaneId displayLaneId = (team == TeamSide.PLAYER1) ? mirrorLane(laneId) : laneId;
            slotsData.put(displayLaneId.name(), laneNbt);
        }

        // Also send to MC-connected player
        ServerPlayerEntity player = getPlayer(session.getPlayerUuid());
        if (player != null) {
            ServerPlayNetworking.send(player, new NetworkHandler.DeploymentSlotSync(slotsData));
        }
    }

    // ========================================================================
    // MC SERVER EVENTS (for players connected to MC during prep/battle)
    // ========================================================================

    /**
     * Called when an MC player joins the server during prep/battle.
     * Teleport them to the right spot on the arena.
     * Fix 4: Added safety checks to prevent disconnect packet errors.
     */
    public void onPlayerJoinMc(ServerPlayerEntity player) {
        if (!gameActive) return;
        TeamSide team = playerTeams.get(player.getUuid());
        if (team == null) {
            player.sendMessage(Text.literal("§c[ArenaClash] You are not part of the current game."));
            return;
        }

        if (phase == GamePhase.PREPARATION || phase == GamePhase.BATTLE || phase == GamePhase.GAME_OVER) {
            server.execute(() -> {
                try {
                    if (player.isDisconnected()) return;

                    // FIX 10: Restore player inventory from singleplayer survival
                    TcpSession session = tcpServer.getSession(player.getUuid());
                    if (session != null) {
                        String savedInv = session.getSavedInventoryJson();
                        if (savedInv != null && !savedInv.isEmpty()) {
                            try {
                                net.minecraft.nbt.NbtCompound invNbt = net.minecraft.nbt.StringNbtReader.parse(savedInv);
                                net.minecraft.nbt.NbtList items = invNbt.getList("Items", 10);
                                player.getInventory().clear();
                                player.getInventory().readNbt(items);
                                player.currentScreenHandler.sendContentUpdates();
                            } catch (Exception e) {
                                player.getInventory().clear();
                                player.currentScreenHandler.sendContentUpdates();
                            }
                        } else {
                            // No saved inventory - clear
                            player.getInventory().clear();
                            player.currentScreenHandler.sendContentUpdates();
                        }
                    } else {
                        player.getInventory().clear();
                        player.currentScreenHandler.sendContentUpdates();
                    }

                    worldManager.teleportToArena(player, team);

                    // Allow flying on the arena so players can spectate the battle
                    player.getAbilities().allowFlying = true;
                    player.sendAbilitiesUpdate();

                    // Sync cards via MC networking too
                    if (session != null) {
                        ServerPlayNetworking.send(player,
                                new NetworkHandler.CardInventorySync(session.getCardInventory().toNbt()));
                        session.setConnectedToMc(true);
                    }
                } catch (Exception e) {
                    if (server != null) {
                        server.execute(() -> {
                            try {
                                if (!player.isDisconnected()) {
                                    player.sendMessage(Text.literal("§c[ArenaClash] Error during arena setup. Try reconnecting."));
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                }
            });
        }
    }

    // ========================================================================
    // RESET
    // ========================================================================

    public String resetGame() {
        if (worldManager != null && worldManager.getArenaWorld() != null) {
            ArenaBuilder.clearArena(worldManager.getArenaWorld());
        }
        arenaManager.fullReset();
        if (worldManager != null) {
            worldManager.deleteAllWorlds();
        }
        playerTeams.clear();
        playerOrder.clear();
        readyPlayers.clear();
        gameActive = false;
        phase = GamePhase.LOBBY;
        currentRound = 0;
        battleEndGraceTicks = -1;
        gamePaused = false;
        waitingForWorlds = false;
        worldReadyPlayers.clear();
        for (TeamSide t : TeamSide.values()) {
            cumulativeThroneDamage.put(t, 0.0);
            cumulativeTowersDestroyed.put(t, 0);
            cumulativeTowerDamage.put(t, 0.0);
            prevThroneDamageSnapshot.put(t, 0.0);
            prevTowerDamageSnapshot.put(t, 0.0);
            prevTowersDestroyedSnapshot.put(t, 0);
        }

        if (tcpServer != null) {
            tcpServer.cleanupStaleSessions();
            tcpServer.broadcast(SyncProtocol.serverMessage("§cGame reset!"));
            tcpServer.broadcast(SyncProtocol.returnToSingle());
            tcpServer.broadcastLobbyUpdate();
        }

        return "Game reset!";
    }

    // ========================================================================
    // UTILITY
    // ========================================================================

    private ServerPlayerEntity getPlayer(UUID id) {
        return server != null ? server.getPlayerManager().getPlayer(id) : null;
    }

    private void broadcastMc(String message, Formatting color) {
        if (server == null) return;
        server.getPlayerManager().broadcast(Text.literal(message).formatted(color), false);
    }

    /**
     * Get PlayerGameData wrapper for backward compatibility with events.
     * Creates a temporary wrapper backed by the TCP session's card inventory.
     */
    public PlayerGameData getPlayerData(UUID playerId) {
        TeamSide team = playerTeams.get(playerId);
        if (team == null) return null;

        TcpSession session = tcpServer != null ? tcpServer.getSession(playerId) : null;
        CardInventory inventory = session != null ? session.getCardInventory() : new CardInventory();

        // Return a wrapper
        PlayerGameData data = new PlayerGameData(playerId, team);
        data.setCardInventory(inventory);
        return data;
    }

    // === Getters ===
    public GamePhase getPhase() { return phase; }
    public int getCurrentRound() { return currentRound; }
    public int getPhaseTicksRemaining() { return phaseTicksRemaining; }
    public boolean isGameActive() { return gameActive; }
    public WorldManager getWorldManager() { return worldManager; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public Map<UUID, TeamSide> getPlayerTeams() { return playerTeams; }
    public Map<UUID, PlayerGameData> getAllPlayerData() {
        Map<UUID, PlayerGameData> result = new HashMap<>();
        for (UUID uuid : playerOrder) {
            PlayerGameData data = getPlayerData(uuid);
            if (data != null) result.put(uuid, data);
        }
        return result;
    }

    public MinecraftServer getServer() { return server; }
    public long getCurrentGameSeed() { return currentGameSeed; }
}
