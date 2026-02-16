package com.arenaclash.client.render;

import com.arenaclash.client.ArenaClashClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * Renders polished game HUD overlay:
 * - Phase bar with gradient background and accent border
 * - Animated timer with color transitions and pulse
 * - Round counter with visual pip indicator
 * - Phase-specific hints with icons
 * - Bell interaction reminder during PREPARATION/BATTLE
 */
public class GameHudRenderer {

    private static long lastTickTime = 0;
    private static float animTimer = 0;

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        String phase = ArenaClashClient.currentPhase;
        if ("LOBBY".equals(phase)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int screenWidth = client.getWindow().getScaledWidth();
        TextRenderer textRenderer = client.textRenderer;

        // Animation timer
        long now = System.currentTimeMillis();
        if (lastTickTime != 0) {
            animTimer += (now - lastTickTime) / 1000.0f;
        }
        lastTickTime = now;

        int timerTicks = ArenaClashClient.timerTicks;
        int totalSeconds = Math.max(0, timerTicks / 20);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        String timerStr = String.format("%d:%02d", minutes, seconds);

        int round = ArenaClashClient.currentRound;

        // =============================================
        // MAIN HUD BAR (top center)
        // =============================================
        int barWidth = 200;
        int barHeight = 32;
        int barX = screenWidth / 2 - barWidth / 2;
        int barY = 4;

        // Gradient background
        int bgColor1 = getPhaseGradientStart(phase);
        int bgColor2 = getPhaseGradientEnd(phase);
        drawGradientRect(ctx, barX, barY, barX + barWidth, barY + barHeight, bgColor1, bgColor2);

        // Border
        int borderColor = getPhaseAccent(phase);
        ctx.fill(barX, barY, barX + barWidth, barY + 1, borderColor);
        ctx.fill(barX, barY + barHeight - 1, barX + barWidth, barY + barHeight, borderColor);
        ctx.fill(barX, barY, barX + 1, barY + barHeight, borderColor);
        ctx.fill(barX + barWidth - 1, barY, barX + barWidth, barY + barHeight, borderColor);

        // Phase icon + name
        String phaseIcon = getPhaseIcon(phase);
        String phaseName = getPhaseName(phase);
        String phaseText = phaseIcon + " " + phaseName;
        int phaseColor = getPhaseTextColor(phase);
        ctx.drawCenteredTextWithShadow(textRenderer, phaseText, screenWidth / 2, barY + 4, phaseColor);

        // Timer display
        boolean showTimer = shouldShowTimer(phase, timerTicks);
        if (showTimer && timerTicks >= 0) {
            int timerColor = getTimerColor(totalSeconds, phase);

            // Pulsing effect when time is low
            if (totalSeconds <= 10 && totalSeconds > 0) {
                float pulse = (float) (Math.sin(animTimer * 4) * 0.5 + 0.5);
                int alpha = (int) (200 + pulse * 55);
                timerColor = (timerColor & 0x00FFFFFF) | (alpha << 24);
            }

            ctx.drawCenteredTextWithShadow(textRenderer, timerStr, screenWidth / 2, barY + 17, timerColor);
        } else if ("BATTLE".equals(phase)) {
            // Show "LIVE" indicator with pulsing dot
            float pulse = (float) (Math.sin(animTimer * 3) * 0.5 + 0.5);
            int dotAlpha = (int) (100 + pulse * 155);
            int dotColor = (dotAlpha << 24) | 0xFF3333;
            int dotX = screenWidth / 2 - 20;
            int dotY = barY + 19;
            ctx.fill(dotX, dotY, dotX + 4, dotY + 4, dotColor);
            ctx.drawTextWithShadow(textRenderer, "LIVE", dotX + 7, dotY - 1, 0xFFFF4444);
        }

        // Round indicator (left side of bar)
        drawRoundIndicator(ctx, textRenderer, barX + 6, barY + 4, round, phase);

        // =============================================
        // HINT BAR (below main bar)
        // =============================================
        String hint = getPhaseHint(phase);
        if (hint != null) {
            int hintWidth = textRenderer.getWidth(hint) + 16;
            int hintX = screenWidth / 2 - hintWidth / 2;
            int hintY = barY + barHeight + 2;

            ctx.fill(hintX, hintY, hintX + hintWidth, hintY + 12, 0x80000000);
            ctx.drawCenteredTextWithShadow(textRenderer, hint, screenWidth / 2, hintY + 2, 0xBBBBBB);
        }

        // =============================================
        // BELL STATUS (during PREPARATION)
        // =============================================
        if ("PREPARATION".equals(phase)) {
            String bellHint = "\u00A7e\u266A Right-click the Bell to signal ready!";
            int bellY = barY + barHeight + 16;
            float bellPulse = (float) (Math.sin(animTimer * 2) * 0.3 + 0.7);
            int bellAlpha = (int) (bellPulse * 255);
            ctx.drawCenteredTextWithShadow(textRenderer, bellHint, screenWidth / 2, bellY,
                    (bellAlpha << 24) | 0xFFFF55);
        }
    }

    // =============================================
    // HELPER METHODS
    // =============================================

    private static void drawGradientRect(DrawContext ctx, int x1, int y1, int x2, int y2,
                                          int colorTop, int colorBottom) {
        int height = y2 - y1;
        for (int i = 0; i < height; i++) {
            float t = (float) i / height;
            int color = lerpColor(colorTop, colorBottom, t);
            ctx.fill(x1, y1 + i, x2, y1 + i + 1, color);
        }
    }

    private static int lerpColor(int c1, int c2, float t) {
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF, r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void drawRoundIndicator(DrawContext ctx, TextRenderer textRenderer,
                                            int x, int y, int round, String phase) {
        int maxRounds = 3;
        for (int i = 1; i <= maxRounds; i++) {
            int pipColor;
            if (i < round) pipColor = 0xFF55FF55;
            else if (i == round) pipColor = getPhaseTextColor(phase);
            else pipColor = 0xFF555555;
            ctx.fill(x + (i - 1) * 8, y + 8, x + (i - 1) * 8 + 5, y + 13, pipColor);
        }
    }

    private static boolean shouldShowTimer(String phase, int timerTicks) {
        if ("BATTLE".equals(phase)) {
            return timerTicks > 0 && timerTicks <= 200;
        }
        return timerTicks >= 0;
    }

    private static int getTimerColor(int totalSeconds, String phase) {
        if (totalSeconds <= 5) return 0xFFFF3333;
        if (totalSeconds <= 10) return 0xFFFF6633;
        if (totalSeconds <= 30) return 0xFFFFAA33;
        return 0xFFFFFFFF;
    }

    private static String getPhaseIcon(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> "\u26CF";
            case "PREPARATION" -> "\u2699";
            case "BATTLE" -> "\u2694";
            case "ROUND_END" -> "\u2605";
            case "GAME_OVER" -> "\u2655";
            default -> "\u25CF";
        };
    }

    private static String getPhaseName(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> "SURVIVAL";
            case "PREPARATION" -> "PREPARATION";
            case "BATTLE" -> "BATTLE";
            case "ROUND_END" -> "ROUND END";
            case "GAME_OVER" -> "GAME OVER";
            default -> phase;
        };
    }

    private static int getPhaseTextColor(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> 0xFF55FF55;
            case "PREPARATION" -> 0xFFFFFF55;
            case "BATTLE" -> 0xFFFF5555;
            case "ROUND_END" -> 0xFFFFAA00;
            case "GAME_OVER" -> 0xFFFFD700;
            default -> 0xFFAAAAAA;
        };
    }

    private static int getPhaseGradientStart(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> 0xCC0A2A0A;
            case "PREPARATION" -> 0xCC2A2A0A;
            case "BATTLE" -> 0xCC2A0A0A;
            case "ROUND_END" -> 0xCC2A1A0A;
            case "GAME_OVER" -> 0xCC2A2A0A;
            default -> 0xCC0A0A0A;
        };
    }

    private static int getPhaseGradientEnd(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> 0xCC061806;
            case "PREPARATION" -> 0xCC181806;
            case "BATTLE" -> 0xCC180606;
            case "ROUND_END" -> 0xCC180E06;
            case "GAME_OVER" -> 0xCC181806;
            default -> 0xCC060606;
        };
    }

    private static int getPhaseAccent(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> 0xFF226622;
            case "PREPARATION" -> 0xFF666622;
            case "BATTLE" -> 0xFF662222;
            case "ROUND_END" -> 0xFF663311;
            case "GAME_OVER" -> 0xFF666611;
            default -> 0xFF333333;
        };
    }

    private static String getPhaseHint(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> "\u26CF Hunt mobs for cards! \u2502 [J] View cards";
            case "PREPARATION" -> "[J] Deploy mobs \u2502 Bell = Ready";
            case "BATTLE" -> "Bell = Retreat \u2502 Watch the fight!";
            case "ROUND_END" -> "Preparing next round...";
            default -> null;
        };
    }
}
