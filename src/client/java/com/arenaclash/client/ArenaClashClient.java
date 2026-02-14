package com.arenaclash.client;

import com.arenaclash.client.gui.CardScreen;
import com.arenaclash.client.gui.DeploymentScreen;
import com.arenaclash.client.render.GameHudRenderer;
import com.arenaclash.client.tcp.ArenaClashTcpClient;
import com.arenaclash.network.NetworkHandler;
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
    public static String lastServerAddress = "localhost:25566";

    // Scheduled actions (from TCP thread → client thread)
    private static volatile String scheduledMcHost = null;
    private static volatile int scheduledMcPort = 0;
    private static volatile boolean scheduledReturnToSingle = false;

    // Key bindings
    private static KeyBinding openCardsKey;
    private static KeyBinding ringBellKey;

    // Track if we disconnected from MC server to go back to singleplayer
    private static String savedSingleplayerWorld = null;

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
            // Also send via MC networking if connected to server
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
        return tcpClient.connect(host, port, playerName, profileId);
    }

    public static void disconnectTcp() {
        if (tcpClient != null) {
            tcpClient.disconnect();
            tcpClient = null;
        }
        currentPhase = "LOBBY";
        timerTicks = 0;
        currentRound = 0;
    }

    public static ArenaClashTcpClient getTcpClient() {
        return tcpClient;
    }

    // =========================================================================
    // WORLD TRANSITIONS
    // =========================================================================

    /**
     * Schedule connecting to MC server (called from TCP read thread).
     */
    public static void scheduleConnectToMcServer(String host, int port) {
        scheduledMcHost = host;
        scheduledMcPort = port;
    }

    /**
     * Schedule returning to singleplayer (called from TCP read thread).
     */
    public static void scheduleReturnToSingleplayer() {
        scheduledReturnToSingle = true;
    }

    /**
     * Connect to MC server for arena phase.
     * Saves current singleplayer world name so we can return later.
     */
    private void connectToMcServer(MinecraftClient client, String host, int port) {
        LOGGER.info("Connecting to MC server {}:{} for arena phase", host, port);

        // Save current singleplayer world info
        if (client.isInSingleplayer() && client.getServer() != null) {
            savedSingleplayerWorld = client.getServer().getSaveProperties()
                    .getLevelName();
        }

        // Disconnect from singleplayer
        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect();

        // Connect to MC server
        ServerAddress address = new ServerAddress(host, port);
        ServerInfo info = new ServerInfo("Arena Clash", address.toString(), ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(
                client.currentScreen != null ? client.currentScreen : new TitleScreen(),
                client, address, info, false, null);
    }

    /**
     * Return to singleplayer from MC server.
     */
    private void returnToSingleplayer(MinecraftClient client) {
        LOGGER.info("Returning to singleplayer (world: {})", savedSingleplayerWorld);

        // Disconnect from MC server
        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect();

        if (savedSingleplayerWorld != null) {
            // Small delay to let disconnect complete, then reopen world
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}

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
    // TCP → CLIENT STATE SYNC
    // =========================================================================

    /**
     * Called when TCP receives card sync data.
     */
    public static void onCardSyncFromTcp(String cardsSnbt) {
        try {
            NbtCompound nbt = net.minecraft.nbt.StringNbtReader.parse(cardsSnbt);
            cardInventoryData = nbt;

            // Refresh open screens
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

    // =========================================================================
    // MC NETWORK HANDLERS (for when connected to MC server)
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
                                Text.literal("§6§l★ " + payload.displayName() + " Card! ★"));
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
                                "§6=== " + payload.resultType() + " - Winner: " + payload.winner() + " ==="));
                    }
                }));
    }
}
