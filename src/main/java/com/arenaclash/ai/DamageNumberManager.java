package com.arenaclash.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Floating damage numbers displayed as rising armor stands with custom names.
 *
 * Each damage event spawns an invisible armor stand:
 * - Custom name shows damage value with color coding
 * - Rises upward with velocity
 * - Fades (removes) after 20 ticks (1 second)
 *
 * Colors:
 * - WHITE: Physical damage
 * - LIGHT_PURPLE: Magic damage
 * - GOLD: AoE damage
 */
public class DamageNumberManager {

    public enum DamageType {
        PHYSICAL(Formatting.WHITE),
        MAGIC(Formatting.LIGHT_PURPLE),
        AOE(Formatting.GOLD);

        public final Formatting color;
        DamageType(Formatting color) { this.color = color; }
    }

    private static final int LIFETIME_TICKS = 20;
    private static final double RISE_SPEED = 0.06;
    private static final double RANDOM_OFFSET = 0.5;

    private final ServerWorld world;
    private final List<DamageNumber> activeNumbers = new ArrayList<>();

    public DamageNumberManager(ServerWorld world) {
        this.world = world;
    }

    /**
     * Spawn a floating damage number at the given position.
     */
    public void spawn(Vec3d pos, float damage, DamageType type) {
        // Small random offset to avoid overlap
        double ox = (world.random.nextDouble() - 0.5) * RANDOM_OFFSET;
        double oz = (world.random.nextDouble() - 0.5) * RANDOM_OFFSET;

        ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        stand.setPosition(pos.x + ox, pos.y + 1.5, pos.z + oz);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setMarker(true); // No hitbox
        stand.setCustomNameVisible(true);

        // Format damage number
        String dmgText = formatDamage(damage);
        stand.setCustomName(Text.literal(dmgText).formatted(type.color, Formatting.BOLD));

        world.spawnEntity(stand);
        activeNumbers.add(new DamageNumber(stand, LIFETIME_TICKS));
    }

    /**
     * Tick all active damage numbers. Call once per server tick.
     */
    public void tick() {
        Iterator<DamageNumber> it = activeNumbers.iterator();
        while (it.hasNext()) {
            DamageNumber num = it.next();
            num.remainingTicks--;

            if (num.remainingTicks <= 0 || num.entity.isRemoved()) {
                num.entity.discard();
                it.remove();
                continue;
            }

            // Rise upward
            Vec3d currentPos = num.entity.getPos();
            num.entity.setPosition(currentPos.x, currentPos.y + RISE_SPEED, currentPos.z);
        }
    }

    /**
     * Remove all active damage numbers.
     */
    public void clear() {
        for (DamageNumber num : activeNumbers) {
            num.entity.discard();
        }
        activeNumbers.clear();
    }

    /**
     * Format damage value for display.
     */
    private String formatDamage(float damage) {
        if (damage == (int) damage) {
            return String.valueOf((int) damage);
        }
        return String.format("%.1f", damage);
    }

    private static class DamageNumber {
        final ArmorStandEntity entity;
        int remainingTicks;

        DamageNumber(ArmorStandEntity entity, int lifetime) {
            this.entity = entity;
            this.remainingTicks = lifetime;
        }
    }
}
