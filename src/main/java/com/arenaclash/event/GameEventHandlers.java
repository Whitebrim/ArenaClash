package com.arenaclash.event;

import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import com.arenaclash.game.PlayerGameData;
import com.arenaclash.game.TeamSide;
import com.arenaclash.network.NetworkHandler;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/**
 * All server-side event handlers.
 */
public class GameEventHandlers {

    public static void register() {
        registerMobDeathHandler();
        registerRespawnHandler();
        registerAttackProtection();
        registerBlockProtection();
        registerC2SPacketHandlers();
    }

    // === Mob death → card creation ===
    private static void registerMobDeathHandler() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            GameManager gm = GameManager.getInstance();
            if (gm.getPhase() != GamePhase.SURVIVAL) return;
            if (damageSource.getAttacker() instanceof ServerPlayerEntity player) {
                if (MobCardRegistry.isRegistered(entity.getType())) {
                    gm.onMobKilled(player, entity.getType());
                }
            }
        });
    }

    // === Respawn → redirect to correct world ===
    private static void registerRespawnHandler() {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            GameManager gm = GameManager.getInstance();
            if (!gm.isGameActive()) return;
            PlayerGameData data = gm.getPlayerData(newPlayer.getUuid());
            if (data == null) return;

            GamePhase phase = gm.getPhase();
            if (phase == GamePhase.SURVIVAL) {
                gm.getWorldManager().teleportToSurvival(newPlayer, data.getTeam());
            } else if (phase == GamePhase.PREPARATION || phase == GamePhase.BATTLE) {
                gm.getWorldManager().teleportToArena(newPlayer, data.getTeam());
            }
        });
    }

    // === #4: Prevent players from attacking arena mobs and each other during battle ===
    private static void registerAttackProtection() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            GameManager gm = GameManager.getInstance();
            if (!gm.isGameActive()) return ActionResult.PASS;
            PlayerGameData data = gm.getPlayerData(serverPlayer.getUuid());
            if (data == null) return ActionResult.PASS;

            GamePhase phase = gm.getPhase();

            // On the arena world during BATTLE or PREPARATION: prevent ALL attacks
            ServerWorld arenaWorld = gm.getWorldManager().getArenaWorld();
            if (world == arenaWorld && (phase == GamePhase.BATTLE || phase == GamePhase.PREPARATION)) {
                // Check if target is an arena mob or another player
                if (entity.getCommandTags().contains("arenaclash_mob")
                        || entity.getCommandTags().contains("arenaclash_structure")
                        || entity instanceof ServerPlayerEntity) {
                    serverPlayer.sendMessage(Text.literal("§cYou cannot attack during this phase!"), true);
                    return ActionResult.FAIL;
                }
            }

            return ActionResult.PASS;
        });
    }

    // === #7: Block break/place protection on the arena ===
    private static void registerBlockProtection() {
        // Block breaking
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return true;
            return isBlockActionAllowed(serverPlayer, pos);
        });

        // Block placement
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            if (!isBlockActionAllowed(serverPlayer, hitResult.getBlockPos().offset(hitResult.getSide()))) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    /**
     * Check if a player is allowed to break/place at this position.
     * Rules:
     * - In survival worlds: always allowed
     * - On arena during PREPARATION: allowed only in their team zone (not structures)
     * - On arena during BATTLE: never allowed
     * - On arena during other phases: never allowed
     */
    private static boolean isBlockActionAllowed(ServerPlayerEntity player, BlockPos pos) {
        GameManager gm = GameManager.getInstance();
        if (!gm.isGameActive()) return true;

        PlayerGameData data = gm.getPlayerData(player.getUuid());
        if (data == null) return true;

        ServerWorld arenaWorld = gm.getWorldManager().getArenaWorld();
        if (player.getServerWorld() != arenaWorld) return true; // Not on arena = allowed

        GamePhase phase = gm.getPhase();
        GameConfig cfg = GameConfig.get();

        // During battle: no building at all
        if (phase == GamePhase.BATTLE || phase == GamePhase.ROUND_END || phase == GamePhase.GAME_OVER) {
            player.sendMessage(Text.literal("§cYou cannot modify the arena during battle!"), true);
            return false;
        }

        // During preparation: only in your team zone
        if (phase == GamePhase.PREPARATION) {
            int cz = cfg.arenaCenterZ;
            int halfLen = cfg.arenaLaneLength / 2;

            // P1 zone: Z < center, P2 zone: Z > center
            if (data.getTeam() == TeamSide.PLAYER1) {
                if (pos.getZ() >= cz) {
                    player.sendMessage(Text.literal("§cYou can only build on your side!"), true);
                    return false;
                }
            } else {
                if (pos.getZ() <= cz) {
                    player.sendMessage(Text.literal("§cYou can only build on your side!"), true);
                    return false;
                }
            }

            // Don't allow breaking structure blocks (towers, thrones)
            for (var structure : gm.getArenaManager().getStructures()) {
                if (structure.getOwner() == data.getTeam() && structure.getBoundingBox().contains(pos.getX(), pos.getY(), pos.getZ())) {
                    player.sendMessage(Text.literal("§cYou cannot modify your own structures!"), true);
                    return false;
                }
            }

            return true; // Allowed in your zone
        }

        // Other phases on arena: not allowed
        return false;
    }

    // === C2S Packet Handlers ===
    private static void registerC2SPacketHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.PlaceCardRequest.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> {
                        try {
                            java.util.UUID cardId = java.util.UUID.fromString(payload.cardId());
                            com.arenaclash.arena.Lane.LaneId laneId =
                                    com.arenaclash.arena.Lane.LaneId.valueOf(payload.laneId());
                            GameManager.getInstance().handlePlaceCard(player, cardId, laneId, payload.slotIndex());
                        } catch (Exception e) {
                            player.sendMessage(Text.literal("§cInvalid request"));
                        }
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.RemoveCardRequest.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> {
                        try {
                            com.arenaclash.arena.Lane.LaneId laneId =
                                    com.arenaclash.arena.Lane.LaneId.valueOf(payload.laneId());
                            GameManager.getInstance().handleRemoveCard(player, laneId, payload.slotIndex());
                        } catch (Exception e) {
                            player.sendMessage(Text.literal("§cInvalid request"));
                        }
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.RingBell.ID,
                (payload, context) -> {
                    context.server().execute(() ->
                            GameManager.getInstance().handleBellRing(context.player()));
                });

        ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.OpenCardGui.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    context.server().execute(() -> {
                        var data = GameManager.getInstance().getPlayerData(player.getUuid());
                        if (data != null) {
                            ServerPlayNetworking.send(player,
                                    new NetworkHandler.CardInventorySync(data.getCardInventory().toNbt()));
                        }
                    });
                });
    }
}
