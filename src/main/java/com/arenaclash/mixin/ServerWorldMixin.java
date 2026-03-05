package com.arenaclash.mixin;

import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Three-in-one mixin on ServerLevel.addFreshEntity():
 *
 * 1. Prevent XP orb spawning during survival phase.
 * 2. Tag spawner-spawned mobs (detected by proximity to a MobSpawnerBlockEntity)
 *    with "arenaclash_spawner_mob" tag + red label → no cards on kill.
 * 3. Replace raw-ore ItemEntity drops with smelted ingots during survival phase
 *    so players don't need to waste time on furnaces.
 */
@Mixin(ServerLevel.class)
public class ServerWorldMixin {

    /** Spawner scan radius (blocks). Spawners spawn mobs within 4 blocks horizontally. */
    @Unique
    private static final int SPAWNER_SCAN_RADIUS = 5;

    // --- Lazily initialized ore replacement map ---
    @Unique
    private static Map<Item, ItemStack> arenaclash$oreItemReplacements;

    @Unique
    private static Map<Item, ItemStack> arenaclash$getOreReplacements() {
        if (arenaclash$oreItemReplacements == null) {
            arenaclash$oreItemReplacements = new HashMap<>();
            // Raw ores → ingots (these are what ores drop by default without silk touch)
            arenaclash$oreItemReplacements.put(Items.RAW_IRON, new ItemStack(Items.IRON_INGOT));
            arenaclash$oreItemReplacements.put(Items.RAW_GOLD, new ItemStack(Items.GOLD_INGOT));
            arenaclash$oreItemReplacements.put(Items.RAW_COPPER, new ItemStack(Items.COPPER_INGOT));
            // Note: Ancient Debris is NOT auto-smelted because it drops itself (the block)
            // unlike other ores which drop raw materials. This also respects Silk Touch.
            // Nether gold ore drops gold nuggets; replace with ingot for convenience
            // (9 nuggets = 1 ingot, but just giving 1 nugget → 1 ingot is too generous;
            //  keep the nuggets as-is since they're already smelted)
        }
        return arenaclash$oreItemReplacements;
    }

    // ================================================================
    // 1. Prevent XP orbs
    // ================================================================

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void arenaclash$onSpawnEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        boolean isSurvival = arenaclash$isSurvivalPhase();

        // --- XP prevention ---
        if (entity instanceof ExperienceOrb && isSurvival) {
            cir.setReturnValue(false);
            return;
        }

        // --- Spawner mob detection ---
        if (entity instanceof Mob mob && isSurvival) {
            if (arenaclash$isNearSpawner(mob)) {
                arenaclash$tagAsSpawnerMob(mob);
            }
        }

        // --- Ore item replacement ---
        if (entity instanceof ItemEntity itemEntity && isSurvival) {
            ItemStack stack = itemEntity.getItem();
            ItemStack replacement = arenaclash$getOreReplacements().get(stack.getItem());
            if (replacement != null) {
                // Preserve count (e.g. fortune gives multiple raw_iron → multiple iron_ingot)
                ItemStack newStack = replacement.copy();
                newStack.setCount(stack.getCount());
                itemEntity.setItem(newStack);
            }
        }
    }

    // ================================================================
    // Spawner detection via proximity to spawner blocks
    // ================================================================

    @Unique
    private boolean arenaclash$isNearSpawner(Mob mob) {
        ServerLevel world = (ServerLevel) (Object) this;
        BlockPos mobPos = mob.blockPosition();

        // Scan a cube around the mob for spawner blocks.
        // Spawners spawn mobs within 4 horizontal blocks, so 5 is a safe radius.
        // False positives (natural mob happens to spawn near a spawner) are rare
        // and inconsequential — the mob just won't give a card.
        for (BlockPos pos : BlockPos.withinManhattan(mobPos, SPAWNER_SCAN_RADIUS, SPAWNER_SCAN_RADIUS, SPAWNER_SCAN_RADIUS)) {
            if (world.getBlockState(pos).is(Blocks.SPAWNER)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private void arenaclash$tagAsSpawnerMob(Mob mob) {
        // Command tag → death handler skips card creation
        mob.addTag("arenaclash_spawner_mob");

        // Red ✘ + "Spawner" label above head
        mob.setCustomName(Component.translatable("arenaclash.msg.spawner_tag"));
        mob.setCustomNameVisible(true);
    }

    // ================================================================
    // Utility
    // ================================================================

    @Unique
    private static boolean arenaclash$isSurvivalPhase() {
        GameManager gm = GameManager.getInstance();
        return (gm.isGameActive() && gm.getPhase() == GamePhase.SURVIVAL)
                || com.arenaclash.tcp.SingleplayerBridge.survivalPhaseActive;
    }
}
