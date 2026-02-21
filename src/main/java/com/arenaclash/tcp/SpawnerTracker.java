package com.arenaclash.tcp;

/**
 * Thread-local flag used to detect when entities are being spawned
 * by a mob spawner. Set by MobSpawnerLogicMixin, read by ServerWorldMixin.
 *
 * Mobs spawned from spawners are tagged with "arenaclash_spawner_mob"
 * and display a red ✘ above their head. They do NOT yield cards when killed.
 */
public class SpawnerTracker {
    private static final ThreadLocal<Boolean> SPAWNER_ACTIVE = ThreadLocal.withInitial(() -> false);

    public static void setSpawnerActive(boolean active) {
        SPAWNER_ACTIVE.set(active);
    }

    public static boolean isSpawnerActive() {
        return SPAWNER_ACTIVE.get();
    }
}
