package com.arenaclash;

import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.command.ArenaSetupCommands;
import com.arenaclash.command.GameCommands;
import com.arenaclash.config.GameConfig;
import com.arenaclash.event.GameEventHandlers;
import com.arenaclash.game.GameManager;
import com.arenaclash.network.NetworkHandler;
import com.arenaclash.tcp.ArenaClashTcpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArenaClash implements ModInitializer {
    public static final String MOD_ID = "arenaclash";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ArenaClashTcpServer tcpServer;

    @Override
    public void onInitialize() {
        LOGGER.info("Arena Clash initializing...");

        GameConfig.load();
        MobCardRegistry.init();
        NetworkHandler.registerS2CPayloads();
        NetworkHandler.registerC2SPayloads();
        GameCommands.register();
        ArenaSetupCommands.register();
        GameEventHandlers.register();

        // Server started → init GameManager + start TCP server
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            GameManager.getInstance().init(server);

            // Start TCP server on MC port + 1
            GameConfig cfg = GameConfig.get();
            int mcPort = server.getServerPort();
            if (mcPort <= 0) mcPort = 25565; // singleplayer/default
            int tcpPort = cfg.tcpPort > 0 ? cfg.tcpPort : mcPort + 1;

            try {
                tcpServer = new ArenaClashTcpServer(tcpPort, mcPort);
                tcpServer.start();
                GameManager.getInstance().setTcpServer(tcpServer);
                LOGGER.info("Arena Clash TCP server started on port {}", tcpPort);
            } catch (Exception e) {
                LOGGER.error("Failed to start TCP server on port {}", tcpPort, e);
            }

            LOGGER.info("Arena Clash GameManager initialized");
        });

        // Main game tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GameManager.getInstance().tick();
        });

        // Clean up on server stop
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (tcpServer != null) {
                tcpServer.stop();
                LOGGER.info("Arena Clash TCP server stopped");
            }
            if (GameManager.getInstance().isGameActive()) {
                GameManager.getInstance().resetGame();
            }
        });

        LOGGER.info("Arena Clash initialized!");
    }

    public static ArenaClashTcpServer getTcpServer() {
        return tcpServer;
    }
}
