package com.arenaclash.ai;

import com.arenaclash.arena.ArenaStructure;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * A* pathfinder for lane navigation.
 * Generates waypoints along lanes and can route around obstacles (player-built towers in future).
 * For MVP: simple waypoint following along predefined lane paths.
 */
public class LanePathfinder {

    /**
     * Generate waypoints along a straight lane path from start to end.
     * Waypoints are placed every 2 blocks for smooth movement.
     */
    public static List<BlockPos> generateLaneWaypoints(BlockPos start, BlockPos end, int y) {
        List<BlockPos> waypoints = new ArrayList<>();

        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        int steps = (int) (length / 2.0); // Waypoint every 2 blocks

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = start.getX() + (int) (dx * t);
            int z = start.getZ() + (int) (dz * t);
            waypoints.add(new BlockPos(x, y, z));
        }

        // Ensure end point is included
        if (!waypoints.get(waypoints.size() - 1).equals(end)) {
            waypoints.add(new BlockPos(end.getX(), y, end.getZ()));
        }

        return waypoints;
    }

    /**
     * Find a path around an obstacle using A* on a small grid.
     * Used when a tower/structure blocks the direct path.
     */
    public static List<BlockPos> findPathAround(BlockPos start, BlockPos end, int y,
                                                  Set<BlockPos> obstacles, int laneWidth) {
        // A* on 2D grid
        Set<BlockPos> openSet = new HashSet<>();
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> gScore = new HashMap<>();
        Map<BlockPos, Double> fScore = new HashMap<>();

        BlockPos startFlat = new BlockPos(start.getX(), y, start.getZ());
        BlockPos endFlat = new BlockPos(end.getX(), y, end.getZ());

        openSet.add(startFlat);
        gScore.put(startFlat, 0.0);
        fScore.put(startFlat, heuristic(startFlat, endFlat));

        int maxIterations = 1000;
        int iterations = 0;

        while (!openSet.isEmpty() && iterations < maxIterations) {
            iterations++;

            // Find node with lowest fScore
            BlockPos current = null;
            double lowestF = Double.MAX_VALUE;
            for (BlockPos pos : openSet) {
                double f = fScore.getOrDefault(pos, Double.MAX_VALUE);
                if (f < lowestF) {
                    lowestF = f;
                    current = pos;
                }
            }

            if (current == null) break;

            if (current.getSquaredDistance(endFlat) <= 2.0) {
                return reconstructPath(cameFrom, current);
            }

            openSet.remove(current);
            closedSet.add(current);

            // Check neighbors (4-directional + diagonals)
            for (int[] dir : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                BlockPos neighbor = new BlockPos(current.getX() + dir[0], y, current.getZ() + dir[1]);

                if (closedSet.contains(neighbor)) continue;
                if (obstacles.contains(neighbor)) continue;

                // Check lane bounds (don't wander too far from lane center)
                // This is a simplification - in practice, check against lane boundaries

                double tentativeG = gScore.getOrDefault(current, Double.MAX_VALUE)
                        + (dir[0] != 0 && dir[1] != 0 ? 1.414 : 1.0);

                if (!openSet.contains(neighbor)) {
                    openSet.add(neighbor);
                } else if (tentativeG >= gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    continue;
                }

                cameFrom.put(neighbor, current);
                gScore.put(neighbor, tentativeG);
                fScore.put(neighbor, tentativeG + heuristic(neighbor, endFlat));
            }
        }

        // Fallback: return direct path
        return generateLaneWaypoints(start, end, y);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.sqrt(a.getSquaredDistance(b));
    }

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> cameFrom, BlockPos current) {
        List<BlockPos> path = new ArrayList<>();
        path.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.add(0, current);
        }
        return path;
    }
}
