package com.arenaclash.client.gui;

import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.network.NetworkHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deployment screen shown during preparation phase.
 * Left side: card inventory (scrollable list)
 * Right side: 3 lanes with 4 slots each (2x2 grid per lane)
 * Click card -> select it -> click slot -> place it
 */
public class DeploymentScreen extends Screen {
    private final CardInventory inventory;
    private final CompoundTag slotData;

    // Selection state
    private MobCard selectedCard = null;
    private int cardScrollOffset = 0;

    // Static scroll memory — persists when screen is recreated on CARD_SYNC
    private static int persistedScrollOffset = 0;

    // Layout constants
    private static final int CARD_LIST_WIDTH = 180;
    private static final int CARD_ENTRY_HEIGHT = 28;
    private static final int CARDS_VISIBLE = 8;
    private static final int SLOT_SIZE = 40;
    private static final int LANE_GAP = 20;

    // Lane names
    private static final String[] LANE_NAMES = {"LEFT", "CENTER", "RIGHT"};
    private static final String[] LANE_DISPLAY_KEYS = {
            "arenaclash.screen.deploy.lane.left",
            "arenaclash.screen.deploy.lane.center",
            "arenaclash.screen.deploy.lane.right"
    };

    // Slot states from server
    private final String[][] slotCards = new String[3][4]; // [lane][slot] = mob display name or null
    private final boolean[][] slotOccupied = new boolean[3][4];

    public DeploymentScreen(CompoundTag inventoryData, CompoundTag slotData) {
        super(Component.translatable("arenaclash.screen.deploy.title"));
        this.inventory = CardInventory.fromNbt(inventoryData);
        this.slotData = slotData;
        parseSlotData();
        // Restore persisted scroll position, clamped to valid range
        int maxScroll = Math.max(0, inventory.getCardCount() - CARDS_VISIBLE);
        this.cardScrollOffset = Math.min(persistedScrollOffset, maxScroll);
    }

    private void parseSlotData() {
        if (slotData == null) return;
        for (int l = 0; l < 3; l++) {
            String laneName = LANE_NAMES[l];
            if (!slotData.contains(laneName)) continue;
            CompoundTag laneNbt = slotData.getCompound(laneName);
            for (int s = 0; s < 4; s++) {
                String key = "slot_" + s;
                if (!laneNbt.contains(key)) continue;
                CompoundTag slotNbt = laneNbt.getCompound(key);
                slotOccupied[l][s] = !slotNbt.getBoolean("empty");
                if (slotOccupied[l][s] && slotNbt.contains("card")) {
                    CompoundTag cardNbt = slotNbt.getCompound("card");
                    String mobId = cardNbt.getString("mobId");
                    var def = com.arenaclash.card.MobCardRegistry.getById(mobId);
                    slotCards[l][s] = def != null ? I18n.get(def.translationKey()) : mobId;
                }
            }
        }
    }

    @Override
    protected void init() {
        super.init();

        // Scroll buttons for card list
        int listX = 20;
        int listY = 50;

        this.addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> {
            if (cardScrollOffset > 0) cardScrollOffset--;
            persistedScrollOffset = cardScrollOffset;
        }).bounds(listX + CARD_LIST_WIDTH + 5, listY, 18, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> {
            int max = Math.max(0, inventory.getCardCount() - CARDS_VISIBLE);
            if (cardScrollOffset < max) cardScrollOffset++;
            persistedScrollOffset = cardScrollOffset;
        }).bounds(listX + CARD_LIST_WIDTH + 5, listY + CARDS_VISIBLE * CARD_ENTRY_HEIGHT - 18, 18, 18).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark overlay without blur (renderBackground in 1.21.1 applies blur shader)
        guiGraphics.fill(0, 0, width, height, 0xC0101010);

        // Title
        guiGraphics.drawCenteredString(font, I18n.get("arenaclash.screen.deploy.title"), width / 2, 10, 0xFFFFFF);
        guiGraphics.drawCenteredString(font,
                I18n.get("arenaclash.screen.deploy.hint"),
                width / 2, 25, 0x888888);

        // === Left: Card List ===
        int listX = 20;
        int listY = 50;
        guiGraphics.fill(listX - 2, listY - 2, listX + CARD_LIST_WIDTH + 2, listY + CARDS_VISIBLE * CARD_ENTRY_HEIGHT + 2, 0x80000000);
        guiGraphics.drawString(font, I18n.get("arenaclash.screen.deploy.cards_count", String.valueOf(inventory.getCardCount())), listX, listY - 14, 0xFFFF00);

        List<MobCard> cards = inventory.getAllCards();
        for (int i = cardScrollOffset; i < Math.min(cardScrollOffset + CARDS_VISIBLE, cards.size()); i++) {
            MobCard card = cards.get(i);
            MobCardDefinition def = card.getDefinition();
            if (def == null) continue;

            int y = listY + (i - cardScrollOffset) * CARD_ENTRY_HEIGHT;
            boolean isSelected = selectedCard != null && selectedCard.getCardId().equals(card.getCardId());
            boolean isHovered = mouseX >= listX && mouseX <= listX + CARD_LIST_WIDTH && mouseY >= y && mouseY < y + CARD_ENTRY_HEIGHT;

            // Background
            int bgColor = isSelected ? 0xC0445588 : (isHovered ? 0x80444444 : 0x40222222);
            guiGraphics.fill(listX, y, listX + CARD_LIST_WIDTH, y + CARD_ENTRY_HEIGHT - 1, bgColor);

            // Name
            String lvSuffix = com.arenaclash.card.MobCardRegistry.isUpgradeLocked(card.getMobId())
                    ? "" : " " + I18n.get("arenaclash.screen.deploy.lv", String.valueOf(card.getLevel()));
            guiGraphics.drawString(font, I18n.get(def.translationKey()) + lvSuffix, listX + 4, y + 2, 0xFFFFFF);
            // Stats line
            String stats = String.format("\u2665%.0f \u2694%.0f \u26A1%.1f", card.getHP(), card.getAttack(), card.getSpeed());
            guiGraphics.drawString(font, stats, listX + 4, y + 14, 0xAAAAAA);
        }

        // === Right: Lane Slots ===
        int slotsStartX = listX + CARD_LIST_WIDTH + 40;
        int slotsStartY = 60;

        for (int l = 0; l < 3; l++) {
            int laneX = slotsStartX + l * (SLOT_SIZE * 2 + LANE_GAP);

            // Lane title
            guiGraphics.drawCenteredString(font, "\u00A7b" + I18n.get(LANE_DISPLAY_KEYS[l]),
                    laneX + SLOT_SIZE, slotsStartY - 14, 0x55FFFF);

            // 2x2 grid
            for (int s = 0; s < 4; s++) {
                int col = s % 2;
                int row = s / 2;
                int sx = laneX + col * SLOT_SIZE;
                int sy = slotsStartY + row * SLOT_SIZE;

                boolean isHovered = mouseX >= sx && mouseX < sx + SLOT_SIZE - 1 && mouseY >= sy && mouseY < sy + SLOT_SIZE - 1;
                boolean occupied = slotOccupied[l][s];

                // Slot background
                int slotBg = occupied ? 0xC0224422 : (isHovered ? 0x80555555 : 0x60333333);
                guiGraphics.fill(sx, sy, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, slotBg);
                guiGraphics.renderOutline(sx, sy, SLOT_SIZE - 1, SLOT_SIZE - 1, isHovered ? 0xFFFFFF00 : 0xFF666666);

                if (occupied && slotCards[l][s] != null) {
                    // Draw mob name in slot
                    String name = slotCards[l][s];
                    if (name.length() > 8) name = name.substring(0, 7) + "\u2026";
                    guiGraphics.drawCenteredString(font, name, sx + SLOT_SIZE / 2, sy + SLOT_SIZE / 2 - 4, 0x44FF44);
                } else {
                    guiGraphics.drawCenteredString(font, I18n.get("arenaclash.screen.deploy.empty"), sx + SLOT_SIZE / 2, sy + SLOT_SIZE / 2 - 4, 0x444444);
                }
            }
        }

        // Selected card indicator
        if (selectedCard != null) {
            MobCardDefinition def = selectedCard.getDefinition();
            String name = def != null ? I18n.get(def.translationKey()) : "???";
            guiGraphics.drawString(font, I18n.get("arenaclash.screen.deploy.selected", name), slotsStartX, slotsStartY + SLOT_SIZE * 2 + 10, 0x44FF44);
        }

        // Keybind hints
        guiGraphics.drawCenteredString(font,
                I18n.get("arenaclash.screen.deploy.keybinds"),
                width / 2, height - 15, 0x888888);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Left click on card list -> select card
        int listX = 20;
        int listY = 50;

        if (mouseX >= listX && mouseX <= listX + CARD_LIST_WIDTH) {
            List<MobCard> cards = inventory.getAllCards();
            for (int i = cardScrollOffset; i < Math.min(cardScrollOffset + CARDS_VISIBLE, cards.size()); i++) {
                int y = listY + (i - cardScrollOffset) * CARD_ENTRY_HEIGHT;
                if (mouseY >= y && mouseY < y + CARD_ENTRY_HEIGHT) {
                    selectedCard = cards.get(i);
                    return true;
                }
            }
        }

        // Click on lane slots
        int slotsStartX = listX + CARD_LIST_WIDTH + 40;
        int slotsStartY = 60;

        for (int l = 0; l < 3; l++) {
            int laneX = slotsStartX + l * (SLOT_SIZE * 2 + LANE_GAP);
            for (int s = 0; s < 4; s++) {
                int col = s % 2;
                int row = s / 2;
                int sx = laneX + col * SLOT_SIZE;
                int sy = slotsStartY + row * SLOT_SIZE;

                if (mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                    if (button == 0 && selectedCard != null && !slotOccupied[l][s]) {
                        // Place card
                        ClientPlayNetworking.send(new NetworkHandler.PlaceCardRequest(
                                selectedCard.getCardId().toString(), LANE_NAMES[l], s));
                        // Optimistic UI update
                        slotOccupied[l][s] = true;
                        var def = selectedCard.getDefinition();
                        slotCards[l][s] = def != null ? I18n.get(def.translationKey()) : "Mob";
                        inventory.removeCard(selectedCard.getCardId());
                        selectedCard = null;
                        return true;
                    } else if (button == 1 && slotOccupied[l][s]) {
                        // Remove card (right click)
                        ClientPlayNetworking.send(new NetworkHandler.RemoveCardRequest(LANE_NAMES[l], s));
                        slotOccupied[l][s] = false;
                        slotCards[l][s] = null;
                        return true;
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0 && cardScrollOffset > 0) cardScrollOffset--;
        else if (scrollY < 0) {
            int max = Math.max(0, inventory.getCardCount() - CARDS_VISIBLE);
            if (cardScrollOffset < max) cardScrollOffset++;
        }
        persistedScrollOffset = cardScrollOffset;
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
}
