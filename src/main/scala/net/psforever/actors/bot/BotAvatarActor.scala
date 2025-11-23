// Copyright (c) 2024 PSForever
package net.psforever.actors.bot

import akka.actor.typed.{Behavior, PostStop}
import akka.actor.typed.scaladsl.Behaviors
import net.psforever.actors.session.AvatarActor

/**
  * A stub typed actor that handles messages from PlayerControl for bots.
  * Bots don't need real avatar persistence, so this actor just absorbs messages.
  */
object BotAvatarActor {

  def apply(): Behavior[AvatarActor.Command] = Behaviors.setup { _ =>
    active()
  }

  private def active(): Behavior[AvatarActor.Command] = {
    Behaviors.receiveMessage[AvatarActor.Command] { _ =>
      // Absorb all messages - bots don't need real avatar management
      Behaviors.same
    }.receiveSignal {
      case (_, PostStop) =>
        Behaviors.same
    }
  }
}
