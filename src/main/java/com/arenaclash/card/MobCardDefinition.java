package com.arenaclash.card;

import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;

import java.util.List;

/**
 * Immutable definition of a mob card type. Registered once per mob type.
 */
public record MobCardDefinition(
        EntityType<?> entityType,
        String id,
        String displayName,
        double baseHP,
        double baseSpeed,        // blocks per second
        double baseAttack,       // damage per hit without weapon
        int attackCooldown,      // ticks between attacks
        boolean canEquipWeapon,
        boolean canEquipArmor,
        List<String> allowedWeaponTags,  // e.g. "bow", "crossbow", "sword", "potion"
        MobCategory category
) {
    public enum MobCategory {
        UNDEAD,
        ARTHROPOD,
        NETHER,
        END,
        GOLEM,
        ILLAGER,
        ANIMAL,
        NEUTRAL,
        BOSS
    }

    /**
     * Returns the translation key for this mob card's display name.
     * Used with I18n.translate() on client or Text.translatable() on server.
     */
    public String translationKey() {
        return "arenaclash.mob." + id;
    }

    /**
     * Returns the translation key for this mob's category.
     */
    public String categoryTranslationKey() {
        return "arenaclash.category." + category.name().toLowerCase();
    }

    /**
     * Get scaled HP for given level. Stats scale linearly with level.
     */
    public double getHP(int level) {
        return baseHP * level;
    }

    public double getSpeed(int level) {
        return baseSpeed; // Speed doesn't scale with level
    }

    public double getAttack(int level) {
        return baseAttack * level;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", id);
        nbt.putString("displayName", displayName);
        nbt.putDouble("baseHP", baseHP);
        nbt.putDouble("baseSpeed", baseSpeed);
        nbt.putDouble("baseAttack", baseAttack);
        nbt.putInt("attackCooldown", attackCooldown);
        nbt.putBoolean("canEquipWeapon", canEquipWeapon);
        nbt.putBoolean("canEquipArmor", canEquipArmor);
        nbt.putString("category", category.name());
        return nbt;
    }
}
