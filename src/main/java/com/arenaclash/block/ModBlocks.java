package com.arenaclash.block;

import com.arenaclash.ArenaClash;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers all custom blocks and block items for Arena Clash.
 */
public class ModBlocks {

    public static final Block CARD_UPGRADE_WORKBENCH = new CardUpgradeWorkbenchBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BROWN)
                    .strength(2.5f)
                    .sound(SoundType.WOOD)
    );

    public static void register() {
        // Register block
        Registry.register(BuiltInRegistries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(ArenaClash.MOD_ID, "card_upgrade_workbench"),
                CARD_UPGRADE_WORKBENCH);

        // Register block item
        Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(ArenaClash.MOD_ID, "card_upgrade_workbench"),
                new BlockItem(CARD_UPGRADE_WORKBENCH, new Item.Properties()));

        // Add to creative tab
        CreativeModeTabEvents.modifyOutput(CreativeModeTabs.FUNCTIONAL_BLOCKS).register((registeredEntries, output) ->
                output.accept(CARD_UPGRADE_WORKBENCH));

        ArenaClash.LOGGER.info("Arena Clash blocks registered");
    }
}
