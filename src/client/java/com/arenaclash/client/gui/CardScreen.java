package com.arenaclash.client.gui;

import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.card.MobCardRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * GUI screen showing the player's card inventory.
 * Cards are displayed as a scrollable list with mob name, level, and stats.
 */
public class CardScreen extends Screen {
    private final CardInventory inventory;
    private int scrollOffset = 0;
    private static final int CARD_HEIGHT = 40;
    private static final int CARD_WIDTH = 200;
    private static final int CARDS_PER_PAGE = 6;

    public CardScreen(CompoundTag inventoryData) {
        super(Component.translatable("arenaclash.screen.cards.title", "0"));
        this.inventory = CardInventory.fromNbt(inventoryData);
    }

    @Override
    protected void init() {
        super.init();

        // Scroll buttons
        this.addRenderableWidget(Button.builder(Component.literal("\u25B2"), button -> {
            if (scrollOffset > 0) scrollOffset--;
        }).bounds(width / 2 + CARD_WIDTH / 2 + 10, height / 2 - 80, 20, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("\u25BC"), button -> {
            if (scrollOffset < Math.max(0, inventory.getCardCount() - CARDS_PER_PAGE)) scrollOffset++;
        }).bounds(width / 2 + CARD_WIDTH / 2 + 10, height / 2 + 60, 20, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Draw dark semi-transparent background (renderBackground is overridden to no-op)
        guiGraphics.fill(0, 0, width, height, 0xC0101010);

        // Title
        guiGraphics.drawCenteredString(font,
                I18n.get("arenaclash.screen.cards.title", String.valueOf(inventory.getCardCount())),
                width / 2, 20, 0xFFFFFF);

        // Draw cards
        List<MobCard> cards = inventory.getAllCards();
        int startX = width / 2 - CARD_WIDTH / 2;
        int startY = 45;

        for (int i = scrollOffset; i < Math.min(scrollOffset + CARDS_PER_PAGE, cards.size()); i++) {
            MobCard card = cards.get(i);
            MobCardDefinition def = card.getDefinition();
            if (def == null) continue;

            int y = startY + (i - scrollOffset) * (CARD_HEIGHT + 4);

            // Card background
            guiGraphics.fill(startX - 2, y - 2, startX + CARD_WIDTH + 2, y + CARD_HEIGHT + 2, 0x80000000);

            // Card border (color by category)
            int borderColor = getCategoryColor(def.category());
            guiGraphics.renderOutline(startX - 2, y - 2, CARD_WIDTH + 4, CARD_HEIGHT + 4, borderColor);

            // Mob name + level
            String lvSuffix = com.arenaclash.card.MobCardRegistry.isUpgradeLocked(card.getMobId())
                    ? "" : " " + I18n.get("arenaclash.screen.cards.lv", String.valueOf(card.getLevel()));
            String name = "\u00A7f" + I18n.get(def.translationKey()) + lvSuffix;
            guiGraphics.drawString(font, name, startX + 4, y + 2, 0xFFFFFF);

            // Stats
            String stats = String.format("\u00A7c\u2665%.0f \u00A7a\u2694%.0f \u00A7b\u26A1%.1f",
                    card.getHP(), card.getAttack(), card.getSpeed());
            guiGraphics.drawString(font, stats, startX + 4, y + 14, 0xAAAAAA);

            // Category
            String category = "\u00A77[" + I18n.get(def.categoryTranslationKey()) + "]";
            guiGraphics.drawString(font, category, startX + 4, y + 26, 0x888888);

            // Equipment info
            String equip = "";
            if (def.canEquipWeapon()) equip += "\u00A7d[\u2694] ";
            if (def.canEquipArmor()) equip += "\u00A79[\uD83D\uDEE1] ";
            if (!equip.isEmpty()) {
                guiGraphics.drawString(font, equip,
                        startX + CARD_WIDTH - font.width(equip) - 4, y + 2, 0xFFFFFF);
            }
        }

        // Scrollbar indicator
        if (cards.size() > CARDS_PER_PAGE) {
            int scrollBarHeight = (int)((float)CARDS_PER_PAGE / cards.size() * (CARDS_PER_PAGE * (CARD_HEIGHT + 4)));
            int scrollBarY = startY + (int)((float)scrollOffset / cards.size() * (CARDS_PER_PAGE * (CARD_HEIGHT + 4)));
            guiGraphics.fill(startX + CARD_WIDTH + 4, scrollBarY, startX + CARD_WIDTH + 8, scrollBarY + scrollBarHeight, 0xFFAAAAAA);
        }

        // Instructions
        guiGraphics.drawCenteredString(font,
                I18n.get("arenaclash.screen.cards.hint"),
                width / 2, height - 20, 0x888888);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
        } else if (scrollY < 0 && scrollOffset < inventory.getCardCount() - CARDS_PER_PAGE) {
            scrollOffset++;
        }
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // No-op: prevent blur shader behind the screen
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int getCategoryColor(MobCardDefinition.MobCategory category) {
        return switch (category) {
            case UNDEAD -> 0xFF44AA44;
            case ARTHROPOD -> 0xFF886644;
            case NETHER -> 0xFFFF4444;
            case END -> 0xFFAA44FF;
            case GOLEM -> 0xFFCCCCCC;
            case ILLAGER -> 0xFF4488AA;
            case ANIMAL -> 0xFF88CC44;
            case NEUTRAL -> 0xFFAAAA44;
            case BOSS -> 0xFFFFAA00;
        };
    }
}
