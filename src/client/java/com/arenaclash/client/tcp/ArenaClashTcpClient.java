package com.arenaclash.client.tcp;

import com.arenaclash.client.ArenaClashClient;
import com.arenaclash.tcp.SyncProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-side persistent TCP connection to the Arena Clash server.
 * Runs alongside MC client (even in singleplayer).
 *
 * Messages from server are queued and processed on the MC client thread.
 */
public class ArenaClashTcpClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-TCP");

    private Socket socket;
    private OutputStream outputStream;
    private Thread readThread;
    private volatile boolean connected = false;
    private String serverHost;
    private int serverTcpPort;
    private int serverMcPort;
    private String sessionId;

    // Queue of messages to process on client thread
    private final ConcurrentLinkedQueue<JsonObject> incomingQueue = new ConcurrentLinkedQueue<>();

    // State
    public String currentPhase = "LOBBY";
    public int timerTicks = 0;
    public int currentRound = 0;
    public int lobbyPlayerCount = 0;
    public String lobbyStatus = "";

    /**
     * Connect to the Arena Clash server.
     */
    public boolean connect(String host, int port, String playerName, UUID playerUuid) {
        try {
            this.serverHost = host;
            this.serverTcpPort = port;

            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            outputStream = socket.getOutputStream();
            connected = true;

            // Send AUTH
            send(SyncProtocol.auth(playerName, playerUuid.toString()));

            // Start read thread
            readThread = new Thread(() -> readLoop(), "ArenaClash-TCP-Read");
            readThread.setDaemon(true);
            readThread.start();

            LOGGER.info("Connected to ArenaClash server at {}:{}", host, port);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to connect to {}:{}", host, port, e);
            return false;
        }
    }

    public void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        outputStream = null;
        sessionId = null;
        LOGGER.info("Disconnected from ArenaClash server");
    }

    public boolean isConnected() { return connected; }
    public String getServerHost() { return serverHost; }
    public int getServerMcPort() { return serverMcPort; }
    public String getSessionId() { return sessionId; }

    /**
     * Send a message to the server.
     */
    public synchronized void send(JsonObject msg) {
        if (!connected || outputStream == null) return;
        try {
            SyncProtocol.sendMessage(outputStream, msg);
        } catch (IOException e) {
            connected = false;
        }
    }

    /**
     * Process queued messages on the client tick.
     * Called from ArenaClashClient.onTick().
     */
    public void processIncoming() {
        JsonObject msg;
        while ((msg = incomingQueue.poll()) != null) {
            handleMessage(msg);
        }
    }

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            while (connected) {
                JsonObject msg = SyncProtocol.readMessage(in);
                if (msg == null) break;
                incomingQueue.add(msg);
            }
        } catch (IOException e) {
            // Connection closed
        } finally {
            connected = false;
        }
    }

    private void handleMessage(JsonObject msg) {
        String type = SyncProtocol.getType(msg);
        MinecraftClient client = MinecraftClient.getInstance();

        switch (type) {
            case SyncProtocol.S2C_WELCOME -> {
                sessionId = msg.get("sessionId").getAsString();
                serverMcPort = msg.get("mcPort").getAsInt();
                LOGGER.info("Received welcome, session: {}, MC port: {}", sessionId, serverMcPort);
            }

            case SyncProtocol.S2C_LOBBY_UPDATE -> {
                lobbyPlayerCount = msg.get("playerCount").getAsInt();
                lobbyStatus = msg.get("status").getAsString();
            }

            case SyncProtocol.S2C_PHASE_CHANGE -> {
                currentPhase = msg.get("phase").getAsString();
                currentRound = msg.get("round").getAsInt();
                timerTicks = msg.get("timerTicks").getAsInt();
                // Also update the static fields for HUD
                ArenaClashClient.currentPhase = currentPhase;
                ArenaClashClient.currentRound = currentRound;
                ArenaClashClient.timerTicks = timerTicks;

                // Update singleplayer bridge flag for integrated server mixins
                com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive = "SURVIVAL".equals(currentPhase);

                // Clear deployment slot data when a new round's PREPARATION starts
                if ("PREPARATION".equals(currentPhase)) {
                    ArenaClashClient.deploymentSlotData = null;

                    // Send inventory sync before transitioning to arena
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.player != null && mc.isInSingleplayer()) {
                        try {
                            net.minecraft.nbt.NbtCompound invNbt = new net.minecraft.nbt.NbtCompound();
                            net.minecraft.nbt.NbtList items = new net.minecraft.nbt.NbtList();
                            mc.player.getInventory().writeNbt(items);
                            invNbt.put("Items", items);
                            send(SyncProtocol.inventorySync(invNbt.toString()));
                        } catch (Exception e) {
                            LOGGER.error("Failed to sync inventory", e);
                        }
                    }
                }

                // Reset world ready flag for next survival phase
                if ("SURVIVAL".equals(currentPhase)) {
                    ArenaClashClient.worldReadySent = false;
                    ArenaClashClient.inventoryRestored = false;
                }
            }

            case SyncProtocol.S2C_TIMER_SYNC -> {
                timerTicks = msg.get("timerTicks").getAsInt();
                ArenaClashClient.timerTicks = timerTicks;
            }

            case SyncProtocol.S2C_CONNECT_TO_MC -> {
                // Server says: connect to MC server for arena phase
                String host = msg.get("host").getAsString();
                int port = msg.get("port").getAsInt();
                LOGGER.info("Server says connect to MC server at {}:{}", host, port);
                ArenaClashClient.scheduleConnectToMcServer(host, port);
            }

            case SyncProtocol.S2C_RETURN_TO_SINGLE -> {
                // Server says: go back to singleplayer
                LOGGER.info("Server says return to singleplayer");
                ArenaClashClient.scheduleReturnToSingleplayer();
            }

            case SyncProtocol.S2C_CARD_SYNC -> {
                String cardsJson = msg.get("cards").getAsString();
                ArenaClashClient.onCardSyncFromTcp(cardsJson);
            }

            case SyncProtocol.S2C_MESSAGE -> {
                String text = msg.get("text").getAsString();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(text));
                }
            }

            case SyncProtocol.S2C_GAME_RESULT -> {
                String winner = msg.get("winner").getAsString();
                String details = msg.get("details").getAsString();
                // Update phase to GAME_OVER for HUD
                ArenaClashClient.currentPhase = "GAME_OVER";
                currentPhase = "GAME_OVER";

                if (client.player != null) {
                    String playerName = client.getSession().getUsername();
                    boolean isWinner = winner.equals(playerName);
                    boolean isDraw = "Draw".equals(winner);

                    client.player.sendMessage(Text.literal("§6§l============================="));
                    if (isDraw) {
                        client.player.sendMessage(Text.literal("§e§l         DRAW!"));
                    } else if (isWinner) {
                        client.player.sendMessage(Text.literal("§a§l      VICTORY!"));
                    } else {
                        client.player.sendMessage(Text.literal("§c§l       DEFEAT"));
                    }
                    client.player.sendMessage(Text.literal("§eWinner: §f" + winner));
                    if (!details.isEmpty()) {
                        client.player.sendMessage(Text.literal("§7" + details));
                    }
                    client.player.sendMessage(Text.literal("§6§l============================="));
                }
            }

            case SyncProtocol.S2C_CHAT_RELAY -> {
                // Chat relay from another player
                String sender = msg.get("sender").getAsString();
                String chatMessage = msg.get("message").getAsString();
                ArenaClashClient.onChatRelayFromTcp(sender, chatMessage);
            }

            case SyncProtocol.S2C_GAME_SEED -> {
                // Receive game seed for singleplayer world creation
                long seed = msg.get("seed").getAsLong();
                LOGGER.info("Received game seed: {}", seed);
                // Only trigger world creation on round 1
                // Round 2+ world reload is handled by RETURN_TO_SINGLE
                if (currentRound <= 1) {
                    ArenaClashClient.scheduleWorldCreation(seed, 1);
                }
            }

            case SyncProtocol.S2C_RECONNECT_STATE -> {
                // Reconnection state restore
                String rPhase = msg.get("phase").getAsString();
                int rRound = msg.get("round").getAsInt();
                int rTimer = msg.get("timerTicks").getAsInt();
                String rCards = msg.has("cards") ? msg.get("cards").getAsString() : "";
                long rSeed = msg.has("seed") ? msg.get("seed").getAsLong() : 0;
                ArenaClashClient.onReconnectState(rPhase, rRound, rTimer, rCards, rSeed);
            }

            case SyncProtocol.S2C_INVENTORY_SYNC -> {
                // Bidirectional inventory sync: server sends player's arena inventory
                String itemsSnbt = msg.get("items").getAsString();
                ArenaClashClient.pendingInventoryRestore = itemsSnbt;
                LOGGER.info("Received inventory sync from server ({} chars)", itemsSnbt.length());
            }
        }
    }

    // === Convenience senders ===

    public void sendCardObtained(String mobId) {
        send(SyncProtocol.cardObtained(mobId));
    }

    public void sendReady() {
        send(SyncProtocol.makeMessage(SyncProtocol.C2S_READY));
    }

    public void sendBellRing() {
        send(SyncProtocol.makeMessage(SyncProtocol.C2S_BELL_RING));
    }

    public void sendPlaceCard(String cardId, String laneId, int slotIndex) {
        send(SyncProtocol.placeCard(cardId, laneId, slotIndex));
    }

    public void sendRemoveCard(String laneId, int slotIndex) {
        send(SyncProtocol.removeCard(laneId, slotIndex));
    }

    public void sendChat(String message) {
        send(SyncProtocol.chatMessage(message));
    }

    public void sendWorldReady() {
        send(SyncProtocol.makeMessage("WORLD_READY"));
    }

    public void sendInventorySync(String itemsSnbt) {
        send(SyncProtocol.inventorySync(itemsSnbt));
    }
}
