# PlanetSide Bots - Proof of Concept Plan

## Goal
Get a single bot to spawn, be visible to clients, and perform basic actions.

---

## Phase 0: Research & Setup (DONE)

- [x] Clone PSF-LoginServer
- [x] Analyze codebase architecture
- [x] Identify Player entity system
- [x] Find existing AI patterns (AutomatedTurretBehavior)
- [x] Document integration points

---

## Phase 1: "Hello World" Bot

### Objective
Spawn a bot entity that appears in the game world as a player character.

### Tasks

#### 1.1 Understand Player Creation Flow
- [ ] Trace how a real player is created when they log in
- [ ] Find `Player` instantiation code
- [ ] Find zone registration code (`Zone.AddPlayer` or similar)
- [ ] Find GUID allocation for new entities

#### 1.2 Create Minimal Bot Infrastructure
- [ ] Create `src/main/scala/net/psforever/objects/bot/` directory
- [ ] Create `Bot.scala` - wrapper or extension of Player
- [ ] Create `BotActor.scala` - minimal actor (just keeps bot alive)

#### 1.3 Bot Spawning
- [ ] Find how to spawn a player at a SpawnPoint without client
- [ ] Allocate GUID for bot
- [ ] Register bot in zone
- [ ] Broadcast bot's existence to other players

#### 1.4 Validation
- [ ] Connect real client to server
- [ ] Verify bot appears as a player character
- [ ] Bot should be standing still at spawn location

### Questions to Answer
- Can we create a Player without Avatar from database?
- Do we need to fake an Account/Character entry?
- What minimum packets must be sent to make bot visible?

---

## Phase 2: Moving Bot

### Objective
Bot moves through the world and other players can see it moving.

### Tasks

#### 2.1 PlayerStateMessage Broadcasting
- [ ] Understand how player position updates are broadcast
- [ ] Find the packet: `PlayerStateMessage`
- [ ] Implement position update loop in BotActor

#### 2.2 Simple Movement
- [ ] Bot walks forward
- [ ] Bot turns (change orientation)
- [ ] Movement speed matches infantry walking

#### 2.3 Patrol Behavior
- [ ] Bot walks between two points
- [ ] Bot stops, turns, walks back

### Success Criteria
- Real player sees bot walking around
- Bot movement is smooth (not teleporting)

---

## Phase 3: Combat Bot

### Objective
Bot can detect enemies and shoot at them.

### Tasks

#### 3.1 Vision System
- [ ] Implement FOV cone check (60-90 degrees)
- [ ] Detect players in range
- [ ] Filter by faction (don't shoot friendlies)

#### 3.2 Target Tracking
- [ ] Store current target reference
- [ ] Turn to face target (orientation updates)
- [ ] Lose target when out of range/sight

#### 3.3 Shooting
- [ ] Find weapon fire packets (`ChangeFireStateMessage_Start`, `WeaponFireMessage`)
- [ ] Implement firing at target
- [ ] Basic accuracy (not perfect, not terrible)

#### 3.4 Taking Damage
- [ ] Bot receives damage normally (via PlayerControl)
- [ ] Bot dies when HP reaches 0
- [ ] Bot respawns after death

### Success Criteria
- Bot shoots at enemy players
- Bot can be killed
- Bot respawns

---

## Phase 4: Smart Bot

### Objective
Bot makes tactical decisions.

### Tasks

#### 4.1 Health-Based Retreat
- [ ] Track bot's HP
- [ ] Retreat when HP below threshold
- [ ] Find cover or run toward spawn

#### 4.2 Ammunition Management
- [ ] Track ammo count
- [ ] Retreat to resupply when empty

#### 4.3 V-Menu Communication
- [ ] Bot sends VVV when needs help
- [ ] Bot sends VNG when in vehicle without gunner
- [ ] Proper chat message format

### Success Criteria
- Bot retreats when hurt
- Bot calls for help appropriately

---

## Phase 5: Bot Classes

### Objective
Different bot types with different behaviors.

### Tasks

#### 5.1 Class Definitions
- [ ] Define loadouts for each class (Driver, Support, Hacker, AV, MAX)
- [ ] Create certification sets per class
- [ ] Assign behavior weights per class

#### 5.2 Class-Specific Behavior
- [ ] Support: Prioritize healing/repairing
- [ ] Hacker: Prioritize hacking doors/objectives
- [ ] AV: Prioritize anti-vehicle combat
- [ ] MAX: Heavy combat behavior

#### 5.3 Vet & Ace Classes
- [ ] Vet: Multi-role capability
- [ ] Ace: Command behavior, last to logout

### Success Criteria
- Multiple bot types visible
- Each type behaves differently

---

## Phase 6: Population Management

### Objective
Bots scale with player population.

### Tasks

#### 6.1 BotManager
- [ ] Track real player count per faction
- [ ] Calculate target bot count
- [ ] Spawn/despawn bots to maintain balance

#### 6.2 Graceful Despawn
- [ ] Non-Ace bots leave first
- [ ] Ace leaves last
- [ ] Bots "log out" naturally (not poof)

#### 6.3 Faction Balance
- [ ] Equal bots per faction
- [ ] Adjust for real player imbalance?

### Success Criteria
- Server maintains ~100 bots per faction when empty
- Bots reduce as real players join
- Ace is last bot standing per faction

---

## Phase 7: Team Coordination

### Objective
Bots work together and follow orders.

### Tasks

#### 7.1 Order System
- [ ] Ace issues attack/defend orders
- [ ] Bots receive and follow orders
- [ ] Order priority system

#### 7.2 Help Response
- [ ] Bots respond to VNG/VNH/VVV
- [ ] Check if can help
- [ ] Navigate to requester

#### 7.3 Celebration Coordination
- [ ] Coordinate V-menu celebrations
- [ ] Staggered timing
- [ ] Limited responders (1-6)

### Success Criteria
- Bots attack/defend as ordered
- Bots help each other
- Celebrations feel natural

---

## Development Environment Setup

### Prerequisites
- Java 8 JDK
- sbt (Scala Build Tool)
- PostgreSQL 10+
- PlanetSide client (version 3.15.84.0)

### Build & Run
```bash
cd PSF-LoginServer
sbt compile
sbt server/run
```

### Testing Approach
1. Run server locally
2. Connect with PS client
3. Observe bot behavior
4. Iterate

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Player without SessionActor breaks things | Medium | High | May need dummy SessionActor |
| GUID allocation conflicts | Medium | High | Coordinate with existing GUID system |
| Performance with 300+ bots | Medium | Medium | Profile early, optimize as needed |
| Network overhead for bot updates | Low | Medium | Batch updates, reduce frequency |
| Client can't render many players | Low | High | Test with max players early |

---

## Success Metrics

### Phase 1 Success
- 1 bot visible in game world

### Phase 3 Success
- Bot engages in combat

### Phase 5 Success
- Multiple bot classes working

### Phase 7 Success (MVP)
- 100+ bots per faction
- Dynamic population scaling
- Team coordination
- "Feels like PlanetSide"

---

## Estimated Complexity

| Phase | Complexity | Notes |
|-------|------------|-------|
| 1 | Medium | Unknown integration points |
| 2 | Low | Straightforward once Phase 1 works |
| 3 | Medium | Combat systems are complex |
| 4 | Low | Building on Phase 3 |
| 5 | Medium | Many classes to implement |
| 6 | Low | Straightforward management |
| 7 | High | Coordination is complex |
