package com.arenaclash.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class GameConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static GameConfig INSTANCE;

    // Timing
    public int dayDurationTicks = 6000;          // 5 minutes real time = 1 MC day (6000 ticks)
    public int preparationTimeTicks = 3600;       // 3 minutes preparation (3600 ticks = 180 sec)
    public int round1Days = 1;
    public int round2Days = 2;
    public int round3Days = 3;
    public int maxRounds = 3;

    // Arena dimensions
    public int arenaLaneLength = 36;              // blocks from deployment zone to enemy deployment zone
    public int laneWidth = 5;
    public int laneSeparation = 15;               // X distance between lane centers
    public int arenaY = 100;                      // Y level of arena floor
    public int deploymentSlots = 4;               // 2x2 default slots per lane

    // Structures
    public int throneHP = 200;
    public int towerHP = 100;
    public double towerRange = 15.0;
    public double towerDamage = 3.0;
    public int towerAttackCooldown = 40;          // ticks between tower attacks
    public double throneAoeDamage = 5.0;
    public double throneAoeRange = 6.0;
    public int throneAttackCooldown = 30;

    // Combat
    public double knockbackStrength = 0.4;
    public double mobAggroRange = 8.0;

    // World
    public long gameSeed = 0;                     // 0 = random seed each game
    public boolean autoDeleteWorldsOnReset = true;

    // Arena center coordinates (in arena dimension)
    public int arenaCenterX = 0;
    public int arenaCenterZ = 0;

    // Networking
    public int tcpPort = 0;                       // 0 = MC port + 1

    // Build zones - players can only build in their build zone during preparation
    public boolean buildZonesEnabled = true;

    // Bell interaction
    public boolean bellToggleReady = true;        // Allow toggling ready via bell

    public static GameConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static void load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("arenaclash.json");
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                INSTANCE = GSON.fromJson(json, GameConfig.class);
            } catch (IOException e) {
                INSTANCE = new GameConfig();
            }
        } else {
            INSTANCE = new GameConfig();
            save();
        }
    }

    public static void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("arenaclash.json");
        try {
            Files.writeString(configPath, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getSurvivalDurationTicks(int round) {
        int days = switch (round) {
            case 1 -> round1Days;
            case 2 -> round2Days;
            case 3 -> round3Days;
            default -> round1Days;
        };
        return days * dayDurationTicks;
    }
}
