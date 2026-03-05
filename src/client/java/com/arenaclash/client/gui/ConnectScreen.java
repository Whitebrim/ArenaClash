package com.arenaclash.client.gui;

import com.arenaclash.client.ArenaClashClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Premium connect screen for Arena Clash.
 * Features a centered dark panel with animated branding,
 * clean status display, and polished visual hierarchy.
 */
public class ConnectScreen extends Screen {
    private final Screen parent;
    private EditBox addressField;
    private Button connectButton;
    private Button disconnectButton;
    private Button continueButton;
    private Button backButton;

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
        super(Component.translatable("arenaclash.screen.connect.title"));
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
        addressField = new EditBox(font, contentX, fieldY, contentWidth, 20,
                Component.translatable("arenaclash.screen.connect.address_field"));
        addressField.setMaxLength(128);
        addressField.setValue(ArenaClashClient.lastServerAddress);
        addressField.setHint(Component.translatable("arenaclash.screen.connect.placeholder"));
        addRenderableWidget(addressField);

        // Connect / Disconnect buttons side by side
        int btnWidth = (contentWidth - 4) / 2;
        int btnY = fieldY + 26;

        connectButton = addRenderableWidget(Button.builder(Component.translatable("arenaclash.screen.connect.connect"), button -> {
            connect();
        }).bounds(contentX, btnY, btnWidth, 20).build());

        disconnectButton = addRenderableWidget(Button.builder(Component.translatable("arenaclash.screen.connect.disconnect"), button -> {
            ArenaClashClient.disconnectTcp();
            statusType = StatusType.IDLE;
            statusText = "";
            // Re-init to rebuild buttons (removes stale Continue button)
            rebuildWidgets();
        }).bounds(contentX + btnWidth + 4, btnY, btnWidth, 20).build());

        // Continue button — only when in-game
        var tcpContinue = ArenaClashClient.getTcpClient();
        boolean showContinue = tcpContinue != null && tcpContinue.isConnected()
                && !"LOBBY".equals(tcpContinue.currentPhase);

        if (showContinue) {
            continueButton = addRenderableWidget(Button.builder(
                    Component.translatable("arenaclash.screen.connect.resume"),
                    button -> {
                        String currentPhaseNow = tcpContinue.currentPhase;
                        if ("SURVIVAL".equals(currentPhaseNow)) {
                            ArenaClashClient.scheduleReturnToSurvival();
                        } else {
                            ArenaClashClient.scheduleReturnToGame();
                        }
                        minecraft.setScreen(parent);
                    }
            ).bounds(contentX, btnY + 26, contentWidth, 20).build());
        }

        // Back button at bottom of panel
        int backY = panelY + panelHeight - 30;
        backButton = addRenderableWidget(Button.builder(Component.translatable("arenaclash.screen.connect.back"), button -> {
            minecraft.setScreen(parent);
        }).bounds(width / 2 - 50, backY, 100, 20).build());

        updateButtonStates();
    }

    private void connect() {
        String addr = addressField.getValue().trim();
        if (addr.isEmpty()) {
            statusText = net.minecraft.client.resources.language.I18n.get("arenaclash.screen.connect.status.enter_address");
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
                statusText = net.minecraft.client.resources.language.I18n.get("arenaclash.screen.connect.status.invalid_port");
                statusColor = 0xFF5555;
                statusType = StatusType.ERROR;
                return;
            }
        } else {
            boolean isDomain = addr.chars().anyMatch(Character::isLetter);
            if (isDomain) {
                net.minecraft.client.multiplayer.resolver.ServerAddress resolved =
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(addr);
                host = resolved.getHost();
                port = resolved.getPort();
            } else {
                host = addr;
                port = 25566;
            }
        }

        statusText = net.minecraft.client.resources.language.I18n.get("arenaclash.screen.connect.status.connecting");
        statusColor = 0xFFFF55;
        statusType = StatusType.CONNECTING;

        boolean success = ArenaClashClient.connectTcp(host, port);
        if (success) {
            statusText = net.minecraft.client.resources.language.I18n.get("arenaclash.screen.connect.status.connected");
            statusColor = 0x55FF55;
            statusType = StatusType.CONNECTED;
        } else {
            statusText = net.minecraft.client.resources.language.I18n.get("arenaclash.screen.connect.status.failed");
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
                String phaseFmt = formatPhase(tcp.currentPhase);
                statusText = net.minecraft.client.resources.language.I18n.get(
                        "arenaclash.screen.connect.status.in_game", phaseFmt, String.valueOf(tcp.currentRound));
                statusColor = 0xFFAA00;
            } else {
                statusType = StatusType.CONNECTED;
                statusText = net.minecraft.client.resources.language.I18n.get(
                        "arenaclash.screen.connect.status.lobby", String.valueOf(tcp.lobbyPlayerCount));
                statusColor = 0x55FF55;
            }
        } else if (statusType == StatusType.CONNECTED || statusType == StatusType.IN_GAME) {
            statusType = StatusType.IDLE;
            statusText = "";
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Update animation timer
        long now = System.currentTimeMillis();
        if (lastFrameTime != 0) {
            animTimer += (now - lastFrameTime) / 1000.0f;
        }
        lastFrameTime = now;

        // 1. Blurred background from vanilla
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 2. Draw panel and custom elements ON TOP
        drawPanel(guiGraphics);
        drawTitle(guiGraphics);
        drawStatusIndicator(guiGraphics);
        drawDecorations(guiGraphics);
    }

    /**
     * Main dark panel — semi-transparent with subtle border.
     */
    private void drawPanel(GuiGraphics guiGraphics) {
        // Outer glow/shadow
        guiGraphics.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2,
                panelY + panelHeight + 2, 0x20000000);
        guiGraphics.fill(panelX - 1, panelY - 1, panelX + panelWidth + 1,
                panelY + panelHeight + 1, 0x40000000);

        // Main panel fill — dark translucent
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xD0101018);

        // Top accent gradient bar (3px tall)
        for (int i = 0; i < 3; i++) {
            float t = (float) i / 3.0f;
            int alpha = (int) (200 - t * 60);
            drawHorizontalGradient(guiGraphics,
                    panelX, panelY + i,
                    panelX + panelWidth, panelY + i + 1,
                    (alpha << 24) | 0xCC6600,
                    (alpha << 24) | 0xFF8800);
        }

        // Subtle side borders (1px, very low opacity)
        int borderColor = 0x25FFFFFF;
        guiGraphics.fill(panelX, panelY + 3, panelX + 1, panelY + panelHeight, borderColor);
        guiGraphics.fill(panelX + panelWidth - 1, panelY + 3,
                panelX + panelWidth, panelY + panelHeight, borderColor);
        guiGraphics.fill(panelX, panelY + panelHeight - 1,
                panelX + panelWidth, panelY + panelHeight, borderColor);
    }

    /**
     * Title section — "ARENA CLASH" with swords, decorative line, subtitle.
     */
    private void drawTitle(GuiGraphics guiGraphics) {
        int centerX = width / 2;
        int titleY = panelY + 12;

        // Title glow — subtle pulse
        float glowPulse = (float) (Math.sin(animTimer * 1.5) * 0.12 + 0.88);
        int glowAlpha = (int) (glowPulse * 255);
        int titleColor = (glowAlpha << 24) | 0xFFAA00;

        // Main title
        String headerText = net.minecraft.client.resources.language.I18n.get("arenaclash.screen.connect.header");
        guiGraphics.drawCenteredString(font,
                headerText, centerX, titleY, titleColor);

        // Decorative gradient line under title
        int lineY = titleY + 13;
        int lineHalfW = 60;
        drawHorizontalGradient(guiGraphics,
                centerX - lineHalfW, lineY,
                centerX, lineY + 1,
                0x00FFAA00, 0x80FFAA00);
        drawHorizontalGradient(guiGraphics,
                centerX, lineY,
                centerX + lineHalfW, lineY + 1,
                0x80FFAA00, 0x00FFAA00);

        // Subtitle
        String subtitleText = net.minecraft.client.resources.language.I18n.get("arenaclash.screen.connect.subtitle");
        guiGraphics.drawCenteredString(font,
                subtitleText, centerX, titleY + 18, 0x999999);

        // Label above address field
        String addressLabel = net.minecraft.client.resources.language.I18n.get("arenaclash.screen.connect.address_label");
        guiGraphics.drawString(font, addressLabel,
                panelX + 20, panelY + 57, 0x888888);
    }

    /**
     * Status indicator with animated dot and clean text.
     */
    private void drawStatusIndicator(GuiGraphics guiGraphics) {
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
        int textWidth = font.width(statusText);
        int totalWidth = 6 + 4 + textWidth;
        int startX = centerX - totalWidth / 2;

        // Draw the dot (small square)
        guiGraphics.fill(startX, statusY + 2, startX + 4, statusY + 6, dotColor);

        // Draw status text
        guiGraphics.drawString(font, statusText,
                startX + 8, statusY, statusColor);
    }

    /**
     * Corner decorations — small angular accents on the panel corners.
     */
    private void drawDecorations(GuiGraphics guiGraphics) {
        int accentColor = 0x35FFAA00;
        int len = 10;

        // Top-left
        guiGraphics.fill(panelX, panelY, panelX + len, panelY + 1, accentColor);
        guiGraphics.fill(panelX, panelY, panelX + 1, panelY + len, accentColor);

        // Top-right
        guiGraphics.fill(panelX + panelWidth - len, panelY,
                panelX + panelWidth, panelY + 1, accentColor);
        guiGraphics.fill(panelX + panelWidth - 1, panelY,
                panelX + panelWidth, panelY + len, accentColor);

        // Bottom-left
        guiGraphics.fill(panelX, panelY + panelHeight - 1,
                panelX + len, panelY + panelHeight, accentColor);
        guiGraphics.fill(panelX, panelY + panelHeight - len,
                panelX + 1, panelY + panelHeight, accentColor);

        // Bottom-right
        guiGraphics.fill(panelX + panelWidth - len, panelY + panelHeight - 1,
                panelX + panelWidth, panelY + panelHeight, accentColor);
        guiGraphics.fill(panelX + panelWidth - 1, panelY + panelHeight - len,
                panelX + panelWidth, panelY + panelHeight, accentColor);
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    private void drawHorizontalGradient(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2,
                                         int colorLeft, int colorRight) {
        int w = x2 - x1;
        if (w <= 0) return;
        for (int i = 0; i < w; i++) {
            float t = (float) i / w;
            int color = lerpColor(colorLeft, colorRight, t);
            guiGraphics.fill(x1 + i, y1, x1 + i + 1, y2, color);
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
            case "SURVIVAL" -> net.minecraft.client.resources.language.I18n.get("arenaclash.phase.survival");
            case "PREPARATION" -> net.minecraft.client.resources.language.I18n.get("arenaclash.phase.preparation");
            case "BATTLE" -> net.minecraft.client.resources.language.I18n.get("arenaclash.phase.battle");
            case "ROUND_END" -> net.minecraft.client.resources.language.I18n.get("arenaclash.phase.round_end");
            case "GAME_OVER" -> net.minecraft.client.resources.language.I18n.get("arenaclash.phase.game_over");
            default -> phase;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
