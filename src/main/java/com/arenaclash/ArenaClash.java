package com.arenaclash;

import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.command.GameCommands;
import com.arenaclash.config.GameConfig;
import com.arenaclash.event.GameEventHandlers;
import com.arenaclash.game.GameManager;
import com.arenaclash.network.NetworkHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArenaClash implements ModInitializer {
    public static final String MOD_ID = "arenaclash";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Arena Clash initializing...");

        // Load config
        GameConfig.load();

        // Register mob card definitions
        MobCardRegistry.init();

        // Register network packets
        NetworkHandler.registerS2CPayloads();
        NetworkHandler.registerC2SPayloads();

        // Register commands
        GameCommands.register();

        // Register event handlers
        GameEventHandlers.register();

        // Server started - initialize GameManager
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            GameManager.getInstance().init(server);
            LOGGER.info("Arena Clash GameManager initialized");
        });

        // Main game tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GameManager.getInstance().tick();
        });

        // Clean up on server stop
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (GameManager.getInstance().isGameActive()) {
                LOGGER.info("Server stopping - cleaning up Arena Clash game");
                GameManager.getInstance().resetGame();
            }
        });

        LOGGER.info("Arena Clash initialized!");
    }
}
