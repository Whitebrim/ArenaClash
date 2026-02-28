package com.arenaclash.client.gui;

import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.client.ArenaClashClient;
import com.arenaclash.client.tcp.ArenaClashTcpClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

import java.util.*;

/**
 * Premium Card Upgrade Workbench screen.
 * Players select two identical cards (same mob type + same level) to merge
 * into a single card of the next level.
 */
public class CardUpgradeScreen extends Screen {

    private final CardInventory inventory;
    private int scrollOffset = 0;

    // Panel dimensions
    private int panelX, panelY, panelWidth, panelHeight;

    // Card list area bounds (for scissor clipping)
    private int listX, listY, listW, listH;

    // Card groups: mob cards grouped by (mobId, level) for merge candidates
    private List<CardGroup> cardGroups;

    // Selection state — stored as mobId:level key to survive screen rebuilds
    private String selectedKey = null;

    // Static selection memory — persists across screen recreations after merge
    private static String persistedSelectedKey = null;

    // Animation
    private float animTimer = 0;
    private long lastFrameTime = 0;

    // Merge cooldown (to prevent double-click)
    private int mergeCooldown = 0;
    private static int persistedMergeCooldown = 0;
    private static final int MERGE_COOLDOWN_MAX = 20; // 1 second

    // Scroll constants
    private static final int CARD_ROW_HEIGHT = 52;
    private static final int VISIBLE_ROWS = 5;

    // Merge result area
    private int mergeButtonX, mergeButtonY, mergeButtonW, mergeButtonH;

    /**
     * A group of cards with the same mobId and level.
     */
    private record CardGroup(String mobId, int level, List<MobCard> cards) {
        boolean canMerge() { return cards.size() >= 2; }
        String key() { return mobId + ":" + level; }
        MobCardDefinition definition() {
            return com.arenaclash.card.MobCardRegistry.getById(mobId);
        }
    }

    public CardUpgradeScreen(NbtCompound inventoryData) {
        super(Text.translatable("arenaclash.upgrade.title"));
        this.inventory = CardInventory.fromNbt(inventoryData);
        rebuildCardGroups();

        // Restore persisted selection
        if (persistedSelectedKey != null) {
            this.selectedKey = persistedSelectedKey;
            // If the group no longer exists, clear
            if (findGroupByKey(selectedKey) == null) {
                selectedKey = null;
                persistedSelectedKey = null;
            }
        }

        // Restore merge cooldown
        this.mergeCooldown = persistedMergeCooldown;
    }

    private void rebuildCardGroups() {
        Map<String, List<MobCard>> grouped = new LinkedHashMap<>();
        for (MobCard card : inventory.getAllCards()) {
            String key = card.getMobId() + ":" + card.getLevel();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(card);
        }

        cardGroups = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            String mobId = parts[0];
            int level = Integer.parseInt(parts[1]);
            cardGroups.add(new CardGroup(mobId, level, entry.getValue()));
        }

        // Sort: mergeable groups first, then by level desc, then by name
        cardGroups.sort((a, b) -> {
            if (a.canMerge() != b.canMerge()) return a.canMerge() ? -1 : 1;
            if (a.level != b.level) return b.level - a.level;
            return a.mobId.compareTo(b.mobId);
        });
    }

    private CardGroup findGroupByKey(String key) {
        if (key == null) return null;
        for (CardGroup g : cardGroups) {
            if (g.key().equals(key)) return g;
        }
        return null;
    }

    /** Get the currently selected group (resolved from key). */
    private CardGroup getSelectedGroup() {
        return findGroupByKey(selectedKey);
    }

    @Override
    protected void init() {
        panelWidth = 320;
        panelHeight = Math.min(height - 30, VISIBLE_ROWS * CARD_ROW_HEIGHT + 120);
        panelX = width / 2 - panelWidth / 2;
        panelY = height / 2 - panelHeight / 2;

        // Card list bounds
        listX = panelX + 10;
        listY = panelY + 42;
        listW = panelWidth - 20;
        listH = panelHeight - 100;

        // Merge button area (at bottom of panel, below list)
        mergeButtonW = 120;
        mergeButtonH = 22;
        mergeButtonX = width / 2 - mergeButtonW / 2;
        mergeButtonY = panelY + panelHeight - 30;
    }

    @Override
    public void tick() {
        super.tick();
        if (mergeCooldown > 0) {
            mergeCooldown--;
            persistedMergeCooldown = mergeCooldown;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        if (lastFrameTime != 0) {
            animTimer += (now - lastFrameTime) / 1000.0f;
        }
        lastFrameTime = now;

        super.render(context, mouseX, mouseY, delta);

        drawPanel(context);
        drawTitle(context);
        drawCardList(context, mouseX, mouseY);
        drawMergeArea(context, mouseX, mouseY);
        drawDecorations(context);
        drawHintBar(context);
    }

    // ================================================================
    // PANEL
    // ================================================================

    private void drawPanel(DrawContext context) {
        context.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2,
                panelY + panelHeight + 2, 0x20000000);
        context.fill(panelX - 1, panelY - 1, panelX + panelWidth + 1,
                panelY + panelHeight + 1, 0x40000000);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xD8101018);

        for (int i = 0; i < 3; i++) {
            float t = (float) i / 3.0f;
            int alpha = (int) (200 - t * 60);
            drawHGrad(context, panelX, panelY + i, panelX + panelWidth, panelY + i + 1,
                    (alpha << 24) | 0xCC6600, (alpha << 24) | 0xFF8800);
        }

        int borderColor = 0x25FFFFFF;
        context.fill(panelX, panelY + 3, panelX + 1, panelY + panelHeight, borderColor);
        context.fill(panelX + panelWidth - 1, panelY + 3,
                panelX + panelWidth, panelY + panelHeight, borderColor);
        context.fill(panelX, panelY + panelHeight - 1,
                panelX + panelWidth, panelY + panelHeight, borderColor);
    }

    // ================================================================
    // TITLE
    // ================================================================

    private void drawTitle(DrawContext context) {
        int cx = width / 2;
        int titleY = panelY + 10;

        float glowPulse = (float) (Math.sin(animTimer * 1.5) * 0.12 + 0.88);
        int glowAlpha = (int) (glowPulse * 255);
        int titleColor = (glowAlpha << 24) | 0xFFAA00;

        context.drawCenteredTextWithShadow(textRenderer,
                I18n.translate("arenaclash.upgrade.header"), cx, titleY, titleColor);

        int lineY = titleY + 12;
        int hw = 80;
        drawHGrad(context, cx - hw, lineY, cx, lineY + 1, 0x00FFAA00, 0x80FFAA00);
        drawHGrad(context, cx, lineY, cx + hw, lineY + 1, 0x80FFAA00, 0x00FFAA00);

        context.drawCenteredTextWithShadow(textRenderer,
                I18n.translate("arenaclash.upgrade.subtitle"), cx, titleY + 16, 0x999999);
    }

    // ================================================================
    // CARD LIST — with scissor clipping
    // ================================================================

    private void drawCardList(DrawContext context, int mouseX, int mouseY) {
        // List background
        context.fill(listX, listY, listX + listW, listY + listH, 0x40000000);
        context.drawBorder(listX, listY, listW, listH, 0x30FFFFFF);

        if (cardGroups.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    I18n.translate("arenaclash.upgrade.no_cards"),
                    width / 2, listY + listH / 2 - 4, 0x666666);
            return;
        }

        // Clamp scroll
        int maxScroll = Math.max(0, cardGroups.size() - VISIBLE_ROWS);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // Enable scissor to clip list contents within bounds
        context.enableScissor(listX, listY, listX + listW, listY + listH);

        int contentX = listX + 4;
        int contentW = listW - 8;
        int rowY = listY + 2;

        for (int i = scrollOffset; i < Math.min(scrollOffset + VISIBLE_ROWS, cardGroups.size()); i++) {
            CardGroup group = cardGroups.get(i);
            drawCardGroupRow(context, group, contentX, rowY, contentW,
                    CARD_ROW_HEIGHT - 4, mouseX, mouseY);
            rowY += CARD_ROW_HEIGHT;
        }

        context.disableScissor();

        // Scrollbar (drawn outside scissor)
        if (cardGroups.size() > VISIBLE_ROWS) {
            int sbX = listX + listW - 4;
            int sbH = listH - 4;
            int thumbH = Math.max(10, (int) ((float) VISIBLE_ROWS / cardGroups.size() * sbH));
            int thumbY = listY + 2 + (maxScroll > 0
                    ? (int) ((float) scrollOffset / maxScroll * (sbH - thumbH)) : 0);
            context.fill(sbX, listY + 2, sbX + 2, listY + listH - 2, 0x30FFFFFF);
            context.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, 0x80FFAA00);
        }
    }

    private void drawCardGroupRow(DrawContext context, CardGroup group, int x, int y,
                                   int w, int h, int mouseX, int mouseY) {
        MobCardDefinition def = group.definition();
        if (def == null) return;

        boolean isSelected = group.key().equals(selectedKey);
        boolean isHovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h
                && mouseY >= listY && mouseY <= listY + listH;
        boolean canMerge = group.canMerge();

        // Row background
        int bgColor;
        if (isSelected) {
            bgColor = 0x50FFAA00;
        } else if (isHovered && canMerge) {
            bgColor = 0x30FFAA00;
        } else {
            bgColor = canMerge ? 0x20333333 : 0x15222222;
        }
        context.fill(x, y, x + w, y + h, bgColor);

        // Left border accent (category color)
        int catColor = getCategoryColor(def.category());
        context.fill(x, y, x + 3, y + h, canMerge ? catColor : (catColor & 0x00FFFFFF) | 0x40000000);

        // Mob name + level
        String lvStr = I18n.translate("arenaclash.upgrade.lv", String.valueOf(group.level()));
        String mobName = I18n.translate(def.translationKey());
        String nameStr = "§f" + mobName + " §6" + lvStr;
        context.drawTextWithShadow(textRenderer, nameStr, x + 8, y + 4, 0xFFFFFF);

        // Count badge
        String countStr = "×" + group.cards().size();
        int countColor = canMerge ? 0xFF55FF55 : 0xFFAAAAAA;
        int countX = x + w - textRenderer.getWidth(countStr) - 6;
        context.drawTextWithShadow(textRenderer, countStr, countX, y + 4, countColor);

        // Stats line
        MobCard sample = group.cards().get(0);
        String stats = String.format("§c♥%.0f §a⚔%.0f §b⚡%.1f",
                sample.getHP(), sample.getAttack(), sample.getSpeed());
        context.drawTextWithShadow(textRenderer, stats, x + 8, y + 16, 0xAAAAAA);

        // Category tag
        String category = "§8[" + I18n.translate(def.categoryTranslationKey()) + "]";
        context.drawTextWithShadow(textRenderer, category, x + 8, y + 28, 0x666666);

        // Merge indicator
        if (canMerge) {
            int nextLevel = group.level() + 1;
            String resultStr = "§7→ §e" + I18n.translate("arenaclash.upgrade.lv", String.valueOf(nextLevel));
            context.drawTextWithShadow(textRenderer, resultStr,
                    countX - textRenderer.getWidth(resultStr) - 8, y + 16, 0xAAAAAA);

            if (isSelected) {
                float pulse = (float) (Math.sin(animTimer * 3) * 0.3 + 0.7);
                int mergeAlpha = (int) (pulse * 255);
                String mergeHint = I18n.translate("arenaclash.upgrade.click_merge");
                int hintW = textRenderer.getWidth(mergeHint);
                context.drawTextWithShadow(textRenderer, mergeHint,
                        x + w - hintW - 6, y + 28, (mergeAlpha << 24) | 0xFFAA00);
            }
        } else {
            String needStr = I18n.translate("arenaclash.upgrade.need_two");
            context.drawTextWithShadow(textRenderer, needStr,
                    countX - textRenderer.getWidth(needStr) - 8, y + 28, 0x555555);
        }

        if (isSelected) {
            context.drawBorder(x, y, w, h, 0xC0FFAA00);
        }
    }

    // ================================================================
    // MERGE AREA
    // ================================================================

    private void drawMergeArea(DrawContext context, int mouseX, int mouseY) {
        int areaY = mergeButtonY - 24;
        int cx = width / 2;

        CardGroup sel = getSelectedGroup();
        if (sel != null && sel.canMerge()) {
            MobCardDefinition def = sel.definition();
            if (def == null) return;

            String mobName = I18n.translate(def.translationKey());
            int curLv = sel.level();
            int newLv = curLv + 1;

            String preview = String.format("§f%s §6Lv.%d §7+ §f%s §6Lv.%d §7→ §e%s §6§lLv.%d",
                    mobName, curLv, mobName, curLv, mobName, newLv);
            context.drawCenteredTextWithShadow(textRenderer, preview, cx, areaY, 0xFFFFFF);

            double newHP = def.getHP(newLv);
            double newAtk = def.getAttack(newLv);
            String newStats = String.format("§c♥%.0f §a⚔%.0f", newHP, newAtk);
            context.drawCenteredTextWithShadow(textRenderer, newStats, cx, areaY + 11, 0xCCCCCC);

            // Merge button with cooldown animation
            boolean btnHovered = mouseX >= mergeButtonX && mouseX <= mergeButtonX + mergeButtonW
                    && mouseY >= mergeButtonY && mouseY <= mergeButtonY + mergeButtonH;
            boolean canClick = mergeCooldown <= 0;

            if (canClick) {
                // Ready state — normal orange button
                int btnBg = btnHovered ? 0xE0CC7700 : 0xC0AA5500;
                context.fill(mergeButtonX, mergeButtonY,
                        mergeButtonX + mergeButtonW, mergeButtonY + mergeButtonH, btnBg);
            } else {
                // Cooldown state — gray background with orange fill progressing left to right
                context.fill(mergeButtonX, mergeButtonY,
                        mergeButtonX + mergeButtonW, mergeButtonY + mergeButtonH, 0x60333333);

                float progress = 1.0f - (float) mergeCooldown / MERGE_COOLDOWN_MAX;
                int fillW = (int) (mergeButtonW * progress);
                if (fillW > 0) {
                    // Orange fill from left
                    drawHGrad(context, mergeButtonX, mergeButtonY,
                            mergeButtonX + fillW, mergeButtonY + mergeButtonH,
                            0xC0884400, 0xC0AA5500);
                }
            }

            int btnBorder = canClick ? (btnHovered ? 0xFFFFAA00 : 0x80FFAA00) : 0x40666666;
            context.drawBorder(mergeButtonX, mergeButtonY, mergeButtonW, mergeButtonH, btnBorder);

            String btnText = I18n.translate("arenaclash.upgrade.merge_button");
            int btnTextColor = canClick ? 0xFFFFFF : 0x888888;
            context.drawCenteredTextWithShadow(textRenderer, btnText,
                    mergeButtonX + mergeButtonW / 2, mergeButtonY + 7, btnTextColor);
        } else {
            String hint = I18n.translate("arenaclash.upgrade.select_hint");
            context.drawCenteredTextWithShadow(textRenderer, hint, cx, areaY + 8, 0x666666);
        }
    }

    // ================================================================
    // DECORATIONS
    // ================================================================

    private void drawDecorations(DrawContext context) {
        int accentColor = 0x35FFAA00;
        int len = 10;

        context.fill(panelX, panelY, panelX + len, panelY + 1, accentColor);
        context.fill(panelX, panelY, panelX + 1, panelY + len, accentColor);
        context.fill(panelX + panelWidth - len, panelY, panelX + panelWidth, panelY + 1, accentColor);
        context.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + len, accentColor);
        context.fill(panelX, panelY + panelHeight - 1, panelX + len, panelY + panelHeight, accentColor);
        context.fill(panelX, panelY + panelHeight - len, panelX + 1, panelY + panelHeight, accentColor);
        context.fill(panelX + panelWidth - len, panelY + panelHeight - 1,
                panelX + panelWidth, panelY + panelHeight, accentColor);
        context.fill(panelX + panelWidth - 1, panelY + panelHeight - len,
                panelX + panelWidth, panelY + panelHeight, accentColor);
    }

    private void drawHintBar(DrawContext context) {
        context.drawCenteredTextWithShadow(textRenderer,
                I18n.translate("arenaclash.upgrade.hint"),
                width / 2, panelY + panelHeight + 4, 0x666666);
    }

    // ================================================================
    // INPUT
    // ================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Check merge button FIRST (higher priority than list rows)
            CardGroup sel = getSelectedGroup();
            if (sel != null && sel.canMerge() && mergeCooldown <= 0) {
                if (mouseX >= mergeButtonX && mouseX <= mergeButtonX + mergeButtonW
                        && mouseY >= mergeButtonY && mouseY <= mergeButtonY + mergeButtonH) {
                    performMerge();
                    return true;
                }
            }

            // Check card group rows — only within list bounds
            int contentX = listX + 4;
            int contentW = listW - 8;

            if (mouseX >= contentX && mouseX <= contentX + contentW
                    && mouseY >= listY && mouseY <= listY + listH) {
                for (int i = scrollOffset; i < Math.min(scrollOffset + VISIBLE_ROWS, cardGroups.size()); i++) {
                    int rowY = listY + 2 + (i - scrollOffset) * CARD_ROW_HEIGHT;
                    int rowBottom = rowY + CARD_ROW_HEIGHT - 4;
                    if (rowBottom > listY + listH) break;
                    if (mouseY >= rowY && mouseY <= rowBottom) {
                        CardGroup group = cardGroups.get(i);
                        if (group.canMerge()) {
                            selectedKey = group.key();
                            persistedSelectedKey = selectedKey;
                        }
                        return true;
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, cardGroups.size() - VISIBLE_ROWS);
        if (verticalAmount > 0 && scrollOffset > 0) {
            scrollOffset--;
        } else if (verticalAmount < 0 && scrollOffset < maxScroll) {
            scrollOffset++;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            this.close();
            return true;
        }
        if (keyCode == 32) { // SPACE — quick merge like Satisfactory crafter
            CardGroup sel = getSelectedGroup();
            if (sel != null && sel.canMerge() && mergeCooldown <= 0) {
                performMerge();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void performMerge() {
        CardGroup sel = getSelectedGroup();
        if (sel == null || !sel.canMerge()) return;
        if (mergeCooldown > 0) return;

        MobCard card1 = sel.cards().get(0);
        MobCard card2 = sel.cards().get(1);

        ArenaClashTcpClient tcp = ArenaClashClient.getTcpClient();
        if (tcp != null && tcp.isConnected()) {
            tcp.sendMergeCards(
                    card1.getCardId().toString(),
                    card2.getCardId().toString()
            );
            mergeCooldown = MERGE_COOLDOWN_MAX;
            persistedMergeCooldown = mergeCooldown;

            // Keep selection: if 3+ cards, group stays at same level.
            // If exactly 2, group disappears — select the result level.
            if (sel.cards().size() <= 2) {
                String resultKey = sel.mobId() + ":" + (sel.level() + 1);
                persistedSelectedKey = resultKey;
                selectedKey = resultKey;
            }
            // else: persistedSelectedKey stays the same (group still exists)
        }
    }

    /** Call this to clear persisted state when closing workbench for real. */
    public static void clearPersistedState() {
        persistedSelectedKey = null;
        persistedMergeCooldown = 0;
    }

    // ================================================================
    // UTILITY
    // ================================================================

    private void drawHGrad(DrawContext ctx, int x1, int y1, int x2, int y2, int cL, int cR) {
        int w = x2 - x1;
        if (w <= 0) return;
        for (int i = 0; i < w; i++) {
            float t = (float) i / w;
            ctx.fill(x1 + i, y1, x1 + i + 1, y2, lerpColor(cL, cR, t));
        }
    }

    private static int lerpColor(int c1, int c2, float t) {
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF, r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        return ((int)(a1 + (a2 - a1) * t) << 24) | ((int)(r1 + (r2 - r1) * t) << 16)
                | ((int)(g1 + (g2 - g1) * t) << 8) | (int)(b1 + (b2 - b1) * t);
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

    @Override
    public boolean shouldPause() {
        return false;
    }
}
