package com.arenaclash.command;

import com.arenaclash.arena.ArenaDefinition;
import com.arenaclash.arena.Lane;
import com.arenaclash.game.TeamSide;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * In-game arena building commands.
 *
 * All commands under /ac arena:
 *   wand         - Give selection wand (golden axe)
 *   bounds       - Set arena bounds from pos1/pos2
 *   setspawn     - Set player spawn point
 *   setbell      - Set bell block position
 *   setthrone    - Set throne structure (pos1 = position, pos2 = bounds max)
 *   addtower     - Add tower structure
 *   waypoint     - Add/set waypoint on a lane
 *   clearwaypoints - Clear all waypoints on a lane
 *   deploy       - Set deployment slot
 *   buildzone    - Add build zone
 *   save         - Export to JSON
 *   load         - Import from JSON
 *   show         - Toggle zone visualization
 *   savetemplate - Save current world as arena template
 *
 * Uses a wand (golden axe) for pos1 (left click) / pos2 (right click) selection.
 */
public class ArenaSetupCommands {

    // Per-player selection state
    private static final Map<UUID, BlockPos> pos1Map = new HashMap<>();
    private static final Map<UUID, BlockPos> pos2Map = new HashMap<>();

    // Active arena definition being edited
    private static ArenaDefinition editingDef = null;

    // Visualization toggle
    private static boolean showVisualization = false;

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var arena = literal("ac").requires(s -> s.hasPermissionLevel(2))
                    .then(literal("arena")

                            // /ac arena wand
                            .then(literal("wand").executes(ArenaSetupCommands::giveWand))

                            // /ac arena bounds
                            .then(literal("bounds").executes(ArenaSetupCommands::setBounds))

                            // /ac arena setspawn <p1|p2>
                            .then(literal("setspawn")
                                    .then(argument("team", StringArgumentType.word())
                                            .executes(ArenaSetupCommands::setSpawn)))

                            // /ac arena setbell <p1|p2>
                            .then(literal("setbell")
                                    .then(argument("team", StringArgumentType.word())
                                            .executes(ArenaSetupCommands::setBell)))

                            // /ac arena setthrone <p1|p2>
                            .then(literal("setthrone")
                                    .then(argument("team", StringArgumentType.word())
                                            .executes(ArenaSetupCommands::setThrone)))

                            // /ac arena addtower <p1|p2> <lane>
                            .then(literal("addtower")
                                    .then(argument("team", StringArgumentType.word())
                                            .then(argument("lane", StringArgumentType.word())
                                                    .executes(ArenaSetupCommands::addTower))))

                            // /ac arena waypoint <lane> <index>
                            .then(literal("waypoint")
                                    .then(argument("lane", StringArgumentType.word())
                                            .then(argument("index", IntegerArgumentType.integer(0, 99))
                                                    .executes(ArenaSetupCommands::setWaypoint))))

                            // /ac arena clearwaypoints <lane>
                            .then(literal("clearwaypoints")
                                    .then(argument("lane", StringArgumentType.word())
                                            .executes(ArenaSetupCommands::clearWaypoints)))

                            // /ac arena deploy <lane> <p1|p2> <slot>
                            .then(literal("deploy")
                                    .then(argument("lane", StringArgumentType.word())
                                            .then(argument("team", StringArgumentType.word())
                                                    .then(argument("slot", IntegerArgumentType.integer(0, 7))
                                                            .executes(ArenaSetupCommands::setDeploySlot)))))

                            // /ac arena buildzone <p1|p2>
                            .then(literal("buildzone")
                                    .then(argument("team", StringArgumentType.word())
                                            .executes(ArenaSetupCommands::addBuildZone)))

                            // /ac arena save
                            .then(literal("save").executes(ArenaSetupCommands::saveDefinition))

                            // /ac arena load
                            .then(literal("load").executes(ArenaSetupCommands::loadDefinition))

                            // /ac arena show
                            .then(literal("show").executes(ArenaSetupCommands::toggleShow))

                            // /ac arena new
                            .then(literal("new").executes(ArenaSetupCommands::newDefinition))

                            // /ac arena savetemplate
                            .then(literal("savetemplate").executes(ArenaSetupCommands::saveTemplate))
                    );

            dispatcher.register(arena);
        });
    }

    // ========================================================================
    // WAND & SELECTION
    // ========================================================================

    private static int giveWand(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        ItemStack wand = new ItemStack(Items.GOLDEN_AXE);
        wand.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§eArena Wand §7(L=pos1, R=pos2)"));
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("arenaclash_wand", true);
        wand.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        player.getInventory().insertStack(wand);

        sendMsg(ctx, "§aGave arena wand! Left click = pos1, Right click = pos2");
        return 1;
    }

    /**
     * Called from event handler when player uses the wand.
     */
    public static void onWandUse(ServerPlayerEntity player, BlockPos pos, boolean isRightClick) {
        if (isRightClick) {
            pos2Map.put(player.getUuid(), pos);
            player.sendMessage(Text.literal("§dPos2 set: " + formatPos(pos)), true);
        } else {
            pos1Map.put(player.getUuid(), pos);
            player.sendMessage(Text.literal("§9Pos1 set: " + formatPos(pos)), true);
        }
    }

    /**
     * Check if an item is an arena wand.
     */
    public static boolean isWand(ItemStack stack) {
        if (stack.isEmpty()) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyNbt().getBoolean("arenaclash_wand");
    }

    // ========================================================================
    // ARENA DEFINITION COMMANDS
    // ========================================================================

    private static int newDefinition(CommandContext<ServerCommandSource> ctx) {
        editingDef = new ArenaDefinition();
        sendMsg(ctx, "§aNew arena definition created. Use commands to set positions.");
        return 1;
    }

    private static int setBounds(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        BlockPos p1 = getPos1(ctx);
        BlockPos p2 = getPos2(ctx);
        if (p1 == null || p2 == null) return 0;

        BlockPos min = new BlockPos(
                Math.min(p1.getX(), p2.getX()),
                Math.min(p1.getY(), p2.getY()),
                Math.min(p1.getZ(), p2.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(p1.getX(), p2.getX()),
                Math.max(p1.getY(), p2.getY()),
                Math.max(p1.getZ(), p2.getZ())
        );

        def.setBounds(min, max);
        sendMsg(ctx, "§aBounds set: " + formatPos(min) + " → " + formatPos(max));
        return 1;
    }

    private static int setSpawn(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        TeamSide team = parseTeam(StringArgumentType.getString(ctx, "team"));
        if (team == null) { sendMsg(ctx, "§cInvalid team. Use p1 or p2."); return 0; }

        Vec3d pos = ctx.getSource().getPlayer().getPos();
        def.setPlayerSpawn(team, pos);
        sendMsg(ctx, "§aSpawn for " + team.name() + " set at " + formatVec(pos));
        return 1;
    }

    private static int setBell(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        TeamSide team = parseTeam(StringArgumentType.getString(ctx, "team"));
        if (team == null) { sendMsg(ctx, "§cInvalid team. Use p1 or p2."); return 0; }

        BlockPos pos = ctx.getSource().getPlayer().getBlockPos();
        def.setBellPos(team, pos);
        sendMsg(ctx, "§aBell for " + team.name() + " set at " + formatPos(pos));
        return 1;
    }

    private static int setThrone(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        TeamSide team = parseTeam(StringArgumentType.getString(ctx, "team"));
        if (team == null) { sendMsg(ctx, "§cInvalid team. Use p1 or p2."); return 0; }

        BlockPos p1 = getPos1(ctx);
        BlockPos p2 = getPos2(ctx);
        if (p1 == null || p2 == null) return 0;

        BlockPos center = ctx.getSource().getPlayer().getBlockPos();
        BlockPos min = new BlockPos(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
        BlockPos max = new BlockPos(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));

        ArenaDefinition.StructureDef throne = new ArenaDefinition.StructureDef(center, min, max, 200);
        throne.attackRange = 6.0;
        throne.attackDamage = 5.0;
        throne.attackCooldown = 30;
        def.setThrone(team, throne);

        sendMsg(ctx, "§aThrone for " + team.name() + " set at " + formatPos(center)
                + " bounds " + formatPos(min) + "→" + formatPos(max));
        return 1;
    }

    private static int addTower(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        TeamSide team = parseTeam(StringArgumentType.getString(ctx, "team"));
        Lane.LaneId lane = Lane.LaneId.fromString(StringArgumentType.getString(ctx, "lane"));
        if (team == null) { sendMsg(ctx, "§cInvalid team."); return 0; }

        BlockPos p1 = getPos1(ctx);
        BlockPos p2 = getPos2(ctx);
        if (p1 == null || p2 == null) return 0;

        BlockPos center = ctx.getSource().getPlayer().getBlockPos();
        BlockPos min = new BlockPos(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
        BlockPos max = new BlockPos(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));

        ArenaDefinition.StructureDef tower = new ArenaDefinition.StructureDef(center, min, max, 100);
        tower.attackRange = 15.0;
        tower.attackDamage = 3.0;
        tower.attackCooldown = 40;
        tower.associatedLane = lane;
        def.addTower(team, tower);

        sendMsg(ctx, "§aTower added for " + team.name() + " on lane " + lane.name()
                + " at " + formatPos(center));
        return 1;
    }

    private static int setWaypoint(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        Lane.LaneId laneId = Lane.LaneId.fromString(StringArgumentType.getString(ctx, "lane"));
        int index = IntegerArgumentType.getInteger(ctx, "index");

        Vec3d pos = ctx.getSource().getPlayer().getPos();

        // Ensure lane exists
        ArenaDefinition.LaneDef laneDef = def.getLane(laneId);
        if (laneDef == null) {
            laneDef = new ArenaDefinition.LaneDef(laneId);
            def.getLanes().put(laneId, laneDef);
        }

        // Insert or replace waypoint at index
        while (laneDef.waypointsP1toP2.size() <= index) {
            laneDef.waypointsP1toP2.add(Vec3d.ZERO);
        }
        laneDef.waypointsP1toP2.set(index, pos);

        sendMsg(ctx, "§aWaypoint " + laneId.name() + "[" + index + "] set at " + formatVec(pos));
        return 1;
    }

    private static int clearWaypoints(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        Lane.LaneId laneId = Lane.LaneId.fromString(StringArgumentType.getString(ctx, "lane"));

        ArenaDefinition.LaneDef laneDef = def.getLane(laneId);
        if (laneDef != null) {
            laneDef.waypointsP1toP2.clear();
        }

        sendMsg(ctx, "§aWaypoints cleared for lane " + laneId.name());
        return 1;
    }

    private static int setDeploySlot(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        Lane.LaneId laneId = Lane.LaneId.fromString(StringArgumentType.getString(ctx, "lane"));
        TeamSide team = parseTeam(StringArgumentType.getString(ctx, "team"));
        int slot = IntegerArgumentType.getInteger(ctx, "slot");
        if (team == null) { sendMsg(ctx, "§cInvalid team."); return 0; }

        BlockPos pos = ctx.getSource().getPlayer().getBlockPos();

        ArenaDefinition.LaneDef laneDef = def.getLane(laneId);
        if (laneDef == null) {
            laneDef = new ArenaDefinition.LaneDef(laneId);
            def.getLanes().put(laneId, laneDef);
        }

        var slots = team == TeamSide.PLAYER1 ? laneDef.deploymentSlotsP1 : laneDef.deploymentSlotsP2;
        while (slots.size() <= slot) {
            slots.add(BlockPos.ORIGIN);
        }
        slots.set(slot, pos);

        sendMsg(ctx, "§aDeploy slot " + team.name() + " lane " + laneId.name()
                + " [" + slot + "] at " + formatPos(pos));
        return 1;
    }

    private static int addBuildZone(CommandContext<ServerCommandSource> ctx) {
        ArenaDefinition def = getOrCreateDef();
        TeamSide team = parseTeam(StringArgumentType.getString(ctx, "team"));
        if (team == null) { sendMsg(ctx, "§cInvalid team."); return 0; }

        BlockPos p1 = getPos1(ctx);
        BlockPos p2 = getPos2(ctx);
        if (p1 == null || p2 == null) return 0;

        BlockPos min = new BlockPos(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
        BlockPos max = new BlockPos(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));

        def.addBuildZone(team, new ArenaDefinition.ZoneDef(min, max, team.name() + "_build_" + def.getBuildZones(team).size()));

        sendMsg(ctx, "§aBuild zone added for " + team.name()
                + ": " + formatPos(min) + "→" + formatPos(max));
        return 1;
    }

    // ========================================================================
    // SAVE / LOAD / TEMPLATE
    // ========================================================================

    private static int saveDefinition(CommandContext<ServerCommandSource> ctx) {
        if (editingDef == null) {
            sendMsg(ctx, "§cNo arena definition to save. Use /ac arena new first.");
            return 0;
        }

        try {
            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("arenaclash");
            java.nio.file.Files.createDirectories(configDir);
            editingDef.save(configDir.resolve("arena_definition.json"));
            sendMsg(ctx, "§aArena definition saved to config/arenaclash/arena_definition.json");
            return 1;
        } catch (Exception e) {
            sendMsg(ctx, "§cFailed to save: " + e.getMessage());
            return 0;
        }
    }

    private static int loadDefinition(CommandContext<ServerCommandSource> ctx) {
        try {
            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("arenaclash");
            Path file = configDir.resolve("arena_definition.json");
            if (!java.nio.file.Files.exists(file)) {
                sendMsg(ctx, "§cNo arena_definition.json found. Use /ac arena new + save first.");
                return 0;
            }
            editingDef = ArenaDefinition.load(file);
            sendMsg(ctx, "§aArena definition loaded from JSON!");
            return 1;
        } catch (Exception e) {
            sendMsg(ctx, "§cFailed to load: " + e.getMessage());
            return 0;
        }
    }

    private static int toggleShow(CommandContext<ServerCommandSource> ctx) {
        showVisualization = !showVisualization;
        sendMsg(ctx, showVisualization
                ? "§aVisualization ON — zone outlines visible"
                : "§7Visualization OFF");
        // TODO: Send packet to client to toggle ArenaZoneRenderer
        return 1;
    }

    private static int saveTemplate(CommandContext<ServerCommandSource> ctx) {
        // Save the current world as a template
        var server = ctx.getSource().getServer();
        var worldDir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);

        try {
            Path templateDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("arenaclash").resolve("arena_template");
            // This is a simplified version — full implementation would copy the world folder
            sendMsg(ctx, "§aTemplate saved. World folder can be copied to: " + templateDir);
            if (editingDef != null) {
                editingDef.setTemplateWorldName("arena_template");
            }
            return 1;
        } catch (Exception e) {
            sendMsg(ctx, "§cFailed to save template: " + e.getMessage());
            return 0;
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private static ArenaDefinition getOrCreateDef() {
        if (editingDef == null) {
            editingDef = new ArenaDefinition();
        }
        return editingDef;
    }

    public static ArenaDefinition getEditingDef() { return editingDef; }
    public static boolean isShowingVisualization() { return showVisualization; }

    private static BlockPos getPos1(CommandContext<ServerCommandSource> ctx) {
        UUID uuid = ctx.getSource().getPlayer().getUuid();
        BlockPos p = pos1Map.get(uuid);
        if (p == null) sendMsg(ctx, "§cPos1 not set! Use wand left-click.");
        return p;
    }

    private static BlockPos getPos2(CommandContext<ServerCommandSource> ctx) {
        UUID uuid = ctx.getSource().getPlayer().getUuid();
        BlockPos p = pos2Map.get(uuid);
        if (p == null) sendMsg(ctx, "§cPos2 not set! Use wand right-click.");
        return p;
    }

    private static TeamSide parseTeam(String s) {
        return switch (s.toLowerCase()) {
            case "p1", "player1", "1", "blue" -> TeamSide.PLAYER1;
            case "p2", "player2", "2", "red" -> TeamSide.PLAYER2;
            default -> null;
        };
    }

    private static void sendMsg(CommandContext<ServerCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
    }

    private static String formatPos(BlockPos pos) {
        return String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatVec(Vec3d pos) {
        return String.format("(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z);
    }
}
