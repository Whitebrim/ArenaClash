package com.arenaclash.card;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.*;

/**
 * Stores all cards owned by a player. Serialized to NBT and attached to player data.
 */
public class CardInventory {
    private final List<MobCard> cards = new ArrayList<>();

    public void addCard(MobCard card) {
        cards.add(card);
    }

    public boolean removeCard(UUID cardId) {
        return cards.removeIf(c -> c.getCardId().equals(cardId));
    }

    public MobCard getCard(UUID cardId) {
        return cards.stream()
                .filter(c -> c.getCardId().equals(cardId))
                .findFirst()
                .orElse(null);
    }

    public List<MobCard> getAllCards() {
        return Collections.unmodifiableList(cards);
    }

    public List<MobCard> getCardsByMobId(String mobId) {
        return cards.stream()
                .filter(c -> c.getMobId().equals(mobId))
                .toList();
    }

    public int getCardCount() {
        return cards.size();
    }

    public void clear() {
        cards.clear();
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        ListTag list = new ListTag();
        for (MobCard card : cards) {
            list.add(card.toNbt());
        }
        nbt.put("cards", list);
        return nbt;
    }

    public static CardInventory fromNbt(CompoundTag nbt) {
        CardInventory inv = new CardInventory();
        ListTag list = nbt.getListOrEmpty("cards");
        for (int i = 0; i < list.size(); i++) {
            inv.cards.add(MobCard.fromNbt(list.getCompoundOrEmpty(i)));
        }
        return inv;
    }
}
