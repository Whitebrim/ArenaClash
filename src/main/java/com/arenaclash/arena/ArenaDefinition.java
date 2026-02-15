package com.arenaclash.arena;

import com.arenaclash.game.TeamSide;
import com.google.gson.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Data-driven arena definition loaded from JSON.
 *
 * The arena builder places colored markers in the world using /ac arena commands,
 * then exports to arena_definition.json. On game start, this definition is loaded
 * and used for all positioning, pathing, and zone enforcement.
 *
 * Structure:
 * - Each lane has ordered waypoints (curved paths supported!)
 * - Each lane has deployment zones per team
 * - Lane boundaries are defined as polygonal zones
 * - Tower and throne positions are explicit with schematics
 * - Player spawn points per team
 * - Build zones per team (where players can place blocks)
 */
public class ArenaDefinition {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaClash-ArenaDef");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // === Core positions ===
    private Vec3d player1Spawn;
    private Vec3d player2Spawn;

    // === Structures ===
    private StructureDef player1Throne;
    private StructureDef player2Throne;
    private final List<StructureDef> player1Towers = new ArrayList<>();
    private final List<StructureDef> player2Towers = new ArrayList<>();

    // === Lanes ===
    private final Map<Lane.LaneId, LaneDef> lanes = new EnumMap<>(Lane.LaneId.class);

    // === Build zones ===
    private final List<ZoneDef> player1BuildZones = new ArrayList<>();
    private final List<ZoneDef> player2BuildZones = new ArrayList<>();

    // === Bell positions ===
    private BlockPos player1BellPos;
    private BlockPos player2BellPos;

    // === Arena bounds ===
    private BlockPos boundsMin;
    private BlockPos boundsMax;

    // === Template world name ===
    private String templateWorldName = "arena_template";

    // ========================================================================
    // DATA CLASSES
    // ========================================================================

    public static class StructureDef {
        public BlockPos position;
        public BlockPos boundsMin;  // Bounding box for the structure
        public BlockPos boundsMax;
        public String schematicName; // Optional schematic to paste
        public double hp;
        public double attackRange;
        public double attackDamage;
        public int attackCooldown;
        public Lane.LaneId associatedLane; // For towers

        public StructureDef() {}
        public StructureDef(BlockPos pos, BlockPos bMin, BlockPos bMax, double hp) {
            this.position = pos;
            this.boundsMin = bMin;
            this.boundsMax = bMax;
            this.hp = hp;
        }
    }

    public static class LaneDef {
        public Lane.LaneId id;
        public List<Vec3d> waypointsP1toP2 = new ArrayList<>(); // Ordered waypoints from P1 side to P2 side
        public List<BlockPos> deploymentSlotsP1 = new ArrayList<>();
        public List<BlockPos> deploymentSlotsP2 = new ArrayList<>();
        public List<Vec3d> boundaryPolygon = new ArrayList<>(); // Polygon defining lane boundaries
        public int width = 5; // Approximate width for boundary checking

        public LaneDef() {}
        public LaneDef(Lane.LaneId id) { this.id = id; }
    }

    public static class ZoneDef {
        public BlockPos min;
        public BlockPos max;
        public String name;

        public ZoneDef() {}
        public ZoneDef(BlockPos min, BlockPos max, String name) {
            this.min = min;
            this.max = max;
            this.name = name;
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }
    }

    // ========================================================================
    // GETTERS
    // ========================================================================

    public Vec3d getPlayerSpawn(TeamSide team) {
        return team == TeamSide.PLAYER1 ? player1Spawn : player2Spawn;
    }

    public StructureDef getThrone(TeamSide team) {
        return team == TeamSide.PLAYER1 ? player1Throne : player2Throne;
    }

    public List<StructureDef> getTowers(TeamSide team) {
        return team == TeamSide.PLAYER1 ? player1Towers : player2Towers;
    }

    public Map<Lane.LaneId, LaneDef> getLanes() { return lanes; }

    public LaneDef getLane(Lane.LaneId id) { return lanes.get(id); }

    public BlockPos getBellPos(TeamSide team) {
        return team == TeamSide.PLAYER1 ? player1BellPos : player2BellPos;
    }

    public List<ZoneDef> getBuildZones(TeamSide team) {
        return team == TeamSide.PLAYER1 ? player1BuildZones : player2BuildZones;
    }

    public BlockPos getBoundsMin() { return boundsMin; }
    public BlockPos getBoundsMax() { return boundsMax; }
    public String getTemplateWorldName() { return templateWorldName; }

    // ========================================================================
    // SETTERS (used by arena setup commands)
    // ========================================================================

    public void setPlayerSpawn(TeamSide team, Vec3d pos) {
        if (team == TeamSide.PLAYER1) player1Spawn = pos;
        else player2Spawn = pos;
    }

    public void setThrone(TeamSide team, StructureDef def) {
        if (team == TeamSide.PLAYER1) player1Throne = def;
        else player2Throne = def;
    }

    public void addTower(TeamSide team, StructureDef def) {
        (team == TeamSide.PLAYER1 ? player1Towers : player2Towers).add(def);
    }

    public void setBellPos(TeamSide team, BlockPos pos) {
        if (team == TeamSide.PLAYER1) player1BellPos = pos;
        else player2BellPos = pos;
    }

    public void setBounds(BlockPos min, BlockPos max) {
        this.boundsMin = min;
        this.boundsMax = max;
    }

    public void addBuildZone(TeamSide team, ZoneDef zone) {
        (team == TeamSide.PLAYER1 ? player1BuildZones : player2BuildZones).add(zone);
    }

    public void setTemplateWorldName(String name) {
        this.templateWorldName = name;
    }

    // ========================================================================
    // LANE BOUNDARY CHECK
    // ========================================================================

    /**
     * Check if a position is within a lane's boundary.
     * Used to keep mobs from wandering off the path.
     */
    public boolean isWithinLaneBounds(Lane.LaneId laneId, Vec3d pos) {
        LaneDef lane = lanes.get(laneId);
        if (lane == null) return false;

        // Simple approach: check distance to nearest waypoint segment
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < lane.waypointsP1toP2.size() - 1; i++) {
            Vec3d a = lane.waypointsP1toP2.get(i);
            Vec3d b = lane.waypointsP1toP2.get(i + 1);
            double dist = distanceToSegment(pos.x, pos.z, a.x, a.z, b.x, b.z);
            minDist = Math.min(minDist, dist);
        }
        return minDist <= lane.width / 2.0 + 1.0; // Small buffer
    }

    /**
     * Clamp a position to stay within lane boundaries.
     * Returns the closest valid position on the lane.
     */
    public Vec3d clampToLane(Lane.LaneId laneId, Vec3d pos) {
        LaneDef lane = lanes.get(laneId);
        if (lane == null) return pos;

        double halfWidth = lane.width / 2.0;
        Vec3d closestOnPath = pos;
        double minDist = Double.MAX_VALUE;
        Vec3d closestSegPoint = pos;

        for (int i = 0; i < lane.waypointsP1toP2.size() - 1; i++) {
            Vec3d a = lane.waypointsP1toP2.get(i);
            Vec3d b = lane.waypointsP1toP2.get(i + 1);
            Vec3d closest = closestPointOnSegment(pos, a, b);
            double dx = closest.x - pos.x;
            double dz = closest.z - pos.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < minDist) {
                minDist = dist;
                closestSegPoint = closest;
            }
        }

        if (minDist <= halfWidth) {
            return pos; // Already within bounds
        }

        // Push back toward the lane center
        Vec3d dir = new Vec3d(pos.x - closestSegPoint.x, 0, pos.z - closestSegPoint.z);
        double len = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (len < 0.01) return pos;

        return new Vec3d(
            closestSegPoint.x + (dir.x / len) * halfWidth,
            pos.y,
            closestSegPoint.z + (dir.z / len) * halfWidth
        );
    }

    private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 0.001) return Math.sqrt((px - ax) * (px - ax) + (pz - az) * (pz - az));
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / lenSq));
        double projX = ax + t * dx, projZ = az + t * dz;
        return Math.sqrt((px - projX) * (px - projX) + (pz - projZ) * (pz - projZ));
    }

    private static Vec3d closestPointOnSegment(Vec3d p, Vec3d a, Vec3d b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 0.001) return a;
        double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.z - a.z) * dz) / lenSq));
        return new Vec3d(a.x + t * dx, a.y + t * (b.y - a.y), a.z + t * dz);
    }

    // ========================================================================
    // SERIALIZATION
    // ========================================================================

    public void save(Path path) throws IOException {
        JsonObject root = new JsonObject();

        // Spawns
        root.add("player1Spawn", vec3dToJson(player1Spawn));
        root.add("player2Spawn", vec3dToJson(player2Spawn));

        // Thrones
        if (player1Throne != null) root.add("player1Throne", structureToJson(player1Throne));
        if (player2Throne != null) root.add("player2Throne", structureToJson(player2Throne));

        // Towers
        root.add("player1Towers", structureListToJson(player1Towers));
        root.add("player2Towers", structureListToJson(player2Towers));

        // Lanes
        JsonObject lanesJson = new JsonObject();
        for (var entry : lanes.entrySet()) {
            lanesJson.add(entry.getKey().name(), laneToJson(entry.getValue()));
        }
        root.add("lanes", lanesJson);

        // Build zones
        root.add("player1BuildZones", zonesToJson(player1BuildZones));
        root.add("player2BuildZones", zonesToJson(player2BuildZones));

        // Bells
        if (player1BellPos != null) root.add("player1Bell", blockPosToJson(player1BellPos));
        if (player2BellPos != null) root.add("player2Bell", blockPosToJson(player2BellPos));

        // Bounds
        if (boundsMin != null) root.add("boundsMin", blockPosToJson(boundsMin));
        if (boundsMax != null) root.add("boundsMax", blockPosToJson(boundsMax));

        root.addProperty("templateWorldName", templateWorldName);

        Files.writeString(path, GSON.toJson(root));
        LOGGER.info("Saved arena definition to {}", path);
    }

    public static ArenaDefinition load(Path path) throws IOException {
        String json = Files.readString(path);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        ArenaDefinition def = new ArenaDefinition();

        if (root.has("player1Spawn")) def.player1Spawn = jsonToVec3d(root.getAsJsonObject("player1Spawn"));
        if (root.has("player2Spawn")) def.player2Spawn = jsonToVec3d(root.getAsJsonObject("player2Spawn"));
        if (root.has("player1Throne")) def.player1Throne = jsonToStructure(root.getAsJsonObject("player1Throne"));
        if (root.has("player2Throne")) def.player2Throne = jsonToStructure(root.getAsJsonObject("player2Throne"));
        if (root.has("player1Towers")) def.player1Towers.addAll(jsonToStructureList(root.getAsJsonArray("player1Towers")));
        if (root.has("player2Towers")) def.player2Towers.addAll(jsonToStructureList(root.getAsJsonArray("player2Towers")));

        if (root.has("lanes")) {
            JsonObject lanesJson = root.getAsJsonObject("lanes");
            for (var entry : lanesJson.entrySet()) {
                Lane.LaneId id = Lane.LaneId.valueOf(entry.getKey());
                def.lanes.put(id, jsonToLane(id, entry.getValue().getAsJsonObject()));
            }
        }

        if (root.has("player1BuildZones")) def.player1BuildZones.addAll(jsonToZones(root.getAsJsonArray("player1BuildZones")));
        if (root.has("player2BuildZones")) def.player2BuildZones.addAll(jsonToZones(root.getAsJsonArray("player2BuildZones")));
        if (root.has("player1Bell")) def.player1BellPos = jsonToBlockPos(root.getAsJsonObject("player1Bell"));
        if (root.has("player2Bell")) def.player2BellPos = jsonToBlockPos(root.getAsJsonObject("player2Bell"));
        if (root.has("boundsMin")) def.boundsMin = jsonToBlockPos(root.getAsJsonObject("boundsMin"));
        if (root.has("boundsMax")) def.boundsMax = jsonToBlockPos(root.getAsJsonObject("boundsMax"));
        if (root.has("templateWorldName")) def.templateWorldName = root.get("templateWorldName").getAsString();

        LOGGER.info("Loaded arena definition from {} ({} lanes)", path, def.lanes.size());
        return def;
    }

    /**
     * Generate a default arena definition matching the old hardcoded layout.
     * Used as fallback when no JSON file exists.
     */
    public static ArenaDefinition createDefault(int cx, int cz, int y, int laneLength, int laneSep) {
        ArenaDefinition def = new ArenaDefinition();
        int halfLen = laneLength / 2;
        int p1BaseZ = cz - halfLen - 10;
        int p2BaseZ = cz + halfLen + 10;
        int p1DeployZ = cz - halfLen;
        int p2DeployZ = cz + halfLen;

        def.player1Spawn = new Vec3d(cx + 0.5, y + 1, p1BaseZ - 5 + 0.5);
        def.player2Spawn = new Vec3d(cx + 0.5, y + 1, p2BaseZ + 5 + 0.5);

        // Thrones
        def.player1Throne = new StructureDef(new BlockPos(cx, y, p1BaseZ),
                new BlockPos(cx - 2, y, p1BaseZ - 2), new BlockPos(cx + 2, y + 5, p1BaseZ + 2), 200);
        def.player1Throne.attackRange = 6.0;
        def.player1Throne.attackDamage = 5.0;
        def.player1Throne.attackCooldown = 30;

        def.player2Throne = new StructureDef(new BlockPos(cx, y, p2BaseZ),
                new BlockPos(cx - 2, y, p2BaseZ - 2), new BlockPos(cx + 2, y + 5, p2BaseZ + 2), 200);
        def.player2Throne.attackRange = 6.0;
        def.player2Throne.attackDamage = 5.0;
        def.player2Throne.attackCooldown = 30;

        // Towers
        int leftX = cx - laneSep, rightX = cx + laneSep;
        for (int side : new int[]{-1, 1}) {
            int towerX = side < 0 ? leftX : rightX;
            Lane.LaneId assocLane = side < 0 ? Lane.LaneId.LEFT : Lane.LaneId.RIGHT;

            StructureDef t1 = new StructureDef(new BlockPos(towerX, y, p1BaseZ + 3),
                    new BlockPos(towerX - 1, y, p1BaseZ + 2), new BlockPos(towerX + 1, y + 4, p1BaseZ + 4), 100);
            t1.attackRange = 15; t1.attackDamage = 3; t1.attackCooldown = 40;
            t1.associatedLane = assocLane;
            def.player1Towers.add(t1);

            StructureDef t2 = new StructureDef(new BlockPos(towerX, y, p2BaseZ - 3),
                    new BlockPos(towerX - 1, y, p2BaseZ - 4), new BlockPos(towerX + 1, y + 4, p2BaseZ - 2), 100);
            t2.attackRange = 15; t2.attackDamage = 3; t2.attackCooldown = 40;
            t2.associatedLane = assocLane;
            def.player2Towers.add(t2);
        }

        // Lanes with waypoints (straight for default, users can make curved)
        for (Lane.LaneId laneId : Lane.LaneId.values()) {
            int laneX = switch (laneId) {
                case LEFT -> leftX;
                case CENTER -> cx;
                case RIGHT -> rightX;
            };

            LaneDef laneDef = new LaneDef(laneId);
            laneDef.width = 5;

            // Generate waypoints from P1 deploy → P1 tower → center → P2 tower → P2 deploy
            int p1TowerZ = p1BaseZ + 3;
            int p2TowerZ = p2BaseZ - 3;

            // Waypoints P1→P2 (mobs from P1 use these forward, P2 reverses them)
            for (int z = p1DeployZ; z <= p2DeployZ; z += 2) {
                laneDef.waypointsP1toP2.add(new Vec3d(laneX + 0.5, y, z + 0.5));
            }
            // Ensure last waypoint reaches enemy tower/throne area
            laneDef.waypointsP1toP2.add(new Vec3d(laneX + 0.5, y, p2DeployZ + 0.5));

            // Deployment slots 2x2 per team
            laneDef.deploymentSlotsP1.add(new BlockPos(laneX - 1, y, p1DeployZ));
            laneDef.deploymentSlotsP1.add(new BlockPos(laneX, y, p1DeployZ));
            laneDef.deploymentSlotsP1.add(new BlockPos(laneX - 1, y, p1DeployZ + 1));
            laneDef.deploymentSlotsP1.add(new BlockPos(laneX, y, p1DeployZ + 1));

            laneDef.deploymentSlotsP2.add(new BlockPos(laneX - 1, y, p2DeployZ));
            laneDef.deploymentSlotsP2.add(new BlockPos(laneX, y, p2DeployZ));
            laneDef.deploymentSlotsP2.add(new BlockPos(laneX - 1, y, p2DeployZ - 1));
            laneDef.deploymentSlotsP2.add(new BlockPos(laneX, y, p2DeployZ - 1));

            def.lanes.put(laneId, laneDef);
        }

        // Default bell positions (near thrones)
        def.player1BellPos = new BlockPos(cx + 3, y + 1, p1BaseZ);
        def.player2BellPos = new BlockPos(cx + 3, y + 1, p2BaseZ);

        // Bounds
        int arenaHalfWidth = laneSep + 15;
        def.boundsMin = new BlockPos(cx - arenaHalfWidth, y - 2, p1BaseZ - 10);
        def.boundsMax = new BlockPos(cx + arenaHalfWidth, y + 15, p2BaseZ + 10);

        return def;
    }

    // ========================================================================
    // JSON HELPERS
    // ========================================================================

    private static JsonObject vec3dToJson(Vec3d v) {
        if (v == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x); o.addProperty("y", v.y); o.addProperty("z", v.z);
        return o;
    }

    private static Vec3d jsonToVec3d(JsonObject o) {
        return new Vec3d(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
    }

    private static JsonObject blockPosToJson(BlockPos p) {
        if (p == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX()); o.addProperty("y", p.getY()); o.addProperty("z", p.getZ());
        return o;
    }

    private static BlockPos jsonToBlockPos(JsonObject o) {
        return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    private static JsonObject structureToJson(StructureDef s) {
        JsonObject o = new JsonObject();
        o.add("position", blockPosToJson(s.position));
        o.add("boundsMin", blockPosToJson(s.boundsMin));
        o.add("boundsMax", blockPosToJson(s.boundsMax));
        o.addProperty("hp", s.hp);
        if (s.attackRange > 0) o.addProperty("attackRange", s.attackRange);
        if (s.attackDamage > 0) o.addProperty("attackDamage", s.attackDamage);
        if (s.attackCooldown > 0) o.addProperty("attackCooldown", s.attackCooldown);
        if (s.associatedLane != null) o.addProperty("associatedLane", s.associatedLane.name());
        if (s.schematicName != null) o.addProperty("schematic", s.schematicName);
        return o;
    }

    private static StructureDef jsonToStructure(JsonObject o) {
        StructureDef s = new StructureDef();
        s.position = jsonToBlockPos(o.getAsJsonObject("position"));
        s.boundsMin = jsonToBlockPos(o.getAsJsonObject("boundsMin"));
        s.boundsMax = jsonToBlockPos(o.getAsJsonObject("boundsMax"));
        s.hp = o.get("hp").getAsDouble();
        if (o.has("attackRange")) s.attackRange = o.get("attackRange").getAsDouble();
        if (o.has("attackDamage")) s.attackDamage = o.get("attackDamage").getAsDouble();
        if (o.has("attackCooldown")) s.attackCooldown = o.get("attackCooldown").getAsInt();
        if (o.has("associatedLane")) s.associatedLane = Lane.LaneId.valueOf(o.get("associatedLane").getAsString());
        if (o.has("schematic")) s.schematicName = o.get("schematic").getAsString();
        return s;
    }

    private JsonArray structureListToJson(List<StructureDef> list) {
        JsonArray arr = new JsonArray();
        for (StructureDef s : list) arr.add(structureToJson(s));
        return arr;
    }

    private static List<StructureDef> jsonToStructureList(JsonArray arr) {
        List<StructureDef> list = new ArrayList<>();
        for (JsonElement e : arr) list.add(jsonToStructure(e.getAsJsonObject()));
        return list;
    }

    private static JsonObject laneToJson(LaneDef lane) {
        JsonObject o = new JsonObject();
        o.addProperty("id", lane.id.name());
        o.addProperty("width", lane.width);
        JsonArray wp = new JsonArray();
        for (Vec3d v : lane.waypointsP1toP2) wp.add(vec3dToJson(v));
        o.add("waypoints", wp);
        JsonArray dp1 = new JsonArray();
        for (BlockPos p : lane.deploymentSlotsP1) dp1.add(blockPosToJson(p));
        o.add("deployP1", dp1);
        JsonArray dp2 = new JsonArray();
        for (BlockPos p : lane.deploymentSlotsP2) dp2.add(blockPosToJson(p));
        o.add("deployP2", dp2);
        return o;
    }

    private static LaneDef jsonToLane(Lane.LaneId id, JsonObject o) {
        LaneDef lane = new LaneDef(id);
        if (o.has("width")) lane.width = o.get("width").getAsInt();
        if (o.has("waypoints")) {
            for (JsonElement e : o.getAsJsonArray("waypoints"))
                lane.waypointsP1toP2.add(jsonToVec3d(e.getAsJsonObject()));
        }
        if (o.has("deployP1")) {
            for (JsonElement e : o.getAsJsonArray("deployP1"))
                lane.deploymentSlotsP1.add(jsonToBlockPos(e.getAsJsonObject()));
        }
        if (o.has("deployP2")) {
            for (JsonElement e : o.getAsJsonArray("deployP2"))
                lane.deploymentSlotsP2.add(jsonToBlockPos(e.getAsJsonObject()));
        }
        return lane;
    }

    private static JsonArray zonesToJson(List<ZoneDef> zones) {
        JsonArray arr = new JsonArray();
        for (ZoneDef z : zones) {
            JsonObject o = new JsonObject();
            o.add("min", blockPosToJson(z.min));
            o.add("max", blockPosToJson(z.max));
            if (z.name != null) o.addProperty("name", z.name);
            arr.add(o);
        }
        return arr;
    }

    private static List<ZoneDef> jsonToZones(JsonArray arr) {
        List<ZoneDef> list = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            list.add(new ZoneDef(jsonToBlockPos(o.getAsJsonObject("min")),
                    jsonToBlockPos(o.getAsJsonObject("max")),
                    o.has("name") ? o.get("name").getAsString() : null));
        }
        return list;
    }
}
