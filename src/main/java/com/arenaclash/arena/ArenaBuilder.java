package com.arenaclash.arena;

import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automatically builds the arena in the world and produces an {@link ArenaDefinition}
 * with all waypoints, deployment slots, structure bounds, bells, etc.
 *
 * This replaces the manual JSON-based builder flow: instead of requiring the user
 * to walk around placing waypoints, we programmatically generate everything.
 *
 * The arena layout:
 *
 *   [ P1 Base / Throne ]
 *   [ P1 Towers (L/R) ]
 *   [ P1 Deploy Zone   ]
 *        |  |  |           3 lanes (LEFT, CENTER, RIGHT)
 *        |  |  |           waypoints every 2 blocks along Z
 *   [ P2 Deploy Zone   ]
 *   [ P2 Towers (L/R)  ]
 *   [ P2 Base / Throne ]
 *
 * Lanes run along the Z axis. P1 is at negative Z, P2 at positive Z.
 */
public class ArenaBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-Builder");

    // Block palette
    private static final BlockState FLOOR           = Blocks.SMOOTH_STONE.getDefaultState();
    private static final BlockState LANE_LEFT       = Blocks.CYAN_CONCRETE.getDefaultState();
    private static final BlockState LANE_CENTER     = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
    private static final BlockState LANE_RIGHT      = Blocks.ORANGE_CONCRETE.getDefaultState();
    private static final BlockState WALL            = Blocks.STONE_BRICKS.getDefaultState();
    private static final BlockState WALL_TOP        = Blocks.STONE_BRICK_SLAB.getDefaultState();
    private static final BlockState THRONE_BLOCK    = Blocks.OBSIDIAN.getDefaultState();
    private static final BlockState THRONE_ACCENT   = Blocks.CRYING_OBSIDIAN.getDefaultState();
    private static final BlockState TOWER_BASE      = Blocks.DEEPSLATE_BRICKS.getDefaultState();
    private static final BlockState TOWER_PILLAR    = Blocks.DEEPSLATE_BRICK_WALL.getDefaultState();
    private static final BlockState TOWER_TOP       = Blocks.DEEPSLATE_BRICK_SLAB.getDefaultState();
    private static final BlockState DEPLOY_P1       = Blocks.BLUE_CONCRETE.getDefaultState();
    private static final BlockState DEPLOY_P2       = Blocks.RED_CONCRETE.getDefaultState();
    private static final BlockState GLASS_BARRIER   = Blocks.GLASS.getDefaultState();
    private static final BlockState BASE_FLOOR_P1   = Blocks.BLUE_TERRACOTTA.getDefaultState();
    private static final BlockState BASE_FLOOR_P2   = Blocks.RED_TERRACOTTA.getDefaultState();
    private static final BlockState BELL_BLOCK      = Blocks.BELL.getDefaultState();

    /**
     * Build the entire arena in the given world and return the matching ArenaDefinition.
     * All positions in the definition correspond exactly to placed blocks.
     */
    public static ArenaDefinition buildArena(ServerWorld world) {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y  = cfg.arenaY;
        int laneLength = cfg.arenaLaneLength;
        int laneSep    = cfg.laneSeparation;

        return buildArena(world, cx, cz, y, laneLength, laneSep);
    }

    /**
     * Build the arena with explicit parameters.
     */
    public static ArenaDefinition buildArena(ServerWorld world, int cx, int cz, int y,
                                              int laneLength, int laneSep) {
        LOGGER.info("Building arena at ({},{},{}) laneLen={} laneSep={}", cx, y, cz, laneLength, laneSep);

        ArenaDefinition def = new ArenaDefinition();

        int halfLen    = laneLength / 2;
        int laneWidth  = 5;  // blocks wide per lane
        int halfLane   = laneWidth / 2;

        // Key Z positions
        int p1DeployZ  = cz - halfLen;            // P1 deployment line
        int p2DeployZ  = cz + halfLen;            // P2 deployment line
        int p1BaseZ    = p1DeployZ - 12;          // P1 base/throne area
        int p2BaseZ    = p2DeployZ + 12;          // P2 base/throne area
        int p1TowerZ   = p1DeployZ - 5;           // P1 tower line
        int p2TowerZ   = p2DeployZ + 5;           // P2 tower line

        // Lane X positions
        int leftX   = cx - laneSep;
        int centerX = cx;
        int rightX  = cx + laneSep;
        int[] laneXs = { leftX, centerX, rightX };
        Lane.LaneId[] laneIds = { Lane.LaneId.LEFT, Lane.LaneId.CENTER, Lane.LaneId.RIGHT };
        BlockState[] laneBlocks = { LANE_LEFT, LANE_CENTER, LANE_RIGHT };

        // Arena width
        int arenaHalfWidth = laneSep + halfLane + 8;

        // ====================================================================
        // 1) CLEAR AREA — fill with air, then lay floor
        // ====================================================================
        for (int bx = cx - arenaHalfWidth; bx <= cx + arenaHalfWidth; bx++) {
            for (int bz = p1BaseZ - 5; bz <= p2BaseZ + 5; bz++) {
                // Floor
                world.setBlockState(new BlockPos(bx, y, bz), FLOOR, 2);
                // Clear above (8 blocks)
                for (int by = y + 1; by <= y + 8; by++) {
                    world.setBlockState(new BlockPos(bx, by, bz), Blocks.AIR.getDefaultState(), 2);
                }
                // Foundation below
                world.setBlockState(new BlockPos(bx, y - 1, bz), Blocks.STONE.getDefaultState(), 2);
            }
        }

        // ====================================================================
        // 2) BASE FLOORS (colored per team)
        // ====================================================================
        for (int bx = cx - arenaHalfWidth; bx <= cx + arenaHalfWidth; bx++) {
            for (int bz = p1BaseZ - 5; bz < p1DeployZ; bz++) {
                world.setBlockState(new BlockPos(bx, y, bz), BASE_FLOOR_P1, 2);
            }
            for (int bz = p2DeployZ + 1; bz <= p2BaseZ + 5; bz++) {
                world.setBlockState(new BlockPos(bx, y, bz), BASE_FLOOR_P2, 2);
            }
        }

        // ====================================================================
        // 3) LANE PATHS (colored strips along Z axis)
        // ====================================================================
        for (int i = 0; i < 3; i++) {
            int lx = laneXs[i];
            BlockState block = laneBlocks[i];
            for (int bz = p1DeployZ; bz <= p2DeployZ; bz++) {
                for (int bx = lx - halfLane; bx <= lx + halfLane; bx++) {
                    world.setBlockState(new BlockPos(bx, y, bz), block, 2);
                }
            }
        }

        // ====================================================================
        // 4) WALLS between lanes and on arena edges
        // ====================================================================
        // Outer walls
        buildWall(world, cx - arenaHalfWidth, y, p1BaseZ - 5, cx - arenaHalfWidth, y, p2BaseZ + 5, 3);
        buildWall(world, cx + arenaHalfWidth, y, p1BaseZ - 5, cx + arenaHalfWidth, y, p2BaseZ + 5, 3);
        // Back walls
        buildWall(world, cx - arenaHalfWidth, y, p1BaseZ - 5, cx + arenaHalfWidth, y, p1BaseZ - 5, 3);
        buildWall(world, cx - arenaHalfWidth, y, p2BaseZ + 5, cx + arenaHalfWidth, y, p2BaseZ + 5, 3);

        // Inter-lane glass barriers (only in the battle area, not in bases)
        for (int i = 0; i < 3; i++) {
            int lx = laneXs[i];
            // Left barrier of lane
            for (int bz = p1DeployZ; bz <= p2DeployZ; bz++) {
                int wx = lx - halfLane - 1;
                if (isNotOnAnotherLane(wx, laneXs, halfLane)) {
                    world.setBlockState(new BlockPos(wx, y + 1, bz), GLASS_BARRIER, 2);
                }
            }
            // Right barrier of lane
            for (int bz = p1DeployZ; bz <= p2DeployZ; bz++) {
                int wx = lx + halfLane + 1;
                if (isNotOnAnotherLane(wx, laneXs, halfLane)) {
                    world.setBlockState(new BlockPos(wx, y + 1, bz), GLASS_BARRIER, 2);
                }
            }
        }

        // ====================================================================
        // 5) DEPLOYMENT ZONES (colored pads)
        // ====================================================================
        for (int i = 0; i < 3; i++) {
            int lx = laneXs[i];
            // P1 deploy pads (4 slots: 2x2 grid)
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    world.setBlockState(new BlockPos(lx - 1 + dx, y, p1DeployZ + dz), DEPLOY_P1, 2);
                }
            }
            // P2 deploy pads
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    world.setBlockState(new BlockPos(lx - 1 + dx, y, p2DeployZ - 1 + dz), DEPLOY_P2, 2);
                }
            }
        }

        // ====================================================================
        // 6) TOWERS (3-block pillar with platform top)
        // ====================================================================
        // P1 towers: one near LEFT lane, one near RIGHT lane
        BlockPos p1TowerLeft  = buildTower(world, leftX,  y, p1TowerZ);
        BlockPos p1TowerRight = buildTower(world, rightX, y, p1TowerZ);
        // P2 towers
        BlockPos p2TowerLeft  = buildTower(world, leftX,  y, p2TowerZ);
        BlockPos p2TowerRight = buildTower(world, rightX, y, p2TowerZ);

        // ====================================================================
        // 7) THRONES (larger obsidian structure)
        // ====================================================================
        buildThrone(world, cx, y, p1BaseZ, TeamSide.PLAYER1);
        buildThrone(world, cx, y, p2BaseZ, TeamSide.PLAYER2);

        // ====================================================================
        // 8) BELL blocks (next to thrones)
        // ====================================================================
        BlockPos bell1Pos = new BlockPos(cx + 4, y + 1, p1BaseZ);
        BlockPos bell2Pos = new BlockPos(cx + 4, y + 1, p2BaseZ);
        world.setBlockState(bell1Pos, BELL_BLOCK, 3);
        world.setBlockState(bell2Pos, BELL_BLOCK, 3);

        // ====================================================================
        // 9) LIGHTING (sea lanterns embedded in floor every 6 blocks)
        // ====================================================================
        for (int bx = cx - arenaHalfWidth + 2; bx <= cx + arenaHalfWidth - 2; bx += 6) {
            for (int bz = p1BaseZ - 3; bz <= p2BaseZ + 3; bz += 6) {
                world.setBlockState(new BlockPos(bx, y, bz), Blocks.SEA_LANTERN.getDefaultState(), 2);
            }
        }

        // ====================================================================
        // BUILD THE ARENA DEFINITION
        // ====================================================================

        // Spawns
        def.setPlayerSpawn(TeamSide.PLAYER1, new Vec3d(cx + 0.5, y + 1, p1BaseZ - 3 + 0.5));
        def.setPlayerSpawn(TeamSide.PLAYER2, new Vec3d(cx + 0.5, y + 1, p2BaseZ + 3 + 0.5));

        // Thrones
        ArenaDefinition.StructureDef throne1 = new ArenaDefinition.StructureDef(
                new BlockPos(cx, y, p1BaseZ),
                new BlockPos(cx - 2, y, p1BaseZ - 2),
                new BlockPos(cx + 2, y + 5, p1BaseZ + 2),
                200.0
        );
        throne1.attackRange = 6.0;
        throne1.attackDamage = 5.0;
        throne1.attackCooldown = 30;
        def.setThrone(TeamSide.PLAYER1, throne1);

        ArenaDefinition.StructureDef throne2 = new ArenaDefinition.StructureDef(
                new BlockPos(cx, y, p2BaseZ),
                new BlockPos(cx - 2, y, p2BaseZ - 2),
                new BlockPos(cx + 2, y + 5, p2BaseZ + 2),
                200.0
        );
        throne2.attackRange = 6.0;
        throne2.attackDamage = 5.0;
        throne2.attackCooldown = 30;
        def.setThrone(TeamSide.PLAYER2, throne2);

        // Towers
        addTowerDef(def, TeamSide.PLAYER1, leftX,  y, p1TowerZ, Lane.LaneId.LEFT);
        addTowerDef(def, TeamSide.PLAYER1, rightX, y, p1TowerZ, Lane.LaneId.RIGHT);
        addTowerDef(def, TeamSide.PLAYER2, leftX,  y, p2TowerZ, Lane.LaneId.LEFT);
        addTowerDef(def, TeamSide.PLAYER2, rightX, y, p2TowerZ, Lane.LaneId.RIGHT);

        // Lanes + waypoints + deployment slots
        for (int i = 0; i < 3; i++) {
            int lx = laneXs[i];
            Lane.LaneId laneId = laneIds[i];

            ArenaDefinition.LaneDef laneDef = new ArenaDefinition.LaneDef(laneId);
            laneDef.width = laneWidth;

            // Waypoints from P1 side → P2 side (every 2 blocks along Z)
            for (int z = p1DeployZ; z <= p2DeployZ; z += 2) {
                laneDef.waypointsP1toP2.add(new Vec3d(lx + 0.5, y + 1, z + 0.5));
            }
            // Ensure we reach the end exactly
            Vec3d lastWp = laneDef.waypointsP1toP2.get(laneDef.waypointsP1toP2.size() - 1);
            if (lastWp.z < p2DeployZ + 0.5) {
                laneDef.waypointsP1toP2.add(new Vec3d(lx + 0.5, y + 1, p2DeployZ + 0.5));
            }

            // Deployment slots P1 (4 slots in 2x2)
            laneDef.deploymentSlotsP1.add(new BlockPos(lx - 1, y, p1DeployZ));
            laneDef.deploymentSlotsP1.add(new BlockPos(lx,     y, p1DeployZ));
            laneDef.deploymentSlotsP1.add(new BlockPos(lx - 1, y, p1DeployZ + 1));
            laneDef.deploymentSlotsP1.add(new BlockPos(lx,     y, p1DeployZ + 1));

            // Deployment slots P2 (4 slots in 2x2)
            laneDef.deploymentSlotsP2.add(new BlockPos(lx - 1, y, p2DeployZ));
            laneDef.deploymentSlotsP2.add(new BlockPos(lx,     y, p2DeployZ));
            laneDef.deploymentSlotsP2.add(new BlockPos(lx - 1, y, p2DeployZ - 1));
            laneDef.deploymentSlotsP2.add(new BlockPos(lx,     y, p2DeployZ - 1));

            def.getLanes().put(laneId, laneDef);
        }

        // Bells
        def.setBellPos(TeamSide.PLAYER1, bell1Pos);
        def.setBellPos(TeamSide.PLAYER2, bell2Pos);

        // Build zones (the base areas)
        def.addBuildZone(TeamSide.PLAYER1, new ArenaDefinition.ZoneDef(
                new BlockPos(cx - arenaHalfWidth + 1, y, p1BaseZ - 4),
                new BlockPos(cx + arenaHalfWidth - 1, y + 6, p1DeployZ - 1),
                "P1 Base"
        ));
        def.addBuildZone(TeamSide.PLAYER2, new ArenaDefinition.ZoneDef(
                new BlockPos(cx - arenaHalfWidth + 1, y, p2DeployZ + 1),
                new BlockPos(cx + arenaHalfWidth - 1, y + 6, p2BaseZ + 4),
                "P2 Base"
        ));

        // Arena bounds
        def.setBounds(
                new BlockPos(cx - arenaHalfWidth, y - 2, p1BaseZ - 5),
                new BlockPos(cx + arenaHalfWidth, y + 15, p2BaseZ + 5)
        );

        LOGGER.info("Arena built: {} lanes, {} waypoints per lane, {} towers, 2 thrones",
                def.getLanes().size(),
                def.getLanes().values().stream().findFirst().map(l -> l.waypointsP1toP2.size()).orElse(0),
                def.getTowers(TeamSide.PLAYER1).size() + def.getTowers(TeamSide.PLAYER2).size()
        );

        return def;
    }

    /**
     * Remove all arena-placed blocks (clear the area to air).
     */
    public static void clearArena(ServerWorld world) {
        GameConfig cfg = GameConfig.get();
        int cx = cfg.arenaCenterX;
        int cz = cfg.arenaCenterZ;
        int y  = cfg.arenaY;
        int laneLength = cfg.arenaLaneLength;
        int laneSep    = cfg.laneSeparation;
        int halfLen    = laneLength / 2;
        int arenaHalfWidth = laneSep + 2 + 8;
        int p1BaseZ = cz - halfLen - 12;
        int p2BaseZ = cz + halfLen + 12;

        for (int bx = cx - arenaHalfWidth; bx <= cx + arenaHalfWidth; bx++) {
            for (int bz = p1BaseZ - 5; bz <= p2BaseZ + 5; bz++) {
                for (int by = y - 1; by <= y + 10; by++) {
                    world.setBlockState(new BlockPos(bx, by, bz), Blocks.AIR.getDefaultState(), 2);
                }
            }
        }
    }

    // ====================================================================
    // PRIVATE BUILD HELPERS
    // ====================================================================

    private static void buildWall(ServerWorld world, int x1, int y, int z1, int x2, int yz, int z2, int height) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = y + 1; by <= y + height; by++) {
                    world.setBlockState(new BlockPos(bx, by, bz), WALL, 2);
                }
                world.setBlockState(new BlockPos(bx, y + height + 1, bz), WALL_TOP, 2);
            }
        }
    }

    private static BlockPos buildTower(ServerWorld world, int cx, int y, int tz) {
        // 3x3 base, 4 blocks tall, slab top
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(new BlockPos(cx + dx, y, tz + dz), TOWER_BASE, 2);
                for (int h = 1; h <= 3; h++) {
                    if (dx == 0 && dz == 0) {
                        // Center pillar
                        world.setBlockState(new BlockPos(cx, y + h, tz), TOWER_BASE, 2);
                    } else if (Math.abs(dx) + Math.abs(dz) == 1) {
                        // Cross pillars (wall posts)
                        world.setBlockState(new BlockPos(cx + dx, y + h, tz + dz), TOWER_PILLAR, 2);
                    }
                    // Corners left empty for arrow line-of-sight
                }
            }
        }
        // Platform on top
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(new BlockPos(cx + dx, y + 4, tz + dz), TOWER_TOP, 2);
            }
        }
        // Torch on top
        world.setBlockState(new BlockPos(cx, y + 5, tz), Blocks.SOUL_LANTERN.getDefaultState(), 2);
        return new BlockPos(cx, y, tz);
    }

    private static void buildThrone(ServerWorld world, int cx, int y, int tz, TeamSide team) {
        BlockState accent = team == TeamSide.PLAYER1
                ? Blocks.BLUE_CONCRETE.getDefaultState()
                : Blocks.RED_CONCRETE.getDefaultState();

        // 5x5 base platform
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlockState(new BlockPos(cx + dx, y, tz + dz), THRONE_BLOCK, 2);
                // Outer ring accent
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    world.setBlockState(new BlockPos(cx + dx, y + 1, tz + dz), accent, 2);
                }
            }
        }
        // Central pillar
        for (int h = 1; h <= 4; h++) {
            world.setBlockState(new BlockPos(cx, y + h, tz), THRONE_BLOCK, 2);
        }
        // Crying obsidian accents on corners
        for (int dx : new int[]{-2, 2}) {
            for (int dz : new int[]{-2, 2}) {
                world.setBlockState(new BlockPos(cx + dx, y + 2, tz + dz), THRONE_ACCENT, 2);
            }
        }
        // Beacon-like top
        world.setBlockState(new BlockPos(cx, y + 5, tz),
                team == TeamSide.PLAYER1 ? Blocks.BLUE_STAINED_GLASS.getDefaultState()
                        : Blocks.RED_STAINED_GLASS.getDefaultState(), 2);
        world.setBlockState(new BlockPos(cx, y + 6, tz), Blocks.GLOWSTONE.getDefaultState(), 2);
    }

    private static void addTowerDef(ArenaDefinition def, TeamSide team, int tx, int y, int tz, Lane.LaneId lane) {
        ArenaDefinition.StructureDef towerDef = new ArenaDefinition.StructureDef(
                new BlockPos(tx, y, tz),
                new BlockPos(tx - 1, y, tz - 1),
                new BlockPos(tx + 1, y + 4, tz + 1),
                100.0
        );
        towerDef.attackRange = 15.0;
        towerDef.attackDamage = 3.0;
        towerDef.attackCooldown = 40;
        towerDef.associatedLane = lane;
        def.addTower(team, towerDef);
    }

    /**
     * Check that an X coordinate doesn't fall on another lane's blocks.
     */
    private static boolean isNotOnAnotherLane(int x, int[] laneXs, int halfLane) {
        for (int lx : laneXs) {
            if (x >= lx - halfLane && x <= lx + halfLane) {
                return false;
            }
        }
        return true;
    }
}
