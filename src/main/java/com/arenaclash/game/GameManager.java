package com.arenaclash.game;

import com.arenaclash.arena.ArenaDefinition;
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

    // Cumulative stats across rounds
    private final Map<TeamSide, Double> cumulativeThroneDamage = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Integer> cumulativeTowersDestroyed = new EnumMap<>(TeamSide.class);
    private final Map<TeamSide, Double> cumulativeTowerDamage = new EnumMap<>(TeamSide.class);

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

        // Initialize arena world using ArenaDefinition
        worldManager.createArenaWorld();
        ServerWorld arenaWorld = worldManager.getArenaWorld();

        // Load arena definition from JSON, or create default
        ArenaDefinition arenaDef = null;
        try {
            java.nio.file.Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("arenaclash");
            java.nio.file.Path defFile = configDir.resolve("arena_definition.json");
            if (java.nio.file.Files.exists(defFile)) {
                arenaDef = ArenaDefinition.load(defFile);
                com.arenaclash.ArenaClash.LOGGER.info("Loaded arena definition from JSON");
            }
        } catch (Exception e) {
            com.arenaclash.ArenaClash.LOGGER.warn("Failed to load arena_definition.json, using defaults", e);
        }

        if (arenaDef == null) {
            // Fallback: create default straight-lane arena
            arenaDef = ArenaDefinition.createDefault(
                    GameConfig.get().arenaCenterX,
                    GameConfig.get().arenaCenterZ,
                    GameConfig.get().arenaY,
                    GameConfig.get().arenaLaneLength,
                    12 // lane separation (default)
            );
            com.arenaclash.ArenaClash.LOGGER.info("Using default arena definition");
        }

        // Create ArenaManager with the loaded definition
        this.arenaManager = new ArenaManager(arenaWorld, arenaDef);
        if (arenaWorld != null) {
            arenaWorld.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_SPAWNING).set(false, server);
        }

        // Reset stats
        for (TeamSide t : TeamSide.values()) {
            cumulativeThroneDamage.put(t, 0.0);
            cumulativeTowersDestroyed.put(t, 0);
            cumulativeTowerDamage.put(t, 0.0);
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

        // Accumulate damage stats
        for (TeamSide team : TeamSide.values()) {
            TeamSide opponent = team.opponent();
            ArenaStructure throne = arenaManager.getThrone(opponent);
            if (throne != null) {
                double dmg = throne.getMaxHp() - throne.getCurrentHp();
                cumulativeThroneDamage.merge(team, dmg, Double::sum);
            }
            for (ArenaStructure tower : arenaManager.getTowers(opponent)) {
                if (tower.isDestroyed()) cumulativeTowersDestroyed.merge(team, 1, Integer::sum);
                double dmg = tower.getMaxHp() - tower.getCurrentHp();
                cumulativeTowerDamage.merge(team, dmg, Double::sum);
            }
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
        gameActive = false;

        String winnerName = "Draw";
        if (winner != null && playerOrder.size() > winner.ordinal()) {
            UUID winnerUuid = playerOrder.get(winner.ordinal());
            TcpSession session = tcpServer.getSession(winnerUuid);
            if (session != null) winnerName = session.getPlayerName();
        }

        tcpServer.broadcast(SyncProtocol.gameResult(winnerName, ""));
        tcpServer.broadcast(SyncProtocol.serverMessage("§6§l=== GAME OVER === Winner: " + winnerName));
        tcpServer.broadcast(SyncProtocol.returnToSingle());
    }

    private TeamSide determineWinner() {
        double p1Score = cumulativeThroneDamage.getOrDefault(TeamSide.PLAYER1, 0.0) * 2
                + cumulativeTowersDestroyed.getOrDefault(TeamSide.PLAYER1, 0) * 50
                + cumulativeTowerDamage.getOrDefault(TeamSide.PLAYER1, 0.0);
        double p2Score = cumulativeThroneDamage.getOrDefault(TeamSide.PLAYER2, 0.0) * 2
                + cumulativeTowersDestroyed.getOrDefault(TeamSide.PLAYER2, 0) * 50
                + cumulativeTowerDamage.getOrDefault(TeamSide.PLAYER2, 0.0);
        if (p1Score > p2Score) return TeamSide.PLAYER1;
        if (p2Score > p1Score) return TeamSide.PLAYER2;
        return null;
    }

    // ========================================================================
    // TICK
    // ========================================================================

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

        // Keep structure HP updated during preparation (every 2 seconds)
        // HP bars are now rendered client-side via HealthBarRenderer
        // Structure state is maintained in ArenaStructure instances

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

            MobCard card = session.getCardInventory().getCard(cardId);
            if (card == null) {
                session.send(SyncProtocol.serverMessage("§cCard not found!"));
                return;
            }

            boolean success = arenaManager.placeMob(team, laneId, slotIndex, card);
            if (success) {
                session.getCardInventory().removeCard(cardId);
                session.send(SyncProtocol.serverMessage(
                        "§aPlaced " + card.getDefinition().displayName() + " on " + laneId));
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
            MobCard card = arenaManager.removeMob(session.getTeam(), laneId, slotIndex);
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
        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            Lane lane = arenaManager.getLane(laneId);
            if (lane == null) continue;
            NbtCompound laneNbt = new NbtCompound();
            var slots = lane.getDeploymentSlots(session.getTeam());
            for (int i = 0; i < slots.size(); i++) {
                NbtCompound slotNbt = new NbtCompound();
                // Check if a mob has been deployed at this slot by checking ArenaMobs
                boolean occupied = false;
                for (var mob : arenaManager.getMobs(session.getTeam())) {
                    if (mob.getLane().getId() == laneId) {
                        // Simplified: any mob on this lane means a slot is filled
                        occupied = true;
                        break;
                    }
                }
                slotNbt.putBoolean("empty", !occupied);
                laneNbt.put("slot_" + i, slotNbt);
            }
            slotsData.put(laneId.name(), laneNbt);
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
            // Player is not part of the game - don't kick them during login
            // as that causes the disconnect packet error. Just let them be.
            player.sendMessage(Text.literal("§c[ArenaClash] You are not part of the current game."));
            return;
        }

        if (phase == GamePhase.PREPARATION || phase == GamePhase.BATTLE) {
            // Delay teleport slightly to ensure connection is fully established (Fix 4)
            server.execute(() -> {
                try {
                    if (player.isDisconnected()) return;

                    // Clear their MC inventory (they keep items in singleplayer)
                    player.getInventory().clear();
                    player.currentScreenHandler.sendContentUpdates();

                    worldManager.teleportToArena(player, team);

                    // Allow flying on the arena so players can spectate the battle
                    player.getAbilities().allowFlying = true;
                    player.sendAbilitiesUpdate();

                    // Sync cards via MC networking too
                    TcpSession session = tcpServer.getSession(player.getUuid());
                    if (session != null) {
                        ServerPlayNetworking.send(player,
                                new NetworkHandler.CardInventorySync(session.getCardInventory().toNbt()));
                        session.setConnectedToMc(true);
                    }
                } catch (Exception e) {
                    // Fix 4: Catch any errors during player setup to prevent crash
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
        // In template-based system, just clean up entities and delete the world
        if (arenaManager != null) {
            arenaManager.fullReset();
        }
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
        for (TeamSide t : TeamSide.values()) {
            cumulativeThroneDamage.put(t, 0.0);
            cumulativeTowersDestroyed.put(t, 0);
            cumulativeTowerDamage.put(t, 0.0);
        }

        if (tcpServer != null) {
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

    public void broadcastMc(String message, Formatting color) {
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
