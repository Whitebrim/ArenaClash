package com.arenaclash.client.world;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
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
 * - Round 1: Open CreateWorldScreen invisibly, configure via WorldCreator,
 *   then call the private createLevel() through an @Invoker accessor mixin.
 * - Round 2+: Reload the same world via IntegratedServerLoader.start()
 * - After world loads: apply game rules (Hard difficulty, keepInventory, etc.)
 */
public class WorldCreationHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-WorldCreation");
    public static final String WORLD_NAME_PREFIX = "ArenaClash_";

    // --- Pending creation request ---
    private static volatile boolean creationPending = false;
    private static volatile long pendingSeed = 0;
    private static volatile int pendingRound = 1;

    // --- Auto-submit state machine ---
    // After we call CreateWorldScreen.create(), the screen needs a few frames
    // to fully initialise its WorldCreator.  We wait, then configure + submit.
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

    /** Schedule world creation/loading.  Safe to call from any thread. */
    public static void scheduleWorldCreation(long seed, int round) {
        pendingSeed = seed;
        pendingRound = round;
        currentGameSeed = seed;
        creationPending = true;
        gameRulesApplied = false;
    }

    /** Must be called every client tick from ArenaClashClient.onTick(). */
    public static void tickPending(MinecraftClient client) {
        // ---- auto-submit state machine ----
        if (autoSubmitPending) {
            if (--autoSubmitTicksLeft <= 0) {
                autoSubmitPending = false;
                doAutoSubmit(client);
            }
            return;                             // don't process creation while submitting
        }

        // ---- pending creation request ----
        if (!creationPending) {
            // Still tick: apply game rules once when world finishes loading
            applyGameRulesOnceIfNeeded(client);
            return;
        }
        creationPending = false;

        long seed  = pendingSeed;
        int  round = pendingRound;

        // Already inside an ArenaClash world?  (possible round 2+)
        if (client.isInSingleplayer() && client.getServer() != null) {
            String levelName = client.getServer().getSaveProperties().getLevelName();
            if (levelName != null && levelName.startsWith(WORLD_NAME_PREFIX)) {
                LOGGER.info("Already in ArenaClash world '{}' — continuing round {}", levelName, round);
                applyGameRules(client);
                return;
            }
        }

        // Round 2+: just reload the world created in round 1
        if (round > 1 && currentWorldDirName != null) {
            LOGGER.info("Reloading world '{}' for round {}", currentWorldDirName, round);
            loadExistingWorld(client, currentWorldDirName);
            return;
        }

        // Round 1: create a brand-new world
        String worldName = WORLD_NAME_PREFIX + System.currentTimeMillis();
        LOGGER.info("Creating new world '{}' seed={} round={}", worldName, seed, round);
        beginWorldCreation(client, worldName, seed);
    }

    // ====================================================================
    // WORLD CREATION  (uses CreateWorldScreen + @Invoker accessor)
    // ====================================================================

    private static void beginWorldCreation(MinecraftClient client, String worldName, long seed) {
        // 1. Disconnect from whatever we're in now
        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect();

        // 2. Prepare the auto-submit state machine
        autoSubmitWorldName = worldName;
        autoSubmitSeed      = seed;
        autoSubmitPending   = true;
        autoSubmitTicksLeft = 8;               // ~8 ticks for CreateWorldScreen to init fully

        // 3. Open CreateWorldScreen on the next frame (after disconnect settles)
        client.execute(() -> {
            try {
                CreateWorldScreen.create(client, new TitleScreen());
            } catch (Exception e) {
                LOGGER.error("Failed to open CreateWorldScreen", e);
                autoSubmitPending = false;
            }
        });
    }

    /**
     * Called when autoSubmitTicksLeft reaches 0.
     * By now, CreateWorldScreen.init() has run and WorldCreator is ready.
     */
    private static void doAutoSubmit(MinecraftClient client) {
        if (!(client.currentScreen instanceof CreateWorldScreen createScreen)) {
            LOGGER.error("Expected CreateWorldScreen but got {} — aborting auto-create",
                    client.currentScreen);
            return;
        }

        try {
            WorldCreator creator = createScreen.getWorldCreator();
            if (creator == null) {
                LOGGER.error("WorldCreator is null on CreateWorldScreen");
                return;
            }

            // Configure everything the player would normally set manually
            creator.setWorldName(autoSubmitWorldName);
            creator.setSeed(String.valueOf(autoSubmitSeed));
            creator.setGameMode(WorldCreator.Mode.SURVIVAL);
            creator.setDifficulty(Difficulty.HARD);
            creator.setCheatsEnabled(true);     // needed for /gamerule

            LOGGER.info("Configured WorldCreator: name={}, seed={}, SURVIVAL, HARD, cheats=on",
                    autoSubmitWorldName, autoSubmitSeed);

            // Remember the dir name so we can reload in later rounds
            currentWorldDirName = autoSubmitWorldName;

            // Trigger the full Mojang world-creation pipeline (datapacks, registries,
            // worldgen, level.dat, etc.) — method made accessible via arenaclash.accesswidener
            createScreen.createLevel();
            LOGGER.info("createLevel() invoked — world creation started");

        } catch (Exception e) {
            LOGGER.error("Failed to auto-submit CreateWorldScreen", e);
            client.setScreen(new TitleScreen());
        }
    }

    // ====================================================================
    // WORLD RELOAD  (round 2+)
    // ====================================================================

    private static void loadExistingWorld(MinecraftClient client, String dirName) {
        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect();

        // small delay for disconnect to flush, then reload
        client.execute(() -> {
            client.execute(() -> {                  // double-execute = 2-tick delay
                try {
                    client.createIntegratedServerLoader().start(dirName, () -> {
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
    private static void applyGameRulesOnceIfNeeded(MinecraftClient client) {
        if (gameRulesApplied) return;
        if (!client.isInSingleplayer() || client.getServer() == null) return;
        // Wait until the server is actually running
        if (!client.getServer().isRunning()) return;

        String levelName = client.getServer().getSaveProperties().getLevelName();
        if (levelName != null && levelName.startsWith(WORLD_NAME_PREFIX)) {
            applyGameRules(client);
            gameRulesApplied = true;
        }
    }

    /** Force-apply all ArenaClash game rules right now. */
    public static void applyGameRules(MinecraftClient client) {
        if (!client.isInSingleplayer() || client.getServer() == null) return;

        var server = client.getServer();
        server.execute(() -> {
            try {
                server.setDifficulty(Difficulty.HARD, true);

                var gr = server.getGameRules();
                gr.get(GameRules.DO_DAYLIGHT_CYCLE).set(true, server);
                gr.get(GameRules.KEEP_INVENTORY).set(true, server);

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

    /** Delete every ArenaClash_* save from disk. */
    public static void cleanupOldWorlds() {
        MinecraftClient client = MinecraftClient.getInstance();
        Path savesDir = client.getLevelStorage().getSavesDirectory();
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