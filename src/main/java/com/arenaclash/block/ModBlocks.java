package com.arenaclash.block;

import com.arenaclash.ArenaClash;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * Registers all custom blocks and block items for Arena Clash.
 */
public class ModBlocks {

    public static final Block CARD_UPGRADE_WORKBENCH = new CardUpgradeWorkbenchBlock(
            AbstractBlock.Settings.create()
                    .mapColor(MapColor.SPRUCE_BROWN)
                    .strength(2.5f)
                    .sounds(BlockSoundGroup.WOOD)
    );

    public static void register() {
        // Register block
        Registry.register(Registries.BLOCK,
                Identifier.of(ArenaClash.MOD_ID, "card_upgrade_workbench"),
                CARD_UPGRADE_WORKBENCH);

        // Register block item
        Registry.register(Registries.ITEM,
                Identifier.of(ArenaClash.MOD_ID, "card_upgrade_workbench"),
                new BlockItem(CARD_UPGRADE_WORKBENCH, new Item.Settings()));

        // Add to creative tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries ->
                entries.add(CARD_UPGRADE_WORKBENCH));

        ArenaClash.LOGGER.info("Arena Clash blocks registered");
    }
}
