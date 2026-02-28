package com.arenaclash.block;

import com.arenaclash.network.NetworkHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Card Upgrade Workbench — allows players to merge two identical cards
 * into a higher-level card during the Preparation phase on the arena.
 *
 * Right-click sends a server packet to open the GUI on the client.
 * This ensures server-side checks (enemy build zone, phase) are respected.
 */
public class CardUpgradeWorkbenchBlock extends Block {

    public CardUpgradeWorkbenchBlock(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) {
            // Client side: just consume the click, GUI will open when server sends packet
            return ActionResult.SUCCESS;
        }

        // Server side: send packet to open the upgrade GUI
        // (This only fires if UseBlockCallback didn't return FAIL for enemy zone etc.)
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new NetworkHandler.OpenUpgradeGui());
        }
        return ActionResult.CONSUME;
    }
}
