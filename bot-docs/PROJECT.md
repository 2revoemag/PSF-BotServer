# PlanetSide Bots Project

## Overview
Bringing bot support to PlanetSide through the PSForever server emulator project.

## Resources
- **Server Emulator**: [PSF-LoginServer](https://github.com/psforever/PSF-LoginServer) (Scala)
- **Wiki Reference**: https://www.psforever.net/PlanetSide/
- **Community**: PSForever dev team and community

---

## Documentation Structure

> **Important**: Keep documentation updated for context handoff between sessions.

| Document | Purpose |
|----------|---------|
| `HANDOFF.md` | **Start here** - Context for new Claude instances |
| `PROJECT.md` | This file - Overview and status |
| `GAME_FEEL.md` | Behavioral spec from user's notes |
| `ARCHITECTURE.md` | Technical design decisions |
| `CODEBASE_MAP.md` | Key files with line numbers |
| `POC_PLAN.md` | Implementation milestones |
| `DEV_SETUP.md` | Dev environment setup (verified working) |
| `SKETCHES/` | Conceptual code (not production) |

### Documentation Practices
- When discovering new code patterns, update `CODEBASE_MAP.md` with file paths and line numbers
- Link related concepts (e.g., "PlayerControl handles damage, see line 50-80")
- Write for a fresh Claude instance that has no prior context
- Keep `HANDOFF.md` current with latest decisions and next steps

---

## Game Context (PlanetSide 1)
- **Genre**: MMOFPS (Massively Multiplayer Online First Person Shooter)
- **Factions**: Terran Republic (TR), New Conglomerate (NC), Vanu Sovereignty (VS)
- **Scale**: Designed for hundreds to thousands of concurrent players
- **Core Loop**: Territory control via base capture through lattice-linked continents
- **Emulator Status**: ~98% complete - virtually indistinguishable from original to veteran players

### Key Gameplay Elements
- **Combined Arms**: Infantry, ground vehicles, aircraft all operating together
- **Armor Types**: Standard, Reinforced, Agile, Infiltration suits
- **Certification System**: Players unlock equipment/vehicles via certs
- **Base Capture**: Lattice-based progression, facility benefits cascade through links
- **Continents**: 10 landmass continents + underground caverns

### Vehicle Categories
- **Air**: Reaver, Mosquito, Liberator, Galaxy
- **Ground**: Lightning, Magrider, Vanguard, Harasser, ATV, ANT, Sunderer
- **Specialized**: BattleFrame Robotics (BFRs)

---

## Project Goals

### Primary Goal
**Population bots** - Make the world feel alive even with low real player counts
- Target: Hundreds of bots capable of running simultaneously
- **Dynamic scaling**: Bots log out as real players join each faction
- Bots should participate in the war naturally (capture bases, fight, move with purpose)

### Scope Phases
1. **Phase 1**: Infantry bots only
2. **Phase 2+**: Ground vehicles, aircraft, advanced behaviors (TBD)

### Intelligence Level
- Start realistic, iterate based on what's achievable
- Goal: Bots that don't break immersion, feel like "bad but trying" players

---

## Technical Approach

### Decided: BotActor (Server-Side Native)
After analyzing the codebase, we chose to create a new `BotActor` rather than emulating `SessionActor`:
- SessionActor handles network I/O from clients - bots have no client
- BotActor makes AI decisions internally, broadcasts via zone services
- Less overhead, better scalability, full optimization control

### Architecture Overview
```
BotManager (per zone)
└── BotActor (per bot)
    └── Controls Player entity
        └── PlayerControl (existing) handles damage/death
```

### Key Integration Points
- `Zone.Population` - Join/Spawn/Leave for bots
- `zone.AvatarEvents` - Broadcast position/actions
- `GUIDTask.registerPlayer()` - GUID allocation
- `PlayerControl` - Handles damage/death (reused)

See `CODEBASE_MAP.md` for file locations and line numbers.

### Dev Team Support
- Team wants this feature but bandwidth-limited
- Will assist with major blockers
- Full support expected as project nears completion
- **Project Lead**: Community member (us!) taking point

---

## Status

**Phase**: POC Complete - Bots Spawn, Move, Fight, Die & Respawn!

### Completed
- [x] Clone PSF-LoginServer codebase
- [x] Analyze codebase architecture
- [x] Map spawn/broadcast/GUID flows
- [x] Document behavioral spec from user's notes
- [x] Design bot architecture
- [x] Create conceptual code sketches
- [x] Build handoff documentation
- [x] Set up dev environment (Java 8, sbt, PostgreSQL)
- [x] Verify baseline compile and server run
- [x] **Phase 1: Spawn a static bot** - WORKING!
- [x] **Phase 2: Bot moves and broadcasts** - WORKING!
- [x] **Phase 3: Bot detects enemies and shoots** - WORKING!
- [x] Bot loadout/equipment (POC - proper backpack drops)
- [x] Death/respawn cycle with weighted random delays
- [x] Two-force accuracy system (adjustment vs recoil)
- [x] Target recognition time based on distance

### Resolved Questions (Through Trial & Error)
- Avatar IDs must be POSITIVE (900000+), not negative
- PlayerControl works perfectly with stub BotAvatarActor
- Must use `registerAvatar()` not `registerPlayer()` (locker needs GUID)

### Next Steps
- [ ] Faction-specific spawn commands (!botnc, !bottr, !botvs) - **IN PROGRESS**
- [ ] Terrain following (Z height)
- [ ] Bot class differentiation (loadouts will be revised for prod)
- [ ] Population scaling (auto-spawn/despawn based on real players)

---

## Faction Behavior & Game Feel
*See `GAME_FEEL.md` for detailed behavioral spec including:*
- Vision system (60-90 degree FOV, partial/full spotting)
- V-menu communication system
- Bot classes (Driver, Support, Hacker, AV, MAX, Vet, Ace)
- Movement patterns (newbie vs veteran)
- Retreat behaviors
- Attitude and vengeance system
- Chaos factor
