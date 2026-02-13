package com.arenaclash.event;

import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import com.arenaclash.network.NetworkHandler;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Registers server-side event handlers for gameplay mechanics.
 */
public class GameEventHandlers {

    public static void register() {
        registerMobDeathHandler();
        registerC2SPacketHandlers();
    }

    /**
     * When a player kills a mob during survival phase, create a card.
     */
    private static void registerMobDeathHandler() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            GameManager gm = GameManager.getInstance();
            if (gm.getPhase() != GamePhase.SURVIVAL) return;

            // Check if killer is a player
            if (damageSource.getAttacker() instanceof ServerPlayerEntity player) {
                // Check if this mob type is registered
                if (MobCardRegistry.isRegistered(entity.getType())) {
                    gm.onMobKilled(player, entity.getType());
                }
            }
        });
    }

    /**
     * Register handlers for client-to-server packets.
     */
    private static void registerC2SPacketHandlers() {
        // Place card on deployment slot
        ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.PlaceCardRequest.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> {
                        try {
                            java.util.UUID cardId = java.util.UUID.fromString(payload.cardId());
                            com.arenaclash.arena.Lane.LaneId laneId =
                                    com.arenaclash.arena.Lane.LaneId.valueOf(payload.laneId());
                            GameManager.getInstance().handlePlaceCard(
                                    player, cardId, laneId, payload.slotIndex());
                        } catch (Exception e) {
                            player.sendMessage(net.minecraft.text.Text.literal("§cInvalid request"));
                        }
                    });
                });

        // Remove card from deployment slot
        ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.RemoveCardRequest.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> {
                        try {
                            com.arenaclash.arena.Lane.LaneId laneId =
                                    com.arenaclash.arena.Lane.LaneId.valueOf(payload.laneId());
                            GameManager.getInstance().handleRemoveCard(player, laneId, payload.slotIndex());
                        } catch (Exception e) {
                            player.sendMessage(net.minecraft.text.Text.literal("§cInvalid request"));
                        }
                    });
                });

        // Ring bell
        ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.RingBell.ID,
                (payload, context) -> {
                    context.server().execute(() ->
                            GameManager.getInstance().handleBellRing(context.player()));
                });

        // Open card GUI
        ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.OpenCardGui.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> {
                        // Send card inventory sync to trigger GUI opening on client
                        var data = GameManager.getInstance().getPlayerData(player.getUuid());
                        if (data != null) {
                            ServerPlayNetworking.send(player,
                                    new NetworkHandler.CardInventorySync(data.getCardInventory().toNbt()));
                        }
                    });
                });
    }
}
