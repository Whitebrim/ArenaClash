# CLAUDE.md — Arena Clash

## Project Overview

Arena Clash is a **Fabric mod for Minecraft 1.21.1** implementing a Clash Royale-inspired 1v1 PvP arena game. Players gather resources in separate survival worlds (same seed for fairness), earn mob cards from kills, then deploy mobs on a 3-lane arena where they auto-fight. Best of 3 rounds.

- **Version:** 0.5.6 (MVP stage)
- **Author:** Brim
- **License:** All-Rights-Reserved
- **Language:** Java 21
- **Build System:** Gradle with Fabric Loom 1.7-SNAPSHOT
- **Game Design Document:** `GDD.md` (written in Russian)

## Build & Run

```bash
# Build the mod JAR
./gradlew build

# On Windows
BUILD.cmd
```

Output JAR goes to `build/libs/`. No test suite exists — testing is manual via gameplay and `/ac` commands.

## Tech Stack & Dependencies

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.5+ |
| Fabric API | 0.103.0+1.21.1 |
| Yarn Mappings | 1.21.1+build.3 |
| Java | 21 |

Implicit libraries via Fabric: GSON (JSON), SLF4J (logging).

## Architecture

### Hybrid TCP + Minecraft Server

The mod uses a **dual-network architecture**:
- **TCP server** (port = MC port + 1): persistent lobby, card sync, timer, chat relay — survives singleplayer world transitions
- **MC server**: only active during PREPARATION and BATTLE phases for arena visuals

### Game Flow & Phases

```
Title → ConnectScreen → Lobby (TCP) →
  SURVIVAL (singleplayer) → PREPARATION (arena) → BATTLE (arena) → ROUND_END
  → repeat 3 rounds → GAME_OVER
```

Phase enum: `GamePhase.java` — LOBBY, SURVIVAL, PREPARATION, BATTLE, ROUND_END, GAME_OVER

### Source Set Split

Fabric Loom splits sources into **main** (server) and **client** environments:
- `src/main/java/com/arenaclash/` — Server-side code
- `src/client/java/com/arenaclash/client/` — Client-side code
- `src/main/resources/` — Shared resources (mod manifest, mixins, assets, data)

## Project Structure

```
src/main/java/com/arenaclash/
├── ArenaClash.java              # Server entrypoint (ModInitializer)
├── arena/
│   ├── ArenaBuilder.java        # Constructs arena blocks (floor, walls, towers)
│   ├── ArenaManager.java        # Battle orchestration, mob spawning, combat loop
│   ├── ArenaMob.java            # Mob AI state machine & special abilities
│   ├── ArenaStructure.java      # Tower/Throne with HP, attacks, visuals
│   ├── Lane.java                # Lane geometry, waypoints, deployment slots
│   └── ProjectileTracker.java   # Tower arrow tracking
├── block/
│   ├── ModBlocks.java           # Custom block registry
│   └── CardUpgradeWorkbenchBlock.java  # Future: card merge workbench
├── card/
│   ├── CardInventory.java       # Player's card collection (NBT-serializable)
│   ├── MobCard.java             # Card instance (UUID, mob ID, level)
│   ├── MobCardDefinition.java   # Immutable mob template (stats, category)
│   └── MobCardRegistry.java     # ~60 mob type definitions
├── command/
│   └── GameCommands.java        # /ac command tree
├── config/
│   └── GameConfig.java          # JSON config (config/arenaclash.json)
├── event/
│   └── GameEventHandlers.java   # Fabric event listeners
├── game/
│   ├── GameManager.java         # Central state machine (~900 LOC)
│   ├── GamePhase.java           # Phase enum
│   ├── PlayerGameData.java      # Per-player state wrapper
│   └── TeamSide.java            # PLAYER1/PLAYER2 enum
├── mixin/
│   ├── ServerWorldMixin.java    # Spawner detection, XP prevention, ore auto-smelt
│   ├── ExperienceOrbMixin.java  # Block XP collection in survival
│   ├── DayCycleMixin.java       # Accelerated day/night
│   ├── PlayerManagerMixin.java  # Chat/death relay via TCP
│   ├── EntityRideMixin.java     # Prevent arena mob mounting
│   ├── ArenaMobDamageMixin.java # Custom damage handling
│   └── ProjectileCollisionMixin.java
├── network/
│   └── NetworkHandler.java      # Fabric packet definitions (S2C/C2S)
├── tcp/
│   ├── ArenaClashTcpServer.java # Persistent TCP server
│   ├── TcpSession.java          # Per-player TCP session state
│   ├── SyncProtocol.java        # JSON message protocol (length-prefix)
│   └── SingleplayerBridge.java  # Thread bridge for singleplayer
└── world/
    └── WorldManager.java        # World creation/deletion

src/client/java/com/arenaclash/client/
├── ArenaClashClient.java        # Client entrypoint (ClientModInitializer)
├── gui/
│   ├── CardScreen.java          # Card inventory viewer
│   ├── CardUpgradeScreen.java   # Future: card merging UI
│   ├── ConnectScreen.java       # TCP server connection UI
│   └── DeploymentScreen.java    # Drag-and-drop mob placement
├── mixin/
│   ├── ClientChatMixin.java
│   ├── TitleScreenMixin.java
│   └── PlayerListMixin.java
├── render/
│   └── GameHudRenderer.java     # HUD overlay (timer, phase, round pips)
├── survival/
│   └── OpponentMarkerManager.java
├── tcp/
│   └── ArenaClashTcpClient.java
└── world/
    └── WorldCreationHelper.java

src/main/resources/
├── fabric.mod.json              # Mod manifest
├── arenaclash.mixins.json       # Server mixin config
├── arenaclash.client.mixins.json
├── arenaclash.accesswidener
├── assets/arenaclash/
│   ├── lang/en_us.json, ru_ru.json   # Localization
│   ├── blockstates/, models/, textures/
│   └── icon.png
└── data/arenaclash/
    ├── advancement/, loot_table/, recipe/
```

## Key Subsystems

### Game Manager (`game/GameManager.java`)
Central orchestrator (~900 LOC). Singleton. Manages phase transitions, TCP sessions, player data, arena creation, world management, battle loops, and scoring.

### Pause System
- `/ac pause` and `/ac continue` — pauses game timer and blocks progression.
- **Auto-pause**: client detects ESC menu, sends `C2S_PAUSE_STATE` via TCP for remote pause sync.

### Build Zone (PREPARATION)
- 13×13×3 area behind each player's throne. Players can only place/break blocks within their own build zone. Interaction with opponent's zone is blocked with a warning.

### Opponent Presence (Survival Phase)
- **Ghost marker**: ArmorStand pair shows opponent's position and equipment in real-time (body + floating nametag).
- **State sync**: position, rotation, and equipment (6 slots as SNBT) sent every 4 client ticks (5 times/sec).
- **Dimension visibility**: marker hidden when players are in different dimensions; reappears when matched.
- **Auto-timeout**: marker removed after 5 seconds (100 ticks) without updates (disconnect detection).

### Chat & Communication
- **Chat relay**: player messages forwarded via TCP to opponent with vanilla `chat.type.text` formatting.
- **Broadcast relay**: system messages (advancements, deaths) serialized as JSON Text and relayed with full formatting (hover events, colors).
- **Opponent card notifications**: colored chat message when opponent obtains a card; bonus cards marked separately.

### Reconnection & Session Management
- **Inventory sync**: bidirectional — saved on MC disconnect, restored when re-entering singleplayer.
- **Full state restoration**: phase, round, timer, card inventory, game seed restored on reconnect.
- **"Continue" button**: appears on Title Screen when a game session is active. Resumes at current phase (SURVIVAL → singleplayer, PREPARATION/BATTLE → arena server).

### Arena Combat (`arena/`)
- **ArenaMob** (~800 LOC): State machine (IDLE → ADVANCING → FIGHTING → RETREATING → DEAD). Handles waypoint movement, lane confinement, closest-enemy targeting, special abilities (creeper explosion, blaze/ghast fireballs, witch potions, evoker vex summoning, enderman teleport), stuck detection, HP bars.
- **Flying mobs**: ghast, phantom, blaze, bee, vex, allay, bat, parrot, breeze, wither — fly 1 block above ground, use aerial waypoint following.
- **Evoker vex summoning**: spawns up to 3 vex child mobs on the lane; vexes don't return as cards on death.
- **Slime splitting**: dead slimes spawn smaller child slimes as arena mobs.
- **Floating damage numbers**: animated damage text above mobs during battle (tagged `arenaclash_dmg_number`, auto-cleaned).
- **Mob tick randomization**: tick order is shuffled each tick to prevent P1-always-first advantage in simultaneous attacks.
- **Grace period**: 10-second pause after all mobs die before round ends.
- **ArenaManager**: Spawns mobs from cards, runs combat loop, checks victory conditions. Tracks XP earned, tower/throne damage, and towers destroyed per team.
- **ArenaStructure**: Tower (250 HP, shoots arrows) and Throne (1000 HP, AoE shockwave).
- **ProjectileTracker**: Custom hit-detection for arena projectiles (tower arrows, mob fireballs) via proximity checking instead of vanilla collision. Projectiles tagged `arenaclash_mob_projectile` / `arenaclash_tower_arrow`. Auto-cleanup each tick. `ProjectileCollisionMixin` suppresses vanilla collision for arena entities.
- **Lane**: 3 lanes (LEFT, CENTER, RIGHT), ~36 blocks long, 5 blocks wide. Directional waypoints per team.

### Card System (`card/`)
- **MobCardRegistry**: ~60 mob types. Stats: HP, speed, attack damage, attack cooldown, equipment slots, category (Undead, Arthropod, Nether, End, Illager, Golem, Animal, Special, Boss).
- Cards earned by killing mobs in survival. Spawner mobs excluded (5-block radius detection, red "Spawner" label).
- **Baby variants**: baby zombie → separate `baby_zombie` card; baby hostile/neutral (piglin, hoglin, zoglin) → parent type card; baby passive → no card.
- **Slime/Magma Cube sizes**: small/medium/large → separate cards (`slime`, `slime_medium`, `slime_large`).
- **Looting bonus**: Looting enchantment gives 10%/20%/30% chance per level for a bonus card.
- **Level scaling**: HP and attack damage multiply by level; speed does not scale. MVP: all cards level 1, no equipment, no merging.

### TCP Protocol (`tcp/`)
- Length-prefixed (4-byte big-endian) JSON messages via `SyncProtocol`
- Message types: AUTH, CARD_OBTAINED, READY, GAME_STATE, TIMER, OPPONENT_STATE, BROADCAST, PLAYER_STATE, PAUSE_STATE, etc.
- Reconnection support with full state restoration
- `SingleplayerBridge` for integrated server thread bridging (queues mob kills, chat, broadcasts between MC server thread and TCP client thread)

### Mixins (`mixin/`)
Server-side bytecode patches for: spawner mob detection (5-block radius, red glow), XP prevention, ore auto-smelting (raw → ingot, respects Fortune/Silk Touch), day cycle acceleration, chat/broadcast relay, custom damage/projectile handling.
Client-side mixins: `TitleScreenMixin` (injects "Arena Clash" button), `PlayerListMixin` (suppresses TAB player list during game), `ClientChatMixin`.

### Client UI/UX
- **"Arena Clash" button on Title Screen** — quick access to ConnectScreen via `TitleScreenMixin`.
- **TAB opens card inventory** — `PlayerListMixin` suppresses vanilla player list; TAB opens `CardScreen` / `DeploymentScreen` depending on phase.
- **Saved server address** — last connected TCP IP persisted to `arenaclash_client.txt` in config dir, auto-loaded on start.
- **HUD timer animation** — color transitions: green → orange (10-30s) → red pulsing (<5s). Phase icon, round pips, LIVE indicator, bell hint, GAME_OVER overlay.

## Commands

All commands under `/ac`:
- `start` / `start <p1> <p2>` — Start game
- `reset` — Reset and delete worlds
- `status` — Show phase/round/timer
- `cards` — List player's cards
- `givecard <player> <mobId> [count]` — Debug: give cards
- `pause` / `continue` — Pause/resume game (timer stops, progression blocked)
- `skip` — Skip current phase
- `config show` / `config <param> <value>` — View/tune config

## Configuration

Persisted to `config/arenaclash.json`. Key tunable values:
- `dayDurationTicks`: 6000 (5 min = 1 MC day)
- `preparationTimeTicks`: 3600 (3 min)
- `maxRounds`: 3
- Arena dimensions, structure HP/damage/ranges, knockback, mob aggro range, TCP port

## Conventions

### Git Commits
Follow conventional commits with optional version scope:
```
feat: description
fix: description
feat(0.5.1): description
fix(0.5.2): description
chore: description
```

### Code Style
- Java 21 features are available (records, pattern matching, etc.)
- Singleton pattern for managers (GameManager, GameConfig, MobCardRegistry)
- State machine pattern for game phases and mob AI
- Fabric event system for server hooks
- NBT serialization for card inventories
- GSON for config and TCP protocol
- ConcurrentHashMap for thread-safe TCP session management
- Localization keys in `assets/arenaclash/lang/` (en_us + ru_ru)

### Important Patterns
- Server code in `src/main/`, client code in `src/client/` — Fabric Loom enforces this split
- Mixins must be registered in the corresponding JSON config files
- Access wideners declared in `arenaclash.accesswidener`
- New mob types must be registered in `MobCardRegistry`
- New packets must be defined in `NetworkHandler` with both S2C and C2S handlers
- Config fields require defaults in `GameConfig` and serialization via GSON

### Victory Conditions (priority order)
1. Throne destroyed → instant win
2. After 3 rounds: most cumulative throne damage
3. Tiebreak: most towers destroyed → most tower damage → draw

## Post-MVP Planned Features

These are designed but **not yet implemented** (stubs may exist):
- Card merging (2x same level → level+1) via CardUpgradeWorkbenchBlock
- Equipment system (weapons, armor, potions on cards)
- Skill tree (buy deployment slots with XP)
- Sacrifice table (cards → XP)
- Spells/curses
- 2v2 and FFA modes
- Spectator mode
- Quest system
