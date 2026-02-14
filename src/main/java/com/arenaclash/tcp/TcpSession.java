package com.arenaclash.tcp;

import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.game.TeamSide;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Represents a connected player's session via TCP.
 * Exists even when the player is in singleplayer (not on MC server).
 */
public class TcpSession {
    private final String sessionId;
    private final String playerName;
    private final UUID playerUuid;
    private final OutputStream outputStream;
    private TeamSide team;
    private CardInventory cardInventory;
    private boolean ready = false;
    private boolean connectedToMc = false;
    private volatile boolean alive = true;

    public TcpSession(String sessionId, String playerName, UUID playerUuid, OutputStream outputStream) {
        this.sessionId = sessionId;
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.outputStream = outputStream;
        this.cardInventory = new CardInventory();
    }

    public String getSessionId() { return sessionId; }
    public String getPlayerName() { return playerName; }
    public UUID getPlayerUuid() { return playerUuid; }
    public TeamSide getTeam() { return team; }
    public void setTeam(TeamSide team) { this.team = team; }
    public CardInventory getCardInventory() { return cardInventory; }
    public void setCardInventory(CardInventory inventory) { this.cardInventory = inventory; }
    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }
    public boolean isConnectedToMc() { return connectedToMc; }
    public void setConnectedToMc(boolean connected) { this.connectedToMc = connected; }
    public boolean isAlive() { return alive; }

    public void disconnect() {
        alive = false;
        try { outputStream.close(); } catch (IOException ignored) {}
    }

    /**
     * Send a message to this client via TCP.
     */
    public synchronized void send(JsonObject msg) {
        if (!alive) return;
        try {
            SyncProtocol.sendMessage(outputStream, msg);
        } catch (IOException e) {
            alive = false;
        }
    }

    /**
     * Add a card obtained in singleplayer.
     */
    public void addCard(String mobId) {
        cardInventory.addCard(new MobCard(mobId));
    }
}
