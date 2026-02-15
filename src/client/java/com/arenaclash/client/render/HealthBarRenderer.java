package com.arenaclash.client.render;

import com.arenaclash.ai.CombatSystem;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Client-side renderer for beautiful HP bars above arena mobs and structures.
 *
 * Style inspired by Dota 2 / Deadlock:
 * - Gradient bar: green → yellow → red based on HP %
 * - Semi-transparent black background
 * - Shows: mob name, level, team color, HP fraction
 * - Scales with camera distance
 * - Billboard (always faces camera)
 *
 * Registers on WorldRenderEvents.AFTER_ENTITIES.
 */
public class HealthBarRenderer {

    // Bar dimensions (in world units at reference distance)
    private static final float BAR_WIDTH = 1.2f;
    private static final float BAR_HEIGHT = 0.08f;
    private static final float BAR_PADDING = 0.02f;
    private static final float BAR_Y_OFFSET = 0.3f;  // Above entity head
    private static final float TEXT_SCALE = 0.015f;

    // Colors
    private static final int BG_COLOR = 0x99000000;
    private static final int FRAME_COLOR = 0xFF333333;
    private static final int P1_TINT = 0xFF4488FF;  // Blue team
    private static final int P2_TINT = 0xFFFF4444;  // Red team

    // Reference distance for scale calculation
    private static final float REF_DISTANCE = 10.0f;
    private static final float MIN_SCALE = 0.4f;
    private static final float MAX_SCALE = 1.2f;
    private static final float MAX_RENDER_DISTANCE = 48.0f;

    private static boolean enabled = true;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(HealthBarRenderer::onRenderWorld);
    }

    public static void setEnabled(boolean val) { enabled = val; }

    private static void onRenderWorld(WorldRenderContext context) {
        if (!enabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        float tickDelta = context.tickCounter().getTickDelta(true);

        MatrixStack matrices = context.matrixStack();

        // Iterate all entities in the world
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isDead() || living.isRemoved()) continue;

            // Check if this is an ArenaClash mob (has our tag)
            if (!hasArenaTag(living)) continue;

            // Distance check
            double distSq = entity.squaredDistanceTo(cameraPos.x, cameraPos.y, cameraPos.z);
            if (distSq > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) continue;

            renderHealthBar(matrices, context, living, camera, cameraPos, tickDelta);
        }
    }

    private static void renderHealthBar(MatrixStack matrices, WorldRenderContext context,
                                          LivingEntity entity, Camera camera,
                                          Vec3d cameraPos, float tickDelta) {
        // Interpolated position
        double x = MathHelper.lerp(tickDelta, entity.prevX, entity.getX());
        double y = MathHelper.lerp(tickDelta, entity.prevY, entity.getY());
        double z = MathHelper.lerp(tickDelta, entity.prevZ, entity.getZ());

        // Bar position: above head
        float entityHeight = entity.getHeight();
        double barY = y + entityHeight + BAR_Y_OFFSET;

        // Camera-relative position
        double dx = x - cameraPos.x;
        double dy = barY - cameraPos.y;
        double dz = z - cameraPos.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Scale based on distance
        float scale = (float) (REF_DISTANCE / Math.max(dist, 1.0));
        scale = MathHelper.clamp(scale, MIN_SCALE, MAX_SCALE);
        scale *= 0.025f; // World unit scale

        // HP info
        float hp = entity.getHealth();
        float maxHp = entity.getMaxHealth();
        float hpFraction = maxHp > 0 ? hp / maxHp : 0;

        // Team info
        String teamStr = getArenaTag(entity, CombatSystem.TAG_TEAM);
        boolean isP1 = "PLAYER1".equals(teamStr);
        int teamColor = isP1 ? P1_TINT : P2_TINT;

        // Level
        int level = getArenaIntTag(entity, CombatSystem.TAG_LEVEL);

        matrices.push();
        matrices.translate(dx, dy, dz);

        // Billboard: face camera
        matrices.multiply(camera.getRotation());
        matrices.scale(-scale, -scale, scale);

        Matrix4f posMatrix = matrices.peek().getPositionMatrix();
        VertexConsumerProvider immediate = context.consumers();
        if (immediate == null) {
            matrices.pop();
            return;
        }

        // Disable depth test so bars are always visible
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;

        // === Draw background ===
        float halfWidth = BAR_WIDTH * 0.5f / scale * scale;
        float bgLeft = -BAR_WIDTH / 2 - BAR_PADDING;
        float bgRight = BAR_WIDTH / 2 + BAR_PADDING;
        float bgTop = -BAR_HEIGHT / 2 - BAR_PADDING - 6; // Extra space for text
        float bgBottom = BAR_HEIGHT / 2 + BAR_PADDING;

        // Background quad
        drawQuad(posMatrix, immediate, bgLeft, bgTop, bgRight, bgBottom, BG_COLOR);

        // === Draw HP bar ===
        float barLeft = -BAR_WIDTH / 2;
        float barRight = barLeft + BAR_WIDTH * hpFraction;
        float barTop = -BAR_HEIGHT / 2;
        float barBottom = BAR_HEIGHT / 2;

        // HP color gradient
        int barColor = getHpColor(hpFraction);
        drawQuad(posMatrix, immediate, barLeft, barTop, barRight, barBottom, barColor);

        // Empty portion (dark)
        if (hpFraction < 1.0f) {
            drawQuad(posMatrix, immediate, barRight, barTop, BAR_WIDTH / 2, barBottom, 0xFF1A1A1A);
        }

        // Frame
        float frameThickness = 0.5f;
        drawQuad(posMatrix, immediate, barLeft - frameThickness, barTop - frameThickness,
                BAR_WIDTH / 2 + frameThickness, barTop, FRAME_COLOR);
        drawQuad(posMatrix, immediate, barLeft - frameThickness, barBottom,
                BAR_WIDTH / 2 + frameThickness, barBottom + frameThickness, FRAME_COLOR);

        // === Draw text ===
        // Name + Level
        String entityName = entity.hasCustomName() && entity.getCustomName() != null
                ? entity.getCustomName().getString()
                : entity.getType().getName().getString();
        String labelText = entityName;
        if (level > 0) labelText += " Lv." + level;

        // HP text
        String hpText = String.format("%.0f/%.0f", hp, maxHp);

        float textY = barTop - 8;
        textRenderer.draw(labelText, -textRenderer.getWidth(labelText) / 2.0f, textY,
                teamColor, false, posMatrix, immediate, TextRenderer.TextLayerType.NORMAL,
                0, 0xF000F0);

        // HP numbers right-aligned
        textRenderer.draw(hpText, BAR_WIDTH / 2 - textRenderer.getWidth(hpText), barTop + 1,
                0xFFCCCCCC, false, posMatrix, immediate, TextRenderer.TextLayerType.NORMAL,
                0, 0xF000F0);

        matrices.pop();
    }

    /**
     * Draw a colored quad.
     */
    private static void drawQuad(Matrix4f matrix, VertexConsumerProvider immediate,
                                   float x1, float y1, float x2, float y2, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        VertexConsumer consumer = immediate.getBuffer(RenderLayer.getGui());
        consumer.vertex(matrix, x1, y1, 0).color(r, g, b, a);
        consumer.vertex(matrix, x1, y2, 0).color(r, g, b, a);
        consumer.vertex(matrix, x2, y2, 0).color(r, g, b, a);
        consumer.vertex(matrix, x2, y1, 0).color(r, g, b, a);
    }

    /**
     * Get HP bar color based on HP fraction.
     * Green (100%) → Yellow (50%) → Red (0%)
     */
    private static int getHpColor(float fraction) {
        fraction = MathHelper.clamp(fraction, 0, 1);

        int r, g;
        if (fraction > 0.5f) {
            // Green to Yellow
            float t = (fraction - 0.5f) * 2.0f;
            r = (int) MathHelper.lerp(t, 255, 80);
            g = (int) MathHelper.lerp(t, 220, 220);
        } else {
            // Yellow to Red
            float t = fraction * 2.0f;
            r = 255;
            g = (int) MathHelper.lerp(t, 50, 220);
        }

        return 0xFF000000 | (r << 16) | (g << 8) | 30;
    }

    // ========================================================================
    // TAG READING (client-side)
    // ========================================================================

    private static boolean hasArenaTag(LivingEntity entity) {
        // On client side, we check custom name or scoreboard tags
        // For now, check if entity has our custom name prefix
        if (entity.getCommandTags().contains(CombatSystem.TAG_MOB)) return true;

        // Fallback: check NBT
        NbtCompound nbt = new NbtCompound();
        entity.writeNbt(nbt);
        return nbt.getBoolean(CombatSystem.TAG_MOB);
    }

    private static String getArenaTag(LivingEntity entity, String key) {
        NbtCompound nbt = new NbtCompound();
        entity.writeNbt(nbt);
        return nbt.contains(key) ? nbt.getString(key) : null;
    }

    private static int getArenaIntTag(LivingEntity entity, String key) {
        NbtCompound nbt = new NbtCompound();
        entity.writeNbt(nbt);
        return nbt.contains(key) ? nbt.getInt(key) : 0;
    }
}
