package com.arenaclash.client.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles automatic creation and loading of singleplayer survival worlds
 * for the Arena Clash game mode.
 *
 * Strategy:
 * - Round 1: Open CreateWorldScreen invisibly, configure via WorldCreationUiState,
 *   then call the private onCreate() through an access widener.
 * - Round 2+: Reload the same world via WorldOpenFlows.openWorld()
 * - After world loads: apply game rules (Hard difficulty, keepInventory, etc.)
 */
public class WorldCreationHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-WorldCreation");
    public static final String WORLD_NAME_PREFIX = "ArenaClash_";

    // --- Pending creation request ---
    private static volatile boolean creationPending = false;
    private static volatile long pendingSeed = 0;

    // --- Auto-submit state machine ---
    private static volatile boolean autoSubmitPending = false;
    private static String autoSubmitWorldName = null;
    private static long autoSubmitSeed = 0;
    private static int autoSubmitTicksLeft = 0;

    // --- Current game state ---
    private static String currentWorldDirName = null;
    private static long currentGameSeed = 0;

    // Whether game rules have already been applied to the current world load
    private static boolean gameRulesApplied = false;

    // ====================================================================
    // PUBLIC API
    // ====================================================================

    /** Schedule world creation/loading. Safe to call from any thread. */
    public static void scheduleWorldCreation(long seed) {
        pendingSeed = seed;
        currentGameSeed = seed;
        creationPending = true;
        gameRulesApplied = false;
    }

    /**
     * The entire world selection logic is based on gameSessionId:
     *
     * 1. World name = "ArenaClash_" + gameSessionId
     * 2. Already inside that world? → stay
     * 3. Inside a DIFFERENT ArenaClash_ world? → disconnect, re-enter pending
     * 4. World exists on disk? → load it
     * 5. World doesn't exist? → delete old worlds, create new one
     *
     * No isNewGame flags, no round checks. gameSessionId is the single source of truth.
     */
    public static void tickPending(Minecraft client) {
        // ---- auto-submit state machine ----
        if (autoSubmitPending) {
            if (--autoSubmitTicksLeft <= 0) {
                autoSubmitPending = false;
                doAutoSubmit(client);
            }
            return;
        }

        // ---- pending creation request ----
        if (!creationPending) {
            applyGameRulesOnceIfNeeded(client);
            return;
        }
        creationPending = false;

        long seed = pendingSeed;
        String gsId = com.arenaclash.client.ArenaClashClient.lastGameSessionId;

        // No session ID → can't determine world name, skip
        if (gsId == null || gsId.isEmpty()) {
            LOGGER.warn("No gameSessionId available, cannot create/load world");
            return;
        }

        String expectedWorldName = WORLD_NAME_PREFIX + gsId;

        // Case 1: Already inside the correct ArenaClash world → stay
        if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
            String levelName = client.getSingleplayerServer().getWorldData().getLevelName();
            if (expectedWorldName.equals(levelName)) {
                LOGGER.info("Already in correct world '{}'", levelName);
                currentWorldDirName = expectedWorldName;
                applyGameRules(client);
                return;
            }
            // Case 2: Inside a DIFFERENT ArenaClash world → disconnect first
            if (levelName != null && levelName.startsWith(WORLD_NAME_PREFIX)) {
                LOGGER.info("In wrong world '{}', need '{}' — disconnecting", levelName, expectedWorldName);
                creationPending = true; // re-process next tick after disconnect
                client.disconnectWithProgressScreen();
                return;
            }
        }

        // Case 3: World exists on disk → load it
        if (worldExistsOnDisk(expectedWorldName)) {
            LOGGER.info("Found world '{}' on disk — loading", expectedWorldName);
            currentWorldDirName = expectedWorldName;
            loadExistingWorld(client, expectedWorldName);
            return;
        }

        // Case 4: World doesn't exist → clean up old worlds, create new one
        LOGGER.info("World '{}' not found — cleaning up old worlds and creating new", expectedWorldName);
        cleanupOldWorlds();
        currentWorldDirName = expectedWorldName;
        beginWorldCreation(client, expectedWorldName, seed);
    }

    // ====================================================================
    // WORLD CREATION  (uses CreateWorldScreen + @Invoker accessor)
    // ====================================================================

    private static void beginWorldCreation(Minecraft client, String worldName, long seed) {
        // 1. Disconnect from whatever we're in now
        if (client.level != null) {
            client.disconnectWithProgressScreen();
        }

        // 2. Prepare the auto-submit state machine
        autoSubmitWorldName = worldName;
        autoSubmitSeed      = seed;
        autoSubmitPending   = true;
        autoSubmitTicksLeft = 8;               // ~8 ticks for CreateWorldScreen to init fully

        // 3. Open CreateWorldScreen on the next frame (after disconnect settles)
        client.execute(() -> {
            try {
                CreateWorldScreen.openFresh(client, () -> client.setScreen(new TitleScreen()));
            } catch (Exception e) {
                LOGGER.error("Failed to open CreateWorldScreen", e);
                autoSubmitPending = false;
            }
        });
    }

    /**
     * Called when autoSubmitTicksLeft reaches 0.
     * By now, CreateWorldScreen.init() has run and WorldCreationUiState is ready.
     */
    private static void doAutoSubmit(Minecraft client) {
        if (!(client.screen instanceof CreateWorldScreen createScreen)) {
            LOGGER.error("Expected CreateWorldScreen but got {} — aborting auto-create",
                    client.screen);
            return;
        }

        try {
            var creator = createScreen.getUiState();
            if (creator == null) {
                LOGGER.error("WorldCreationUiState is null on CreateWorldScreen");
                return;
            }

            // Configure everything the player would normally set manually
            creator.setName(autoSubmitWorldName);
            creator.setSeed(String.valueOf(autoSubmitSeed));
            creator.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.SURVIVAL);
            creator.setDifficulty(Difficulty.HARD);
            creator.setAllowCommands(true);     // needed for /gamerule

            LOGGER.info("Configured WorldCreationUiState: name={}, seed={}, SURVIVAL, HARD, cheats=on",
                    autoSubmitWorldName, autoSubmitSeed);

            // Remember the dir name so we can reload in later rounds
            currentWorldDirName = autoSubmitWorldName;

            // Trigger the full Mojang world-creation pipeline (datapacks, registries,
            // worldgen, level.dat, etc.) — method made accessible via arenaclash.accesswidener
            createScreen.onCreate();
            LOGGER.info("onCreate() invoked — world creation started");

        } catch (Exception e) {
            LOGGER.error("Failed to auto-submit CreateWorldScreen", e);
            client.setScreen(new TitleScreen());
        }
    }

    // ====================================================================
    // WORLD RELOAD  (round 2+)
    // ====================================================================

    private static void loadExistingWorld(Minecraft client, String dirName) {
        if (client.level != null) {
            client.disconnectWithProgressScreen();
        }

        // small delay for disconnect to flush, then reload
        client.execute(() -> {
            client.execute(() -> {                  // double-execute = 2-tick delay
                try {
                    client.createWorldOpenFlows().openWorld(dirName, () -> {
                        LOGGER.warn("World load cancelled: {}", dirName);
                        client.setScreen(new TitleScreen());
                    });
                } catch (Exception e) {
                    LOGGER.error("Failed to load world '{}'", dirName, e);
                    client.setScreen(new TitleScreen());
                }
            });
        });
    }

    // ====================================================================
    // GAME RULES
    // ====================================================================

    /**
     * Automatically apply game rules once when the integrated server finishes loading.
     * Called every tick; acts only once per world load.
     */
    private static void applyGameRulesOnceIfNeeded(Minecraft client) {
        if (gameRulesApplied) return;
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null) return;
        // Wait until the server is actually running
        if (!client.getSingleplayerServer().isRunning()) return;

        String levelName = client.getSingleplayerServer().getWorldData().getLevelName();
        if (levelName != null && levelName.startsWith(WORLD_NAME_PREFIX)) {
            applyGameRules(client);
            gameRulesApplied = true;
        }
    }

    /** Force-apply all ArenaClash game rules right now. */
    public static void applyGameRules(Minecraft client) {
        if (!client.hasSingleplayerServer() || client.getSingleplayerServer() == null) return;

        var server = client.getSingleplayerServer();
        server.execute(() -> {
            try {
                server.setDifficulty(Difficulty.HARD, true);

                var gr = server.getGameRules();
                gr.set(GameRules.ADVANCE_TIME, true, server);
                gr.set(GameRules.KEEP_INVENTORY, true, server);

                LOGGER.info("Applied game rules — HARD, keepInventory, daylightCycle");
            } catch (Exception e) {
                LOGGER.error("Failed to apply game rules", e);
            }
        });
    }

    // ====================================================================
    // GETTERS / SETTERS
    // ====================================================================

    public static long   getCurrentGameSeed()    { return currentGameSeed; }
    public static String getCurrentWorldDirName() { return currentWorldDirName; }
    public static void   setCurrentWorldDirName(String n) { currentWorldDirName = n; }

    public static void reset() {
        currentWorldDirName = null;
        currentGameSeed     = 0;
        creationPending     = false;
        autoSubmitPending   = false;
        gameRulesApplied    = false;
    }

    // ====================================================================
    // CLEANUP
    // ====================================================================

    /**
     * Check if a world with the given directory name exists on disk.
     */
    private static boolean worldExistsOnDisk(String dirName) {
        if (dirName == null) return false;
        Minecraft client = Minecraft.getInstance();
        Path savesDir = client.getLevelSource().getBaseDir();
        Path worldDir = savesDir.resolve(dirName);
        return Files.exists(worldDir) && Files.isDirectory(worldDir);
    }

    /**
     * Scan the saves directory for an existing ArenaClash_* world.
     * Returns the directory name of the most recently modified one, or null if none found.
     */
    private static String findExistingArenaClashWorld(Minecraft client) {
        Path savesDir = client.getLevelSource().getBaseDir();
        try {
            if (!Files.exists(savesDir)) return null;
            try (var dirs = Files.list(savesDir)) {
                return dirs.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith(WORLD_NAME_PREFIX))
                        .max(java.util.Comparator.comparingLong(p -> {
                            try { return Files.getLastModifiedTime(p).toMillis(); }
                            catch (IOException e) { return 0L; }
                        }))
                        .map(p -> p.getFileName().toString())
                        .orElse(null);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan saves directory for existing ArenaClash worlds", e);
            return null;
        }
    }

    /** Delete a specific world by directory name. */
    public static void deleteWorld(String worldDirName) {
        if (worldDirName == null || worldDirName.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        Path savesDir = client.getLevelSource().getBaseDir();
        Path worldDir = savesDir.resolve(worldDirName);
        if (Files.exists(worldDir) && Files.isDirectory(worldDir)) {
            try {
                deleteDirectory(worldDir);
                LOGGER.info("Deleted world: {}", worldDirName);
            } catch (IOException e) {
                LOGGER.warn("Could not delete world: {}", worldDirName, e);
            }
        }
    }

    /** Delete every ArenaClash_* save from disk. */
    public static void cleanupOldWorlds() {
        Minecraft client = Minecraft.getInstance();
        Path savesDir = client.getLevelSource().getBaseDir();
        try {
            if (!Files.exists(savesDir)) return;
            try (var dirs = Files.list(savesDir)) {
                dirs.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString()
                                .toLowerCase()
                                .startsWith(WORLD_NAME_PREFIX.toLowerCase()))
                        .forEach(p -> {
                            try {
                                deleteDirectory(p);
                                LOGGER.info("Deleted old world: {}", p.getFileName());
                            } catch (IOException e) {
                                LOGGER.warn("Could not delete world: {}", p.getFileName(), e);
                            }
                        });
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan saves directory", e);
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }
}