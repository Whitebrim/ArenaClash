package com.arenaclash.arena;

import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Builds the physical arena structure in the world using blocks.
 * Creates: floor, lanes, walls, towers, thrones, deployment zones, and divider.
 *
 * Arena layout (looking from above, Z axis forward):
 *
 *   P1 Side (negative Z):
 *     [Throne P1]  y+5 structure at center
 *     [Tower L]         [Tower R]
 *     [Deploy L] [Deploy C] [Deploy R]
 *   --------DIVIDER (center Z)--------
 *     [Deploy L] [Deploy C] [Deploy R]
 *     [Tower L]         [Tower R]
 *     [Throne P2]
 *   P2 Side (positive Z):
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
    private static final BlockState TOWER_SLAB = Blocks.COBBLESTONE_SLAB.getDefaultState();
    private static final BlockState DEPLOY_ZONE = Blocks.LIME_CONCRETE.getDefaultState();
    private static final BlockState DEPLOY_ZONE_P2 = Blocks.RED_CONCRETE.getDefaultState();
    private static final BlockState BARRIER = Blocks.BARRIER.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState GLASS_DIVIDER = Blocks.TINTED_GLASS.getDefaultState();
    private static final BlockState SIDE_FLOOR_P1 = Blocks.BLUE_CONCRETE.getDefaultState();
    private static final BlockState SIDE_FLOOR_P2 = Blocks.RED_CONCRETE.getDefaultState();
    private static final BlockState BEACON_LIGHT = Blocks.SEA_LANTERN.getDefaultState();

    /**
     * Build the entire arena. Call once when a game starts.
     */
    public static void buildArena(ServerWorld world) {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int laneW = cfg.laneWidth;
        int sep = cfg.laneSeparation;

        // Derived positions
        int p1BaseZ = cz - halfLen - 10;
        int p2BaseZ = cz + halfLen + 10;
        int p1DeployZ = cz - halfLen;
        int p2DeployZ = cz + halfLen;

        int leftX = cx - sep;
        int rightX = cx + sep;

        // Total arena width & length
        int arenaHalfWidth = sep + laneW + 10;
        int minX = cx - arenaHalfWidth;
        int maxX = cx + arenaHalfWidth;
        int minZ = p1BaseZ - 5;
        int maxZ = p2BaseZ + 5;

        // === Clear the area ===
        clearArea(world, minX, y - 1, minZ, maxX, y + 15, maxZ);

        // === Build floor ===
        fillBlock(world, FLOOR, minX, y - 1, minZ, maxX, y - 1, maxZ);

        // === Build lane floors ===
        for (int laneX : new int[]{leftX, cx, rightX}) {
            fillBlock(world, LANE_FLOOR,
                    laneX - laneW / 2, y - 1, p1DeployZ - 2,
                    laneX + laneW / 2, y - 1, p2DeployZ + 2);
        }

        // === Center divider line ===
        fillBlock(world, DIVIDER, minX, y - 1, cz - 1, maxX, y - 1, cz + 1);
        // Glass wall divider (2 blocks high for visual, but passable by mobs)
        fillBlock(world, GLASS_DIVIDER, minX, y, cz, maxX, y, cz);

        // === Side walls ===
        // Left wall
        fillBlock(world, WALL, minX - 1, y, minZ, minX - 1, y + 3, maxZ);
        fillBlock(world, WALL_TOP, minX - 1, y + 4, minZ, minX - 1, y + 4, maxZ);
        // Right wall
        fillBlock(world, WALL, maxX + 1, y, minZ, maxX + 1, y + 3, maxZ);
        fillBlock(world, WALL_TOP, maxX + 1, y + 4, minZ, maxX + 1, y + 4, maxZ);
        // End walls
        fillBlock(world, WALL, minX, y, minZ - 1, maxX, y + 3, minZ - 1);
        fillBlock(world, WALL, minX, y, maxZ + 1, maxX, y + 3, maxZ + 1);

        // === Lane side walls (between lanes, low walls) ===
        for (int laneX : new int[]{leftX, cx, rightX}) {
            int wallLeft = laneX - laneW / 2 - 1;
            int wallRight = laneX + laneW / 2 + 1;
            // Low walls along lanes (only 1 block high)
            fillBlock(world, WALL, wallLeft, y, p1DeployZ + 2, wallLeft, y, p2DeployZ - 2);
            fillBlock(world, WALL, wallRight, y, p1DeployZ + 2, wallRight, y, p2DeployZ - 2);
        }

        // === Deployment zones ===
        for (int laneX : new int[]{leftX, cx, rightX}) {
            // P1 deploy zone (2x2)
            fillBlock(world, DEPLOY_ZONE,
                    laneX - 1, y - 1, p1DeployZ,
                    laneX, y - 1, p1DeployZ + 1);
            // P2 deploy zone (2x2)
            fillBlock(world, DEPLOY_ZONE_P2,
                    laneX - 1, y - 1, p2DeployZ - 1,
                    laneX, y - 1, p2DeployZ);
        }

        // === Player side areas (for free building) ===
        // P1 side floor coloring
        fillBlock(world, SIDE_FLOOR_P1, minX, y - 1, minZ, maxX, y - 1, p1DeployZ - 3);
        // P2 side floor coloring
        fillBlock(world, SIDE_FLOOR_P2, minX, y - 1, p2DeployZ + 3, maxX, y - 1, maxZ);

        // === Build Thrones ===
        buildThrone(world, cx, y, p1BaseZ, TeamSide.PLAYER1);
        buildThrone(world, cx, y, p2BaseZ, TeamSide.PLAYER2);

        // === Build Towers ===
        buildTower(world, leftX, y, p1BaseZ + 3, TeamSide.PLAYER1);
        buildTower(world, rightX, y, p1BaseZ + 3, TeamSide.PLAYER1);
        buildTower(world, leftX, y, p2BaseZ - 3, TeamSide.PLAYER2);
        buildTower(world, rightX, y, p2BaseZ - 3, TeamSide.PLAYER2);

        // === Lighting ===
        // Place sea lanterns along walls periodically
        for (int z = minZ; z <= maxZ; z += 6) {
            world.setBlockState(new BlockPos(minX, y + 2, z), BEACON_LIGHT);
            world.setBlockState(new BlockPos(maxX, y + 2, z), BEACON_LIGHT);
        }
        for (int x = minX; x <= maxX; x += 6) {
            world.setBlockState(new BlockPos(x, y + 2, minZ), BEACON_LIGHT);
            world.setBlockState(new BlockPos(x, y + 2, maxZ), BEACON_LIGHT);
        }

        // === Invisible ceiling barrier (prevent flying out) ===
        fillBlock(world, BARRIER, minX, y + 12, minZ, maxX, y + 12, maxZ);
    }

    /**
     * Build a throne structure at the given position.
     * 5x5 base, 5 blocks tall, with gold core and obsidian accents.
     */
    private static void buildThrone(ServerWorld world, int cx, int y, int cz, TeamSide team) {
        // Base platform (5x5)
        fillBlock(world, THRONE_ACCENT, cx - 2, y, cz - 2, cx + 2, y, cz + 2);

        // Core pillar (3x3, 3 tall)
        fillBlock(world, THRONE_BLOCK, cx - 1, y + 1, cz - 1, cx + 1, y + 3, cz + 1);

        // Top crown (corners)
        for (int dx = -2; dx <= 2; dx += 4) {
            for (int dz = -2; dz <= 2; dz += 4) {
                world.setBlockState(new BlockPos(cx + dx / 2, y + 4, cz + dz / 2), THRONE_ACCENT);
            }
        }
        // Top center beacon
        world.setBlockState(new BlockPos(cx, y + 4, cz), BEACON_LIGHT);

        // Corner pillars
        for (int dx : new int[]{-2, 2}) {
            for (int dz : new int[]{-2, 2}) {
                fillBlock(world, THRONE_ACCENT, cx + dx, y + 1, cz + dz, cx + dx, y + 3, cz + dz);
            }
        }
    }

    /**
     * Build a defensive tower at the given position.
     * 3x3 base, 4 blocks tall.
     */
    private static void buildTower(ServerWorld world, int cx, int y, int cz, TeamSide team) {
        // Base (3x3)
        fillBlock(world, TOWER_BLOCK, cx - 1, y, cz - 1, cx + 1, y, cz + 1);

        // Walls (3x3, 3 tall, hollow inside)
        for (int height = 1; height <= 3; height++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue; // Hollow center
                    world.setBlockState(new BlockPos(cx + dx, y + height, cz + dz), TOWER_BLOCK);
                }
            }
        }

        // Top crenellations
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                world.setBlockState(new BlockPos(cx + dx, y + 4, cz + dz), TOWER_TOP);
            }
        }

        // Light at top
        world.setBlockState(new BlockPos(cx, y + 3, cz), BEACON_LIGHT);
    }

    /**
     * Clear all blocks in an area.
     */
    public static void clearArena(ServerWorld world) {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y = cfg.arenaY;
        int halfLen = cfg.arenaLaneLength / 2;
        int sep = cfg.laneSeparation;
        int arenaHalfWidth = sep + cfg.laneWidth + 10;

        int minX = cx - arenaHalfWidth - 2;
        int maxX = cx + arenaHalfWidth + 2;
        int minZ = cz - halfLen - 16;
        int maxZ = cz + halfLen + 16;

        clearArea(world, minX, y - 2, minZ, maxX, y + 16, maxZ);
    }

    // === Helper methods ===

    private static void fillBlock(ServerWorld world, BlockState state,
                                   int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.setBlockState(new BlockPos(x, y, z), state);
                }
            }
        }
    }

    private static void clearArea(ServerWorld world,
                                   int x1, int y1, int z1, int x2, int y2, int z2) {
        fillBlock(world, AIR, x1, y1, z1, x2, y2, z2);
    }
}
