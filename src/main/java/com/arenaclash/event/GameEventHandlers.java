package com.arenaclash.event;

import com.arenaclash.arena.ArenaDefinition;
import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.command.ArenaSetupCommands;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import com.arenaclash.game.PlayerGameData;
import com.arenaclash.game.TeamSide;
import com.arenaclash.network.NetworkHandler;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BellBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
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
        registerBellInteraction();
        registerWandInteraction();
        registerPlayerJoinHandler();
        registerC2SPacketHandlers();
    }

    // === Mob death → card creation ===
    private static void registerMobDeathHandler() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (damageSource.getAttacker() instanceof ServerPlayerEntity player) {
                // Fix 3: Skip baby passive animals (they shouldn't drop cards)
                if (entity instanceof net.minecraft.entity.passive.PassiveEntity passiveEntity) {
                    if (passiveEntity.isBaby()) return;
                }

                // Fix 3: Baby zombies get their own card
                String cardId = null;
                if (entity instanceof net.minecraft.entity.mob.ZombieEntity zombie && zombie.isBaby()) {
                    cardId = "baby_zombie";
                    // Make sure baby_zombie is registered
                    if (MobCardRegistry.getById(cardId) == null) return;
                } else {
                    if (!MobCardRegistry.isRegistered(entity.getType())) return;
                    var def = MobCardRegistry.getByEntityType(entity.getType());
                    if (def == null) return;
                    cardId = def.id();
                }

                final String finalCardId = cardId;
                var def = MobCardRegistry.getById(finalCardId);
                if (def == null) return;

                // Check if we're on an integrated server (singleplayer)
                if (!player.getServer().isDedicated()) {
                    // Singleplayer: only capture kills during ArenaClash survival
                    if (!com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive) return;
                    // Forward via bridge → client TCP → dedicated server
                    com.arenaclash.tcp.SingleplayerBridge.pendingMobKills.add(finalCardId);
                    player.sendMessage(Text.literal("§a+ " + def.displayName() + " card obtained!"));
                } else {
                    // Dedicated server: handle directly via GameManager
                    GameManager gm = GameManager.getInstance();
                    if (gm.getPhase() == GamePhase.SURVIVAL) {
                        gm.onMobKilled(player, entity.getType());
                    }
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
     * - On arena during PREPARATION: allowed only in their team's build zones
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

        // During battle: no building at all
        if (phase == GamePhase.BATTLE || phase == GamePhase.ROUND_END || phase == GamePhase.GAME_OVER) {
            player.sendMessage(Text.literal("§cYou cannot modify the arena during battle!"), true);
            return false;
        }

        // During preparation: only in your team's build zones (from ArenaDefinition)
        if (phase == GamePhase.PREPARATION) {
            ArenaDefinition arenaDef = null;
            if (gm.getArenaManager() != null) {
                arenaDef = gm.getArenaManager().getDefinition();
            }

            if (arenaDef != null) {
                // Check build zones
                for (ArenaDefinition.ZoneDef zone : arenaDef.getBuildZones(data.getTeam())) {
                    if (zone.contains(pos)) {
                        return true; // Allowed in build zone
                    }
                }
                player.sendMessage(Text.literal("§cYou can only build in your designated build zones!"), true);
                return false;
            }

            // Fallback: use old center-based check if no definition loaded
            GameConfig cfg = GameConfig.get();
            int cz = cfg.arenaCenterZ;
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

            // Don't allow breaking structures
            if (gm.getArenaManager() != null) {
                for (var structure : gm.getArenaManager().getStructures()) {
                    var sDef = structure.getDefinition();
                    if (sDef.boundsMin != null && sDef.boundsMax != null) {
                        if (pos.getX() >= sDef.boundsMin.getX() && pos.getX() <= sDef.boundsMax.getX()
                                && pos.getY() >= sDef.boundsMin.getY() && pos.getY() <= sDef.boundsMax.getY()
                                && pos.getZ() >= sDef.boundsMin.getZ() && pos.getZ() <= sDef.boundsMax.getZ()) {
                            player.sendMessage(Text.literal("§cYou cannot modify structures!"), true);
                            return false;
                        }
                    }
                }
            }

            return true;
        }

        // Other phases on arena: not allowed
        return false;
    }

    // === Bell block interaction (physical bell replaces keyboard B key) ===
    private static void registerBellInteraction() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

            BlockPos clickedPos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(clickedPos);

            // Check if it's a bell block
            if (!(state.getBlock() instanceof BellBlock)) return ActionResult.PASS;

            GameManager gm = GameManager.getInstance();
            if (!gm.isGameActive()) return ActionResult.PASS;

            // Check arena definition for bell positions
            ArenaDefinition arenaDef = null;
            if (gm.getArenaManager() != null) {
                arenaDef = gm.getArenaManager().getDefinition();
            }
            if (arenaDef == null) return ActionResult.PASS;

            PlayerGameData data = gm.getPlayerData(serverPlayer.getUuid());
            if (data == null) return ActionResult.PASS;

            TeamSide team = data.getTeam();
            BlockPos bellPos = arenaDef.getBellPos(team);

            // Check if this bell is the player's team bell
            if (bellPos != null && clickedPos.equals(bellPos)) {
                // Handle bell ring through GameManager
                gm.handleBellRing(serverPlayer);

                // Play bell sound + animation
                world.playSound(null, clickedPos, SoundEvents.BLOCK_BELL_USE,
                        SoundCategory.BLOCKS, 2.0f, 1.0f);

                // Broadcast message
                GamePhase phase = gm.getPhase();
                String teamName = team == TeamSide.PLAYER1 ? "§9Player 1" : "§cPlayer 2";
                if (phase == GamePhase.PREPARATION) {
                    gm.broadcastMc(teamName + " §erang the bell! Ready for battle!", Formatting.YELLOW);
                } else if (phase == GamePhase.BATTLE) {
                    gm.broadcastMc(teamName + " §eorders retreat!", Formatting.YELLOW);
                }

                return ActionResult.SUCCESS;
            }

            // Not their bell — check if it's enemy bell
            TeamSide opponent = team.opponent();
            BlockPos enemyBell = arenaDef.getBellPos(opponent);
            if (enemyBell != null && clickedPos.equals(enemyBell)) {
                serverPlayer.sendMessage(Text.literal("§cThis is not your bell!"), true);
                return ActionResult.FAIL;
            }

            return ActionResult.PASS;
        });
    }

    // === Wand interaction for arena setup ===
    private static void registerWandInteraction() {
        // Left click (attack block) = pos1
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            ItemStack held = player.getStackInHand(hand);
            if (!ArenaSetupCommands.isWand(held)) return ActionResult.PASS;

            ArenaSetupCommands.onWandUse(serverPlayer, pos, false);
            return ActionResult.SUCCESS;
        });

        // Right click (use block) = pos2
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            ItemStack held = player.getStackInHand(hand);
            if (!ArenaSetupCommands.isWand(held)) return ActionResult.PASS;

            ArenaSetupCommands.onWandUse(serverPlayer, hitResult.getBlockPos(), true);
            return ActionResult.SUCCESS;
        });
    }

    // === Player joins MC server → teleport to arena ===
    private static void registerPlayerJoinHandler() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            GameManager gm = GameManager.getInstance();
            if (gm.isGameActive()) {
                // Fix 4: Delay by 2 ticks to let player fully load and connection stabilize
                // This prevents the "Sending unknown packet disconnect" error
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
