# Arena Clash — Fabric Mod for Minecraft 1.21.1

A Clash Royale-style 1v1 PvP arena mod where players gather resources in survival, then battle by deploying mobs onto 3-lane arena.

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16.5+
- Fabric API 0.103.0+
- Java 21+
- Fantasy Library (included/bundled)

## Build

```bash
./gradlew build
```

JAR output: `build/libs/arena-clash-0.1.0.jar`

## Setup

1. Build the mod and place the JAR in the server's `mods/` folder
2. Also install Fabric API on both server and clients
3. Players need the mod installed client-side for GUI, HUD, and keybinds

## Commands (requires OP level 2)

| Command | Description |
|---|---|
| `/ac start <player1> <player2>` | Start a new game |
| `/ac reset` | Reset game, delete all worlds |
| `/ac status` | Show current game state |
| `/ac cards` | List your card inventory |
| `/ac bell` | Ring the bell (ready/retreat) |
| `/ac givecard <player> <mobId> [count]` | Debug: give cards |
| `/ac config show` | Show current config |
| `/ac config dayDuration <ticks>` | Set day duration |
| `/ac config prepTime <ticks>` | Set preparation time |
| `/ac config seed <seed>` | Set world seed |

## Keybinds

| Key | Action |
|---|---|
| `J` | Open card inventory / deployment screen |
| `B` | Ring bell (ready during prep / retreat during battle) |

## Game Flow

### Round Structure (3 rounds)
1. **Survival Phase** — Players are teleported to separate identical overworld copies. Kill mobs to earn cards. Ores drop smelted ingots. XP is disabled. Accelerated day cycle (5 min = 1 day).
   - Round 1: 1 day, Round 2: 2 days, Round 3: 3 days
2. **Preparation Phase** — Players teleported to arena. Place mob cards on deployment slots (2×2 per lane, 3 lanes). Press `J` to open deployment GUI. Ring bell (`B`) when ready, or wait for timer (3 min).
3. **Battle Phase** — Mobs spawn and advance along lanes toward enemy throne. Custom pathfinding (A* waypoints) and combat system. Towers shoot arrows at nearby enemies. Throne deals AoE damage.
4. **Round End** — Dead mobs generate XP. Retreated mobs return to cards. Damage stats accumulated.

### Victory Conditions (priority order)
1. Throne destroyed → instant win
2. After 3 rounds: most throne damage dealt
3. Tie: most enemy towers destroyed
4. Tie: most tower damage dealt
5. Full tie → draw

## Mob Card System

- Kill any registered mob in survival → get a level 1 card
- Card appears with totem-like animation (MVP: chat message)
- Cards stored in separate GUI (not vanilla inventory)
- During preparation, place cards on lane slots

### Registered Mobs (~40 types)
**Undead:** Zombie, Skeleton, Husk, Stray, Drowned, Phantom, Wither Skeleton, Zombified Piglin
**Arthropod:** Spider, Cave Spider, Silverfish, Endermite
**Nether:** Blaze, Ghast, Piglin, Piglin Brute, Hoglin, Magma Cube
**End:** Enderman, Shulker
**Illager:** Vindicator, Pillager, Evoker, Ravager, Vex
**Golem:** Iron Golem, Snow Golem
**Animal:** Wolf, Bee, Llama, Goat, Chicken, Cow, Pig, Sheep
**Special:** Creeper, Witch, Slime
**Boss:** Warden, Wither, Elder Guardian

## Arena Layout

```
          [Throne P2]          Z+
         /    |    \
    [Tower] [   ] [Tower]
       |      |      |
    Lane L  Lane C  Lane R
       |      |      |
    =======DIVIDER=======    ← center Z
       |      |      |
    Lane L  Lane C  Lane R
       |      |      |
    [Tower] [   ] [Tower]
         \    |    /
          [Throne P1]          Z-
```

- Lane length: ~36 blocks
- Lane width: 5 blocks
- Lane separation: 15 blocks between centers
- Towers cover their lane + center lane
- Throne has AoE melee damage

## Configuration

All values tunable via `/ac config` or `config/arenaclash.json`:

- Day duration, preparation time, round counts
- Arena dimensions, lane length/width/separation
- Throne/tower HP, damage, range, cooldowns
- Knockback strength, mob aggro range
- Game seed

## Architecture

```
com.arenaclash/
├── ArenaClash.java          # Main entry point
├── ai/
│   ├── CombatSystem.java    # Custom melee/structure combat
│   └── LanePathfinder.java  # A* waypoint pathfinding
├── arena/
│   ├── ArenaBuilder.java    # Block structure generation
│   ├── ArenaManager.java    # Battle management
│   ├── ArenaMob.java        # Mob controller/wrapper
│   ├── ArenaStructure.java  # Tower/throne with HP
│   └── Lane.java            # Lane + deployment slots
├── card/
│   ├── CardInventory.java   # Per-player card storage
│   ├── MobCard.java         # Card instance
│   ├── MobCardDefinition.java # Card template/stats
│   └── MobCardRegistry.java # All mob definitions
├── command/
│   └── GameCommands.java    # /ac commands
├── config/
│   └── GameConfig.java      # Tunable config
├── event/
│   └── GameEventHandlers.java # Server events
├── game/
│   ├── GameManager.java     # Central state machine
│   ├── GamePhase.java       # Phase enum
│   ├── PlayerGameData.java  # Per-player data
│   └── TeamSide.java        # Team enum
├── mixin/
│   ├── ExperienceOrbMixin.java  # Prevent XP collection
│   ├── OreBlockMixin.java       # Ore → ingot drops
│   └── ServerWorldMixin.java    # Prevent XP spawning
├── network/
│   └── NetworkHandler.java  # All packets
└── world/
    └── WorldManager.java    # Fantasy runtime worlds

client/
├── ArenaClashClient.java    # Client entry point
├── gui/
│   ├── CardScreen.java      # Card inventory viewer
│   └── DeploymentScreen.java # Mob placement GUI
└── render/
    └── GameHudRenderer.java # HUD overlay
```

## MVP Scope

**Included:**
- 7 runtime worlds (Arena + 2×Overworld/Nether/End)
- Full game cycle: Survival → Prep → Battle × 3 rounds
- Card drops from mob kills, card inventory
- Custom A* pathfinding + custom combat
- Towers and thrones as block structures with HP + degradation
- All victory conditions
- Config system
- Client GUI + HUD

**Deferred (post-MVP):**
- Card merging/upgrading (requires workshop tables)
- Weapon/armor card equipment (requires workshop)
- Workshop table system
- Skill tree / XP spending
- Spells/abilities
- Player-built tower defense
- Bell retreat mechanic
- 3D card rendering
- Team vs team / FFA modes
