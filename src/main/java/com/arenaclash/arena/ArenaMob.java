package com.arenaclash.arena;

import com.arenaclash.ai.CombatSystem;
import com.arenaclash.card.MobCard;
import com.arenaclash.card.MobCardDefinition;
import com.arenaclash.config.GameConfig;
import com.arenaclash.game.TeamSide;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/**
 * Controls an arena mob with custom pathfinding and combat.
 *
 * Design:
 * - Entity HP is the source of truth (fire, fall dmg etc. work naturally)
 * - Movement via requestTeleport() each tick (works with noAI, syncs to clients)
 * - AI disabled to prevent vanilla wandering/fighting
 */
public class ArenaMob {
    public enum MobState {
        IDLE, ADVANCING, FIGHTING, RETREATING, DEAD
    }

    private final UUID ownerId;
    private final TeamSide team;
    private final MobCard sourceCard;
    private final Lane.LaneId lane;
    private final BlockPos startSlotPos;

    private UUID entityId;
    private MobState state = MobState.IDLE;

    private final double maxHP;
    private final double moveSpeed;      // blocks per second
    private final double attackDamage;
    private final int attackCooldown;
    private int attackCooldownRemaining = 0;

    private List<BlockPos> waypoints;
    private int currentWaypointIndex = 0;

    private UUID targetEntityId;
    private ArenaStructure targetStructure;
    private boolean markedDead = false;

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

    public double getCurrentHP(ServerWorld world) {
        Entity e = getEntity(world);
        if (e instanceof LivingEntity living) return living.getHealth();
        return 0;
    }

    public boolean isDead() { return markedDead || state == MobState.DEAD; }

    /**
     * Spawn the entity with custom HP, AI disabled, tagged for identification.
     */
    public void spawn(ServerWorld world, BlockPos pos) {
        MobCardDefinition def = sourceCard.getDefinition();
        if (def == null) return;

        Entity entity = def.entityType().create(world);
        if (entity == null) return;

        entity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);

        if (entity instanceof MobEntity mobEntity) {
            mobEntity.setAiDisabled(true);
            mobEntity.setPersistent();
            var attr = mobEntity.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (attr != null) attr.setBaseValue(maxHP);
            mobEntity.setHealth((float) maxHP);

            // Fix 3: Set baby flag for baby_zombie cards
            if ("baby_zombie".equals(sourceCard.getMobId()) && entity instanceof net.minecraft.entity.mob.ZombieEntity zombie) {
                zombie.setBaby(true);
            }
        }

        entity.addCommandTag("arenaclash_mob");
        entity.addCommandTag("team_" + team.name());
        entity.addCommandTag("lane_" + lane.name());
        entity.setCustomNameVisible(true);
        updateEntityName(entity, (float) maxHP);

        world.spawnEntity(entity);
        this.entityId = entity.getUuid();
        this.state = MobState.IDLE;
    }

    public void startAdvancing(List<BlockPos> waypoints) {
        this.waypoints = waypoints;
        this.currentWaypointIndex = 0;
        this.state = MobState.ADVANCING;
    }

    public void startRetreating() {
        if (isDead()) return;
        this.state = MobState.RETREATING;
    }

    /**
     * Main tick - called every server tick during battle.
     */
    public void tick(ServerWorld world, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        if (isDead()) return;

        Entity entity = getEntity(world);
        if (entity == null || !entity.isAlive()) {
            markDead();
            return;
        }

        // Check entity died from env damage (fire, cactus, etc.)
        if (entity instanceof LivingEntity living && living.getHealth() <= 0) {
            markDead();
            return;
        }

        if (attackCooldownRemaining > 0) attackCooldownRemaining--;

        switch (state) {
            case ADVANCING -> tickAdvancing(world, entity, allMobs, structures);
            case FIGHTING -> tickFighting(world, entity, allMobs, structures);
            case RETREATING -> tickRetreating(world, entity, allMobs);
            default -> {}
        }

        // Update nametag with real entity HP
        if (entity instanceof LivingEntity living) {
            updateEntityName(entity, living.getHealth());
        }
    }

    private void tickAdvancing(ServerWorld world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        GameConfig cfg = GameConfig.get();

        // Fix 4: Only engage enemies if this mob can deal damage
        if (attackDamage > 0) {
            ArenaMob nearestEnemy = findNearestEnemy(world, entity, allMobs, cfg.mobAggroRange);
            if (nearestEnemy != null) {
                targetEntityId = nearestEnemy.getEntityId();
                targetStructure = null;
                state = MobState.FIGHTING;
                return;
            }

            ArenaStructure nearestStruct = findNearestEnemyStructure(entity, structures, cfg.mobAggroRange);
            if (nearestStruct != null) {
                targetStructure = nearestStruct;
                targetEntityId = null;
                state = MobState.FIGHTING;
                return;
            }
        }

        moveTowardWaypoint(entity);
    }

    private void tickFighting(ServerWorld world, Entity entity, List<ArenaMob> allMobs, List<ArenaStructure> structures) {
        double attackRange = 2.5;

        if (targetEntityId != null) {
            ArenaMob targetMob = findMobByEntityId(allMobs, targetEntityId);
            if (targetMob == null || targetMob.isDead()) {
                targetEntityId = null;
                state = MobState.ADVANCING;
                return;
            }
            Entity targetEntity = targetMob.getEntity(world);
            if (targetEntity == null) {
                targetEntityId = null;
                state = MobState.ADVANCING;
                return;
            }

            double dist = entity.squaredDistanceTo(targetEntity);
            if (dist > attackRange * attackRange) {
                moveToward(entity, targetEntity.getPos());
            } else if (attackCooldownRemaining <= 0) {
                CombatSystem.performAttack(this, targetMob, world);
                attackCooldownRemaining = attackCooldown;
            }
        } else if (targetStructure != null) {
            if (targetStructure.isDestroyed()) {
                targetStructure = null;
                state = MobState.ADVANCING;
                return;
            }
            double dist = entity.getBlockPos().getSquaredDistance(targetStructure.getPosition());
            if (dist > attackRange * attackRange) {
                moveToward(entity, Vec3d.ofCenter(targetStructure.getPosition()));
            } else if (attackCooldownRemaining <= 0) {
                CombatSystem.attackStructure(this, targetStructure, world);
                attackCooldownRemaining = attackCooldown;
            }
        } else {
            state = MobState.ADVANCING;
        }
    }

    private void tickRetreating(ServerWorld world, Entity entity, List<ArenaMob> allMobs) {
        double distToHome = entity.getBlockPos().getSquaredDistance(startSlotPos);
        if (distToHome <= 2.0 * 2.0) {
            state = MobState.IDLE;
            return;
        }
        moveToward(entity, Vec3d.ofCenter(startSlotPos));
    }

    /**
     * Deal damage by setting entity health directly.
     */
    public void takeDamage(double amount, ServerWorld world) {
        Entity e = getEntity(world);
        if (e instanceof LivingEntity living) {
            float newHealth = Math.max(0, living.getHealth() - (float) amount);
            living.setHealth(newHealth);
            if (newHealth <= 0) {
                markDead();
                living.kill();
            }
        }
    }

    private void markDead() {
        markedDead = true;
        state = MobState.DEAD;
    }

    public Entity getEntity(ServerWorld world) {
        if (entityId == null) return null;
        return world.getEntity(entityId);
    }

    public void removeEntity(ServerWorld world) {
        Entity e = getEntity(world);
        if (e != null) e.discard();
    }

    // === Movement ===

    private void moveTowardWaypoint(Entity entity) {
        if (waypoints == null || currentWaypointIndex >= waypoints.size()) {
            // Reached end of waypoints
            // Fix 4: If this mob can't attack, become idle (prevents infinite battle)
            if (attackDamage <= 0) {
                state = MobState.IDLE;
            }
            return;
        }

        BlockPos target = waypoints.get(currentWaypointIndex);
        Vec3d targetVec = Vec3d.ofCenter(target);
        double dx = entity.getX() - targetVec.x;
        double dz = entity.getZ() - targetVec.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= 1.2) {
            currentWaypointIndex++;
            if (currentWaypointIndex >= waypoints.size()) return;
            target = waypoints.get(currentWaypointIndex);
        }
        moveToward(entity, Vec3d.ofCenter(target));
    }

    /**
     * Move entity via requestTeleport — works with noAI and syncs to all clients.
     */
    private void moveToward(Entity entity, Vec3d target) {
        Vec3d current = entity.getPos();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.05) return;

        double speed = moveSpeed / 20.0; // blocks per tick
        double step = Math.min(speed, horizontalDist);
        double nx = dx / horizontalDist;
        double nz = dz / horizontalDist;

        double newX = current.x + nx * step;
        double newZ = current.z + nz * step;

        // requestTeleport sends position to all tracking clients
        entity.requestTeleport(newX, current.y, newZ);

        // Face movement direction
        float yaw = (float) (Math.atan2(-nx, nz) * (180.0 / Math.PI));
        entity.setYaw(yaw);
        if (entity instanceof LivingEntity living) {
            living.setHeadYaw(yaw);
            living.setBodyYaw(yaw);
        }
    }

    // === Targeting ===

    private ArenaMob findNearestEnemy(ServerWorld world, Entity self, List<ArenaMob> allMobs, double range) {
        ArenaMob nearest = null;
        double nearestDist = range * range;
        for (ArenaMob mob : allMobs) {
            if (mob.getTeam() == this.team || mob.isDead()) continue;
            Entity other = mob.getEntity(world);
            if (other == null) continue;
            double dist = self.squaredDistanceTo(other);
            if (dist < nearestDist) { nearestDist = dist; nearest = mob; }
        }
        return nearest;
    }

    private ArenaStructure findNearestEnemyStructure(Entity self, List<ArenaStructure> structures, double range) {
        ArenaStructure nearest = null;
        double nearestDist = range * range;
        for (ArenaStructure s : structures) {
            if (s.getOwner() == this.team || s.isDestroyed()) continue;
            double dist = self.getBlockPos().getSquaredDistance(s.getPosition());
            if (dist < nearestDist) { nearestDist = dist; nearest = s; }
        }
        return nearest;
    }

    private ArenaMob findMobByEntityId(List<ArenaMob> mobs, UUID entityId) {
        for (ArenaMob mob : mobs) {
            if (entityId.equals(mob.getEntityId())) return mob;
        }
        return null;
    }

    private void updateEntityName(Entity entity, float hp) {
        MobCardDefinition def = sourceCard.getDefinition();
        if (def == null) return;
        String name = def.displayName() + " Lv." + sourceCard.getLevel()
                + " " + (int) hp + "/" + (int) maxHP;
        Formatting color = team == TeamSide.PLAYER1 ? Formatting.BLUE : Formatting.RED;
        entity.setCustomName(Text.literal(name).formatted(color));
    }
}
