/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.List

import se.scalablesolutions.akka.actor.{Actor, ActorRef, ActorInitializationException}

import org.multiverse.commitbarriers.CountDownCommitBarrier
import se.scalablesolutions.akka.AkkaException
import java.util.concurrent.{ConcurrentSkipListSet}
import se.scalablesolutions.akka.util.{Duration, HashCode, Logging}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class MessageInvocation(val receiver: ActorRef,
                              val message: Any,
                              val sender: Option[ActorRef],
                              val senderFuture: Option[CompletableFuture[Any]],
                              val transactionSet: Option[CountDownCommitBarrier]) {
  if (receiver eq null) throw new IllegalArgumentException("receiver is null")

  def invoke = try {
    receiver.invoke(this)
  } catch {
    case e: NullPointerException => throw new ActorInitializationException(
      "Don't call 'self ! message' in the Actor's constructor (e.g. body of the class).")
  }

  def send = receiver.dispatcher.dispatch(this)

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, receiver.actor)
    result = HashCode.hash(result, message.asInstanceOf[AnyRef])
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].receiver.actor == receiver.actor &&
    that.asInstanceOf[MessageInvocation].message == message
  }

  override def toString = synchronized {
    "MessageInvocation[" +
     "\n\tmessage = " + message +
     "\n\treceiver = " + receiver +
     "\n\tsender = " + sender +
     "\n\tsenderFuture = " + senderFuture +
     "\n\ttransactionSet = " + transactionSet +
     "]"
  }
}

class MessageQueueAppendFailedException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageQueue {
  def append(handle: MessageInvocation)
}

/* Tells the dispatcher that it should create a bounded mailbox with the specified push timeout
 * (If capacity > 0)
 */
case class BoundedMailbox(capacity: Int, pushTimeOut: Option[Duration])

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDispatcher extends Logging {
  protected val uuids = new ConcurrentSkipListSet[String]

  def dispatch(invocation: MessageInvocation)

  def start

  def shutdown

  def register(actorRef: ActorRef) {
    if(actorRef.mailbox eq null)
      actorRef.mailbox = createMailbox(actorRef)
    uuids add actorRef.uuid
  }
  def unregister(actorRef: ActorRef) = {
    uuids remove actorRef.uuid
    //actorRef.mailbox = null //FIXME should we null out the mailbox here?
    if (canBeShutDown) shutdown // shut down in the dispatcher's references is zero
  }
  
  def canBeShutDown: Boolean = uuids.isEmpty

  def isShutdown: Boolean

  /**
   * Returns the size of the mailbox for the specified actor
   */
  def mailboxSize(actorRef: ActorRef):Int

  /**
   *  Creates and returns a mailbox for the given actor
   */
  protected def createMailbox(actorRef: ActorRef): AnyRef = null
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDemultiplexer {
  def select
  def wakeUp
  def acquireSelectedInvocations: List[MessageInvocation]
  def releaseSelectedInvocations
}