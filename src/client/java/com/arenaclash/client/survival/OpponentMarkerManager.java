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
 * Manages two ghostly ArmorStand entities on the integrated server that represent
 * the opponent player during survival phase.
 *
 * TWO entities are used because marker ArmorStands have height=0, which causes
 * the nametag to render at feet level:
 *   1) BODY stand - Marker=true, ShowArms=true, holds equipment, NO nametag.
 *      Positioned at the opponent's actual coordinates.
 *   2) NAMETAG stand - Marker=true, has CustomName visible, NO equipment.
 *      Positioned NAMETAG_Y_OFFSET blocks above the body so the name floats
 *      above the visible equipment like a real player's nametag.
 *
 * Both entities live on the INTEGRATED SERVER - they sync to the client
 * automatically through normal Minecraft entity tracking.
 *
 * Key properties:
 * - Marker=true  -> no hitbox, no collision, cannot be interacted with
 * - Invisible=true -> body hidden, only equipment + nametag visible
 * - Invulnerable=true -> cannot be damaged
 * - Position interpolated smoothly for fluid movement
 * - Disappears when dimensions don't match (opponent in Nether, you in Overworld)
 * - Auto-removes after 5 seconds without an update (opponent disconnected)
 */
public class OpponentMarkerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-OpponentMarker");

    /** Stable UUIDs so we can find/replace the entities reliably. */
    private static final UUID BODY_UUID = UUID.fromString("00000000-aaaa-cccc-aaaa-000000000001");
    private static final UUID NAMETAG_UUID = UUID.fromString("00000000-aaaa-cccc-aaaa-000000000002");

    /**
     * Height offset for the nametag stand above the body stand.
     * Marker armor stands have height=0, so nametag renders at feet position.
     * We place a separate stand higher so the name floats above the "head".
     */
    private static final double NAMETAG_Y_OFFSET = 2.0;

    private static ArmorStandEntity bodyEntity = null;
    private static ArmorStandEntity nametagEntity = null;
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

        // Timeout - opponent disconnected or stopped sending
        ticksSinceLastUpdate++;
        if (ticksSinceLastUpdate > TIMEOUT_TICKS) {
            remove();
            return;
        }

        // Dimension check - hide marker if opponent is in a different dimension
        String localDim = client.world.getRegistryKey().getValue().toString();
        String opDim = targetDimension;
        if (opDim != null && !opDim.equals(localDim)) {
            removeEntities();
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

            // Create entities if missing
            if (bodyEntity == null || bodyEntity.isRemoved()
                    || nametagEntity == null || nametagEntity.isRemoved()) {
                createMarkers(world, tx, ty, tz, tyaw);
            }

            if (bodyEntity == null || nametagEntity == null) return;

            // Interpolate position
            currentX += (tx - currentX) * LERP_SPEED;
            currentY += (ty - currentY) * LERP_SPEED;
            currentZ += (tz - currentZ) * LERP_SPEED;

            // Interpolate yaw (handle 360 wrap)
            float yawDiff = tyaw - currentYaw;
            while (yawDiff > 180) yawDiff -= 360;
            while (yawDiff < -180) yawDiff += 360;
            currentYaw += yawDiff * LERP_SPEED;

            // Update body stand (at opponent's actual position)
            bodyEntity.setPosition(currentX, currentY, currentZ);
            bodyEntity.setYaw(currentYaw);
            bodyEntity.setHeadYaw(currentYaw);
            bodyEntity.setBodyYaw(currentYaw);

            // Update nametag stand (offset above body)
            nametagEntity.setPosition(currentX, currentY + NAMETAG_Y_OFFSET, currentZ);
            nametagEntity.setYaw(currentYaw);
            nametagEntity.setHeadYaw(currentYaw);
            nametagEntity.setBodyYaw(currentYaw);

            // Apply equipment to body stand only
            if (eqSnbt != null) {
                applyEquipment(eqSnbt, world);
            }
        });
    }

    /**
     * Remove both markers entirely. Called when leaving survival or disconnecting.
     */
    public static void remove() {
        removeEntities();
        hasTarget = false;
        opponentName = null;
        pendingEquipment = null;
        targetDimension = null;
        ticksSinceLastUpdate = 0;
    }

    // =====================================================================
    // INTERNALS
    // =====================================================================

    private static void removeEntities() {
        MinecraftClient client = MinecraftClient.getInstance();
        var server = client.getServer();

        if (bodyEntity != null) {
            ArmorStandEntity entity = bodyEntity;
            bodyEntity = null;
            if (server != null && server.isRunning()) {
                server.execute(entity::discard);
            }
        }
        if (nametagEntity != null) {
            ArmorStandEntity entity = nametagEntity;
            nametagEntity = null;
            if (server != null && server.isRunning()) {
                server.execute(entity::discard);
            }
        }
    }

    private static void createMarkers(ServerWorld world, double x, double y, double z, float yaw) {
        try {
            // Remove any leftover markers with same UUIDs
            var existingBody = world.getEntity(BODY_UUID);
            if (existingBody != null) existingBody.discard();
            var existingNametag = world.getEntity(NAMETAG_UUID);
            if (existingNametag != null) existingNametag.discard();

            // Snap position (no interpolation for first frame)
            currentX = x;
            currentY = y;
            currentZ = z;
            currentYaw = yaw;

            // --- BODY STAND: equipment visible, no nametag ---
            ArmorStandEntity body = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
            body.setUuid(BODY_UUID);
            body.setPosition(x, y, z);
            body.setYaw(yaw);
            body.setInvisible(true);      // Hide the wooden body
            body.setInvulnerable(true);   // Can't be damaged
            body.setNoGravity(true);      // Floats freely
            body.setSilent(true);         // No sounds
            body.setShowArms(true);       // Show held items / weapons
            body.setMarker(true);         // No hitbox at all
            body.setHideBasePlate(true);  // No stone slab at the feet
            body.setCustomNameVisible(false); // No nametag on body stand
            body.addCommandTag("arenaclash_opponent_marker");
            world.spawnEntity(body);
            bodyEntity = body;

            // --- NAMETAG STAND: nametag visible, no equipment ---
            ArmorStandEntity nametag = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
            nametag.setUuid(NAMETAG_UUID);
            nametag.setPosition(x, y + NAMETAG_Y_OFFSET, z);
            nametag.setYaw(yaw);
            nametag.setInvisible(true);
            nametag.setInvulnerable(true);
            nametag.setNoGravity(true);
            nametag.setSilent(true);
            nametag.setShowArms(false);    // No arms needed
            nametag.setMarker(true);       // No hitbox
            nametag.setHideBasePlate(true);
            nametag.addCommandTag("arenaclash_opponent_marker");

            if (opponentName != null) {
                nametag.setCustomName(
                        Text.literal(opponentName)
                                .styled(s -> s.withColor(Formatting.WHITE)));
                nametag.setCustomNameVisible(true);
            }

            world.spawnEntity(nametag);
            nametagEntity = nametag;

            LOGGER.info("Spawned opponent markers (body + nametag) for {} on integrated server", opponentName);
        } catch (Exception e) {
            LOGGER.error("Failed to create opponent markers", e);
            bodyEntity = null;
            nametagEntity = null;
        }
    }

    /**
     * Parse equipment SNBT and equip the body ArmorStand.
     * Format: {Helmet:"item snbt", Chestplate:"...", ...}
     */
    private static void applyEquipment(String snbt, ServerWorld world) {
        if (bodyEntity == null) return;
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
            // Silently ignore - equipment parsing is best-effort
        }
    }

    private static void applySlot(NbtCompound nbt, String key, EquipmentSlot slot,
                                    com.mojang.serialization.DynamicOps<net.minecraft.nbt.NbtElement> ops) {
        try {
            if (nbt.contains(key)) {
                String itemSnbt = nbt.getString(key);
                if (itemSnbt.isEmpty() || itemSnbt.equals("{}")) {
                    bodyEntity.equipStack(slot, ItemStack.EMPTY);
                } else {
                    NbtCompound itemNbt = StringNbtReader.parse(itemSnbt);
                    ItemStack stack = ItemStack.CODEC.parse(ops, itemNbt)
                            .resultOrPartial(err -> {})
                            .orElse(ItemStack.EMPTY);
                    bodyEntity.equipStack(slot, stack);
                }
            } else {
                bodyEntity.equipStack(slot, ItemStack.EMPTY);
            }
        } catch (Exception e) {
            bodyEntity.equipStack(slot, ItemStack.EMPTY);
        }
    }
}
