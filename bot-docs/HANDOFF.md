# PlanetSide Bots - Handoff Document

> **Read this first** if you're a new Claude instance or agent picking up this project.

## What Is This Project?

We're adding **bot players** to PlanetSide 1 via the PSForever server emulator. The goal is to make the game world feel alive even with low real player population.

### The Vision
- Hundreds of bots per server (scaling down as real players join)
- Bots that feel like real players, not mechanical turrets
- V-menu chatter, teamwork, attitude/personality systems
- Infantry first, vehicles later

### Key Stakeholder
The user is a **veteran PlanetSide player** (decade+ experience) and serves as the expert on "game feel". They have detailed notes on how bots should behave. Always defer to them on gameplay questions.

---

## Project Structure

```
PSF-LoginServer/
├── bot-docs/               ← Bot project documentation
│   ├── HANDOFF.md          ← YOU ARE HERE - Start here
│   ├── PROJECT.md          ← Overview, goals, status
│   ├── GAME_FEEL.md        ← Behavioral spec (vision, movement, V-menu, classes, etc.)
│   ├── ARCHITECTURE.md     ← Technical design decisions
│   ├── CODEBASE_MAP.md     ← Key files with line numbers (CRITICAL REFERENCE)
│   ├── POC_PLAN.md         ← Phased implementation milestones
│   ├── DEV_SETUP.md        ← Development environment setup
│   └── SKETCHES/           ← Conceptual code (not production)
│       ├── BotActor_v1.scala
│       └── BotSpawner_v1.scala
├── src/main/scala/net/psforever/
│   └── actors/bot/         ← Bot implementation code
│       ├── BotManager.scala
│       └── BotAvatarActor.scala
└── ...                     ← Rest of PSForever server codebase
```

### Read Order for New Instance
1. **HANDOFF.md** (this file) - Context and decisions
2. **CODEBASE_MAP.md** - Where things are in the code
3. **GAME_FEEL.md** - How bots should behave
4. **SKETCHES/** - Conceptual code to understand approach

---

## Key Technical Decisions Made

### 1. BotActor Approach (NOT SessionActor)
We decided to create a new `BotActor` rather than emulating `SessionActor` because:
- SessionActor's main job is handling network packets from clients
- Bots have no client, so no incoming packets
- BotActor can be optimized specifically for AI needs
- Less overhead, better scalability

### 2. Server-Side Native Bots
Bots are first-class server entities, not fake clients connecting from outside.
- Bots use existing `Player` and `PlayerControl` code
- No network overhead
- Actions broadcast through same services as real players

### 3. Bot Identity
Bot characters use:
- **High positive IDs** (900000+) to avoid collision with real DB players
- **No special characters in names** - use `xxBOTxxName` format (not `[BOT]Name`)
- Predefined loadouts per class

### 4. Tick Rate
- 10-20 FPS for bot AI decisions (not 60)
- Can reduce further for bots far from real players
- Server load monitoring will inform final values

---

## Bot Architecture Summary

```
BotManager (per zone)
├── Monitors real player population
├── Spawns/despawns bots to maintain target count
└── Manages one Ace per faction (last to logout)

BotActor (per bot)
├── 10 FPS tick loop
├── Vision cone detection (60-90 degrees)
├── Decision making (attack, retreat, objective, help)
├── Movement execution
├── State broadcasting
└── Controls a Player entity

BotAvatarActor (stub)
└── Minimal - just accepts messages PlayerControl sends

Player + PlayerControl (existing code)
├── Player entity with Vitality, inventory, etc.
└── PlayerControl handles damage/death (already exists)
```

---

## Spawn Flow (Critical Path)

```scala
// 1. Create avatar (HIGH POSITIVE ID - 900000+)
val avatar = Avatar(900000 + botNum, BasicCharacterData(name, faction, sex, head, voice))

// 2. Create player entity
val player = new Player(avatar)
player.Position = spawnPosition
player.Spawn()

// 3. Create typed stub avatar actor (MUST be typed ActorRef)
val typedSystem = context.system.toTyped
val botAvatarActor: ActorRef[AvatarActor.Command] =
  typedSystem.systemActorOf(BotAvatarActor(), s"bot-avatar-$botId")

// 4. Register GUIDs (ASYNC - use registerAvatar, NOT registerPlayer!)
TaskWorkflow.execute(GUIDTask.registerAvatar(zone.GUID, player)).onComplete {
  case Success(_) =>
    // 5. Join zone population
    zone.Population ! Zone.Population.Join(avatar)
    // 6. Spawn player (creates PlayerControl actor)
    zone.Population ! Zone.Population.Spawn(avatar, player, botAvatarActor)
    // 7. Broadcast to clients
    zone.AvatarEvents ! AvatarServiceMessage(zone.id, AvatarAction.LoadPlayer(...))
  case Failure(ex) =>
    log.error(s"Failed: ${ex.getMessage}")
}
```

---

## Broadcasting (How Bots Appear to Real Players)

Bots broadcast their state via the same channels real players use:

```scala
// Position updates (10-20 times per second)
zone.AvatarEvents ! AvatarServiceMessage(
  zone.id,
  AvatarAction.PlayerState(guid, pos, vel, facing, crouch, jump, ...)
)

// Weapon fire
zone.AvatarEvents ! AvatarServiceMessage(
  zone.id,
  AvatarAction.ChangeFireState_Start(playerGuid, weaponGuid)
)

// V-menu voice commands
// (Need to investigate ChatMsg format)
```

---

## Bot Classes (From User's Notes)

| Class | Role | Behavior |
|-------|------|----------|
| Driver | Vehicle operation | Low priority for gunning, wants to drive |
| Support | Heal & Repair | LOVES gunning (can heal vehicle), prioritizes healing |
| Hacker | Infiltration | Door unlocking, base capture |
| AV | Anti-Vehicle | Tank hunters |
| MAX | Heavy Exosuit | AI/AV/AA variants, switches based on intel |
| Vet | Versatile | Jack of all trades, ADAD movement, vengeance system |
| Ace | Empire Leader | ONE per empire, last to logout, uses Command Chat |

---

## Implementation Status

### COMPLETED (POC Working!)
- [x] BotAvatarActor stub (typed actor, absorbs messages)
- [x] BotManager (spawns bots, handles async GUID flow)
- [x] `!bot` chat command for testing
- [x] Bot spawns visible to players
- [x] Bot takes damage from players (PlayerControl works!)
- [x] Bot movement (10 tick/sec, random wandering, PlayerState broadcasts)
- [x] Multiple bots work independently
- [x] Bot loadout/equipment (POC - proper backpack drops on death)
- [x] Bot shooting with two-force accuracy system (adjustment vs recoil)
- [x] Target acquisition with distance-based recognition time
- [x] Death/respawn cycle with weighted random delays (1-90 sec distribution)
- [x] Burst fire control (resets after 15-25 shots)

### KNOWN ISSUES (Expected for POC)
- Walks through walls (no collision detection)
- No Z-height adjustment (melts into stairs, terrain)
- Bots spawn as whatever faction the spawning player is (BUG - being fixed)

### IN PROGRESS
- [ ] Faction-specific spawn commands (!botnc, !bottr, !botvs)
  - Currently `!bot` spawns bots as the player's faction
  - Need separate commands to spawn specific empire bots

### NOT YET IMPLEMENTED
- [ ] Terrain following (Z height from map data)
- [ ] Collision avoidance
- [ ] Pathfinding
- [ ] V-menu voice command sending
- [ ] Celebration coordination system
- [ ] Vengeance/attitude system
- [ ] Population scaling (spawn/despawn based on real players)
- [ ] Bot class differentiation (loadouts will be revised for prod)

---

## Resolved Questions (Through Trial & Error)

1. **Avatar IDs**: Must be POSITIVE. Negative IDs break packet encoding (32-bit unsigned). Use 900000+.
2. **PlayerControl + stub AvatarActor**: WORKS! BotAvatarActor just absorbs messages, PlayerControl functions normally.
3. **GUID Registration**: Must use `registerAvatar()` not `registerPlayer()` - locker needs GUID for PlayerControl init.

## Open Questions for PSForever Devs

1. **Bot identification**: Should we add `isBot: Boolean` to Player?
2. **Spawn point access**: Best way to find valid faction spawn locations?

---

## Spawn Location Logic (For Future Implementation)

When a player dies, they choose from up to 3 spawn options (all must be friendly-owned):

1. **Nearest Tower** - Guard towers scattered across continents
2. **Nearest AMS** - Advanced Mobile Station (deployable spawn vehicle)
3. **Nearest Base/Facility** - The main base spawn tubes

**Special Cases:**
- **Sanctuary** (home2, home1, home3): Only one spawn option (the sanctuary itself)
- **Warpgates**: Can spawn at warpgates your faction owns

**For Bots:**
- Currently: Respawn at original spawn position (simple)
- Future: Query zone for valid faction spawn points, pick nearest or random
- Need to find: `SpawnPoint` instances, check faction ownership, calculate distance

**Code References to Investigate:**
- `SpawnPoint.scala` - Spawn point trait and calculations
- `Zone.spawnGroups` - Map of building -> spawn points
- `Building.Faction` - Check ownership for spawn eligibility

---

## Important Behavioral Notes

### From GAME_FEEL.md (Key Points)
- **Vision**: 60-90 degree FOV, distance-based spotting time
- **Movement**: Newbies run straight, Vets do ADAD + crouch spam
- **Retreat**: Vets retreat at low HP, Drivers save vehicles, everyone retreats when out of ammo
- **V-menu**: "Needs" always fire, celebrations are coordinated (1-6 responders, staggered timing)
- **Vengeance**: Vets remember who killed them, may seek revenge, taunt on success
- **Panic**: Non-combat bots (repairing) panic when shot, run to cover while swapping weapons
- **Chaos**: The goal is authentic battlefield chaos - spam bullets, grenades, voice commands

### What NOT to Do
- No bunny hopping (almost no one jumps except MAX dodging AV)
- No laser accuracy (bots should miss)
- No instant reactions (bots need reaction time)
- No perfect coordination (spread out, don't stand on same pixel)
- No mechanical behavior (turrets are terrible, we want human-like chaos)

---

## Development Environment

```bash
# Server codebase
cd PSF-LoginServer
sbt compile    # Compile
sbt server/run # Run server

# Requirements
- Java 8 JDK
- sbt (Scala Build Tool)
- PostgreSQL 10+
- PlanetSide client (version 3.15.84.0)
```

---

## Context for Future Agents

If you're a **coding agent** tasked with implementation:
1. Read `CODEBASE_MAP.md` for file locations
2. Read `SKETCHES/` for conceptual starting points
3. Start with Milestone 1: spawn a static bot that appears in-game
4. Test empirically - compile, run, connect with client, observe

If you're a **research agent** investigating something:
1. The PSF-LoginServer codebase is in `./PSF-LoginServer/`
2. All server code is under `src/main/scala/net/psforever/`
3. Tests are under `src/test/scala/`
4. Use grep/glob to find patterns

If the user says **"update the docs"**:
1. Update the relevant .md files
2. Keep `CODEBASE_MAP.md` current with any new discoveries
3. Add line numbers when referencing code

---

## Last Session Summary

**Date**: 2025-11-23

**Recent Accomplishments**:
- POC fully working: bots spawn, move, fight, die, respawn
- Bot loadout/equipment working (proper backpack drops)
- Two-force accuracy system implemented (adjustment vs recoil)
- Death/respawn cycle with weighted random delays
- Target acquisition with distance-based recognition time

**Current Work**:
- Fixing faction bug: bots spawn as player's faction instead of specified faction
- Adding faction-specific spawn commands: `!botnc`, `!bottr`, `!botvs`
- Files being modified:
  - `src/main/scala/net/psforever/actors/session/support/ChatOperations.scala` - add command cases
  - `src/main/scala/net/psforever/actors/bot/BotManager.scala` - use passed faction parameter

**DB Notes**:
- Bot characters use DB IDs 2-301 (xxBOTxxTestBot1 through xxBOTxxTestBot300)
- NCBotfighter character in DB is showing as VS (DB quirk, not a blocker)

**Next Steps**:
- Complete faction-specific spawn commands
- Terrain following (Z height)
- Population scaling
