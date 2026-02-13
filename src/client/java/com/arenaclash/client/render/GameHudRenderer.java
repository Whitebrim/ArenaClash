package com.arenaclash.client.render;

import com.arenaclash.client.ArenaClashClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * Renders game HUD overlay: phase indicator, timer, round counter.
 */
public class GameHudRenderer {

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        String phase = ArenaClashClient.currentPhase;
        if ("LOBBY".equals(phase)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int screenWidth = client.getWindow().getScaledWidth();
        var textRenderer = client.textRenderer;

        int timerTicks = ArenaClashClient.timerTicks;
        int totalSeconds = timerTicks / 20;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        String timerStr = String.format("%d:%02d", minutes, seconds);

        int round = ArenaClashClient.currentRound;

        // Phase + Round indicator (top center)
        String phaseDisplay = getPhaseDisplay(phase);
        int phaseColor = getPhaseColor(phase);

        // Background bar
        int barWidth = 180;
        int barX = screenWidth / 2 - barWidth / 2;
        int barY = 4;
        ctx.fill(barX, barY, barX + barWidth, barY + 28, 0xA0000000);

        // Phase name
        ctx.drawCenteredTextWithShadow(textRenderer, phaseDisplay, screenWidth / 2, barY + 3, phaseColor);

        // Timer
        int timerColor = totalSeconds <= 10 ? 0xFF4444 : 0xFFFFFF;
        ctx.drawCenteredTextWithShadow(textRenderer, timerStr, screenWidth / 2, barY + 15, timerColor);

        // Round indicator (top left of bar)
        ctx.drawTextWithShadow(textRenderer, "§7R" + round, barX + 4, barY + 3, 0xAAAAAA);

        // Phase-specific extras
        if ("SURVIVAL".equals(phase)) {
            // Show "Gather resources!" hint
            ctx.drawCenteredTextWithShadow(textRenderer, "§7⛏ Gather resources!",
                    screenWidth / 2, barY + 32, 0x888888);
        } else if ("PREPARATION".equals(phase)) {
            ctx.drawCenteredTextWithShadow(textRenderer, "§7[J] Deploy mobs | [B] Ready",
                    screenWidth / 2, barY + 32, 0x888888);
        } else if ("BATTLE".equals(phase)) {
            ctx.drawCenteredTextWithShadow(textRenderer, "§7[B] Retreat | Watch the fight!",
                    screenWidth / 2, barY + 32, 0x888888);
        }
    }

    private static String getPhaseDisplay(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> "§a⛏ SURVIVAL";
            case "PREPARATION" -> "§e⚙ PREPARATION";
            case "BATTLE" -> "§c⚔ BATTLE";
            case "ROUND_END" -> "§6★ ROUND END";
            case "GAME_OVER" -> "§6§l★ GAME OVER ★";
            default -> "§7" + phase;
        };
    }

    private static int getPhaseColor(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> 0x55FF55;
            case "PREPARATION" -> 0xFFFF55;
            case "BATTLE" -> 0xFF5555;
            case "ROUND_END" -> 0xFFAA00;
            case "GAME_OVER" -> 0xFFAA00;
            default -> 0xAAAAAA;
        };
    }
}
