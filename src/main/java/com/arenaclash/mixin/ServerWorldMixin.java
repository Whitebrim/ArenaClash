package com.arenaclash.mixin;

import com.arenaclash.game.GameManager;
import com.arenaclash.game.GamePhase;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Three-in-one mixin on ServerWorld.spawnEntity():
 *
 * 1. Prevent XP orb spawning during survival phase.
 * 2. Tag spawner-spawned mobs (detected by proximity to a MobSpawnerBlockEntity)
 *    with "arenaclash_spawner_mob" tag + red label → no cards on kill.
 * 3. Replace raw-ore ItemEntity drops with smelted ingots during survival phase
 *    so players don't need to waste time on furnaces.
 */
@Mixin(ServerWorld.class)
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
            // Ancient debris drops itself; smelting gives netherite scrap
            arenaclash$oreItemReplacements.put(Items.ANCIENT_DEBRIS, new ItemStack(Items.NETHERITE_SCRAP));
            // Nether gold ore drops gold nuggets; replace with ingot for convenience
            // (9 nuggets = 1 ingot, but just giving 1 nugget → 1 ingot is too generous;
            //  keep the nuggets as-is since they're already smelted)
        }
        return arenaclash$oreItemReplacements;
    }

    // ================================================================
    // 1. Prevent XP orbs
    // ================================================================

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void arenaclash$onSpawnEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        boolean isSurvival = arenaclash$isSurvivalPhase();

        // --- XP prevention ---
        if (entity instanceof ExperienceOrbEntity && isSurvival) {
            cir.setReturnValue(false);
            return;
        }

        // --- Spawner mob detection ---
        if (entity instanceof MobEntity mob && isSurvival) {
            if (arenaclash$isNearSpawner(mob)) {
                arenaclash$tagAsSpawnerMob(mob);
            }
        }

        // --- Ore item replacement ---
        if (entity instanceof ItemEntity itemEntity && isSurvival) {
            ItemStack stack = itemEntity.getStack();
            ItemStack replacement = arenaclash$getOreReplacements().get(stack.getItem());
            if (replacement != null) {
                // Preserve count (e.g. fortune gives multiple raw_iron → multiple iron_ingot)
                ItemStack newStack = replacement.copy();
                newStack.setCount(stack.getCount());
                itemEntity.setStack(newStack);
            }
        }
    }

    // ================================================================
    // Spawner detection via proximity to spawner blocks
    // ================================================================

    @Unique
    private boolean arenaclash$isNearSpawner(MobEntity mob) {
        ServerWorld world = (ServerWorld) (Object) this;
        BlockPos mobPos = mob.getBlockPos();

        // Scan a cube around the mob for spawner blocks.
        // Spawners spawn mobs within 4 horizontal blocks, so 5 is a safe radius.
        // False positives (natural mob happens to spawn near a spawner) are rare
        // and inconsequential — the mob just won't give a card.
        for (BlockPos pos : BlockPos.iterateOutwards(mobPos, SPAWNER_SCAN_RADIUS, SPAWNER_SCAN_RADIUS, SPAWNER_SCAN_RADIUS)) {
            if (world.getBlockState(pos).isOf(Blocks.SPAWNER)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private void arenaclash$tagAsSpawnerMob(MobEntity mob) {
        // Command tag → death handler skips card creation
        mob.addCommandTag("arenaclash_spawner_mob");

        // Red ✘ + "Spawner" label above head
        mob.setCustomName(Text.literal("\u00a7c\u2718 Spawner"));
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
