package com.arenaclash.client.render;

import com.arenaclash.arena.ArenaDefinition;
import com.arenaclash.arena.Lane;
import com.arenaclash.game.TeamSide;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Client-side renderer for arena zone visualization.
 *
 * When /ac arena show is active, draws colored wireframes and outlines for:
 * - GREEN lines: LEFT lane waypoints path
 * - YELLOW lines: CENTER lane waypoints path
 * - RED lines: RIGHT lane waypoints path
 * - BLUE blocks: P1 deployment zones
 * - RED blocks: P2 deployment zones
 * - GOLD outline: Thrones
 * - GRAY outline: Towers
 * - CYAN outline: Build zones
 * - WHITE outline: Arena bounds
 *
 * All rendered via WorldRenderEvents.AFTER_ENTITIES using line rendering.
 */
public class ArenaZoneRenderer {

    // Lane colors (ARGB)
    private static final int COLOR_LEFT = 0xFF44FF44;    // Green
    private static final int COLOR_CENTER = 0xFFFFFF44;  // Yellow
    private static final int COLOR_RIGHT = 0xFFFF4444;   // Red
    private static final int COLOR_DEPLOY_P1 = 0xFF4488FF; // Blue
    private static final int COLOR_DEPLOY_P2 = 0xFFFF4444; // Red
    private static final int COLOR_THRONE = 0xFFFFAA00;   // Gold
    private static final int COLOR_TOWER = 0xFF888888;    // Gray
    private static final int COLOR_BUILD = 0xFF44FFFF;    // Cyan
    private static final int COLOR_BOUNDS = 0xFFFFFFFF;   // White

    private static boolean enabled = false;
    private static ArenaDefinition definition = null;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(ArenaZoneRenderer::onRenderWorld);
    }

    public static void setEnabled(boolean val) { enabled = val; }
    public static void setDefinition(ArenaDefinition def) { definition = def; }

    private static void onRenderWorld(WorldRenderContext context) {
        if (!enabled || definition == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        MatrixStack matrices = context.matrixStack();

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Render all zone types
        renderBounds(matrices, context);
        renderLanes(matrices, context);
        renderDeployments(matrices, context);
        renderStructures(matrices, context);
        renderBuildZones(matrices, context);

        matrices.pop();
    }

    // ========================================================================
    // BOUNDS
    // ========================================================================

    private static void renderBounds(MatrixStack matrices, WorldRenderContext context) {
        BlockPos min = definition.getBoundsMin();
        BlockPos max = definition.getBoundsMax();
        if (min == null || max == null) return;

        drawBoxOutline(matrices, context, min, max, COLOR_BOUNDS);
    }

    // ========================================================================
    // LANES (waypoint paths)
    // ========================================================================

    private static void renderLanes(MatrixStack matrices, WorldRenderContext context) {
        for (var entry : definition.getLanes().entrySet()) {
            Lane.LaneId laneId = entry.getKey();
            ArenaDefinition.LaneDef laneDef = entry.getValue();

            int color = switch (laneId) {
                case LEFT -> COLOR_LEFT;
                case CENTER -> COLOR_CENTER;
                case RIGHT -> COLOR_RIGHT;
            };

            // Draw waypoint path
            List<Vec3d> waypoints = laneDef.waypointsP1toP2;
            for (int i = 0; i < waypoints.size() - 1; i++) {
                Vec3d a = waypoints.get(i);
                Vec3d b = waypoints.get(i + 1);
                drawLine(matrices, context, a, b, color);
            }

            // Draw waypoint markers (small boxes)
            for (int i = 0; i < waypoints.size(); i++) {
                Vec3d wp = waypoints.get(i);
                drawSmallMarker(matrices, context, wp, color);
            }

            // Draw lane width boundaries (parallel lines offset from path)
            float halfWidth = laneDef.width / 2.0f;
            for (int i = 0; i < waypoints.size() - 1; i++) {
                Vec3d a = waypoints.get(i);
                Vec3d b = waypoints.get(i + 1);

                // Perpendicular offset in XZ plane
                double dx = b.x - a.x;
                double dz = b.z - a.z;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 0.01) continue;

                double perpX = -dz / len * halfWidth;
                double perpZ = dx / len * halfWidth;

                Vec3d a1 = a.add(perpX, 0, perpZ);
                Vec3d b1 = b.add(perpX, 0, perpZ);
                Vec3d a2 = a.add(-perpX, 0, -perpZ);
                Vec3d b2 = b.add(-perpX, 0, -perpZ);

                // Dimmer color for boundary lines
                int dimColor = (color & 0x00FFFFFF) | 0x60000000;
                drawLine(matrices, context, a1, b1, dimColor);
                drawLine(matrices, context, a2, b2, dimColor);
            }
        }
    }

    // ========================================================================
    // DEPLOYMENTS
    // ========================================================================

    private static void renderDeployments(MatrixStack matrices, WorldRenderContext context) {
        for (var entry : definition.getLanes().entrySet()) {
            ArenaDefinition.LaneDef laneDef = entry.getValue();

            for (BlockPos pos : laneDef.deploymentSlotsP1) {
                drawBlockHighlight(matrices, context, pos, COLOR_DEPLOY_P1);
            }
            for (BlockPos pos : laneDef.deploymentSlotsP2) {
                drawBlockHighlight(matrices, context, pos, COLOR_DEPLOY_P2);
            }
        }
    }

    // ========================================================================
    // STRUCTURES
    // ========================================================================

    private static void renderStructures(MatrixStack matrices, WorldRenderContext context) {
        // Thrones
        for (TeamSide team : TeamSide.values()) {
            ArenaDefinition.StructureDef throne = definition.getThrone(team);
            if (throne != null && throne.boundsMin != null && throne.boundsMax != null) {
                drawBoxOutline(matrices, context, throne.boundsMin, throne.boundsMax, COLOR_THRONE);
            }

            for (ArenaDefinition.StructureDef tower : definition.getTowers(team)) {
                if (tower.boundsMin != null && tower.boundsMax != null) {
                    drawBoxOutline(matrices, context, tower.boundsMin, tower.boundsMax, COLOR_TOWER);
                }
            }
        }
    }

    // ========================================================================
    // BUILD ZONES
    // ========================================================================

    private static void renderBuildZones(MatrixStack matrices, WorldRenderContext context) {
        for (TeamSide team : TeamSide.values()) {
            for (ArenaDefinition.ZoneDef zone : definition.getBuildZones(team)) {
                drawBoxOutline(matrices, context, zone.min, zone.max, COLOR_BUILD);
            }
        }
    }

    // ========================================================================
    // DRAWING PRIMITIVES
    // ========================================================================

    private static void drawLine(MatrixStack matrices, WorldRenderContext context,
                                  Vec3d from, Vec3d to, int color) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumerProvider consumers = context.consumers();
        if (consumers == null) return;

        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        VertexConsumer consumer = consumers.getBuffer(RenderLayer.getLines());

        // Direction for normals
        float dx = (float)(to.x - from.x);
        float dy = (float)(to.y - from.y);
        float dz = (float)(to.z - from.z);
        float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 0.001f) return;
        dx /= len; dy /= len; dz /= len;

        consumer.vertex(matrix, (float) from.x, (float) from.y + 0.05f, (float) from.z)
                .color(r, g, b, a).normal(matrices.peek(), dx, dy, dz);
        consumer.vertex(matrix, (float) to.x, (float) to.y + 0.05f, (float) to.z)
                .color(r, g, b, a).normal(matrices.peek(), dx, dy, dz);
    }

    private static void drawBoxOutline(MatrixStack matrices, WorldRenderContext context,
                                        BlockPos min, BlockPos max, int color) {
        float x1 = min.getX();
        float y1 = min.getY();
        float z1 = min.getZ();
        float x2 = max.getX() + 1;
        float y2 = max.getY() + 1;
        float z2 = max.getZ() + 1;

        // 12 edges of a box
        drawLine(matrices, context, new Vec3d(x1, y1, z1), new Vec3d(x2, y1, z1), color);
        drawLine(matrices, context, new Vec3d(x1, y1, z1), new Vec3d(x1, y2, z1), color);
        drawLine(matrices, context, new Vec3d(x1, y1, z1), new Vec3d(x1, y1, z2), color);
        drawLine(matrices, context, new Vec3d(x2, y2, z2), new Vec3d(x1, y2, z2), color);
        drawLine(matrices, context, new Vec3d(x2, y2, z2), new Vec3d(x2, y1, z2), color);
        drawLine(matrices, context, new Vec3d(x2, y2, z2), new Vec3d(x2, y2, z1), color);
        drawLine(matrices, context, new Vec3d(x2, y1, z1), new Vec3d(x2, y2, z1), color);
        drawLine(matrices, context, new Vec3d(x2, y1, z1), new Vec3d(x2, y1, z2), color);
        drawLine(matrices, context, new Vec3d(x1, y2, z1), new Vec3d(x2, y2, z1), color);
        drawLine(matrices, context, new Vec3d(x1, y2, z1), new Vec3d(x1, y2, z2), color);
        drawLine(matrices, context, new Vec3d(x1, y1, z2), new Vec3d(x2, y1, z2), color);
        drawLine(matrices, context, new Vec3d(x1, y1, z2), new Vec3d(x1, y2, z2), color);
    }

    private static void drawSmallMarker(MatrixStack matrices, WorldRenderContext context,
                                          Vec3d pos, int color) {
        float s = 0.15f; // Small marker size
        BlockPos min = BlockPos.ofFloored(pos.x - s, pos.y - s, pos.z - s);
        BlockPos max = BlockPos.ofFloored(pos.x + s, pos.y + s, pos.z + s);

        // Draw as small filled cross
        drawLine(matrices, context,
                new Vec3d(pos.x - s, pos.y, pos.z),
                new Vec3d(pos.x + s, pos.y, pos.z), color);
        drawLine(matrices, context,
                new Vec3d(pos.x, pos.y - s, pos.z),
                new Vec3d(pos.x, pos.y + s, pos.z), color);
        drawLine(matrices, context,
                new Vec3d(pos.x, pos.y, pos.z - s),
                new Vec3d(pos.x, pos.y, pos.z + s), color);
    }

    private static void drawBlockHighlight(MatrixStack matrices, WorldRenderContext context,
                                             BlockPos pos, int color) {
        // Highlight a single block with translucent outline
        drawBoxOutline(matrices, context, pos, pos, color);
    }
}
