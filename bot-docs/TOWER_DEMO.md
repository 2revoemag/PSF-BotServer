# Tower Attack Demo

## Overview
A controlled demonstration of the bot combat system at a single tower location. By manually capturing coordinates, we create a bounded "arena" where bots behave correctly without needing full world geometry.

## Why This Approach
- **Wall shooting problem**: Bots currently shoot through walls (no server-side collision)
- **Full solution is massive**: UBR mesh extraction + navmesh generation = huge project
- **POC needs to be demonstrable**: Bots shooting through 4 concrete walls isn't a demo, it's a bug showcase
- **Scrappy but effective**: Manual coordinate capture gives us a working showcase

---

## Implementation Plan

### Step 1: Choose a Tower
Pick a tower with good sight lines and clear boundaries:
- [ ] Select tower location (user to provide)
- [ ] Capture tower coordinates via `/loc` command in-game

### Step 2: Define Combat Arena
Capture boundary coordinates by walking the perimeter:

```
/loc at each corner point:
- Northwest corner
- Northeast corner
- Southeast corner
- Southwest corner
- Height bounds (ground level, top platform)
```

### Step 3: Implement Arena System

```scala
// Combat arena definition
case class CombatArena(
  minX: Float, maxX: Float,
  minY: Float, maxY: Float,
  minZ: Float, maxZ: Float,
  name: String = "unnamed"
)

// Check if position is within arena bounds
def isInArena(pos: Vector3, arena: CombatArena): Boolean = {
  pos.x >= arena.minX && pos.x <= arena.maxX &&
  pos.y >= arena.minY && pos.y <= arena.maxY &&
  pos.z >= arena.minZ && pos.z <= arena.maxZ
}

// Only engage if BOTH bot AND target are in the arena
// This prevents shooting through walls into/out of the arena
def findTarget(botState: BotState): Option[Player] = {
  val arena = currentArena // loaded from config

  zone.LivePlayers.filter { p =>
    isInArena(botState.player.Position, arena) &&
    isInArena(p.Position, arena) &&
    p.isAlive &&
    p.Faction != botFaction &&
    // ... other existing checks
  }
}
```

### Step 4: Optional - Interior Exclusion Zones
Define boxes for building interiors where bots won't engage:

```scala
case class ExclusionZone(
  minX: Float, maxX: Float,
  minY: Float, maxY: Float,
  minZ: Float, maxZ: Float,
  name: String = "interior"
)

// Skip targets in exclusion zones
def isInExclusionZone(pos: Vector3, zones: Seq[ExclusionZone]): Boolean = {
  zones.exists { zone =>
    pos.x >= zone.minX && pos.x <= zone.maxX &&
    pos.y >= zone.minY && pos.y <= zone.maxY &&
    pos.z >= zone.minZ && pos.z <= zone.maxZ
  }
}
```

---

## Demo Scenario

### Setup
1. Spawn 3-5 NC bots around tower perimeter
2. Player approaches as TR/VS
3. Bots engage when player enters arena bounds
4. Combat feels natural - no wall shooting because everyone is in open area

### What to Show
- Bots spot player and react (2-second delay)
- Bots track and engage target
- Accuracy/recoil system visible in hit patterns
- Bots die when killed, respawn after delay
- Weapon drawn, muzzle flash, sound effects

### Known Limitations for Demo
- [ ] No tracers (investigating - may be client-side animation issue)
- [ ] Bots don't navigate around obstacles (random wandering only)
- [ ] Single hardcoded arena location

---

## Future: Scaling Beyond One Tower

Once the single-tower demo works, the approach can scale:

1. **Multiple arenas**: Define several towers/bases as combat zones
2. **Config file**: Load arena definitions from JSON/YAML
3. **Auto-generation**: Eventually parse map data for structure bounds
4. **Full solution**: UBR mesh extraction for complete world collision

---

## Coordinate Capture Workflow

When ready to capture tower coordinates:

```
1. Log into game, go to chosen tower
2. Walk to each boundary corner, type /loc
3. Record coordinates in format:

   Tower: [Name]
   Continent: [home1/home2/etc]

   Boundary Points:
   - NW: (x, y, z)
   - NE: (x, y, z)
   - SE: (x, y, z)
   - SW: (x, y, z)

   Height Bounds:
   - Ground: z = [value]
   - Top: z = [value]

   Optional Interior Exclusions:
   - Room 1: (minX, minY, minZ) to (maxX, maxY, maxZ)
```

---

## Tracer Investigation Notes

**Current status**: No visible tracers despite multiple approaches

**What we've tried:**
- AvatarEvents with AvatarAction.ChangeFireState_Start
- LocalEvents with LocalAction.SendResponse(ChangeFireStateMessage_Start)
- Adding WeaponFireMessage broadcast

**Hypothesis**:
Tracers for hitscan weapons may be purely client-side, calculated from:
1. ChangeFireState (weapon is firing)
2. PlayerState position/orientation
3. Client renders tracer based on facing direction

**Why it might not work for bots**:
- Missing animation state that triggers tracer rendering
- Client needs specific packet sequence it's not receiving
- Suppressor weapon specifically may handle differently

**Lower priority for now** - focus on arena system first.

---

## Status

- [ ] Tower location selected
- [ ] Coordinates captured
- [ ] Arena bounds implemented
- [ ] Exclusion zones defined (optional)
- [ ] Demo tested and working
- [ ] Video/screenshots captured
