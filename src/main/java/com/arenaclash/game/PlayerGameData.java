package com.arenaclash.game;

import com.arenaclash.card.CardInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Holds all game-related data for a single player.
 */
public class PlayerGameData {
    private final UUID playerId;
    private TeamSide team;
    private CardInventory cardInventory;
    private int experiencePoints;           // Earned from arena kills, for skill tree (future)
    private boolean readyForBattle;         // Rang the bell
    private BlockPos survivalReturnPos;     // Where to return after arena
    private String survivalReturnWorld;     // Which world to return to

    public PlayerGameData(UUID playerId, TeamSide team) {
        this.playerId = playerId;
        this.team = team;
        this.cardInventory = new CardInventory();
        this.experiencePoints = 0;
        this.readyForBattle = false;
    }

    public UUID getPlayerId() { return playerId; }
    public TeamSide getTeam() { return team; }
    public CardInventory getCardInventory() { return cardInventory; }
    public int getExperiencePoints() { return experiencePoints; }
    public boolean isReadyForBattle() { return readyForBattle; }
    public BlockPos getSurvivalReturnPos() { return survivalReturnPos; }
    public String getSurvivalReturnWorld() { return survivalReturnWorld; }

    public void setTeam(TeamSide team) { this.team = team; }
    public void setCardInventory(CardInventory inventory) { this.cardInventory = inventory; }
    public void setReadyForBattle(boolean ready) { this.readyForBattle = ready; }
    public void addExperience(int xp) { this.experiencePoints += xp; }

    public void saveSurvivalPosition(ServerPlayer player) {
        this.survivalReturnPos = player.blockPosition();
        this.survivalReturnWorld = player.level().dimension().identifier().toString();
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.store("playerId", UUIDUtil.CODEC, playerId);
        nbt.putString("team", team.name());
        nbt.put("cards", cardInventory.toNbt());
        nbt.putInt("xp", experiencePoints);
        if (survivalReturnPos != null) {
            nbt.putInt("returnX", survivalReturnPos.getX());
            nbt.putInt("returnY", survivalReturnPos.getY());
            nbt.putInt("returnZ", survivalReturnPos.getZ());
            nbt.putString("returnWorld", survivalReturnWorld);
        }
        return nbt;
    }

    public static PlayerGameData fromNbt(CompoundTag nbt) {
        UUID id = nbt.read("playerId", UUIDUtil.CODEC).orElse(UUID.randomUUID());
        TeamSide team = TeamSide.valueOf(nbt.getStringOr("team", "PLAYER1"));
        PlayerGameData data = new PlayerGameData(id, team);
        data.cardInventory = CardInventory.fromNbt(nbt.getCompoundOrEmpty("cards"));
        data.experiencePoints = nbt.getIntOr("xp", 0);
        if (nbt.contains("returnX")) {
            data.survivalReturnPos = new BlockPos(
                    nbt.getIntOr("returnX", 0), nbt.getIntOr("returnY", 0), nbt.getIntOr("returnZ", 0));
            data.survivalReturnWorld = nbt.getStringOr("returnWorld", "");
        }
        return data;
    }
}
