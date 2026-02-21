# Arena Clash — Fabric Mod for Minecraft 1.21.1

A Clash Royale-style 1v1 PvP arena mod where players gather resources in survival, then battle by deploying mobs onto a 3-lane arena.

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

1. Build the mod and place the JAR in the server's `mods/` folder.
2. Install Fabric API on both server and clients.
3. Players need the mod installed client-side for GUI, HUD, and keybinds.
4. TCP server starts automatically on port 25566 alongside MC server.

## Architecture

Arena Clash uses a **hybrid TCP + MC server** model:

- **TCP connection** — persistent, used for lobby, card sync, phase notifications, chat relay. Stays active even when players are in singleplayer.
- **MC server connection** — only during PREPARATION and BATTLE phases. Players connect to the dedicated server to view the arena.
- **Singleplayer** — during SURVIVAL phase, each player plays in their own auto-generated singleplayer world (same seed for fairness).

```
Title Screen → "Arena Clash" button → ConnectScreen → TCP connect
              ↓
         LOBBY (TCP only, waiting for 2 players)
              ↓  auto-start when 2 connected
         SURVIVAL (singleplayer, TCP sync)
              ↓  timer expires
         PREPARATION (MC server connect, place cards on arena)
              ↓  both ready or timer expires
         BATTLE (MC server, watch mobs fight)
              ↓  all mobs dead or throne destroyed
         ROUND_END → SURVIVAL → ... (repeat for 3 rounds)
              ↓  after final round or throne destroyed
         GAME_OVER (15s results screen → return to singleplayer)
```

## Commands (requires OP level 2)

| Command                                 | Description                                               |
|-----------------------------------------|-----------------------------------------------------------|
| `/ac start`                             | Start a new game (auto-starts when 2 TCP players connect) |
| `/ac start <player1> <player2>`         | Start with specific MC players (requires TCP sessions)    |
| `/ac reset`                             | Reset game, delete all arena worlds                       |
| `/ac status`                            | Show current game phase, round, timer                     |
| `/ac cards`                             | List your card inventory                                  |
| `/ac givecard <player> <mobId> [count]` | Debug: give cards to a player                             |
| `/ac pause`                             | Pause the game timer                                      |
| `/ac continue`                          | Resume the game timer                                     |
| `/ac skip`                              | Skip current phase                                        |
| `/ac config show`                       | Show current config                                       |
| `/ac config dayDuration <ticks>`        | Set day duration (default: 6000)                          |
| `/ac config prepTime <ticks>`           | Set preparation time                                      |
| `/ac config seed <seed>`                | Set world seed                                            |

## Keybinds

| Key   | Action                                                                   |
|-------|--------------------------------------------------------------------------|
| `Tab` | Open card inventory (survival/battle) or deployment screen (preparation) |

## Game Flow

### Round Structure (default: 3 rounds)

1. **Survival Phase** — Players play in separate singleplayer worlds with identical seeds. Kill mobs to earn cards. Ores auto-smelt (raw iron → iron ingot, etc.). XP orbs are disabled. Day/night cycle is accelerated. Spawner mobs are tagged red and don't give cards. Duration scales with round (R1: 1 day, R2: 2 days, R3: 3 days).

2. **Preparation Phase** — Players connect to the MC server and see the arena. Open deployment GUI (`R`) to place mob cards on 3 lanes × 2 slots per lane. Ring the physical bell on the arena or wait for timer (3 min) to start battle. Players can build in designated zones behind their throne.

3. **Battle Phase** — Deployed mobs spawn and advance along lanes toward the enemy throne. Waypoint-based navigation with lane confinement. Towers shoot arrows at nearby enemies. Throne deals AoE damage. No time limit — ends when all mobs are dead or a throne is destroyed.

4. **Round End** — Retreated mobs return to cards. Damage stats are accumulated. 10-second pause before next survival phase.

### Victory Conditions (priority order)

1. Throne destroyed → instant win
2. After 3 rounds: most cumulative throne damage
3. Tiebreak: most enemy towers destroyed
4. Tiebreak: most tower damage dealt
5. Full tie → draw

## Mob Cards

Kill a registered mob during survival → get a level 1 card. Cards are stored in a separate inventory (not vanilla slots) and synced via TCP.

### Registered Mobs (~40 types)

**Undead:** Zombie, Skeleton, Husk, Stray, Drowned, Phantom, Wither Skeleton, Zombified Piglin
**Arthropod:** Spider, Cave Spider, Silverfish, Endermite
**Nether:** Blaze, Ghast, Piglin, Piglin Brute, Hoglin, Magma Cube
**End:** Enderman, Shulker
**Illager:** Vindicator, Pillager, Evoker, Ravager, Vex
**Golem:** Iron Golem, Snow Golem
**Animal:** Wolf, Bee, Llama, Goat, Chicken, Cow, Pig, Sheep
**Special:** Creeper, Witch, Slime, Baby Zombie
**Boss:** Warden, Wither, Elder Guardian

## Arena Layout

```
          [Throne P2]          Z+
         /    |    \
    [Tower] [   ] [Tower]
       |      |      |
    Lane R Lane C  Lane L
       |      |      |
    =======DIVIDER=======    ← center Z
       |      |      |
    Lane L  Lane C  Lane R
       |      |      |
    [Tower] [   ] [Tower]
         \    |    /
          [Throne P1]          Z-
```

Lane length ~36 blocks, width 5 blocks, separation 15 blocks between centers. Towers cover their lane + adjacent. Throne has AoE melee.

## Configuration

All values tunable via `/ac config` or `config/arenaclash.json`:

- Day duration, preparation time, max rounds
- Arena dimensions, lane geometry
- Throne/tower HP, damage, range, cooldowns
- Knockback strength, mob aggro range
- Game seed (0 = random)

## Mixins

| Mixin                       | Target                                  | Purpose                                                                 |
|-----------------------------|-----------------------------------------|-------------------------------------------------------------------------|
| `ServerWorldMixin`          | `ServerWorld.spawnEntity`               | XP orb prevention, spawner mob detection (proximity), ore auto-smelting |
| `ExperienceOrbMixin`        | `ExperienceOrbEntity.onPlayerCollision` | Prevent XP collection during survival                                   |
| `DayCycleMixin`             | `ServerWorld.tick`                      | Accelerate day/night cycle                                              |
| `PlayerManagerMixin`        | `PlayerManager.broadcast`               | Forward chat/death messages via TCP                                     |
| `EntityRideMixin`           | `Entity.startRiding`                    | Prevent arena/spawner mobs from mounting vehicles                       |
| `TitleScreenMixin` (client) | Title screen                            | Add "Arena Clash" button                                                |
| `ClientChatMixin` (client)  | Client chat                             | Intercept outgoing chat for TCP relay                                   |

## Project Structure

```
com.arenaclash/
├── ArenaClash.java              # Server entry point, TCP server init
├── arena/
│   ├── ArenaBuilder.java        # Block-level arena construction
│   ├── ArenaManager.java        # Battle orchestration, lanes, structures
│   ├── ArenaMob.java            # Mob controller (state machine, combat, navigation)
│   ├── ArenaStructure.java      # Tower/throne with HP, attacks, visual effects
│   └── Lane.java                # Lane geometry + deployment slots
├── card/
│   ├── CardInventory.java       # Per-player card collection
│   ├── MobCard.java             # Card instance (definition + UUID)
│   ├── MobCardDefinition.java   # Card template (stats, category, entity type)
│   └── MobCardRegistry.java     # All mob definitions (~40 types)
├── command/
│   └── GameCommands.java        # /ac commands
├── config/
│   └── GameConfig.java          # Tunable config with JSON persistence
├── event/
│   └── GameEventHandlers.java   # Server events (mob kills, bell, build zones)
├── game/
│   ├── GameManager.java         # Central state machine
│   ├── GamePhase.java           # LOBBY, SURVIVAL, PREPARATION, BATTLE, etc.
│   ├── PlayerGameData.java      # Per-player wrapper for events
│   └── TeamSide.java            # PLAYER1 / PLAYER2
├── mixin/                       # See Mixins table above
├── network/
│   └── NetworkHandler.java      # Fabric networking packets (MC server ↔ client)
├── tcp/
│   ├── ArenaClashTcpServer.java # Persistent TCP server (lobby, sync, relay)
│   ├── SingleplayerBridge.java  # Queue bridge: integrated server → client thread
│   ├── SyncProtocol.java        # JSON message protocol definitions
│   └── TcpSession.java          # Per-player TCP session state
└── world/
    └── WorldManager.java        # Fantasy runtime world management

client/
├── ArenaClashClient.java        # Client entry point, tick loop, world transitions
├── gui/
│   ├── CardScreen.java          # Card inventory viewer
│   ├── ConnectScreen.java       # TCP server connection UI
│   └── DeploymentScreen.java    # Mob placement GUI
├── mixin/
│   ├── ClientChatMixin.java     # Chat interception
│   └── TitleScreenMixin.java    # Title screen button
├── render/
│   └── GameHudRenderer.java     # HUD overlay (timer, phase, round pips)
├── tcp/
│   └── ArenaClashTcpClient.java # Client-side TCP connection
└── world/
    └── WorldCreationHelper.java # Singleplayer world creation from seed
```

## Key Technical Details

- **Inventory sync**: Player inventories are serialized as SNBT and transferred via TCP between singleplayer and arena server.
- **Reconnection**: TCP sessions preserve cards and team assignment on disconnect/reconnect. Game state is restored automatically.
- **Auto-pause**: If both players press ESC simultaneously, the game timer pauses.
- **Lane mirroring**: PLAYER1's "LEFT" maps to world RIGHT so both players see consistent UI perspectives.
- **Spawner detection**: Mobs spawning within 5 blocks of a spawner block are tagged with red glow and excluded from card drops.
- **Ore auto-smelting**: Raw ores dropped as `ItemEntity` are replaced with their smelted equivalents. Works with Fortune (preserves stack count) and doesn't affect Silk Touch (drops ore block, not raw item).
