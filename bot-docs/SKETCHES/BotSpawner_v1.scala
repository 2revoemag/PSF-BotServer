// SKETCH - NOT PRODUCTION CODE
// Bot spawning flow - what would it take to spawn a bot?

package net.psforever.actors.bot

import akka.actor.{Actor, ActorContext, ActorRef, Props}
import net.psforever.objects.{GlobalDefinitions, Player}
import net.psforever.objects.avatar.Avatar
import net.psforever.objects.definition.ExoSuitDefinition
import net.psforever.objects.guid.GUIDTask
import net.psforever.objects.loadouts.InfantryLoadout
import net.psforever.objects.zones.Zone
import net.psforever.packet.game.objectcreate.BasicCharacterData
import net.psforever.types._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}
import java.util.concurrent.atomic.AtomicInteger

/**
 * BotSpawner - Responsible for spawning bots into a zone
 *
 * Key insight: Looking at ZonePopulationActor.scala:
 * - Zone.Population.Join(avatar) -> registers avatar in playerMap
 * - Zone.Population.Spawn(avatar, player, avatarActor) -> creates PlayerControl
 * - GUIDTask.registerPlayer(zone.GUID, player) -> assigns GUIDs
 *
 * For bots, we need:
 * 1. Create an Avatar (normally from DB, but we can construct directly)
 * 2. Create a Player with that Avatar
 * 3. Equip the player with loadout
 * 4. Register GUIDs for player and equipment
 * 5. Join zone population
 * 6. Spawn player
 * 7. Create BotActor to control AI
 */
object BotSpawner {

  // Counter for generating unique bot IDs
  // Using negative numbers to avoid collision with real player charIds
  private val botIdCounter = new AtomicInteger(-1)

  /**
   * Spawn a single bot into a zone
   *
   * @param zone The zone to spawn into
   * @param faction Which empire (TR, NC, VS)
   * @param botClass The class/role of the bot
   * @param spawnPosition Where to spawn
   * @param context ActorContext for creating BotActor
   * @return The spawned player entity
   */
  def spawnBot(
    zone: Zone,
    faction: PlanetSideEmpire.Value,
    botClass: BotClass,
    spawnPosition: Vector3,
    context: ActorContext
  ): Player = {

    // 1. Generate unique bot ID (negative to avoid DB collision)
    val botId = botIdCounter.getAndDecrement()

    // 2. Create Avatar
    val avatar = createBotAvatar(botId, faction, botClass)

    // 3. Create Player entity
    val player = new Player(avatar)

    // 4. Configure player
    configurePlayer(player, botClass, spawnPosition)

    // 5. Register GUIDs for player and all equipment
    // This is async - we need to wait for it to complete
    val registerTask = GUIDTask.registerPlayer(zone.GUID, player)
    TaskWorkflow.execute(registerTask)

    // 6. Join zone population (avatar-level)
    zone.Population ! Zone.Population.Join(avatar)

    // 7. Create a placeholder BotAvatarActor
    // Real AvatarActor handles DB persistence - we don't need that
    val botAvatarActor = context.actorOf(
      BotAvatarActor.props(avatar),
      name = s"bot-avatar-$botId"
    )

    // 8. Spawn player in zone (creates PlayerControl actor)
    zone.Population ! Zone.Population.Spawn(avatar, player, botAvatarActor)

    // 9. Add to block map for spatial queries
    zone.actor ! ZoneActor.AddToBlockMap(player, spawnPosition)

    // 10. Create BotActor for AI control
    val personality = createPersonality(botClass)
    val botActor = context.actorOf(
      BotActor.props(player, avatar, zone, botClass, personality),
      name = s"bot-ai-$botId"
    )

    // 11. Broadcast player existence to all connected clients
    broadcastPlayerSpawn(zone, player)

    player
  }

  /**
   * Create an Avatar for a bot
   */
  private def createBotAvatar(
    botId: Int,
    faction: PlanetSideEmpire.Value,
    botClass: BotClass
  ): Avatar = {
    val name = generateBotName(faction, botClass, botId)
    val sex = if (scala.util.Random.nextBoolean()) CharacterSex.Male else CharacterSex.Female
    val head = scala.util.Random.nextInt(5) + 1
    val voice = CharacterVoice.values.toSeq(scala.util.Random.nextInt(CharacterVoice.values.size))

    // Create avatar with predefined certifications for the class
    Avatar(
      id = botId,
      basic = BasicCharacterData(name, faction, sex, head, voice),
      bep = botClass.battleRank * 1000L,  // Fake BEP for appearance
      cep = if (botClass.role == BotRole.Ace) 10000L else 0L,  // CR for Ace only
      certifications = botClass.certifications
    )
  }

  /**
   * Generate a bot name like "[BOT]Grunt_TR_042"
   */
  private def generateBotName(
    faction: PlanetSideEmpire.Value,
    botClass: BotClass,
    botId: Int
  ): String = {
    val factionPrefix = faction match {
      case PlanetSideEmpire.TR => "TR"
      case PlanetSideEmpire.NC => "NC"
      case PlanetSideEmpire.VS => "VS"
      case _ => "XX"
    }
    val classPrefix = botClass.role match {
      case BotRole.Driver => "Driver"
      case BotRole.Support => "Medic"
      case BotRole.Hacker => "Hacker"
      case BotRole.AV => "Heavy"
      case BotRole.MAX => "MAX"
      case BotRole.Veteran => "Vet"
      case BotRole.Ace => "Ace"
    }
    f"[BOT]${classPrefix}_${factionPrefix}_${math.abs(botId)}%03d"
  }

  /**
   * Configure player entity with position, equipment, etc.
   */
  private def configurePlayer(
    player: Player,
    botClass: BotClass,
    spawnPosition: Vector3
  ): Unit = {
    // Set position and orientation
    player.Position = spawnPosition
    player.Orientation = Vector3(0, 0, scala.util.Random.nextFloat() * 360f)

    // Set exosuit based on class
    val exosuit = botClass.role match {
      case BotRole.MAX => ExoSuitType.MAX
      case BotRole.Hacker => ExoSuitType.Agile  // Infiltrators use Agile
      case _ => ExoSuitType.Reinforced
    }
    player.ExoSuit = exosuit

    // Equip loadout
    equipLoadout(player, botClass)

    // Spawn the player (set health, armor)
    player.Spawn()
  }

  /**
   * Equip player with class-appropriate loadout
   */
  private def equipLoadout(player: Player, botClass: BotClass): Unit = {
    // TODO: Load from predefined loadouts
    // For now, just give basic equipment based on faction + class

    // Example: Standard infantry loadout
    // player.Slot(0).Equipment = ... // Rifle
    // player.Slot(1).Equipment = ... // Sidearm
    // etc.
  }

  /**
   * Create personality/behavior weights for a bot class
   */
  private def createPersonality(botClass: BotClass): BotPersonality = {
    botClass.role match {
      case BotRole.Veteran | BotRole.Ace =>
        BotPersonality(
          experienceLevel = if (botClass.role == BotRole.Ace) ExperienceLevel.Ace else ExperienceLevel.Veteran,
          movementStyle = MovementStyle.Veteran,
          fovDegrees = 75f,  // Better awareness
          accuracyModifier = 1.2f,
          retreatThreshold = 0.3f
        )
      case BotRole.MAX =>
        BotPersonality(
          experienceLevel = ExperienceLevel.Regular,
          movementStyle = MovementStyle.Newbie,  // MAXes are slower
          fovDegrees = 60f,
          accuracyModifier = 1.0f,
          retreatThreshold = 0.2f  // MAXes don't retreat easily
        )
      case _ =>
        BotPersonality(
          experienceLevel = ExperienceLevel.Newbie,
          movementStyle = MovementStyle.Newbie,
          fovDegrees = 60f,
          accuracyModifier = 0.8f,  // Worse accuracy
          retreatThreshold = 0.25f
        )
    }
  }

  /**
   * Broadcast player spawn to all connected clients
   */
  private def broadcastPlayerSpawn(zone: Zone, player: Player): Unit = {
    // Use ObjectCreateMessage to create the player on all clients
    // This is what makes the bot visible to everyone

    import net.psforever.packet.game.ObjectCreateMessage
    import net.psforever.packet.game.objectcreate._

    // Build the player data for ObjectCreateMessage
    val playerData = PlayerData.create(player)

    // Broadcast via AvatarService
    zone.AvatarEvents ! AvatarServiceMessage(
      zone.id,
      AvatarAction.LoadPlayer(
        player.GUID,
        player.Definition.ObjectId,
        player.GUID,  // target_guid - same as player for self
        playerData,
        None  // no parent (not in vehicle)
      )
    )
  }

  /**
   * Despawn a bot from a zone (graceful logout)
   */
  def despawnBot(zone: Zone, player: Player): Unit = {
    // 1. Stop BotActor

    // 2. Notify zone population
    zone.Population ! Zone.Population.Leave(player.avatar)

    // 3. Broadcast player deletion
    zone.AvatarEvents ! AvatarServiceMessage(
      zone.id,
      AvatarAction.ObjectDelete(player.GUID, player.GUID)
    )

    // 4. Unregister GUIDs
    TaskWorkflow.execute(GUIDTask.unregisterPlayer(zone.GUID, player))

    // 5. Remove from block map
    zone.actor ! ZoneActor.RemoveFromBlockMap(player)
  }
}

/**
 * Simplified AvatarActor for bots
 *
 * The real AvatarActor handles:
 * - DB persistence (saving stats, certs, etc.)
 * - Character selection
 * - Login/logout flow
 *
 * For bots, we don't need most of that. Just a stub actor
 * that can handle the messages PlayerControl expects.
 */
class BotAvatarActor(avatar: Avatar) extends Actor {
  // Minimal implementation - just accept messages and do nothing
  def receive: Receive = {
    case _ => // Ignore most messages - bots don't persist
  }
}

object BotAvatarActor {
  def props(avatar: Avatar): Props = Props(classOf[BotAvatarActor], avatar)
}

/**
 * BotManager - Manages bot population across a zone
 */
class BotManager(zone: Zone) extends Actor {
  import scala.concurrent.duration._

  private val targetBotsPerFaction = 100
  private var bots: Map[PlanetSideGUID, Player] = Map.empty

  // Population check timer
  context.system.scheduler.scheduleWithFixedDelay(
    initialDelay = 5.seconds,
    delay = 10.seconds,
    receiver = self,
    message = BotManager.CheckPopulation
  )(context.dispatcher)

  def receive: Receive = {
    case BotManager.CheckPopulation =>
      balancePopulation()

    case BotManager.SpawnBot(faction, botClass, position) =>
      val player = BotSpawner.spawnBot(zone, faction, botClass, position, context)
      bots += (player.GUID -> player)

    case BotManager.DespawnBot(guid) =>
      bots.get(guid).foreach { player =>
        BotSpawner.despawnBot(zone, player)
        bots -= guid
      }

    case BotManager.DespawnAll =>
      bots.values.foreach(player => BotSpawner.despawnBot(zone, player))
      bots = Map.empty
  }

  private def balancePopulation(): Unit = {
    PlanetSideEmpire.values.foreach { faction =>
      if (faction != PlanetSideEmpire.NEUTRAL) {
        val realPlayers = zone.LivePlayers.count(p =>
          !isBotPlayer(p) && p.Faction == faction
        )
        val currentBots = bots.values.count(_.Faction == faction)
        val targetBots = math.max(0, targetBotsPerFaction - realPlayers)

        if (currentBots < targetBots) {
          // Spawn more bots
          val toSpawn = targetBots - currentBots
          (0 until toSpawn).foreach { _ =>
            val position = findSpawnPosition(faction)
            val botClass = randomBotClass()
            self ! BotManager.SpawnBot(faction, botClass, position)
          }
        } else if (currentBots > targetBots) {
          // Despawn excess bots (non-Ace first)
          val toRemove = currentBots - targetBots
          val botsToRemove = bots.values
            .filter(_.Faction == faction)
            .toSeq
            .sortBy(p => if (isAce(p)) 1 else 0)  // Ace last
            .take(toRemove)

          botsToRemove.foreach(p => self ! BotManager.DespawnBot(p.GUID))
        }
      }
    }
  }

  private def isBotPlayer(player: Player): Boolean = {
    // Check if name starts with [BOT]
    player.Name.startsWith("[BOT]")
  }

  private def isAce(player: Player): Boolean = {
    player.Name.contains("Ace_")
  }

  private def findSpawnPosition(faction: PlanetSideEmpire.Value): Vector3 = {
    // TODO: Find appropriate spawn point for faction
    // Could use owned bases, warpgates, etc.
    Vector3(100f, 100f, 10f)  // Placeholder
  }

  private def randomBotClass(): BotClass = {
    // Weighted random class selection
    // More grunts than specialists
    BotClass("Grunt", BotRole.Veteran, Set(), 10)  // Placeholder
  }
}

object BotManager {
  case object CheckPopulation
  case class SpawnBot(faction: PlanetSideEmpire.Value, botClass: BotClass, position: Vector3)
  case class DespawnBot(guid: PlanetSideGUID)
  case object DespawnAll
}
