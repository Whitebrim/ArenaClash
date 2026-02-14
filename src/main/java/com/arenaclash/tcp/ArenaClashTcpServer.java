package com.arenaclash.tcp;

import com.arenaclash.ArenaClash;
import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.game.GameManager;
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
                String sessionId = "session_" + sessionCounter.incrementAndGet();

                session = new TcpSession(sessionId, playerName, playerUuid, socket.getOutputStream());
                sessions.put(sessionId, session);
                sessionsByUuid.put(playerUuid, session);

                ArenaClash.LOGGER.info("[ArenaClash TCP] Player {} connected (session {})", playerName, sessionId);

                // Send welcome
                session.send(SyncProtocol.welcome(sessionId, port, mcPort));

                // Notify lobby update
                broadcastLobbyUpdate();

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
                    // Acknowledge
                    var def = MobCardRegistry.getById(mobId);
                    session.send(SyncProtocol.serverMessage(
                            "§a+ " + (def != null ? def.displayName() : mobId) + " card obtained!"));
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
                // TODO: sync player's survival inventory for use on arena
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
