package com.arenaclash.command;

import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.GameManager;
import com.arenaclash.game.PlayerGameData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers all /ac (arenaclash) commands.
 */
public class GameCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = literal("ac").requires(source -> source.hasPermissionLevel(2));

            // /ac start — start game with 2 TCP-connected players
            root.then(literal("start")
                    .executes(ctx -> {
                        String result = GameManager.getInstance().startGame();
                        ctx.getSource().sendFeedback(() -> Text.literal(result), true);
                        return 1;
                    })
                    // /ac start <player1> <player2>
                    .then(argument("player1", EntityArgumentType.player())
                            .then(argument("player2", EntityArgumentType.player())
                                    .executes(ctx -> {
                                        ServerPlayerEntity p1 = EntityArgumentType.getPlayer(ctx, "player1");
                                        ServerPlayerEntity p2 = EntityArgumentType.getPlayer(ctx, "player2");
                                        String result = GameManager.getInstance().startGame(p1, p2);
                                        ctx.getSource().sendFeedback(() -> Text.literal(result), true);
                                        return 1;
                                    }))));

            // /ac reset
            root.then(literal("reset")
                    .executes(ctx -> {
                        String result = GameManager.getInstance().resetGame();
                        ctx.getSource().sendFeedback(() -> Text.literal(result), true);
                        return 1;
                    }));

            // /ac status
            root.then(literal("status")
                    .executes(ctx -> {
                        GameManager gm = GameManager.getInstance();
                        ctx.getSource().sendFeedback(() -> Text.literal(
                                "§6=== Arena Clash Status ===\n" +
                                "§eActive: " + gm.isGameActive() + "\n" +
                                "§ePhase: " + gm.getPhase() + "\n" +
                                "§eRound: " + gm.getCurrentRound() + "\n" +
                                "§eTimer: " + (gm.getPhaseTicksRemaining() / 20) + "s\n" +
                                "§ePaused: " + gm.isGamePaused()
                        ), false);
                        return 1;
                    }));

            // /ac cards - show player's cards
            root.then(literal("cards")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) return 0;
                        PlayerGameData data = GameManager.getInstance().getPlayerData(player.getUuid());
                        if (data == null) {
                            ctx.getSource().sendFeedback(() -> Text.literal("§cYou're not in a game!"), false);
                            return 0;
                        }
                        CardInventory inv = data.getCardInventory();
                        StringBuilder sb = new StringBuilder("§6=== Your Cards (" + inv.getCardCount() + ") ===\n");
                        for (MobCard card : inv.getAllCards()) {
                            var def = card.getDefinition();
                            if (def != null) {
                                sb.append("§e- ").append(def.displayName())
                                        .append(" Lv.").append(card.getLevel())
                                        .append(" §7(HP:").append(String.format("%.0f", card.getHP()))
                                        .append(" ATK:").append(String.format("%.0f", card.getAttack()))
                                        .append(" SPD:").append(String.format("%.1f", card.getSpeed()))
                                        .append(")\n");
                            }
                        }
                        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
                        return 1;
                    }));

            // /ac config <key> <value> - change config values
            root.then(literal("config")
                    .then(literal("dayDuration")
                            .then(argument("ticks", IntegerArgumentType.integer(100))
                                    .executes(ctx -> {
                                        int val = IntegerArgumentType.getInteger(ctx, "ticks");
                                        GameConfig.get().dayDurationTicks = val;
                                        GameConfig.save();
                                        ctx.getSource().sendFeedback(() ->
                                                Text.literal("§aDay duration set to " + val + " ticks"), true);
                                        return 1;
                                    })))
                    .then(literal("prepTime")
                            .then(argument("ticks", IntegerArgumentType.integer(200))
                                    .executes(ctx -> {
                                        int val = IntegerArgumentType.getInteger(ctx, "ticks");
                                        GameConfig.get().preparationTimeTicks = val;
                                        GameConfig.save();
                                        ctx.getSource().sendFeedback(() ->
                                                Text.literal("§aPrep time set to " + val + " ticks"), true);
                                        return 1;
                                    })))
                    .then(literal("seed")
                            .then(argument("seed", LongArgumentType.longArg())
                                    .executes(ctx -> {
                                        long val = LongArgumentType.getLong(ctx, "seed");
                                        GameConfig.get().gameSeed = val;
                                        GameConfig.save();
                                        ctx.getSource().sendFeedback(() ->
                                                Text.literal("§aSeed set to " + val), true);
                                        return 1;
                                    })))
                    .then(literal("show")
                            .executes(ctx -> {
                                GameConfig cfg = GameConfig.get();
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                        "§6=== Config ===\n" +
                                        "§eDayDuration: " + cfg.dayDurationTicks + " ticks\n" +
                                        "§ePrepTime: " + cfg.preparationTimeTicks + " ticks\n" +
                                        "§eRounds: " + cfg.maxRounds + "\n" +
                                        "§eSurvival days: " + cfg.round1Days + "/" + cfg.round2Days + "/" + cfg.round3Days + "\n" +
                                        "§eSeed: " + (cfg.gameSeed == 0 ? "§7random (0)" : String.valueOf(cfg.gameSeed)) + "\n" +
                                        "§eLaneLength: " + cfg.arenaLaneLength + "\n" +
                                        "§eThroneHP: " + cfg.throneHP + "\n" +
                                        "§eTowerHP: " + cfg.towerHP
                                ), false);
                                return 1;
                            })));

            // /ac givecard <player> <mobId> [count] - debug command to give cards
            root.then(literal("givecard")
                    .then(argument("player", EntityArgumentType.player())
                            .then(argument("mobId", StringArgumentType.word())
                                    .executes(ctx -> giveCard(ctx.getSource(),
                                            EntityArgumentType.getPlayer(ctx, "player"),
                                            StringArgumentType.getString(ctx, "mobId"), 1))
                                    .then(argument("count", IntegerArgumentType.integer(1, 64))
                                            .executes(ctx -> giveCard(ctx.getSource(),
                                                    EntityArgumentType.getPlayer(ctx, "player"),
                                                    StringArgumentType.getString(ctx, "mobId"),
                                                    IntegerArgumentType.getInteger(ctx, "count")))))));

            // /ac bell - ring the bell
            root.then(literal("bell")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player != null) {
                            GameManager.getInstance().handleBellRing(player);
                        }
                        return 1;
                    }));

            // /ac pause — pause the game timer
            root.then(literal("pause")
                    .executes(ctx -> {
                        String result = GameManager.getInstance().pauseGame();
                        ctx.getSource().sendFeedback(() -> Text.literal(result), true);
                        return 1;
                    }));

            // /ac continue — resume the game timer
            root.then(literal("continue")
                    .executes(ctx -> {
                        String result = GameManager.getInstance().continueGame();
                        ctx.getSource().sendFeedback(() -> Text.literal(result), true);
                        return 1;
                    }));

            // /ac skip — skip current phase
            root.then(literal("skip")
                    .executes(ctx -> {
                        String result = GameManager.getInstance().skipPhase();
                        ctx.getSource().sendFeedback(() -> Text.literal(result), true);
                        return 1;
                    }));

            // /ac reload - reload config from disk
            root.then(literal("reload")
                    .executes(ctx -> {
                        GameConfig.load();
                        ctx.getSource().sendFeedback(() -> Text.literal("§aArenaClash config reloaded from disk!"), true);
                        // Show key values
                        GameConfig cfg = GameConfig.get();
                        ctx.getSource().sendFeedback(() -> Text.literal(
                                "§7  dayDuration=" + cfg.dayDurationTicks +
                                " prepTime=" + cfg.preparationTimeTicks +
                                " rounds=" + cfg.maxRounds +
                                " seed=" + (cfg.gameSeed == 0 ? "random" : cfg.gameSeed) +
                                " throneHP=" + cfg.throneHP +
                                " towerHP=" + cfg.towerHP
                        ), false);
                        return 1;
                    }));

            // /ac help - list all commands
            root.then(literal("help")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal(
                                "§6§l=== Arena Clash Commands ===\n" +
                                "§e/ac start §7- Start game (needs 2 TCP players)\n" +
                                "§e/ac start <p1> <p2> §7- Start with specific players\n" +
                                "§e/ac reset §7- Reset the current game\n" +
                                "§e/ac pause §7- Pause the game timer\n" +
                                "§e/ac continue §7- Resume the game timer\n" +
                                "§e/ac skip §7- Skip current phase\n" +
                                "§e/ac reload §7- Reload config from disk\n" +
                                "§e/ac status §7- Show game state\n" +
                                "§e/ac cards §7- Show your card inventory\n" +
                                "§e/ac bell §7- Ring bell (ready/retreat)\n" +
                                "§e/ac givecard <player> <mobId> [count] §7- Give cards\n" +
                                "§e/ac config show §7- Show all config values\n" +
                                "§e/ac config dayDuration <ticks> §7- Set day length\n" +
                                "§e/ac config prepTime <ticks> §7- Set prep duration\n" +
                                "§e/ac config seed <seed> §7- Set world seed (0=random)\n" +
                                "§e/ac help §7- This message"
                        ), false);
                        return 1;
                    }));

            // Also show help when running bare /ac
            root.executes(ctx -> {
                ctx.getSource().sendFeedback(() -> Text.literal(
                        "§6Arena Clash §7- Use §e/ac help §7for command list"
                ), false);
                return 1;
            });

            dispatcher.register(root);
        });
    }

    private static int giveCard(ServerCommandSource source, ServerPlayerEntity player, String mobId, int count) {
        PlayerGameData data = GameManager.getInstance().getPlayerData(player.getUuid());
        if (data == null) {
            source.sendFeedback(() -> Text.literal("§cPlayer not in game!"), false);
            return 0;
        }
        var def = com.arenaclash.card.MobCardRegistry.getById(mobId);
        if (def == null) {
            source.sendFeedback(() -> Text.literal("§cUnknown mob ID: " + mobId), false);
            return 0;
        }
        for (int i = 0; i < count; i++) {
            data.getCardInventory().addCard(new MobCard(mobId));
        }
        // Sync cards to client so GUI updates immediately
        GameManager.getInstance().syncCards(player);
        source.sendFeedback(() -> Text.literal("§aGave " + count + "x " + def.displayName() + " to " + player.getName().getString()), true);
        return 1;
    }
}
