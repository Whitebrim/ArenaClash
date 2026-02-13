package com.arenaclash.world;

import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeWorldConfig;
import xyz.nucleoid.fantasy.RuntimeWorldHandle;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages creation and deletion of per-player worlds using the Fantasy library.
 * Creates: Arena (default overworld), 2 Overworlds, 2 Nethers, 2 Ends.
 */
public class WorldManager {
    private final MinecraftServer server;
    private Fantasy fantasy;

    // Handles for runtime worlds
    private final Map<String, RuntimeWorldHandle> worldHandles = new HashMap<>();

    // World keys per team and dimension type
    private final Map<TeamSide, Map<DimensionType, RegistryKey<World>>> worldKeys = new EnumMap<>(TeamSide.class);

    public enum DimensionType {
        OVERWORLD, NETHER, THE_END
    }

    public WorldManager(MinecraftServer server) {
        this.server = server;
        this.fantasy = Fantasy.get(server);
        worldKeys.put(TeamSide.PLAYER1, new EnumMap<>(DimensionType.class));
        worldKeys.put(TeamSide.PLAYER2, new EnumMap<>(DimensionType.class));
    }

    /**
     * Create all player worlds with the given seed.
     */
    public void createPlayerWorlds(long seed) {
        for (TeamSide team : TeamSide.values()) {
            createWorldForTeam(team, DimensionType.OVERWORLD, seed);
            createWorldForTeam(team, DimensionType.NETHER, seed);
            createWorldForTeam(team, DimensionType.THE_END, seed);
        }
    }

    private void createWorldForTeam(TeamSide team, DimensionType dimType, long seed) {
        String worldId = "arenaclash_" + team.name().toLowerCase() + "_" + dimType.name().toLowerCase();
        Identifier id = Identifier.of("arenaclash", worldId);

        RuntimeWorldConfig config = new RuntimeWorldConfig();
        config.setSeed(seed);

        switch (dimType) {
            case OVERWORLD -> {
                config.setDimensionType(DimensionTypes.OVERWORLD);
                config.setGenerator(server.getOverworld().getChunkManager().getChunkGenerator());
            }
            case NETHER -> {
                config.setDimensionType(DimensionTypes.THE_NETHER);
                ServerWorld nether = server.getWorld(World.NETHER);
                if (nether != null) {
                    config.setGenerator(nether.getChunkManager().getChunkGenerator());
                }
            }
            case THE_END -> {
                config.setDimensionType(DimensionTypes.THE_END);
                ServerWorld end = server.getWorld(World.END);
                if (end != null) {
                    config.setGenerator(end.getChunkManager().getChunkGenerator());
                }
            }
        }

        RuntimeWorldHandle handle = fantasy.getOrOpenPersistentWorld(id, config);
        worldHandles.put(worldId, handle);

        RegistryKey<World> key = handle.asWorld().getRegistryKey();
        worldKeys.get(team).put(dimType, key);

        // Set day cycle speed for overworld
        if (dimType == DimensionType.OVERWORLD) {
            handle.asWorld().getGameRules().get(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE).set(true, server);
        }
    }

    /**
     * Get the ServerWorld for a team's dimension.
     */
    public ServerWorld getWorld(TeamSide team, DimensionType dimType) {
        RegistryKey<World> key = worldKeys.get(team).get(dimType);
        if (key == null) return null;
        return server.getWorld(key);
    }

    /**
     * Get the arena world (the default overworld).
     */
    public ServerWorld getArenaWorld() {
        return server.getOverworld();
    }

    /**
     * Teleport a player to their overworld with safe spawn.
     */
    public void teleportToSurvival(ServerPlayerEntity player, TeamSide team) {
        ServerWorld world = getWorld(team, DimensionType.OVERWORLD);
        if (world == null) return;

        BlockPos spawnPos = world.getSpawnPos();
        // Find safe Y: scan down from sky to find solid ground
        BlockPos safePos = findSafeSpawn(world, spawnPos.getX(), spawnPos.getZ());
        player.teleport(world, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5,
                player.getYaw(), player.getPitch());
    }

    /**
     * Teleport a player to the arena at their team's side.
     */
    public void teleportToArena(ServerPlayerEntity player, TeamSide team) {
        ServerWorld arena = getArenaWorld();
        GameConfig cfg = GameConfig.get();

        int x = cfg.arenaCenterX;
        int y = cfg.arenaY;
        int z = cfg.arenaCenterZ;
        int halfLen = cfg.arenaLaneLength / 2;

        // P1 spawns at negative Z side, P2 at positive Z side
        int spawnZ = team == TeamSide.PLAYER1 ? z - halfLen - 15 : z + halfLen + 15;

        player.teleport(arena, x + 0.5, y + 1, spawnZ + 0.5,
                team == TeamSide.PLAYER1 ? 0 : 180, 0);
    }

    /**
     * Teleport player back to their survival world at their saved position.
     */
    public void teleportBackToSurvival(ServerPlayerEntity player, TeamSide team, BlockPos pos) {
        ServerWorld world = getWorld(team, DimensionType.OVERWORLD);
        if (world == null) return;

        if (pos != null) {
            player.teleport(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                    player.getYaw(), player.getPitch());
        } else {
            teleportToSurvival(player, team);
        }
    }

    /**
     * Find a safe spawn position by scanning downward from max height.
     * Looks for a solid block with 2 air blocks above it.
     */
    private BlockPos findSafeSpawn(ServerWorld world, int x, int z) {
        // Force-load the chunk so blocks are available
        world.getChunk(x >> 4, z >> 4);

        for (int y = world.getTopY() - 1; y > world.getBottomY(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockPos above1 = pos.up();
            BlockPos above2 = pos.up(2);
            if (!world.getBlockState(pos).isAir()
                    && world.getBlockState(above1).isAir()
                    && world.getBlockState(above2).isAir()) {
                return above1; // Stand on top of the solid block
            }
        }
        // Fallback: place at y=100
        return new BlockPos(x, 100, z);
    }

    /**
     * Delete all player worlds (for game reset).
     */
    public void deleteAllWorlds() {
        for (Map.Entry<String, RuntimeWorldHandle> entry : worldHandles.entrySet()) {
            try {
                entry.getValue().delete();
            } catch (Exception e) {
                // Log but continue
                System.err.println("Failed to delete world " + entry.getKey() + ": " + e.getMessage());
            }
        }
        worldHandles.clear();
        worldKeys.get(TeamSide.PLAYER1).clear();
        worldKeys.get(TeamSide.PLAYER2).clear();
    }

    /**
     * Check if player is in a survival world (not arena).
     */
    public boolean isInSurvivalWorld(ServerPlayerEntity player) {
        RegistryKey<World> worldKey = player.getServerWorld().getRegistryKey();
        for (TeamSide team : TeamSide.values()) {
            for (Map.Entry<DimensionType, RegistryKey<World>> entry : worldKeys.get(team).entrySet()) {
                if (entry.getValue().equals(worldKey)) return true;
            }
        }
        return false;
    }

    /**
     * Get which team owns the world the player is currently in.
     */
    public TeamSide getTeamForWorld(ServerPlayerEntity player) {
        RegistryKey<World> worldKey = player.getServerWorld().getRegistryKey();
        for (TeamSide team : TeamSide.values()) {
            for (RegistryKey<World> key : worldKeys.get(team).values()) {
                if (key.equals(worldKey)) return team;
            }
        }
        return null;
    }
}
