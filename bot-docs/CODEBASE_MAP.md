# PSF-LoginServer Codebase Map

> **Purpose**: Quick reference for understanding the PlanetSide server emulator codebase.
> This document maps key files, line numbers, and relationships for bot implementation.

## Quick Reference

| Concept | File | Key Lines | Notes |
|---------|------|-----------|-------|
| Player Entity | `objects/Player.scala` | 29-150 | Main player class |
| Player Behavior | `objects/avatar/PlayerControl.scala` | Full file | Akka actor for damage/death/equipment |
| Avatar Data | `objects/avatar/Avatar.scala` | 130-205 | Persistent character data (certs, loadouts) |
| Session Handling | `actors/session/SessionActor.scala` | 99-400 | Network packet processing (NOT needed for bots) |
| Zone Population | `objects/zones/ZonePopulationActor.scala` | 30-85 | Join/Spawn/Leave/Release flow |
| GUID Registration | `objects/guid/GUIDTask.scala` | 185-204 | `registerAvatar()` for full registration |
| Position Broadcast | `services/avatar/AvatarServiceMessage.scala` | 83-97 | `AvatarAction.PlayerState` |
| Turret AI Reference | `objects/serverobject/turret/auto/AutomatedTurretBehavior.scala` | Full file | Example of existing AI pattern |

---

## Core Entity System

### Player.scala
**Path**: `src/main/scala/net/psforever/objects/Player.scala`

```
Line 29-41:   class Player extends PlanetSideServerObject with many traits
Line 54-70:   Private state (armor, capacitor, exosuit, holsters, inventory)
Line 72-78:   Movement state (facingYawUpper, crouching, jumping, cloaked, afk)
Line 123-133: Spawn() - resurrects a dead player
Line 135-139: Die() - kills the player
Line 141-147: Revive() - revives without full reset
```

**Key Traits Mixed In**:
- `Vitality` - health/damage system
- `FactionAffinity` - TR/NC/VS
- `Container` - inventory
- `ZoneAware` - knows which continent it's on
- `MountableEntity` - can sit in vehicles

### Avatar.scala
**Path**: `src/main/scala/net/psforever/objects/avatar/Avatar.scala`

```
Line 85-87:   Factory method Avatar(charId, name, faction, sex, head, voice)
Line 130-151: case class Avatar with all fields:
              - id: Int (DB row ID - could use negative for bots)
              - basic: BasicCharacterData (name, faction, sex, head, voice)
              - bep/cep: Experience points
              - certifications: Set[Certification]
              - implants, shortcuts, locker, deployables
              - loadouts, cooldowns, scorecard
Line 155-156: br/cr: Battle Rank and Command Rank derived from BEP/CEP
```

**Important**: `Avatar.id` is described as "unique identifier corresponding to a database table row index". For bots, we can use negative IDs to avoid collision.

### PlayerControl.scala
**Path**: `src/main/scala/net/psforever/objects/avatar/PlayerControl.scala`

This is an **Akka Actor** that handles player behavior.

```
Key behaviors it handles:
- Damage intake and death
- Healing (from equipment, implants)
- Equipment changes
- Environment interaction (water, lava, radiation)
- Jamming effects
```

**Critical**: When `Zone.Population.Spawn()` is called, it creates a `PlayerControl` actor for the player:
```scala
// From ZonePopulationActor.scala line 60-63
player.Actor = context.actorOf(
  Props(classOf[PlayerControl], player, avatarActor),
  name = GetPlayerControlName(player, None)
)
```

---

## Zone & Population System

### Zone.scala
**Path**: `src/main/scala/net/psforever/objects/zones/Zone.scala`

Key properties:
- `LivePlayers` - list of alive players in zone
- `Corpses` - list of dead player backpacks
- `Population` - ActorRef to ZonePopulationActor
- `AvatarEvents` - ActorRef to AvatarService for broadcasting
- `GUID` - UniqueNumberOps for GUID allocation

### ZonePopulationActor.scala
**Path**: `src/main/scala/net/psforever/objects/zones/ZonePopulationActor.scala`

**This is critical for bot spawning.**

```
Line 31-34:   Zone.Population.Join(avatar)
              - Adds avatar.id to playerMap with None value
              - Starts player management systems if first player

Line 36-50:   Zone.Population.Leave(avatar)
              - Removes from playerMap
              - Cleans up player position, block map

Line 52-71:   Zone.Population.Spawn(avatar, player, avatarActor)
              - Associates player with avatar in playerMap
              - Sets player.Zone = zone
              - CREATES PlayerControl actor (line 60-63)
              - Adds to block map

Line 73-84:   Zone.Population.Release(avatar)
              - Disassociates player from avatar
              - Used when player dies/respawns
```

---

## GUID System

### GUIDTask.scala
**Path**: `src/main/scala/net/psforever/objects/guid/GUIDTask.scala`

```
Line 72-73:   registerObject() - basic GUID registration
Line 102-107: registerTool() - register weapon + ammo boxes
Line 125-130: registerEquipment() - dispatches to appropriate registrar
Line 200-204: registerPlayer() - registers player + holsters + inventory

Usage:
  TaskWorkflow.execute(GUIDTask.registerPlayer(zone.GUID, player))
```

---

## Broadcasting System

### AvatarServiceMessage.scala
**Path**: `src/main/scala/net/psforever/services/avatar/AvatarServiceMessage.scala`

```
Line 20:      AvatarServiceMessage(forChannel: String, actionMessage: AvatarAction.Action)

Line 83-97:   AvatarAction.PlayerState - position/orientation broadcast
              Fields: player_guid, pos, vel, facingYaw, facingPitch,
                      facingYawUpper, timestamp, is_crouching, is_jumping,
                      jump_thrust, is_cloaked, spectator, weaponInHand
```

**To broadcast bot position**:
```scala
zone.AvatarEvents ! AvatarServiceMessage(
  zone.id,
  AvatarAction.PlayerState(
    player.GUID,
    player.Position,
    Some(velocity),
    facingYaw, facingPitch, facingYawUpper,
    timestamp,
    is_crouching, is_jumping, jump_thrust,
    is_cloaked, spectator = false, weaponInHand
  )
)
```

### Other Important AvatarActions
```
AvatarAction.LoadPlayer          - Creates player on clients (spawn)
AvatarAction.ObjectDelete        - Removes player from clients (despawn)
AvatarAction.ChangeFireState_Start/Stop - Weapon firing
AvatarAction.Killed              - Death notification
AvatarAction.HitHint             - Damage indicator
```

---

## Existing AI Pattern

### AutomatedTurretBehavior.scala
**Path**: `src/main/scala/net/psforever/objects/serverobject/turret/auto/AutomatedTurretBehavior.scala`

**NOT the behavior we want** (turrets are mechanical), but useful as a **technical reference** for:
- Akka actor message patterns
- Target tracking state management
- Periodic checks via scheduler
- Damage/retaliation responses

```
Line 26-27:   trait AutomatedTurretBehavior { _: Actor with DamageableEntity =>
              Shows how to mix behavior into an actor

Line 51-54:   Timer pattern for periodic checks:
              context.system.scheduler.scheduleWithFixedDelay(...)

Line 145-154: Target list management (AddTarget, RemoveTarget, Detected)
Line 245-258: Engagement flow (engageNewDetectedTarget)
```

---

## Session System (Reference Only)

Bots don't need SessionActor, but understanding it helps:

### SessionActor.scala
**Path**: `src/main/scala/net/psforever/actors/session/SessionActor.scala`

```
Line 99:      class SessionActor - receives from middlewareActor (network)
Line 115:     def receive = startup (initial state)
Line 168-171: parse() - handles PlanetSideGamePacket from client
Line 372+:    handleGamePkt() - dispatches packets to handlers
```

**Key insight**: SessionActor's job is handling network I/O. Bots generate actions internally, so we don't need this.

### AvatarActor.scala
**Path**: `src/main/scala/net/psforever/actors/session/AvatarActor.scala`

Handles avatar persistence, certification management, loadouts, etc.

```
Line 70-88:   Factory and commands
Line 81:      apply() creates the actor with sessionActor reference
```

**For bots**: We need a stub `BotAvatarActor` that accepts the messages `PlayerControl` might send but doesn't persist anything.

---

## SpawnPoint System

### SpawnPoint.scala
**Path**: `src/main/scala/net/psforever/objects/SpawnPoint.scala`

```
Line 11-70:   trait SpawnPoint
              - GUID, Position, Orientation
              - SpecificPoint(target) -> (Vector3, Vector3) for spawn pos/orient

Line 72-160:  object SpawnPoint
              - Default, Tube, AMS, Gate spawn point calculations
```

---

## File Locations Summary

```
src/main/scala/net/psforever/
├── actors/
│   ├── session/
│   │   ├── SessionActor.scala          # Network session (NOT for bots)
│   │   ├── AvatarActor.scala           # Avatar persistence
│   │   └── support/
│   │       ├── SessionData.scala       # Session state
│   │       └── ZoningOperations.scala  # Spawn flow reference
│   └── zone/
│       └── ZoneActor.scala             # Zone management
├── objects/
│   ├── Player.scala                    # Player entity
│   ├── SpawnPoint.scala               # Spawn locations
│   ├── avatar/
│   │   ├── Avatar.scala               # Character data
│   │   └── PlayerControl.scala        # Player behavior actor
│   ├── guid/
│   │   └── GUIDTask.scala             # GUID registration
│   ├── serverobject/turret/auto/
│   │   └── AutomatedTurretBehavior.scala  # AI reference
│   └── zones/
│       ├── Zone.scala                 # Zone class
│       └── ZonePopulationActor.scala  # Population management
└── services/
    └── avatar/
        ├── AvatarService.scala        # Broadcasting service
        └── AvatarServiceMessage.scala # Message definitions
```

---

## Quick Patterns

### Spawn a Player (Derived from Real Flow)
```scala
// 1. Create avatar
val avatar = Avatar(botId, name, faction, sex, head, voice)

// 2. Create player
val player = new Player(avatar)
player.Position = spawnPosition
player.Spawn()

// 3. Register GUIDs
TaskWorkflow.execute(GUIDTask.registerPlayer(zone.GUID, player))

// 4. Join zone
zone.Population ! Zone.Population.Join(avatar)

// 5. Spawn (creates PlayerControl)
zone.Population ! Zone.Population.Spawn(avatar, player, botAvatarActor)

// 6. Broadcast existence
zone.AvatarEvents ! AvatarServiceMessage(zone.id, AvatarAction.LoadPlayer(...))
```

### Broadcast Position
```scala
zone.AvatarEvents ! AvatarServiceMessage(
  zone.id,
  AvatarAction.PlayerState(player.GUID, pos, vel, yaw, pitch, yawUpper,
                           timestamp, crouch, jump, thrust, cloak, false, weaponOut)
)
```

### Despawn a Player
```scala
zone.Population ! Zone.Population.Leave(avatar)
zone.AvatarEvents ! AvatarServiceMessage(zone.id, AvatarAction.ObjectDelete(guid, guid))
TaskWorkflow.execute(GUIDTask.unregisterAvatar(zone.GUID, player))
```

---

## Bot Implementation - Lessons Learned

### Critical: Use `registerAvatar` NOT `registerPlayer`
`PlayerControl` creates a `LockerContainerControl` actor in its constructor (line 74-80) which calls `PlanetSideServerObject.UniqueActorName(locker)`. This requires `locker.GUID` to be assigned.

- `registerPlayer()` skips locker registration
- `registerAvatar()` includes locker registration
- **Always use `registerAvatar()` for bots**

### Critical: Avatar IDs Must Be Positive
Packet encoding uses 32-bit unsigned integers. Negative IDs cause:
```
ERROR: basic_appearance/a/unk6: -1 is less than minimum value 0
```
**Solution**: Use high positive IDs (900000+) to avoid collision with real DB player IDs.

### Critical: GUID Registration is Async
`TaskWorkflow.execute()` returns a `Future[Any]`. You MUST wait for completion before:
- Accessing `player.GUID`
- Calling `Zone.Population.Spawn()`
- Broadcasting `LoadPlayer`

```scala
TaskWorkflow.execute(GUIDTask.registerAvatar(zone.GUID, player)).onComplete {
  case Success(_) =>
    // NOW safe to use player.GUID and spawn
    self ! CompleteSpawn(...)
  case Failure(ex) =>
    log.error(s"GUID registration failed: ${ex.getMessage}")
}
```

### AvatarActor Must Be Typed
`Zone.Population.Spawn` expects `ActorRef[AvatarActor.Command]` (typed), not classic `ActorRef`.
Use Akka typed actor system:
```scala
val typedSystem = context.system.toTyped
val botAvatarActor: ActorRef[AvatarActor.Command] =
  typedSystem.systemActorOf(BotAvatarActor(), s"bot-avatar-$botId")
```

### Bot Files Created
```
src/main/scala/net/psforever/actors/bot/
├── BotAvatarActor.scala   # Stub typed actor - absorbs AvatarActor messages
└── BotManager.scala       # Spawns/manages bots, handles async GUID flow
```

### Working Spawn Flow (Verified)
```scala
// 1. Create avatar with HIGH POSITIVE ID
val avatar = Avatar(900000, BasicCharacterData(name, faction, sex, head, voice))

// 2. Create player
val player = new Player(avatar)
player.Position = position
player.Spawn()

// 3. Create typed stub avatar actor
val botAvatarActor = typedSystem.systemActorOf(BotAvatarActor(), name)

// 4. Register GUIDs (ASYNC!) - includes locker
TaskWorkflow.execute(GUIDTask.registerAvatar(zone.GUID, player)).onComplete {
  case Success(_) =>
    // 5. Join zone
    zone.Population ! Zone.Population.Join(avatar)
    // 6. Spawn (creates PlayerControl)
    zone.Population ! Zone.Population.Spawn(avatar, player, botAvatarActor)
    // 7. Broadcast to clients
    zone.AvatarEvents ! AvatarServiceMessage(zone.id, AvatarAction.LoadPlayer(...))
}
```
