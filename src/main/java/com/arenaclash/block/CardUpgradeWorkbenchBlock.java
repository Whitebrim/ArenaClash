package com.arenaclash.block;

import com.arenaclash.network.NetworkHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Card Upgrade Workbench — allows players to merge two identical cards
 * into a higher-level card during the Preparation phase on the arena.
 *
 * Right-click sends a server packet to open the GUI on the client.
 * This ensures server-side checks (enemy build zone, phase) are respected.
 */
public class CardUpgradeWorkbenchBlock extends Block {

    public CardUpgradeWorkbenchBlock(Properties settings) {
        super(settings);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                              Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            // Client side: just consume the click, GUI will open when server sends packet
            return InteractionResult.SUCCESS;
        }

        // Server side: send packet to open the upgrade GUI
        // (This only fires if UseBlockCallback didn't return FAIL for enemy zone etc.)
        if (player instanceof ServerPlayer serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new NetworkHandler.OpenUpgradeGui());
        }
        return InteractionResult.CONSUME;
    }
}
