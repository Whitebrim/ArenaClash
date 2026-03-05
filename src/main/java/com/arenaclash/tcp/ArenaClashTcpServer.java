package com.arenaclash.tcp;

import com.arenaclash.ArenaClash;
import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.game.GameManager;
import com.arenaclash.game.TeamSide;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP server that runs on a separate port alongside the MC server.
 * Manages persistent connections for Arena Clash sessions.
 *
 * Flow:
 * 1. Client connects via TCP → AUTH → session created
 * 2. Server sends WELCOME + LOBBY_UPDATE
 * 3. When game starts (via /ac start): PHASE_CHANGE(SURVIVAL) + timer
 * 4. During survival: client sends CARD_OBTAINED, server tracks cards
 * 5. Phase transition: CONNECT_TO_MC → client joins MC server for arena
 * 6. After battle: RETURN_TO_SINGLE → client goes back to singleplayer
 */
public class ArenaClashTcpServer {
    private final int port;
    private final int mcPort;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    private final Map<String, TcpSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, TcpSession> sessionsByUuid = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCounter = new AtomicInteger(0);

    public ArenaClashTcpServer(int tcpPort, int mcPort) {
        this.port = tcpPort;
        this.mcPort = mcPort;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;

        acceptThread = new Thread(() -> {
            ArenaClash.LOGGER.info("[ArenaClash TCP] Listening on port {}", port);
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setKeepAlive(true);
                    handleNewConnection(clientSocket);
                } catch (IOException e) {
                    if (running) {
                        ArenaClash.LOGGER.error("[ArenaClash TCP] Accept error", e);
                    }
                }
            }
        }, "ArenaClash-TCP-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        for (TcpSession session : sessions.values()) {
            session.disconnect();
        }
        sessions.clear();
        sessionsByUuid.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    private void handleNewConnection(Socket socket) {
        Thread clientThread = new Thread(() -> {
            TcpSession session = null;
            try {
                InputStream in = socket.getInputStream();

                // Wait for AUTH message
                JsonObject authMsg = SyncProtocol.readMessage(in);
                if (authMsg == null || !SyncProtocol.C2S_AUTH.equals(SyncProtocol.getType(authMsg))) {
                    socket.close();
                    return;
                }

                String playerName = authMsg.get("playerName").getAsString();
                UUID playerUuid = UUID.fromString(authMsg.get("uuid").getAsString());

                // Check for existing session (reconnection)
                TcpSession existingSession = sessionsByUuid.get(playerUuid);
                CardInventory reconnectCards = null;
                TeamSide reconnectTeam = null;
                String reconnectInventoryJson = null;
                if (existingSession != null) {
                    ArenaClash.LOGGER.info("[ArenaClash TCP] Player {} reconnecting (replacing old session {})",
                            playerName, existingSession.getSessionId());
                    reconnectCards = existingSession.getCardInventory();
                    reconnectTeam = existingSession.getTeam();
                    reconnectInventoryJson = existingSession.getSavedInventoryJson();
                    sessions.remove(existingSession.getSessionId());
                    existingSession.disconnect();
                }

                // Fallback: look up team from GameManager if old session had no team
                // (e.g. session was cleaned up before reconnect)
                GameManager gm = GameManager.getInstance();
                if (reconnectTeam == null && gm.isGameActive()) {
                    TeamSide teamFromGm = gm.getPlayerTeams().get(playerUuid);
                    if (teamFromGm != null) {
                        reconnectTeam = teamFromGm;
                        ArenaClash.LOGGER.info("[ArenaClash TCP] Recovered team {} for {} from GameManager",
                                reconnectTeam, playerName);
                    }
                }

                String sessionId = "session_" + sessionCounter.incrementAndGet();

                session = new TcpSession(sessionId, playerName, playerUuid, socket.getOutputStream());

                // Restore state from previous session if reconnecting
                if (reconnectCards != null) {
                    session.setCardInventory(reconnectCards);
                }
                if (reconnectTeam != null) {
                    session.setTeam(reconnectTeam);
                }
                if (reconnectInventoryJson != null) {
                    session.setSavedInventoryJson(reconnectInventoryJson);
                }

                sessions.put(sessionId, session);
                sessionsByUuid.put(playerUuid, session);

                ArenaClash.LOGGER.info("[ArenaClash TCP] Player {} connected (session {})", playerName, sessionId);

                // Send welcome
                session.send(SyncProtocol.welcome(sessionId, port, mcPort));

                // Send reconnect state if game is active
                GameManager gm2 = GameManager.getInstance();
                if (gm2.isGameActive() && reconnectTeam != null) {
                    String cardsSnbt = session.getCardInventory().toNbt().toString();
                    session.send(SyncProtocol.reconnectState(
                            gm2.getPhase().name(),
                            gm2.getCurrentRound(),
                            gm2.getPhaseTicksRemaining(),
                            cardsSnbt,
                            gm2.getCurrentGameSeed(),
                            gm2.getGameSessionId()
                    ));
                    ArenaClash.LOGGER.info("[ArenaClash TCP] Sent reconnect state to {}", playerName);
                }

                // Notify lobby update
                broadcastLobbyUpdate();

                // Auto-start game when 2 players are connected
                if (hasTwoPlayers() && !GameManager.getInstance().isGameActive()) {
                    ArenaClash.LOGGER.info("[ArenaClash TCP] 2 players connected, auto-starting game!");
                    // Schedule on server main thread
                    net.minecraft.server.MinecraftServer server = GameManager.getInstance().getServer();
                    if (server != null) {
                        server.execute(() -> {
                            Component result = GameManager.getInstance().startGame();
                            ArenaClash.LOGGER.info("[ArenaClash TCP] Auto-start result: {}", result.getString());
                        });
                    }
                }

                // Read loop
                while (session.isAlive() && running) {
                    JsonObject msg = SyncProtocol.readMessage(in);
                    if (msg == null) break;
                    handleClientMessage(session, msg);
                }
            } catch (IOException e) {
                // Connection closed
            } finally {
                if (session != null) {
                    ArenaClash.LOGGER.info("[ArenaClash TCP] Player {} disconnected", session.getPlayerName());
                    sessions.remove(session.getSessionId());
                    // During an active game, keep the session in sessionsByUuid so that
                    // reconnection can find the old cards/team.  Only the socket is dead.
                    if (!GameManager.getInstance().isGameActive()) {
                        sessionsByUuid.remove(session.getPlayerUuid());
                    } else {
                        ArenaClash.LOGGER.info("[ArenaClash TCP] Keeping session data for {} (game active, allows reconnect)",
                                session.getPlayerName());
                    }
                    session.disconnect();
                    broadcastLobbyUpdate();
                }
                try { socket.close(); } catch (IOException ignored) {}
            }
        }, "ArenaClash-TCP-Client-" + socket.getRemoteSocketAddress());
        clientThread.setDaemon(true);
        clientThread.start();
    }

    private void handleClientMessage(TcpSession session, JsonObject msg) {
        String type = SyncProtocol.getType(msg);
        GameManager gm = GameManager.getInstance();

        switch (type) {
            case SyncProtocol.C2S_CARD_OBTAINED -> {
                String mobId = msg.get("mobId").getAsString();
                boolean isBonus = msg.has("bonus") && msg.get("bonus").getAsBoolean();
                var def = MobCardRegistry.getById(mobId);
                if (def != null) {
                    session.addCard(mobId);
                    int count = session.getCardInventory().getCardsByMobId(mobId).size();

                    session.send(SyncProtocol.translatableMessage(
                            "arenaclash.msg.card_obtained_count",
                            def.translationKey(), String.valueOf(count)));
                    syncCards(session);

                    // Notify opponent about this card obtained
                    for (TcpSession other : sessions.values()) {
                        if (!other.getSessionId().equals(session.getSessionId())) {
                            other.send(SyncProtocol.opponentCardObtained(
                                    session.getPlayerName(), def.translationKey(), count, isBonus));
                        }
                    }
                }
            }
            case SyncProtocol.C2S_READY -> {
                session.setReady(true);
                broadcast(SyncProtocol.translatableMessage(
                        "arenaclash.tcp.player_ready", session.getPlayerName()));
                gm.onTcpReady(session);
            }
            case SyncProtocol.C2S_PLACE_CARD -> {
                String cardId = msg.get("cardId").getAsString();
                String laneId = msg.get("laneId").getAsString();
                int slotIndex = msg.get("slotIndex").getAsInt();
                gm.handleTcpPlaceCard(session, cardId, laneId, slotIndex);
            }
            case SyncProtocol.C2S_REMOVE_CARD -> {
                String laneId = msg.get("laneId").getAsString();
                int slotIndex = msg.get("slotIndex").getAsInt();
                gm.handleTcpRemoveCard(session, laneId, slotIndex);
            }
            case SyncProtocol.C2S_BELL_RING -> {
                gm.handleTcpBellRing(session);
            }
            case SyncProtocol.C2S_INVENTORY_SYNC -> {
                // Store player's survival inventory for use on arena
                String itemsJson = msg.get("items").getAsString();
                session.setSavedInventoryJson(itemsJson);
                gm.onInventorySync(session, itemsJson);
            }
            case SyncProtocol.C2S_CHAT -> {
                // Relay chat to all other players
                String chatMessage = msg.get("message").getAsString();

                // Handle /ac commands - execute on MC server
                if (chatMessage.startsWith("/ac ") || chatMessage.equals("/ac")) {
                    net.minecraft.server.MinecraftServer server = gm.getServer();
                    if (server != null) {
                        final String cmd = chatMessage.substring(1); // Remove leading "/"
                        server.execute(() -> {
                            try {
                                // Create a command source that relays feedback to the TCP session
                                net.minecraft.commands.CommandSource tcpOutput = new net.minecraft.commands.CommandSource() {
                                    @Override
                                    public void sendSystemMessage(net.minecraft.network.chat.Component message) {
                                        session.send(SyncProtocol.serverMessage(message.getString()));
                                    }
                                    @Override
                                    public boolean acceptsSuccess() { return true; }
                                    @Override
                                    public boolean acceptsFailure() { return true; }
                                    @Override
                                    public boolean shouldInformAdmins() { return false; }
                                };
                                net.minecraft.commands.CommandSourceStack source =
                                        new net.minecraft.commands.CommandSourceStack(
                                                tcpOutput,
                                                server.createCommandSourceStack().getPosition(),
                                                server.createCommandSourceStack().getRotation(),
                                                server.overworld(),
                                                net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER, // permission level
                                                session.getPlayerName(),
                                                net.minecraft.network.chat.Component.literal(session.getPlayerName()),
                                                server,
                                                null
                                        );
                                server.getCommands().performPrefixedCommand(source, "/" + cmd);
                            } catch (Exception e) {
                                session.send(SyncProtocol.translatableMessage("arenaclash.tcp.command_error", e.getMessage()));
                            }
                        });
                    } else {
                        session.send(SyncProtocol.translatableMessage("arenaclash.tcp.server_unavailable"));
                    }
                    return;
                }

                for (TcpSession other : sessions.values()) {
                    if (!other.getSessionId().equals(session.getSessionId())) {
                        other.send(SyncProtocol.chatRelay(session.getPlayerName(), chatMessage));
                    }
                }
            }
            case "WORLD_READY" -> {
                // Player's singleplayer world is created and ready
                net.minecraft.server.MinecraftServer server = gm.getServer();
                if (server != null) {
                    server.execute(() -> gm.onPlayerWorldReady(session.getPlayerUuid()));
                }
            }
            case SyncProtocol.C2S_PAUSE_STATE -> {
                boolean paused = msg.get("paused").getAsBoolean();
                session.setPaused(paused);
                // Check if both players are paused or at least one unpaused
                net.minecraft.server.MinecraftServer srv = gm.getServer();
                if (srv != null) {
                    srv.execute(() -> gm.updatePauseFromClients());
                }
            }
            case SyncProtocol.C2S_MERGE_CARDS -> {
                String cardId1 = msg.get("cardId1").getAsString();
                String cardId2 = msg.get("cardId2").getAsString();
                handleMergeCards(session, cardId1, cardId2);
            }
            case SyncProtocol.C2S_PLAYER_STATE -> {
                // Relay player position/equipment to opponent
                double x = msg.get("x").getAsDouble();
                double y = msg.get("y").getAsDouble();
                double z = msg.get("z").getAsDouble();
                float yaw = msg.get("yaw").getAsFloat();
                float pitch = msg.get("pitch").getAsFloat();
                String dimension = msg.has("dim") ? msg.get("dim").getAsString() : "minecraft:overworld";
                String equipment = msg.has("equipment") ? msg.get("equipment").getAsString() : null;

                // Cache equipment so we can always send it to opponent
                // (client only sends equipment when it changes, but opponent needs it on reconnect)
                if (equipment != null) {
                    session.setLastEquipmentSnbt(equipment);
                }
                String equipmentToRelay = session.getLastEquipmentSnbt();

                for (TcpSession other : sessions.values()) {
                    if (!other.getSessionId().equals(session.getSessionId())) {
                        other.send(SyncProtocol.opponentState(
                                session.getPlayerName(), x, y, z, yaw, pitch, dimension, equipmentToRelay));
                    }
                }
            }
            case SyncProtocol.C2S_BROADCAST -> {
                // System broadcast (achievements, deaths) — relay to opponent
                String broadcastText = msg.get("message").getAsString();
                for (TcpSession other : sessions.values()) {
                    if (!other.getSessionId().equals(session.getSessionId())) {
                        other.send(SyncProtocol.broadcastRelay(session.getPlayerName(), broadcastText));
                    }
                }
            }
        }
    }

    // === Broadcasting ===

    public void broadcast(JsonObject msg) {
        for (TcpSession session : sessions.values()) {
            session.send(msg);
        }
    }

    public void broadcastLobbyUpdate() {
        broadcast(SyncProtocol.lobbyUpdate(sessions.size(), 2,
                sessions.size() >= 2 ? "Ready to start!" : "Waiting for players..."));
    }

    public void syncCards(TcpSession session) {
        String cardsSnbt = session.getCardInventory().toNbt().toString();
        session.send(SyncProtocol.cardSync(cardsSnbt));
    }

    /**
     * Handle card merge request: combine two identical cards (same mobId, same level)
     * into one card of the next level.
     */
    private void handleMergeCards(TcpSession session, String cardId1Str, String cardId2Str) {
        try {
            java.util.UUID id1 = java.util.UUID.fromString(cardId1Str);
            java.util.UUID id2 = java.util.UUID.fromString(cardId2Str);

            if (id1.equals(id2)) {
                session.send(SyncProtocol.translatableMessage("arenaclash.upgrade.fail.same_card"));
                return;
            }

            com.arenaclash.card.CardInventory inv = session.getCardInventory();
            com.arenaclash.card.MobCard card1 = inv.getCard(id1);
            com.arenaclash.card.MobCard card2 = inv.getCard(id2);

            if (card1 == null || card2 == null) {
                session.send(SyncProtocol.translatableMessage("arenaclash.upgrade.fail.not_found"));
                return;
            }

            if (!card1.getMobId().equals(card2.getMobId())) {
                session.send(SyncProtocol.translatableMessage("arenaclash.upgrade.fail.different_type"));
                return;
            }

            if (com.arenaclash.card.MobCardRegistry.isUpgradeLocked(card1.getMobId())) {
                session.send(SyncProtocol.translatableMessage("arenaclash.upgrade.fail.upgrade_locked"));
                return;
            }

            if (card1.getLevel() != card2.getLevel()) {
                session.send(SyncProtocol.translatableMessage("arenaclash.upgrade.fail.different_level"));
                return;
            }

            // Perform merge: remove both, add new card at level + 1
            String mobId = card1.getMobId();
            int newLevel = card1.getLevel() + 1;

            inv.removeCard(id1);
            inv.removeCard(id2);

            com.arenaclash.card.MobCard merged = new com.arenaclash.card.MobCard(mobId);
            merged.setLevel(newLevel);
            inv.addCard(merged);

            // Sync updated inventory
            syncCards(session);

            // Send success message
            var def = com.arenaclash.card.MobCardRegistry.getById(mobId);
            String mobTranslationKey = def != null ? def.translationKey() : mobId;
            session.send(SyncProtocol.translatableMessage(
                    "arenaclash.upgrade.success", mobTranslationKey, String.valueOf(newLevel)));

            ArenaClash.LOGGER.info("[ArenaClash TCP] Player {} merged 2x {} Lv.{} → Lv.{}",
                    session.getPlayerName(), mobId, newLevel - 1, newLevel);

        } catch (Exception e) {
            session.send(SyncProtocol.translatableMessage("arenaclash.upgrade.fail.error"));
            ArenaClash.LOGGER.error("Error merging cards for {}: {}", session.getPlayerName(), e.getMessage());
        }
    }

    // === Getters ===

    public Map<String, TcpSession> getSessions() { return sessions; }
    public Map<UUID, TcpSession> getSessionsByUuid() { return sessionsByUuid; }

    public TcpSession getSession(UUID uuid) {
        return sessionsByUuid.get(uuid);
    }

    public int getConnectedCount() {
        return sessions.size();
    }

    public boolean hasTwoPlayers() {
        return sessions.size() >= 2;
    }

    /**
     * Remove dead sessions from sessionsByUuid that are no longer in the active sessions map.
     * Called when game ends/resets to clean up reconnection holdovers.
     */
    public void cleanupStaleSessions() {
        sessionsByUuid.entrySet().removeIf(entry -> {
            TcpSession s = entry.getValue();
            if (!s.isAlive() && !sessions.containsKey(s.getSessionId())) {
                ArenaClash.LOGGER.info("[ArenaClash TCP] Cleaned up stale session for {}", s.getPlayerName());
                return true;
            }
            return false;
        });
    }
}
