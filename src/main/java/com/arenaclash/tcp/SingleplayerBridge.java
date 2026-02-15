package com.arenaclash.tcp;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bridge for forwarding mob kills from the integrated server (singleplayer)
 * to the client-side TCP connection.
 *
 * In singleplayer, server-side events fire on the integrated server thread.
 * The client TCP client runs on the client thread. This queue bridges them.
 *
 * On a dedicated server, this queue is unused (TCP handles it directly).
 */
public class SingleplayerBridge {
    /**
     * Mob IDs killed by the player in singleplayer.
     * Polled by the client tick handler and sent via TCP.
     */
    public static final ConcurrentLinkedQueue<String> pendingMobKills = new ConcurrentLinkedQueue<>();

    /**
     * Chat messages from singleplayer to forward via TCP (Fix 6).
     * Includes player messages, death messages, and achievement messages.
     */
    public static final ConcurrentLinkedQueue<String> pendingChatMessages = new ConcurrentLinkedQueue<>();

    /**
     * Set to true when the player is in an ArenaClash survival phase in singleplayer.
     * Used by server-side mixins (ore drops, XP prevention, day cycle) to apply
     * game rules on the integrated server, which doesn't have GameManager active.
     */
    public static volatile boolean survivalPhaseActive = false;

    /**
     * Day duration in ticks from config, used for accelerated day/night cycle.
     * Default MC day = 24000 ticks. If this is e.g. 6000, time advances 4x faster.
     */
    public static volatile int dayDurationTicks = 6000;
}
