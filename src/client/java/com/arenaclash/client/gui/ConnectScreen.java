package com.arenaclash.client.gui;

import com.arenaclash.client.ArenaClashClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Screen for connecting to an Arena Clash server via TCP.
 * Shows server address input, connect button, and lobby status.
 */
public class ConnectScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget addressField;
    private ButtonWidget connectButton;
    private ButtonWidget disconnectButton;
    private String statusText = "";
    private int statusColor = 0xAAAAAA;

    public ConnectScreen(Screen parent) {
        super(Text.literal("Arena Clash"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Address field
        addressField = new TextFieldWidget(textRenderer, width / 2 - 100, 80, 200, 20,
                Text.literal("Server Address"));
        addressField.setMaxLength(128);
        addressField.setText(ArenaClashClient.lastServerAddress);
        addressField.setPlaceholder(Text.literal("input ip address please"));
        addDrawableChild(addressField);

        // Connect button
        connectButton = addDrawableChild(ButtonWidget.builder(Text.literal("Connect"), button -> {
            connect();
        }).dimensions(width / 2 - 100, 110, 95, 20).build());

        // Disconnect button
        disconnectButton = addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), button -> {
            ArenaClashClient.disconnectTcp();
            updateStatus();
        }).dimensions(width / 2 + 5, 110, 95, 20).build());

        // Back button
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> {
            client.setScreen(parent);
        }).dimensions(width / 2 - 50, height - 40, 100, 20).build());

        updateStatus();
    }

    private void connect() {
        String addr = addressField.getText().trim();
        if (addr.isEmpty()) {
            statusText = "§cEnter a server address!";
            statusColor = 0xFF4444;
            return;
        }

        ArenaClashClient.lastServerAddress = addr;

        // Parse host:port
        String host;
        int port;

        if (addr.contains(":")) {
            // Explicit port specified
            String[] parts = addr.split(":", 2);
            host = parts[0];
            try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) {
                statusText = "§cInvalid port!";
                statusColor = 0xFF4444;
                return;
            }
        } else {
            // No explicit port — check if it's a domain name (contains letters)
            boolean isDomain = addr.chars().anyMatch(Character::isLetter);
            if (isDomain) {
                // Use MC's SRV resolution for domain names
                // _minecraft._tcp.domain → resolved host:port
                net.minecraft.client.network.ServerAddress resolved =
                        net.minecraft.client.network.ServerAddress.parse(addr);
                host = resolved.getAddress();
                port = resolved.getPort(); // SRV-resolved port as-is
            } else {
                // Bare IP without port — use default TCP port
                host = addr;
                port = 25566;
            }
        }

        statusText = "§eConnecting to " + host + ":" + port + "...";
        statusColor = 0xFFFF55;

        boolean success = ArenaClashClient.connectTcp(host, port);
        if (success) {
            statusText = "§aConnected! Waiting in lobby...";
            statusColor = 0x55FF55;
        } else {
            statusText = "§cFailed to connect to " + host + ":" + port + "!";
            statusColor = 0xFF4444;
        }
    }

    private void updateStatus() {
        var tcp = ArenaClashClient.getTcpClient();
        if (tcp != null && tcp.isConnected()) {
            connectButton.active = false;
            disconnectButton.active = true;
            addressField.active = false;
        } else {
            connectButton.active = true;
            disconnectButton.active = false;
            addressField.active = true;
        }
    }

    @Override
    public void tick() {
        super.tick();
        updateStatus();

        var tcp = ArenaClashClient.getTcpClient();
        if (tcp != null && tcp.isConnected()) {
            statusText = "§aConnected | " + tcp.lobbyStatus
                    + " §7(" + tcp.lobbyPlayerCount + "/2 players)";
            statusColor = 0x55FF55;

            if (!"LOBBY".equals(tcp.currentPhase)) {
                statusText = "§6Game in progress: " + tcp.currentPhase
                        + " Round " + tcp.currentRound;
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1. Render blur background + widgets (buttons, text field)
        super.render(context, mouseX, mouseY, delta);

        // 2. All text drawn AFTER super.render() so it's ON TOP of blur

        // Title
        context.drawCenteredTextWithShadow(textRenderer,
                "§6§l⚔ Arena Clash ⚔", width / 2, 20, 0xFFAA00);
        context.drawCenteredTextWithShadow(textRenderer,
                "§7Connect to an Arena Clash server", width / 2, 35, 0x888888);

        // Server address label
        context.drawTextWithShadow(textRenderer, "§fServer Address:",
                width / 2 - 100, 68, 0xFFFFFF);

        // Status
        context.drawCenteredTextWithShadow(textRenderer, statusText,
                width / 2, 145, statusColor);

        // Instructions
        context.drawCenteredTextWithShadow(textRenderer,
                "§7Domain names resolve SRV records automatically",
                width / 2, 165, 0x666666);
        context.drawCenteredTextWithShadow(textRenderer,
                "§7You play survival in singleplayer, arena on the server",
                width / 2, 178, 0x666666);

        // Lobby info
        var tcp = ArenaClashClient.getTcpClient();
        if (tcp != null && tcp.isConnected()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    "§aSession: " + tcp.getSessionId(), width / 2, 200, 0x55FF55);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
