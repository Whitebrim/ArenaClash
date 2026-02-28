package com.arenaclash.arena;

import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

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
    private boolean markedDead = false;

    private double laneMinX, laneMaxX, laneMinZ, laneMaxZ;
    private boolean laneBoundsSet = false;

    private Vec3d lastPosition;
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

    public double getCurrentHP(ServerWorld world) {
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
                ItemStack mainHand = living.getEquippedStack(EquipmentSlot.MAINHAND);
                yield mainHand.isOf(Items.BOW);
            }
            case "pillager" -> {
                ItemStack mainHand = living.getEquippedStack(EquipmentSlot.MAINHAND);
                yield mainHand.isOf(Items.CROSSBOW);
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

    public void spawn(ServerWorld world, BlockPos pos) {
        MobCardDefinition def = sourceCard.getDefinition();
        if (def == null) return;

        Entity entity = def.entityType().create(world);
        if (entity == null) return;

        double spawnY = pos.getY() + (isFlying() ? 1.0 : 0.0);
        entity.refreshPositionAndAngles(pos.getX() + 0.5, spawnY, pos.getZ() + 0.5, 0, 0);

        if (entity instanceof MobEntity mob) {
            mob.setAiDisabled(true);
            mob.setPersistent();
            var attr = mob.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (attr != null) attr.setBaseValue(maxHP);
            mob.setHealth((float) maxHP);
            if ("baby_zombie".equals(sourceCard.getMobId()) && entity instanceof net.minecraft.entity.mob.ZombieEntity z) {
                z.setBaby(true);
            }
            if (entity instanceof net.minecraft.entity.mob.SlimeEntity slime) {
                String id = sourceCard.getMobId();
                int size = id.contains("large") ? 4 : id.contains("medium") ? 2 : 1;
                slime.setSize(size, false);
                var hpAttr = slime.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
                if (hpAttr != null) hpAttr.setBaseValue(maxHP);
                slime.setHealth((float) maxHP);
            }
        }

        entity.addCommandTag("arenaclash_mob");
        entity.addCommandTag("team_" + team.name());
        entity.addCommandTag("lane_" + lane.name());
        entity.setCustomNameVisible(false);
        entity.setInvulnerable(true);

        world.spawnEntity(entity);
        this.entityId = entity.getUuid();
        this.state = MobState.IDLE;
        this.lastPosition = entity.getPos();

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
    }

    // ================================================================
    // HP BAR — position updated every single tick
    // ================================================================

    private void spawnHpBar(ServerWorld world, Entity entity) {
        ArmorStandEntity marker = new ArmorStandEntity(world,
                entity.getX(), entity.getY() + entity.getHeight() + 0.3, entity.getZ());
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomNameVisible(true);
        marker.setSilent(true);
        marker.setSmall(true);
        marker.setMarker(true);
        marker.addCommandTag("arenaclash_mob_hp");
        world.spawnEntity(marker);
        this.hpBarEntityId = marker.getUuid();
        updateHpBar(world, entity, true);
    }

    /** Move HP bar to entity position (every tick) and update text (every 5 ticks or forced) */
    private void updateHpBar(ServerWorld world, Entity mobEntity, boolean forceText) {
        if (hpBarEntityId == null) return;
        Entity marker = world.getEntity(hpBarEntityId);
        if (marker == null) return;

        // Teleport marker to exact mob position — every tick for smooth tracking
        double hpY = mobEntity.getY() + mobEntity.getHeight() + 0.3;
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
        net.minecraft.text.MutableText mobName = sourceCard.getDefinition() != null
                ? Text.translatable(sourceCard.getDefinition().translationKey())
                : Text.literal("Mob");
        String levelStr = " \u00A76Lv." + sourceCard.getLevel();
        marker.setCustomName(Text.literal(teamColor).append(mobName).append(Text.literal(levelStr + " " + bar + " " + hpColor + (int) hp)));
    }

    private void removeHpBar(ServerWorld world) {
        if (hpBarEntityId != null) {
            Entity marker = world.getEntity(hpBarEntityId);
            if (marker != null) marker.discard();
            hpBarEntityId = null;
        }
    }

    // ================================================================
    // TICK
    // ================================================================

    public void tick(ServerWorld world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        if (isDead()) return;
        Entity entity = getEntity(world);
        if (entity == null || !entity.isAlive()) { markDead(world); return; }
        if (entity instanceof LivingEntity l && l.getHealth() <= 0) { markDead(world); return; }

        if (attackCooldownRemaining > 0) attackCooldownRemaining--;

        tickEnvironmental(world, entity);

        if (isFlying() && entity.isOnGround()) {
            entity.requestTeleport(entity.getX(), entity.getY() + 1.0, entity.getZ());
        }

        switch (state) {
            case ADVANCING -> tickAdvancing(world, entity, allMobs, structures);
            case FIGHTING -> tickFighting(world, entity, allMobs, structures);
            case RETREATING -> tickRetreating(world, entity, allMobs);
            default -> {}
        }

        if (laneBoundsSet) enforceLaneBounds(entity);
        pushAwayFromAllies(world, entity, allMobs);

        if (state == MobState.ADVANCING && lastPosition != null) {
            if (entity.getPos().squaredDistanceTo(lastPosition) < 0.01) {
                stuckTicks++;
                if (stuckTicks > STUCK_THRESHOLD && waypoints != null && currentWaypointIndex < waypoints.size() - 1) {
                    currentWaypointIndex++;
                    stuckTicks = 0;
                }
            } else { stuckTicks = 0; }
        }
        lastPosition = entity.getPos();

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

    private void tickEnvironmental(ServerWorld world, Entity entity) {
        if (isUndead() && sunBurnCooldown <= 0) {
            if (world.isDay() && !world.isRaining()) {
                BlockPos pos = entity.getBlockPos();
                if (world.isSkyVisible(pos)) {
                    if (entity instanceof LivingEntity living) {
                        ItemStack helmet = living.getEquippedStack(EquipmentSlot.HEAD);
                        if (helmet.isEmpty()) {
                            entity.setOnFireFor(2);
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
                if (!entity.isTouchingWater()) {
                    takeDamage(1.0, world);
                    world.spawnParticles(ParticleTypes.BUBBLE,
                            entity.getX(), entity.getY() + 0.5, entity.getZ(),
                            5, 0.3, 0.3, 0.3, 0.02);
                }
            }
        }
    }

    // ================================================================
    // MOB COLLISION
    // ================================================================

    private void pushAwayFromAllies(ServerWorld world, Entity self, List<ArenaMob> allMobs) {
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
                    newX = MathHelper.clamp(newX, laneMinX + 0.3, laneMaxX + 0.7);
                    newZ = MathHelper.clamp(newZ, laneMinZ + 0.3, laneMaxZ + 0.7);
                }
                self.requestTeleport(newX, self.getY(), newZ);
            }
        }
    }

    // ================================================================
    // ADVANCING
    // ================================================================

    private void tickAdvancing(ServerWorld world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
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
                state = MobState.FIGHTING;
                return;
            }
            double structureAggroRange = Math.max(cfg.mobAggroRange, 20.0);
            ArenaStructure struct = findNearestEnemyStructure(entity, structures, structureAggroRange);
            if (struct != null) {
                double dist = entity.getBlockPos().getSquaredDistance(struct.getPosition());
                if (dist <= 10.0 * 10.0) {
                    targetStructure = struct;
                    targetEntityId = null;
                    state = MobState.FIGHTING;
                    return;
                }
            }
        } else {
            ArenaStructure blockingStruct = findNearestEnemyStructure(entity, structures, 5.0);
            if (blockingStruct != null) {
                Vec3d sPos = Vec3d.ofCenter(blockingStruct.getPosition());
                if (hDist(entity.getPos(), sPos) <= 3.5) {
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
                targetStructure = nearestStruct;
                targetEntityId = null;
                state = MobState.FIGHTING;
            } else if (nearestStruct != null) {
                Vec3d sPos = Vec3d.ofCenter(nearestStruct.getPosition());
                if (hDist(entity.getPos(), sPos) > 3.5) moveToward(entity, sPos);
            }
        }
    }

    // ================================================================
    // FIGHTING
    // ================================================================

    private void tickFighting(ServerWorld world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        AttackType atkType = getEffectiveAttackType(entity);
        double meleeRange = 2.5;
        double rangedRange = getRangedAttackRange();
        GameConfig cfg = GameConfig.get();

        if (targetEntityId != null) {
            ArenaMob target = findMobByEntityId(allMobs, targetEntityId);
            if (target == null || target.isDead()) { targetEntityId = null; skipPassedWaypoints(entity); state = MobState.ADVANCING; return; }
            Entity tEnt = target.getEntity(world);
            if (tEnt == null) { targetEntityId = null; skipPassedWaypoints(entity); state = MobState.ADVANCING; return; }

            double dist = Math.sqrt(entity.squaredDistanceTo(tEnt));
            faceEntity(entity, tEnt);

            switch (atkType) {
                case RANGED -> {
                    if (dist > rangedRange) moveToward(entity, tEnt.getPos());
                    else if (attackCooldownRemaining <= 0) {
                        performRangedAttack(entity, target, world);
                        attackCooldownRemaining = attackCooldown;
                    }
                }
                case CREEPER_EXPLOSION -> {
                    if (dist > 3.0) moveToward(entity, tEnt.getPos());
                    else performCreeperExplosion(entity, world, allMobs, structures);
                }
                case TELEPORT_MELEE -> {
                    if (dist > meleeRange && dist < 16.0 && attackCooldownRemaining <= 0) {
                        performTeleportAttack(entity, target, world, allMobs);
                        attackCooldownRemaining = attackCooldown;
                    } else if (dist > meleeRange) moveToward(entity, tEnt.getPos());
                    else if (attackCooldownRemaining <= 0) {
                        performAttack(entity, target, world, allMobs);
                        attackCooldownRemaining = attackCooldown;
                    }
                }
                case SUMMONER -> {
                    if (dist > rangedRange) moveToward(entity, tEnt.getPos());
                    else if (attackCooldownRemaining <= 0) {
                        performSummonerAttack(entity, target, world, allMobs);
                        attackCooldownRemaining = attackCooldown;
                    }
                }
                default -> { // MELEE
                    if (dist > meleeRange) moveToward(entity, tEnt.getPos());
                    else if (attackCooldownRemaining <= 0) {
                        performAttack(entity, target, world, allMobs);
                        attackCooldownRemaining = attackCooldown;
                    }
                }
            }
        } else if (targetStructure != null) {
            if (targetStructure.isDestroyed()) { targetStructure = null; skipPassedWaypoints(entity); state = MobState.ADVANCING; return; }
            Vec3d sPos = Vec3d.ofCenter(targetStructure.getPosition());
            double dist = hDist(entity.getPos(), sPos);

            if (atkType == AttackType.CREEPER_EXPLOSION) {
                if (dist > 3.0) moveToward(entity, sPos);
                else performCreeperExplosion(entity, world, allMobs, structures);
            } else if (atkType == AttackType.RANGED) {
                if (dist > Math.min(rangedRange, 8.0)) moveToward(entity, sPos);
                else if (attackCooldownRemaining <= 0) {
                    performStructureAttack(entity, targetStructure, world);
                    attackCooldownRemaining = attackCooldown;
                }
            } else {
                if (dist > meleeRange + 2.5) moveToward(entity, sPos);
                else if (attackCooldownRemaining <= 0) {
                    performStructureAttack(entity, targetStructure, world);
                    attackCooldownRemaining = attackCooldown;
                }
            }
        } else {
            double searchRange = (atkType == AttackType.RANGED) ?
                    Math.max(cfg.mobAggroRange, rangedRange) : cfg.mobAggroRange;
            ArenaMob enemy = findNearestEnemy(world, entity, allMobs, searchRange);
            if (enemy != null) { targetEntityId = enemy.getEntityId(); return; }
            ArenaStructure struct = findNearestEnemyStructure(entity, structures, 100.0);
            if (struct != null) { targetStructure = struct; return; }
            skipPassedWaypoints(entity);
            state = MobState.ADVANCING;
        }
    }

    // ================================================================
    // RETREATING
    // ================================================================

    private void tickRetreating(ServerWorld world, Entity entity, List<ArenaMob> allMobs) {
        if (entity.getBlockPos().getSquaredDistance(startSlotPos) <= 4.0) { state = MobState.IDLE; return; }
        if (attackDamage > 0) {
            ArenaMob nearby = findNearestEnemy(world, entity, allMobs, GameConfig.get().mobAggroRange * 0.5);
            if (nearby != null) {
                Entity ne = nearby.getEntity(world);
                if (ne != null && entity.squaredDistanceTo(ne) <= 6.25 && attackCooldownRemaining <= 0) {
                    performAttack(entity, nearby, world, allMobs);
                    attackCooldownRemaining = attackCooldown;
                }
            }
        }
        moveToward(entity, Vec3d.ofCenter(startSlotPos));
    }

    // ================================================================
    // COMBAT - MELEE (with splash)
    // ================================================================

    private void performAttack(Entity attacker, ArenaMob defender, ServerWorld world, List<ArenaMob> allMobs) {
        if (defender.isDead() || attackDamage <= 0) return;
        Entity dEnt = defender.getEntity(world);
        if (dEnt == null) return;

        // Trigger attack animation
        triggerAttackAnimation(attacker, world);

        float dmg = (float) attackDamage;

        // Cave spider poison
        if ("cave_spider".equals(sourceCard.getMobId()) && dEnt instanceof LivingEntity target) {
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100, 0));
        }
        // Pufferfish poison
        if ("pufferfish".equals(sourceCard.getMobId()) && dEnt instanceof LivingEntity target) {
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 140, 1));
        }

        defender.takeDamage(dmg, world);

        // Splash damage to nearby enemies (50% dmg within 1.5 blocks)
        if (allMobs != null) {
            for (ArenaMob nearby : allMobs) {
                if (nearby == defender || nearby.getTeam() == team || nearby.isDead()) continue;
                Entity nEnt = nearby.getEntity(world);
                if (nEnt == null) continue;
                if (nEnt.squaredDistanceTo(dEnt) < 2.25) {
                    float splashDmg = dmg * 0.5f;
                    nearby.takeDamage(splashDmg, world);
                    spawnDamageNumber(world, nEnt.getPos().add(0, nEnt.getHeight() + 0.3, 0), splashDmg);
                    world.spawnParticles(ParticleTypes.SWEEP_ATTACK,
                            nEnt.getX(), nEnt.getBodyY(0.5), nEnt.getZ(), 1, 0.1, 0.1, 0.1, 0.0);
                }
            }
        }

        // Knockback
        Vec3d kbDir = dEnt.getPos().subtract(attacker.getPos()).normalize();
        double kb = GameConfig.get().knockbackStrength;
        double newX = dEnt.getX() + kbDir.x * kb;
        double newZ = dEnt.getZ() + kbDir.z * kb;
        if (defender.laneBoundsSet) {
            newX = MathHelper.clamp(newX, defender.laneMinX + 0.3, defender.laneMaxX + 0.7);
            newZ = MathHelper.clamp(newZ, defender.laneMinZ + 0.3, defender.laneMaxZ + 0.7);
        }
        dEnt.requestTeleport(newX, dEnt.getY(), newZ);

        if (dEnt instanceof LivingEntity ld) { ld.hurtTime = 10; ld.maxHurtTime = 10; }

        int pCount = Math.min((int)(dmg / 2) + 1, 8);
        world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, dEnt.getX(), dEnt.getBodyY(0.5), dEnt.getZ(), pCount, 0.3, 0.2, 0.3, 0.1);
        if (dmg >= 8) world.spawnParticles(ParticleTypes.CRIT, dEnt.getX(), dEnt.getBodyY(0.5), dEnt.getZ(), 5, 0.4, 0.3, 0.4, 0.2);
        if (dmg >= 15) world.spawnParticles(ParticleTypes.ENCHANTED_HIT, dEnt.getX(), dEnt.getBodyY(0.5), dEnt.getZ(), 8, 0.5, 0.4, 0.5, 0.3);

        spawnDamageNumber(world, dEnt.getPos().add(0, dEnt.getHeight() + 0.5, 0), dmg);
        playAttackSound(world, attacker.getPos());
    }

    // ================================================================
    // COMBAT - RANGED (proper projectile entities)
    // ================================================================

    private void performRangedAttack(Entity attacker, ArenaMob defender, ServerWorld world) {
        if (defender.isDead()) return;
        Entity targetEntity = defender.getEntity(world);
        if (targetEntity == null) return;

        triggerAttackAnimation(attacker, world);

        String mobId = sourceCard.getMobId();
        Vec3d shootFrom = attacker.getPos().add(0, attacker.getHeight() * 0.7, 0);
        Vec3d targetPos = targetEntity.getPos().add(0, targetEntity.getHeight() * 0.5, 0);
        Vec3d direction = targetPos.subtract(shootFrom).normalize();

        switch (mobId) {
            case "skeleton", "stray", "bogged" -> {
                // Real arrow projectile
                ArrowEntity arrow = new ArrowEntity(world, shootFrom.x, shootFrom.y, shootFrom.z,
                        new ItemStack(Items.ARROW), null);
                arrow.setVelocity(direction.x, direction.y + 0.05, direction.z, 1.6f, 2.0f);
                arrow.setDamage(0);
                arrow.pickupType = ArrowEntity.PickupPermission.DISALLOWED;
                arrow.addCommandTag("arenaclash_mob_projectile");
                world.spawnEntity(arrow);
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_SKELETON_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "pillager" -> {
                ArrowEntity arrow = new ArrowEntity(world, shootFrom.x, shootFrom.y, shootFrom.z,
                        new ItemStack(Items.ARROW), null);
                arrow.setVelocity(direction.x, direction.y + 0.03, direction.z, 1.8f, 1.5f);
                arrow.setDamage(0);
                arrow.pickupType = ArrowEntity.PickupPermission.DISALLOWED;
                arrow.addCommandTag("arenaclash_mob_projectile");
                world.spawnEntity(arrow);
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "blaze" -> {
                // Real small fireball
                try {
                    SmallFireballEntity fb = new SmallFireballEntity(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.multiply(0.8));
                    fb.setPosition(shootFrom);
                    fb.addCommandTag("arenaclash_mob_projectile");
                    world.spawnEntity(fb);
                } catch (Exception ignored) {
                    spawnVisualArrow(world, shootFrom, direction);
                }
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "ghast" -> {
                // Real fireball
                try {
                    FireballEntity fb = new FireballEntity(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.multiply(0.5), 0);
                    fb.setPosition(shootFrom);
                    fb.addCommandTag("arenaclash_mob_projectile");
                    world.spawnEntity(fb);
                } catch (Exception ignored) {
                    spawnVisualArrow(world, shootFrom, direction);
                }
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_GHAST_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "witch" -> {
                // Snowball proxy for potion
                try {
                    SnowballEntity sb = new SnowballEntity(world, shootFrom.x, shootFrom.y, shootFrom.z);
                    sb.setVelocity(direction.x, direction.y + 0.2, direction.z, 0.75f, 4.0f);
                    sb.addCommandTag("arenaclash_mob_projectile");
                    world.spawnEntity(sb);
                } catch (Exception ignored) {
                    spawnVisualArrow(world, shootFrom, direction);
                }
                world.spawnParticles(ParticleTypes.SPLASH, targetEntity.getX(),
                        targetEntity.getBodyY(0.5), targetEntity.getZ(), 10, 0.3, 0.3, 0.3, 0.1);
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_WITCH_THROW, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "snow_golem" -> {
                try {
                    SnowballEntity sb = new SnowballEntity(world, shootFrom.x, shootFrom.y, shootFrom.z);
                    sb.setVelocity(direction.x, direction.y + 0.1, direction.z, 1.2f, 3.0f);
                    sb.addCommandTag("arenaclash_mob_projectile");
                    world.spawnEntity(sb);
                } catch (Exception ignored) {
                    spawnVisualArrow(world, shootFrom, direction);
                }
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_SNOW_GOLEM_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "guardian", "elder_guardian" -> {
                // Laser beam — particle line (no entity in vanilla for guardian laser)
                int steps = 10;
                for (int i = 0; i < steps; i++) {
                    double t = (double) i / steps;
                    double px = shootFrom.x + (targetPos.x - shootFrom.x) * t;
                    double py = shootFrom.y + (targetPos.y - shootFrom.y) * t;
                    double pz = shootFrom.z + (targetPos.z - shootFrom.z) * t;
                    float r = "elder_guardian".equals(mobId) ? 0.5f : 0.2f;
                    float g = "elder_guardian".equals(mobId) ? 0.0f : 0.8f;
                    float b = "elder_guardian".equals(mobId) ? 0.5f : 1.0f;
                    world.spawnParticles(new DustParticleEffect(
                            new org.joml.Vector3f(r, g, b), 1.0f), px, py, pz, 1, 0, 0, 0, 0);
                }
                var snd = "elder_guardian".equals(mobId) ? SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE : SoundEvents.ENTITY_GUARDIAN_ATTACK;
                world.playSound(null, attacker.getBlockPos(), snd, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "llama", "trader_llama" -> {
                // Llama spit — snowball proxy
                try {
                    SnowballEntity spit = new SnowballEntity(world, shootFrom.x, shootFrom.y, shootFrom.z);
                    spit.setVelocity(direction.x, direction.y + 0.1, direction.z, 1.0f, 5.0f);
                    spit.addCommandTag("arenaclash_mob_projectile");
                    world.spawnEntity(spit);
                } catch (Exception ignored) {
                    spawnVisualArrow(world, shootFrom, direction);
                }
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_LLAMA_SPIT, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "breeze" -> {
                // Wind charge particles (no WindChargeEntity in 1.21.1 public API)
                for (int i = 0; i < 6; i++) {
                    double t = (double) i / 6;
                    double px = shootFrom.x + direction.x * t * 8;
                    double py = shootFrom.y + direction.y * t * 8;
                    double pz = shootFrom.z + direction.z * t * 8;
                    world.spawnParticles(ParticleTypes.CLOUD, px, py, pz, 3, 0.1, 0.1, 0.1, 0.05);
                    world.spawnParticles(ParticleTypes.POOF, px, py, pz, 1, 0.1, 0.1, 0.1, 0.02);
                }
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
            case "warden" -> {
                // Sonic boom — particle beam
                int steps = 12;
                for (int i = 0; i < steps; i++) {
                    double t = (double) i / steps;
                    double px = shootFrom.x + (targetPos.x - shootFrom.x) * t;
                    double py = shootFrom.y + (targetPos.y - shootFrom.y) * t;
                    double pz = shootFrom.z + (targetPos.z - shootFrom.z) * t;
                    world.spawnParticles(ParticleTypes.SONIC_BOOM, px, py, pz, 1, 0, 0, 0, 0);
                }
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 1.5f, 1.0f);
            }
            case "wither" -> {
                // Real wither skull
                try {
                    WitherSkullEntity skull = new WitherSkullEntity(world,
                            attacker instanceof LivingEntity le ? le : null,
                            direction.multiply(0.6));
                    skull.setPosition(shootFrom);
                    skull.addCommandTag("arenaclash_mob_projectile");
                    world.spawnEntity(skull);
                } catch (Exception ignored) {
                    spawnVisualArrow(world, shootFrom, direction);
                }
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
                if (targetEntity instanceof LivingEntity lt) {
                    lt.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 100, 1));
                }
            }
            default -> {
                // Fallback: arrow
                ArrowEntity arrow = new ArrowEntity(world, shootFrom.x, shootFrom.y, shootFrom.z,
                        new ItemStack(Items.ARROW), null);
                arrow.setVelocity(direction.x, direction.y + 0.05, direction.z, 1.6f, 2.0f);
                arrow.setDamage(0);
                arrow.pickupType = ArrowEntity.PickupPermission.DISALLOWED;
                arrow.addCommandTag("arenaclash_mob_projectile");
                world.spawnEntity(arrow);
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
        }

        // Apply damage directly (projectile is visual only)
        float dmg = (float) attackDamage;
        defender.takeDamage(dmg, world);
        spawnDamageNumber(world, targetEntity.getPos().add(0, targetEntity.getHeight() + 0.3, 0), dmg);
        if (targetEntity instanceof LivingEntity ld) { ld.hurtTime = 10; ld.maxHurtTime = 10; }

        // Blaze DOT
        if ("blaze".equals(mobId)) targetEntity.setOnFireFor(3);
    }

    /** Spawn a short-lived visual arrow as fallback for ranged attacks */
    private void spawnVisualArrow(ServerWorld world, Vec3d from, Vec3d dir) {
        ArrowEntity arrow = new ArrowEntity(world, from.x, from.y, from.z,
                new ItemStack(Items.ARROW), null);
        arrow.setVelocity(dir.x, dir.y + 0.05, dir.z, 1.6f, 2.0f);
        arrow.setDamage(0);
        arrow.pickupType = ArrowEntity.PickupPermission.DISALLOWED;
        arrow.addCommandTag("arenaclash_mob_projectile");
        world.spawnEntity(arrow);
    }

    // ================================================================
    // COMBAT - CREEPER EXPLOSION
    // ================================================================

    private void performCreeperExplosion(Entity creeper, ServerWorld world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        Vec3d center = creeper.getPos();
        double explosionRadius = 4.0;
        float explosionDamage = (float) Math.max(10.0, this.attackDamage); // Uses card attack, scales with level

        for (ArenaMob mob : allMobs) {
            if (mob == this || mob.getTeam() == team || mob.isDead()) continue;
            Entity e = mob.getEntity(world);
            if (e == null) continue;
            double dist = hDist(center, e.getPos());
            if (dist <= explosionRadius) {
                float dmg = (float) (explosionDamage * (1.0 - dist / explosionRadius));
                mob.takeDamage(dmg, world);
                spawnDamageNumber(world, e.getPos().add(0, e.getHeight() + 0.3, 0), dmg);
                Vec3d kb = e.getPos().subtract(center).normalize().multiply(1.5);
                double nx = e.getX() + kb.x, nz = e.getZ() + kb.z;
                if (mob.laneBoundsSet) {
                    nx = MathHelper.clamp(nx, mob.laneMinX + 0.3, mob.laneMaxX + 0.7);
                    nz = MathHelper.clamp(nz, mob.laneMinZ + 0.3, mob.laneMaxZ + 0.7);
                }
                e.requestTeleport(nx, e.getY(), nz);
            }
        }
        for (ArenaStructure struct : structures) {
            if (struct.getOwner() == team || struct.isDestroyed()) continue;
            Vec3d sPos = Vec3d.ofCenter(struct.getPosition());
            if (hDist(center, sPos) <= explosionRadius + 2.0) struct.damage(explosionDamage * 2.0f, world);
        }

        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 1, center.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.CLOUD, center.x, center.y + 1, center.z, 20, 1.5, 1, 1.5, 0.1);
        world.playSound(null, center.x, center.y, center.z,
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.0f, 1.0f);
        markDead(world);
    }

    // ================================================================
    // COMBAT - TELEPORT (Enderman)
    // ================================================================

    private void performTeleportAttack(Entity attacker, ArenaMob defender, ServerWorld world, List<ArenaMob> allMobs) {
        Entity targetEntity = defender.getEntity(world);
        if (targetEntity == null) return;

        Vec3d targetFacing = Vec3d.fromPolar(0, targetEntity.getYaw()).normalize();
        Vec3d behindPos = targetEntity.getPos().subtract(targetFacing.multiply(1.5));

        double newX = behindPos.x, newZ = behindPos.z;
        if (laneBoundsSet) {
            newX = MathHelper.clamp(newX, laneMinX + 0.3, laneMaxX + 0.7);
            newZ = MathHelper.clamp(newZ, laneMinZ + 0.3, laneMaxZ + 0.7);
        }

        world.spawnParticles(ParticleTypes.PORTAL, attacker.getX(), attacker.getY() + 1, attacker.getZ(), 15, 0.3, 0.5, 0.3, 0.3);
        attacker.requestTeleport(newX, attacker.getY(), newZ);
        world.spawnParticles(ParticleTypes.PORTAL, newX, attacker.getY() + 1, newZ, 15, 0.3, 0.5, 0.3, 0.3);
        world.playSound(null, newX, attacker.getY(), newZ, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.0f, 1.0f);

        performAttack(attacker, defender, world, allMobs);
    }

    // ================================================================
    // COMBAT - SUMMONER (Evoker) — real vexes + fang attack
    // ================================================================

    private void performSummonerAttack(Entity attacker, ArenaMob defender, ServerWorld world, List<ArenaMob> allMobs) {
        Entity targetEntity = defender.getEntity(world);
        if (targetEntity == null) return;

        triggerAttackAnimation(attacker, world);

        // Alternate between vex summoning and fang attack
        int liveVexes = (int) evokerVexIds.stream()
                .filter(id -> { Entity v = world.getEntity(id); return v != null && v.isAlive(); })
                .count();

        if (liveVexes < 3 && world.getRandom().nextFloat() < 0.35) {
            // Summon a real Vex entity
            VexEntity vex = EntityType.VEX.create(world);
            if (vex != null) {
                double vx = attacker.getX() + (world.getRandom().nextDouble() - 0.5) * 2;
                double vy = attacker.getY() + 1.0;
                double vz = attacker.getZ() + (world.getRandom().nextDouble() - 0.5) * 2;
                vex.refreshPositionAndAngles(vx, vy, vz, 0, 0);
                vex.setAiDisabled(true);
                vex.setPersistent();
                vex.setInvulnerable(true);
                vex.addCommandTag("arenaclash_mob");
                vex.addCommandTag("team_" + team.name());
                vex.addCommandTag("lane_" + lane.name());
                vex.addCommandTag("arenaclash_vex");
                var hpAttr = vex.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
                if (hpAttr != null) hpAttr.setBaseValue(14.0);
                vex.setHealth(14.0f);
                world.spawnEntity(vex);
                evokerVexIds.add(vex.getUuid());

                // Create ArenaMob wrapper for the vex so it fights
                MobCard vexCard = new MobCard("vex");
                ArenaMob vexMob = new ArenaMob(ownerId, team, vexCard, lane, startSlotPos);
                vexMob.entityId = vex.getUuid();
                vexMob.state = MobState.ADVANCING;
                vexMob.waypoints = this.waypoints != null ? new ArrayList<>(this.waypoints) : new ArrayList<>();
                vexMob.currentWaypointIndex = this.currentWaypointIndex;
                vexMob.setSubMob(true);
                if (laneBoundsSet) vexMob.setLaneBounds(laneMinX, laneMaxX, laneMinZ, laneMaxZ);
                vexMob.spawnHpBar(world, vex);
                // DEFERRED: add to pendingChildMobs, NOT to allMobs during iteration
                pendingChildMobs.add(vexMob);

                world.spawnParticles(ParticleTypes.ENCHANTED_HIT, vx, vy + 0.5, vz, 10, 0.3, 0.3, 0.3, 0.2);
                world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
        } else {
            // Fang attack — spawn evoker fangs entity line toward target (AoE)
            Vec3d dir = targetEntity.getPos().subtract(attacker.getPos()).normalize();
            float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0 / Math.PI));

            // Range increased 1.5x: 8 fangs instead of 5
            for (int i = 1; i <= 8; i++) {
                double px = attacker.getX() + dir.x * i * 1.0;
                double pz = attacker.getZ() + dir.z * i * 1.0;
                double py = attacker.getY();
                try {
                    net.minecraft.entity.mob.EvokerFangsEntity fangs =
                            new net.minecraft.entity.mob.EvokerFangsEntity(world, px, py, pz, yaw, i * 2,
                                    attacker instanceof LivingEntity le ? le : null);
                    fangs.addCommandTag("arenaclash_mob_projectile");
                    world.spawnEntity(fangs);
                } catch (Exception ignored) {
                    world.spawnParticles(ParticleTypes.CLOUD, px, py + 0.3, pz, 3, 0.1, 0.1, 0.1, 0.02);
                }
            }
            world.playSound(null, attacker.getBlockPos(), SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.HOSTILE, 1.0f, 1.0f);

            // AoE damage: hit all enemies within the fang line corridor
            float dmg = (float) attackDamage;
            double fangReach = 8.0;
            for (ArenaMob nearby : allMobs) {
                if (nearby.getTeam() == team || nearby.isDead()) continue;
                Entity nEnt = nearby.getEntity(world);
                if (nEnt == null) continue;
                Vec3d toEnemy = nEnt.getPos().subtract(attacker.getPos());
                double projDist = toEnemy.x * dir.x + toEnemy.z * dir.z;
                if (projDist > 0 && projDist <= fangReach + 1.0) {
                    double perpDist = Math.abs(toEnemy.x * (-dir.z) + toEnemy.z * dir.x);
                    if (perpDist <= 1.5) {
                        nearby.takeDamage(dmg, world);
                        spawnDamageNumber(world, nEnt.getPos().add(0, nEnt.getHeight() + 0.5, 0), dmg);
                        if (nEnt instanceof LivingEntity ld) { ld.hurtTime = 10; ld.maxHurtTime = 10; }
                    }
                }
            }
        }
    }

    // ================================================================
    // COMBAT - STRUCTURE ATTACK
    // ================================================================

    private void performStructureAttack(Entity attacker, ArenaStructure structure, ServerWorld world) {
        if (structure.isDestroyed() || attackDamage <= 0) return;
        triggerAttackAnimation(attacker, world);

        float dmg = (float) attackDamage;
        structure.damage(dmg, world);

        Vec3d sp = Vec3d.ofCenter(structure.getPosition()).add(0, 1, 0);
        world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, sp.x, sp.y, sp.z, 3, 0.5, 0.3, 0.5, 0.1);
        world.spawnParticles(ParticleTypes.SMOKE, sp.x, sp.y, sp.z, 3, 0.5, 0.5, 0.5, 0.02);
        world.playSound(null, sp.x, sp.y, sp.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.0f, 0.7f);
        spawnDamageNumber(world, sp.add(0, 1.5, 0), dmg);
    }

    /**
     * Trigger the proper attack animation for this mob type.
     * - Humanoid mobs (zombies, skeletons, piglins): swingHand works
     * - Iron Golem: needs entity status 4 for its signature arm-sweep animation
     * - Ravager: entity status 4 for attack lunge
     * - Other mobs: entity status 4 is a generic attack trigger
     * We send both swingHand (for humanoids) and entity status (for non-humanoids).
     */
    private void triggerAttackAnimation(Entity attacker, ServerWorld world) {
        if (attacker instanceof LivingEntity la) {
            la.swingHand(la.getActiveHand());
        }
        // Entity status 4 = PLAY_ATTACK_SOUND / attack animation
        // This triggers native attack animations for mobs like Iron Golem,
        // Ravager, Hoglin, Zoglin, etc. that don't use swingHand for their animation
        if (attacker instanceof net.minecraft.entity.passive.IronGolemEntity
                || attacker instanceof net.minecraft.entity.mob.RavagerEntity
                || attacker instanceof net.minecraft.entity.mob.HoglinEntity
                || attacker instanceof net.minecraft.entity.mob.ZoglinEntity) {
            world.sendEntityStatus(attacker, (byte) 4);
        }
    }

    private void playAttackSound(ServerWorld world, Vec3d pos) {
        float pitch = 0.8f + world.getRandom().nextFloat() * 0.4f;
        MobCardDefinition def = sourceCard.getDefinition();
        if (def == null) { world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 0.8f, pitch); return; }
        var sound = switch (def.category()) {
            case UNDEAD -> SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR;
            case GOLEM -> SoundEvents.ENTITY_IRON_GOLEM_ATTACK;
            case BOSS -> SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT;
            case ARTHROPOD -> SoundEvents.ENTITY_SPIDER_AMBIENT;
            default -> SoundEvents.ENTITY_PLAYER_ATTACK_STRONG;
        };
        world.playSound(null, pos.x, pos.y, pos.z, sound, SoundCategory.HOSTILE, 0.8f, pitch);
    }

    public void takeDamage(double amount, ServerWorld world) {
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
                living.maxHurtTime = 10;
            }
        }
    }

    private void markDead(ServerWorld world) {
        if (markedDead) return;
        markedDead = true;
        state = MobState.DEAD;
        Entity e = getEntity(world);

        // Handle slime splitting: spawn smaller slimes before discarding
        if (e != null && isSlimeType() && getSlimeSize() > 1) {
            spawnSlimeSplit(world, e);
        }

        if (e != null) {
            world.spawnParticles(ParticleTypes.SOUL, e.getX(), e.getY() + 0.5, e.getZ(), 10, 0.3, 0.5, 0.3, 0.05);
            world.spawnParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + 0.5, e.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
            world.spawnParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 0.5, e.getZ(), 5, 0.2, 0.3, 0.2, 0.03);
            world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_GENERIC_DEATH, SoundCategory.HOSTILE, 1.0f, 0.8f + world.getRandom().nextFloat() * 0.4f);
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
    private void spawnSlimeSplit(ServerWorld world, Entity parent) {
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
            ArenaMob childMob = new ArenaMob(ownerId, team, childCard, lane, startSlotPos);

            double ox = (world.getRandom().nextDouble() - 0.5) * 1.5;
            double oz = (world.getRandom().nextDouble() - 0.5) * 1.5;
            BlockPos childPos = new BlockPos(
                    (int)(parent.getX() + ox),
                    parent.getBlockPos().getY(),
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
                SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.HOSTILE, 1.0f, 0.8f);
    }

    // ================================================================
    // MOVEMENT
    // ================================================================

    private void moveTowardWaypoint(Entity entity) {
        if (waypoints == null || currentWaypointIndex >= waypoints.size()) return;
        BlockPos target = waypoints.get(currentWaypointIndex);
        Vec3d tv = Vec3d.ofCenter(target);
        if (hDist(entity.getPos(), tv) <= 1.5) {
            currentWaypointIndex++;
            if (currentWaypointIndex >= waypoints.size()) return;
            target = waypoints.get(currentWaypointIndex);
            tv = Vec3d.ofCenter(target);
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
        Vec3d pos = entity.getPos();
        // Skip waypoints we've already passed: if the NEXT waypoint is closer than
        // the current one, it means we're past the current waypoint
        while (currentWaypointIndex < waypoints.size() - 1) {
            Vec3d current = Vec3d.ofCenter(waypoints.get(currentWaypointIndex));
            Vec3d next = Vec3d.ofCenter(waypoints.get(currentWaypointIndex + 1));
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

    private void moveToward(Entity entity, Vec3d target) {
        Vec3d cur = entity.getPos();
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
            newX = MathHelper.clamp(newX, effMinX, effMaxX);
            newZ = MathHelper.clamp(newZ, laneMinZ + 0.3, laneMaxZ + 0.7);
        }
        double newY = isFlying() ? Math.max(cur.y, startSlotPos.getY() + 1.0) : cur.y;
        entity.requestTeleport(newX, newY, newZ);
        faceDir(entity, nx, nz);
    }

    private void faceDir(Entity entity, double nx, double nz) {
        float yaw = (float)(Math.atan2(-nx, nz) * (180.0 / Math.PI));
        entity.setYaw(yaw);
        if (entity instanceof LivingEntity l) { l.setHeadYaw(yaw); l.setBodyYaw(yaw); }
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
        double x = MathHelper.clamp(entity.getX(), effMinX, effMaxX);
        double z = MathHelper.clamp(entity.getZ(), laneMinZ + 0.3, laneMaxZ + 0.7);
        if (x != entity.getX() || z != entity.getZ()) entity.requestTeleport(x, entity.getY(), z);
    }

    // ================================================================
    // TARGETING
    // ================================================================

    private ArenaMob findNearestEnemy(ServerWorld world, Entity self, List<ArenaMob> mobs, double range) {
        ArenaMob nearest = null; double best = range * range;
        for (ArenaMob m : mobs) {
            if (m.getTeam() == team || m.isDead()) continue;
            Entity o = m.getEntity(world); if (o == null) continue;
            double d = self.squaredDistanceTo(o);
            if (d < best) { best = d; nearest = m; }
        }
        return nearest;
    }

    private ArenaStructure findNearestEnemyStructure(Entity self, List<ArenaStructure> structures, double range) {
        ArenaStructure nearestTower = null, nearestThrone = null;
        double bestTower = range * range, bestThrone = range * range;
        Vec3d selfPos = self.getPos();
        for (ArenaStructure s : structures) {
            if (s.getOwner() == team || s.isDestroyed()) continue;
            if (s.getType() == ArenaStructure.StructureType.TOWER) {
                Lane.LaneId towerLane = s.getAssociatedLane();
                if (towerLane != null && towerLane != this.lane) continue;
            }
            Vec3d sPos = Vec3d.ofCenter(s.getPosition());
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

    public Entity getEntity(ServerWorld world) {
        return entityId == null ? null : world.getEntity(entityId);
    }

    public void removeEntity(ServerWorld world) {
        Entity e = getEntity(world); if (e != null) e.discard();
        removeHpBar(world);
    }

    private double hDist(Vec3d a, Vec3d b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static void spawnDamageNumber(ServerWorld world, Vec3d pos, double damage) {
        double ox = (world.getRandom().nextDouble() - 0.5) * 0.5;
        double oz = (world.getRandom().nextDouble() - 0.5) * 0.5;
        ArmorStandEntity marker = new ArmorStandEntity(world, pos.x + ox, pos.y, pos.z + oz);
        marker.setInvisible(true); marker.setInvulnerable(true); marker.setNoGravity(true);
        marker.setCustomNameVisible(true); marker.setSilent(true); marker.setSmall(true);
        marker.setMarker(true); marker.addCommandTag("arenaclash_dmg_number");
        String color;
        if (damage >= 15) color = "\u00A7c\u00A7l";
        else if (damage >= 8) color = "\u00A76";
        else if (damage >= 4) color = "\u00A7e";
        else color = "\u00A7f";
        String dmgText = damage == Math.floor(damage) ? String.format("%.0f", damage) : String.format("%.1f", damage);
        marker.setCustomName(Text.literal(color + "-" + dmgText + " \u2764"));
        world.spawnEntity(marker);
        marker.age = -25;
    }

    public static void tickDamageNumbers(ServerWorld world) {
        List<ArmorStandEntity> toRemove = new ArrayList<>();
        for (Entity e : world.iterateEntities()) {
            if (e instanceof ArmorStandEntity as) {
                if (e.getCommandTags().contains("arenaclash_dmg_number")) {
                    e.setPosition(e.getX(), e.getY() + 0.04, e.getZ());
                    if (e.age > 0) toRemove.add(as);
                }
            }
        }
        toRemove.forEach(Entity::discard);
    }

    /** Cleanup any stray arena mob projectiles and tower arrows that are stuck */
    public static void tickProjectileCleanup(ServerWorld world) {
        List<Entity> toRemove = new ArrayList<>();
        for (Entity e : world.iterateEntities()) {
            boolean isMobProj = e.getCommandTags().contains("arenaclash_mob_projectile");
            boolean isTowerArrow = e.getCommandTags().contains("arenaclash_tower_arrow");
            if (isMobProj || isTowerArrow) {
                // Tower arrows: let them fly and stick visually, remove after 5 sec
                if (isTowerArrow) {
                    if (e.age > 100) toRemove.add(e);
                } else {
                    // Mob projectiles: remove after 3 sec
                    if (e.age > 60) toRemove.add(e);
                }
            }
        }
        toRemove.forEach(Entity::discard);
    }
}
