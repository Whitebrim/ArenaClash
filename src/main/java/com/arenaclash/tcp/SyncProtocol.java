package com.arenaclash.tcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight TCP protocol for Arena Clash.
 * Runs alongside MC protocol for persistent state sync.
 *
 * Wire format: [4 bytes big-endian length] [UTF-8 JSON payload]
 * JSON: { "type": "MESSAGE_TYPE", ...fields }
 *
 * Designed for extensibility (curses, chat, spectating, etc.)
 */
public class SyncProtocol {
    public static final Gson GSON = new GsonBuilder().create();

    // === S2C Message Types ===
    public static final String S2C_WELCOME = "WELCOME";
    public static final String S2C_LOBBY_UPDATE = "LOBBY_UPDATE";
    public static final String S2C_PHASE_CHANGE = "PHASE_CHANGE";
    public static final String S2C_CONNECT_TO_MC = "CONNECT_TO_MC";
    public static final String S2C_RETURN_TO_SINGLE = "RETURN_TO_SINGLE";
    public static final String S2C_CARD_SYNC = "CARD_SYNC";
    public static final String S2C_CARD_OBTAINED_ACK = "CARD_OBTAINED_ACK";
    public static final String S2C_GAME_RESULT = "GAME_RESULT";
    public static final String S2C_TIMER_SYNC = "TIMER_SYNC";
    public static final String S2C_MESSAGE = "MESSAGE";
    public static final String S2C_CHAT_RELAY = "CHAT_RELAY";
    public static final String S2C_GAME_SEED = "GAME_SEED";
    public static final String S2C_RECONNECT_STATE = "RECONNECT_STATE";

    // === C2S Message Types ===
    public static final String C2S_AUTH = "AUTH";
    public static final String C2S_CARD_OBTAINED = "CARD_OBTAINED";
    public static final String C2S_READY = "READY";
    public static final String C2S_INVENTORY_SYNC = "INVENTORY_SYNC";
    public static final String C2S_PLACE_CARD = "PLACE_CARD";
    public static final String C2S_REMOVE_CARD = "REMOVE_CARD";
    public static final String C2S_BELL_RING = "BELL_RING";
    public static final String C2S_CHAT = "CHAT";

    // === IO Helpers ===

    /**
     * Send a JSON message over the socket.
     */
    public static void sendMessage(OutputStream out, JsonObject msg) throws IOException {
        byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);
        // Write length (4 bytes, big-endian)
        out.write((data.length >> 24) & 0xFF);
        out.write((data.length >> 16) & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write(data.length & 0xFF);
        out.write(data);
        out.flush();
    }

    /**
     * Read a JSON message from the socket. Blocking.
     * Returns null on EOF.
     */
    public static JsonObject readMessage(InputStream in) throws IOException {
        // Read length
        int b1 = in.read();
        if (b1 == -1) return null;
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if (b2 == -1 || b3 == -1 || b4 == -1) return null;

        int length = (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
        if (length <= 0 || length > 1_000_000) {
            throw new IOException("Invalid message length: " + length);
        }

        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(data, read, length - read);
            if (r == -1) return null;
            read += r;
        }

        String json = new String(data, StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // === Message Builders ===

    public static JsonObject makeMessage(String type) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        return msg;
    }

    public static String getType(JsonObject msg) {
        return msg.has("type") ? msg.get("type").getAsString() : "";
    }

    // S2C builders

    public static JsonObject welcome(String sessionId, int tcpPort, int mcPort) {
        JsonObject msg = makeMessage(S2C_WELCOME);
        msg.addProperty("sessionId", sessionId);
        msg.addProperty("tcpPort", tcpPort);
        msg.addProperty("mcPort", mcPort);
        return msg;
    }

    public static JsonObject lobbyUpdate(int playerCount, int required, String status) {
        JsonObject msg = makeMessage(S2C_LOBBY_UPDATE);
        msg.addProperty("playerCount", playerCount);
        msg.addProperty("required", required);
        msg.addProperty("status", status);
        return msg;
    }

    public static JsonObject phaseChange(String phase, int round, int timerTicks) {
        JsonObject msg = makeMessage(S2C_PHASE_CHANGE);
        msg.addProperty("phase", phase);
        msg.addProperty("round", round);
        msg.addProperty("timerTicks", timerTicks);
        return msg;
    }

    public static JsonObject connectToMc(String host, int port) {
        JsonObject msg = makeMessage(S2C_CONNECT_TO_MC);
        msg.addProperty("host", host);
        msg.addProperty("port", port);
        return msg;
    }

    public static JsonObject returnToSingle() {
        return makeMessage(S2C_RETURN_TO_SINGLE);
    }

    public static JsonObject cardSync(String cardsJson) {
        JsonObject msg = makeMessage(S2C_CARD_SYNC);
        msg.addProperty("cards", cardsJson);
        return msg;
    }

    public static JsonObject timerSync(int timerTicks) {
        JsonObject msg = makeMessage(S2C_TIMER_SYNC);
        msg.addProperty("timerTicks", timerTicks);
        return msg;
    }

    public static JsonObject serverMessage(String text) {
        JsonObject msg = makeMessage(S2C_MESSAGE);
        msg.addProperty("text", text);
        return msg;
    }

    public static JsonObject gameResult(String winner, String details) {
        JsonObject msg = makeMessage(S2C_GAME_RESULT);
        msg.addProperty("winner", winner);
        msg.addProperty("details", details);
        return msg;
    }

    // C2S builders

    public static JsonObject auth(String playerName, String uuid) {
        JsonObject msg = makeMessage(C2S_AUTH);
        msg.addProperty("playerName", playerName);
        msg.addProperty("uuid", uuid);
        return msg;
    }

    public static JsonObject cardObtained(String mobId) {
        JsonObject msg = makeMessage(C2S_CARD_OBTAINED);
        msg.addProperty("mobId", mobId);
        return msg;
    }

    public static JsonObject placeCard(String cardId, String laneId, int slotIndex) {
        JsonObject msg = makeMessage(C2S_PLACE_CARD);
        msg.addProperty("cardId", cardId);
        msg.addProperty("laneId", laneId);
        msg.addProperty("slotIndex", slotIndex);
        return msg;
    }

    public static JsonObject removeCard(String laneId, int slotIndex) {
        JsonObject msg = makeMessage(C2S_REMOVE_CARD);
        msg.addProperty("laneId", laneId);
        msg.addProperty("slotIndex", slotIndex);
        return msg;
    }

    public static JsonObject inventorySync(String itemsJson) {
        JsonObject msg = makeMessage(C2S_INVENTORY_SYNC);
        msg.addProperty("items", itemsJson);
        return msg;
    }

    // New message builders

    public static JsonObject chatRelay(String senderName, String message) {
        JsonObject msg = makeMessage(S2C_CHAT_RELAY);
        msg.addProperty("sender", senderName);
        msg.addProperty("message", message);
        return msg;
    }

    public static JsonObject gameSeed(long seed) {
        JsonObject msg = makeMessage(S2C_GAME_SEED);
        msg.addProperty("seed", seed);
        return msg;
    }

    public static JsonObject chatMessage(String message) {
        JsonObject msg = makeMessage(C2S_CHAT);
        msg.addProperty("message", message);
        return msg;
    }

    public static JsonObject reconnectState(String phase, int round, int timerTicks, String cardsSnbt, long seed) {
        JsonObject msg = makeMessage(S2C_RECONNECT_STATE);
        msg.addProperty("phase", phase);
        msg.addProperty("round", round);
        msg.addProperty("timerTicks", timerTicks);
        msg.addProperty("cards", cardsSnbt);
        msg.addProperty("seed", seed);
        return msg;
    }
}
