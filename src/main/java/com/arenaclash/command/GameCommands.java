package com.arenaclash.command;

import com.arenaclash.card.CardInventory;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.GameManager;
import com.arenaclash.game.PlayerGameData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.hasPermission;
import static net.minecraft.commands.Commands.literal;

/**
 * Registers all /ac (arenaclash) commands.
 */
public class GameCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = literal("ac").requires(hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS));

            // /ac start — start game with 2 TCP-connected players
            root.then(literal("start")
                    .executes(ctx -> {
                        Component result = GameManager.getInstance().startGame();
                        ctx.getSource().sendSuccess(() -> result, true);
                        return 1;
                    })
                    // /ac start <player1> <player2>
                    .then(argument("player1", EntityArgument.player())
                            .then(argument("player2", EntityArgument.player())
                                    .executes(ctx -> {
                                        ServerPlayer p1 = EntityArgument.getPlayer(ctx, "player1");
                                        ServerPlayer p2 = EntityArgument.getPlayer(ctx, "player2");
                                        Component result = GameManager.getInstance().startGame(p1, p2);
                                        ctx.getSource().sendSuccess(() -> result, true);
                                        return 1;
                                    }))));

            // /ac reset
            root.then(literal("reset")
                    .executes(ctx -> {
                        Component result = GameManager.getInstance().resetGame();
                        ctx.getSource().sendSuccess(() -> result, true);
                        return 1;
                    }));

            // /ac status
            root.then(literal("status")
                    .executes(ctx -> {
                        GameManager gm = GameManager.getInstance();
                        MutableComponent status = Component.translatable("arenaclash.cmd.status.title").append("\n")
                                .append(Component.translatable("arenaclash.cmd.status.active", String.valueOf(gm.isGameActive()))).append("\n")
                                .append(Component.translatable("arenaclash.cmd.status.phase", String.valueOf(gm.getPhase()))).append("\n")
                                .append(Component.translatable("arenaclash.cmd.status.round", String.valueOf(gm.getCurrentRound()))).append("\n")
                                .append(Component.translatable("arenaclash.cmd.status.timer", String.valueOf(gm.getPhaseTicksRemaining() / 20))).append("\n")
                                .append(Component.translatable("arenaclash.cmd.status.paused", String.valueOf(gm.isGamePaused())));
                        ctx.getSource().sendSuccess(() -> status, false);
                        return 1;
                    }));

            // /ac cards - show player's cards
            root.then(literal("cards")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayer();
                        if (player == null) return 0;
                        PlayerGameData data = GameManager.getInstance().getPlayerData(player.getUUID());
                        if (data == null) {
                            ctx.getSource().sendSuccess(() -> Component.translatable("arenaclash.cmd.not_in_game"), false);
                            return 0;
                        }
                        CardInventory inv = data.getCardInventory();
                        MutableComponent msg = Component.translatable("arenaclash.cmd.cards.title", String.valueOf(inv.getCardCount()));
                        for (MobCard card : inv.getAllCards()) {
                            var def = card.getDefinition();
                            if (def != null) {
                                msg.append("\n").append(Component.translatable("arenaclash.cmd.cards.entry",
                                        Component.translatable(def.translationKey()),
                                        String.valueOf(card.getLevel()),
                                        String.format("%.0f", card.getHP()),
                                        String.format("%.0f", card.getAttack()),
                                        String.format("%.1f", card.getSpeed())));
                            }
                        }
                        ctx.getSource().sendSuccess(() -> msg, false);
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
                                        ctx.getSource().sendSuccess(() ->
                                                Component.translatable("arenaclash.cmd.day_duration_set", String.valueOf(val)), true);
                                        return 1;
                                    })))
                    .then(literal("prepTime")
                            .then(argument("ticks", IntegerArgumentType.integer(200))
                                    .executes(ctx -> {
                                        int val = IntegerArgumentType.getInteger(ctx, "ticks");
                                        GameConfig.get().preparationTimeTicks = val;
                                        GameConfig.save();
                                        ctx.getSource().sendSuccess(() ->
                                                Component.translatable("arenaclash.cmd.prep_time_set", String.valueOf(val)), true);
                                        return 1;
                                    })))
                    .then(literal("seed")
                            .then(argument("seed", LongArgumentType.longArg())
                                    .executes(ctx -> {
                                        long val = LongArgumentType.getLong(ctx, "seed");
                                        GameConfig.get().gameSeed = val;
                                        GameConfig.save();
                                        ctx.getSource().sendSuccess(() ->
                                                Component.translatable("arenaclash.cmd.seed_set", String.valueOf(val)), true);
                                        return 1;
                                    })))
                    .then(literal("show")
                            .executes(ctx -> {
                                GameConfig cfg = GameConfig.get();
                                Component seedDisplay = cfg.gameSeed == 0
                                        ? Component.translatable("arenaclash.cmd.config.seed_random")
                                        : Component.literal(String.valueOf(cfg.gameSeed));
                                MutableComponent msg = Component.translatable("arenaclash.cmd.config.title").append("\n")
                                        .append(Component.translatable("arenaclash.cmd.config.day_duration", String.valueOf(cfg.dayDurationTicks))).append("\n")
                                        .append(Component.translatable("arenaclash.cmd.config.prep_time", String.valueOf(cfg.preparationTimeTicks))).append("\n")
                                        .append(Component.translatable("arenaclash.cmd.config.rounds", String.valueOf(cfg.maxRounds))).append("\n")
                                        .append(Component.translatable("arenaclash.cmd.config.survival_days",
                                                String.valueOf(cfg.round1Days), String.valueOf(cfg.round2Days), String.valueOf(cfg.round3Days))).append("\n")
                                        .append(Component.translatable("arenaclash.cmd.config.seed", seedDisplay)).append("\n")
                                        .append(Component.translatable("arenaclash.cmd.config.lane_length", String.valueOf(cfg.arenaLaneLength))).append("\n")
                                        .append(Component.translatable("arenaclash.cmd.config.throne_hp", String.valueOf(cfg.throneHP))).append("\n")
                                        .append(Component.translatable("arenaclash.cmd.config.tower_hp", String.valueOf(cfg.towerHP)));
                                ctx.getSource().sendSuccess(() -> msg, false);
                                return 1;
                            })));

            // /ac givecard <player> <mobId> [count] [level] - debug command to give cards
            // Mob ID autocomplete from MobCardRegistry
            SuggestionProvider<CommandSourceStack> mobIdSuggestions = (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                for (var def : MobCardRegistry.getAll()) {
                    if (def.id().toLowerCase().startsWith(remaining)) {
                        builder.suggest(def.id());
                    }
                }
                return builder.buildFuture();
            };

            root.then(literal("givecard")
                    .then(argument("player", EntityArgument.player())
                            .then(argument("mobId", StringArgumentType.word())
                                    .suggests(mobIdSuggestions)
                                    .executes(ctx -> giveCard(ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player"),
                                            StringArgumentType.getString(ctx, "mobId"), 1, 1))
                                    .then(argument("count", IntegerArgumentType.integer(1, 64))
                                            .executes(ctx -> giveCard(ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player"),
                                                    StringArgumentType.getString(ctx, "mobId"),
                                                    IntegerArgumentType.getInteger(ctx, "count"), 1))
                                            .then(argument("level", IntegerArgumentType.integer(1, 100))
                                                    .executes(ctx -> giveCard(ctx.getSource(),
                                                            EntityArgument.getPlayer(ctx, "player"),
                                                            StringArgumentType.getString(ctx, "mobId"),
                                                            IntegerArgumentType.getInteger(ctx, "count"),
                                                            IntegerArgumentType.getInteger(ctx, "level"))))))));

            // /ac bell - ring the bell
            root.then(literal("bell")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayer();
                        if (player != null) {
                            GameManager.getInstance().handleBellRing(player);
                        }
                        return 1;
                    }));

            // /ac pause — pause the game timer
            root.then(literal("pause")
                    .executes(ctx -> {
                        Component result = GameManager.getInstance().pauseGame();
                        ctx.getSource().sendSuccess(() -> result, true);
                        return 1;
                    }));

            // /ac continue — resume the game timer
            root.then(literal("continue")
                    .executes(ctx -> {
                        Component result = GameManager.getInstance().continueGame();
                        ctx.getSource().sendSuccess(() -> result, true);
                        return 1;
                    }));

            // /ac skip — skip current phase
            root.then(literal("skip")
                    .executes(ctx -> {
                        Component result = GameManager.getInstance().skipPhase();
                        ctx.getSource().sendSuccess(() -> result, true);
                        return 1;
                    }));

            // /ac reload - reload config from disk
            root.then(literal("reload")
                    .executes(ctx -> {
                        GameConfig.load();
                        ctx.getSource().sendSuccess(() -> Component.translatable("arenaclash.cmd.config_reloaded"), true);
                        GameConfig cfg = GameConfig.get();
                        String seedVal = cfg.gameSeed == 0 ? "random" : String.valueOf(cfg.gameSeed);
                        ctx.getSource().sendSuccess(() -> Component.translatable("arenaclash.cmd.config.reload_details",
                                String.valueOf(cfg.dayDurationTicks),
                                String.valueOf(cfg.preparationTimeTicks),
                                String.valueOf(cfg.maxRounds),
                                seedVal,
                                String.valueOf(cfg.throneHP),
                                String.valueOf(cfg.towerHP)
                        ), false);
                        return 1;
                    }));

            // /ac help - list all commands
            root.then(literal("help")
                    .executes(ctx -> {
                        MutableComponent help = Component.translatable("arenaclash.cmd.help.title").append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.start")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.start_players")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.reset")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.pause")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.continue")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.skip")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.reload")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.status")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.cards")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.bell")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.givecard")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.config_show")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.config_day")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.config_prep")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.config_seed")).append("\n")
                                .append(Component.translatable("arenaclash.cmd.help.help"));
                        ctx.getSource().sendSuccess(() -> help, false);
                        return 1;
                    }));

            // Also show help when running bare /ac
            root.executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.translatable("arenaclash.cmd.ac_hint"), false);
                return 1;
            });

            dispatcher.register(root);
        });
    }

    private static int giveCard(CommandSourceStack source, ServerPlayer player, String mobId, int count, int level) {
        PlayerGameData data = GameManager.getInstance().getPlayerData(player.getUUID());
        if (data == null) {
            source.sendSuccess(() -> Component.translatable("arenaclash.cmd.player_not_in_game"), false);
            return 0;
        }
        var def = com.arenaclash.card.MobCardRegistry.getById(mobId);
        if (def == null) {
            source.sendSuccess(() -> Component.translatable("arenaclash.cmd.unknown_mob", mobId), false);
            return 0;
        }
        for (int i = 0; i < count; i++) {
            MobCard card = new MobCard(mobId);
            card.setLevel(level);
            data.getCardInventory().addCard(card);
        }
        // Sync cards to client so GUI updates immediately
        GameManager.getInstance().syncCards(player);
        String lvStr = level > 1 ? " Lv." + level : "";
        source.sendSuccess(() -> Component.translatable("arenaclash.cmd.gave_card",
                String.valueOf(count), Component.translatable(def.translationKey()).getString() + lvStr, player.getName().getString()), true);
        return 1;
    }
}
