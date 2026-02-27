package com.arenaclash.client.gui;

import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.card.MobCardRegistry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

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

    public CardScreen(NbtCompound inventoryData) {
        super(Text.translatable("arenaclash.screen.cards.title", "0"));
        this.inventory = CardInventory.fromNbt(inventoryData);
    }

    @Override
    protected void init() {
        super.init();

        // Scroll buttons
        this.addDrawableChild(ButtonWidget.builder(Text.literal("▲"), button -> {
            if (scrollOffset > 0) scrollOffset--;
        }).dimensions(width / 2 + CARD_WIDTH / 2 + 10, height / 2 - 80, 20, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("▼"), button -> {
            if (scrollOffset < Math.max(0, inventory.getCardCount() - CARDS_PER_PAGE)) scrollOffset++;
        }).dimensions(width / 2 + CARD_WIDTH / 2 + 10, height / 2 + 60, 20, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Draw dark semi-transparent background (renderBackground is overridden to no-op)
        context.fill(0, 0, width, height, 0xC0101010);

        // Title
        context.drawCenteredTextWithShadow(textRenderer,
                I18n.translate("arenaclash.screen.cards.title", String.valueOf(inventory.getCardCount())),
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
            context.fill(startX - 2, y - 2, startX + CARD_WIDTH + 2, y + CARD_HEIGHT + 2, 0x80000000);

            // Card border (color by category)
            int borderColor = getCategoryColor(def.category());
            context.drawBorder(startX - 2, y - 2, CARD_WIDTH + 4, CARD_HEIGHT + 4, borderColor);

            // Mob name + level
            String name = "§f" + I18n.translate(def.translationKey()) + " " + I18n.translate("arenaclash.screen.cards.lv", String.valueOf(card.getLevel()));
            context.drawTextWithShadow(textRenderer, name, startX + 4, y + 2, 0xFFFFFF);

            // Stats
            String stats = String.format("§c♥%.0f §a⚔%.0f §b⚡%.1f",
                    card.getHP(), card.getAttack(), card.getSpeed());
            context.drawTextWithShadow(textRenderer, stats, startX + 4, y + 14, 0xAAAAAA);

            // Category
            String category = "§7[" + I18n.translate(def.categoryTranslationKey()) + "]";
            context.drawTextWithShadow(textRenderer, category, startX + 4, y + 26, 0x888888);

            // Equipment info
            String equip = "";
            if (def.canEquipWeapon()) equip += "§d[⚔] ";
            if (def.canEquipArmor()) equip += "§9[🛡] ";
            if (!equip.isEmpty()) {
                context.drawTextWithShadow(textRenderer, equip,
                        startX + CARD_WIDTH - textRenderer.getWidth(equip) - 4, y + 2, 0xFFFFFF);
            }
        }

        // Scrollbar indicator
        if (cards.size() > CARDS_PER_PAGE) {
            int scrollBarHeight = (int)((float)CARDS_PER_PAGE / cards.size() * (CARDS_PER_PAGE * (CARD_HEIGHT + 4)));
            int scrollBarY = startY + (int)((float)scrollOffset / cards.size() * (CARDS_PER_PAGE * (CARD_HEIGHT + 4)));
            context.fill(startX + CARD_WIDTH + 4, scrollBarY, startX + CARD_WIDTH + 8, scrollBarY + scrollBarHeight, 0xFFAAAAAA);
        }

        // Instructions
        context.drawCenteredTextWithShadow(textRenderer,
                I18n.translate("arenaclash.screen.cards.hint"),
                width / 2, height - 20, 0x888888);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount > 0 && scrollOffset > 0) {
            scrollOffset--;
        } else if (verticalAmount < 0 && scrollOffset < inventory.getCardCount() - CARDS_PER_PAGE) {
            scrollOffset++;
        }
        return true;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // No-op: prevent 1.21.1 from applying blur shader behind the screen
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
