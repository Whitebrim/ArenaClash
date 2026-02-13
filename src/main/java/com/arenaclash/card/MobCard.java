package com.arenaclash.card;

import net.minecraft.nbt.NbtCompound;

import java.util.UUID;

/**
 * A specific card instance owned by a player. Contains level and unique ID.
 * For MVP: level is always 1, no equipment.
 */
public class MobCard {
    private final UUID cardId;
    private final String mobId;       // References MobCardDefinition.id
    private int level;

    // Future: equipment slots
    // private ItemStack weapon;
    // private ItemStack[] armor;

    public MobCard(String mobId) {
        this.cardId = UUID.randomUUID();
        this.mobId = mobId;
        this.level = 1;
    }

    public MobCard(UUID cardId, String mobId, int level) {
        this.cardId = cardId;
        this.mobId = mobId;
        this.level = level;
    }

    public UUID getCardId() { return cardId; }
    public String getMobId() { return mobId; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public MobCardDefinition getDefinition() {
        return MobCardRegistry.getById(mobId);
    }

    public double getHP() {
        MobCardDefinition def = getDefinition();
        return def != null ? def.getHP(level) : 1;
    }

    public double getSpeed() {
        MobCardDefinition def = getDefinition();
        return def != null ? def.getSpeed(level) : 1;
    }

    public double getAttack() {
        MobCardDefinition def = getDefinition();
        return def != null ? def.getAttack(level) : 1;
    }

    public int getAttackCooldown() {
        MobCardDefinition def = getDefinition();
        return def != null ? def.attackCooldown() : 20;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putUuid("cardId", cardId);
        nbt.putString("mobId", mobId);
        nbt.putInt("level", level);
        return nbt;
    }

    public static MobCard fromNbt(NbtCompound nbt) {
        UUID id = nbt.getUuid("cardId");
        String mobId = nbt.getString("mobId");
        int level = nbt.getInt("level");
        return new MobCard(id, mobId, level);
    }
}
