package com.arenaclash.world;

import com.arenaclash.arena.ArenaBuilder;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the arena world (the default server overworld).
 * Survival worlds are handled client-side in singleplayer.
 */
public class WorldManager {
    private final MinecraftServer server;

    public WorldManager(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Get the arena world (the default overworld).
     */
    public ServerWorld getArenaWorld() {
        return server.getOverworld();
    }

    /**
     * Teleport a player to the arena at their team's side.
     */
    public void teleportToArena(ServerPlayerEntity player, TeamSide team) {
        ServerWorld arena = getArenaWorld();
        GameConfig cfg = GameConfig.get();

        int x = cfg.arenaCenterX;
        int y = cfg.arenaY;
        int z = cfg.arenaCenterZ;
        int halfLen = cfg.arenaLaneLength / 2;

        // P1 spawns at negative Z side, P2 at positive Z side
        int spawnZ = team == TeamSide.PLAYER1 ? z - halfLen - 15 : z + halfLen + 15;

        player.teleport(arena, x + 0.5, y + 1, spawnZ + 0.5,
                team == TeamSide.PLAYER1 ? 0 : 180, 0);
    }

    /**
     * Kick all players from the MC server.
     * They will be disconnected and return to the main menu.
     */
    public void kickAllPlayers() {
        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());
        for (ServerPlayerEntity player : players) {
            try {
                player.networkHandler.disconnect(
                        Text.translatable("arenaclash.msg.game_ended"));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Full world cleanup: remove ALL non-player entities, clear arena blocks.
     * Should be called AFTER players have been kicked (delayed by a few ticks)
     * to ensure disconnected player entities are fully gone.
     */
    public void cleanupArenaWorld() {
        ServerWorld arena = getArenaWorld();
        if (arena == null) return;

        // Remove ALL non-player entities from the world — mobs, armor stands,
        // projectiles, items, XP orbs, area effect clouds, everything.
        removeAllEntities(arena);

        // Clear arena blocks (fills the entire arena bounding box with air)
        ArenaBuilder.clearArena(arena);
    }

    /**
     * Full world reset: cleanup + rebuild arena from scratch.
     * Called with a delay after players are kicked to prepare for the next game.
     */
    public void resetArenaWorld() {
        cleanupArenaWorld();

        // Rebuild the arena so it's ready for the next game.
        // This means the next startGame() doesn't need to worry about stale state.
        ArenaBuilder.buildArena(getArenaWorld());
    }

    /**
     * Remove every non-player entity from the world.
     * Iterates twice to catch entities that might have been spawned
     * by other entities during the first pass (e.g. item drops on death).
     */
    private void removeAllEntities(ServerWorld world) {
        for (int pass = 0; pass < 2; pass++) {
            List<Entity> toRemove = new ArrayList<>();
            for (Entity entity : world.iterateEntities()) {
                if (entity instanceof ServerPlayerEntity) continue;
                toRemove.add(entity);
            }
            for (Entity entity : toRemove) {
                entity.discard();
            }
        }
    }
}
