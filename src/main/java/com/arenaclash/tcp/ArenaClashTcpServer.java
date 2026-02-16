package com.arenaclash.tcp;

import com.arenaclash.ArenaClash;
import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.game.GameManager;
import com.arenaclash.game.TeamSide;
import com.google.gson.JsonObject;

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

                // Fix 7: Check for existing session (reconnection)
                TcpSession existingSession = sessionsByUuid.get(playerUuid);
                CardInventory reconnectCards = null;
                TeamSide reconnectTeam = null;
                if (existingSession != null) {
                    ArenaClash.LOGGER.info("[ArenaClash TCP] Player {} reconnecting (replacing old session {})",
                            playerName, existingSession.getSessionId());
                    reconnectCards = existingSession.getCardInventory();
                    reconnectTeam = existingSession.getTeam();
                    sessions.remove(existingSession.getSessionId());
                    existingSession.disconnect();
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

                sessions.put(sessionId, session);
                sessionsByUuid.put(playerUuid, session);

                ArenaClash.LOGGER.info("[ArenaClash TCP] Player {} connected (session {})", playerName, sessionId);

                // Send welcome
                session.send(SyncProtocol.welcome(sessionId, port, mcPort));

                // Fix 7: Send reconnect state if game is active
                GameManager gm2 = GameManager.getInstance();
                if (gm2.isGameActive() && reconnectTeam != null) {
                    String cardsSnbt = session.getCardInventory().toNbt().toString();
                    session.send(SyncProtocol.reconnectState(
                            gm2.getPhase().name(),
                            gm2.getCurrentRound(),
                            gm2.getPhaseTicksRemaining(),
                            cardsSnbt,
                            gm2.getCurrentGameSeed()
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
                            String result = GameManager.getInstance().startGame();
                            ArenaClash.LOGGER.info("[ArenaClash TCP] Auto-start result: {}", result);
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
                    sessionsByUuid.remove(session.getPlayerUuid());
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
                if (MobCardRegistry.getById(mobId) != null) {
                    session.addCard(mobId);
                    // Fix 8: Don't send ack message here - client already shows it locally
                    // Sync updated card list
                    syncCards(session);
                }
            }
            case SyncProtocol.C2S_READY -> {
                session.setReady(true);
                broadcast(SyncProtocol.serverMessage(
                        "§e" + session.getPlayerName() + " is ready!"));
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
                // FIX 10: Store player's survival inventory for use on arena
                String itemsJson = msg.get("items").getAsString();
                session.setSavedInventoryJson(itemsJson);
                gm.onInventorySync(session, itemsJson);
            }
            case SyncProtocol.C2S_CHAT -> {
                // Fix 6: Relay chat to all other players
                String chatMessage = msg.get("message").getAsString();
                for (TcpSession other : sessions.values()) {
                    if (!other.getSessionId().equals(session.getSessionId())) {
                        other.send(SyncProtocol.chatRelay(session.getPlayerName(), chatMessage));
                    }
                }
            }
            case "WORLD_READY" -> {
                // FIX 7: Player's singleplayer world is created and ready
                net.minecraft.server.MinecraftServer server = gm.getServer();
                if (server != null) {
                    server.execute(() -> gm.onPlayerWorldReady(session.getPlayerUuid()));
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
}
