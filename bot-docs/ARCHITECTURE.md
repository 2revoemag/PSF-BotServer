# PlanetSide Bots - Technical Architecture

## PSF-LoginServer Codebase Analysis

### Technology Stack
- **Language**: Scala (99.5%)
- **Actor Framework**: Akka (classic actors)
- **Database**: PostgreSQL
- **Build**: sbt

### Key Components Discovered

#### Entity Hierarchy
```
PlanetSideServerObject (base)
├── Player
│   ├── Vitality (health, armor)
│   ├── FactionAffinity (TR/NC/VS)
│   ├── Container (inventory)
│   ├── ZoneAware (continent awareness)
│   └── MountableEntity (vehicle seats)
├── Vehicle
├── Deployable
└── FacilityTurret
```

#### Actor System
```
SessionActor (per-connection)
├── Handles network packets from client
├── Manages player state
├── Routes to subsystem handlers
└── Mode-based behavior (Normal, Spectator, CSR)

PlayerControl (per-player entity)
├── Akka Actor controlling Player object
├── Handles damage, healing, death
├── Equipment management
├── Containable behavior
└── Environment interaction
```

#### Relevant Files
| File | Purpose |
|------|---------|
| `objects/Player.scala` | Player entity class |
| `objects/avatar/Avatar.scala` | Persistent player data (certs, loadouts) |
| `objects/avatar/PlayerControl.scala` | Player behavior Actor |
| `actors/session/SessionActor.scala` | Network session handler |
| `objects/SpawnPoint.scala` | Spawn location trait |
| `objects/serverobject/turret/auto/AutomatedTurretBehavior.scala` | **AI reference implementation** |

---

## Existing AI Pattern: AutomatedTurretBehavior

The codebase already has AI! `AutomatedTurretBehavior` is a trait that provides:

### Target Management
- `Targets` - list of known potential targets
- `Target` - current active target
- `AddTarget()` / `RemoveTarget()` - target list management
- `Detected()` - check if target is already known

### Detection & Engagement
- `Alert(target)` - new target spotted
- `Unalert(target)` - target lost
- `ConfirmShot(target)` - hit confirmation
- Range-based detection (`ranges.trigger`, `ranges.escape`, `ranges.detection`)
- Periodic validation sweeps (`detectionSweepTime`)

### Combat Logic
- `engageNewDetectedTarget()` - begin shooting
- `noLongerEngageDetectedTarget()` - stop shooting
- `trySelectNewTarget()` - target selection algorithm
- Target decay checks (destroyed, out of range, MIA)
- Retaliation behavior (respond to being attacked)

### Timing/Cooldowns
- `cooldowns.missedShot` - timeout for unconfirmed hits
- `cooldowns.targetSelect` - delay before selecting new target
- `cooldowns.targetElimination` - delay after killing target
- Self-reported refire timer for continuous fire

### Key Insight
> The turret AI works by being an Akka Actor that receives messages (`Alert`, `ConfirmShot`, `PeriodicCheck`) and maintains internal state. **Bots can follow the same pattern.**

---

## Proposed Bot Architecture

### Option A: Server-Side Native Bots (Preferred)

```
BotActor (extends Actor)
├── BotBehavior trait (similar to AutomatedTurretBehavior)
│   ├── Target detection (vision cone, partial/full spot)
│   ├── Combat engagement
│   ├── V-menu communication
│   └── Attitude/Vengeance system
├── BotMovement trait
│   ├── Pathfinding
│   ├── ADAD strafing
│   └── Retreat behavior
├── BotObjective trait
│   ├── Follow orders (attack/defend)
│   ├── Base capture
│   └── Help responses (VNG, VNH)
└── Controls a Player object (no SessionActor needed)
```

#### How It Would Work
1. **BotManager** actor manages bot lifecycle
   - Spawns bots when population is low
   - Removes bots when real players join
   - Assigns bots to factions

2. **Bot entity** is a `Player` object
   - Has `Avatar` with certs, loadouts (predefined by class)
   - Has `PlayerControl` actor for damage/death handling
   - Has **new** `BotActor` for AI decision-making

3. **Bot appears to clients** as normal player
   - Spawns at SpawnPoints
   - Sends PlayerStateMessage updates
   - Fires weapons, takes damage, dies normally

#### Integration Points
- `Zone.LivePlayers` - bots appear here
- `Zone.AvatarEvents` - bots send/receive events
- `PlayerStateMessage` - bots broadcast position/orientation
- `ChatMsg` - bots send V-menu voice commands

### Option B: Bot-as-Client (Fallback)

External process connects to server as fake client, mimics player packets.

**Pros**: No server code changes needed initially
**Cons**: Network overhead, harder to scale, more fragile

---

## Bot Class Implementation

Each bot class needs:

### Data Definition
```scala
case class BotClass(
  name: String,
  certifications: Set[Certification],
  loadout: Loadout,
  experienceLevel: BotExperience,  // Newbie, Vet, Ace
  primaryRole: BotRole             // Driver, Support, Hacker, AV, MAX, Vet, Ace
)
```

### Behavior Weights
```scala
trait BotPersonality {
  def aggressionLevel: Float       // 0.0 = passive, 1.0 = aggressive
  def accuracyBase: Float          // Base accuracy modifier
  def movementStyle: MovementStyle // Newbie (straight), Vet (ADAD), etc.
  def retreatThreshold: Float      // HP percentage to retreat
}
```

---

## Communication System

### V-Menu Integration
```scala
// Bot sends voice command
def sendVoiceCommand(cmd: VoiceCommand): Unit = {
  zone.AvatarEvents ! AvatarServiceMessage(
    zone.id,
    AvatarAction.SendResponse(botGUID, ChatMsg(ChatMessageType.CMT_VOICE, cmd.text))
  )
}

// Bot responds to nearby voice commands
def handleVoiceCommand(sender: Player, cmd: VoiceCommand): Unit = cmd match {
  case VNG if canBeGunner => respondAndAssist(sender)
  case VNH if canHack => respondAndAssist(sender)
  case VVV => evaluateHelpRequest(sender)
  // etc.
}
```

### Celebration Coordination
```scala
object CelebrationCoordinator {
  def onBaseCapture(zone: Zone, faction: PlanetSideEmpire): Unit = {
    val eligibleBots = zone.LivePlayers
      .filter(_.isBot)
      .filter(_.Faction == faction)
      .filter(_.isAlive)

    val responderCount = Random.nextInt(5) + 1  // 1-6
    val responders = Random.shuffle(eligibleBots).take(responderCount)

    responders.zipWithIndex.foreach { case (bot, i) =>
      val delay = Random.nextFloat() * 1.5f  // 0-1.5 seconds
      scheduler.scheduleOnce(delay.seconds) {
        bot.sendVoiceCommand(randomCelebration())
      }
    }
  }
}
```

---

## Spawn/Despawn Logic

### Dynamic Population Management
```scala
class BotPopulationManager(zone: Zone) {
  val targetBotsPerFaction = 100
  val minRealPlayersBeforeScaling = 10

  def tick(): Unit = {
    PlanetSideEmpire.values.foreach { faction =>
      val realPlayers = zone.LivePlayers.count(p => !p.isBot && p.Faction == faction)
      val currentBots = zone.LivePlayers.count(p => p.isBot && p.Faction == faction)
      val targetBots = math.max(0, targetBotsPerFaction - realPlayers)

      if (currentBots < targetBots) spawnBots(faction, targetBots - currentBots)
      if (currentBots > targetBots) despawnBots(faction, currentBots - targetBots)
    }
  }

  def despawnBots(faction: PlanetSideEmpire, count: Int): Unit = {
    // Remove non-Ace bots first, Ace is last to go
    val bots = zone.LivePlayers
      .filter(p => p.isBot && p.Faction == faction)
      .sortBy(b => if (b.botClass == Ace) Int.MaxValue else 0)
      .take(count)

    bots.foreach(gracefulLogout)
  }
}
```

---

## Proof of Concept Milestones

### Milestone 1: Static Bot
- [ ] Create `BotActor` skeleton
- [ ] Spawn a `Player` entity without `SessionActor`
- [ ] Bot appears in zone, visible to clients
- [ ] Bot stands still (no AI)

### Milestone 2: Moving Bot
- [ ] Implement basic movement
- [ ] Bot walks in a pattern
- [ ] `PlayerStateMessage` broadcasts correctly

### Milestone 3: Reactive Bot
- [ ] Detect nearby enemies (vision cone)
- [ ] Turn to face target
- [ ] Basic shooting (ChangeFireStateMessage)

### Milestone 4: Smart Bot
- [ ] Target selection logic
- [ ] Retreat on low HP
- [ ] V-menu help requests

### Milestone 5: Team Bot
- [ ] Follow orders from Ace
- [ ] Respond to V-menu requests
- [ ] Coordinated behavior

---

## Questions for PSForever Devs

1. **Player without SessionActor**: Is this currently possible? What breaks?
2. **Bot flag**: Should we add `isBot: Boolean` to `Player` class?
3. **GUID allocation**: How do we get GUIDs for bot entities?
4. **Zone registration**: What's the proper way to add a player to a zone without client connection?
5. **Existing NPC code**: Is there any other AI code beyond `AutomatedTurretBehavior`?

---

## File Structure (Proposed)

```
src/main/scala/net/psforever/
├── objects/
│   └── bot/
│       ├── Bot.scala              # Bot entity (extends Player?)
│       ├── BotClass.scala         # Class definitions (Driver, Support, etc.)
│       ├── BotLoadouts.scala      # Predefined loadouts per class
│       └── BotPersonality.scala   # Behavior weights
├── actors/
│   └── bot/
│       ├── BotActor.scala         # Main bot AI actor
│       ├── BotBehavior.scala      # Combat/detection trait
│       ├── BotMovement.scala      # Movement trait
│       ├── BotObjective.scala     # Objective handling trait
│       └── BotManager.scala       # Population management
└── services/
    └── bot/
        ├── BotService.scala       # Global bot coordination
        └── BotVoiceCoordinator.scala  # V-menu coordination
```
