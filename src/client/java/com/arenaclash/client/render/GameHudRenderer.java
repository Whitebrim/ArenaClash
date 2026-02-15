package com.arenaclash.client.render;

import com.arenaclash.client.ArenaClashClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.MathHelper;

/**
 * Client-side HUD renderer for ArenaClash.
 *
 * Displays:
 * - Phase bar at top of screen (current game phase)
 * - Timer countdown
 * - Round number
 * - Score/structure HP indicators
 *
 * Style: Clean, semi-transparent, inspired by Dota 2/Deadlock HUD.
 */
public class GameHudRenderer {

    // Colors
    private static final int BG_COLOR = 0x80000000;         // Semi-transparent black
    private static final int ACCENT_P1 = 0xFF4488FF;        // Blue
    private static final int ACCENT_P2 = 0xFFFF4444;        // Red
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_GRAY = 0xFFAAAAAA;
    private static final int TEXT_GOLD = 0xFFFFAA00;

    // Phase colors
    private static final int PHASE_LOBBY = 0xFF888888;
    private static final int PHASE_SURVIVAL = 0xFF44AA44;
    private static final int PHASE_PREPARATION = 0xFF4488FF;
    private static final int PHASE_BATTLE = 0xFFFF4444;
    private static final int PHASE_ROUND_END = 0xFFFFAA00;
    private static final int PHASE_GAME_OVER = 0xFFFF44FF;

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        String phase = ArenaClashClient.currentPhase;
        if (phase == null || phase.equals("LOBBY")) return; // Don't render in lobby

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        TextRenderer textRenderer = client.textRenderer;

        renderPhaseBar(context, textRenderer, screenWidth, phase);
        renderTimer(context, textRenderer, screenWidth, phase);
        renderRoundInfo(context, textRenderer, screenWidth, screenHeight);
    }

    /**
     * Phase bar at top center of screen.
     */
    private static void renderPhaseBar(DrawContext context, TextRenderer textRenderer,
                                         int screenWidth, String phase) {
        String displayPhase = getPhaseDisplayName(phase);
        int phaseColor = getPhaseColor(phase);

        int barWidth = 200;
        int barHeight = 24;
        int barX = (screenWidth - barWidth) / 2;
        int barY = 4;

        // Background
        context.fill(barX, barY, barX + barWidth, barY + barHeight, BG_COLOR);

        // Colored accent line at top
        context.fill(barX, barY, barX + barWidth, barY + 2, phaseColor);

        // Phase name centered
        int textWidth = textRenderer.getWidth(displayPhase);
        context.drawText(textRenderer, displayPhase,
                (screenWidth - textWidth) / 2, barY + 7, phaseColor, true);
    }

    /**
     * Timer below phase bar.
     */
    private static void renderTimer(DrawContext context, TextRenderer textRenderer,
                                      int screenWidth, String phase) {
        int timerTicks = ArenaClashClient.timerTicks;
        if (timerTicks <= 0) return;

        int seconds = timerTicks / 20;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        String timeStr = String.format("%d:%02d", minutes, seconds);

        int timerY = 30;
        int textWidth = textRenderer.getWidth(timeStr);

        // Pulsing effect when low time
        int color = TEXT_WHITE;
        if (timerTicks < 200) { // Last 10 seconds
            float pulse = (float) Math.sin(System.currentTimeMillis() * 0.01) * 0.5f + 0.5f;
            int red = (int) MathHelper.lerp(pulse, 0xFF, 0xFF);
            int green = (int) MathHelper.lerp(pulse, 0xFF, 0x44);
            int blue = (int) MathHelper.lerp(pulse, 0xFF, 0x44);
            color = 0xFF000000 | (red << 16) | (green << 8) | blue;
        }

        context.drawText(textRenderer, timeStr,
                (screenWidth - textWidth) / 2, timerY, color, true);
    }

    /**
     * Round info in top-left corner.
     */
    private static void renderRoundInfo(DrawContext context, TextRenderer textRenderer,
                                          int screenWidth, int screenHeight) {
        int round = ArenaClashClient.currentRound;
        if (round <= 0) return;

        String roundStr = "Round " + round;
        int x = 8;
        int y = 8;

        // Small background
        int w = textRenderer.getWidth(roundStr) + 8;
        context.fill(x - 2, y - 2, x + w, y + 12, BG_COLOR);
        context.drawText(textRenderer, roundStr, x + 2, y, TEXT_GOLD, true);
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private static String getPhaseDisplayName(String phase) {
        return switch (phase) {
            case "LOBBY" -> "Lobby";
            case "SURVIVAL" -> "⚒ Survival";
            case "PREPARATION" -> "🛡 Preparation";
            case "BATTLE" -> "⚔ Battle";
            case "ROUND_END" -> "Round End";
            case "GAME_OVER" -> "Game Over";
            default -> phase;
        };
    }

    private static int getPhaseColor(String phase) {
        return switch (phase) {
            case "LOBBY" -> PHASE_LOBBY;
            case "SURVIVAL" -> PHASE_SURVIVAL;
            case "PREPARATION" -> PHASE_PREPARATION;
            case "BATTLE" -> PHASE_BATTLE;
            case "ROUND_END" -> PHASE_ROUND_END;
            case "GAME_OVER" -> PHASE_GAME_OVER;
            default -> TEXT_WHITE;
        };
    }
}
