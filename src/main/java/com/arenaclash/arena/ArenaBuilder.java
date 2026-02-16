package com.arenaclash.arena;

import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Builds the physical arena structure in the world using blocks.
 * Creates: floor, lanes, walls, towers, thrones, deployment zones, divider, bells.
 */
public class ArenaBuilder {

    // Block palettes
    private static final BlockState FLOOR = Blocks.SMOOTH_STONE.getDefaultState();
    private static final BlockState LANE_FLOOR = Blocks.POLISHED_DEEPSLATE.getDefaultState();
    private static final BlockState WALL = Blocks.STONE_BRICKS.getDefaultState();
    private static final BlockState WALL_TOP = Blocks.STONE_BRICK_WALL.getDefaultState();
    private static final BlockState DIVIDER = Blocks.RED_NETHER_BRICKS.getDefaultState();
    private static final BlockState THRONE_BLOCK = Blocks.GOLD_BLOCK.getDefaultState();
    private static final BlockState THRONE_ACCENT = Blocks.CRYING_OBSIDIAN.getDefaultState();
    private static final BlockState TOWER_BLOCK = Blocks.COBBLESTONE.getDefaultState();
    private static final BlockState TOWER_TOP = Blocks.COBBLESTONE_WALL.getDefaultState();
    private static final BlockState DEPLOY_ZONE = Blocks.LIME_CONCRETE.getDefaultState();
    private static final BlockState DEPLOY_ZONE_P2 = Blocks.RED_CONCRETE.getDefaultState();
    private static final BlockState BARRIER = Blocks.BARRIER.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState GLASS_DIVIDER = Blocks.TINTED_GLASS.getDefaultState();
    private static final BlockState SIDE_FLOOR_P1 = Blocks.BLUE_CONCRETE.getDefaultState();
    private static final BlockState SIDE_FLOOR_P2 = Blocks.RED_CONCRETE.getDefaultState();
    private static final BlockState BEACON_LIGHT = Blocks.SEA_LANTERN.getDefaultState();
    private static final BlockState BELL_BLOCK = Blocks.BELL.getDefaultState();
    private static final BlockState BELL_PEDESTAL = Blocks.POLISHED_BLACKSTONE.getDefaultState();

    /**
     * Build the entire arena.
     */
    public static void buildArena(ServerWorld world) {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int laneW = cfg.laneWidth;
        int sep = cfg.laneSeparation;

        int p1BaseZ = cz - halfLen - 10;
        int p2BaseZ = cz + halfLen + 10;
        int p1DeployZ = cz - halfLen;
        int p2DeployZ = cz + halfLen;

        int leftX = cx - sep;
        int rightX = cx + sep;

        int arenaHalfWidth = sep + laneW + 10;
        int minX = cx - arenaHalfWidth;
        int maxX = cx + arenaHalfWidth;
        int minZ = p1BaseZ - 5;
        int maxZ = p2BaseZ + 5;

        // Clear area
        clearArea(world, minX, y - 1, minZ, maxX, y + 15, maxZ);

        // Floor
        fillBlock(world, FLOOR, minX, y - 1, minZ, maxX, y - 1, maxZ);

        // Lane floors
        for (int laneX : new int[]{leftX, cx, rightX}) {
            fillBlock(world, LANE_FLOOR,
                    laneX - laneW / 2, y - 1, p1DeployZ - 2,
                    laneX + laneW / 2, y - 1, p2DeployZ + 2);
        }

        // Center divider
        fillBlock(world, DIVIDER, minX, y - 1, cz - 1, maxX, y - 1, cz + 1);
        fillBlock(world, GLASS_DIVIDER, minX, y, cz, maxX, y, cz);

        // Walls
        fillBlock(world, WALL, minX - 1, y, minZ, minX - 1, y + 3, maxZ);
        fillBlock(world, WALL_TOP, minX - 1, y + 4, minZ, minX - 1, y + 4, maxZ);
        fillBlock(world, WALL, maxX + 1, y, minZ, maxX + 1, y + 3, maxZ);
        fillBlock(world, WALL_TOP, maxX + 1, y + 4, minZ, maxX + 1, y + 4, maxZ);
        fillBlock(world, WALL, minX, y, minZ - 1, maxX, y + 3, minZ - 1);
        fillBlock(world, WALL, minX, y, maxZ + 1, maxX, y + 3, maxZ + 1);

        // Lane side walls
        for (int laneX : new int[]{leftX, cx, rightX}) {
            int wallLeft = laneX - laneW / 2 - 1;
            int wallRight = laneX + laneW / 2 + 1;
            fillBlock(world, WALL, wallLeft, y, p1DeployZ + 2, wallLeft, y, p2DeployZ - 2);
            fillBlock(world, WALL, wallRight, y, p1DeployZ + 2, wallRight, y, p2DeployZ - 2);
        }

        // Deployment zones
        for (int laneX : new int[]{leftX, cx, rightX}) {
            fillBlock(world, DEPLOY_ZONE, laneX - 1, y - 1, p1DeployZ, laneX, y - 1, p1DeployZ + 1);
            fillBlock(world, DEPLOY_ZONE_P2, laneX - 1, y - 1, p2DeployZ - 1, laneX, y - 1, p2DeployZ);
        }

        // Player side areas
        fillBlock(world, SIDE_FLOOR_P1, minX, y - 1, minZ, maxX, y - 1, p1DeployZ - 3);
        fillBlock(world, SIDE_FLOOR_P2, minX, y - 1, p2DeployZ + 3, maxX, y - 1, maxZ);

        // Thrones
        buildThrone(world, cx, y, p1BaseZ, TeamSide.PLAYER1);
        buildThrone(world, cx, y, p2BaseZ, TeamSide.PLAYER2);

        // Towers
        buildTower(world, leftX, y, p1BaseZ + 3, TeamSide.PLAYER1);
        buildTower(world, rightX, y, p1BaseZ + 3, TeamSide.PLAYER1);
        buildTower(world, leftX, y, p2BaseZ - 3, TeamSide.PLAYER2);
        buildTower(world, rightX, y, p2BaseZ - 3, TeamSide.PLAYER2);

        // Bells near thrones (to the side, not blocking mob paths)
        buildBellPedestal(world, cx + 3, y, p1BaseZ);
        buildBellPedestal(world, cx + 3, y, p2BaseZ);

        // Lighting
        for (int z = minZ; z <= maxZ; z += 6) {
            world.setBlockState(new BlockPos(minX, y + 2, z), BEACON_LIGHT);
            world.setBlockState(new BlockPos(maxX, y + 2, z), BEACON_LIGHT);
        }
        for (int x = minX; x <= maxX; x += 6) {
            world.setBlockState(new BlockPos(x, y + 2, minZ), BEACON_LIGHT);
            world.setBlockState(new BlockPos(x, y + 2, maxZ), BEACON_LIGHT);
        }

        // Invisible ceiling barrier
        fillBlock(world, BARRIER, minX, y + 12, minZ, maxX, y + 12, maxZ);
    }

    /**
     * Build a bell on a small pedestal.
     */
    private static void buildBellPedestal(ServerWorld world, int x, int y, int z) {
        world.setBlockState(new BlockPos(x, y, z), BELL_PEDESTAL);
        world.setBlockState(new BlockPos(x, y + 1, z), BELL_BLOCK);
    }

    /**
     * Build a throne structure.
     */
    private static void buildThrone(ServerWorld world, int cx, int y, int cz, TeamSide team) {
        fillBlock(world, THRONE_ACCENT, cx - 2, y, cz - 2, cx + 2, y, cz + 2);
        fillBlock(world, THRONE_BLOCK, cx - 1, y + 1, cz - 1, cx + 1, y + 3, cz + 1);
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                world.setBlockState(new BlockPos(cx + dx, y + 4, cz + dz), THRONE_ACCENT);
            }
        }
        world.setBlockState(new BlockPos(cx, y + 4, cz), BEACON_LIGHT);
        for (int dx : new int[]{-2, 2}) {
            for (int dz : new int[]{-2, 2}) {
                fillBlock(world, THRONE_ACCENT, cx + dx, y + 1, cz + dz, cx + dx, y + 3, cz + dz);
            }
        }
    }

    /**
     * Build a defensive tower.
     */
    private static void buildTower(ServerWorld world, int cx, int y, int cz, TeamSide team) {
        fillBlock(world, TOWER_BLOCK, cx - 1, y, cz - 1, cx + 1, y, cz + 1);
        for (int height = 1; height <= 3; height++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    world.setBlockState(new BlockPos(cx + dx, y + height, cz + dz), TOWER_BLOCK);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                world.setBlockState(new BlockPos(cx + dx, y + 4, cz + dz), TOWER_TOP);
            }
        }
        world.setBlockState(new BlockPos(cx, y + 3, cz), BEACON_LIGHT);
    }

    public static void clearArena(ServerWorld world) {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int sep = cfg.laneSeparation;
        int arenaHalfWidth = sep + cfg.laneWidth + 10;

        clearArea(world,
                cx - arenaHalfWidth - 2, y - 2, cz - halfLen - 16,
                cx + arenaHalfWidth + 2, y + 16, cz + halfLen + 16);
    }

    private static void fillBlock(ServerWorld world, BlockState state,
                                   int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    world.setBlockState(new BlockPos(x, y, z), state);
    }

    private static void clearArea(ServerWorld world, int x1, int y1, int z1, int x2, int y2, int z2) {
        fillBlock(world, AIR, x1, y1, z1, x2, y2, z2);
    }
}
