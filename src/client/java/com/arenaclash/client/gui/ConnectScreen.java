package com.arenaclash.client.gui;

import com.arenaclash.client.ArenaClashClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Premium connect screen for Arena Clash.
 * Features a centered dark panel with animated branding,
 * clean status display, and polished visual hierarchy.
 */
public class ConnectScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget addressField;
    private ButtonWidget connectButton;
    private ButtonWidget disconnectButton;
    private ButtonWidget continueButton;
    private ButtonWidget backButton;

    // Status display
    private String statusText = "";
    private int statusColor = 0xAAAAAA;
    private StatusType statusType = StatusType.IDLE;

    // Animation
    private float animTimer = 0;
    private long lastFrameTime = 0;

    // Panel dimensions (calculated in init)
    private int panelX, panelY, panelWidth, panelHeight;

    private enum StatusType {
        IDLE, CONNECTING, CONNECTED, ERROR, IN_GAME
    }

    public ConnectScreen(Screen parent) {
        super(Text.literal("Arena Clash"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Panel layout — centered, compact
        panelWidth = 240;
        panelHeight = 200;
        panelX = width / 2 - panelWidth / 2;
        panelY = height / 2 - panelHeight / 2 - 5;

        int contentX = panelX + 20;
        int contentWidth = panelWidth - 40;
        int fieldY = panelY + 68;

        // Address field
        addressField = new TextFieldWidget(textRenderer, contentX, fieldY, contentWidth, 20,
                Text.literal("Server Address"));
        addressField.setMaxLength(128);
        addressField.setText(ArenaClashClient.lastServerAddress);
        addressField.setPlaceholder(Text.literal("server address"));
        addDrawableChild(addressField);

        // Connect / Disconnect buttons side by side
        int btnWidth = (contentWidth - 4) / 2;
        int btnY = fieldY + 26;

        connectButton = addDrawableChild(ButtonWidget.builder(Text.literal("Connect"), button -> {
            connect();
        }).dimensions(contentX, btnY, btnWidth, 20).build());

        disconnectButton = addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), button -> {
            ArenaClashClient.disconnectTcp();
            statusType = StatusType.IDLE;
            statusText = "";
            updateButtonStates();
        }).dimensions(contentX + btnWidth + 4, btnY, btnWidth, 20).build());

        // Continue button — only when in-game
        var tcpContinue = ArenaClashClient.getTcpClient();
        boolean showContinue = tcpContinue != null && tcpContinue.isConnected()
                && !"LOBBY".equals(tcpContinue.currentPhase);

        if (showContinue) {
            continueButton = addDrawableChild(ButtonWidget.builder(
                    Text.literal("\u00a7a\u25B6 Resume Game"),
                    button -> {
                        String currentPhaseNow = tcpContinue.currentPhase;
                        if ("SURVIVAL".equals(currentPhaseNow)) {
                            ArenaClashClient.scheduleReturnToSurvival();
                        } else {
                            ArenaClashClient.scheduleReturnToGame();
                        }
                        client.setScreen(parent);
                    }
            ).dimensions(contentX, btnY + 26, contentWidth, 20).build());
        }

        // Back button at bottom of panel
        int backY = panelY + panelHeight - 30;
        backButton = addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> {
            client.setScreen(parent);
        }).dimensions(width / 2 - 50, backY, 100, 20).build());

        updateButtonStates();
    }

    private void connect() {
        String addr = addressField.getText().trim();
        if (addr.isEmpty()) {
            statusText = "Enter a server address";
            statusColor = 0xFF5555;
            statusType = StatusType.ERROR;
            return;
        }

        ArenaClashClient.lastServerAddress = addr;

        String host;
        int port;

        if (addr.contains(":")) {
            String[] parts = addr.split(":", 2);
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                statusText = "Invalid port number";
                statusColor = 0xFF5555;
                statusType = StatusType.ERROR;
                return;
            }
        } else {
            boolean isDomain = addr.chars().anyMatch(Character::isLetter);
            if (isDomain) {
                net.minecraft.client.network.ServerAddress resolved =
                        net.minecraft.client.network.ServerAddress.parse(addr);
                host = resolved.getAddress();
                port = resolved.getPort();
            } else {
                host = addr;
                port = 25566;
            }
        }

        statusText = "Connecting...";
        statusColor = 0xFFFF55;
        statusType = StatusType.CONNECTING;

        boolean success = ArenaClashClient.connectTcp(host, port);
        if (success) {
            statusText = "Connected \u2014 waiting for players";
            statusColor = 0x55FF55;
            statusType = StatusType.CONNECTED;
        } else {
            statusText = "Connection failed";
            statusColor = 0xFF5555;
            statusType = StatusType.ERROR;
        }
        updateButtonStates();
    }

    private void updateButtonStates() {
        var tcp = ArenaClashClient.getTcpClient();
        boolean isConnected = tcp != null && tcp.isConnected();

        connectButton.active = !isConnected;
        disconnectButton.active = isConnected;
        addressField.active = !isConnected;
    }

    @Override
    public void tick() {
        super.tick();
        updateButtonStates();

        var tcp = ArenaClashClient.getTcpClient();
        if (tcp != null && tcp.isConnected()) {
            if (!"LOBBY".equals(tcp.currentPhase)) {
                statusType = StatusType.IN_GAME;
                statusText = formatPhase(tcp.currentPhase) + " \u2014 Round " + tcp.currentRound;
                statusColor = 0xFFAA00;
            } else {
                statusType = StatusType.CONNECTED;
                statusText = tcp.lobbyPlayerCount + "/2 players in lobby";
                statusColor = 0x55FF55;
            }
        } else if (statusType == StatusType.CONNECTED || statusType == StatusType.IN_GAME) {
            statusType = StatusType.IDLE;
            statusText = "";
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Update animation timer
        long now = System.currentTimeMillis();
        if (lastFrameTime != 0) {
            animTimer += (now - lastFrameTime) / 1000.0f;
        }
        lastFrameTime = now;

        // 1. Blurred background from vanilla
        super.render(context, mouseX, mouseY, delta);

        // 2. Draw panel and custom elements ON TOP
        drawPanel(context);
        drawTitle(context);
        drawStatusIndicator(context);
        drawDecorations(context);
    }

    /**
     * Main dark panel — semi-transparent with subtle border.
     */
    private void drawPanel(DrawContext context) {
        // Outer glow/shadow
        context.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2,
                panelY + panelHeight + 2, 0x20000000);
        context.fill(panelX - 1, panelY - 1, panelX + panelWidth + 1,
                panelY + panelHeight + 1, 0x40000000);

        // Main panel fill — dark translucent
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xD0101018);

        // Top accent gradient bar (3px tall)
        for (int i = 0; i < 3; i++) {
            float t = (float) i / 3.0f;
            int alpha = (int) (200 - t * 60);
            drawHorizontalGradient(context,
                    panelX, panelY + i,
                    panelX + panelWidth, panelY + i + 1,
                    (alpha << 24) | 0xCC6600,
                    (alpha << 24) | 0xFF8800);
        }

        // Subtle side borders (1px, very low opacity)
        int borderColor = 0x25FFFFFF;
        context.fill(panelX, panelY + 3, panelX + 1, panelY + panelHeight, borderColor);
        context.fill(panelX + panelWidth - 1, panelY + 3,
                panelX + panelWidth, panelY + panelHeight, borderColor);
        context.fill(panelX, panelY + panelHeight - 1,
                panelX + panelWidth, panelY + panelHeight, borderColor);
    }

    /**
     * Title section — "ARENA CLASH" with swords, decorative line, subtitle.
     */
    private void drawTitle(DrawContext context) {
        int centerX = width / 2;
        int titleY = panelY + 12;

        // Title glow — subtle pulse
        float glowPulse = (float) (Math.sin(animTimer * 1.5) * 0.12 + 0.88);
        int glowAlpha = (int) (glowPulse * 255);
        int titleColor = (glowAlpha << 24) | 0xFFAA00;

        // Main title
        context.drawCenteredTextWithShadow(textRenderer,
                "\u2694  ARENA CLASH  \u2694", centerX, titleY, titleColor);

        // Decorative gradient line under title
        int lineY = titleY + 13;
        int lineHalfW = 60;
        drawHorizontalGradient(context,
                centerX - lineHalfW, lineY,
                centerX, lineY + 1,
                0x00FFAA00, 0x80FFAA00);
        drawHorizontalGradient(context,
                centerX, lineY,
                centerX + lineHalfW, lineY + 1,
                0x80FFAA00, 0x00FFAA00);

        // Subtitle
        context.drawCenteredTextWithShadow(textRenderer,
                "\u00a77Server Connection", centerX, titleY + 18, 0x999999);

        // Label above address field
        context.drawTextWithShadow(textRenderer, "\u00a77Address",
                panelX + 20, panelY + 57, 0x888888);
    }

    /**
     * Status indicator with animated dot and clean text.
     */
    private void drawStatusIndicator(DrawContext context) {
        if (statusText.isEmpty() && statusType == StatusType.IDLE) return;

        int centerX = width / 2;

        // Position below the last button row
        var tcp = ArenaClashClient.getTcpClient();
        boolean hasContinue = tcp != null && tcp.isConnected() && !"LOBBY".equals(tcp.currentPhase);
        int statusY = panelY + 68 + 26 + (hasContinue ? 52 : 26) + 8;

        // Animated status dot
        int dotColor;
        boolean pulseDot = false;
        switch (statusType) {
            case CONNECTING:
                dotColor = 0xFFFF55;
                pulseDot = true;
                break;
            case CONNECTED:
                dotColor = 0x55FF55;
                break;
            case ERROR:
                dotColor = 0xFF5555;
                break;
            case IN_GAME:
                dotColor = 0xFFAA00;
                pulseDot = true;
                break;
            default:
                dotColor = 0x888888;
                break;
        }

        if (pulseDot) {
            float pulse = (float) (Math.sin(animTimer * 4) * 0.35 + 0.65);
            int alpha = (int) (pulse * 255);
            dotColor = (alpha << 24) | (dotColor & 0x00FFFFFF);
        } else {
            dotColor = 0xFF000000 | dotColor;
        }

        // Calculate layout: dot + gap + text, centered
        int textWidth = textRenderer.getWidth(statusText);
        int totalWidth = 6 + 4 + textWidth;
        int startX = centerX - totalWidth / 2;

        // Draw the dot (small square)
        context.fill(startX, statusY + 2, startX + 4, statusY + 6, dotColor);

        // Draw status text
        context.drawTextWithShadow(textRenderer, statusText,
                startX + 8, statusY, statusColor);
    }

    /**
     * Corner decorations — small angular accents on the panel corners.
     */
    private void drawDecorations(DrawContext context) {
        int accentColor = 0x35FFAA00;
        int len = 10;

        // Top-left
        context.fill(panelX, panelY, panelX + len, panelY + 1, accentColor);
        context.fill(panelX, panelY, panelX + 1, panelY + len, accentColor);

        // Top-right
        context.fill(panelX + panelWidth - len, panelY,
                panelX + panelWidth, panelY + 1, accentColor);
        context.fill(panelX + panelWidth - 1, panelY,
                panelX + panelWidth, panelY + len, accentColor);

        // Bottom-left
        context.fill(panelX, panelY + panelHeight - 1,
                panelX + len, panelY + panelHeight, accentColor);
        context.fill(panelX, panelY + panelHeight - len,
                panelX + 1, panelY + panelHeight, accentColor);

        // Bottom-right
        context.fill(panelX + panelWidth - len, panelY + panelHeight - 1,
                panelX + panelWidth, panelY + panelHeight, accentColor);
        context.fill(panelX + panelWidth - 1, panelY + panelHeight - len,
                panelX + panelWidth, panelY + panelHeight, accentColor);
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    private void drawHorizontalGradient(DrawContext ctx, int x1, int y1, int x2, int y2,
                                         int colorLeft, int colorRight) {
        int w = x2 - x1;
        if (w <= 0) return;
        for (int i = 0; i < w; i++) {
            float t = (float) i / w;
            int color = lerpColor(colorLeft, colorRight, t);
            ctx.fill(x1 + i, y1, x1 + i + 1, y2, color);
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

    private static String formatPhase(String phase) {
        return switch (phase) {
            case "SURVIVAL" -> "\u26CF Survival";
            case "PREPARATION" -> "\u2699 Preparation";
            case "BATTLE" -> "\u2694 Battle";
            case "ROUND_END" -> "\u2605 Round End";
            case "GAME_OVER" -> "\u2655 Game Over";
            default -> phase;
        };
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
