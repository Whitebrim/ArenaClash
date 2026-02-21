package com.arenaclash.card;

import com.arenaclash.card.MobCardDefinition.MobCategory;
import net.minecraft.entity.EntityType;

import java.util.*;

public class MobCardRegistry {
    private static final Map<String, MobCardDefinition> REGISTRY = new LinkedHashMap<>();
    private static final Map<EntityType<?>, MobCardDefinition> BY_ENTITY = new HashMap<>();

    public static void init() {
        // === UNDEAD ===
        register(new MobCardDefinition(
                EntityType.ZOMBIE, "zombie", "Zombie",
                20.0, 2.3, 4.0, 20,
                true, true, List.of("sword", "axe", "shovel"), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.SKELETON, "skeleton", "Skeleton",
                20.0, 2.5, 3.0, 25,
                true, true, List.of("bow", "sword"), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.HUSK, "husk", "Husk",
                20.0, 2.3, 4.0, 20,
                true, true, List.of("sword", "axe", "shovel"), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.STRAY, "stray", "Stray",
                20.0, 2.5, 3.0, 25,
                true, true, List.of("bow", "sword"), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.DROWNED, "drowned", "Drowned",
                20.0, 2.3, 4.0, 20,
                true, true, List.of("trident", "sword"), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.PHANTOM, "phantom", "Phantom",
                20.0, 3.5, 6.0, 30,
                false, false, List.of(), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.WITHER_SKELETON, "wither_skeleton", "Wither Skeleton",
                20.0, 2.5, 8.0, 20,
                true, true, List.of("sword", "axe"), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.ZOMBIFIED_PIGLIN, "zombified_piglin", "Zombified Piglin",
                20.0, 2.3, 6.0, 20,
                true, true, List.of("sword", "axe"), MobCategory.UNDEAD));

        // === ARTHROPOD ===
        register(new MobCardDefinition(
                EntityType.SPIDER, "spider", "Spider",
                16.0, 3.0, 3.0, 18,
                false, false, List.of(), MobCategory.ARTHROPOD));
        register(new MobCardDefinition(
                EntityType.CAVE_SPIDER, "cave_spider", "Cave Spider",
                12.0, 3.0, 3.0, 18,
                false, false, List.of(), MobCategory.ARTHROPOD));
        register(new MobCardDefinition(
                EntityType.SILVERFISH, "silverfish", "Silverfish",
                8.0, 3.5, 1.0, 10,
                false, false, List.of(), MobCategory.ARTHROPOD));
        register(new MobCardDefinition(
                EntityType.ENDERMITE, "endermite", "Endermite",
                8.0, 3.0, 2.0, 12,
                false, false, List.of(), MobCategory.ARTHROPOD));

        // === HOSTILE - SPECIAL ===
        register(new MobCardDefinition(
                EntityType.CREEPER, "creeper", "Creeper",
                20.0, 2.5, 0.0, 0,   // Creeper explodes, no melee
                false, false, List.of("potion"), MobCategory.NEUTRAL));
        register(new MobCardDefinition(
                EntityType.WITCH, "witch", "Witch",
                26.0, 2.5, 2.0, 40,
                false, false, List.of("potion"), MobCategory.NEUTRAL));
        register(new MobCardDefinition(
                EntityType.SLIME, "slime", "Slime",
                16.0, 2.0, 4.0, 20,
                false, false, List.of(), MobCategory.NEUTRAL));
        register(new MobCardDefinition(
                EntityType.MAGMA_CUBE, "magma_cube", "Magma Cube",
                16.0, 2.0, 6.0, 20,
                false, false, List.of(), MobCategory.NETHER));

        // === NETHER ===
        register(new MobCardDefinition(
                EntityType.BLAZE, "blaze", "Blaze",
                20.0, 2.5, 6.0, 40,
                false, false, List.of(), MobCategory.NETHER));
        register(new MobCardDefinition(
                EntityType.GHAST, "ghast", "Ghast",
                10.0, 2.0, 10.0, 60,
                false, false, List.of(), MobCategory.NETHER));
        register(new MobCardDefinition(
                EntityType.PIGLIN, "piglin", "Piglin",
                16.0, 2.5, 5.0, 20,
                true, true, List.of("sword", "crossbow"), MobCategory.NETHER));
        register(new MobCardDefinition(
                EntityType.PIGLIN_BRUTE, "piglin_brute", "Piglin Brute",
                50.0, 2.5, 10.0, 20,
                true, true, List.of("axe", "sword"), MobCategory.NETHER));
        register(new MobCardDefinition(
                EntityType.HOGLIN, "hoglin", "Hoglin",
                40.0, 2.8, 8.0, 25,
                false, false, List.of(), MobCategory.NETHER));

        // === END ===
        register(new MobCardDefinition(
                EntityType.ENDERMAN, "enderman", "Enderman",
                40.0, 3.5, 7.0, 18,
                false, false, List.of(), MobCategory.END));
        register(new MobCardDefinition(
                EntityType.SHULKER, "shulker", "Shulker",
                30.0, 0.0, 4.0, 60,   // Shulker is stationary, ranged
                false, false, List.of(), MobCategory.END));

        // === ILLAGERS ===
        register(new MobCardDefinition(
                EntityType.VINDICATOR, "vindicator", "Vindicator",
                24.0, 2.8, 8.0, 18,
                true, true, List.of("axe", "sword"), MobCategory.ILLAGER));
        register(new MobCardDefinition(
                EntityType.PILLAGER, "pillager", "Pillager",
                24.0, 2.5, 4.0, 30,
                true, true, List.of("crossbow"), MobCategory.ILLAGER));
        register(new MobCardDefinition(
                EntityType.EVOKER, "evoker", "Evoker",
                24.0, 2.5, 6.0, 50,
                false, false, List.of(), MobCategory.ILLAGER));
        register(new MobCardDefinition(
                EntityType.RAVAGER, "ravager", "Ravager",
                100.0, 2.5, 12.0, 25,
                false, false, List.of(), MobCategory.ILLAGER));
        register(new MobCardDefinition(
                EntityType.VEX, "vex", "Vex",
                14.0, 3.5, 5.0, 18,
                true, false, List.of("sword"), MobCategory.ILLAGER));

        // === GOLEMS ===
        register(new MobCardDefinition(
                EntityType.IRON_GOLEM, "iron_golem", "Iron Golem",
                100.0, 2.5, 15.0, 25,
                false, false, List.of(), MobCategory.GOLEM));
        register(new MobCardDefinition(
                EntityType.SNOW_GOLEM, "snow_golem", "Snow Golem",
                4.0, 2.5, 0.0, 20,    // Throws snowballs (knockback)
                false, false, List.of(), MobCategory.GOLEM));

        // === ANIMALS (cannon fodder / utility) ===
        register(new MobCardDefinition(
                EntityType.WOLF, "wolf", "Wolf",
                8.0, 3.2, 4.0, 15,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.BEE, "bee", "Bee",
                10.0, 3.5, 2.0, 20,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.LLAMA, "llama", "Llama",
                22.0, 2.5, 1.0, 30,  // Spits
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.GOAT, "goat", "Goat",
                10.0, 2.8, 2.0, 40,  // Rams with knockback
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.CHICKEN, "chicken", "Chicken",
                4.0, 2.5, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.COW, "cow", "Cow",
                10.0, 2.0, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.PIG, "pig", "Pig",
                10.0, 2.5, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.SHEEP, "sheep", "Sheep",
                8.0, 2.3, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));

        // === BOSS ===
        register(new MobCardDefinition(
                EntityType.WARDEN, "warden", "Warden",
                500.0, 3.0, 30.0, 25,
                false, false, List.of(), MobCategory.BOSS));
        register(new MobCardDefinition(
                EntityType.WITHER, "wither", "Wither",
                300.0, 2.5, 12.0, 20,
                false, false, List.of(), MobCategory.BOSS));
        register(new MobCardDefinition(
                EntityType.ELDER_GUARDIAN, "elder_guardian", "Elder Guardian",
                80.0, 1.5, 8.0, 40,
                false, false, List.of(), MobCategory.BOSS));

        // === SPECIAL: Baby Zombie ===
        // Uses EntityType.ZOMBIE but registered by ID only (not by entity type to avoid conflict)
        registerById(new MobCardDefinition(
                EntityType.ZOMBIE, "baby_zombie", "Baby Zombie",
                20.0, 3.5, 3.0, 15,  // Faster than regular zombie, less damage
                true, true, List.of("sword", "axe"), MobCategory.UNDEAD));

        // === ADDITIONAL ANIMALS ===
        register(new MobCardDefinition(
                EntityType.CAT, "cat", "Cat",
                10.0, 3.0, 2.0, 18,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.OCELOT, "ocelot", "Ocelot",
                10.0, 3.2, 2.0, 18,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.HORSE, "horse", "Horse",
                26.0, 3.5, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.DONKEY, "donkey", "Donkey",
                22.0, 2.8, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.MULE, "mule", "Mule",
                24.0, 3.0, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.RABBIT, "rabbit", "Rabbit",
                3.0, 3.5, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.FOX, "fox", "Fox",
                10.0, 3.0, 2.0, 18,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.PANDA, "panda", "Panda",
                20.0, 2.0, 6.0, 30,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.TURTLE, "turtle", "Turtle",
                30.0, 1.0, 0.0, 0,  // Slow tank, no damage
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.PARROT, "parrot", "Parrot",
                6.0, 3.5, 1.0, 20,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.MOOSHROOM, "mooshroom", "Mooshroom",
                10.0, 2.0, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.POLAR_BEAR, "polar_bear", "Polar Bear",
                30.0, 2.5, 6.0, 25,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.DOLPHIN, "dolphin", "Dolphin",
                10.0, 3.5, 2.0, 18,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.SQUID, "squid", "Squid",
                10.0, 2.0, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.GLOW_SQUID, "glow_squid", "Glow Squid",
                10.0, 2.0, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.AXOLOTL, "axolotl", "Axolotl",
                14.0, 2.5, 2.0, 20,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.FROG, "frog", "Frog",
                10.0, 3.0, 1.0, 20,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.CAMEL, "camel", "Camel",
                32.0, 2.5, 0.0, 0,  // Tanky, no damage
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.SNIFFER, "sniffer", "Sniffer",
                14.0, 1.5, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.ARMADILLO, "armadillo", "Armadillo",
                12.0, 2.0, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));

        // === ADDITIONAL HOSTILE (1.21 / 1.21.1) ===
        register(new MobCardDefinition(
                EntityType.BREEZE, "breeze", "Breeze",
                30.0, 3.0, 4.0, 30,
                false, false, List.of(), MobCategory.NEUTRAL));
        register(new MobCardDefinition(
                EntityType.BOGGED, "bogged", "Bogged",
                16.0, 2.5, 3.0, 25,
                true, true, List.of("bow", "sword"), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.GUARDIAN, "guardian", "Guardian",
                30.0, 2.0, 6.0, 40,
                false, false, List.of(), MobCategory.NEUTRAL));
        register(new MobCardDefinition(
                EntityType.ZOGLIN, "zoglin", "Zoglin",
                40.0, 3.0, 8.0, 20,
                false, false, List.of(), MobCategory.NETHER));
        register(new MobCardDefinition(
                EntityType.ZOMBIE_VILLAGER, "zombie_villager", "Zombie Villager",
                20.0, 2.3, 3.0, 20,
                true, true, List.of("sword", "axe"), MobCategory.UNDEAD));

        // === UTILITY / SPECIAL NEUTRAL ===
        register(new MobCardDefinition(
                EntityType.ALLAY, "allay", "Allay",
                20.0, 3.5, 0.0, 0,   // Support mob, no damage
                false, false, List.of(), MobCategory.NEUTRAL));
        register(new MobCardDefinition(
                EntityType.BAT, "bat", "Bat",
                6.0, 4.0, 0.0, 0,     // Fast, fragile, no damage
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.STRIDER, "strider", "Strider",
                20.0, 2.0, 0.0, 0,
                false, false, List.of(), MobCategory.NETHER));
        register(new MobCardDefinition(
                EntityType.TRADER_LLAMA, "trader_llama", "Trader Llama",
                22.0, 2.5, 1.0, 30,  // Spits
                false, false, List.of(), MobCategory.ANIMAL));

        // === SKELETON / ZOMBIE HORSES ===
        register(new MobCardDefinition(
                EntityType.SKELETON_HORSE, "skeleton_horse", "Skeleton Horse",
                15.0, 4.0, 0.0, 0,   // Very fast, no damage
                false, false, List.of(), MobCategory.UNDEAD));
        register(new MobCardDefinition(
                EntityType.ZOMBIE_HORSE, "zombie_horse", "Zombie Horse",
                15.0, 3.5, 0.0, 0,
                false, false, List.of(), MobCategory.UNDEAD));

        // === AQUATIC ===
        register(new MobCardDefinition(
                EntityType.COD, "cod", "Cod",
                3.0, 2.0, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.SALMON, "salmon", "Salmon",
                3.0, 2.5, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.PUFFERFISH, "pufferfish", "Pufferfish",
                3.0, 2.0, 2.0, 30,   // Poisons on contact
                false, false, List.of(), MobCategory.ANIMAL));
        register(new MobCardDefinition(
                EntityType.TROPICAL_FISH, "tropical_fish", "Tropical Fish",
                3.0, 2.0, 0.0, 0,
                false, false, List.of(), MobCategory.ANIMAL));
    }

    private static void register(MobCardDefinition def) {
        REGISTRY.put(def.id(), def);
        BY_ENTITY.put(def.entityType(), def);
    }

    /**
     * Register by ID only, without mapping to entity type.
     * Used for variant cards like baby_zombie that share EntityType with another card.
     */
    private static void registerById(MobCardDefinition def) {
        REGISTRY.put(def.id(), def);
    }

    public static MobCardDefinition getById(String id) {
        return REGISTRY.get(id);
    }

    public static MobCardDefinition getByEntityType(EntityType<?> type) {
        return BY_ENTITY.get(type);
    }

    public static Collection<MobCardDefinition> getAll() {
        return REGISTRY.values();
    }

    public static boolean isRegistered(EntityType<?> type) {
        return BY_ENTITY.containsKey(type);
    }
}
