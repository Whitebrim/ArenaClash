package com.arenaclash.mixin;

import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mixin to replace ore drops with smelted versions during survival phase.
 *
 * IMPORTANT: The replacement map MUST be lazily initialized.
 * Using a static {} block in a @Mixin(Block.class) causes circular class loading:
 * Block clinit -> OreBlockMixin static init -> Blocks clinit -> needs Block -> crash.
 */
@Mixin(Block.class)
public class OreBlockMixin {

    @Unique
    private static Map<Block, ItemStack> arenaclash$oreReplacements;

    @Unique
    private static Map<Block, ItemStack> arenaclash$getOreReplacements() {
        if (arenaclash$oreReplacements == null) {
            arenaclash$oreReplacements = new HashMap<>();
            // Overworld ores
            arenaclash$oreReplacements.put(Blocks.IRON_ORE, new ItemStack(Items.IRON_INGOT));
            arenaclash$oreReplacements.put(Blocks.DEEPSLATE_IRON_ORE, new ItemStack(Items.IRON_INGOT));
            arenaclash$oreReplacements.put(Blocks.GOLD_ORE, new ItemStack(Items.GOLD_INGOT));
            arenaclash$oreReplacements.put(Blocks.DEEPSLATE_GOLD_ORE, new ItemStack(Items.GOLD_INGOT));
            arenaclash$oreReplacements.put(Blocks.COPPER_ORE, new ItemStack(Items.COPPER_INGOT, 3));
            arenaclash$oreReplacements.put(Blocks.DEEPSLATE_COPPER_ORE, new ItemStack(Items.COPPER_INGOT, 3));
            arenaclash$oreReplacements.put(Blocks.RAW_IRON_BLOCK, new ItemStack(Items.IRON_INGOT, 9));
            arenaclash$oreReplacements.put(Blocks.RAW_GOLD_BLOCK, new ItemStack(Items.GOLD_INGOT, 9));
            arenaclash$oreReplacements.put(Blocks.RAW_COPPER_BLOCK, new ItemStack(Items.COPPER_INGOT, 9));
            // Nether ores
            arenaclash$oreReplacements.put(Blocks.NETHER_GOLD_ORE, new ItemStack(Items.GOLD_INGOT));
            arenaclash$oreReplacements.put(Blocks.ANCIENT_DEBRIS, new ItemStack(Items.NETHERITE_SCRAP));
        }
        return arenaclash$oreReplacements;
    }

    @Inject(method = "getDroppedStacks", at = @At("RETURN"), cancellable = true)
    private static void arenaclash$replaceOreDrops(BlockState state,
                                                   ServerWorld world,
                                                   BlockPos pos,
                                                   net.minecraft.block.entity.BlockEntity blockEntity,
                                                   CallbackInfoReturnable<List<ItemStack>> cir) {
        GameManager gm = GameManager.getInstance();
        boolean isSurvival = (gm.isGameActive() && gm.getPhase() == GamePhase.SURVIVAL)
                || com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive;
        if (!isSurvival) return;

        Block block = state.getBlock();
        ItemStack replacement = arenaclash$getOreReplacements().get(block);
        if (replacement != null) {
            List<ItemStack> newDrops = new ArrayList<>();
            newDrops.add(replacement.copy());
            cir.setReturnValue(newDrops);
        }
    }
}
