package com.arenaclash.game;

public enum GamePhase {
    LOBBY,          // Waiting for players
    SURVIVAL,       // Players gathering resources in their worlds
    PREPARATION,    // Players on arena, setting up mobs
    BATTLE,         // Mobs fighting
    ROUND_END,      // Brief pause between rounds
    GAME_OVER       // Game finished
}
