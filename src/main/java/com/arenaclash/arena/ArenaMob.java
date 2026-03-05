package com.arenaclash.arena;

import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.card.MobCardRegistry;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Arena mob with full special abilities, HP bars, flying, environmental damage, splash melee, collision.
 */
public class ArenaMob {
    public enum MobState { IDLE, ADVANCING, FIGHTING, RETREATING, DEAD }
    private enum AttackType { MELEE, RANGED, CREEPER_EXPLOSION, TELEPORT_MELEE, SUMMONER }

    private final UUID ownerId;
    private final TeamSide team;
    private final MobCard sourceCard;
    private final Lane.LaneId lane;
    private final BlockPos startSlotPos;

    private UUID entityId;
    private MobState state = MobState.IDLE;

    private final double maxHP;
    private final double moveSpeed;
    private final double attackDamage;
    private final int attackCooldown;
    private int attackCooldownRemaining = 0;

    private List<BlockPos> waypoints;
    private int currentWaypointIndex = 0;

    private UUID targetEntityId;
    private ArenaStructure targetStructure;
    private Vec3 structureApproachPos; // Assigned approach position around a structure
    private boolean markedDead = false;

    private double laneMinX, laneMaxX, laneMinZ, laneMaxZ;
    private boolean laneBoundsSet = false;

    private Vec3 lastPosition;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 60;

    // HP bar
    private UUID hpBarEntityId;

    // Evoker vex tracking
    private final List<UUID> evokerVexIds = new ArrayList<>();

    // Environmental timers
    private int sunBurnCooldown = 0;
    private int suffocationTicks = 0;

    // Sub-mob flag (vexes spawned by evoker are sub-mobs, they don't return as cards)
    private boolean isSubMob = false;

    // Deferred child mobs (vexes, slime splits) — added to activeMobs AFTER iteration
    private final List<ArenaMob> pendingChildMobs = new ArrayList<>();

    public ArenaMob(UUID ownerId, TeamSide team, MobCard card, Lane.LaneId lane, BlockPos startSlotPos) {
        this.ownerId = ownerId;
        this.team = team;
        this.sourceCard = card;
        this.lane = lane;
        this.startSlotPos = startSlotPos;
        this.maxHP = card.getHP();
        this.moveSpeed = card.getSpeed();
        this.attackDamage = card.getAttack();
        this.attackCooldown = card.getAttackCooldown();
    }

    public UUID getOwnerId() { return ownerId; }
    public TeamSide getTeam() { return team; }
    public MobCard getSourceCard() { return sourceCard; }
    public Lane.LaneId getLane() { return lane; }
    public UUID getEntityId() { return entityId; }
    public MobState getState() { return state; }
    public BlockPos getStartSlotPos() { return startSlotPos; }
    public double getMaxHP() { return maxHP; }
    public double getAttackDamage() { return attackDamage; }
    public boolean isDead() { return markedDead || state == MobState.DEAD; }
    public boolean isSubMob() { return isSubMob; }
    public void setSubMob(boolean sub) { this.isSubMob = sub; }
    public List<ArenaMob> getPendingChildMobs() { return pendingChildMobs; }

    /**
     * Returns the attack cooldown with ±10% random variance applied.
     * This prevents deterministic outcomes when two identical mobs fight.
     */
    private int getRandomizedCooldown() {
        double variance = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4; // 0.8 to 1.2
        return Math.max(1, (int) Math.round(attackCooldown * variance));
    }

    private boolean isSlimeType() {
        String id = sourceCard.getMobId();
        return id.startsWith("slime") || id.startsWith("magma_cube");
    }

    /** Get the "size" of this slime mob from its card ID. Large=4, Medium=2, Small=1 */
    private int getSlimeSize() {
        String id = sourceCard.getMobId();
        if (id.contains("large")) return 4;
        if (id.contains("medium")) return 2;
        return 1;
    }

    public double getCurrentHP(ServerLevel world) {
        Entity e = getEntity(world);
        return (e instanceof LivingEntity living) ? living.getHealth() : 0;
    }

    public void setLaneBounds(double minX, double maxX, double minZ, double maxZ) {
        this.laneMinX = minX; this.laneMaxX = maxX;
        this.laneMinZ = minZ; this.laneMaxZ = maxZ;
        this.laneBoundsSet = true;
    }

    // ================================================================
    // ATTACK TYPE — skeleton without bow is melee
    // ================================================================

    private AttackType getAttackType() {
        String id = sourceCard.getMobId();
        // Skeleton/stray/bogged: only ranged if they have a bow (checked at attack time)
        // pillager: only ranged if they have a crossbow
        return switch (id) {
            case "skeleton", "stray", "bogged", "pillager" -> AttackType.RANGED;
            case "blaze", "ghast", "witch", "snow_golem" -> AttackType.RANGED;
            case "guardian", "elder_guardian" -> AttackType.RANGED;
            case "llama", "trader_llama", "breeze" -> AttackType.RANGED;
            case "wither" -> AttackType.RANGED;
            case "warden" -> AttackType.RANGED;
            case "creeper" -> AttackType.CREEPER_EXPLOSION;
            case "enderman" -> AttackType.TELEPORT_MELEE;
            case "evoker" -> AttackType.SUMMONER;
            default -> AttackType.MELEE;
        };
    }

    /** For skeleton/pillager: if no ranged weapon, fall back to melee */
    private boolean hasRangedWeapon(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        String id = sourceCard.getMobId();
        return switch (id) {
            case "skeleton", "stray", "bogged" -> {
                ItemStack mainHand = living.getItemBySlot(EquipmentSlot.MAINHAND);
                yield mainHand.is(Items.BOW);
            }
            case "pillager" -> {
                ItemStack mainHand = living.getItemBySlot(EquipmentSlot.MAINHAND);
                yield mainHand.is(Items.CROSSBOW);
            }
            default -> true; // Other ranged mobs always use ranged
        };
    }

    /** Effective attack type considering equipment */
    private AttackType getEffectiveAttackType(Entity entity) {
        AttackType base = getAttackType();
        if (base == AttackType.RANGED && !hasRangedWeapon(entity)) {
            return AttackType.MELEE;
        }
        return base;
    }

    private double getRangedAttackRange() {
        String id = sourceCard.getMobId();
        return switch (id) {
            case "ghast" -> 16.0;
            case "warden" -> 15.0;
            case "blaze", "wither" -> 12.0;
            case "evoker" -> 10.0;
            case "skeleton", "stray", "bogged", "pillager" -> 10.0;
            case "guardian", "elder_guardian" -> 10.0;
            case "witch", "snow_golem", "llama", "trader_llama", "breeze" -> 8.0;
            default -> 8.0;
        };
    }

    private boolean isFlying() {
        String id = sourceCard.getMobId();
        return switch (id) {
            case "ghast", "phantom", "blaze", "bee", "vex", "allay", "bat", "parrot", "breeze", "wither" -> true;
            default -> false;
        };
    }

    private boolean isUndead() {
        MobCardDefinition def = sourceCard.getDefinition();
        return def != null && def.category() == MobCardDefinition.MobCategory.UNDEAD;
    }

    private boolean isAquaticSuffocates() {
        String id = sourceCard.getMobId();
        return switch (id) {
            case "cod", "salmon", "pufferfish", "tropical_fish", "squid", "glow_squid",
                 "dolphin", "guardian", "elder_guardian", "axolotl" -> true;
            default -> false;
        };
    }

    // ================================================================
    // SPAWN
    // ================================================================

    public void spawn(ServerLevel world, BlockPos pos) {
        MobCardDefinition def = sourceCard.getDefinition();
        if (def == null) return;

        Entity entity = def.entityType().create(world);
        if (entity == null) return;

        double spawnY = pos.getY() + (isFlying() ? 1.0 : 0.0);
        entity.moveTo(pos.getX() + 0.5, spawnY, pos.getZ() + 0.5, 0, 0);

        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            var attr = mob.getAttribute(Attributes.MAX_HEALTH);
            if (attr != null) attr.setBaseValue(maxHP);
            mob.setHealth((float) maxHP);
            if ("baby_zombie".equals(sourceCard.getMobId()) && entity instanceof net.minecraft.world.entity.monster.Zombie z) {
                z.setBaby(true);
            }
            if (entity instanceof net.minecraft.world.entity.monster.Slime slime) {
                String id = sourceCard.getMobId();
                int size = id.contains("large") ? 4 : id.contains("medium") ? 2 : 1;
                slime.setSize(size, false);
                var hpAttr = slime.getAttribute(Attributes.MAX_HEALTH);
                if (hpAttr != null) hpAttr.setBaseValue(maxHP);
                slime.setHealth((float) maxHP);
            }
        }

        entity.addTag("arenaclash_mob");
        entity.addTag("team_" + team.name());
        entity.addTag("lane_" + lane.name());
        entity.setCustomNameVisible(false);
        // NOTE: We intentionally do NOT set entity.setInvulnerable(true) here.
        // Vanilla invulnerability causes arrow deflection in PersistentProjectileEntity.onEntityHit
        // (both isInvulnerableTo check AND damage() returning false trigger velocity reversal).
        // Instead, ArenaMobDamageMixin blocks ALL vanilla damage at the LivingEntity.damage() level,
        // while ProjectileCollisionMixin prevents projectile consumption.
        // Custom damage is applied via ArenaMob.takeDamage() → setHealth() which bypasses damage().

        world.addFreshEntity(entity);
        this.entityId = entity.getUUID();
        this.state = MobState.IDLE;
        this.lastPosition = entity.position();

        spawnHpBar(world, entity);
    }

    public void startAdvancing(List<BlockPos> waypoints) {
        this.waypoints = new ArrayList<>(waypoints);
        this.currentWaypointIndex = 0;
        this.state = MobState.ADVANCING;
    }

    public void startRetreating() {
        if (isDead()) return;
        this.state = MobState.RETREATING;
        this.targetEntityId = null;
        this.targetStructure = null;
        this.structureApproachPos = null;
    }

    // ================================================================
    // HP BAR — position updated every single tick
    // ================================================================

    private void spawnHpBar(ServerLevel world, Entity entity) {
        ArmorStand marker = new ArmorStand(world,
                entity.getX(), entity.getY() + entity.getBbHeight() + 0.3, entity.getZ());
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomNameVisible(true);
        marker.setSilent(true);
        marker.setSmall(true);
        marker.setMarker(true);
        marker.addTag("arenaclash_mob_hp");
        world.addFreshEntity(marker);
        this.hpBarEntityId = marker.getUUID();
        updateHpBar(world, entity, true);
    }

    /** Move HP bar to entity position (every tick) and update text (every 5 ticks or forced) */
    private void updateHpBar(ServerLevel world, Entity mobEntity, boolean forceText) {
        if (hpBarEntityId == null) return;
        Entity marker = world.getEntity(hpBarEntityId);
        if (marker == null) return;

        // Teleport marker to exact mob position — every tick for smooth tracking
        double hpY = mobEntity.getY() + mobEntity.getBbHeight() + 0.3;
        marker.setPosition(mobEntity.getX(), hpY, mobEntity.getZ());

        if (!forceText) return;
        if (!(mobEntity instanceof LivingEntity living)) return;
        double hp = living.getHealth();
        double pct = hp / maxHP;

        String hpColor = pct > 0.5 ? "\u00A7a" : pct > 0.25 ? "\u00A7e" : "\u00A7c";
        int filled = Math.max(0, (int) Math.ceil(pct * 20));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            bar.append(i < filled ? hpColor + "|" : "\u00A78|");
        }
        String teamColor = (team == TeamSide.PLAYER1) ? "\u00A79" : "\u00A7c";
        net.minecraft.network.chat.MutableComponent mobName = sourceCard.getDefinition() != null
                ? Component.translatable(sourceCard.getDefinition().translationKey())
                : Component.literal("Mob");
        String levelStr = MobCardRegistry.isUpgradeLocked(sourceCard.getMobId())
                ? "" : " \u00A76Lv." + sourceCard.getLevel();
        marker.setCustomName(Component.literal(teamColor).append(mobName).append(Component.literal(levelStr + " " + bar + " " + hpColor + (int) hp)));
    }

    private void removeHpBar(ServerLevel world) {
        if (hpBarEntityId != null) {
            Entity marker = world.getEntity(hpBarEntityId);
            if (marker != null) marker.discard();
            hpBarEntityId = null;
        }
    }

    // ================================================================
    // TICK
    // ================================================================

    public void tick(ServerLevel world, List<ArenaMob> allMobs, List<ArenaStructure> structures, ProjectileTracker projectileTracker) {
        if (isDead()) return;
        Entity entity = getEntity(world);
        if (entity == null || !entity.isAlive()) { markDead(world); return; }
        if (entity instanceof LivingEntity l && l.getHealth() <= 0) { markDead(world); return; }

        if (attackCooldownRemaining > 0) attackCooldownRemaining--;

        tickEnvironmental(world, entity);

        if (isFlying() && entity.onGround()) {
            entity.teleportTo(entity.getX(), entity.getY() + 1.0, entity.getZ());
        }

        switch (state) {
            case ADVANCING -> tickAdvancing(world, entity, allMobs, structures, projectileTracker);
            case FIGHTING -> tickFighting(world, entity, allMobs, structures, projectileTracker);
            case RETREATING -> tickRetreating(world, entity, allMobs);
            default -> {}
        }

        if (laneBoundsSet) enforceLaneBounds(entity);
        pushAwayFromAllies(world, entity, allMobs);

        if (state == MobState.ADVANCING && lastPosition != null) {
            if (entity.position().distanceToSqr(lastPosition) < 0.01) {
                stuckTicks++;
                if (stuckTicks > STUCK_THRESHOLD && waypoints != null && currentWaypointIndex < waypoints.size() - 1) {
                    currentWaypointIndex++;
                    stuckTicks = 0;
                }
            } else { stuckTicks = 0; }
        }
        lastPosition = entity.position();

        // HP bar: position every tick, text every 5 ticks
        boolean updateText = (world.getServer().getTicks() % 5 == 0);
        updateHpBar(world, entity, updateText);

        // Clean up dead vexes
        evokerVexIds.removeIf(vexId -> {
            Entity v = world.getEntity(vexId);
            return v == null || !v.isAlive();
        });
    }

    // ================================================================
    // ENVIRONMENTAL DAMAGE
    // ================================================================

    private void tickEnvironmental(ServerLevel world, Entity entity) {
        if (isUndead() && sunBurnCooldown <= 0) {
            if (world.isDay() && !world.isRaining()) {
                BlockPos pos = entity.blockPosition();
                if (world.canSeeSky(pos)) {
                    if (entity instanceof LivingEntity living) {
                        ItemStack helmet = living.getItemBySlot(EquipmentSlot.HEAD);
                        if (helmet.isEmpty()) {
                            entity.igniteForSeconds(2);
                            takeDamage(1.0, world);
                            sunBurnCooldown = 20;
                        }
                    }
                }
            }
        }
        if (sunBurnCooldown > 0) sunBurnCooldown--;

        if (isAquaticSuffocates()) {
            suffocationTicks++;
            if (suffocationTicks >= 20) {
                suffocationTicks = 0;
                if (!entity.isInWater()) {
                    takeDamage(1.0, world);
                    world.sendParticles(ParticleTypes.BUBBLE,
                            entity.getX(), entity.getY() + 0.5, entity.getZ(),
                            5, 0.3, 0.3, 0.3, 0.02);
                }
            }
        }
    }

    // ================================================================
    // MOB COLLISION
    // ================================================================

    private void pushAwayFromAllies(ServerLevel world, Entity self, List<ArenaMob> allMobs) {
        for (ArenaMob other : allMobs) {
            if (other == this || other.isDead() || other.getTeam() != team) continue;
            Entity otherEntity = other.getEntity(world);
            if (otherEntity == null) continue;
            double dx = self.getX() - otherEntity.getX();
            double dz = self.getZ() - otherEntity.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < 0.64 && distSq > 0.001) {
                double dist = Math.sqrt(distSq);
                double nx = dx / dist, nz = dz / dist;
                double newX = self.getX() + nx * 0.03;
                double newZ = self.getZ() + nz * 0.03;
                if (laneBoundsSet) {
                    newX = Mth.clamp(newX, laneMinX + 0.3, laneMaxX + 0.7);
                    newZ = Mth.clamp(newZ, laneMinZ + 0.3, laneMaxZ + 0.7);
                }
                self.teleportTo(newX, self.getY(), newZ);
            }
        }
    }

    // ================================================================
    // ADVANCING
    // ================================================================

    private void tickAdvancing(ServerLevel world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures, ProjectileTracker projectileTracker) {
        GameConfig cfg = GameConfig.get();
        AttackType atkType = getEffectiveAttackType(entity);
        boolean canFight = attackDamage > 0 || atkType == AttackType.CREEPER_EXPLOSION;
        double effectiveAggroRange = (atkType == AttackType.RANGED) ?
                Math.max(cfg.mobAggroRange, getRangedAttackRange()) : cfg.mobAggroRange;

        if (canFight) {
            ArenaMob enemy = findNearestEnemy(world, entity, allMobs, effectiveAggroRange);
            if (enemy != null) {
                targetEntityId = enemy.getEntityId();
                targetStructure = null;
                structureApproachPos = null;
                state = MobState.FIGHTING;
                return;
            }
            double structureAggroRange = Math.max(cfg.mobAggroRange, 20.0);
            ArenaStructure struct = findNearestEnemyStructure(entity, structures, structureAggroRange);
            if (struct != null) {
                double dist = entity.blockPosition().distSqr(struct.getPosition());
                if (dist <= 10.0 * 10.0) {
                    assignStructureTarget(struct, allMobs);
                    state = MobState.FIGHTING;
                    return;
                }
            }
        } else {
            ArenaStructure blockingStruct = findNearestEnemyStructure(entity, structures, 5.0);
            if (blockingStruct != null) {
                Vec3 sPos = Vec3.atCenterOf(blockingStruct.getPosition());
                if (hDist(entity.position(), sPos) <= 3.5) {
                    double dx2 = sPos.x - entity.getX(), dz2 = sPos.z - entity.getZ();
                    double d2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);
                    if (d2 > 0.01) faceDir(entity, dx2 / d2, dz2 / d2);
                    return;
                }
            }
        }
        moveTowardWaypoint(entity);

        if (waypoints != null && currentWaypointIndex >= waypoints.size()) {
            ArenaStructure nearestStruct = findNearestEnemyStructure(entity, structures, 100.0);
            if (nearestStruct != null && canFight) {
                assignStructureTarget(nearestStruct, allMobs);
                state = MobState.FIGHTING;
            } else if (nearestStruct != null) {
                Vec3 sPos = Vec3.atCenterOf(nearestStruct.getPosition());
                if (hDist(entity.position(), sPos) > 3.5) moveToward(entity, sPos);
            }
        }
    }

    // ================================================================
    // FIGHTING
    // ================================================================

    private void tickFighting(ServerLevel world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures, ProjectileTracker projectileTracker) {
        AttackType atkType = getEffectiveAttackType(entity);
        double meleeRange = 2.5;
        double rangedRange = getRangedAttackRange();
        GameConfig cfg = GameConfig.get();

        if (targetEntityId != null) {
            ArenaMob target = findMobByEntityId(allMobs, targetEntityId);
            if (target == null || target.isDead()) { targetEntityId = null; skipPassedWaypoints(entity); state = MobState.ADVANCING; return; }
            Entity tEnt = target.getEntity(world);
            if (tEnt == null) { targetEntityId = null; skipPassedWaypoints(entity); state = MobState.ADVANCING; return; }

            double dist = Math.sqrt(entity.distanceToSqr(tEnt));
            faceEntity(entity, tEnt);

            switch (atkType) {
                case RANGED -> {
                    if (dist > rangedRange) moveToward(entity, tEnt.position());
                    else if (attackCooldownRemaining <= 0) {
                        performRangedAttack(entity, target, world, projectileTracker);
                        attackCooldownRemaining = getRandomizedCooldown();
                    }
                }
                case CREEPER_EXPLOSION -> {
                    if (dist > 3.0) moveToward(entity, tEnt.position());
                    else performCreeperExplosion(entity, world, allMobs, structures);
                }
                case TELEPORT_MELEE -> {
                    if (dist > meleeRange && dist < 16.0 && attackCooldownRemaining <= 0) {
                        performTeleportAttack(entity, target, world, allMobs);
                        attackCooldownRemaining = getRandomizedCooldown();
                    } else if (dist > meleeRange) moveToward(entity, tEnt.position());
                    else if (attackCooldownRemaining <= 0) {
                        performAttack(entity, target, world, allMobs);
                        attackCooldownRemaining = getRandomizedCooldown();
                    }
                }
                case SUMMONER -> {
                    if (dist > rangedRange) moveToward(entity, tEnt.position());
                    else if (attackCooldownRemaining <= 0) {
                        performSummonerAttack(entity, target, world, allMobs);
                        attackCooldownRemaining = getRandomizedCooldown();
                    }
                }
                default -> { // MELEE
                    if (dist > meleeRange) moveToward(entity, tEnt.position());
                    else if (attackCooldownRemaining <= 0) {
                        performAttack(entity, target, world, allMobs);
                        attackCooldownRemaining = getRandomizedCooldown();
                    }
                }
            }
        } else if (targetStructure != null) {
            if (targetStructure.isDestroyed()) { targetStructure = null; structureApproachPos = null; skipPassedWaypoints(entity); state = MobState.ADVANCING; return; }
            Vec3 sPos = getStructureMoveTarget();
            double dist = hDist(entity.position(), sPos);

            if (atkType == AttackType.CREEPER_EXPLOSION) {
                if (dist > 3.0) moveToward(entity, sPos);
                else performCreeperExplosion(entity, world, allMobs, structures);
            } else if (atkType == AttackType.RANGED) {
                if (dist > Math.min(rangedRange, 8.0)) moveToward(entity, sPos);
                else if (attackCooldownRemaining <= 0) {
                    performStructureAttack(entity, targetStructure, world, projectileTracker);
                    attackCooldownRemaining = getRandomizedCooldown();
                }
            } else {
                if (dist > meleeRange + 2.5) moveToward(entity, sPos);
                else if (attackCooldownRemaining <= 0) {
                    performStructureAttack(entity, targetStructure, world, projectileTracker);
                    attackCooldownRemaining = getRandomizedCooldown();
                }
            }
        } else {
            double searchRange = (atkType == AttackType.RANGED) ?
                    Math.max(cfg.mobAggroRange, rangedRange) : cfg.mobAggroRange;
            ArenaMob enemy = findNearestEnemy(world, entity, allMobs, searchRange);
            if (enemy != null) { targetEntityId = enemy.getEntityId(); return; }
            ArenaStructure struct = findNearestEnemyStructure(entity, structures, 100.0);
            if (struct != null) { assignStructureTarget(struct, allMobs); return; }
            skipPassedWaypoints(entity);
            state = MobState.ADVANCING;
        }
    }

    // ================================================================
    // RETREATING
    // ================================================================

    private void tickRetreating(ServerLevel world, Entity entity, List<ArenaMob> allMobs) {
        if (entity.blockPosition().distSqr(startSlotPos) <= 4.0) { state = MobState.IDLE; return; }
        if (attackDamage > 0) {
            ArenaMob nearby = findNearestEnemy(world, entity, allMobs, GameConfig.get().mobAggroRange * 0.5);
            if (nearby != null) {
                Entity ne = nearby.getEntity(world);
                if (ne != null && entity.distanceToSqr(ne) <= 6.25 && attackCooldownRemaining <= 0) {
                    performAttack(entity, nearby, world, allMobs);
                    attackCooldownRemaining = getRandomizedCooldown();
                }
            }
        }
        moveToward(entity, Vec3.atCenterOf(startSlotPos));
    }

    // ================================================================
    // COMBAT - MELEE (with splash)
    // ================================================================

    private void performAttack(Entity attacker, ArenaMob defender, ServerLevel world, List<ArenaMob> allMobs) {
        if (defender.isDead() || attackDamage <= 0) return;
        Entity dEnt = defender.getEntity(world);
        if (dEnt == null) return;

        // Trigger attack animation
        triggerAttackAnimation(attacker, world);

        float dmg = (float) attackDamage;

        // Cave spider poison
        if ("cave_spider".equals(sourceCard.getMobId()) && dEnt instanceof LivingEntity target) {
            target.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
        }
        // Pufferfish poison
        if ("pufferfish".equals(sourceCard.getMobId()) && dEnt instanceof LivingEntity target) {
            target.addEffect(new MobEffectInstance(MobEffects.POISON, 140, 1));
        }

        defender.takeDamage(dmg, world);

        // Splash damage to nearby enemies (50% dmg within 1.5 blocks)
        if (allMobs != null) {
            for (ArenaMob nearby : allMobs) {
                if (nearby == defender || nearby.getTeam() == team || nearby.isDead()) continue;
                Entity nEnt = nearby.getEntity(world);
                if (nEnt == null) continue;
                if (nEnt.distanceToSqr(dEnt) < 2.25) {
                    float splashDmg = dmg * 0.5f;
                    nearby.takeDamage(splashDmg, world);
                    spawnDamageNumber(world, nEnt.position().add(0, nEnt.getBbHeight() + 0.3, 0), splashDmg);
                    world.sendParticles(ParticleTypes.SWEEP_ATTACK,
                            nEnt.getX(), nEnt.getY(0.5), nEnt.getZ(), 1, 0.1, 0.1, 0.1, 0.0);
                }
            }
        }

        // Knockback
        Vec3 kbDir = dEnt.position().subtract(attacker.position()).normalize();
        double kb = GameConfig.get().knockbackStrength;
        double newX = dEnt.getX() + kbDir.x * kb;
        double newZ = dEnt.getZ() + kbDir.z * kb;
        if (defender.laneBoundsSet) {
            newX = Mth.clamp(newX, defender.laneMinX + 0.3, defender.laneMaxX + 0.7);
            newZ = Mth.clamp(newZ, defender.laneMinZ + 0.3, defender.laneMaxZ + 0.7);
        }
        dEnt.teleportTo(newX, dEnt.getY(), newZ);

        if (dEnt instanceof LivingEntity ld) { ld.hurtTime = 10; ld.hurtDuration = 10; }

        int pCount = Math.min((int)(dmg / 2) + 1, 8);
        world.sendParticles(ParticleTypes.DAMAGE_INDICATOR, dEnt.getX(), dEnt.getY(0.5), dEnt.getZ(), pCount, 0.3, 0.2, 0.3, 0.1);
        if (dmg >= 8) world.sendParticles(ParticleTypes.CRIT, dEnt.getX(), dEnt.getY(0.5), dEnt.getZ(), 5, 0.4, 0.3, 0.4, 0.2);
        if (dmg >= 15) world.sendParticles(ParticleTypes.ENCHANTED_HIT, dEnt.getX(), dEnt.getY(0.5), dEnt.getZ(), 8, 0.5, 0.4, 0.5, 0.3);

        spawnDamageNumber(world, dEnt.position().add(0, dEnt.getBbHeight() + 0.5, 0), dmg);
        playAttackSound(world, attacker.position());
    }

    // ================================================================
    // COMBAT - RANGED (proper projectile entities)
    // ================================================================

    private void performRangedAttack(Entity attacker, ArenaMob defender, ServerLevel world, ProjectileTracker tracker) {
        if (defender.isDead()) return;
        Entity targetEntity = defender.getEntity(world);
        if (targetEntity == null) return;

        triggerAttackAnimation(attacker, world);

        String mobId = sourceCard.getMobId();
        Vec3 shootFrom = attacker.position().add(0, attacker.getBbHeight() * 0.7, 0);
        Vec3 targetPos = targetEntity.position().add(0, targetEntity.getBbHeight() * 0.5, 0);
        Vec3 direction = targetPos.subtract(shootFrom).normalize();

        float dmg = (float) attackDamage;

        switch (mobId) {
            case "skeleton", "stray", "bogged" -> {
                Arrow arrow = new Arrow(world, shootFrom.x, shootFrom.y, shootFrom.z,
                        new ItemStack(Items.ARROW), null);
                arrow.shoot(direction.x, direction.y + 0.05, direction.z, 1.6f, 2.0f);
                arrow.setBaseDamage(0);
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                arrow.addTag("arenaclash_mob_projectile");
                world.addFreshEntity(arrow);
                tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                        ProjectileTracker.HitEffect.NONE, 2.0, 0);
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_SKELETON_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
            case "pillager" -> {
                Arrow arrow = new Arrow(world, shootFrom.x, shootFrom.y, shootFrom.z,
                        new ItemStack(Items.ARROW), null);
                arrow.shoot(direction.x, direction.y + 0.03, direction.z, 1.8f, 1.5f);
                arrow.setBaseDamage(0);
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                arrow.addTag("arenaclash_mob_projectile");
                world.addFreshEntity(arrow);
                tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                        ProjectileTracker.HitEffect.NONE, 2.0, 0);
                world.playSound(null, attacker.blockPosition(), SoundEvents.ITEM_CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
            case "blaze" -> {
                try {
                    SmallFireball fb = new SmallFireball(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.scale(0.8));
                    fb.setPosition(shootFrom);
                    fb.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(fb);
                    tracker.trackMobProjectile(fb.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.BLAZE_FIRE, 2.5, 0);
                } catch (Exception ignored) {
                    Arrow arrow = spawnVisualArrow(world, shootFrom, direction);
                    tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.BLAZE_FIRE, 2.0, 0);
                }
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
            case "ghast" -> {
                // Ghast fireball: tracked with AOE explosion on impact
                try {
                    LargeFireball fb = new LargeFireball(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.scale(0.5), 0);
                    fb.setPosition(shootFrom);
                    fb.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(fb);
                    tracker.trackMobProjectile(fb.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.GHAST_AOE, 3.0, 4.5);
                } catch (Exception ignored) {
                    Arrow arrow = spawnVisualArrow(world, shootFrom, direction);
                    tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.GHAST_AOE, 2.5, 4.5);
                }
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_GHAST_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
            case "witch" -> {
                try {
                    Snowball sb = new Snowball(world, shootFrom.x, shootFrom.y, shootFrom.z);
                    sb.shoot(direction.x, direction.y + 0.2, direction.z, 0.75f, 4.0f);
                    sb.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(sb);
                    tracker.trackMobProjectile(sb.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.NONE, 2.0, 0);
                } catch (Exception ignored) {
                    Arrow arrow = spawnVisualArrow(world, shootFrom, direction);
                    tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.NONE, 2.0, 0);
                }
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_WITCH_THROW, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
            case "snow_golem" -> {
                try {
                    Snowball sb = new Snowball(world, shootFrom.x, shootFrom.y, shootFrom.z);
                    sb.shoot(direction.x, direction.y + 0.1, direction.z, 1.2f, 3.0f);
                    sb.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(sb);
                    tracker.trackMobProjectile(sb.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.NONE, 2.0, 0);
                } catch (Exception ignored) {
                    Arrow arrow = spawnVisualArrow(world, shootFrom, direction);
                    tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.NONE, 2.0, 0);
                }
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_SNOW_GOLEM_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
            case "guardian", "elder_guardian" -> {
                // Guardian laser beam — instant damage (particle effect, no projectile entity)
                int steps = 10;
                for (int i = 0; i < steps; i++) {
                    double t = (double) i / steps;
                    double px = shootFrom.x + (targetPos.x - shootFrom.x) * t;
                    double py = shootFrom.y + (targetPos.y - shootFrom.y) * t;
                    double pz = shootFrom.z + (targetPos.z - shootFrom.z) * t;
                    float r = "elder_guardian".equals(mobId) ? 0.5f : 0.2f;
                    float g = "elder_guardian".equals(mobId) ? 0.0f : 0.8f;
                    float b = "elder_guardian".equals(mobId) ? 0.5f : 1.0f;
                    world.sendParticles(new DustParticleOptions(
                            new org.joml.Vector3f(r, g, b), 1.0f), px, py, pz, 1, 0, 0, 0, 0);
                }
                var snd = "elder_guardian".equals(mobId) ? SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE : SoundEvents.ENTITY_GUARDIAN_ATTACK;
                world.playSound(null, attacker.blockPosition(), snd, SoundSource.HOSTILE, 1.0f, 1.0f);
                // Instant damage — no projectile entity to track
                defender.takeDamage(dmg, world);
                spawnDamageNumber(world, targetEntity.position().add(0, targetEntity.getBbHeight() + 0.3, 0), dmg);
                if (targetEntity instanceof LivingEntity ld) { ld.hurtTime = 10; ld.hurtDuration = 10; }
            }
            case "llama", "trader_llama" -> {
                try {
                    Snowball spit = new Snowball(world, shootFrom.x, shootFrom.y, shootFrom.z);
                    spit.shoot(direction.x, direction.y + 0.1, direction.z, 1.0f, 5.0f);
                    spit.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(spit);
                    tracker.trackMobProjectile(spit.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.NONE, 2.0, 0);
                } catch (Exception ignored) {
                    Arrow arrow = spawnVisualArrow(world, shootFrom, direction);
                    tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.NONE, 2.0, 0);
                }
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_LLAMA_SPIT, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
            case "breeze" -> {
                // Wind charge — instant damage (particle effect, no projectile entity)
                for (int i = 0; i < 6; i++) {
                    double t = (double) i / 6;
                    double px = shootFrom.x + direction.x * t * 8;
                    double py = shootFrom.y + direction.y * t * 8;
                    double pz = shootFrom.z + direction.z * t * 8;
                    world.sendParticles(ParticleTypes.CLOUD, px, py, pz, 3, 0.1, 0.1, 0.1, 0.05);
                    world.sendParticles(ParticleTypes.POOF, px, py, pz, 1, 0.1, 0.1, 0.1, 0.02);
                }
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_BREEZE_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
                defender.takeDamage(dmg, world);
                spawnDamageNumber(world, targetEntity.position().add(0, targetEntity.getBbHeight() + 0.3, 0), dmg);
                if (targetEntity instanceof LivingEntity ld) { ld.hurtTime = 10; ld.hurtDuration = 10; }
            }
            case "warden" -> {
                // Sonic boom — instant damage (particle beam, no projectile entity)
                int steps = 12;
                for (int i = 0; i < steps; i++) {
                    double t = (double) i / steps;
                    double px = shootFrom.x + (targetPos.x - shootFrom.x) * t;
                    double py = shootFrom.y + (targetPos.y - shootFrom.y) * t;
                    double pz = shootFrom.z + (targetPos.z - shootFrom.z) * t;
                    world.sendParticles(ParticleTypes.SONIC_BOOM, px, py, pz, 1, 0, 0, 0, 0);
                }
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.5f, 1.0f);
                defender.takeDamage(dmg, world);
                spawnDamageNumber(world, targetEntity.position().add(0, targetEntity.getBbHeight() + 0.3, 0), dmg);
                if (targetEntity instanceof LivingEntity ld) { ld.hurtTime = 10; ld.hurtDuration = 10; }
            }
            case "wither" -> {
                try {
                    WitherSkull skull = new WitherSkull(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.scale(0.6));
                    skull.setPosition(shootFrom);
                    skull.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(skull);
                    tracker.trackMobProjectile(skull.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.WITHER_EFFECT, 2.5, 0);
                } catch (Exception ignored) {
                    Arrow arrow = spawnVisualArrow(world, shootFrom, direction);
                    tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                            ProjectileTracker.HitEffect.WITHER_EFFECT, 2.0, 0);
                }
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_WITHER_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
            default -> {
                // Fallback: arrow
                Arrow arrow = new Arrow(world, shootFrom.x, shootFrom.y, shootFrom.z,
                        new ItemStack(Items.ARROW), null);
                arrow.shoot(direction.x, direction.y + 0.05, direction.z, 1.6f, 2.0f);
                arrow.setBaseDamage(0);
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                arrow.addTag("arenaclash_mob_projectile");
                world.addFreshEntity(arrow);
                tracker.trackMobProjectile(arrow.getUUID(), team, defender, dmg,
                        ProjectileTracker.HitEffect.NONE, 2.0, 0);
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_ARROW_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
        }
    }

    /** Spawn a short-lived visual arrow as fallback for ranged attacks. Returns the entity for tracking. */
    private Arrow spawnVisualArrow(ServerLevel world, Vec3 from, Vec3 dir) {
        Arrow arrow = new Arrow(world, from.x, from.y, from.z,
                new ItemStack(Items.ARROW), null);
        arrow.shoot(dir.x, dir.y + 0.05, dir.z, 1.6f, 2.0f);
        arrow.setBaseDamage(0);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.addTag("arenaclash_mob_projectile");
        world.addFreshEntity(arrow);
        return arrow;
    }

    // ================================================================
    // COMBAT - CREEPER EXPLOSION
    // ================================================================

    private void performCreeperExplosion(Entity creeper, ServerLevel world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        Vec3 center = creeper.position();
        double explosionRadius = 4.0;
        float explosionDamage = (float) Math.max(10.0, this.attackDamage); // Uses card attack, scales with level

        for (ArenaMob mob : allMobs) {
            if (mob == this || mob.getTeam() == team || mob.isDead()) continue;
            Entity e = mob.getEntity(world);
            if (e == null) continue;
            double dist = hDist(center, e.position());
            if (dist <= explosionRadius) {
                float dmg = (float) (explosionDamage * (1.0 - dist / explosionRadius));
                mob.takeDamage(dmg, world);
                spawnDamageNumber(world, e.position().add(0, e.getBbHeight() + 0.3, 0), dmg);
                Vec3 kb = e.position().subtract(center).normalize().scale(1.5);
                double nx = e.getX() + kb.x, nz = e.getZ() + kb.z;
                if (mob.laneBoundsSet) {
                    nx = Mth.clamp(nx, mob.laneMinX + 0.3, mob.laneMaxX + 0.7);
                    nz = Mth.clamp(nz, mob.laneMinZ + 0.3, mob.laneMaxZ + 0.7);
                }
                e.teleportTo(nx, e.getY(), nz);
            }
        }
        for (ArenaStructure struct : structures) {
            if (struct.getOwner() == team || struct.isDestroyed()) continue;
            Vec3 sPos = Vec3.atCenterOf(struct.getPosition());
            if (hDist(center, sPos) <= explosionRadius + 2.0) struct.damage(explosionDamage * 2.0f, world);
        }

        world.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 1, center.z, 1, 0, 0, 0, 0);
        world.sendParticles(ParticleTypes.CLOUD, center.x, center.y + 1, center.z, 20, 1.5, 1, 1.5, 0.1);
        world.playSound(null, center.x, center.y, center.z,
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0f, 1.0f);
        markDead(world);
    }

    // ================================================================
    // COMBAT - TELEPORT (Enderman)
    // ================================================================

    private void performTeleportAttack(Entity attacker, ArenaMob defender, ServerLevel world, List<ArenaMob> allMobs) {
        Entity targetEntity = defender.getEntity(world);
        if (targetEntity == null) return;

        Vec3 targetFacing = Vec3.directionFromRotation(0, targetEntity.getYRot()).normalize();
        Vec3 behindPos = targetEntity.position().subtract(targetFacing.scale(1.5));

        double newX = behindPos.x, newZ = behindPos.z;
        if (laneBoundsSet) {
            newX = Mth.clamp(newX, laneMinX + 0.3, laneMaxX + 0.7);
            newZ = Mth.clamp(newZ, laneMinZ + 0.3, laneMaxZ + 0.7);
        }

        world.sendParticles(ParticleTypes.PORTAL, attacker.getX(), attacker.getY() + 1, attacker.getZ(), 15, 0.3, 0.5, 0.3, 0.3);
        attacker.teleportTo(newX, attacker.getY(), newZ);
        world.sendParticles(ParticleTypes.PORTAL, newX, attacker.getY() + 1, newZ, 15, 0.3, 0.5, 0.3, 0.3);
        world.playSound(null, newX, attacker.getY(), newZ, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 1.0f);

        performAttack(attacker, defender, world, allMobs);
    }

    // ================================================================
    // COMBAT - SUMMONER (Evoker) — real vexes + fang attack
    // ================================================================

    private void performSummonerAttack(Entity attacker, ArenaMob defender, ServerLevel world, List<ArenaMob> allMobs) {
        Entity targetEntity = defender.getEntity(world);
        if (targetEntity == null) return;

        triggerAttackAnimation(attacker, world);

        // Alternate between vex summoning and fang attack
        int liveVexes = (int) evokerVexIds.stream()
                .filter(id -> { Entity v = world.getEntity(id); return v != null && v.isAlive(); })
                .count();

        if (liveVexes < 3 && world.getRandom().nextFloat() < 0.35) {
            // Summon a real Vex entity
            Vex vex = EntityType.VEX.create(world);
            if (vex != null) {
                double vx = attacker.getX() + (world.getRandom().nextDouble() - 0.5) * 2;
                double vy = attacker.getY() + 1.0;
                double vz = attacker.getZ() + (world.getRandom().nextDouble() - 0.5) * 2;
                vex.moveTo(vx, vy, vz, 0, 0);
                vex.setNoAi(true);
                vex.setPersistenceRequired();
                vex.addTag("arenaclash_mob");
                vex.addTag("team_" + team.name());
                vex.addTag("lane_" + lane.name());
                vex.addTag("arenaclash_vex");
                var hpAttr = vex.getAttribute(Attributes.MAX_HEALTH);
                if (hpAttr != null) hpAttr.setBaseValue(14.0);
                vex.setHealth(14.0f);
                world.addFreshEntity(vex);
                evokerVexIds.add(vex.getUUID());

                // Create ArenaMob wrapper for the vex so it fights
                MobCard vexCard = new MobCard("vex");
                ArenaMob vexMob = new ArenaMob(ownerId, team, vexCard, lane, startSlotPos);
                vexMob.entityId = vex.getUUID();
                vexMob.state = MobState.ADVANCING;
                vexMob.waypoints = this.waypoints != null ? new ArrayList<>(this.waypoints) : new ArrayList<>();
                vexMob.currentWaypointIndex = this.currentWaypointIndex;
                vexMob.setSubMob(true);
                if (laneBoundsSet) vexMob.setLaneBounds(laneMinX, laneMaxX, laneMinZ, laneMaxZ);
                vexMob.spawnHpBar(world, vex);
                // DEFERRED: add to pendingChildMobs, NOT to allMobs during iteration
                pendingChildMobs.add(vexMob);

                world.sendParticles(ParticleTypes.ENCHANTED_HIT, vx, vy + 0.5, vz, 10, 0.3, 0.3, 0.3, 0.2);
                world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.0f, 1.0f);
            }
        } else {
            // Fang attack — spawn evoker fangs entity line toward target (AoE)
            Vec3 dir = targetEntity.position().subtract(attacker.position()).normalize();
            float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0 / Math.PI));

            // Range increased 1.5x: 8 fangs instead of 5
            for (int i = 1; i <= 8; i++) {
                double px = attacker.getX() + dir.x * i * 1.0;
                double pz = attacker.getZ() + dir.z * i * 1.0;
                double py = attacker.getY();
                try {
                    net.minecraft.world.entity.projectile.EvokerFangs fangs =
                            new net.minecraft.world.entity.projectile.EvokerFangs(world, px, py, pz, yaw, i * 2,
                                    attacker instanceof LivingEntity le ? le : null);
                    fangs.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(fangs);
                } catch (Exception ignored) {
                    world.sendParticles(ParticleTypes.CLOUD, px, py + 0.3, pz, 3, 0.1, 0.1, 0.1, 0.02);
                }
            }
            world.playSound(null, attacker.blockPosition(), SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.0f, 1.0f);

            // AoE damage: hit all enemies within the fang line corridor
            float dmg = (float) attackDamage;
            double fangReach = 8.0;
            for (ArenaMob nearby : allMobs) {
                if (nearby.getTeam() == team || nearby.isDead()) continue;
                Entity nEnt = nearby.getEntity(world);
                if (nEnt == null) continue;
                Vec3 toEnemy = nEnt.position().subtract(attacker.position());
                double projDist = toEnemy.x * dir.x + toEnemy.z * dir.z;
                if (projDist > 0 && projDist <= fangReach + 1.0) {
                    double perpDist = Math.abs(toEnemy.x * (-dir.z) + toEnemy.z * dir.x);
                    if (perpDist <= 1.5) {
                        nearby.takeDamage(dmg, world);
                        spawnDamageNumber(world, nEnt.position().add(0, nEnt.getBbHeight() + 0.5, 0), dmg);
                        if (nEnt instanceof LivingEntity ld) { ld.hurtTime = 10; ld.hurtDuration = 10; }
                    }
                }
            }
        }
    }

    // ================================================================
    // COMBAT - STRUCTURE ATTACK
    // ================================================================

    private void performStructureAttack(Entity attacker, ArenaStructure structure, ServerLevel world, ProjectileTracker tracker) {
        if (structure.isDestroyed() || attackDamage <= 0) return;
        triggerAttackAnimation(attacker, world);

        float dmg = (float) attackDamage;
        AttackType atkType = getEffectiveAttackType(attacker);

        if (atkType == AttackType.RANGED) {
            // Ranged mobs: fire a tracked projectile at the structure
            String mobId = sourceCard.getMobId();
            Vec3 shootFrom = attacker.position().add(0, attacker.getBbHeight() * 0.7, 0);
            Vec3 targetPos = Vec3.atCenterOf(structure.getPosition()).add(0, 2, 0);
            Vec3 direction = targetPos.subtract(shootFrom).normalize();

            ProjectileTracker.HitEffect effect = switch (mobId) {
                case "blaze" -> ProjectileTracker.HitEffect.BLAZE_FIRE;
                case "ghast" -> ProjectileTracker.HitEffect.GHAST_AOE;
                case "wither" -> ProjectileTracker.HitEffect.WITHER_EFFECT;
                default -> ProjectileTracker.HitEffect.NONE;
            };

            // Instant beam/effect attacks — no projectile to track
            if (isInstantRanged(mobId)) {
                structure.damage(dmg, world);
                Vec3 sp = Vec3.atCenterOf(structure.getPosition()).add(0, 1, 0);
                world.sendParticles(ParticleTypes.DAMAGE_INDICATOR, sp.x, sp.y, sp.z, 3, 0.5, 0.3, 0.5, 0.1);
                world.sendParticles(ParticleTypes.SMOKE, sp.x, sp.y, sp.z, 3, 0.5, 0.5, 0.5, 0.02);
                spawnDamageNumber(world, sp.add(0, 1.5, 0), dmg);
                playRangedSound(mobId, attacker, world);
                return;
            }

            // Fire a projectile entity and track it
            UUID projId = fireStructureProjectile(world, attacker, shootFrom, direction, mobId);
            if (projId != null) {
                double hitRadius = "ghast".equals(mobId) ? 3.0 : 2.5;
                tracker.trackStructureProjectile(projId, team, structure, dmg, effect, hitRadius);
            } else {
                // Fallback: instant damage if projectile spawn failed
                structure.damage(dmg, world);
                Vec3 sp = Vec3.atCenterOf(structure.getPosition()).add(0, 1, 0);
                spawnDamageNumber(world, sp.add(0, 1.5, 0), dmg);
            }
            playRangedSound(mobId, attacker, world);
        } else {
            // Melee attack on structure: instant damage
            structure.damage(dmg, world);

            Vec3 sp = Vec3.atCenterOf(structure.getPosition()).add(0, 1, 0);
            world.sendParticles(ParticleTypes.DAMAGE_INDICATOR, sp.x, sp.y, sp.z, 3, 0.5, 0.3, 0.5, 0.1);
            world.sendParticles(ParticleTypes.SMOKE, sp.x, sp.y, sp.z, 3, 0.5, 0.5, 0.5, 0.02);
            world.playSound(null, sp.x, sp.y, sp.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 1.0f, 0.7f);
            spawnDamageNumber(world, sp.add(0, 1.5, 0), dmg);
        }
    }

    /** Check if this ranged mob uses an instant beam/effect with no projectile entity */
    private boolean isInstantRanged(String mobId) {
        return switch (mobId) {
            case "guardian", "elder_guardian", "warden", "breeze" -> true;
            default -> false;
        };
    }

    /** Fire a projectile entity toward a structure and return its UUID */
    private UUID fireStructureProjectile(ServerLevel world, Entity attacker, Vec3 shootFrom, Vec3 direction, String mobId) {
        try {
            return switch (mobId) {
                case "skeleton", "stray", "bogged", "pillager" -> {
                    Arrow arrow = new Arrow(world, shootFrom.x, shootFrom.y, shootFrom.z,
                            new ItemStack(Items.ARROW), null);
                    arrow.shoot(direction.x, direction.y + 0.05, direction.z, 1.6f, 2.0f);
                    arrow.setBaseDamage(0);
                    arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                    arrow.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(arrow);
                    yield arrow.getUUID();
                }
                case "blaze" -> {
                    SmallFireball fb = new SmallFireball(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.scale(0.8));
                    fb.setPosition(shootFrom);
                    fb.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(fb);
                    yield fb.getUUID();
                }
                case "ghast" -> {
                    LargeFireball fb = new LargeFireball(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.scale(0.5), 0);
                    fb.setPosition(shootFrom);
                    fb.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(fb);
                    yield fb.getUUID();
                }
                case "wither" -> {
                    WitherSkull skull = new WitherSkull(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.scale(0.6));
                    skull.setPosition(shootFrom);
                    skull.addTag("arenaclash_mob_projectile");
                    world.addFreshEntity(skull);
                    yield skull.getUUID();
                }
                default -> {
                    Arrow arrow = spawnVisualArrow(world, shootFrom, direction);
                    yield arrow.getUUID();
                }
            };
        } catch (Exception e) {
            Arrow arrow = spawnVisualArrow(world, shootFrom, direction);
            return arrow.getUUID();
        }
    }

    /** Play the ranged attack sound for a mob type */
    private void playRangedSound(String mobId, Entity attacker, ServerLevel world) {
        var sound = switch (mobId) {
            case "skeleton", "stray", "bogged" -> SoundEvents.ENTITY_SKELETON_SHOOT;
            case "pillager" -> SoundEvents.ITEM_CROSSBOW_SHOOT;
            case "blaze" -> SoundEvents.ENTITY_BLAZE_SHOOT;
            case "ghast" -> SoundEvents.ENTITY_GHAST_SHOOT;
            case "witch" -> SoundEvents.ENTITY_WITCH_THROW;
            case "snow_golem" -> SoundEvents.ENTITY_SNOW_GOLEM_SHOOT;
            case "warden" -> SoundEvents.ENTITY_WARDEN_SONIC_BOOM;
            case "wither" -> SoundEvents.ENTITY_WITHER_SHOOT;
            case "breeze" -> SoundEvents.ENTITY_BREEZE_SHOOT;
            case "llama", "trader_llama" -> SoundEvents.ENTITY_LLAMA_SPIT;
            case "guardian" -> SoundEvents.ENTITY_GUARDIAN_ATTACK;
            case "elder_guardian" -> SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE;
            default -> SoundEvents.ENTITY_ARROW_SHOOT;
        };
        world.playSound(null, attacker.blockPosition(), sound, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    /**
     * Trigger the proper attack animation for this mob type.
     * - Humanoid mobs (zombies, skeletons, piglins): swing works
     * - Iron Golem: needs entity status 4 for its signature arm-sweep animation
     * - Ravager: entity status 4 for attack lunge
     * - Other mobs: entity status 4 is a generic attack trigger
     * We send both swing (for humanoids) and entity status (for non-humanoids).
     */
    private void triggerAttackAnimation(Entity attacker, ServerLevel world) {
        if (attacker instanceof LivingEntity la) {
            la.swing(la.getUsedItemHand());
        }
        // Entity status 4 = PLAY_ATTACK_SOUND / attack animation
        // This triggers native attack animations for mobs like Iron Golem,
        // Ravager, Hoglin, Zoglin, etc. that don't use swing for their animation
        if (attacker instanceof net.minecraft.world.entity.animal.IronGolem
                || attacker instanceof net.minecraft.world.entity.monster.Ravager
                || attacker instanceof net.minecraft.world.entity.monster.hoglin.Hoglin
                || attacker instanceof net.minecraft.world.entity.monster.Zoglin) {
            world.broadcastEntityEvent(attacker, (byte) 4);
        }
    }

    private void playAttackSound(ServerLevel world, Vec3 pos) {
        float pitch = 0.8f + world.getRandom().nextFloat() * 0.4f;
        MobCardDefinition def = sourceCard.getDefinition();
        if (def == null) { world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 0.8f, pitch); return; }
        var sound = switch (def.category()) {
            case UNDEAD -> SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR;
            case GOLEM -> SoundEvents.ENTITY_IRON_GOLEM_ATTACK;
            case BOSS -> SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT;
            case ARTHROPOD -> SoundEvents.ENTITY_SPIDER_AMBIENT;
            default -> SoundEvents.ENTITY_PLAYER_ATTACK_STRONG;
        };
        world.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.HOSTILE, 0.8f, pitch);
    }

    public void takeDamage(double amount, ServerLevel world) {
        Entity e = getEntity(world);
        if (e instanceof LivingEntity living) {
            float newHP = Math.max(0, living.getHealth() - (float) amount);
            if (newHP <= 0) {
                // Don't set health to exactly 0 — that triggers vanilla death processing (slime split etc.)
                // Instead keep at tiny positive value, then discard immediately in markDead
                living.setHealth(0.001f);
                markDead(world);
            } else {
                living.setHealth(newHP);
                living.hurtTime = 10;
                living.hurtDuration = 10;
            }
        }
    }

    private void markDead(ServerLevel world) {
        if (markedDead) return;
        markedDead = true;
        state = MobState.DEAD;
        Entity e = getEntity(world);

        // Handle slime splitting: spawn smaller slimes before discarding
        if (e != null && isSlimeType() && getSlimeSize() > 1) {
            spawnSlimeSplit(world, e);
        }

        if (e != null) {
            world.sendParticles(ParticleTypes.SOUL, e.getX(), e.getY() + 0.5, e.getZ(), 10, 0.3, 0.5, 0.3, 0.05);
            world.sendParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + 0.5, e.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
            world.sendParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 0.5, e.getZ(), 5, 0.2, 0.3, 0.2, 0.03);
            world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_GENERIC_DEATH, SoundSource.HOSTILE, 1.0f, 0.8f + world.getRandom().nextFloat() * 0.4f);
            e.discard();
        }
        removeHpBar(world);
        for (UUID vexId : evokerVexIds) {
            Entity vex = world.getEntity(vexId);
            if (vex != null) vex.discard();
        }
        evokerVexIds.clear();
    }

    /**
     * Spawn smaller slimes when a large/medium slime dies.
     * Children fight on the same team and can be recovered as cards if they retreat.
     */
    private void spawnSlimeSplit(ServerLevel world, Entity parent) {
        int currentSize = getSlimeSize();
        String baseType = sourceCard.getMobId().startsWith("magma_cube") ? "magma_cube" : "slime";
        String childCardId;
        if (currentSize >= 4) {
            childCardId = baseType + "_medium";
        } else if (currentSize >= 2) {
            childCardId = baseType; // small slime
        } else {
            return; // small slimes don't split
        }

        var childDef = com.arenaclash.card.MobCardRegistry.getById(childCardId);
        if (childDef == null) return;

        int childCount = 2 + world.getRandom().nextInt(2); // 2-3 children
        for (int i = 0; i < childCount; i++) {
            MobCard childCard = new MobCard(childCardId);
            childCard.setLevel(sourceCard.getLevel()); // Inherit parent's level
            ArenaMob childMob = new ArenaMob(ownerId, team, childCard, lane, startSlotPos);

            double ox = (world.getRandom().nextDouble() - 0.5) * 1.5;
            double oz = (world.getRandom().nextDouble() - 0.5) * 1.5;
            BlockPos childPos = new BlockPos(
                    (int)(parent.getX() + ox),
                    parent.blockPosition().getY(),
                    (int)(parent.getZ() + oz));

            childMob.spawn(world, childPos);
            if (laneBoundsSet) {
                childMob.setLaneBounds(laneMinX, laneMaxX, laneMinZ, laneMaxZ);
            }
            if (waypoints != null) {
                childMob.waypoints = new ArrayList<>(waypoints);
                childMob.currentWaypointIndex = currentWaypointIndex;
            }
            childMob.state = MobState.ADVANCING;
            childMob.setSubMob(false); // CAN be recovered as cards on retreat

            pendingChildMobs.add(childMob);
        }

        world.playSound(null, parent.getX(), parent.getY(), parent.getZ(),
                SoundEvents.ENTITY_SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    // ================================================================
    // MOVEMENT
    // ================================================================

    private void moveTowardWaypoint(Entity entity) {
        if (waypoints == null || currentWaypointIndex >= waypoints.size()) return;
        BlockPos target = waypoints.get(currentWaypointIndex);
        Vec3 tv = Vec3.atCenterOf(target);
        if (hDist(entity.position(), tv) <= 1.5) {
            currentWaypointIndex++;
            if (currentWaypointIndex >= waypoints.size()) return;
            target = waypoints.get(currentWaypointIndex);
            tv = Vec3.atCenterOf(target);
        }
        moveToward(entity, tv);
    }

    /**
     * After a fight, the mob may have moved past its current waypoint.
     * Skip any waypoints that are behind us (closer to our start than to the next waypoint).
     * This prevents the mob from walking backward after winning a fight.
     */
    private void skipPassedWaypoints(Entity entity) {
        if (waypoints == null || waypoints.size() <= 1) return;
        Vec3 pos = entity.position();
        // Skip waypoints we've already passed: if the NEXT waypoint is closer than
        // the current one, it means we're past the current waypoint
        while (currentWaypointIndex < waypoints.size() - 1) {
            Vec3 current = Vec3.atCenterOf(waypoints.get(currentWaypointIndex));
            Vec3 next = Vec3.atCenterOf(waypoints.get(currentWaypointIndex + 1));
            double distCurrent = hDist(pos, current);
            double distNext = hDist(pos, next);
            // If we're within reach of current, or the next is closer, skip current
            if (distCurrent <= 1.5 || distNext < distCurrent) {
                currentWaypointIndex++;
            } else {
                break;
            }
        }
    }

    private void moveToward(Entity entity, Vec3 target) {
        Vec3 cur = entity.position();
        double dx = target.x - cur.x, dz = target.z - cur.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.05) return;
        double speed = moveSpeed / 20.0;
        double step = Math.min(speed, dist);
        double nx = dx / dist, nz = dz / dist;
        double newX = cur.x + nx * step;
        double newZ = cur.z + nz * step;
        if (laneBoundsSet) {
            double effMinX = laneMinX + 0.3, effMaxX = laneMaxX + 0.7;
            if (targetStructure != null) {
                double sx = targetStructure.getPosition().getX() + 0.5;
                effMinX = Math.min(effMinX, sx - 3.0);
                effMaxX = Math.max(effMaxX, sx + 3.0);
            }
            effMinX = Math.min(effMinX, target.x - 1.0);
            effMaxX = Math.max(effMaxX, target.x + 1.0);
            newX = Mth.clamp(newX, effMinX, effMaxX);
            newZ = Mth.clamp(newZ, laneMinZ + 0.3, laneMaxZ + 0.7);
        }
        double newY = isFlying() ? Math.max(cur.y, startSlotPos.getY() + 1.0) : cur.y;
        entity.teleportTo(newX, newY, newZ);
        faceDir(entity, nx, nz);
    }

    private void faceDir(Entity entity, double nx, double nz) {
        float yaw = (float)(Math.atan2(-nx, nz) * (180.0 / Math.PI));
        entity.setYRot(yaw);
        if (entity instanceof LivingEntity l) { l.setYHeadRot(yaw); l.setYBodyRot(yaw); }
    }

    private void faceEntity(Entity e, Entity t) {
        double dx = t.getX() - e.getX(), dz = t.getZ() - e.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 0.01) faceDir(e, dx / d, dz / d);
    }

    private void enforceLaneBounds(Entity entity) {
        if (!laneBoundsSet) return;
        boolean approachingStructure = (targetStructure != null &&
                (state == MobState.FIGHTING || state == MobState.ADVANCING));
        double effMinX = laneMinX + 0.3, effMaxX = laneMaxX + 0.7;
        if (approachingStructure) {
            double sx = targetStructure.getPosition().getX() + 0.5;
            effMinX = Math.min(effMinX, sx - 3.0);
            effMaxX = Math.max(effMaxX, sx + 3.0);
        }
        if (state == MobState.ADVANCING && waypoints != null && currentWaypointIndex < waypoints.size()) {
            BlockPos wp = waypoints.get(currentWaypointIndex);
            effMinX = Math.min(effMinX, wp.getX() - 1.0);
            effMaxX = Math.max(effMaxX, wp.getX() + 2.0);
        }
        double x = Mth.clamp(entity.getX(), effMinX, effMaxX);
        double z = Mth.clamp(entity.getZ(), laneMinZ + 0.3, laneMaxZ + 0.7);
        if (x != entity.getX() || z != entity.getZ()) entity.teleportTo(x, entity.getY(), z);
    }

    // ================================================================
    // TARGETING
    // ================================================================

    private ArenaMob findNearestEnemy(ServerLevel world, Entity self, List<ArenaMob> mobs, double range) {
        ArenaMob nearest = null; double best = range * range;
        for (ArenaMob m : mobs) {
            if (m.getTeam() == team || m.isDead()) continue;
            Entity o = m.getEntity(world); if (o == null) continue;
            double d = self.distanceToSqr(o);
            if (d < best) { best = d; nearest = m; }
        }
        return nearest;
    }

    private ArenaStructure findNearestEnemyStructure(Entity self, List<ArenaStructure> structures, double range) {
        ArenaStructure nearestTower = null, nearestThrone = null;
        double bestTower = range * range, bestThrone = range * range;
        Vec3 selfPos = self.position();
        for (ArenaStructure s : structures) {
            if (s.getOwner() == team || s.isDestroyed()) continue;
            if (s.getType() == ArenaStructure.StructureType.TOWER) {
                Lane.LaneId towerLane = s.getAssociatedLane();
                if (towerLane != null && towerLane != this.lane) continue;
            }
            Vec3 sPos = Vec3.atCenterOf(s.getPosition());
            double dx = selfPos.x - sPos.x, dz = selfPos.z - sPos.z;
            double d = dx * dx + dz * dz;
            if (s.getType() == ArenaStructure.StructureType.TOWER) {
                if (d < bestTower) { bestTower = d; nearestTower = s; }
            } else {
                if (d < bestThrone) { bestThrone = d; nearestThrone = s; }
            }
        }
        return nearestTower != null ? nearestTower : nearestThrone;
    }

    private ArenaMob findMobByEntityId(List<ArenaMob> mobs, UUID eid) {
        for (ArenaMob m : mobs) if (eid.equals(m.getEntityId())) return m;
        return null;
    }

    /**
     * Generate approach positions around a structure and pick the one with
     * the fewest mobs already heading there.
     *
     * All offsets are relative to the structure's center BlockPos.
     * frontDirZ points TOWARD the attacker's side: P1=-1 (attacks from -Z), P2=+1 (attacks from +Z).
     *
     * Throne (9 positions):
     *   Front row (3):  X in {-1, 0, +1},  Z = center + frontDirZ * 3
     *   Side 1 (3):     X = +3,            Z in {center - 1, center, center + 1}
     *   Side 2 (3):     X = -3,            Z in {center - 1, center, center + 1}
     *
     * Tower (3 positions):
     *   Front row (3):  X in {-1, 0, +1},  Z = center + frontDirZ * 2
     */
    private Vec3 pickStructureApproachPos(ArenaStructure struct, List<ArenaMob> allMobs) {
        // Use integer BlockPos as center (no +0.5 offset) so positions land on block grid
        BlockPos pos = struct.getPosition();
        double cx = pos.getX();
        double cy = pos.getY();
        double cz = pos.getZ();

        // Approach direction: P1 comes from -Z, P2 comes from +Z
        double frontDirZ = (team == TeamSide.PLAYER1) ? -1.0 : 1.0;

        List<Vec3> approachPoints = new ArrayList<>();

        if (struct.getType() == ArenaStructure.StructureType.TOWER) {
            // Tower: 3 front positions, 2 blocks ahead of center
            double fz = cz + frontDirZ * 2.0;
            approachPoints.add(new Vec3(cx + 1.0, cy, fz));
            approachPoints.add(new Vec3(cx,       cy, fz));
            approachPoints.add(new Vec3(cx - 1.0, cy, fz));
        } else {
            // Throne: 3 front + 3 per side = 9 positions
            // Front row: 3 blocks ahead of center
            double fz = cz + frontDirZ * 3.0;
            approachPoints.add(new Vec3(cx + 1.0, cy, fz));
            approachPoints.add(new Vec3(cx,       cy, fz));
            approachPoints.add(new Vec3(cx - 1.0, cy, fz));
            // Side 1 (X = +3): spread along Z from center-1 to center+1
            approachPoints.add(new Vec3(cx + 3.0, cy, cz - 1.0));
            approachPoints.add(new Vec3(cx + 3.0, cy, cz));
            approachPoints.add(new Vec3(cx + 3.0, cy, cz + 1.0));
            // Side 2 (X = -3): spread along Z from center-1 to center+1
            approachPoints.add(new Vec3(cx - 3.0, cy, cz - 1.0));
            approachPoints.add(new Vec3(cx - 3.0, cy, cz));
            approachPoints.add(new Vec3(cx - 3.0, cy, cz + 1.0));
        }

        // Count how many mobs are targeting each position
        int[] counts = new int[approachPoints.size()];
        for (ArenaMob m : allMobs) {
            if (m == this || m.isDead() || m.getTeam() != team) continue;
            if (m.structureApproachPos == null) continue;
            for (int i = 0; i < approachPoints.size(); i++) {
                if (m.structureApproachPos.distanceToSqr(approachPoints.get(i)) < 0.5) {
                    counts[i]++;
                    break;
                }
            }
        }

        // Pick the approach point with the fewest mobs
        int bestIdx = 0;
        int bestCount = counts[0];
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] < bestCount) {
                bestCount = counts[i];
                bestIdx = i;
            }
        }
        return approachPoints.get(bestIdx);
    }

    /**
     * Assign approach position when targeting a structure.
     */
    private void assignStructureTarget(ArenaStructure struct, List<ArenaMob> allMobs) {
        targetStructure = struct;
        targetEntityId = null;
        structureApproachPos = pickStructureApproachPos(struct, allMobs);
    }

    /**
     * Get the position to move toward for the current structure target.
     */
    private Vec3 getStructureMoveTarget() {
        if (structureApproachPos != null) return structureApproachPos;
        BlockPos pos = targetStructure.getPosition();
        return new Vec3(pos.getX(), pos.getY(), pos.getZ());
    }

    public Entity getEntity(ServerLevel world) {
        return entityId == null ? null : world.getEntity(entityId);
    }

    public void removeEntity(ServerLevel world) {
        Entity e = getEntity(world); if (e != null) e.discard();
        removeHpBar(world);
    }

    private double hDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static void spawnDamageNumber(ServerLevel world, Vec3 pos, double damage) {
        double ox = (world.getRandom().nextDouble() - 0.5) * 0.5;
        double oz = (world.getRandom().nextDouble() - 0.5) * 0.5;
        ArmorStand marker = new ArmorStand(world, pos.x + ox, pos.y, pos.z + oz);
        marker.setInvisible(true); marker.setInvulnerable(true); marker.setNoGravity(true);
        marker.setCustomNameVisible(true); marker.setSilent(true); marker.setSmall(true);
        marker.setMarker(true); marker.addTag("arenaclash_dmg_number");
        String color;
        if (damage >= 15) color = "\u00A7c\u00A7l";
        else if (damage >= 8) color = "\u00A76";
        else if (damage >= 4) color = "\u00A7e";
        else color = "\u00A7f";
        String dmgText = damage == Math.floor(damage) ? String.format("%.0f", damage) : String.format("%.1f", damage);
        marker.setCustomName(Component.literal(color + "-" + dmgText + " \u2764"));
        world.addFreshEntity(marker);
        marker.tickCount = -25;
    }

    public static void tickDamageNumbers(ServerLevel world) {
        List<ArmorStand> toRemove = new ArrayList<>();
        for (Entity e : world.getAllEntities()) {
            if (e instanceof ArmorStand as) {
                if (e.getTags().contains("arenaclash_dmg_number")) {
                    e.setPosition(e.getX(), e.getY() + 0.04, e.getZ());
                    if (e.tickCount > 0) toRemove.add(as);
                }
            }
        }
        toRemove.forEach(Entity::discard);
    }

    /** Cleanup any stray arena mob projectiles and tower arrows that are stuck */
    public static void tickProjectileCleanup(ServerLevel world) {
        List<Entity> toRemove = new ArrayList<>();
        for (Entity e : world.getAllEntities()) {
            boolean isMobProj = e.getTags().contains("arenaclash_mob_projectile");
            boolean isTowerArrow = e.getTags().contains("arenaclash_tower_arrow");
            if (isMobProj || isTowerArrow) {
                // Tower arrows: let them fly and stick visually, remove after 5 sec
                if (isTowerArrow) {
                    if (e.tickCount > 100) toRemove.add(e);
                } else {
                    // Mob projectiles: remove after 3 sec
                    if (e.tickCount > 60) toRemove.add(e);
                }
            }
        }
        toRemove.forEach(Entity::discard);
    }
}
