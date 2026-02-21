package com.arenaclash.client;

import com.arenaclash.client.gui.CardScreen;
import com.arenaclash.client.gui.DeploymentScreen;
import com.arenaclash.client.render.GameHudRenderer;
import com.arenaclash.client.tcp.ArenaClashTcpClient;
import com.arenaclash.client.world.WorldCreationHelper;
import com.arenaclash.network.NetworkHandler;
import com.arenaclash.tcp.SyncProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ArenaClashClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-Client");

    // Client-side synced state
    public static String currentPhase = "LOBBY";
    public static int timerTicks = 0;
    public static int currentRound = 0;
    public static NbtCompound cardInventoryData = null;
    public static NbtCompound deploymentSlotData = null;

    // TCP client
    private static ArenaClashTcpClient tcpClient;
    public static String lastServerAddress = "join.brimworld.online:25522";

    // Scheduled actions (from TCP thread -> client thread)
    private static volatile String scheduledMcHost = null;
    private static volatile int scheduledMcPort = 0;
    private static volatile boolean scheduledReturnToSingle = false;

    // Key bindings
    private static KeyBinding openCardsKey;
    private static KeyBinding ringBellKey;

    // Track singleplayer world name for return trips
    private static String savedSingleplayerWorld = null;

    // FIX 7: Track whether we've sent world ready signal
    public static boolean worldReadySent = false;

    // Bidirectional inventory sync: pending inventory to restore in singleplayer
    public static volatile String pendingInventoryRestore = null;
    // Track whether inventory was already restored this session
    public static boolean inventoryRestored = false;

    // Client pause state tracking (for auto-pause)
    private static boolean lastPauseState = false;

    // Config file for persistent IP address (Fix 8)
    private static final String CONFIG_FILE = "arenaclash_client.txt";

    @Override
    public void onInitializeClient() {
        openCardsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.arenaclash.open_cards", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J, "category.arenaclash"
        ));
        ringBellKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.arenaclash.ring_bell", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B, "category.arenaclash"
        ));

        // Load saved server address (Fix 8)
        loadSavedAddress();

        registerMcPacketHandlers();

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register((drawContext, renderTickCounter) ->
                GameHudRenderer.render(drawContext, renderTickCounter));
    }

    private void onTick(MinecraftClient client) {
        // Process TCP messages
        if (tcpClient != null && tcpClient.isConnected()) {
            tcpClient.processIncoming();
        }

        // Forward singleplayer mob kills via TCP
        if (tcpClient != null && tcpClient.isConnected()) {
            String mobId;
            while ((mobId = com.arenaclash.tcp.SingleplayerBridge.pendingMobKills.poll()) != null) {
                tcpClient.sendCardObtained(mobId);
            }
        }

        // Forward singleplayer chat messages via TCP (Fix 6)
        if (tcpClient != null && tcpClient.isConnected()) {
            String chatMsg;
            while ((chatMsg = com.arenaclash.tcp.SingleplayerBridge.pendingChatMessages.poll()) != null) {
                tcpClient.sendChat(chatMsg);
            }
        }

        // Handle scheduled MC server connect
        if (scheduledMcHost != null) {
            String host = scheduledMcHost;
            int port = scheduledMcPort;
            scheduledMcHost = null;
            connectToMcServer(client, host, port);
        }

        // Handle scheduled return to singleplayer
        if (scheduledReturnToSingle) {
            scheduledReturnToSingle = false;
            returnToSingleplayer(client);
        }

        // Handle world creation ticks (Fix 3)
        WorldCreationHelper.tickPending(client);

        // Track singleplayer world name
        if (client.isInSingleplayer() && client.getServer() != null
                && "SURVIVAL".equals(currentPhase)) {
            String levelName = client.getServer().getSaveProperties().getLevelName();
            if (levelName != null && levelName.startsWith(WorldCreationHelper.WORLD_NAME_PREFIX)) {
                WorldCreationHelper.setCurrentWorldDirName(levelName);
                savedSingleplayerWorld = levelName;
            }
        }

        // FIX 7: Send WORLD_READY when singleplayer world is loaded and game is active
        if (client.isInSingleplayer() && client.world != null && "SURVIVAL".equals(currentPhase)) {
            if (!worldReadySent && tcpClient != null && tcpClient.isConnected()) {
                worldReadySent = true;
                tcpClient.send(SyncProtocol.makeMessage("WORLD_READY"));
            }

            // Bidirectional inventory sync: restore inventory from arena when returning to singleplayer
            if (!inventoryRestored && pendingInventoryRestore != null && client.player != null) {
                try {
                    String invSnbt = pendingInventoryRestore;
                    // Must restore on the INTEGRATED SERVER side, not the client side.
                    // The server is authoritative for inventory; client-only changes get
                    // overwritten by server sync packets.
                    var integratedServer = client.getServer();
                    if (integratedServer != null && integratedServer.isRunning()) {
                        final String snbt = invSnbt;
                        integratedServer.execute(() -> {
                            try {
                                var serverPlayer = integratedServer.getPlayerManager()
                                        .getPlayer(client.player.getUuid());
                                if (serverPlayer != null) {
                                    net.minecraft.nbt.NbtCompound invNbt =
                                            net.minecraft.nbt.StringNbtReader.parse(snbt);
                                    net.minecraft.nbt.NbtList items = invNbt.getList("Items", 10);
                                    serverPlayer.getInventory().clear();
                                    serverPlayer.getInventory().readNbt(items);
                                    serverPlayer.currentScreenHandler.sendContentUpdates();
                                    serverPlayer.playerScreenHandler.sendContentUpdates();
                                }
                            } catch (Exception e) {
                                LOGGER.error("Failed to restore inventory on server side", e);
                            }
                        });
                        inventoryRestored = true;
                        pendingInventoryRestore = null;
                    }
                    // else: server not ready yet, will retry next tick
                } catch (Exception e) {
                    LOGGER.error("Failed to schedule inventory restore", e);
                    pendingInventoryRestore = null;
                }
            }
        }

        // Auto-pause: check if client is paused (ESC menu) and send state changes
        if (tcpClient != null && tcpClient.isConnected()) {
            boolean currentlyPaused = client.isPaused()
                    || (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen);
            if (currentlyPaused != lastPauseState) {
                lastPauseState = currentlyPaused;
                tcpClient.send(SyncProtocol.pauseState(currentlyPaused));
            }
        }

        // Key bindings
        while (openCardsKey.wasPressed()) {
            if ("PREPARATION".equals(currentPhase) && cardInventoryData != null) {
                client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
            } else if (("SURVIVAL".equals(currentPhase) || "BATTLE".equals(currentPhase))
                    && cardInventoryData != null) {
                client.setScreen(new CardScreen(cardInventoryData));
            }
        }

        while (ringBellKey.wasPressed()) {
            if (tcpClient != null && tcpClient.isConnected()) {
                tcpClient.sendBellRing();
            }
            try {
                ClientPlayNetworking.send(new NetworkHandler.RingBell());
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // TCP MANAGEMENT
    // =========================================================================

    public static boolean connectTcp(String host, int port) {
        if (tcpClient != null && tcpClient.isConnected()) {
            tcpClient.disconnect();
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        String playerName = mc.getSession().getUsername();
        var profileId = mc.getSession().getUuidOrNull();
        if (profileId == null) {
            LOGGER.error("No player UUID available");
            return false;
        }

        tcpClient = new ArenaClashTcpClient();
        boolean success = tcpClient.connect(host, port, playerName, profileId);

        // Save address on successful connect — note: lastServerAddress is set by the caller
        // with the raw user input, so we just persist that
        if (success) {
            saveAddress();
        }

        return success;
    }

    public static void disconnectTcp() {
        if (tcpClient != null) {
            tcpClient.disconnect();
            tcpClient = null;
        }
        currentPhase = "LOBBY";
        timerTicks = 0;
        currentRound = 0;
        pendingInventoryRestore = null;
        inventoryRestored = false;
        lastPauseState = false;
        com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive = false;
    }

    public static ArenaClashTcpClient getTcpClient() {
        return tcpClient;
    }

    // =========================================================================
    // WORLD TRANSITIONS
    // =========================================================================

    public static void scheduleConnectToMcServer(String host, int port) {
        scheduledMcHost = host;
        scheduledMcPort = port;
    }

    public static void scheduleReturnToSingleplayer() {
        scheduledReturnToSingle = true;
    }

    public static void scheduleWorldCreation(long seed, int round) {
        WorldCreationHelper.scheduleWorldCreation(seed, round);
    }

    private void connectToMcServer(MinecraftClient client, String host, int port) {
        LOGGER.info("Connecting to MC server {}:{} for arena phase", host, port);

        if (client.isInSingleplayer() && client.getServer() != null) {
            savedSingleplayerWorld = client.getServer().getSaveProperties().getLevelName();
        }

        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect();

        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            client.execute(() -> {
                ServerAddress address = new ServerAddress(host, port);
                ServerInfo info = new ServerInfo("Arena Clash", address.toString(), ServerInfo.ServerType.OTHER);
                ConnectScreen.connect(
                        client.currentScreen != null ? client.currentScreen : new TitleScreen(),
                        client, address, info, false, null);
            });
        }, "ArenaClash-ConnectMC").start();
    }

    private void returnToSingleplayer(MinecraftClient client) {
        LOGGER.info("Returning to singleplayer (world: {})", savedSingleplayerWorld);

        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect();

        if (savedSingleplayerWorld != null) {
            new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                client.execute(() -> {
                    try {
                        client.createIntegratedServerLoader().start(savedSingleplayerWorld, () -> {});
                    } catch (Exception e) {
                        LOGGER.error("Failed to re-open singleplayer world", e);
                        client.setScreen(new TitleScreen());
                    }
                });
            }, "ArenaClash-ReconnectSingle").start();
        } else {
            client.setScreen(new TitleScreen());
        }
    }

    // =========================================================================
    // TCP -> CLIENT STATE SYNC
    // =========================================================================

    public static void onCardSyncFromTcp(String cardsSnbt) {
        try {
            NbtCompound nbt = net.minecraft.nbt.StringNbtReader.parse(cardsSnbt);
            cardInventoryData = nbt;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen instanceof CardScreen) {
                client.setScreen(new CardScreen(cardInventoryData));
            } else if (client.currentScreen instanceof DeploymentScreen && cardInventoryData != null) {
                client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse card sync from TCP: {}", e.getMessage());
        }
    }

    /** Chat relay from other player (Fix 6). */
    public static void onChatRelayFromTcp(String sender, String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("\u00a77[" + sender + "] \u00a7f" + message));
        }
    }

    /** Reconnection state restore (Fix 7). */
    public static void onReconnectState(String phase, int round, int timer, String cardsSnbt, long seed) {
        currentPhase = phase;
        currentRound = round;
        timerTicks = timer;
        if (cardsSnbt != null && !cardsSnbt.isEmpty()) {
            onCardSyncFromTcp(cardsSnbt);
        }
        LOGGER.info("Reconnected to game: phase={}, round={}, seed={}", phase, round, seed);

        // Fix 2: Update singleplayer bridge flag
        com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive = "SURVIVAL".equals(phase);

        MinecraftClient client = MinecraftClient.getInstance();
        // Trigger appropriate action based on current phase
        if ("SURVIVAL".equals(phase)) {
            // Need to be in singleplayer world
            if (seed != 0) {
                WorldCreationHelper.scheduleWorldCreation(seed, round);
            }
        } else if ("PREPARATION".equals(phase) || "BATTLE".equals(phase)) {
            // Need to connect to MC server for arena
            if (tcpClient != null && tcpClient.isConnected()) {
                String host = tcpClient.getServerHost();
                int mcPort = tcpClient.getServerMcPort();
                if (mcPort > 0) {
                    scheduleConnectToMcServer(host, mcPort);
                }
            }
        }
    }

    // =========================================================================
    // IP ADDRESS PERSISTENCE (Fix 8)
    // =========================================================================

    private static void loadSavedAddress() {
        try {
            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            Path configFile = configDir.resolve(CONFIG_FILE);
            if (Files.exists(configFile)) {
                String content = Files.readString(configFile).trim();
                if (!content.isEmpty()) {
                    lastServerAddress = content;
                    LOGGER.info("Loaded saved server address: {}", lastServerAddress);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to load saved server address", e);
        }
    }

    private static void saveAddress() {
        try {
            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            Path configFile = configDir.resolve(CONFIG_FILE);
            Files.writeString(configFile, lastServerAddress);
        } catch (IOException e) {
            LOGGER.warn("Failed to save server address", e);
        }
    }

    // =========================================================================
    // MC NETWORK HANDLERS
    // =========================================================================

    private void registerMcPacketHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.GameStateSync.ID,
                (payload, context) -> context.client().execute(() -> {
                    currentPhase = payload.phase();
                    timerTicks = payload.timerTicks();
                    currentRound = payload.round();
                }));

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.CardInventorySync.ID,
                (payload, context) -> context.client().execute(() -> {
                    cardInventoryData = payload.data();
                    MinecraftClient client = context.client();
                    if (client.currentScreen instanceof CardScreen) {
                        client.setScreen(new CardScreen(cardInventoryData));
                    } else if (client.currentScreen instanceof DeploymentScreen) {
                        client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.CardObtained.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().player != null) {
                        context.client().player.sendMessage(
                                Text.literal("\u00a76\u00a7l\u2605 " + payload.displayName() + " Card! \u2605"));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.DeploymentSlotSync.ID,
                (payload, context) -> context.client().execute(() -> {
                    deploymentSlotData = payload.data();
                    MinecraftClient client = context.client();
                    if (client.currentScreen instanceof DeploymentScreen && cardInventoryData != null) {
                        client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.BattleResultNotify.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().player != null) {
                        context.client().player.sendMessage(Text.literal(
                                "\u00a76=== " + payload.resultType() + " - Winner: " + payload.winner() + " ==="));
                    }
                }));
    }
}
