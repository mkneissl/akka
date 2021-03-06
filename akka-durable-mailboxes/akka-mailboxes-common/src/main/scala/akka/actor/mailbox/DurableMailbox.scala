/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import MailboxProtocol._

import akka.actor.{ Actor, ActorRef, NullChannel }
import akka.dispatch._
import akka.event.EventHandler
import akka.remote.MessageSerializer
import akka.remote.protocol.RemoteProtocol.MessageProtocol
import akka.AkkaException

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait DurableMailboxBase {
  def serialize(message: MessageInvocation): Array[Byte]
  def deserialize(bytes: Array[Byte]): MessageInvocation
}

private[akka] object DurableExecutableMailboxConfig {
  val Name = "[\\.\\/\\$\\s]".r
}

class DurableMailboxException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause){
  def this(message: String) = this(message, null)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class DurableExecutableMailbox(owner: ActorRef) extends MessageQueue with ExecutableMailbox with DurableMailboxBase {
  import DurableExecutableMailboxConfig._

  val ownerAddress = owner.id
  val name = "mailbox_" + Name.replaceAllIn(ownerAddress, "_")

  EventHandler.debug(this, "Creating %s mailbox [%s]".format(getClass.getName, name))

  val dispatcher: ExecutorBasedEventDrivenDispatcher = owner.dispatcher match {
    case e: ExecutorBasedEventDrivenDispatcher ⇒ e
    case _                                     ⇒ null
  }

  //TODO: switch to RemoteProtocol
  def serialize(durableMessage: MessageInvocation) = {
    val message = MessageSerializer.serialize(durableMessage.message.asInstanceOf[AnyRef])
    val builder = DurableMailboxMessageProtocol.newBuilder
      .setOwnerAddress(ownerAddress)
      .setMessage(message.toByteString)
    durableMessage.channel match {
      case a: ActorRef ⇒ builder.setSenderAddress(a.id)
      case _           ⇒
    }
    builder.build.toByteArray
  }

  //TODO: switch to RemoteProtocol
  def deserialize(bytes: Array[Byte]) = {
    val durableMessage = DurableMailboxMessageProtocol.parseFrom(bytes)
    val messageProtocol = MessageProtocol.parseFrom(durableMessage.getMessage)
    val message = MessageSerializer.deserialize(messageProtocol, None) //TODO Revisit and fix classloader
    val ownerAddress = durableMessage.getOwnerAddress
    val owner = Actor.registry.actorsFor(ownerAddress).headOption.getOrElse(
      throw new DurableMailboxException("No actor could be found for address [" + ownerAddress + "], could not deserialize message."))

    val senderOption = if (durableMessage.hasSenderAddress) {
      Actor.registry.actorsFor(durableMessage.getSenderAddress).headOption
    } else None
    val sender = senderOption match {
      case Some(ref) ⇒ ref
      case None      ⇒ NullChannel
    }

    new MessageInvocation(owner, message, sender)
  }
}
