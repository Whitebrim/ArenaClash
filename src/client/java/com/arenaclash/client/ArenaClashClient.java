package com.arenaclash.client;

import com.arenaclash.client.gui.CardScreen;
import com.arenaclash.client.gui.CardUpgradeScreen;
import com.arenaclash.client.gui.DeploymentScreen;
import com.arenaclash.client.render.GameHudRenderer;
import com.arenaclash.client.survival.OpponentMarkerManager;
import com.arenaclash.client.tcp.ArenaClashTcpClient;
import com.arenaclash.client.world.WorldCreationHelper;
import com.arenaclash.network.NetworkHandler;
import com.arenaclash.tcp.SyncProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.ConnectScreen;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.InputConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
    public static CompoundTag cardInventoryData = null;
    public static CompoundTag deploymentSlotData = null;

    // TCP client
    private static ArenaClashTcpClient tcpClient;
    public static String lastServerAddress = "join.brimworld.online:25522";

    // Scheduled actions (from TCP thread -> client thread)
    private static volatile String scheduledMcHost = null;
    private static volatile int scheduledMcPort = 0;
    private static volatile boolean scheduledReturnToSingle = false;
    private static volatile boolean scheduledReturnToTitle = false;
    private static volatile boolean scheduledReturnToGame = false;
    private static volatile boolean scheduledReturnToSurvival = false;

    // Last known MC server address for Continue button
    private static String lastMcHost = null;
    private static int lastMcPort = 0;

    // Key bindings
    private static KeyMapping openCardsKey;

    // Track singleplayer world name for return trips
    private static String savedSingleplayerWorld = null;

    // Track whether we've sent world ready signal
    public static boolean worldReadySent = false;

    // Bidirectional inventory sync: pending inventory to restore in singleplayer
    public static volatile String pendingInventoryRestore = null;
    // Track whether inventory was already restored this session
    public static boolean inventoryRestored = false;

    // Client pause state tracking (for auto-pause)
    private static boolean lastPauseState = false;

    // Player state sync tick counter (send every 4 ticks to reduce bandwidth)
    private static int playerStateTicks = 0;
    // Last sent equipment SNBT to avoid resending unchanged data
    private static String lastEquipmentSnbt = null;

    // Config file for persistent IP address
    private static final String CONFIG_FILE = "arenaclash_client.txt";

    // Stored game seed for Continue button during SURVIVAL
    public static long lastGameSeed = 0;
    // Game session ID for deterministic world naming
    public static String lastGameSessionId = null;

    @Override
    public void onInitializeClient() {
        openCardsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.arenaclash.open_cards", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_TAB, "category.arenaclash"
        ));

        // Load saved server address
        loadSavedAddress();

        registerMcPacketHandlers();
        registerClientCommands();
        registerWorkbenchInteraction();

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) ->
                GameHudRenderer.render(guiGraphics, deltaTracker));
    }

    /**
     * Register client-side block interaction for the Card Upgrade Workbench.
     * When the server confirms the workbench interaction is valid,
     * it sends an OpenUpgradeGui packet. The client opens the GUI on receipt.
     * This replaces the old client-side UseBlockCallback approach, ensuring
     * the GUI never opens when the server would block the interaction
     * (e.g., enemy build zone, wrong phase).
     */
    private void registerWorkbenchInteraction() {
        // No client-side UseBlockCallback needed — the server sends OpenUpgradeGui packet
        // See registerNetworkHandlers() for the receiver
    }

    /**
     * Register client-side /ac commands that intercept before the integrated server.
     * When connected to TCP, all /ac commands are forwarded to the dedicated server.
     * This prevents the integrated server from executing /ac locally (which would
     * show "no game in progress" since the game runs on the dedicated server).
     */
    private void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /ac <anything> → forward via TCP
            dispatcher.register(ClientCommands.literal("ac")
                    .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String args = StringArgumentType.getString(ctx, "args");
                                ArenaClashTcpClient tcp = getTcpClient();
                                if (tcp != null && tcp.isConnected()) {
                                    tcp.sendChat("/ac " + args);
                                    return 1;
                                }
                                ctx.getSource().sendFeedback(Component.translatable("arenaclash.msg.not_connected"));
                                return 0;
                            }))
                    .executes(ctx -> {
                        ArenaClashTcpClient tcp = getTcpClient();
                        if (tcp != null && tcp.isConnected()) {
                            tcp.sendChat("/ac");
                            return 1;
                        }
                        ctx.getSource().sendFeedback(Component.translatable("arenaclash.msg.not_connected"));
                        return 0;
                    }));
        });
    }

    private void onTick(Minecraft client) {
        // Process TCP messages
        if (tcpClient != null && tcpClient.isConnected()) {
            tcpClient.processIncoming();
        }

        // Forward singleplayer mob kills via TCP
        if (tcpClient != null && tcpClient.isConnected()) {
            String mobId;
            while ((mobId = com.arenaclash.tcp.SingleplayerBridge.pendingMobKills.poll()) != null) {
                if (mobId.startsWith(com.arenaclash.tcp.SingleplayerBridge.BONUS_PREFIX)) {
                    String actualMobId = mobId.substring(com.arenaclash.tcp.SingleplayerBridge.BONUS_PREFIX.length());
                    tcpClient.send(SyncProtocol.cardObtainedBonus(actualMobId));
                } else {
                    tcpClient.sendCardObtained(mobId);
                }
            }
        }

        // Forward singleplayer chat messages via TCP
        if (tcpClient != null && tcpClient.isConnected()) {
            String chatMsg;
            while ((chatMsg = com.arenaclash.tcp.SingleplayerBridge.pendingChatMessages.poll()) != null) {
                tcpClient.sendChat(chatMsg);
            }
        }

        // Forward singleplayer broadcasts (achievements, deaths) via TCP
        if (tcpClient != null && tcpClient.isConnected()) {
            String broadcast;
            while ((broadcast = com.arenaclash.tcp.SingleplayerBridge.pendingBroadcasts.poll()) != null) {
                tcpClient.sendBroadcast(broadcast);
            }
        }

        // Send player state to opponent during survival phase (every 4 ticks)
        if (tcpClient != null && tcpClient.isConnected()
                && "SURVIVAL".equals(currentPhase)
                && client.player != null && client.isLocalServer()) {
            playerStateTicks++;
            if (playerStateTicks >= 4) {
                playerStateTicks = 0;
                sendPlayerState(client);
            }
        }

        // Tick opponent marker during survival phase
        if ("SURVIVAL".equals(currentPhase) && client.level != null) {
            OpponentMarkerManager.tick();
        } else {
            // Remove marker when not in survival
            OpponentMarkerManager.remove();
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

        // Handle scheduled return to title screen (after game over)
        if (scheduledReturnToTitle) {
            scheduledReturnToTitle = false;
            returnToTitleScreen(client);
        }

        // Handle scheduled return to game (Continue button)
        if (scheduledReturnToGame) {
            scheduledReturnToGame = false;
            if (lastMcHost != null && lastMcPort > 0) {
                connectToMcServer(client, lastMcHost, lastMcPort);
            } else if (tcpClient != null && tcpClient.getServerMcPort() > 0) {
                // Fallback: derive host from TCP connection
                String host = tcpClient.getServerHost();
                int port = tcpClient.getServerMcPort();
                if (host != null) connectToMcServer(client, host, port);
            }
        }

        // Handle scheduled return to survival (Continue button during SURVIVAL)
        if (scheduledReturnToSurvival) {
            scheduledReturnToSurvival = false;
            returnToSurvival(client);
        }

        // Handle world creation ticks
        WorldCreationHelper.tickPending(client);

        // Track singleplayer world name
        if (client.isLocalServer() && client.getSingleplayerServer() != null
                && "SURVIVAL".equals(currentPhase)) {
            String levelName = client.getSingleplayerServer().getWorldData().getLevelName();
            if (levelName != null && levelName.startsWith(WorldCreationHelper.WORLD_NAME_PREFIX)) {
                WorldCreationHelper.setCurrentWorldDirName(levelName);
                savedSingleplayerWorld = levelName;
            }
        }

        // Send WORLD_READY when singleplayer world is loaded and game is active
        if (client.isLocalServer() && client.level != null && "SURVIVAL".equals(currentPhase)) {
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
                    var integratedServer = client.getSingleplayerServer();
                    if (integratedServer != null && integratedServer.isRunning()) {
                        final String snbt = invSnbt;
                        integratedServer.execute(() -> {
                            try {
                                var serverPlayer = integratedServer.getPlayerList()
                                        .getPlayer(client.player.getUUID());
                                if (serverPlayer != null) {
                                    net.minecraft.nbt.CompoundTag invNbt =
                                            net.minecraft.nbt.TagParser.parseTag(snbt);
                                    net.minecraft.nbt.ListTag items = invNbt.getList("Items", 10);
                                    serverPlayer.getInventory().clearContent();
                                    serverPlayer.getInventory().load(items);
                                    serverPlayer.containerMenu.broadcastChanges();
                                    serverPlayer.inventoryMenu.broadcastChanges();
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
                    || (client.screen instanceof net.minecraft.client.gui.screens.PauseScreen);
            if (currentlyPaused != lastPauseState) {
                lastPauseState = currentlyPaused;
                tcpClient.send(SyncProtocol.pauseState(currentlyPaused));
            }
        }

        // Key bindings
        while (openCardsKey.consumeClick()) {
            if ("PREPARATION".equals(currentPhase) && cardInventoryData != null) {
                client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
            } else if (("SURVIVAL".equals(currentPhase) || "BATTLE".equals(currentPhase))
                    && cardInventoryData != null) {
                client.setScreen(new CardScreen(cardInventoryData));
            }
        }
    }

    // =========================================================================
    // PLAYER STATE SYNC (OPPONENT MARKER)
    // =========================================================================

    /**
     * Collect and send player's current position + equipment to opponent via TCP.
     */
    private static void sendPlayerState(Minecraft client) {
        if (client.player == null || client.level == null || tcpClient == null) return;

        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();
        float yaw = client.player.getYRot();
        float pitch = client.player.getXRot();
        String dimension = client.level.dimension().location().toString();

        // Build equipment SNBT
        String equipmentSnbt = buildEquipmentSnbt(client);

        // Only send equipment if it changed
        String eqToSend = null;
        if (!java.util.Objects.equals(equipmentSnbt, lastEquipmentSnbt)) {
            lastEquipmentSnbt = equipmentSnbt;
            eqToSend = equipmentSnbt;
        }

        tcpClient.sendPlayerState(x, y, z, yaw, pitch, dimension, eqToSend);
    }

    /**
     * Build an SNBT string representing the player's current equipment.
     */
    private static String buildEquipmentSnbt(Minecraft client) {
        if (client.player == null) return "{}";
        try {
            net.minecraft.nbt.CompoundTag eq = new net.minecraft.nbt.CompoundTag();
            var registryOps = client.level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);

            // Main hand
            net.minecraft.world.item.ItemStack mainHand = client.player.getMainHandItem();
            if (mainHand != null && !mainHand.isEmpty()) {
                eq.putString("MainHand", encodeItemStack(mainHand, registryOps));
            }

            // Off hand
            net.minecraft.world.item.ItemStack offHand = client.player.getOffhandItem();
            if (offHand != null && !offHand.isEmpty()) {
                eq.putString("OffHand", encodeItemStack(offHand, registryOps));
            }

            // Armor slots
            net.minecraft.world.item.ItemStack helmet = client.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
            if (helmet != null && !helmet.isEmpty()) {
                eq.putString("Helmet", encodeItemStack(helmet, registryOps));
            }

            net.minecraft.world.item.ItemStack chest = client.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            if (chest != null && !chest.isEmpty()) {
                eq.putString("Chestplate", encodeItemStack(chest, registryOps));
            }

            net.minecraft.world.item.ItemStack legs = client.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS);
            if (legs != null && !legs.isEmpty()) {
                eq.putString("Leggings", encodeItemStack(legs, registryOps));
            }

            net.minecraft.world.item.ItemStack boots = client.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
            if (boots != null && !boots.isEmpty()) {
                eq.putString("Boots", encodeItemStack(boots, registryOps));
            }

            return eq.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String encodeItemStack(net.minecraft.world.item.ItemStack stack,
                                           com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> ops) {
        try {
            return net.minecraft.world.item.ItemStack.CODEC.encodeStart(ops, stack)
                    .resultOrPartial(err -> {})
                    .map(Object::toString)
                    .orElse("{}");
        } catch (Exception e) {
            return "{}";
        }
    }

    // =========================================================================
    // TCP MANAGEMENT
    // =========================================================================

    public static boolean connectTcp(String host, int port) {
        if (tcpClient != null && tcpClient.isConnected()) {
            tcpClient.disconnect();
        }

        Minecraft mc = Minecraft.getInstance();
        String playerName = mc.getUser().getName();
        var profileId = mc.getUser().getProfileId();
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
        lastMcHost = host;
        lastMcPort = port;
    }

    public static void scheduleReturnToSingleplayer() {
        scheduledReturnToSingle = true;
    }

    public static void scheduleReturnToTitleScreen() {
        scheduledReturnToTitle = true;
    }

    /** Reconnect to the MC arena server from title screen (Continue button) */
    public static void scheduleReturnToGame() {
        scheduledReturnToGame = true;
    }

    /** Return to singleplayer survival world from title screen (Continue button during SURVIVAL) */
    public static void scheduleReturnToSurvival() {
        scheduledReturnToSurvival = true;
    }

    public static void scheduleWorldCreation(long seed, int round) {
        scheduleWorldCreation(seed);
    }

    public static void scheduleWorldCreation(long seed, int round, boolean isNewGame) {
        scheduleWorldCreation(seed);
    }

    public static void scheduleWorldCreation(long seed) {
        WorldCreationHelper.scheduleWorldCreation(seed);
    }

    private void connectToMcServer(Minecraft client, String host, int port) {
        LOGGER.info("Connecting to MC server {}:{} for arena phase", host, port);

        if (client.isLocalServer() && client.getSingleplayerServer() != null) {
            savedSingleplayerWorld = client.getSingleplayerServer().getWorldData().getLevelName();
        }

        if (client.level != null) {
            client.level.disconnect();
        }
        client.disconnect();

        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            client.execute(() -> {
                ServerAddress address = new ServerAddress(host, port);
                ServerData info = new ServerData("Arena Clash", address.toString(), ServerData.Type.OTHER);
                ConnectScreen.connect(
                        client.screen != null ? client.screen : new TitleScreen(),
                        client, address, info, false, null);
            });
        }, "ArenaClash-ConnectMC").start();
    }

    private void returnToSingleplayer(Minecraft client) {
        LOGGER.info("Returning to singleplayer (world: {})", savedSingleplayerWorld);

        if (client.level != null) {
            client.level.disconnect();
        }
        client.disconnect();

        if (savedSingleplayerWorld != null) {
            new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                client.execute(() -> {
                    try {
                        client.createWorldOpenFlows().openWorld(savedSingleplayerWorld, () -> {});
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

    /**
     * Return to survival world from title screen (Continue button during SURVIVAL).
     * If the world exists, reopen it. Otherwise, create it using stored game seed.
     */
    private void returnToSurvival(Minecraft client) {
        LOGGER.info("Returning to survival (world: {}, seed: {})", savedSingleplayerWorld, lastGameSeed);

        if (client.level != null) {
            client.level.disconnect();
        }
        client.disconnect();

        // Try reopening existing world first
        if (savedSingleplayerWorld != null) {
            new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                client.execute(() -> {
                    try {
                        client.createWorldOpenFlows().openWorld(savedSingleplayerWorld, () -> {});
                    } catch (Exception e) {
                        LOGGER.error("Failed to re-open singleplayer world, creating new", e);
                        // Fallback: create new world
                        if (lastGameSeed != 0 && tcpClient != null) {
                            WorldCreationHelper.scheduleWorldCreation(lastGameSeed);
                        } else {
                            client.setScreen(new TitleScreen());
                        }
                    }
                });
            }, "ArenaClash-ReconnectSurvival").start();
        } else if (lastGameSeed != 0 && tcpClient != null) {
            // No saved world name — create world from seed
            WorldCreationHelper.scheduleWorldCreation(lastGameSeed);
        } else {
            LOGGER.warn("Cannot return to survival: no saved world and no game seed");
            client.setScreen(new TitleScreen());
        }
    }

    private void returnToTitleScreen(Minecraft client) {
        LOGGER.info("Returning to title screen after game over");

        if (client.level != null) {
            client.level.disconnect();
        }
        client.disconnect();

        // Delete the ArenaClash singleplayer world
        String worldToDelete = savedSingleplayerWorld;
        savedSingleplayerWorld = null;
        currentPhase = "LOBBY";
        timerTicks = 0;
        currentRound = 0;
        lastGameSessionId = null;
        lastEquipmentSnbt = null;

        // Remove opponent marker
        OpponentMarkerManager.remove();

        // Disconnect TCP so Continue button disappears on title screen
        if (tcpClient != null) {
            tcpClient.disconnect();
            tcpClient = null;
        }

        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            client.execute(() -> {
                client.setScreen(new TitleScreen());
                // Delete the arena clash world in background
                if (worldToDelete != null) {
                    WorldCreationHelper.deleteWorld(worldToDelete);
                }
                WorldCreationHelper.reset();
            });
        }, "ArenaClash-ReturnTitle").start();
    }

    // =========================================================================
    // TCP -> CLIENT STATE SYNC
    // =========================================================================

    public static void onCardSyncFromTcp(String cardsSnbt) {
        try {
            CompoundTag nbt = net.minecraft.nbt.TagParser.parseTag(cardsSnbt);
            cardInventoryData = nbt;

            Minecraft client = Minecraft.getInstance();
            if (client.screen instanceof CardScreen) {
                client.setScreen(new CardScreen(cardInventoryData));
            } else if (client.screen instanceof DeploymentScreen && cardInventoryData != null) {
                client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
            } else if (client.screen instanceof CardUpgradeScreen && cardInventoryData != null) {
                client.setScreen(new CardUpgradeScreen(cardInventoryData));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse card sync from TCP: {}", e.getMessage());
        }
    }

    /** Chat relay from other player — formatted identically to vanilla multiplayer chat. */
    public static void onChatRelayFromTcp(String sender, String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            // "chat.type.text" is the vanilla key: <%s> %s → <PlayerName> message
            // Using it gives EXACT vanilla formatting including hover events.
            Component formatted = Component.translatable("chat.type.text",
                    Component.literal(sender), Component.literal(message));
            client.player.sendSystemMessage(formatted);
        }
    }

    /** Opponent state update — update ghost ArmorStand marker. */
    public static void onOpponentStateFromTcp(String name, double x, double y, double z,
                                                float yaw, float pitch, String dimension,
                                                String equipmentSnbt) {
        OpponentMarkerManager.onOpponentState(name, x, y, z, yaw, pitch, dimension, equipmentSnbt);
    }

    /** Opponent obtained a card — display colored notification in chat. */
    public static void onOpponentCardObtainedFromTcp(String sender, String mobTranslationKey, int count, boolean isBonus) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            String mobName = net.minecraft.client.resources.language.I18n.get(mobTranslationKey);

            // §d (light purple) for opponent card notifications
            Component msg;
            if (isBonus) {
                msg = Component.translatable("arenaclash.msg.opponent_card_bonus", sender, mobName, String.valueOf(count));
            } else {
                msg = Component.translatable("arenaclash.msg.opponent_card", sender, mobName, String.valueOf(count));
            }
            client.player.sendSystemMessage(msg);
        }
    }

    /**
     * Broadcast relay from opponent (achievements, deaths).
     * The text is JSON-serialized by Component.Serializer on the sender's integrated
     * server, preserving all vanilla formatting: colours, hover events on advancement
     * names, translatable components, etc.
     */
    public static void onBroadcastRelayFromTcp(String sender, String broadcastJson) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Component formatted = null;
            // Try to deserialize full JSON Component (preserves formatting)
            if (client.level != null) {
                try {
                    formatted = Component.Serializer.fromJson(broadcastJson, client.level.registryAccess());
                } catch (Exception e) {
                    // Fallback below
                }
            }
            if (formatted != null) {
                client.player.sendSystemMessage(formatted);
            } else {
                // Fallback: display as yellow system message
                client.player.sendSystemMessage(Component.literal(broadcastJson)
                        .withStyle(net.minecraft.ChatFormatting.YELLOW));
            }
        }
    }

    /** Reconnection state restore . */
    public static void onReconnectState(String phase, int round, int timer, String cardsSnbt, long seed) {
        currentPhase = phase;
        currentRound = round;
        timerTicks = timer;
        if (cardsSnbt != null && !cardsSnbt.isEmpty()) {
            onCardSyncFromTcp(cardsSnbt);
        }
        LOGGER.info("Reconnected to game: phase={}, round={}, seed={}", phase, round, seed);

        // Update singleplayer bridge flag
        com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive = "SURVIVAL".equals(phase);

        Minecraft client = Minecraft.getInstance();
        // Trigger appropriate action based on current phase
        if ("SURVIVAL".equals(phase)) {
            // Need to be in singleplayer world
            if (seed != 0) {
                WorldCreationHelper.scheduleWorldCreation(seed);
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
    // IP ADDRESS PERSISTENCE
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
                    Minecraft client = context.client();
                    if (client.screen instanceof CardScreen) {
                        client.setScreen(new CardScreen(cardInventoryData));
                    } else if (client.screen instanceof DeploymentScreen) {
                        client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
                    } else if (client.screen instanceof CardUpgradeScreen) {
                        client.setScreen(new CardUpgradeScreen(cardInventoryData));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.CardObtained.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().player != null) {
                        // Use mob translation key for localized name
                        String translatedName = net.minecraft.client.resources.language.I18n.get("arenaclash.mob." + payload.mobId());
                        context.client().player.sendSystemMessage(
                                Component.translatable("arenaclash.msg.card_obtained_star", translatedName));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.DeploymentSlotSync.ID,
                (payload, context) -> context.client().execute(() -> {
                    deploymentSlotData = payload.data();
                    Minecraft client = context.client();
                    if (client.screen instanceof DeploymentScreen && cardInventoryData != null) {
                        client.setScreen(new DeploymentScreen(cardInventoryData, deploymentSlotData));
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.BattleResultNotify.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().player != null) {
                        context.client().player.sendSystemMessage(Component.translatable(
                                "arenaclash.msg.battle_result", payload.resultType(), payload.winner()));
                    }
                }));

        // Open upgrade GUI when server confirms the workbench interaction is valid
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.OpenUpgradeGui.ID,
                (payload, context) -> context.client().execute(() -> {
                    if (cardInventoryData != null) {
                        CardUpgradeScreen.clearPersistedState();
                        Minecraft.getInstance().setScreen(new CardUpgradeScreen(cardInventoryData));
                    }
                }));
    }
}
