package com.arenaclash.game;

public enum TeamSide {
    PLAYER1,
    PLAYER2;

    public TeamSide opponent() {
        return this == PLAYER1 ? PLAYER2 : PLAYER1;
    }
}
