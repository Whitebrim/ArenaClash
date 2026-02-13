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

            // /ac start <player1> <player2>
            root.then(literal("start")
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
                                "§eTimer: " + (gm.getPhaseTicksRemaining() / 20) + "s"
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
                                        "§eSeed: " + cfg.gameSeed + "\n" +
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
        source.sendFeedback(() -> Text.literal("§aGave " + count + "x " + def.displayName() + " to " + player.getName().getString()), true);
        return 1;
    }
}
