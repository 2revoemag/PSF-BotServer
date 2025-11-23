// SKETCH - NOT PRODUCTION CODE
// This is a conceptual exploration of what BotActor might look like

package net.psforever.actors.bot

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import net.psforever.objects.Player
import net.psforever.objects.avatar.Avatar
import net.psforever.objects.zones.Zone
import net.psforever.services.avatar.{AvatarAction, AvatarServiceMessage}
import net.psforever.types.{PlanetSideGUID, Vector3}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * BotActor - Controls a single bot entity
 *
 * Unlike SessionActor (which handles network packets from a client),
 * BotActor makes decisions internally and broadcasts state to other players.
 *
 * Key differences from SessionActor:
 * - No middlewareActor (no network connection)
 * - No incoming packets to process
 * - Generates actions based on AI logic
 * - Still broadcasts via zone.AvatarEvents (same as real players)
 */
class BotActor(
  player: Player,
  avatar: Avatar,
  zone: Zone,
  botClass: BotClass,
  botPersonality: BotPersonality
) extends Actor {

  // Tick timer - how often bot makes decisions and broadcasts state
  private var tickTimer: Cancellable = _

  // Timestamp counter for PlayerStateMessage
  private var timestamp: Int = 0

  // Current AI state
  private var currentTarget: Option[Player] = None
  private var currentObjective: Option[BotObjective] = None
  private var attitude: Float = 0.5f  // 0 = calm, 1 = raging

  // Death memory for vengeance system
  private var lastDeathLocation: Option[Vector3] = None
  private var lastKiller: Option[PlanetSideGUID] = None

  override def preStart(): Unit = {
    // Start the tick loop
    // 10 FPS = 100ms, could go lower for distant bots
    tickTimer = context.system.scheduler.scheduleWithFixedDelay(
      initialDelay = 100.millis,
      delay = 100.millis,  // 10 ticks per second
      receiver = self,
      message = BotActor.Tick
    )
  }

  override def postStop(): Unit = {
    tickTimer.cancel()
  }

  def receive: Receive = {
    case BotActor.Tick =>
      tick()

    case BotActor.TakeDamage(amount, source, sourcePosition) =>
      handleDamage(amount, source, sourcePosition)

    case BotActor.Die(killer) =>
      handleDeath(killer)

    case BotActor.Respawn(spawnPoint) =>
      handleRespawn(spawnPoint)

    case BotActor.ReceiveOrder(order) =>
      handleOrder(order)

    case BotActor.HelpRequest(requester, helpType, location) =>
      handleHelpRequest(requester, helpType, location)
  }

  /**
   * Main AI tick - called every 100ms (10 FPS)
   */
  private def tick(): Unit = {
    if (!player.isAlive) return

    timestamp = (timestamp + 1) % 65536

    // 1. Perception - what can we see?
    val visibleTargets = detectTargets()

    // 2. Decision - what should we do?
    val action = decideAction(visibleTargets)

    // 3. Execute - do the thing
    executeAction(action)

    // 4. Broadcast - tell everyone where we are
    broadcastState()
  }

  /**
   * Detect targets within vision cone
   */
  private def detectTargets(): Seq[Player] = {
    val myPos = player.Position
    val myFacing = player.Orientation.z
    val fovHalf = botPersonality.fovDegrees / 2
    val maxRange = botPersonality.detectionRange

    zone.LivePlayers
      .filter(p => p != player)
      .filter(p => p.Faction != player.Faction)  // enemies only
      .filter(p => p.isAlive)
      .filter { target =>
        val targetPos = target.Position
        val distance = Vector3.Distance(myPos, targetPos)
        val angle = calculateAngle(myPos, targetPos, myFacing)

        distance <= maxRange && math.abs(angle) <= fovHalf
      }
      .toSeq
      .sortBy(t => Vector3.DistanceSquared(myPos, t.Position))
  }

  /**
   * Decide what action to take based on current state and perception
   */
  private def decideAction(visibleTargets: Seq[Player]): BotAction = {
    // Check health - should we retreat?
    if (player.Health < player.MaxHealth * botPersonality.retreatThreshold) {
      return BotAction.Retreat
    }

    // Check ammo - need resupply?
    if (isOutOfAmmo()) {
      return BotAction.Resupply
    }

    // Have a target?
    visibleTargets.headOption match {
      case Some(target) =>
        currentTarget = Some(target)
        BotAction.Attack(target)

      case None =>
        currentTarget = None
        // Follow objective or patrol
        currentObjective match {
          case Some(obj) => BotAction.FollowObjective(obj)
          case None => BotAction.Patrol
        }
    }
  }

  /**
   * Execute the decided action
   */
  private def executeAction(action: BotAction): Unit = action match {
    case BotAction.Attack(target) =>
      // Turn toward target
      val newFacing = calculateFacingToward(player.Position, target.Position)
      player.Orientation = Vector3(0, 0, newFacing)

      // Move based on bot type
      botPersonality.movementStyle match {
        case MovementStyle.Newbie =>
          // Run toward target with held strafe
          moveToward(target.Position, strafeOffset = 2f)
        case MovementStyle.Veteran =>
          // ADAD strafe
          moveWithADAD(target.Position)
        case MovementStyle.NCClose =>
          // Close distance aggressively (shotgun range)
          moveToward(target.Position, speed = 1.5f)
      }

      // Fire if we have line of sight
      if (hasLineOfSight(target)) {
        fireWeapon(target)
      }

    case BotAction.Retreat =>
      val retreatPosition = findRetreatPosition()
      moveToward(retreatPosition)

      // Call for help
      if (shouldCallForHelp()) {
        sendVoiceCommand("VVV")  // HELP!
      }

    case BotAction.Resupply =>
      val terminal = findNearestTerminal()
      terminal.foreach(moveToward)

    case BotAction.FollowObjective(obj) =>
      moveToward(obj.targetPosition)

    case BotAction.Patrol =>
      patrol()
  }

  /**
   * Broadcast current state to other players via AvatarService
   */
  private def broadcastState(): Unit = {
    zone.AvatarEvents ! AvatarServiceMessage(
      zone.id,
      AvatarAction.PlayerState(
        player.GUID,
        player.Position,
        Some(player.Velocity),
        player.Orientation.z,    // facingYaw
        player.Orientation.x,    // facingPitch
        player.facingYawUpper,   // upper body yaw
        timestamp,
        player.Crouching,
        player.Jumping,
        jumpThrust = false,
        player.Cloaked,
        spectator = false,
        weaponInHand = player.DrawnSlot != Player.HandsDownSlot
      )
    )
  }

  /**
   * Handle taking damage
   */
  private def handleDamage(amount: Int, source: PlanetSideGUID, sourcePosition: Vector3): Unit = {
    // If not in combat, react to damage
    if (currentTarget.isEmpty) {
      // Turn toward damage source
      val newFacing = calculateFacingToward(player.Position, sourcePosition)
      player.Orientation = Vector3(0, 0, newFacing)

      // Panic behavior based on class
      if (botClass.role == BotRole.Support || botClass.role == BotRole.Hacker) {
        // Panic! Run to cover, swap to weapon
        // (Support was probably repairing/healing)
      }
    }

    // Increase attitude if repeatedly dying to same source
    if (botPersonality.experienceLevel >= ExperienceLevel.Veteran) {
      lastKiller.foreach { killer =>
        if (killer == source) {
          attitude = math.min(1.0f, attitude + 0.1f)
        }
      }
    }
  }

  /**
   * Handle death
   */
  private def handleDeath(killer: PlanetSideGUID): Unit = {
    lastDeathLocation = Some(player.Position)
    lastKiller = Some(killer)

    // Veteran+ bots remember for vengeance
    if (botPersonality.experienceLevel >= ExperienceLevel.Veteran) {
      // Store vengeance target
    }
  }

  /**
   * Send a V-menu voice command
   */
  private def sendVoiceCommand(command: String): Unit = {
    // TODO: Send ChatMsg with voice command
    // zone.AvatarEvents ! ... ChatMsg ...
  }

  // Helper methods (stubs)
  private def calculateAngle(from: Vector3, to: Vector3, facing: Float): Float = ???
  private def calculateFacingToward(from: Vector3, to: Vector3): Float = ???
  private def moveToward(target: Vector3, strafeOffset: Float = 0f, speed: Float = 1f): Unit = ???
  private def moveWithADAD(target: Vector3): Unit = ???
  private def hasLineOfSight(target: Player): Boolean = ???
  private def fireWeapon(target: Player): Unit = ???
  private def isOutOfAmmo(): Boolean = ???
  private def findRetreatPosition(): Vector3 = ???
  private def findNearestTerminal(): Option[Vector3] = ???
  private def shouldCallForHelp(): Boolean = ???
  private def patrol(): Unit = ???
}

object BotActor {
  def props(player: Player, avatar: Avatar, zone: Zone, botClass: BotClass, personality: BotPersonality): Props =
    Props(classOf[BotActor], player, avatar, zone, botClass, personality)

  // Messages
  case object Tick
  case class TakeDamage(amount: Int, source: PlanetSideGUID, sourcePosition: Vector3)
  case class Die(killer: PlanetSideGUID)
  case class Respawn(spawnPoint: Vector3)
  case class ReceiveOrder(order: BotOrder)
  case class HelpRequest(requester: PlanetSideGUID, helpType: String, location: Vector3)
}

// Supporting types (would be in separate files)
sealed trait BotAction
object BotAction {
  case class Attack(target: Player) extends BotAction
  case object Retreat extends BotAction
  case object Resupply extends BotAction
  case class FollowObjective(objective: BotObjective) extends BotAction
  case object Patrol extends BotAction
}

sealed trait BotRole
object BotRole {
  case object Driver extends BotRole
  case object Support extends BotRole
  case object Hacker extends BotRole
  case object AV extends BotRole
  case object MAX extends BotRole
  case object Veteran extends BotRole
  case object Ace extends BotRole
}

sealed trait ExperienceLevel
object ExperienceLevel {
  case object Newbie extends ExperienceLevel
  case object Regular extends ExperienceLevel
  case object Veteran extends ExperienceLevel
  case object Ace extends ExperienceLevel
}

sealed trait MovementStyle
object MovementStyle {
  case object Newbie extends MovementStyle    // straight run with held strafe
  case object Veteran extends MovementStyle   // ADAD + crouch spam
  case object NCClose extends MovementStyle   // aggressive closing for shotguns
}

case class BotClass(
  name: String,
  role: BotRole,
  // certifications, loadout, etc.
)

case class BotPersonality(
  experienceLevel: ExperienceLevel,
  movementStyle: MovementStyle,
  fovDegrees: Float = 60f,
  detectionRange: Float = 100f,
  retreatThreshold: Float = 0.25f,  // retreat at 25% HP
  accuracyModifier: Float = 1.0f
)

case class BotObjective(
  targetPosition: Vector3,
  objectiveType: String  // "attack", "defend", "capture", etc.
)

case class BotOrder(
  orderType: String,
  targetPosition: Option[Vector3],
  priority: Int
)
