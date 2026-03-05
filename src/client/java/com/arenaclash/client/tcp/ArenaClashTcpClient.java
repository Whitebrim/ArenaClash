package com.arenaclash.client.tcp;

import com.arenaclash.client.ArenaClashClient;
import com.arenaclash.tcp.SyncProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
        Minecraft client = Minecraft.getInstance();

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
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null && mc.isLocalServer()) {
                        try {
                            var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                                    net.minecraft.util.ProblemReporter.DISCARDING,
                                    mc.player.registryAccess());
                            var items = output.list("Items", net.minecraft.world.ItemStackWithSlot.CODEC);
                            mc.player.getInventory().save(items);
                            net.minecraft.nbt.CompoundTag invNbt = output.buildResult();
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
                // Server says: go back to singleplayer (or title screen after game over)
                LOGGER.info("Server says return to singleplayer (phase: {})", currentPhase);
                if ("GAME_OVER".equals(currentPhase)) {
                    // After game over, go to title screen and delete the singleplayer world
                    ArenaClashClient.scheduleReturnToTitleScreen();
                } else {
                    ArenaClashClient.scheduleReturnToSingleplayer();
                }
            }

            case SyncProtocol.S2C_CARD_SYNC -> {
                String cardsJson = msg.get("cards").getAsString();
                ArenaClashClient.onCardSyncFromTcp(cardsJson);
            }

            case SyncProtocol.S2C_MESSAGE -> {
                if (client.player != null) {
                    if (msg.has("key")) {
                        String key = msg.get("key").getAsString();
                        if (msg.has("args")) {
                            com.google.gson.JsonArray argsArr = msg.getAsJsonArray("args");
                            Object[] args = new Object[argsArr.size()];
                            for (int i = 0; i < argsArr.size(); i++) {
                                String argStr = argsArr.get(i).getAsString();
                                // If arg is a translation key, wrap it in Component.translatable()
                                if (argStr.startsWith("arenaclash.mob.") || argStr.startsWith("arenaclash.category.") || argStr.startsWith("arenaclash.lane.")) {
                                    args[i] = Component.translatable(argStr);
                                } else {
                                    args[i] = argStr;
                                }
                            }
                            client.player.sendSystemMessage(Component.translatable(key, args));
                        } else {
                            client.player.sendSystemMessage(Component.translatable(key));
                        }
                    } else {
                        String text = msg.get("text").getAsString();
                        client.player.sendSystemMessage(Component.literal(text));
                    }
                }
            }

            case SyncProtocol.S2C_GAME_RESULT -> {
                String winner = msg.get("winner").getAsString();
                String details = msg.get("details").getAsString();
                // Update phase to GAME_OVER for HUD
                ArenaClashClient.currentPhase = "GAME_OVER";
                currentPhase = "GAME_OVER";

                if (client.player != null) {
                    String playerName = client.getUser().getName();
                    boolean isWinner = winner.equals(playerName);
                    boolean isDraw = "Draw".equals(winner);

                    client.player.sendSystemMessage(Component.translatable("arenaclash.msg.result.separator"));
                    if (isDraw) {
                        client.player.sendSystemMessage(Component.translatable("arenaclash.msg.result.draw"));
                    } else if (isWinner) {
                        client.player.sendSystemMessage(Component.translatable("arenaclash.msg.result.victory"));
                    } else {
                        client.player.sendSystemMessage(Component.translatable("arenaclash.msg.result.defeat"));
                    }
                    client.player.sendSystemMessage(Component.translatable("arenaclash.msg.result.winner", winner));
                    if (!details.isEmpty()) {
                        client.player.sendSystemMessage(Component.translatable("arenaclash.msg.result.details", details));
                    }
                    client.player.sendSystemMessage(Component.translatable("arenaclash.msg.result.separator"));
                }
            }

            case SyncProtocol.S2C_CHAT_RELAY -> {
                // Chat relay from another player — vanilla-style <Player> message format
                String sender = msg.get("sender").getAsString();
                String chatMessage = msg.get("message").getAsString();
                ArenaClashClient.onChatRelayFromTcp(sender, chatMessage);
            }

            case SyncProtocol.S2C_OPPONENT_STATE -> {
                // Opponent position/equipment for the ghost ArmorStand marker
                String opName = msg.get("name").getAsString();
                double ox = msg.get("x").getAsDouble();
                double oy = msg.get("y").getAsDouble();
                double oz = msg.get("z").getAsDouble();
                float oYaw = msg.get("yaw").getAsFloat();
                float oPitch = msg.get("pitch").getAsFloat();
                String oDim = msg.has("dim") ? msg.get("dim").getAsString() : "minecraft:overworld";
                String equipment = msg.has("equipment") ? msg.get("equipment").getAsString() : null;
                ArenaClashClient.onOpponentStateFromTcp(opName, ox, oy, oz, oYaw, oPitch, oDim, equipment);
            }

            case SyncProtocol.S2C_OPPONENT_CARD_OBTAINED -> {
                // Opponent obtained a card — display notification
                String opSender = msg.get("sender").getAsString();
                String mobKey = msg.get("mobKey").getAsString();
                int cardCount = msg.get("count").getAsInt();
                boolean bonus = msg.get("bonus").getAsBoolean();
                ArenaClashClient.onOpponentCardObtainedFromTcp(opSender, mobKey, cardCount, bonus);
            }

            case SyncProtocol.S2C_BROADCAST_RELAY -> {
                // System broadcast from opponent (achievements, deaths)
                String brSender = msg.get("sender").getAsString();
                String brText = msg.get("text").getAsString();
                ArenaClashClient.onBroadcastRelayFromTcp(brSender, brText);
            }

            case SyncProtocol.S2C_GAME_SEED -> {
                long seed = msg.get("seed").getAsLong();
                String gsId = msg.has("gameSessionId") ? msg.get("gameSessionId").getAsString() : null;
                LOGGER.info("Received game seed: {}, sessionId: {}", seed, gsId);
                ArenaClashClient.lastGameSeed = seed;
                ArenaClashClient.lastGameSessionId = gsId;
                // Schedule world creation — WorldCreationHelper uses gameSessionId
                // to decide whether to load existing or create new
                ArenaClashClient.scheduleWorldCreation(seed);
            }

            case SyncProtocol.S2C_RECONNECT_STATE -> {
                // Reconnection state restore
                String rPhase = msg.get("phase").getAsString();
                int rRound = msg.get("round").getAsInt();
                int rTimer = msg.get("timerTicks").getAsInt();
                String rCards = msg.has("cards") ? msg.get("cards").getAsString() : "";
                long rSeed = msg.has("seed") ? msg.get("seed").getAsLong() : 0;
                String rGsId = msg.has("gameSessionId") ? msg.get("gameSessionId").getAsString() : null;

                // CRITICAL: Update TcpClient state so subsequent messages
                // (like GAME_SEED) are handled correctly
                currentPhase = rPhase;
                currentRound = rRound;
                timerTicks = rTimer;
                com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive = "SURVIVAL".equals(rPhase);

                ArenaClashClient.lastGameSeed = rSeed;
                ArenaClashClient.lastGameSessionId = rGsId;
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

    public void sendMergeCards(String cardId1, String cardId2) {
        send(SyncProtocol.mergeCards(cardId1, cardId2));
    }

    public void sendPlayerState(double x, double y, double z, float yaw, float pitch, String dimension, String equipmentSnbt) {
        send(SyncProtocol.playerState(x, y, z, yaw, pitch, dimension, equipmentSnbt));
    }

    public void sendBroadcast(String message) {
        send(SyncProtocol.broadcastMessage(message));
    }
}
