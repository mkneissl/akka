/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.event.EventHandler
import akka.dispatch._
import akka.config.Supervision._
import akka.util._
import ReflectiveAccess._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ScheduledFuture, ConcurrentHashMap, TimeUnit }
import java.util.{ Map ⇒ JMap }

import scala.reflect.BeanProperty
import scala.collection.immutable.Stack
import scala.annotation.tailrec

private[akka] object ActorRefInternals {

  /**
   * LifeCycles for ActorRefs.
   */
  private[akka] sealed trait StatusType
  object UNSTARTED extends StatusType
  object RUNNING extends StatusType
  object BEING_RESTARTED extends StatusType
  object SHUTDOWN extends StatusType
}

/**
 * ActorRef is an immutable and serializable handle to an Actor.
 * <p/>
 * Create an ActorRef for an Actor by using the factory method on the Actor object.
 * <p/>
 * Here is an example on how to create an actor with a default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf[MyActor]
 *   actor.start()
 *   actor ! message
 *   actor.stop()
 * </pre>
 *
 * You can also create and start actors like this:
 * <pre>
 *   val actor = actorOf[MyActor].start()
 * </pre>
 *
 * Here is an example on how to create an actor with a non-default constructor.
 * <pre>
 *   import Actor._
 *
 *   val actor = actorOf(new MyActor(...))
 *   actor.start()
 *   actor ! message
 *   actor.stop()
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait ActorRef extends ActorRefShared with ForwardableChannel with ReplyChannel[Any] with java.lang.Comparable[ActorRef] {
  scalaRef: ScalaActorRef ⇒

  // Only mutable for RemoteServer in order to maintain identity across nodes
  @volatile
  protected[akka] var _uuid = newUuid
  @volatile
  protected[this] var _status: ActorRefInternals.StatusType = ActorRefInternals.UNSTARTED

  /**
   * User overridable callback/setting.
   * <p/>
   * Identifier for actor, does not have to be a unique one. Default is the 'uuid'.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor(id), identifier for remote
   * actor in RemoteServer etc.But also as the identifier for persistence, which means
   * that you can use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  @BeanProperty
  @volatile
  var id: String = _uuid.toString

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for '!!' and '!!!' invocations,
   * e.g. the timeout for the future returned by the call to '!!' and '!!!'.
   */
  @deprecated("Will be replaced by implicit-scoped timeout on all methods that needs it, will default to timeout specified in config", "1.1")
  @BeanProperty
  @volatile
  var timeout: Long = Actor.TIMEOUT

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  @volatile
  var receiveTimeout: Option[Long] = None

  /**
   * Akka Java API. <p/>
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  def setReceiveTimeout(timeout: Long) = this.receiveTimeout = Some(timeout)
  def getReceiveTimeout(): Option[Long] = receiveTimeout

  /**
   * Akka Java API. <p/>
   *  A faultHandler defines what should be done when a linked actor signals an error.
   * <p/>
   * Can be one of:
   * <pre>
   * getContext().setFaultHandler(new AllForOneStrategy(new Class[]{Throwable.class},maxNrOfRetries, withinTimeRange));
   * </pre>
   * Or:
   * <pre>
   * getContext().setFaultHandler(new OneForOneStrategy(new Class[]{Throwable.class},maxNrOfRetries, withinTimeRange));
   * </pre>
   */
  def setFaultHandler(handler: FaultHandlingStrategy)
  def getFaultHandler(): FaultHandlingStrategy

  /**
   * Akka Java API. <p/>
   *  A lifeCycle defines whether the actor will be stopped on error (Temporary) or if it can be restarted (Permanent)
   * <p/>
   * Can be one of:
   *
   * import static akka.config.Supervision.*;
   * <pre>
   * getContext().setLifeCycle(permanent());
   * </pre>
   * Or:
   * <pre>
   * getContext().setLifeCycle(temporary());
   * </pre>
   */
  def setLifeCycle(lifeCycle: LifeCycle): Unit
  def getLifeCycle(): LifeCycle

  /**
   * Akka Java API. <p/>
   * The default dispatcher is the <tt>Dispatchers.globalExecutorBasedEventDrivenDispatcher</tt>.
   * This means that all actors will share the same event-driven executor based dispatcher.
   * <p/>
   * You can override it so it fits the specific use-case that the actor is used for.
   * See the <tt>akka.dispatch.Dispatchers</tt> class for the different
   * dispatchers available.
   * <p/>
   * The default is also that all actors that are created and spawned from within this actor
   * is sharing the same dispatcher as its creator.
   */
  def setDispatcher(dispatcher: MessageDispatcher) = this.dispatcher = dispatcher
  def getDispatcher(): MessageDispatcher = dispatcher

  /**
   * Returns on which node this actor lives if None it lives in the local ActorRegistry
   */
  @deprecated("Remoting will become fully transparent in the future", "1.1")
  def homeAddress: Option[InetSocketAddress]

  /**
   * Java API. <p/>
   */
  @deprecated("Remoting will become fully transparent in the future", "1.1")
  def getHomeAddress(): InetSocketAddress = homeAddress getOrElse null

  /**
   *   Holds the hot swapped partial function.
   */
  @volatile
  protected[akka] var hotswap = Stack[PartialFunction[Any, Unit]]()

  /**
   *  This is a reference to the message currently being processed by the actor
   */
  @volatile
  protected[akka] var currentMessage: MessageInvocation = null

  /**
   * Comparison only takes uuid into account.
   */
  def compareTo(other: ActorRef) = this.uuid compareTo other.uuid

  /**
   * Returns the uuid for the actor.
   */
  def getUuid() = _uuid
  def uuid = _uuid

  /**
   * Akka Java API. <p/>
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def getSender(): Option[ActorRef] = sender

  /**
   * Akka Java API. <p/>
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '!!' or '!!!', else None.
   */
  def getSenderFuture(): Option[CompletableFuture[Any]] = senderFuture

  /**
   * Is the actor being restarted?
   */
  def isBeingRestarted: Boolean = _status == ActorRefInternals.BEING_RESTARTED

  /**
   * Is the actor running?
   */
  def isRunning: Boolean = _status match {
    case ActorRefInternals.BEING_RESTARTED | ActorRefInternals.RUNNING ⇒ true
    case _ ⇒ false
  }

  /**
   * Is the actor shut down?
   */
  def isShutdown: Boolean = _status == ActorRefInternals.SHUTDOWN

  /**
   * Is the actor ever started?
   */
  def isUnstarted: Boolean = _status == ActorRefInternals.UNSTARTED

  /**
   * Is the actor able to handle the message passed in as arguments?
   */
  @deprecated("Will be removed without replacement, it's just not reliable in the face of `become` and `unbecome`", "1.1")
  def isDefinedAt(message: Any): Boolean = actor.isDefinedAt(message)

  /**
   * Only for internal use. UUID is effectively final.
   */
  protected[akka] def uuid_=(uid: Uuid) = _uuid = uid

  /**
   * Akka Java API. <p/>
   * @see sendRequestReply(message: AnyRef, timeout: Long, sender: ActorRef)
   * Uses the default timeout of the Actor (setTimeout()) and omits the sender reference
   */
  @deprecated("Will be removed in 2.0, use 'ask().get()' for blocking calls", "1.2")
  def sendRequestReply(message: AnyRef): AnyRef = {
    !!(message, timeout).getOrElse(throw new ActorTimeoutException(
      "Message [" + message +
        "]\n\tsent to [" + actorClassName +
        "]\n\tfrom [nowhere]\n\twith timeout [" + timeout +
        "]\n\ttimed out."))
      .asInstanceOf[AnyRef]
  }

  /**
   * Akka Java API. <p/>
   * @see sendRequestReply(message: AnyRef, timeout: Long, sender: ActorRef)
   * Uses the default timeout of the Actor (setTimeout())
   */
  @deprecated("Will be removed in 2.0, use 'ask().get()' for blocking calls", "1.2")
  def sendRequestReply(message: AnyRef, sender: ActorRef): AnyRef = sendRequestReply(message, timeout, sender)

  /**
   * Akka Java API. <p/>
   * Sends a message asynchronously and waits on a future for a reply message under the hood.
   * <p/>
   * It waits on the reply either until it receives it or until the timeout expires
   * (which will throw an ActorTimeoutException). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'tell' together with 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReply</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  @deprecated("Will be removed in 2.0, use 'ask().get()' for blocking calls", "1.2")
  def sendRequestReply(message: AnyRef, timeout: Long, sender: ActorRef): AnyRef = {
    ?(message)(sender, Actor.Timeout(timeout)).as[AnyRef].getOrElse(throw new ActorTimeoutException(
      "Message [" + message +
        "]\n\tsent to [" + actorClassName +
        "]\n\tfrom [" + (if (sender ne null) sender.actorClassName else "nowhere") +
        "]\n\twith timeout [" + timeout +
        "]\n\ttimed out."))
  }

  /**
   * Akka Java API. <p/>
   * @see sendRequestReplyFuture(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout()) and omits the sender
   */
  @deprecated("Use 'ask' instead, this method will be removed in the future", "1.2")
  def sendRequestReplyFuture(message: AnyRef): Future[Any] = ?(message)

  /**
   * Akka Java API. <p/>
   * @see sendRequestReplyFuture(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout())
   */
  @deprecated("Use 'ask' instead, this method will be removed in the future", "1.2")
  def sendRequestReplyFuture(message: AnyRef, sender: ActorRef): Future[Any] = ?(message)(sender)

  /**
   *  Akka Java API. <p/>
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'tell' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>sendRequestReplyFuture</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  @deprecated("Use 'ask' instead, this method will be removed in the future", "1.2")
  def sendRequestReplyFuture(message: AnyRef, timeout: Long, sender: ActorRef): Future[Any] = ?(message)(sender, Actor.Timeout(timeout))

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout()) and omits the sender
   */
  def ask(message: AnyRef): Future[Any] = ?(message)

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the specified timeout (milliseconds)
   */
  def ask(message: AnyRef, timeout: Long): Future[Any] = ?(message)(timeout = Actor.Timeout(timeout))

  /**
   * Akka Java API. <p/>
   * @see ask(message: AnyRef, sender: ActorRef): Future[_]
   * Uses the Actors default timeout (setTimeout())
   */
  def ask(message: AnyRef, sender: ActorRef): Future[Any] = ?(message)(sender)

  /**
   *  Akka Java API. <p/>
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use 'tell' together with the 'getContext().getSender()' to
   * implement request/response message exchanges.
   * <p/>
   * If you are sending messages using <code>ask</code> then you <b>have to</b> use <code>getContext().reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  def ask(message: AnyRef, timeout: Long, sender: ActorRef): Future[Any] = ?(message)(sender, Actor.Timeout(timeout))

  /**
   * Akka Java API. <p/>
   * Forwards the message specified to this actor and preserves the original sender of the message
   */
  def forward(message: AnyRef, sender: ActorRef): Unit =
    if (sender eq null) throw new IllegalArgumentException("The 'sender' argument to 'forward' can't be null")
    else forward(message)(sender)

  /**
   * Akka Java API. <p/>
   * Use <code>getContext().replyUnsafe(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  @deprecated("replaced by reply", "1.2")
  def replyUnsafe(message: AnyRef) = reply(message)

  /**
   * Akka Java API. <p/>
   * Use <code>getContext().replySafe(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  @deprecated("replaced by tryReply", "1.2")
  def replySafe(message: AnyRef): Boolean = tryReply(message)

  /**
   * Use <code>self.reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed. This method  fails if the original sender of the message could not be determined with an
   * IllegalStateException.
   * <p/>
   * If you don't want deal with this IllegalStateException, but just a boolean, just use the <code>tryReply(...)</code>
   * version.
   *
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def reply(message: Any) = channel.!(message)(this)

  /**
   * Use <code>getContext().tryReply(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  def tryReply(message: Any): Boolean = channel.tryTell(message)(this)

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  @deprecated("Will be removed without replacement, doesn't make any sense to have in the face of `become` and `unbecome`", "1.1")
  def actorClass: Class[_ <: Actor]

  /**
   * Akka Java API. <p/>
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  @deprecated("Will be removed without replacement, doesn't make any sense to have in the face of `become` and `unbecome`", "1.1")
  def getActorClass(): Class[_ <: Actor] = actorClass

  /**
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  @deprecated("Will be removed without replacement, doesn't make any sense to have in the face of `become` and `unbecome`", "1.1")
  def actorClassName: String

  /**
   * Akka Java API. <p/>
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  @deprecated("Will be removed without replacement, doesn't make any sense to have in the face of `become` and `unbecome`", "1.1")
  def getActorClassName(): String = actorClassName

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher

  /**
   * Starts up the actor and its message queue.
   */
  def start(): this.type

  /**
   * Shuts down the actor its dispatcher and message queue.
   * Alias for 'stop'.
   */
  def exit() = stop()

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop(): Unit

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field of the 'faultHandler' has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   */
  def link(actorRef: ActorRef): Unit

  /**
   * Unlink the actor.
   */
  def unlink(actorRef: ActorRef): Unit

  /**
   * Atomically start and link an actor.
   */
  def startLink(actorRef: ActorRef): Unit

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  @deprecated("Will be removed after 1.1, use Actor.actorOf instead", "1.1")
  def spawn(clazz: Class[_ <: Actor]): ActorRef

  /**
   * Atomically create (from actor class), make it remote and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  @deprecated("Will be removed after 1.1, client managed actors will be removed", "1.1")
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef

  /**
   * Atomically create (from actor class), link and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  @deprecated("Will be removed after 1.1, use use Actor.remote.actorOf instead and then link on success", "1.1")
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef

  /**
   * Atomically create (from actor class), make it remote, link and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  @deprecated("Will be removed after 1.1, client managed actors will be removed", "1.1")
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef

  /**
   * Returns the mailbox size.
   */
  @deprecated("Use actorref.dispatcher.mailboxSize(actorref)", "1.2")
  def mailboxSize = dispatcher.mailboxSize(this)

  /**
   * Akka Java API. <p/>
   * Returns the mailbox size.
   */
  @deprecated("Use actorref.dispatcher.mailboxSize(actorref)", "1.2")
  def getMailboxSize(): Int = mailboxSize

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef]

  /**
   * Akka Java API. <p/>
   * Returns the supervisor, if there is one.
   */
  def getSupervisor(): ActorRef = supervisor getOrElse null

  /**
   * Returns an unmodifiable Java Map containing the linked actors,
   * please note that the backing map is thread-safe but not immutable
   */
  def linkedActors: JMap[Uuid, ActorRef]

  /**
   * Java API. <p/>
   * Returns an unmodifiable Java Map containing the linked actors,
   * please note that the backing map is thread-safe but not immutable
   */
  def getLinkedActors(): JMap[Uuid, ActorRef] = linkedActors

  /**
   * Abstraction for unification of sender and senderFuture for later reply
   */
  def channel: UntypedChannel = {
    val msg = currentMessage
    if (msg ne null) msg.channel
    else NullChannel
  }

  /**
   * Java API. <p/>
   * Abstraction for unification of sender and senderFuture for later reply
   */
  def getChannel: UntypedChannel = channel

  protected[akka] def invoke(messageHandle: MessageInvocation): Unit

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Long,
    channel: UntypedChannel): ActorCompletableFuture

  protected[akka] def actorInstance: AtomicReference[Actor]

  protected[akka] def actor: Actor = actorInstance.get

  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit

  protected[akka] def mailbox: AnyRef
  protected[akka] def mailbox_=(value: AnyRef): AnyRef

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit

  protected[akka] def registerSupervisorAsRemoteActor: Option[Uuid]

  override def hashCode: Int = HashCode.hash(HashCode.SEED, uuid)

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[ActorRef] &&
      that.asInstanceOf[ActorRef].uuid == uuid
  }

  override def toString = "Actor[" + id + ":" + uuid + "]"
}

/**
 *  Local (serializable) ActorRef that is used when referencing the Actor on its "home" node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalActorRef private[akka] (
  private[this] val actorFactory: () ⇒ Actor,
  val homeAddress: Option[InetSocketAddress],
  val clientManaged: Boolean = false)
  extends ActorRef with ScalaActorRef {

  protected[akka] val guard = new ReentrantGuard

  @volatile
  protected[akka] var _futureTimeout: Option[ScheduledFuture[AnyRef]] = None
  @volatile
  private[akka] lazy val _linkedActors = new ConcurrentHashMap[Uuid, ActorRef]
  @volatile
  private[akka] var _supervisor: Option[ActorRef] = None
  @volatile
  private var maxNrOfRetriesCount: Int = 0
  @volatile
  private var restartTimeWindowStartNanos: Long = 0L
  @volatile
  private var _mailbox: AnyRef = _
  @volatile
  private[akka] var _dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher

  protected[akka] val actorInstance = guard.withGuard { new AtomicReference[Actor](newActor) }

  //If it was started inside "newActor", initialize it
  if (isRunning) initializeActorInstance

  // used only for deserialization
  private[akka] def this(
    __uuid: Uuid,
    __id: String,
    __timeout: Long,
    __receiveTimeout: Option[Long],
    __lifeCycle: LifeCycle,
    __supervisor: Option[ActorRef],
    __hotswap: Stack[PartialFunction[Any, Unit]],
    __factory: () ⇒ Actor,
    __homeAddress: Option[InetSocketAddress]) = {
    this(__factory, __homeAddress)
    _uuid = __uuid
    id = __id
    timeout = __timeout
    receiveTimeout = __receiveTimeout
    lifeCycle = __lifeCycle
    _supervisor = __supervisor
    hotswap = __hotswap
    setActorSelfFields(actor, this)
    start
  }

  /**
   * Returns whether this actor ref is client-managed remote or not
   */
  private[akka] final def isClientManaged_? = clientManaged && homeAddress.isDefined && isRemotingEnabled

  // ========= PUBLIC FUNCTIONS =========

  /**
   * Returns the class for the Actor instance that is managed by the ActorRef.
   */
  @deprecated("Will be removed without replacement, doesn't make any sense to have in the face of `become` and `unbecome`", "1.1")
  def actorClass: Class[_ <: Actor] = actor.getClass.asInstanceOf[Class[_ <: Actor]]

  /**
   * Returns the class name for the Actor instance that is managed by the ActorRef.
   */
  @deprecated("Will be removed without replacement, doesn't make any sense to have in the face of `become` and `unbecome`", "1.1")
  def actorClassName: String = actorClass.getName

  /**
   * Sets the dispatcher for this actor. Needs to be invoked before the actor is started.
   */
  def dispatcher_=(md: MessageDispatcher): Unit = guard.withGuard {
    if (!isBeingRestarted) {
      if (!isRunning) _dispatcher = md
      else throw new ActorInitializationException(
        "Can not swap dispatcher for " + toString + " after it has been started")
    }
  }

  /**
   * Get the dispatcher for this actor.
   */
  def dispatcher: MessageDispatcher = _dispatcher

  /**
   * Starts up the actor and its message queue.
   */
  def start(): this.type = guard.withGuard[this.type] {
    if (isShutdown) throw new ActorStartException(
      "Can't restart an actor that has been shut down with 'stop' or 'exit'")
    if (!isRunning) {
      _status = ActorRefInternals.RUNNING
      try {
        dispatcher.attach(this)

        // If we are not currently creating this ActorRef instance
        if ((actorInstance ne null) && (actorInstance.get ne null))
          initializeActorInstance

        if (isClientManaged_?)
          Actor.remote.registerClientManagedActor(homeAddress.get.getAddress.getHostAddress, homeAddress.get.getPort, uuid)

        checkReceiveTimeout //Schedule the initial Receive timeout
      } catch {
        case e ⇒
          _status = ActorRefInternals.UNSTARTED
          throw e
      }
    }
    this
  }

  /**
   * Shuts down the actor its dispatcher and message queue.
   */
  def stop() = guard.withGuard {
    if (isRunning) {
      receiveTimeout = None
      cancelReceiveTimeout

      Actor.registry.unregister(this)

      if (isRemotingEnabled) {
        if (isClientManaged_?)
          Actor.remote.unregisterClientManagedActor(homeAddress.get.getAddress.getHostAddress, homeAddress.get.getPort, uuid)
        Actor.remote.unregister(this)
      }
      _status = ActorRefInternals.SHUTDOWN
      dispatcher.detach(this)

      try {
        val a = actor
        if (Actor.debugLifecycle) EventHandler.debug(a, "stopping")
        a.postStop
      } finally {
        notifySupervisorWithMessage(Death(this, null))
        currentMessage = null
        setActorSelfFields(actorInstance.get, null)
      }
    } //else if (isBeingRestarted) throw new ActorKilledException("Actor [" + toString + "] is being restarted.")
  }

  /**
   * Links an other actor to this actor. Links are unidirectional and means that a the linking actor will
   * receive a notification if the linked actor has crashed.
   * <p/>
   * If the 'trapExit' member field of the 'faultHandler' has been set to at contain at least one exception class then it will
   * 'trap' these exceptions and automatically restart the linked actors according to the restart strategy
   * defined by the 'faultHandler'.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def link(actorRef: ActorRef): Unit = guard.withGuard {
    val actorRefSupervisor = actorRef.supervisor
    val hasSupervisorAlready = actorRefSupervisor.isDefined
    if (hasSupervisorAlready && actorRefSupervisor.get.uuid == uuid) return // we already supervise this guy
    else if (hasSupervisorAlready) throw new IllegalActorStateException(
      "Actor can only have one supervisor [" + actorRef + "], e.g. link(actor) fails")
    else {
      _linkedActors.put(actorRef.uuid, actorRef)
      actorRef.supervisor = Some(this)
    }
    if (Actor.debugLifecycle) EventHandler.debug(actor, "now supervising " + actorRef)
  }

  /**
   * Unlink the actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def unlink(actorRef: ActorRef) = guard.withGuard {
    if (_linkedActors.remove(actorRef.uuid) eq null)
      throw new IllegalActorStateException("Actor [" + actorRef + "] is not a linked actor, can't unlink")

    actorRef.supervisor = None
    if (Actor.debugLifecycle) EventHandler.debug(actor, "stopped supervising " + actorRef)
  }

  /**
   * Atomically start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def startLink(actorRef: ActorRef): Unit = guard.withGuard {
    link(actorRef)
    actorRef.start()
  }

  /**
   * Atomically create (from actor class) and start an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawn(clazz: Class[_ <: Actor]): ActorRef =
    Actor.actorOf(clazz).start()

  /**
   * Atomically create (from actor class), start and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long = Actor.TIMEOUT): ActorRef = {
    ensureRemotingEnabled
    val ref = Actor.remote.actorOf(clazz, hostname, port)
    ref.timeout = timeout
    ref.start()
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef = {
    val actor = spawn(clazz)
    link(actor)
    actor.start()
    actor
  }

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   * <p/>
   * To be invoked from within the actor itself.
   */
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long = Actor.TIMEOUT): ActorRef = {
    ensureRemotingEnabled
    val actor = Actor.remote.actorOf(clazz, hostname, port)
    actor.timeout = timeout
    link(actor)
    actor.start()
    actor
  }

  /**
   * Returns the mailbox.
   */
  def mailbox: AnyRef = _mailbox

  protected[akka] def mailbox_=(value: AnyRef): AnyRef = { _mailbox = value; value }

  /**
   * Returns the supervisor, if there is one.
   */
  def supervisor: Option[ActorRef] = _supervisor

  // ========= AKKA PROTECTED FUNCTIONS =========

  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = _supervisor = sup

  protected[akka] def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit =
    if (isClientManaged_?) {
      val sender = channel match {
        case ref: ActorRef ⇒ Some(ref)
        case _             ⇒ None
      }
      val chFuture = channel match {
        case f: ActorCompletableFuture ⇒ Some(f)
        case _                         ⇒ None
      }
      Actor.remote.send[Any](
        message, sender, chFuture, homeAddress.get, timeout, chFuture.isEmpty, this, None, ActorType.ScalaActor, None)
    } else {
      dispatcher dispatchMessage new MessageInvocation(this, message, channel)
    }

  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Long,
    channel: UntypedChannel): ActorCompletableFuture = {
    if (isClientManaged_?) {
      val chSender = channel match {
        case ref: ActorRef ⇒ Some(ref)
        case _             ⇒ None
      }
      val chFuture = channel match {
        case f: ActorCompletableFuture ⇒ Some(f)
        case _                         ⇒ None
      }
      val future = Actor.remote.send[Any](message, chSender, chFuture, homeAddress.get, timeout, false, this, None, ActorType.ScalaActor, None)
      if (future.isDefined) ActorCompletableFuture(future.get)
      else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
    } else {
      val future = channel match {
        case f: ActorCompletableFuture ⇒ f
        case _                         ⇒ new ActorCompletableFuture(timeout)(dispatcher)
      }
      dispatcher dispatchMessage new MessageInvocation(this, message, future)
      future
    }
  }

  /**
   * Callback for the dispatcher. This is the single entry point to the user Actor implementation.
   */
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = {
    guard.lock.lock
    try {
      if (!isShutdown) {
        currentMessage = messageHandle
        try {
          try {
            cancelReceiveTimeout // FIXME: leave this here?
            actor(messageHandle.message)
            currentMessage = null // reset current message after successful invocation
          } catch {
            case e: InterruptedException ⇒
              currentMessage = null // received message while actor is shutting down, ignore
            case e ⇒
              handleExceptionInDispatch(e, messageHandle.message)
          } finally {
            checkReceiveTimeout // Reschedule receive timeout
          }
        } catch {
          case e ⇒
            EventHandler.error(e, actor, e.getMessage)
            throw e
        }
      }
    } finally { guard.lock.unlock }
  }

  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable) {
    faultHandler match {
      case AllForOneStrategy(_, _, _) if reason == null => //Stopped
        if (_linkedActors.remove(dead.uuid) ne null) {
          val i = _linkedActors.values.iterator
          while (i.hasNext) {
            i.next.stop()
            i.remove
          }
        }
        
      case AllForOneStrategy(trapExit, maxRetries, within) if trapExit.exists(_.isAssignableFrom(reason.getClass)) ⇒
        restartLinkedActors(reason, maxRetries, within)

      case OneForOneStrategy(_, _, _) if reason == null => //Stopped
        _linkedActors.remove(dead.uuid)

      case OneForOneStrategy(trapExit, maxRetries, within) if trapExit.exists(_.isAssignableFrom(reason.getClass)) ⇒
        dead.restart(reason, maxRetries, within)

      case _ ⇒
        if (_supervisor.isDefined) notifySupervisorWithMessage(Death(this, reason)) else dead.stop()
    }
  }

  private def requestRestartPermission(maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Boolean = {
    val denied = if (_status == ActorRefInternals.SHUTDOWN) {
      true
    } else if (maxNrOfRetries.isEmpty && withinTimeRange.isEmpty) { //Immortal
      false
    } else if (withinTimeRange.isEmpty) { // restrict number of restarts
      val retries = maxNrOfRetriesCount + 1
      maxNrOfRetriesCount = retries //Increment number of retries
      retries > maxNrOfRetries.get
    } else { // cannot restart more than N within M timerange
      val retries = maxNrOfRetriesCount + 1

      val windowStart = restartTimeWindowStartNanos
      val now = System.nanoTime
      //We are within the time window if it isn't the first restart, or if the window hasn't closed
      val insideWindow = if (windowStart == 0) true else (now - windowStart) <= TimeUnit.MILLISECONDS.toNanos(withinTimeRange.get)

      if (windowStart == 0 || !insideWindow) //(Re-)set the start of the window
        restartTimeWindowStartNanos = now

      //Reset number of restarts if window has expired, otherwise, increment it
      maxNrOfRetriesCount = if (windowStart != 0 && !insideWindow) 1 else retries //Increment number of retries

      val restartCountLimit = if (maxNrOfRetries.isDefined) maxNrOfRetries.get else 1

      //The actor is dead if it dies X times within the window of restart
      insideWindow && retries > restartCountLimit
    }

    denied == false //If we weren't denied, we have a go
  }

  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    def performRestart() {
      val failedActor = actorInstance.get

      if (Actor.debugLifecycle) EventHandler.debug(failedActor, "restarting")

      val message = if (currentMessage ne null) Some(currentMessage.message) else None

      failedActor match {
        case p: Proxyable ⇒
          failedActor.preRestart(reason, message)
          failedActor.postRestart(reason)
        case _ ⇒
          failedActor.preRestart(reason, message)
          val freshActor = newActor
          setActorSelfFields(failedActor, null) // Only null out the references if we could instantiate the new actor
          actorInstance.set(freshActor) // Assign it here so if preStart fails, we can null out the sef-refs next call
          freshActor.preStart
          freshActor.postRestart(reason)
          if (Actor.debugLifecycle) EventHandler.debug(freshActor, "restarted")
      }
    }

    def tooManyRestarts() {
      _supervisor.foreach { sup ⇒
        // can supervisor handle the notification?
        val notification = MaximumNumberOfRestartsWithinTimeRangeReached(this, maxNrOfRetries, withinTimeRange, reason)
        if (sup.isDefinedAt(notification)) notifySupervisorWithMessage(notification)
      }
      stop
    }

    @tailrec
    def attemptRestart() {
      val success = if (requestRestartPermission(maxNrOfRetries, withinTimeRange)) {
        guard.withGuard[Boolean] {
          _status = ActorRefInternals.BEING_RESTARTED

          lifeCycle match {
            case Temporary ⇒
              shutDownTemporaryActor(this)
              true

            case _ ⇒ // either permanent or none where default is permanent
              val success = try {
                performRestart()
                true
              } catch {
                case e ⇒
                  EventHandler.error(e, this, "Exception in restart of Actor [%s]".format(toString))
                  false // an error or exception here should trigger a retry
              } finally {
                currentMessage = null
              }
              if (success) {
                _status = ActorRefInternals.RUNNING
                dispatcher.resume(this)
                restartLinkedActors(reason, maxNrOfRetries, withinTimeRange)
              }
              success
          }
        }
      } else {
        tooManyRestarts()
        true // done
      }

      if (success) () // alles gut
      else attemptRestart()
    }

    attemptRestart() // recur
  }

  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) = {
    val i = _linkedActors.values.iterator
    while (i.hasNext) {
      val actorRef = i.next
      actorRef.lifeCycle match {
        // either permanent or none where default is permanent
        case Temporary ⇒ shutDownTemporaryActor(actorRef)
        case _         ⇒ actorRef.restart(reason, maxNrOfRetries, withinTimeRange)
      }
    }
  }

  protected[akka] def registerSupervisorAsRemoteActor: Option[Uuid] = {
    ensureRemotingEnabled
    val sup = _supervisor
    if (sup.isDefined) {
      if (homeAddress.isDefined) Actor.remote.registerSupervisorForActor(this)
      Some(sup.get.uuid)
    } else None
  }

  def linkedActors: JMap[Uuid, ActorRef] = java.util.Collections.unmodifiableMap(_linkedActors)

  // ========= PRIVATE FUNCTIONS =========

  private[this] def newActor: Actor = {
    import Actor.{ actorRefInCreation ⇒ refStack }
    (try {
      refStack.set(refStack.get.push(this))
      if (_status == ActorRefInternals.BEING_RESTARTED) {
        val a = actor
        val fresh = try a.freshInstance catch {
          case e ⇒
            EventHandler.error(e, a, "freshInstance() failed, falling back to initial actor factory")
            None
        }
        fresh match {
          case Some(ref) ⇒ ref
          case None      ⇒ actorFactory()
        }
      } else {
        actorFactory()
      }
    } catch {
      case e ⇒
        val stack = refStack.get
        //Clean up if failed
        if ((stack.nonEmpty) && (stack.head eq this)) refStack.set(stack.pop)
        //Then rethrow
        throw e
    }) match {
      case null  ⇒ throw new ActorInitializationException("Actor instance passed to ActorRef can not be 'null'")
      case valid ⇒ valid
    }
  }

  private def shutDownTemporaryActor(temporaryActor: ActorRef) {
    temporaryActor.stop()
    _linkedActors.remove(temporaryActor.uuid) // remove the temporary actor
    // if last temporary actor is gone, then unlink me from supervisor
    if (_linkedActors.isEmpty) notifySupervisorWithMessage(UnlinkAndStop(this))
    true
  }

  private def handleExceptionInDispatch(reason: Throwable, message: Any) = {
    EventHandler.error(reason, this, reason.getMessage)

    //Prevent any further messages to be processed until the actor has been restarted
    dispatcher.suspend(this)

    channel.sendException(reason)

    if (supervisor.isDefined) notifySupervisorWithMessage(Death(this, reason))
    else {
      lifeCycle match {
        case Temporary ⇒ shutDownTemporaryActor(this)
        case _         ⇒ dispatcher.resume(this) //Resume processing for this actor
      }
    }
  }

  private def notifySupervisorWithMessage(notification: LifeCycleMessage) = {
    // FIXME to fix supervisor restart of remote actor for oneway calls, inject a supervisor proxy that can send notification back to client
    _supervisor.foreach { sup ⇒
      if (sup.isShutdown) { // if supervisor is shut down, game over for all linked actors
        //Scoped stop all linked actors, to avoid leaking the 'i' val
        {
          val i = _linkedActors.values.iterator
          while (i.hasNext) {
            i.next.stop()
            i.remove
          }
        }
        //Stop the actor itself
        stop
      } else sup ! notification // else notify supervisor
    }
  }

  private def setActorSelfFields(actor: Actor, value: ActorRef) {

    @tailrec
    def lookupAndSetSelfFields(clazz: Class[_], actor: Actor, value: ActorRef): Boolean = {
      val success = try {
        val selfField = clazz.getDeclaredField("self")
        val someSelfField = clazz.getDeclaredField("someSelf")
        selfField.setAccessible(true)
        someSelfField.setAccessible(true)
        selfField.set(actor, value)
        someSelfField.set(actor, if (value ne null) Some(value) else null)
        true
      } catch {
        case e: NoSuchFieldException ⇒ false
      }

      if (success) true
      else {
        val parent = clazz.getSuperclass
        if (parent eq null)
          throw new IllegalActorStateException(toString + " is not an Actor since it have not mixed in the 'Actor' trait")
        lookupAndSetSelfFields(parent, actor, value)
      }
    }

    lookupAndSetSelfFields(actor.getClass, actor, value)
  }

  private def initializeActorInstance = {
    val a = actor
    if (Actor.debugLifecycle) EventHandler.debug(a, "started")
    a.preStart // run actor preStart
    Actor.registry.register(this)
  }

  protected[akka] def checkReceiveTimeout() {
    cancelReceiveTimeout
    if (receiveTimeout.isDefined && dispatcher.mailboxIsEmpty(this)) { //Only reschedule if desired and there are currently no more messages to be processed
      _futureTimeout = Some(Scheduler.scheduleOnce(this, ReceiveTimeout, receiveTimeout.get, TimeUnit.MILLISECONDS))
    }
  }

  protected[akka] def cancelReceiveTimeout = {
    if (_futureTimeout.isDefined) {
      _futureTimeout.get.cancel(true)
      _futureTimeout = None
    }
  }
}

/**
 * System messages for RemoteActorRef.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RemoteActorSystemMessage {
  val Stop = "RemoteActorRef:stop".intern
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] case class RemoteActorRef private[akka] (
  classOrServiceName: String,
  val actorClassName: String,
  val hostname: String,
  val port: Int,
  _timeout: Long,
  loader: Option[ClassLoader],
  val actorType: ActorType = ActorType.ScalaActor)
  extends ActorRef with ScalaActorRef {

  ensureRemotingEnabled

  val homeAddress = Some(new InetSocketAddress(hostname, port))

  //protected def clientManaged = classOrServiceName.isEmpty //If no class or service name, it's client managed
  id = classOrServiceName
  //id = classOrServiceName.getOrElse("uuid:" + uuid) //If we're a server-managed we want to have classOrServiceName as id, or else, we're a client-managed and we want to have our uuid as id

  timeout = _timeout

  start

  def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = {
    val chSender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    val chFuture = channel match {
      case f: ActorCompletableFuture ⇒ Some(f)
      case _                         ⇒ None
    }
    Actor.remote.send[Any](message, chSender, chFuture, homeAddress.get, timeout, chFuture.isEmpty, this, None, actorType, loader)
  }

  def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Long,
    channel: UntypedChannel): ActorCompletableFuture = {
    val chSender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    val chFuture = channel match {
      case f: ActorCompletableFuture ⇒ Some(f)
      case _                         ⇒ None
    }
    val future = Actor.remote.send[Any](message, chSender, chFuture, homeAddress.get, timeout, false, this, None, actorType, loader)
    if (future.isDefined) ActorCompletableFuture(future.get)
    else throw new IllegalActorStateException("Expected a future from remote call to actor " + toString)
  }

  def start: this.type = synchronized[this.type] {
    _status = ActorRefInternals.RUNNING
    this
  }

  def stop: Unit = synchronized {
    if (_status == ActorRefInternals.RUNNING) {
      _status = ActorRefInternals.SHUTDOWN
      postMessageToMailbox(RemoteActorSystemMessage.Stop, None)
    }
  }

  protected[akka] def registerSupervisorAsRemoteActor: Option[Uuid] = None

  // ==== NOT SUPPORTED ====
  @deprecated("Will be removed without replacement, doesn't make any sense to have in the face of `become` and `unbecome`", "1.1")
  def actorClass: Class[_ <: Actor] = unsupported
  def dispatcher_=(md: MessageDispatcher): Unit = unsupported
  def dispatcher: MessageDispatcher = unsupported
  def link(actorRef: ActorRef): Unit = unsupported
  def unlink(actorRef: ActorRef): Unit = unsupported
  def startLink(actorRef: ActorRef): Unit = unsupported
  def spawn(clazz: Class[_ <: Actor]): ActorRef = unsupported
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef = unsupported
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef = unsupported
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef = unsupported
  def supervisor: Option[ActorRef] = unsupported
  def linkedActors: JMap[Uuid, ActorRef] = unsupported
  protected[akka] def mailbox: AnyRef = unsupported
  protected[akka] def mailbox_=(value: AnyRef): AnyRef = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = unsupported
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = unsupported
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = unsupported
  protected[akka] def invoke(messageHandle: MessageInvocation): Unit = unsupported
  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = unsupported
  protected[akka] def actorInstance: AtomicReference[Actor] = unsupported
  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
}

/**
 * This trait represents the common (external) methods for all ActorRefs
 * Needed because implicit conversions aren't applied when instance imports are used
 *
 * i.e.
 * var self: ScalaActorRef = ...
 * import self._
 * //can't call ActorRef methods here unless they are declared in a common
 * //superclass, which ActorRefShared is.
 */
trait ActorRefShared {
  /**
   * Returns the uuid for the actor.
   */
  def uuid: Uuid
}

/**
 * This trait represents the Scala Actor API
 * There are implicit conversions in ../actor/Implicits.scala
 * from ActorRef -> ScalaActorRef and back
 */
trait ScalaActorRef extends ActorRefShared with ForwardableChannel with ReplyChannel[Any] {
  ref: ActorRef ⇒

  /**
   * Identifier for actor, does not have to be a unique one. Default is the 'uuid'.
   * <p/>
   * This field is used for logging, AspectRegistry.actorsFor(id), identifier for remote
   * actor in RemoteServer etc.But also as the identifier for persistence, which means
   * that you can use a custom name to be able to retrieve the "correct" persisted state
   * upon restart, remote restart etc.
   */
  def id: String

  def id_=(id: String): Unit

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the life-cycle for a supervised actor.
   */
  @volatile
  @BeanProperty
  var lifeCycle: LifeCycle = UndefinedLifeCycle

  /**
   * User overridable callback/setting.
   * <p/>
   *  Don't forget to supply a List of exception types to intercept (trapExit)
   * <p/>
   * Can be one of:
   * <pre>
   *  faultHandler = AllForOneStrategy(trapExit = List(classOf[Exception]), maxNrOfRetries, withinTimeRange)
   * </pre>
   * Or:
   * <pre>
   *  faultHandler = OneForOneStrategy(trapExit = List(classOf[Exception]), maxNrOfRetries, withinTimeRange)
   * </pre>
   */
  @volatile
  @BeanProperty
  var faultHandler: FaultHandlingStrategy = NoFaultHandlingStrategy

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  def sender: Option[ActorRef] = {
    val msg = currentMessage
    if (msg eq null) None
    else msg.channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
  }

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '!!' or '!!!', else None.
   */
  def senderFuture(): Option[CompletableFuture[Any]] = {
    val msg = currentMessage
    if (msg eq null) None
    else msg.channel match {
      case f: ActorCompletableFuture ⇒ Some(f)
      case _                         ⇒ None
    }
  }

  /**
   * Sends a one-way asynchronous message. E.g. fire-and-forget semantics.
   * <p/>
   *
   * If invoked from within an actor then the actor reference is implicitly passed on as the implicit 'sender' argument.
   * <p/>
   *
   * This actor 'sender' reference is then available in the receiving actor in the 'sender' member variable,
   * if invoked from within an Actor. If not then no sender is available.
   * <pre>
   *   actor ! message
   * </pre>
   * <p/>
   */
  def !(message: Any)(implicit channel: UntypedChannel = NullChannel): Unit = {
    if (isRunning) postMessageToMailbox(message, channel)
    else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
  }

  /**
   * Sends a message asynchronously and waits on a future for a reply message.
   * <p/>
   * It waits on the reply either until it receives it (in the form of <code>Some(replyMessage)</code>)
   * or until the timeout expires (which will return None). E.g. send-and-receive-eventually semantics.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use '!' together with the 'sender' member field to
   * implement request/response message exchanges.
   * If you are sending messages using <code>!!</code> then you <b>have to</b> use <code>self.reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  @deprecated("use `(actor ? msg).as[T]` instead", "1.2")
  def !!(message: Any, timeout: Long = this.timeout)(implicit channel: UntypedChannel = NullChannel): Option[Any] = {
    if (isRunning) {
      val future = postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, channel)
      val isMessageJoinPoint = if (isTypedActorEnabled) TypedActorModule.resolveFutureIfMessageIsJoinPoint(message, future)
      else false
      try {
        future.await
      } catch {
        case e: FutureTimeoutException ⇒
          if (isMessageJoinPoint) {
            EventHandler.error(e, this, e.getMessage)
            throw e
          } else None
      }
      future.resultOrException
    } else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
  }

  /**
   * Sends a message asynchronously returns a future holding the eventual reply message.
   * <p/>
   * <b>NOTE:</b>
   * Use this method with care. In most cases it is better to use '!' together with the 'sender' member field to
   * implement request/response message exchanges.
   * If you are sending messages using <code>!!!</code> then you <b>have to</b> use <code>self.reply(..)</code>
   * to send a reply message to the original sender. If not then the sender will block until the timeout expires.
   */
  @deprecated("return type is an illusion, use the more honest ? method", "1.2")
  def !!![T](message: Any, timeout: Long = this.timeout)(implicit channel: UntypedChannel = NullChannel): Future[T] =
    this.?(message)(channel, Actor.Timeout(timeout)).asInstanceOf[Future[T]]

  /**
   * Sends a message asynchronously, returning a future which may eventually hold the reply.
   * Is pronounced: "ask"
   */
  def ?(message: Any)(implicit channel: UntypedChannel = NullChannel, timeout: Actor.Timeout = Actor.defaultTimeout): ActorCompletableFuture = {
    if (isRunning) postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout.duration.toMillis, channel)
    else throw new ActorInitializationException(
      "Actor has not been started, you need to invoke 'actor.start()' before using it")
  }

  /**
   * Forwards the message and passes the original sender actor as the sender.
   * <p/>
   * Works with '!', '!!' and '!!!'.
   */
  def forward(message: Any)(implicit channel: ForwardableChannel) = {
    if (isRunning) {
      postMessageToMailbox(message, channel.channel)
    } else throw new ActorInitializationException("Actor has not been started, you need to invoke 'actor.start()' before using it")
  }

  /**
   * Use <code>reply_?(..)</code> to reply with a message to the original sender of the message currently
   * being processed.
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   */
  @deprecated("Use tryReply(..)", "1.2")
  def reply_?(message: Any): Boolean = tryReply(message)

  /**
   * Atomically create (from actor class) and start an actor.
   */
  def spawn[T <: Actor: Manifest]: ActorRef =
    spawn(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Atomically create (from actor class), start and make an actor remote.
   */
  def spawnRemote[T <: Actor: Manifest](hostname: String, port: Int, timeout: Long): ActorRef = {
    ensureRemotingEnabled
    spawnRemote(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]], hostname, port, timeout)
  }

  /**
   * Atomically create (from actor class), start and link an actor.
   */
  def spawnLink[T <: Actor: Manifest]: ActorRef =
    spawnLink(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Atomically create (from actor class), start, link and make an actor remote.
   */
  def spawnLinkRemote[T <: Actor: Manifest](hostname: String, port: Int, timeout: Long): ActorRef = {
    ensureRemotingEnabled
    spawnLinkRemote(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]], hostname, port, timeout)
  }
}
