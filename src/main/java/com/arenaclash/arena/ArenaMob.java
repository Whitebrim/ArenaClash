package com.arenaclash.arena;

import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
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
 * Fully rewritten arena mob with:
 * - Proper waypoint navigation (side lane → tower → throne, center → throne)
 * - Lane confinement (knockback clamped to lane bounds)
 * - Vanilla-like combat with swing animations, knockback, particles
 * - Smooth movement with proper facing
 * - Stuck detection and recovery
 * - Structure targeting (tower before throne on side lanes)
 */
public class ArenaMob {
    public enum MobState { IDLE, ADVANCING, FIGHTING, RETREATING, DEAD }

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

    // Lane confinement bounds
    private double laneMinX, laneMaxX, laneMinZ, laneMaxZ;
    private boolean laneBoundsSet = false;

    // Stuck detection
    private Vec3d lastPosition;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 60;

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

    public double getCurrentHP(ServerWorld world) {
        Entity e = getEntity(world);
        return (e instanceof LivingEntity living) ? living.getHealth() : 0;
    }

    public void setLaneBounds(double minX, double maxX, double minZ, double maxZ) {
        this.laneMinX = minX;
        this.laneMaxX = maxX;
        this.laneMinZ = minZ;
        this.laneMaxZ = maxZ;
        this.laneBoundsSet = true;
    }

    // ================================================================
    // SPAWN
    // ================================================================

    public void spawn(ServerWorld world, BlockPos pos) {
        MobCardDefinition def = sourceCard.getDefinition();
        if (def == null) return;

        Entity entity = def.entityType().create(world);
        if (entity == null) return;

        entity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);

        if (entity instanceof MobEntity mob) {
            mob.setAiDisabled(true);
            mob.setPersistent();
            var attr = mob.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (attr != null) attr.setBaseValue(maxHP);
            mob.setHealth((float) maxHP);
            if ("baby_zombie".equals(sourceCard.getMobId()) && entity instanceof net.minecraft.entity.mob.ZombieEntity z) {
                z.setBaby(true);
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
    // TICK
    // ================================================================

    public void tick(ServerWorld world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        if (isDead()) return;
        Entity entity = getEntity(world);
        if (entity == null || !entity.isAlive()) { markDead(world); return; }
        if (entity instanceof LivingEntity l && l.getHealth() <= 0) { markDead(world); return; }

        if (attackCooldownRemaining > 0) attackCooldownRemaining--;

        switch (state) {
            case ADVANCING -> tickAdvancing(world, entity, allMobs, structures);
            case FIGHTING -> tickFighting(world, entity, allMobs, structures);
            case RETREATING -> tickRetreating(world, entity, allMobs);
            default -> {}
        }

        if (laneBoundsSet) enforceLaneBounds(entity);

        // Stuck detection
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
    }

    private void tickAdvancing(ServerWorld world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        GameConfig cfg = GameConfig.get();
        if (attackDamage > 0) {
            ArenaMob enemy = findNearestEnemy(world, entity, allMobs, cfg.mobAggroRange);
            if (enemy != null) {
                targetEntityId = enemy.getEntityId();
                targetStructure = null;
                state = MobState.FIGHTING;
                return;
            }
            // Use much larger range for structure detection (structures are static, always visible)
            double structureAggroRange = Math.max(cfg.mobAggroRange, 20.0);
            ArenaStructure struct = findNearestEnemyStructure(entity, structures, structureAggroRange);
            if (struct != null) {
                double dist = entity.getBlockPos().getSquaredDistance(struct.getPosition());
                // Attack if within attack approach range (close enough to start fighting)
                if (dist <= 10.0 * 10.0) {
                    targetStructure = struct;
                    targetEntityId = null;
                    state = MobState.FIGHTING;
                    return;
                }
            }
        } else {
            // Non-attacking mob: check if there's an enemy structure nearby and STOP
            // instead of walking through it. The mob should not pass undamaged structures.
            ArenaStructure blockingStruct = findNearestEnemyStructure(entity, structures, 5.0);
            if (blockingStruct != null) {
                Vec3d sPos = Vec3d.ofCenter(blockingStruct.getPosition());
                double dist = hDist(entity.getPos(), sPos);
                if (dist <= 3.5) {
                    // Close enough to the structure - stop and idle here
                    // Face the structure
                    double dx = sPos.x - entity.getX(), dz = sPos.z - entity.getZ();
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > 0.01) faceDir(entity, dx / d, dz / d);
                    return; // Don't move further
                }
            }
        }
        moveTowardWaypoint(entity);

        // End of waypoints → always seek nearest enemy structure
        if (waypoints != null && currentWaypointIndex >= waypoints.size()) {
            ArenaStructure nearestStruct = findNearestEnemyStructure(entity, structures, 100.0);
            if (nearestStruct != null && attackDamage > 0) {
                targetStructure = nearestStruct;
                targetEntityId = null;
                state = MobState.FIGHTING;
            } else if (nearestStruct != null) {
                // Non-attacking mob at end of waypoints - just stop near structure
                Vec3d sPos = Vec3d.ofCenter(nearestStruct.getPosition());
                double dist = hDist(entity.getPos(), sPos);
                if (dist > 3.5) {
                    moveToward(entity, sPos);
                }
            }
        }
    }

    private void tickFighting(ServerWorld world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        double atkRange = 2.5;
        GameConfig cfg = GameConfig.get();

        if (targetEntityId != null) {
            ArenaMob target = findMobByEntityId(allMobs, targetEntityId);
            if (target == null || target.isDead()) { targetEntityId = null; state = MobState.ADVANCING; return; }
            Entity tEnt = target.getEntity(world);
            if (tEnt == null) { targetEntityId = null; state = MobState.ADVANCING; return; }

            double dist = entity.squaredDistanceTo(tEnt);
            if (dist > atkRange * atkRange) {
                moveToward(entity, tEnt.getPos());
            } else if (attackCooldownRemaining <= 0) {
                performAttack(entity, target, world);
                attackCooldownRemaining = attackCooldown;
            }
            faceEntity(entity, tEnt);

        } else if (targetStructure != null) {
            if (targetStructure.isDestroyed()) { targetStructure = null; state = MobState.ADVANCING; return; }
            Vec3d sPos = Vec3d.ofCenter(targetStructure.getPosition());
            double dist = entity.getPos().squaredDistanceTo(sPos);
            // Structures are multi-block, use wider attack range
            double structRange = atkRange + 2.5;
            if (dist > structRange * structRange) {
                moveToward(entity, sPos);
            } else if (attackCooldownRemaining <= 0) {
                performStructureAttack(entity, targetStructure, world);
                attackCooldownRemaining = attackCooldown;
            }
        } else {
            ArenaMob enemy = findNearestEnemy(world, entity, allMobs, cfg.mobAggroRange);
            if (enemy != null) { targetEntityId = enemy.getEntityId(); return; }
            // Wide search for structures when mob has no target
            ArenaStructure struct = findNearestEnemyStructure(entity, structures, 100.0);
            if (struct != null) { targetStructure = struct; return; }
            state = MobState.ADVANCING;
        }
    }

    private void tickRetreating(ServerWorld world, Entity entity, List<ArenaMob> allMobs) {
        if (entity.getBlockPos().getSquaredDistance(startSlotPos) <= 4.0) { state = MobState.IDLE; return; }
        if (attackDamage > 0) {
            ArenaMob nearby = findNearestEnemy(world, entity, allMobs, GameConfig.get().mobAggroRange * 0.5);
            if (nearby != null) {
                Entity ne = nearby.getEntity(world);
                if (ne != null && entity.squaredDistanceTo(ne) <= 6.25 && attackCooldownRemaining <= 0) {
                    performAttack(entity, nearby, world);
                    attackCooldownRemaining = attackCooldown;
                }
            }
        }
        moveToward(entity, Vec3d.ofCenter(startSlotPos));
    }

    // ================================================================
    // COMBAT
    // ================================================================

    private void performAttack(Entity attacker, ArenaMob defender, ServerWorld world) {
        if (defender.isDead() || attackDamage <= 0) return;
        Entity dEnt = defender.getEntity(world);
        if (dEnt == null) return;

        if (attacker instanceof LivingEntity la) la.swingHand(la.getActiveHand());

        float dmg = (float) attackDamage;
        defender.takeDamage(dmg, world);

        // Knockback within lane bounds
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

        // FIX 9: Spawn floating damage number
        spawnDamageNumber(world, dEnt.getPos().add(0, dEnt.getHeight() + 0.5, 0), dmg);

        playAttackSound(world, attacker.getPos());
    }

    private void performStructureAttack(Entity attacker, ArenaStructure structure, ServerWorld world) {
        if (structure.isDestroyed() || attackDamage <= 0) return;
        if (attacker instanceof LivingEntity la) la.swingHand(la.getActiveHand());

        float dmg = (float) attackDamage;
        structure.damage(dmg, world);

        Vec3d sp = Vec3d.ofCenter(structure.getPosition()).add(0, 1, 0);
        world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, sp.x, sp.y, sp.z, 3, 0.5, 0.3, 0.5, 0.1);
        world.spawnParticles(ParticleTypes.SMOKE, sp.x, sp.y, sp.z, 3, 0.5, 0.5, 0.5, 0.02);
        world.playSound(null, sp.x, sp.y, sp.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.0f, 0.7f);

        // FIX 9: Spawn floating damage number on structure
        spawnDamageNumber(world, sp.add(0, 1.5, 0), dmg);
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
            living.setHealth(newHP);
            living.hurtTime = 10;
            living.maxHurtTime = 10;
            if (newHP <= 0) markDead(world);
        }
    }

    private void markDead(ServerWorld world) {
        if (markedDead) return;
        markedDead = true;
        state = MobState.DEAD;
        Entity e = getEntity(world);
        if (e != null) {
            world.spawnParticles(ParticleTypes.SOUL, e.getX(), e.getY() + 0.5, e.getZ(), 10, 0.3, 0.5, 0.3, 0.05);
            world.spawnParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + 0.5, e.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
            world.spawnParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 0.5, e.getZ(), 5, 0.2, 0.3, 0.2, 0.03);
            world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_GENERIC_DEATH, SoundCategory.HOSTILE, 1.0f, 0.8f + world.getRandom().nextFloat() * 0.4f);
            if (e instanceof LivingEntity l) { l.setInvulnerable(false); l.kill(); } else e.discard();
        }
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
            double effectiveMinX = laneMinX + 0.3;
            double effectiveMaxX = laneMaxX + 0.7;
            // FIX 2: Expand X bounds toward target (structure, waypoint, or move target)
            if (targetStructure != null) {
                double structX = targetStructure.getPosition().getX() + 0.5;
                effectiveMinX = Math.min(effectiveMinX, structX - 3.0);
                effectiveMaxX = Math.max(effectiveMaxX, structX + 3.0);
            }
            // Also expand toward current movement target
            effectiveMinX = Math.min(effectiveMinX, target.x - 1.0);
            effectiveMaxX = Math.max(effectiveMaxX, target.x + 1.0);
            newX = MathHelper.clamp(newX, effectiveMinX, effectiveMaxX);
            newZ = MathHelper.clamp(newZ, laneMinZ + 0.3, laneMaxZ + 0.7);
        }
        entity.requestTeleport(newX, cur.y, newZ);
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
        // FIX 2: When fighting or advancing toward a structure (especially throne),
        // fully expand X bounds to allow reaching the structure
        boolean approachingStructure = (targetStructure != null &&
                (state == MobState.FIGHTING || state == MobState.ADVANCING));
        double effectiveMinX = laneMinX + 0.3;
        double effectiveMaxX = laneMaxX + 0.7;
        if (approachingStructure) {
            // Fully expand X bounds to reach the target structure
            double structX = targetStructure.getPosition().getX() + 0.5;
            effectiveMinX = Math.min(effectiveMinX, structX - 3.0);
            effectiveMaxX = Math.max(effectiveMaxX, structX + 3.0);
        }
        // Also expand when following waypoints that lead outside lane (e.g. toward throne)
        if (state == MobState.ADVANCING && waypoints != null && currentWaypointIndex < waypoints.size()) {
            BlockPos wp = waypoints.get(currentWaypointIndex);
            effectiveMinX = Math.min(effectiveMinX, wp.getX() - 1.0);
            effectiveMaxX = Math.max(effectiveMaxX, wp.getX() + 2.0);
        }
        double x = MathHelper.clamp(entity.getX(), effectiveMinX, effectiveMaxX);
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
        ArenaStructure nearestTower = null;
        ArenaStructure nearestThrone = null;
        double bestTower = range * range;
        double bestThrone = range * range;
        Vec3d selfPos = self.getPos();
        for (ArenaStructure s : structures) {
            if (s.getOwner() == team || s.isDestroyed()) continue;

            // FIX 1: Mobs on a lane should only target structures on their own lane
            // Towers have an associated lane; center lane mobs should NEVER target side towers
            if (s.getType() == ArenaStructure.StructureType.TOWER) {
                Lane.LaneId towerLane = s.getAssociatedLane();
                if (towerLane != null && towerLane != this.lane) {
                    // This tower is on a different lane - skip it
                    // (Towers cover their lane + center for SHOOTING, but mobs shouldn't WALK to them)
                    continue;
                }
            }

            Vec3d sPos = Vec3d.ofCenter(s.getPosition());
            double dx = selfPos.x - sPos.x;
            double dz = selfPos.z - sPos.z;
            double d = dx * dx + dz * dz;
            if (s.getType() == ArenaStructure.StructureType.TOWER) {
                if (d < bestTower) { bestTower = d; nearestTower = s; }
            } else {
                if (d < bestThrone) { bestThrone = d; nearestThrone = s; }
            }
        }
        // Prioritize towers over thrones (tower must be destroyed before attacking throne)
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
    }

    private double hDist(Vec3d a, Vec3d b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * FIX 9: Spawn a floating damage number as an armor stand that despawns after ~1s.
     * The number floats upward and disappears.
     */
    public static void spawnDamageNumber(ServerWorld world, Vec3d pos, double damage) {
        // Small random offset so overlapping hits are visible
        double ox = (world.getRandom().nextDouble() - 0.5) * 0.5;
        double oz = (world.getRandom().nextDouble() - 0.5) * 0.5;

        ArmorStandEntity marker = new ArmorStandEntity(world, pos.x + ox, pos.y, pos.z + oz);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.setCustomNameVisible(true);
        marker.setSilent(true);
        marker.setSmall(true);
        marker.setMarker(true);
        marker.addCommandTag("arenaclash_dmg_number");

        // Color based on damage amount
        String color;
        if (damage >= 15) color = "\u00A7c\u00A7l"; // Red bold for big hits
        else if (damage >= 8) color = "\u00A76";      // Orange
        else if (damage >= 4) color = "\u00A7e";       // Yellow
        else color = "\u00A7f";                         // White

        String dmgText = String.format("%.1f", damage);
        if (damage == Math.floor(damage)) dmgText = String.format("%.0f", damage);
        marker.setCustomName(Text.literal(color + "-" + dmgText + " \u2764"));

        world.spawnEntity(marker);

        // Schedule despawn after 25 ticks (~1.25 seconds), with float-up animation
        UUID markerId = marker.getUuid();
        // Use a simple approach: schedule removal via the marker's age
        marker.age = -25; // Negative age so it will be killed when it reaches positive
    }

    /**
     * Static tick method to animate and clean up damage numbers in the world.
     * Called from ArenaManager.tickBattle().
     */
    public static void tickDamageNumbers(ServerWorld world) {
        List<ArmorStandEntity> toRemove = new ArrayList<>();
        for (Entity e : world.iterateEntities()) {
            if (e instanceof ArmorStandEntity as && e.getCommandTags().contains("arenaclash_dmg_number")) {
                // Float upward
                e.requestTeleport(e.getX(), e.getY() + 0.04, e.getZ());
                // Despawn after being alive for a while
                if (e.age > 0) {
                    toRemove.add(as);
                }
            }
        }
        toRemove.forEach(Entity::discard);
    }
}
