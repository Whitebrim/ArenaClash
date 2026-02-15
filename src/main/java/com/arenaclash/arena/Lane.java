package com.arenaclash.arena;

import com.arenaclash.game.TeamSide;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single lane in the arena.
 * Lanes have ordered waypoints that define the path mobs follow.
 * Supports curved/non-linear paths — waypoints can be placed at any angle.
 *
 * Lane boundaries are computed from waypoints + width, keeping mobs
 * constrained to the visual path the map builder created.
 */
public class Lane {

    public enum LaneId {
        LEFT, CENTER, RIGHT;

        public static LaneId fromString(String s) {
            return switch (s.toUpperCase()) {
                case "LEFT", "L" -> LEFT;
                case "CENTER", "C", "MID", "MIDDLE" -> CENTER;
                case "RIGHT", "R" -> RIGHT;
                default -> CENTER;
            };
        }
    }

    private final LaneId id;
    private final ArenaDefinition.LaneDef definition;

    public Lane(LaneId id, ArenaDefinition.LaneDef definition) {
        this.id = id;
        this.definition = definition;
    }

    public LaneId getId() { return id; }
    public ArenaDefinition.LaneDef getDefinition() { return definition; }

    /**
     * Get waypoints in the correct order for a given team.
     * P1 mobs go forward (index 0 → last), P2 mobs go reverse.
     */
    public List<Vec3d> getWaypointsForTeam(TeamSide team) {
        List<Vec3d> waypoints = definition.waypointsP1toP2;
        if (team == TeamSide.PLAYER2) {
            List<Vec3d> reversed = new ArrayList<>(waypoints);
            Collections.reverse(reversed);
            return reversed;
        }
        return waypoints;
    }

    /**
     * Get deployment slots for a given team on this lane.
     */
    public List<BlockPos> getDeploymentSlots(TeamSide team) {
        return team == TeamSide.PLAYER1
                ? definition.deploymentSlotsP1
                : definition.deploymentSlotsP2;
    }

    /**
     * Get the next waypoint index for a mob at the given position.
     * Finds the closest waypoint ahead of the mob's current progress.
     */
    public int getNextWaypointIndex(Vec3d mobPos, TeamSide team) {
        List<Vec3d> waypoints = getWaypointsForTeam(team);
        if (waypoints.isEmpty()) return -1;

        // Find the closest waypoint
        int closestIdx = 0;
        double closestDist = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            double dist = mobPos.squaredDistanceTo(waypoints.get(i));
            if (dist < closestDist) {
                closestDist = dist;
                closestIdx = i;
            }
        }

        // If we're very close to the closest waypoint, target the next one
        if (closestDist < 4.0 && closestIdx < waypoints.size() - 1) {
            return closestIdx + 1;
        }
        return closestIdx;
    }

    /**
     * Get the final destination (enemy base area) for a team's mobs.
     */
    public Vec3d getDestination(TeamSide team) {
        List<Vec3d> waypoints = getWaypointsForTeam(team);
        return waypoints.isEmpty() ? Vec3d.ZERO : waypoints.get(waypoints.size() - 1);
    }

    /**
     * Clamp a position to be within the lane boundaries.
     * Uses distance to nearest waypoint segment with lane width.
     */
    public Vec3d clampToLane(Vec3d pos) {
        if (definition.waypointsP1toP2.size() < 2) return pos;

        double halfWidth = definition.width / 2.0;
        Vec3d closest = findClosestPointOnPath(pos);

        double dx = pos.x - closest.x;
        double dz = pos.z - closest.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= halfWidth) {
            return pos; // Already within bounds
        }

        // Clamp to boundary
        double scale = halfWidth / dist;
        return new Vec3d(
                closest.x + dx * scale,
                pos.y,
                closest.z + dz * scale
        );
    }

    /**
     * Check if a position is within the lane's boundaries.
     */
    public boolean isWithinBounds(Vec3d pos) {
        if (definition.waypointsP1toP2.size() < 2) return true;

        double halfWidth = definition.width / 2.0;
        Vec3d closest = findClosestPointOnPath(pos);
        double dx = pos.x - closest.x;
        double dz = pos.z - closest.z;
        return (dx * dx + dz * dz) <= (halfWidth * halfWidth);
    }

    /**
     * Find the closest point on the lane's waypoint path to a given position.
     */
    private Vec3d findClosestPointOnPath(Vec3d pos) {
        List<Vec3d> waypoints = definition.waypointsP1toP2;
        Vec3d closest = waypoints.get(0);
        double closestDist = Double.MAX_VALUE;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3d a = waypoints.get(i);
            Vec3d b = waypoints.get(i + 1);
            Vec3d pointOnSeg = closestPointOnSegment(a, b, pos);

            double dx = pos.x - pointOnSeg.x;
            double dz = pos.z - pointOnSeg.z;
            double dist = dx * dx + dz * dz;

            if (dist < closestDist) {
                closestDist = dist;
                closest = pointOnSeg;
            }
        }

        return closest;
    }

    /**
     * Closest point on a line segment A→B to point P (in XZ plane).
     */
    private static Vec3d closestPointOnSegment(Vec3d a, Vec3d b, Vec3d p) {
        double abx = b.x - a.x;
        double abz = b.z - a.z;
        double apx = p.x - a.x;
        double apz = p.z - a.z;

        double ab2 = abx * abx + abz * abz;
        if (ab2 < 0.0001) return a; // Degenerate segment

        double t = (apx * abx + apz * abz) / ab2;
        t = Math.max(0, Math.min(1, t));

        return new Vec3d(a.x + t * abx, a.y, a.z + t * abz);
    }

    /**
     * Get the approximate progress along the lane (0.0 = start, 1.0 = end).
     */
    public double getProgressAlongLane(Vec3d pos, TeamSide team) {
        List<Vec3d> waypoints = getWaypointsForTeam(team);
        if (waypoints.size() < 2) return 0;

        double totalLength = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            totalLength += waypoints.get(i).distanceTo(waypoints.get(i + 1));
        }
        if (totalLength < 0.01) return 0;

        // Find which segment the mob is closest to
        double accumulatedLength = 0;
        double closestDist = Double.MAX_VALUE;
        double bestProgress = 0;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3d a = waypoints.get(i);
            Vec3d b = waypoints.get(i + 1);
            Vec3d segPoint = closestPointOnSegment(a, b, pos);
            double dist = pos.squaredDistanceTo(segPoint);

            if (dist < closestDist) {
                closestDist = dist;
                double segLength = a.distanceTo(b);
                double segProgress = a.distanceTo(segPoint);
                bestProgress = (accumulatedLength + segProgress) / totalLength;
            }

            accumulatedLength += a.distanceTo(b);
        }

        return Math.max(0, Math.min(1, bestProgress));
    }
}
