# PlanetSide Bot Behavior & Game Feel

## Vision System

**Field of View**: 60 degrees horizontal and vertical
- Adjustable variable, expected final range: 60-90 degrees
- Bots cannot see beyond this cone - must turn to acquire targets
- Creates realistic "blind spots" and flanking opportunities for players

### Spotting Mechanics

**Full Spot**: Clear visual on target, can identify and engage
- Time to acquire scales with distance:
  - Close range: Very fast (nearly instant)
  - Long range: Takes longer to confirm target

**Partial Spot**: Know something is there but can't fully engage
- Target went behind cover (saw them go there)
- See tracers coming from a direction (investigate)
- Heard gunfire/explosions from location
- **Behavior**: Move towards OR around to get better angle

---

## Core Decision Logic

```
SPOTTED TARGET?
├── Partial spot?
│   └── Move towards target OR move around target (reposition for better view)
├── Full spot?
│   └── ATTACK or COMPLETE OBJECTIVE
│       (enter vehicle, unlock door, hack terminal, etc.)
└── Cannot proceed?
    └── REQUEST HELP (V-menu)
        └── State what you need (VNG, VNH, etc.)
```

---

## V-Menu Communication System

Bots use the in-game quick voice system to communicate, creating authentic battlefield chatter.

### Voice Commands Used

**Requests & Responses**
| Command | Meaning | Use Case |
|---------|---------|----------|
| VVV | "HELP!" | General distress, need assistance |
| VNG | "Need Gunner" | Vehicle waiting for gunner before proceeding |
| VNH | "Need Hacker" | Door locked, can't proceed, need hacker |
| VVY | "Yes" | Acknowledging help request, on my way |
| VVB | Taunt (variant B) | Vengeance kill, celebration |
| VVZ | Taunt (variant Z) | Vengeance kill, celebration |

**Warnings (Intel Sharing)**
| Command | Meaning | Use Case |
|---------|---------|----------|
| VWA | "Warning: Aircraft!" | Enemy air incoming, triggers AA MAX spawns |
| VWV | "Warning: Vehicles!" | Enemy armor incoming, triggers AV MAX spawns |
| VWT | "Warning: Troops!" | Enemy infantry push, triggers AI MAX spawns |

### Help Request Flow
1. Bot encounters obstacle it cannot handle alone
2. Bot broadcasts appropriate V-menu request
3. Capable bots in range evaluate:
   - Can I help with this? (Do I have the cert/ability?)
   - Can I path to them? (Pull `/loc` of requester, check route)
4. If yes to both:
   - Respond with `VVY`
   - Begin movement to assist
5. **Result**: Organic teamwork feel, tactical advantage, immersion

---

## Bot Classes

PlanetSide 1 has no rigid classes, but bots need defined roles for sanity. Each class is a **pre-built loadout** with associated behavior logic.

### Class Definitions

| Class | Role | Key Abilities | Notes |
|-------|------|---------------|-------|
| **Driver** | Vehicle operation | Drive/pilot vehicles | Low priority on VNG (see below) |
| **Support** | Heal & Repair | Medical applicator, BANK/Nano repair | LOVES gunning (can heal vehicle) |
| **Hacker** | Infiltration & Capture | REK, door unlocking, base/tower/vehicle hacking | Critical for objective play |
| **AV** | Anti-Vehicle | Decimator, Lancer, Phoenix, Striker? | Tank hunters |
| **MAX** | Heavy Exosuit | Faction MAX suits (AI/AV/AA variants) | Walking tanks |
| **Vet** | Versatile Veteran | HA + AV + Basic Hacking + Basic Support | Jack of all trades |
| **Ace** | Empire Leader | Best at everything + Command authority | ONE per empire, special role |

### Vet Class Details
- Can do *most* things but slower/weaker than specialists
- Won't get stuck at locked doors (can hack, just slower)
- Won't panic at incoming tank (has AV, can fight back)
- Field-flexible, self-sufficient
- Good baseline "competent soldier" behavior

### Ace Class Details
- One per empire (TR Ace, NC Ace, VS Ace)
- **Last bot to logout** as real players join
- Uses **Command Chat** (CR3+ channel) for tactical orders
- Makes strategic decisions for bot team:
  - Where to attack
  - Where to defend
  - Force distribution
- **CRITICAL**: Defers to real players
  - Won't overstep human commanders
  - Offers advice if no player is commanding
  - Lets players make the calls when they want to

### MAX Class Details
**Situational Loadout Switching** based on intel:
- **Indoors** (last death location or teammate intel): AI MAX
- **VWV received** (Warning: Vehicles): AV MAX
- **VWA received** (Warning: Aircraft): AA MAX
- **VWT received** (Warning: Troops) + outdoors: Context-dependent

This creates reactive, intelligent heavy support that adapts to battlefield needs.

### Driver & Gunner Dynamics
- **Anyone** can be a gunner - it's not role-restricted
- **Drivers** respond to VNG but low priority (they want to drive)
- **Support** actively seeks gunner seats - can heal vehicle while gunning
- **Drivers** can also heal their own vehicle but teamwork = faster repairs
- Vehicles wait for gunner before proceeding (VNG call) for effectiveness

---

## Attitude & Vengeance System

Gives Vet+ bots personality and memorable behavior.

### Attitude Stat
- Internal emotional state of the bot
- Affects decision-making and aggression

### Vengeance Mechanic
1. **Death Memory**: Vet remembers where they died and who killed them
2. **Revenge Decision**: Based on attitude, may decide to hunt killer
3. **Constraints**:
   - Must be reasonable range (same base spawn area)
   - No cross-continent adventures
4. **On Successful Revenge**:
   - Taunt victim (VVB or VVZ)
   - Attitude decreases (calms down)
5. **On Repeated Death (no revenge)**:
   - Attitude increases ("rage" building)
   - **Rage Effects**:
     - Increased accuracy
     - Increased aggression
6. **Rage Reset Conditions**:
   - Achieve vengeance
   - Get a 3-kill streak
   - Participate in base capture

---

## Movement Patterns

Movement varies by experience level and empire. Critical for not looking like a bot.

### By Experience Level

**New Players / Basic Bots**
- Run mostly straight at target
- Held strafe to maintain firing angle (not ADAD, just constant drift)
- Minimal evasion, focused on keeping crosshairs on target

**Vets & Aces**
- Heavy ADAD strafing (left-right-left-right)
- Combined with crouch spam during firefights
- Much harder to hit, more "tryhard" movement

### Empire-Specific Movement
- **NC**: Benefits from closing distance (shotguns) - more aggressive advance
- **TR/VS**: TBD - circle back when adding faction identity

### What NOT To Do
- **Almost no jumping** - PS players don't bunny hop
- Exception: MAXes might jump to dodge AV rockets (ADVANCED - maybe skip for v1)
- Robotic pathing, perfect angles, inhuman reaction times

### Tuning Note
> "I don't know what [bot movement] would feel like yet, we've never had bots before. I will help dial this in with many many play tests."

Movement feel will require extensive playtesting iteration.

---

## Retreat & Self-Preservation

### When Bots Retreat

| Class | Retreat Trigger |
|-------|-----------------|
| **Vets** | Low HP |
| **Drivers** | Low vehicle HP (save the vehicle!) |
| **Everyone** | Out of ammo (not just reloading - actually empty) |

### Order Context Matters

**If orders = DEFEND:**
- Stay at position no matter what (unless need supplies)
- Quick runs to spawn room / nearest terminal for ammo/resupply
- Return immediately to defensive position

**If orders = ATTACK:**
- More flexible retreat for self-preservation
- Regroup and push again

### What Retreat Looks Like
- Not a panicked sprint
- Backing away while facing threat when possible
- Finding cover, then breaking line of sight

---

## Combat & Accuracy System

Bot shooting should feel natural, not robotic. Two opposing forces create a "sweet spot" in sustained fire.

### Target Recognition Time

Before engaging, bots must **recognize** their target. Time to recognize scales with distance:

| Distance | Recognition Time | Notes |
|----------|-----------------|-------|
| **Close** (< 15m) | Near instant | Light them up immediately |
| **Medium** (15-50m) | 0.3-0.8 sec | Brief pause, then engage |
| **Far** (50m+) | 1-2 sec | Takes time to confirm target |
| **Obscured** | Investigate | Move to get better angle (defer to navmesh) |

### Accuracy System (Two Forces)

**Force 1: Adjustment (Accuracy improves)**
- First shots are inaccurate (bot is "dialing in" aim)
- Accuracy increases with each shot as bot finds the target
- Close targets = higher base accuracy, faster adjustment
- Far targets = lower base accuracy, slower adjustment

**Force 2: Recoil (Spread worsens)**
- Each shot adds recoil
- Recoil accumulates, making spread worse over time
- Creates "diminishing returns" on sustained fire

### The Sweet Spot

```
Accuracy over time during sustained fire:

       ↑ Accuracy
       │         ╭──────╮
       │        ╱        ╲
       │      ╱            ╲
       │    ╱                ╲  ← recoil takes over
       │  ╱
       │╱  ← adjusting aim
       └────────────────────────→ Time/Shots
           First    Middle    Late
           shots    shots     shots
           (miss)   (HIT!)    (spray)
```

**Result by distance:**
- **Close range**: High base accuracy + fast adjustment = nearly all shots hit
- **Medium range**: Sweet spot matters - middle of burst is most dangerous
- **Long range**: May only land a few hits early (lucky) or middle (adjusted)

### Class Modifiers (Future)

| Class | Accuracy Modifier | Notes |
|-------|------------------|-------|
| Basic/Newbie | 1.0x (baseline) | Average accuracy |
| Vet | 1.3x accuracy, 0.8x recoil | Much better aim |
| Ace | 1.5x accuracy, 0.7x recoil | Best of the best |
| Support | 0.9x accuracy | Focused on healing |
| MAX | 0.8x accuracy, 1.2x recoil | Big guns, more spray |

### Practical Implications

1. **Players should win fair fights** - bots aren't aimbots
2. **Getting close is dangerous** - bots don't miss at point blank
3. **Distance = safety** - but not immunity (lucky shots happen)
4. **Burst fire beats spray** - short controlled bursts reset recoil
5. **Flanking works** - recognition time gives advantage

---

## The Chaos Factor

> "Spam? Spam. Be it bullets, grenades, the voice menus it's all about the chaotic spam. It's a war unlike the world has ever recreated."

### What Makes PS Fights Feel Like PS
- **Bullet spam**: Walls of tracers, suppressive fire is real
- **Grenade spam**: Explosions everywhere
- **Voice spam**: V-menu callouts constantly firing
- **Scale**: Hundreds of participants, not 32v32

### Bot Contribution to Chaos
- Bots should ADD to the chaos, not feel sterile
- Coordinated V-menu chatter (see calibration below)
- Miss shots (don't be laser accurate)
- React to nearby explosions/deaths
- Fire at enemies even when hit chance is low (suppression)

---

## Damage Reaction

How bots respond to taking damage depends on context.

### In Combat (Already Engaged)
- **Direct bullet hit from new direction**: May turn toward new threat OR smart-switch targets
- **Explosions/grenades**: Ignore direction (damage comes from everywhere) - stay on current target
- **Decision**: Stick with current target vs switch based on threat assessment

### Out of Combat (Doing Task)
Example: Repairing a vehicle, then gets shot

1. **PANIC** - immediate state change
2. **Run to cover** - most likely the vehicle they were repairing
3. **Swap to weapon** while running
   - Drivers: Have sidearm ready (fast)
   - Support: Must swap from repair tool (slower, panicked weapon swap animation)
4. **Engage threat** once in cover with weapon out

### Key Feel
- The "panic run while swapping weapons" is authentic PS behavior
- Not a clean tactical response - messy, human reaction
- Repair slot = weapon slot means class differences in response time

---

## V-Menu Calibration

Balancing authentic chatter vs annoying spam.

### Always Fire (Functional)
| Type | When | Purpose |
|------|------|---------|
| **Needs** (VNG, VNH, VVV) | When actually needed | Gameplay function |
| **Warnings** (VWA, VWV, VWT) | When threat spotted | Intel sharing |
| **Acknowledgment** (VVY) | When responding to request | Coordination |

### Rare/Conditional
| Type | When | Frequency |
|------|------|-----------|
| **Taunts** (VVB, VVZ) | Vengeance kills, domination | Low |
| **Celebrations** (VVF, VVE) | Exceptional events only | Very controlled |

### Celebration Events
Triggers for VVF ("Fantastic!") and VVE ("Excellent!"):
- Pesky vehicle destroyed
- Killed someone with 10+ kill streak
- Base capture

### Coordinated Celebration System
**Problem**: 50 bots all saying "VVE!" at once = annoying
**Solution**: Backend coordination

```
EVENT: Base captured

1. RNG determines responder count (1-6 bots)
2. Eligible bots (alive, in range) "claim" slots via backend
3. Each responder gets RNG delay timer
4. Staggered, natural response

EXAMPLE TIMELINE:
  0.01s - Bot 1: "VVE" (Excellent!)
  0.30s - Bot 2: "VVF" (Fantastic!)
  1.20s - Bot 3: "VVF"
  1.30s - Bot 4 (Vet): "VVB VVF" ("You can't beat me." + "Outstanding!")
```

### Vet/Ace Flavor
- Vets and Aces can do **combo callouts** (taunt + celebration)
- Adds personality, shows experience
- E.g., "You can't beat me." followed by "Outstanding!"

---

## Open Questions / Needs Expansion

### Resolved
- [x] "Partial spot" vs full spot - distance + time based, also tracers/intel
- [x] Driver class VNG response - low priority, anyone can gun
- [x] MAX variants - switches based on intel (VWA/VWV/VWT, indoor/outdoor)
- [x] Movement patterns - ADAD for vets, straight-run for newbies
- [x] Retreat behavior - HP, ammo, vehicle HP triggers

### Deferred (Future Phases)
- [ ] **FACTION TACTICS**: TR vs NC vs VS combat style differences (post-v1)
- [ ] **MAX jumping to dodge AV**: Advanced behavior, maybe skip

### Resolved (Round 2)
- [x] **REACTION TO DAMAGE**: Context-dependent (see Damage Reaction section)
- [x] **V-menu calibration**: Coordinated celebration system (see V-menu section)

---

## Implementation Notes

### Server Integration Questions
- Can bots trigger V-menu voice commands through server?
- Is Command Chat accessible for bot messages?
- Is CR (Command Rank) system implemented in emulator?

### Technical Considerations
- `/loc` command - server-side coordinate access for pathfinding
- Death tracking - need to log killer + location per bot
- Attitude persistence - per-session or reset on logout?
