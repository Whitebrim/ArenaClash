package com.arenaclash.client.survival;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Manages a ghostly ArmorStand entity on the integrated server that represents
 * the opponent player during survival phase. Shows their name, position,
 * equipped armor and held item in real-time.
 *
 * The entity lives on the INTEGRATED SERVER — it syncs to the client
 * automatically through normal Minecraft entity tracking. This is far more
 * reliable than injecting a client-side-only entity, which can conflict with
 * entity tracking, fail to render, or be cleaned up by internal state.
 *
 * Key properties:
 * - Marker=true  → no hitbox, no collision, cannot be interacted with
 * - ShowArms=true → weapons/items render on the stand
 * - Invisible=true → body hidden, only equipment + nametag visible
 * - Invulnerable=true → cannot be damaged
 * - Position interpolated smoothly for fluid movement
 * - Disappears when dimensions don't match (opponent in Nether, you in Overworld)
 * - Auto-removes after 5 seconds without an update (opponent disconnected)
 */
public class OpponentMarkerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-OpponentMarker");

    /** Stable UUID so we can find/replace the entity reliably. */
    private static final UUID MARKER_UUID = UUID.fromString("00000000-aaaa-cccc-aaaa-000000000001");

    private static ArmorStandEntity markerEntity = null;
    private static String opponentName = null;

    // --- Target state (written from TCP thread, read from tick thread) ---
    private static volatile double targetX, targetY, targetZ;
    private static volatile float targetYaw, targetPitch;
    private static volatile String targetDimension = null;
    private static volatile boolean hasTarget = false;
    private static volatile String pendingEquipment = null;

    // --- Interpolated state (used on tick thread only) ---
    private static double currentX, currentY, currentZ;
    private static float currentYaw;

    /** Ticks since last update from TCP. If > TIMEOUT_TICKS, marker is removed. */
    private static int ticksSinceLastUpdate = 0;
    private static final int TIMEOUT_TICKS = 100; // 5 seconds

    private static final float LERP_SPEED = 0.3f;

    // =====================================================================
    // PUBLIC API
    // =====================================================================

    /**
     * Called from TCP thread when opponent state arrives.
     * Thread-safe: only writes volatile fields.
     */
    public static void onOpponentState(String name, double x, double y, double z,
                                        float yaw, float pitch, String dimension,
                                        String equipmentSnbt) {
        opponentName = name;
        targetX = x;
        targetY = y;
        targetZ = z;
        targetYaw = yaw;
        targetPitch = pitch;
        targetDimension = dimension;
        hasTarget = true;
        ticksSinceLastUpdate = 0;
        if (equipmentSnbt != null) {
            pendingEquipment = equipmentSnbt;
        }
    }

    /**
     * Called every client tick during survival phase.
     * Schedules entity creation/updates on the integrated server thread.
     */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || !hasTarget) {
            return;
        }

        // Timeout — opponent disconnected or stopped sending
        ticksSinceLastUpdate++;
        if (ticksSinceLastUpdate > TIMEOUT_TICKS) {
            remove();
            return;
        }

        // Dimension check — hide marker if opponent is in a different dimension
        String localDim = client.world.getRegistryKey().getValue().toString();
        String opDim = targetDimension;
        if (opDim != null && !opDim.equals(localDim)) {
            removeEntity();
            return;
        }

        // We need the integrated server to spawn/update entities
        var server = client.getServer();
        if (server == null || !server.isRunning()) return;

        // Capture state for server thread
        final double tx = targetX, ty = targetY, tz = targetZ;
        final float tyaw = targetYaw;
        final String eqSnbt = pendingEquipment;
        pendingEquipment = null; // consume

        server.execute(() -> {
            ServerWorld world = server.getOverworld();
            if (world == null) return;

            // Create entity if missing
            if (markerEntity == null || markerEntity.isRemoved()) {
                createMarker(world, tx, ty, tz, tyaw);
            }

            if (markerEntity == null) return;

            // Interpolate position
            currentX += (tx - currentX) * LERP_SPEED;
            currentY += (ty - currentY) * LERP_SPEED;
            currentZ += (tz - currentZ) * LERP_SPEED;

            // Interpolate yaw (handle 360° wrap)
            float yawDiff = tyaw - currentYaw;
            while (yawDiff > 180) yawDiff -= 360;
            while (yawDiff < -180) yawDiff += 360;
            currentYaw += yawDiff * LERP_SPEED;

            markerEntity.setPosition(currentX, currentY, currentZ);
            markerEntity.setYaw(currentYaw);
            markerEntity.setHeadYaw(currentYaw);
            markerEntity.setBodyYaw(currentYaw);

            // Apply equipment if changed
            if (eqSnbt != null) {
                applyEquipment(eqSnbt, world);
            }
        });
    }

    /**
     * Remove the marker entirely. Called when leaving survival or disconnecting.
     */
    public static void remove() {
        removeEntity();
        hasTarget = false;
        opponentName = null;
        pendingEquipment = null;
        targetDimension = null;
        ticksSinceLastUpdate = 0;
    }

    // =====================================================================
    // INTERNALS
    // =====================================================================

    private static void removeEntity() {
        if (markerEntity != null) {
            ArmorStandEntity entity = markerEntity;
            markerEntity = null;
            // Schedule removal on the server thread
            MinecraftClient client = MinecraftClient.getInstance();
            var server = client.getServer();
            if (server != null && server.isRunning()) {
                server.execute(entity::discard);
            }
        }
    }

    private static void createMarker(ServerWorld world, double x, double y, double z, float yaw) {
        try {
            // Remove any leftover marker with same UUID
            var existing = world.getEntity(MARKER_UUID);
            if (existing != null) existing.discard();

            ArmorStandEntity entity = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
            entity.setUuid(MARKER_UUID);

            // Snap position (no interpolation for first frame)
            currentX = x;
            currentY = y;
            currentZ = z;
            currentYaw = yaw;
            entity.setPosition(x, y, z);
            entity.setYaw(yaw);

            // Core flags
            entity.setInvisible(true);     // Hide the wooden body
            entity.setInvulnerable(true);  // Can't be damaged
            entity.setNoGravity(true);     // Floats freely
            entity.setSilent(true);        // No sounds
            entity.setShowArms(true);      // Show held items / weapons
            entity.setMarker(true);        // No hitbox at all (can't interact/push/hit)
            entity.setHideBasePlate(true); // No stone slab at the feet

            // Tags so our own event handlers ignore it
            entity.addCommandTag("arenaclash_opponent_marker");

            // Nametag
            if (opponentName != null) {
                entity.setCustomName(
                        Text.literal(opponentName)
                                .styled(s -> s.withColor(Formatting.GRAY).withItalic(true)));
                entity.setCustomNameVisible(true);
            }

            world.spawnEntity(entity);
            markerEntity = entity;

            LOGGER.info("Spawned opponent marker for {} on integrated server", opponentName);
        } catch (Exception e) {
            LOGGER.error("Failed to create opponent marker", e);
            markerEntity = null;
        }
    }

    /**
     * Parse equipment SNBT and equip the ArmorStand.
     * Format: {Helmet:"item snbt", Chestplate:"...", ...}
     */
    private static void applyEquipment(String snbt, ServerWorld world) {
        if (markerEntity == null) return;
        try {
            NbtCompound nbt = StringNbtReader.parse(snbt);
            var registryOps = world.getRegistryManager().getOps(net.minecraft.nbt.NbtOps.INSTANCE);

            applySlot(nbt, "MainHand", EquipmentSlot.MAINHAND, registryOps);
            applySlot(nbt, "OffHand", EquipmentSlot.OFFHAND, registryOps);
            applySlot(nbt, "Helmet", EquipmentSlot.HEAD, registryOps);
            applySlot(nbt, "Chestplate", EquipmentSlot.CHEST, registryOps);
            applySlot(nbt, "Leggings", EquipmentSlot.LEGS, registryOps);
            applySlot(nbt, "Boots", EquipmentSlot.FEET, registryOps);
        } catch (Exception e) {
            // Silently ignore — equipment parsing is best-effort
        }
    }

    private static void applySlot(NbtCompound nbt, String key, EquipmentSlot slot,
                                    com.mojang.serialization.DynamicOps<net.minecraft.nbt.NbtElement> ops) {
        try {
            if (nbt.contains(key)) {
                String itemSnbt = nbt.getString(key);
                if (itemSnbt.isEmpty() || itemSnbt.equals("{}")) {
                    markerEntity.equipStack(slot, ItemStack.EMPTY);
                } else {
                    NbtCompound itemNbt = StringNbtReader.parse(itemSnbt);
                    ItemStack stack = ItemStack.CODEC.parse(ops, itemNbt)
                            .resultOrPartial(err -> {})
                            .orElse(ItemStack.EMPTY);
                    markerEntity.equipStack(slot, stack);
                }
            } else {
                markerEntity.equipStack(slot, ItemStack.EMPTY);
            }
        } catch (Exception e) {
            markerEntity.equipStack(slot, ItemStack.EMPTY);
        }
    }
}
