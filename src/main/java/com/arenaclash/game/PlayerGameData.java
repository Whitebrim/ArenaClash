package com.arenaclash.game;

import com.arenaclash.card.CardInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

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
    public void setReadyForBattle(boolean ready) { this.readyForBattle = ready; }
    public void addExperience(int xp) { this.experiencePoints += xp; }

    public void saveSurvivalPosition(ServerPlayerEntity player) {
        this.survivalReturnPos = player.getBlockPos();
        this.survivalReturnWorld = player.getServerWorld().getRegistryKey().getValue().toString();
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putUuid("playerId", playerId);
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

    public static PlayerGameData fromNbt(NbtCompound nbt) {
        UUID id = nbt.getUuid("playerId");
        TeamSide team = TeamSide.valueOf(nbt.getString("team"));
        PlayerGameData data = new PlayerGameData(id, team);
        data.cardInventory = CardInventory.fromNbt(nbt.getCompound("cards"));
        data.experiencePoints = nbt.getInt("xp");
        if (nbt.contains("returnX")) {
            data.survivalReturnPos = new BlockPos(
                    nbt.getInt("returnX"), nbt.getInt("returnY"), nbt.getInt("returnZ"));
            data.survivalReturnWorld = nbt.getString("returnWorld");
        }
        return data;
    }
}
