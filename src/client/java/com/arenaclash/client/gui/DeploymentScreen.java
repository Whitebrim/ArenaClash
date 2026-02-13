package com.arenaclash.client.gui;

import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.network.NetworkHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

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
    private final NbtCompound slotData;

    // Selection state
    private MobCard selectedCard = null;
    private int cardScrollOffset = 0;

    // Layout constants
    private static final int CARD_LIST_WIDTH = 180;
    private static final int CARD_ENTRY_HEIGHT = 28;
    private static final int CARDS_VISIBLE = 8;
    private static final int SLOT_SIZE = 40;
    private static final int LANE_GAP = 20;

    // Lane names
    private static final String[] LANE_NAMES = {"LEFT", "CENTER", "RIGHT"};
    private static final String[] LANE_DISPLAY = {"Left Lane", "Center Lane", "Right Lane"};

    // Slot states from server
    private final String[][] slotCards = new String[3][4]; // [lane][slot] = mob display name or null
    private final boolean[][] slotOccupied = new boolean[3][4];

    public DeploymentScreen(NbtCompound inventoryData, NbtCompound slotData) {
        super(Text.literal("Deploy Your Forces"));
        this.inventory = CardInventory.fromNbt(inventoryData);
        this.slotData = slotData;
        parseSlotData();
    }

    private void parseSlotData() {
        if (slotData == null) return;
        for (int l = 0; l < 3; l++) {
            String laneName = LANE_NAMES[l];
            if (!slotData.contains(laneName)) continue;
            NbtCompound laneNbt = slotData.getCompound(laneName);
            for (int s = 0; s < 4; s++) {
                String key = "slot_" + s;
                if (!laneNbt.contains(key)) continue;
                NbtCompound slotNbt = laneNbt.getCompound(key);
                slotOccupied[l][s] = !slotNbt.getBoolean("empty");
                if (slotOccupied[l][s] && slotNbt.contains("card")) {
                    NbtCompound cardNbt = slotNbt.getCompound("card");
                    String mobId = cardNbt.getString("mobId");
                    var def = com.arenaclash.card.MobCardRegistry.getById(mobId);
                    slotCards[l][s] = def != null ? def.displayName() : mobId;
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

        this.addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> {
            if (cardScrollOffset > 0) cardScrollOffset--;
        }).dimensions(listX + CARD_LIST_WIDTH + 5, listY, 18, 18).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> {
            int max = Math.max(0, inventory.getCardCount() - CARDS_VISIBLE);
            if (cardScrollOffset < max) cardScrollOffset++;
        }).dimensions(listX + CARD_LIST_WIDTH + 5, listY + CARDS_VISIBLE * CARD_ENTRY_HEIGHT - 18, 18, 18).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Dark overlay without blur (renderBackground in 1.21.1 applies blur shader)
        ctx.fill(0, 0, width, height, 0xC0101010);

        // Title
        ctx.drawCenteredTextWithShadow(textRenderer, "§6§lDeploy Your Forces", width / 2, 10, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "§7Click a card, then click a slot to place it. Right-click slot to remove.",
                width / 2, 25, 0x888888);

        // === Left: Card List ===
        int listX = 20;
        int listY = 50;
        ctx.fill(listX - 2, listY - 2, listX + CARD_LIST_WIDTH + 2, listY + CARDS_VISIBLE * CARD_ENTRY_HEIGHT + 2, 0x80000000);
        ctx.drawTextWithShadow(textRenderer, "§eCards (" + inventory.getCardCount() + ")", listX, listY - 14, 0xFFFF00);

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
            ctx.fill(listX, y, listX + CARD_LIST_WIDTH, y + CARD_ENTRY_HEIGHT - 1, bgColor);

            // Name
            ctx.drawTextWithShadow(textRenderer, def.displayName() + " Lv." + card.getLevel(), listX + 4, y + 2, 0xFFFFFF);
            // Stats line
            String stats = String.format("♥%.0f ⚔%.0f ⚡%.1f", card.getHP(), card.getAttack(), card.getSpeed());
            ctx.drawTextWithShadow(textRenderer, stats, listX + 4, y + 14, 0xAAAAAA);
        }

        // === Right: Lane Slots ===
        int slotsStartX = listX + CARD_LIST_WIDTH + 40;
        int slotsStartY = 60;

        for (int l = 0; l < 3; l++) {
            int laneX = slotsStartX + l * (SLOT_SIZE * 2 + LANE_GAP);

            // Lane title
            ctx.drawCenteredTextWithShadow(textRenderer, "§b" + LANE_DISPLAY[l],
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
                ctx.fill(sx, sy, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, slotBg);
                ctx.drawBorder(sx, sy, SLOT_SIZE - 1, SLOT_SIZE - 1, isHovered ? 0xFFFFFF00 : 0xFF666666);

                if (occupied && slotCards[l][s] != null) {
                    // Draw mob name in slot
                    String name = slotCards[l][s];
                    if (name.length() > 8) name = name.substring(0, 7) + "…";
                    ctx.drawCenteredTextWithShadow(textRenderer, name, sx + SLOT_SIZE / 2, sy + SLOT_SIZE / 2 - 4, 0x44FF44);
                } else {
                    ctx.drawCenteredTextWithShadow(textRenderer, "§8Empty", sx + SLOT_SIZE / 2, sy + SLOT_SIZE / 2 - 4, 0x444444);
                }
            }
        }

        // Selected card indicator
        if (selectedCard != null) {
            MobCardDefinition def = selectedCard.getDefinition();
            String name = def != null ? def.displayName() : "???";
            ctx.drawTextWithShadow(textRenderer, "§aSelected: §f" + name, slotsStartX, slotsStartY + SLOT_SIZE * 2 + 10, 0x44FF44);
        }

        // Keybind hints
        ctx.drawCenteredTextWithShadow(textRenderer,
                "§7[B] Ring Bell (Ready) | [ESC] Close",
                width / 2, height - 15, 0x888888);

        super.render(ctx, mouseX, mouseY, delta);
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
                        slotCards[l][s] = def != null ? def.displayName() : "Mob";
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
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (vAmount > 0 && cardScrollOffset > 0) cardScrollOffset--;
        else if (vAmount < 0) {
            int max = Math.max(0, inventory.getCardCount() - CARDS_VISIBLE);
            if (cardScrollOffset < max) cardScrollOffset++;
        }
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
