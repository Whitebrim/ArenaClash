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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/**
 * All server-side event handlers.
 * KEY CHANGE: Bell is now a physical block interaction, not a keyboard key.
 */
public class GameEventHandlers {

    public static void register() {
        registerMobDeathHandler();
        registerRespawnHandler();
        registerAttackProtection();
        registerBlockInteraction();
        registerPlayerJoinHandler();
        registerC2SPacketHandlers();
    }

    // === Mob death → card creation ===
    private static void registerMobDeathHandler() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (damageSource.getAttacker() instanceof ServerPlayerEntity player) {
                // Fix 1: Spawner mobs don't give cards
                if (entity.getCommandTags().contains("arenaclash_spawner_mob")) {
                    return;
                }

                String cardId = null;

                // Check baby hostile mobs FIRST — these should give their own type's card.
                // Must be checked before the PassiveEntity baby filter because some hostile
                // mobs (HoglinEntity) extend AnimalEntity → PassiveEntity.
                boolean isBabyHostile = false;
                if (entity instanceof net.minecraft.entity.mob.ZombieEntity zombie && zombie.isBaby()) {
                    isBabyHostile = true;
                } else if (entity instanceof net.minecraft.entity.mob.PiglinEntity piglin && piglin.isBaby()) {
                    isBabyHostile = true;
                } else if (entity instanceof net.minecraft.entity.mob.HoglinEntity hoglin && hoglin.isBaby()) {
                    isBabyHostile = true;
                } else if (entity instanceof net.minecraft.entity.mob.ZoglinEntity zoglin && zoglin.isBaby()) {
                    isBabyHostile = true;
                }

                if (isBabyHostile) {
                    // Special case: actual baby zombie (EntityType.ZOMBIE, not subtype) → baby_zombie card
                    if (entity.getType() == net.minecraft.entity.EntityType.ZOMBIE
                            && entity instanceof net.minecraft.entity.mob.ZombieEntity z && z.isBaby()) {
                        cardId = "baby_zombie";
                        if (MobCardRegistry.getById(cardId) == null) return;
                    } else {
                        // All other baby hostile mobs: use their own entity type's card
                        // (baby husk → husk, baby drowned → drowned, baby piglin → piglin, etc.)
                        var def = MobCardRegistry.getByEntityType(entity.getType());
                        if (def != null) {
                            cardId = def.id();
                        } else {
                            return; // Unknown baby hostile mob type, skip
                        }
                    }
                } else {
                    // Skip baby passive mobs (baby cows, sheep, etc.) — no card for them
                    if (entity instanceof net.minecraft.entity.passive.PassiveEntity passiveEntity) {
                        if (passiveEntity.isBaby()) return;
                    }

                    if (!MobCardRegistry.isRegistered(entity.getType())) return;
                    var def = MobCardRegistry.getByEntityType(entity.getType());
                    if (def == null) return;
                    cardId = def.id();
                }

                final String finalCardId = cardId;
                var def = MobCardRegistry.getById(finalCardId);
                if (def == null) return;

                if (!player.getServer().isDedicated()) {
                    if (!com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive) return;
                    com.arenaclash.tcp.SingleplayerBridge.pendingMobKills.add(finalCardId);
                    player.sendMessage(Text.literal("§a+ " + def.displayName() + " card obtained!"));
                } else {
                    GameManager gm = GameManager.getInstance();
                    if (gm.getPhase() == GamePhase.SURVIVAL) {
                        gm.onMobKilled(player, entity.getType());
                    }
                }
            }
        });
    }

    // === Respawn handler ===
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

    // === Prevent ALL attacks on arena mobs/structures (even by operators/creative) ===
    private static void registerAttackProtection() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;

            // Check if the entity is an arena entity - ALWAYS protect regardless of player status
            if (entity.getCommandTags().contains("arenaclash_mob")
                    || entity.getCommandTags().contains("arenaclash_structure")
                    || entity.getCommandTags().contains("arenaclash_marker")) {
                serverPlayer.sendMessage(Text.literal("§cArena entities cannot be attacked!"), true);
                return ActionResult.FAIL;
            }

            // Also prevent attacking other players on the arena world
            GameManager gm = GameManager.getInstance();
            if (gm.isGameActive()) {
                ServerWorld arenaWorld = gm.getWorldManager().getArenaWorld();
                if (world == arenaWorld && entity instanceof ServerPlayerEntity) {
                    serverPlayer.sendMessage(Text.literal("§cPvP is not allowed in the arena!"), true);
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });
    }

    /**
     * Handle block interactions: bell ringing + build zone enforcement.
     * Bell: right-click bell block near throne → toggle ready / order retreat.
     * Build: only allowed in designated build zone behind throne during PREPARATION.
     */
    private static void registerBlockInteraction() {
        // Block breaking - enforce build zones
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return true;
            return isBlockActionAllowed(serverPlayer, pos, false);
        });

        // Block placement / use - handle bell + enforce build zones
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;

            GameManager gm = GameManager.getInstance();
            if (!gm.isGameActive()) return ActionResult.PASS;

            BlockPos clickedPos = hitResult.getBlockPos();
            ServerWorld arenaWorld = gm.getWorldManager().getArenaWorld();

            // Check if player is on the arena world
            if (world != arenaWorld) return ActionResult.PASS;

            // Check if clicking a bell block
            if (world.getBlockState(clickedPos).isOf(Blocks.BELL)) {
                PlayerGameData data = gm.getPlayerData(serverPlayer.getUuid());
                if (data == null) {
                    serverPlayer.sendMessage(Text.literal("§cYou are not part of this game!"), true);
                    return ActionResult.FAIL;
                }

                // Check if this bell belongs to the player's team
                TeamSide bellTeam = gm.getArenaManager().getBellTeam(clickedPos);
                if (bellTeam != null && bellTeam == data.getTeam()) {
                    // Ring the bell!
                    gm.handleBellRing(serverPlayer);

                    // Play bell sound
                    world.playSound(null, clickedPos.getX(), clickedPos.getY(), clickedPos.getZ(),
                            SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 2.0f, 1.0f);

                    return ActionResult.SUCCESS;
                } else if (bellTeam != null) {
                    serverPlayer.sendMessage(Text.literal("§cThis is not your bell!"), true);
                    return ActionResult.FAIL;
                }
            }

            // Build zone enforcement for block placement
            BlockPos placePos = clickedPos.offset(hitResult.getSide());
            if (!isBlockActionAllowed(serverPlayer, placePos, true)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    /**
     * Check if a player is allowed to break/place at this position.
     * Protects arena world from ALL players, including operators and non-game players.
     */
    private static boolean isBlockActionAllowed(ServerPlayerEntity player, BlockPos pos, boolean isPlace) {
        GameManager gm = GameManager.getInstance();
        if (!gm.isGameActive()) return true;

        ServerWorld arenaWorld = gm.getWorldManager().getArenaWorld();
        if (arenaWorld == null || player.getServerWorld() != arenaWorld) return true;

        // On arena world: check if player is part of the game
        PlayerGameData data = gm.getPlayerData(player.getUuid());

        // Non-game players (including operators) cannot modify the arena AT ALL
        if (data == null) {
            player.sendMessage(Text.literal("§cYou are not part of this game!"), true);
            return false;
        }

        GamePhase phase = gm.getPhase();

        // During battle: no building for anyone
        if (phase == GamePhase.BATTLE || phase == GamePhase.ROUND_END || phase == GamePhase.GAME_OVER) {
            player.sendMessage(Text.literal("§cYou cannot modify the arena during battle!"), true);
            return false;
        }

        // During preparation: only in build zone
        if (phase == GamePhase.PREPARATION) {
            // Don't allow breaking bells
            if (player.getServerWorld().getBlockState(pos).isOf(Blocks.BELL)) {
                return false;
            }

            GameConfig cfg = GameConfig.get();

            // Check build zone
            if (cfg.buildZonesEnabled) {
                if (!gm.getArenaManager().isInBuildZone(data.getTeam(), pos)) {
                    int cz = cfg.arenaCenterZ;
                    if (data.getTeam() == TeamSide.PLAYER1 && pos.getZ() >= cz) {
                        player.sendMessage(Text.literal("§cYou can only build on your side!"), true);
                        return false;
                    } else if (data.getTeam() == TeamSide.PLAYER2 && pos.getZ() <= cz) {
                        player.sendMessage(Text.literal("§cYou can only build on your side!"), true);
                        return false;
                    }
                }
            }

            // Don't allow breaking structure blocks
            for (var structure : gm.getArenaManager().getStructures()) {
                if (structure.getBoundingBox().contains(pos.getX(), pos.getY(), pos.getZ())) {
                    player.sendMessage(Text.literal("§cYou cannot modify arena structures!"), true);
                    return false;
                }
            }

            return true;
        }

        // Any other phase on arena world: deny
        return false;
    }

    // === Player joins MC server ===
    private static void registerPlayerJoinHandler() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            GameManager gm = GameManager.getInstance();
            if (gm.isGameActive()) {
                server.execute(() -> {
                    server.execute(() -> gm.onPlayerJoinMc(player));
                });
            }
        });
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

        // Keep RingBell packet handler for TCP-connected players
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
