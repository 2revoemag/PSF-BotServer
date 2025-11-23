// Copyright (c) 2024 PSForever
package net.psforever.actors.bot

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.actor.{Actor, ActorContext, Cancellable, Props, ActorRef => ClassicActorRef}
import net.psforever.actors.session.AvatarActor
import net.psforever.objects.avatar.Avatar
import net.psforever.objects.guid.{GUIDTask, TaskWorkflow}
import net.psforever.objects.zones.Zone
import net.psforever.objects.{Default, Player, Tool}
import net.psforever.objects.sourcing.{PlayerSource, SourceEntry}
import net.psforever.objects.vital.Vitality
import net.psforever.objects.vital.base.DamageResolution
import net.psforever.objects.vital.interaction.DamageInteraction
import net.psforever.objects.vital.projectile.ProjectileReason
import net.psforever.objects.ballistics.Projectile
import net.psforever.packet.game.objectcreate.BasicCharacterData
import net.psforever.services.avatar.{AvatarAction, AvatarServiceMessage}
import net.psforever.types.{CharacterSex, CharacterVoice, PlanetSideEmpire, PlanetSideGUID, Vector3}
import net.psforever.util.DefinitionUtil
import net.psforever.zones.Zones

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Random, Success, Failure}

/**
  * Manages bot spawning and lifecycle for a zone.
  * Uses DB bot IDs (2-301) to ensure unique bots that can be damaged/killed properly.
  */
class BotManager(zone: Zone) extends Actor {
  import BotManager._

  private val log = org.log4s.getLogger
  private val bots: mutable.Map[Int, BotState] = mutable.Map()
  private val random = new Random()

  // AI toggle - starts OFF so bots spawn passive
  private var aiEnabled: Boolean = false

  // Pool of available bot IDs (DB IDs 2-301 = xxBOTxxTestBot1 through xxBOTxxTestBot300)
  private val availableBotIds: mutable.Queue[Int] = mutable.Queue.from(2 to 301)
  private val usedBotIds: mutable.Set[Int] = mutable.Set()

  // Track dead bots waiting to "tap out" (botId -> tap out info)
  private val waitingToTapOut: mutable.Map[Int, TapOutInfo] = mutable.Map()

  // Track pending respawns (botId -> respawn info)
  private val pendingRespawns: mutable.Map[Int, RespawnInfo] = mutable.Map()

  // Movement tick scheduler - 10 ticks per second
  private var tickScheduler: Option[Cancellable] = None
  private var tickCount: Int = 0

  /**
    * Generate weighted random respawn delay.
    * Most bots respawn quickly (1-5 sec), some take longer, rare cases up to 90 sec.
    * Distribution: ~80% 1-5sec, ~15% 6-30sec, ~4% 31-60sec, ~1% 61-90sec
    */
  private def randomRespawnDelayTicks(): Int = {
    val roll = random.nextInt(100)
    val seconds = if (roll < 80) {
      1 + random.nextInt(5)       // 1-5 seconds (80% chance)
    } else if (roll < 95) {
      6 + random.nextInt(25)      // 6-30 seconds (15% chance)
    } else if (roll < 99) {
      31 + random.nextInt(30)     // 31-60 seconds (4% chance)
    } else {
      61 + random.nextInt(30)     // 61-90 seconds (1% chance)
    }
    seconds * 10 // Convert to ticks (10 ticks per second)
  }

  override def preStart(): Unit = {
    tickScheduler = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        100.milliseconds,
        100.milliseconds,
        self,
        Tick
      )
    )
  }

  override def postStop(): Unit = {
    tickScheduler.foreach(_.cancel())
  }

  def receive: Receive = {
    case SpawnBot(faction, position) =>
      spawnBot(faction, position)

    case CompleteSpawn(botId, name, avatar, player, botAvatarActor) =>
      completeSpawn(botId, name, avatar, player, botAvatarActor)

    case DespawnBot(botId) =>
      despawnBot(botId)

    case DespawnAllBots =>
      bots.keys.toSeq.foreach(despawnBot)

    case SetAIEnabled(enabled) =>
      aiEnabled = enabled
      val state = if (enabled) "ON" else "OFF"
      log.info(s"Bot AI is now $state (${bots.size} bots affected)")
      // If turning off, stop all bots from firing
      if (!enabled) {
        bots.values.foreach { botState =>
          if (botState.combat.isFiring) {
            stopFiring(botState)
          }
        }
      }

    case Tick =>
      tickCount += 1
      checkForDeadBots()
      processTapOuts()
      processRespawns()
      updateBots()

    case _ => ()
  }

  private def spawnBot(faction: PlanetSideEmpire.Value, position: Vector3): Unit = {
    if (availableBotIds.isEmpty) {
      log.warn("No available bot IDs - all 300 bots are in use!")
      return
    }

    val botId = availableBotIds.dequeue()
    usedBotIds.add(botId)
    val botNumber = botId - 1 // ID 2 = TestBot1, ID 3 = TestBot2, etc.
    val name = s"xxBOTxxTestBot$botNumber"

    log.info(s"Spawning bot '$name' (dbId=$botId) at $position in zone ${zone.id}")

    val avatar = Avatar(
      botId,
      BasicCharacterData(
        name,
        faction,
        CharacterSex.Male,
        0,
        CharacterVoice.Voice5
      )
    )

    val player = new Player(avatar)
    player.Position = position
    player.Orientation = Vector3(0, 0, 0)
    DefinitionUtil.applyDefaultLoadout(player)
    player.DrawnSlot = 2 // Draw suppressor (slot 2) so bot can shoot
    player.Spawn()

    val typedSystem = context.system.toTyped
    val botAvatarActor: ActorRef[AvatarActor.Command] =
      typedSystem.systemActorOf(BotAvatarActor(), s"bot-avatar-$botId-${System.currentTimeMillis}")

    val selfRef = self
    TaskWorkflow.execute(GUIDTask.registerAvatar(zone.GUID, player)).onComplete {
      case Success(_) =>
        log.info(s"GUID registration complete for bot '$name', GUID=${player.GUID}")
        selfRef ! CompleteSpawn(botId, name, avatar, player, botAvatarActor)
      case Failure(ex) =>
        log.error(s"GUID registration failed for bot '$name': ${ex.getMessage}")
        // Return the ID to the pool on failure
        availableBotIds.enqueue(botId)
        usedBotIds.remove(botId)
    }
  }

  private def completeSpawn(
    botId: Int,
    name: String,
    avatar: Avatar,
    player: Player,
    botAvatarActor: ActorRef[AvatarActor.Command]
  ): Unit = {
    log.info(s"Completing spawn for bot '$name' with GUID ${player.GUID}")

    zone.Population ! Zone.Population.Join(avatar)
    zone.Population ! Zone.Population.Spawn(avatar, player, botAvatarActor)

    val definition = player.Definition
    zone.AvatarEvents ! AvatarServiceMessage(
      zone.id,
      AvatarAction.LoadPlayer(
        player.GUID,
        definition.ObjectId,
        player.GUID,
        definition.Packet.ConstructorData(player).get,
        None
      )
    )

    // Don't broadcast weapon draw yet - wait for readyTick to let client fully load
    // Initialize movement state
    val moveAngle = random.nextFloat() * 360f
    val moveState = MovementState(
      targetYaw = moveAngle,
      moveSpeed = 3f, // Slightly slower walk speed
      moveUntilTick = tickCount + 30 + random.nextInt(50)
    )

    // Set readyTick 2 seconds from now (20 ticks) to let client fully load before combat
    val readyAt = tickCount + 20
    bots(botId) = BotState(botId, name, avatar, player, botAvatarActor, moveState, CombatState(), player.Position, readyAt, weaponDrawn = false)
    log.info(s"Bot '$name' spawned successfully with GUID ${player.GUID} (${bots.size} bots active, ${availableBotIds.size} available)")
  }

  private def checkForDeadBots(): Unit = {
    // Find newly dead bots (dead but not yet in waitingToTapOut)
    val newlyDeadBots = bots.values.filter { b =>
      !b.player.isAlive && !waitingToTapOut.contains(b.id)
    }.toSeq

    newlyDeadBots.foreach { botState =>
      // Calculate random "time on ground" before tap out
      val delayTicks = randomRespawnDelayTicks()
      val delaySec = delayTicks / 10f
      log.info(s"Bot '${botState.name}' died at ${botState.player.Position}, will tap out in ${delaySec}s")

      // Schedule tap out - body stays on ground until then
      waitingToTapOut(botState.id) = TapOutInfo(
        botState = botState,
        tapOutAtTick = tickCount + delayTicks
      )
    }
  }

  private def processTapOuts(): Unit = {
    val readyToTapOut = waitingToTapOut.values.filter(_.tapOutAtTick <= tickCount).toSeq
    readyToTapOut.foreach { info =>
      waitingToTapOut.remove(info.botState.id)
      handleBotTapOut(info.botState)
    }
  }

  private def handleBotTapOut(botState: BotState): Unit = {
    val player = botState.player
    val avatar = botState.avatar
    val botId = botState.id

    log.info(s"Bot '${botState.name}' tapping out, creating backpack and scheduling respawn")

    // Convert the dead player to a backpack/corpse (sets isBackpack = true)
    player.Release

    // Register corpse for tracking (must happen before Release broadcast, and while still in zone population)
    zone.Population ! Zone.Corpse.Add(player)

    // Broadcast the corpse/backpack to all clients
    zone.AvatarEvents ! AvatarServiceMessage(
      zone.id,
      AvatarAction.Release(player, zone)
    )

    // Now leave the zone population (avatar is no longer "playing")
    zone.Population ! Zone.Population.Release(avatar)

    // Remove from active bots
    bots.remove(botId)

    // Schedule immediate respawn (tap out already waited)
    pendingRespawns(botId) = RespawnInfo(
      botId = botId,
      name = botState.name,
      faction = avatar.faction,
      spawnPosition = botState.spawnPosition,
      respawnAtTick = tickCount + 10 // Small delay for respawn after tap out (1 second)
    )
  }

  private def processRespawns(): Unit = {
    val readyToRespawn = pendingRespawns.values.filter(_.respawnAtTick <= tickCount).toSeq
    readyToRespawn.foreach { info =>
      pendingRespawns.remove(info.botId)
      log.info(s"Respawning bot '${info.name}' at ${info.spawnPosition}")
      respawnBot(info)
    }
  }

  private def respawnBot(info: RespawnInfo): Unit = {
    val botId = info.botId
    val name = info.name

    log.info(s"Respawning bot '$name' (dbId=$botId) at ${info.spawnPosition}")

    val avatar = Avatar(
      botId,
      BasicCharacterData(
        name,
        info.faction,
        CharacterSex.Male,
        0,
        CharacterVoice.Voice5
      )
    )

    val player = new Player(avatar)
    player.Position = info.spawnPosition
    player.Orientation = Vector3(0, 0, 0)
    DefinitionUtil.applyDefaultLoadout(player)
    player.DrawnSlot = 2 // Draw suppressor (slot 2) so bot can shoot
    player.Spawn()

    val typedSystem = context.system.toTyped
    val botAvatarActor: ActorRef[AvatarActor.Command] =
      typedSystem.systemActorOf(BotAvatarActor(), s"bot-avatar-$botId-${System.currentTimeMillis}")

    val selfRef = self
    TaskWorkflow.execute(GUIDTask.registerAvatar(zone.GUID, player)).onComplete {
      case Success(_) =>
        log.info(s"GUID registration complete for respawning bot '$name', GUID=${player.GUID}")
        selfRef ! CompleteSpawn(botId, name, avatar, player, botAvatarActor)
      case Failure(ex) =>
        log.error(s"GUID registration failed for respawning bot '$name': ${ex.getMessage}")
        // Return the ID to the pool on failure
        usedBotIds.remove(botId)
        availableBotIds.enqueue(botId)
    }
  }

  private def updateBots(): Unit = {
    val timestamp = (System.currentTimeMillis() % 65536).toInt

    bots.values.foreach { botState =>
      val player = botState.player
      if (player.isAlive) {
        var currentBotState = botState

        // Check if bot is ready (spawn delay passed) and weapon not yet drawn
        val isReady = tickCount >= botState.readyTick
        if (isReady && !botState.weaponDrawn) {
          // Now broadcast the weapon draw - client has had time to load
          zone.AvatarEvents ! AvatarServiceMessage(
            zone.id,
            AvatarAction.ObjectHeld(player.GUID, player.DrawnSlot, player.LastDrawnSlot)
          )
          log.info(s"${botState.name} has drawn a suppressor from its holster")
          currentBotState = currentBotState.copy(weaponDrawn = true)
        }

        // Only update combat and movement if AI is enabled AND bot is ready
        if (aiEnabled && isReady) {
          // Update combat (finds targets, fires)
          val newCombatState = updateCombat(currentBotState)
          currentBotState = currentBotState.copy(combat = newCombatState)

          // Update movement (will face target if in combat)
          val newMoveState = updateMovement(currentBotState, player)
          currentBotState = currentBotState.copy(movement = newMoveState)
        }
        // If AI disabled or not ready, bot just stands still (no movement/combat updates)

        bots(botState.id) = currentBotState

        zone.AvatarEvents ! AvatarServiceMessage(
          zone.id,
          AvatarAction.PlayerState(
            player.GUID,
            player.Position,
            player.Velocity,
            player.Orientation.z,
            0f,
            player.Orientation.z,
            timestamp,
            is_crouching = false,
            is_jumping = false,
            jump_thrust = false,
            is_cloaked = false,
            spectator = false,
            weaponInHand = currentBotState.weaponDrawn
          )
        )
      }
    }
  }

  private def updateMovement(botState: BotState, player: Player): MovementState = {
    var moveState = botState.movement

    // If we have a target, face them instead of random wandering
    if (botState.combat.target.isDefined) {
      botState.combat.target.flatMap(guid => zone.LivePlayers.find(_.GUID == guid)) match {
        case Some(targetPlayer) =>
          val dx = targetPlayer.Position.x - player.Position.x
          val dy = targetPlayer.Position.y - player.Position.y
          val targetYaw = math.toDegrees(math.atan2(dx, dy)).toFloat
          moveState = moveState.copy(targetYaw = targetYaw)
        case None => ()
      }
    } else if (tickCount >= moveState.moveUntilTick) {
      moveState = moveState.copy(
        targetYaw = random.nextFloat() * 360f,
        moveUntilTick = tickCount + 30 + random.nextInt(50)
      )
    }

    val yawRad = math.toRadians(moveState.targetYaw).toFloat
    val speed = moveState.moveSpeed / 10f
    val vx = math.sin(yawRad).toFloat * speed
    val vy = math.cos(yawRad).toFloat * speed

    val newPos = Vector3(
      player.Position.x + vx,
      player.Position.y + vy,
      player.Position.z
    )

    player.Position = newPos
    player.Orientation = Vector3(0, 0, moveState.targetYaw)
    player.Velocity = Some(Vector3(vx * 10, vy * 10, 0))

    moveState
  }

  // ============ COMBAT SYSTEM ============

  /** Maximum engagement range in game units */
  private val MaxEngagementRange = 100f
  /** Recognition time in ticks based on distance */
  private def recognitionTimeTicks(distance: Float): Int = {
    if (distance < 15f) 1                        // Near instant for close
    else if (distance < 50f) 3 + random.nextInt(5)  // 0.3-0.8 sec for medium
    else 10 + random.nextInt(10)                 // 1-2 sec for far
  }

  /** Find the closest enemy player in range */
  private def findTarget(botState: BotState): Option[Player] = {
    val player = botState.player
    val botFaction = botState.avatar.faction

    zone.LivePlayers
      .filter { p =>
        p.isAlive &&
        p.Faction != botFaction &&
        !p.Name.startsWith("xxBOTxx") && // Don't target other bots for now
        Vector3.Distance(player.Position, p.Position) <= MaxEngagementRange
      }
      .sortBy(p => Vector3.Distance(player.Position, p.Position))
      .headOption
  }

  /** Calculate hit chance based on distance and the two-force accuracy system */
  private def calculateHitChance(distance: Float, shotsFired: Int, ticksAiming: Int): Float = {
    // Base accuracy decreases with distance
    val baseAccuracy = if (distance < 10f) 0.95f
      else if (distance < 20f) 0.80f
      else if (distance < 40f) 0.60f
      else if (distance < 60f) 0.40f
      else 0.25f

    // Adjustment bonus: accuracy improves over time as bot "dials in"
    // Max bonus after ~1 second (10 ticks) of aiming
    val adjustmentBonus = math.min(ticksAiming * 0.03f, 0.30f)

    // Recoil penalty: spread worsens with each shot
    // Gets bad after ~10 shots
    val recoilPenalty = math.min(shotsFired * 0.04f, 0.50f)

    // Final hit chance: base + adjustment - recoil, clamped to [0.05, 0.95]
    val hitChance = baseAccuracy + adjustmentBonus - recoilPenalty
    math.max(0.05f, math.min(0.95f, hitChance))
  }

  /** Get the bot's equipped weapon */
  private def getWeapon(player: Player): Option[Tool] = {
    val slot = player.DrawnSlot
    if (slot >= 0 && slot < player.Holsters().length) {
      player.Holsters()(slot).Equipment match {
        case Some(tool: Tool) => Some(tool)
        case _ => None
      }
    } else None
  }

  /** Update combat state for a bot */
  private def updateCombat(botState: BotState): CombatState = {
    val player = botState.player
    var combat = botState.combat

    // Find or validate target
    val currentTarget = combat.target.flatMap(guid => zone.LivePlayers.find(_.GUID == guid))
    val targetStillValid = currentTarget.exists { t =>
      t.isAlive && Vector3.Distance(player.Position, t.Position) <= MaxEngagementRange
    }

    if (!targetStillValid) {
      // Lost target or need new one - stop firing if we were
      if (combat.isFiring) {
        stopFiring(botState)
      }
      // Look for new target
      findTarget(botState) match {
        case Some(newTarget) =>
          val distance = Vector3.Distance(player.Position, newTarget.Position)
          log.info(s"${botState.name} acquired target ${newTarget.Name} at distance ${distance.toInt}m")
          combat = combat.copy(
            target = Some(newTarget.GUID),
            targetAcquiredTick = tickCount,
            shotsFired = 0,
            isFiring = false
          )
        case None =>
          combat = CombatState() // No target, reset combat state
      }
    }

    // If we have a target, handle combat
    combat.target.flatMap(guid => zone.LivePlayers.find(_.GUID == guid)) match {
      case Some(targetPlayer) =>
        val distance = Vector3.Distance(player.Position, targetPlayer.Position)
        val ticksSinceAcquired = tickCount - combat.targetAcquiredTick
        val recognitionTime = recognitionTimeTicks(distance)

        // Wait for recognition time before firing
        if (ticksSinceAcquired >= recognitionTime) {
          // Start firing if not already
          if (!combat.isFiring) {
            startFiring(botState, targetPlayer.Name)
            combat = combat.copy(isFiring = true, burstStartTick = tickCount, shotsFired = 0)
          }

          // Fire every 2 ticks (~5 shots per second for automatic weapons)
          if (tickCount - combat.lastShotTick >= 2) {
            val ticksAiming = tickCount - combat.burstStartTick
            val hitChance = calculateHitChance(distance, combat.shotsFired, ticksAiming)

            if (random.nextFloat() < hitChance) {
              fireAtTarget(botState, targetPlayer)
            }

            combat = combat.copy(
              shotsFired = combat.shotsFired + 1,
              lastShotTick = tickCount
            )

            // Burst control: after 15-25 shots, pause briefly to "reset recoil"
            if (combat.shotsFired >= 15 + random.nextInt(10)) {
              stopFiring(botState)
              combat = combat.copy(
                isFiring = false,
                shotsFired = 0
              )
            }
          }
        }

      case None =>
        // Target no longer valid
        if (combat.isFiring) {
          stopFiring(botState)
          combat = combat.copy(isFiring = false)
        }
    }

    combat
  }

  /** Broadcast that bot started firing */
  private def startFiring(botState: BotState, targetName: String): Unit = {
    getWeapon(botState.player).foreach { weapon =>
      log.info(s"${botState.name} is attacking $targetName")
      zone.AvatarEvents ! AvatarServiceMessage(
        zone.id,
        AvatarAction.ChangeFireState_Start(botState.player.GUID, weapon.GUID)
      )
    }
  }

  /** Broadcast that bot stopped firing */
  private def stopFiring(botState: BotState): Unit = {
    getWeapon(botState.player).foreach { weapon =>
      zone.AvatarEvents ! AvatarServiceMessage(
        zone.id,
        AvatarAction.ChangeFireState_Stop(botState.player.GUID, weapon.GUID)
      )
    }
  }

  /** Deal damage to target */
  private def fireAtTarget(botState: BotState, target: Player): Unit = {
    getWeapon(botState.player).foreach { weapon =>
      val projectileType = weapon.Projectile
      val fireMode = weapon.FireMode

      // Create a projectile for damage calculation
      val projectile = Projectile(
        profile = projectileType,
        tool_def = weapon.Definition,
        fire_mode = fireMode,
        mounted_in = None,
        owner = PlayerSource(botState.player),
        attribute_to = weapon.Definition.ObjectId,
        shot_origin = botState.player.Position,
        shot_angle = botState.player.Orientation,
        shot_velocity = None
      )

      // Create damage interaction and calculate the damage function
      val damageInteraction = DamageInteraction(
        SourceEntry(target),
        target.Position,
        ProjectileReason(
          DamageResolution.Hit,
          projectile,
          target.DamageModel
        ),
        DamageResolution.Hit
      )

      // Validate target can receive damage (has a proper Actor)
      if (target.CanDamage && target.Actor != Default.Actor) {
        // Send damage to target's actor (calculate() returns the function needed by Vitality.Damage)
        target.Actor ! Vitality.Damage(damageInteraction.calculate())

        // Send hit hint to target (so they know they're being shot)
        zone.AvatarEvents ! AvatarServiceMessage(
          zone.id,
          AvatarAction.HitHint(botState.player.GUID, target.GUID)
        )
      } else {
        log.warn(s"${botState.name} cannot damage ${target.Name} - target has no valid Actor")
      }
    }
  }

  private def despawnBot(botId: Int): Unit = {
    bots.get(botId) match {
      case Some(botState) =>
        log.info(s"Despawning bot '${botState.name}' (dbId=$botId)")
        val player = botState.player
        val avatar = botState.avatar

        zone.Population ! Zone.Population.Leave(avatar)
        zone.AvatarEvents ! AvatarServiceMessage(
          zone.id,
          AvatarAction.ObjectDelete(player.GUID, player.GUID)
        )
        TaskWorkflow.execute(GUIDTask.unregisterAvatar(zone.GUID, player))

        bots.remove(botId)
        // Return the ID to the available pool
        usedBotIds.remove(botId)
        availableBotIds.enqueue(botId)
        log.info(s"Bot '${botState.name}' despawned (${bots.size} bots active, ${availableBotIds.size} available)")

      case None =>
        // Also check pending respawns
        pendingRespawns.get(botId) match {
          case Some(info) =>
            pendingRespawns.remove(botId)
            usedBotIds.remove(botId)
            availableBotIds.enqueue(botId)
            log.info(s"Cancelled pending respawn for bot '${info.name}'")
          case None =>
            log.warn(s"Bot with id $botId not found")
        }
    }
  }
}

object BotManager {
  def props(zone: Zone): Props = Props(classOf[BotManager], zone)

  // Commands - removed name from SpawnBot since we use DB names
  sealed trait Command
  final case class SpawnBot(faction: PlanetSideEmpire.Value, position: Vector3) extends Command
  final case class DespawnBot(botId: Int) extends Command
  case object DespawnAllBots extends Command
  final case class SetAIEnabled(enabled: Boolean) extends Command
  private case object Tick extends Command

  private[bot] final case class CompleteSpawn(
    botId: Int,
    name: String,
    avatar: Avatar,
    player: Player,
    botAvatarActor: ActorRef[AvatarActor.Command]
  ) extends Command

  case class MovementState(
    targetYaw: Float = 0f,
    moveSpeed: Float = 3f,
    moveUntilTick: Int = 0
  )

  /**
    * Combat state tracking for accuracy system.
    * Two opposing forces: adjustment (improves accuracy) vs recoil (worsens spread)
    */
  case class CombatState(
    target: Option[PlanetSideGUID] = None,  // Current target GUID
    isFiring: Boolean = false,               // Currently shooting
    shotsFired: Int = 0,                     // Shots in current burst (for recoil)
    targetAcquiredTick: Int = 0,             // When we first spotted target (for recognition time)
    lastShotTick: Int = 0,                   // When we last fired
    burstStartTick: Int = 0                  // When current burst started (for adjustment)
  )

  case class BotState(
    id: Int,
    name: String,
    avatar: Avatar,
    player: Player,
    avatarActor: ActorRef[AvatarActor.Command],
    movement: MovementState = MovementState(),
    combat: CombatState = CombatState(),
    spawnPosition: Vector3 = Vector3.Zero,
    readyTick: Int = 0,         // Tick when bot is ready for combat (after spawn delay)
    weaponDrawn: Boolean = false // Whether weapon draw has been broadcast to clients
  )

  case class TapOutInfo(
    botState: BotState,
    tapOutAtTick: Int
  )

  case class RespawnInfo(
    botId: Int,
    name: String,
    faction: PlanetSideEmpire.Value,
    spawnPosition: Vector3,
    respawnAtTick: Int
  )

  def spawnTestBot(context: ActorContext, position: Vector3): Option[ClassicActorRef] = {
    Zones.zones.find(_.id == "home2") match {
      case Some(zone) =>
        val manager = context.actorOf(props(zone), s"bot-manager-${System.currentTimeMillis}")
        manager ! SpawnBot(PlanetSideEmpire.TR, position)
        Some(manager)
      case None =>
        None
    }
  }
}
