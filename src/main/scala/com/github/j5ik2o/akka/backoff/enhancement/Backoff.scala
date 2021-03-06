package com.github.j5ik2o.akka.backoff.enhancement

import akka.actor.{ ActorRef, OneForOneStrategy, Props, SupervisorStrategy }

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Builds back-off options for creating a back-off supervisor.
 * You can pass `BackoffOptions` to `akka.pattern.BackoffSupervisor.props`.
 * An example of creating back-off options:
 * {{{
 * Backoff.onFailure(childProps, childName, minBackoff, maxBackoff, randomFactor)
 *               .withManualReset
 *               .withSupervisorStrategy(
 *                 OneforOneStrategy(){
 *                    case e: GivingUpException => Stop
 *                    case e: RetryableException => Restart
 *                 }
 *               )
 *
 * }}}
 */
object Backoff {
  /**
   * Back-off options for creating a back-off supervisor actor that expects a child actor to restart on failure.
   *
   * This explicit supervisor behaves similarly to the normal implicit supervision where
   * if an actor throws an exception, the decider on the supervisor will decide when to
   * `Stop`, `Restart`, `Escalate`, `Resume` the child actor.
   *
   * When the `Restart` directive is specified, the supervisor will delay the restart
   * using an exponential back off strategy (bounded by minBackoff and maxBackoff).
   *
   * This supervisor is intended to be transparent to both the child actor and external actors.
   * Where external actors can send messages to the supervisor as if it was the child and the
   * messages will be forwarded. And when the child is `Terminated`, the supervisor is also
   * `Terminated`.
   * Transparent to the child means that the child does not have to be aware that it is being
   * supervised specifically by this actor. Just like it does
   * not need to know when it is being supervised by the usual implicit supervisors.
   * The only caveat is that the `ActorRef` of the child is not stable, so any user storing the
   * `sender()` `ActorRef` from the child response may eventually not be able to communicate with
   * the stored `ActorRef`. In general all messages to the child should be directed through this actor.
   *
   * An example of where this supervisor might be used is when you may have an actor that is
   * responsible for continuously polling on a server for some resource that sometimes may be down.
   * Instead of hammering the server continuously when the resource is unavailable, the actor will
   * be restarted with an exponentially increasing back off until the resource is available again.
   *
   * '''***
   * This supervisor should not be used with `Akka Persistence` child actors.
   * `Akka Persistence` actors shutdown unconditionally on `persistFailure()`s rather
   * than throw an exception on a failure like normal actors.
   * [[#onStop]] should be used instead for cases where the child actor
   * terminates itself as a failure signal instead of the normal behavior of throwing an exception.
   * ***'''
   * You can define another
   * supervision strategy by using `akka.pattern.BackoffOptions.withSupervisorStrategy` on `akka.pattern.BackoffOptions`.
   *
   * @param childProps   the `akka.actor.Props` of the child actor that
   *                     will be started and supervised
   * @param childName    name of the child actor
   * @param minBackoff   minimum (initial) duration until the child actor will
   *                     started again, if it is terminated
   * @param maxBackoff   the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *                     random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *                     In order to skip this additional delay pass in `0`.
   */
  def onFailure(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double
  ): BackoffOptions =
    BackoffOptionsImpl(
      RestartImpliesFailure,
      childProps,
      childName,
      minBackoff,
      maxBackoff,
      randomFactor,
      replyWhileStopped = None
    )

  /**
   * Back-off options for creating a back-off supervisor actor that expects a child actor to stop on failure.
   *
   * This actor can be used to supervise a child actor and start it again
   * after a back-off duration if the child actor is stopped.
   *
   * This is useful in situations where the re-start of the child actor should be
   * delayed e.g. in order to give an external resource time to recover before the
   * child actor tries contacting it again (after being restarted).
   *
   * Specifically this pattern is useful for persistent actors,
   * which are stopped in case of persistence failures.
   * Just restarting them immediately would probably fail again (since the data
   * store is probably unavailable). It is better to try again after a delay.
   *
   * It supports exponential back-off between the given `minBackoff` and
   * `maxBackoff` durations. For example, if `minBackoff` is 3 seconds and
   * `maxBackoff` 30 seconds the start attempts will be delayed with
   * 3, 6, 12, 24, 30, 30 seconds. The exponential back-off counter is reset
   * if the actor is not terminated within the `minBackoff` duration.
   *
   * In addition to the calculated exponential back-off an additional
   * random delay based the given `randomFactor` is added, e.g. 0.2 adds up to 20%
   * delay. The reason for adding a random delay is to avoid that all failing
   * actors hit the backend resource at the same time.
   *
   * You can retrieve the current child `ActorRef` by sending `BackoffSupervisor.GetCurrentChild`
   * message to this actor and it will reply with `akka.pattern.BackoffSupervisor.CurrentChild`
   * containing the `ActorRef` of the current child, if any.
   *
   * The `BackoffSupervisor`delegates all messages from the child to the parent of the
   * `BackoffSupervisor`, with the supervisor as sender.
   *
   * The `BackoffSupervisor` forwards all other messages to the child, if it is currently running.
   *
   * The child can stop itself and send a `akka.actor.PoisonPill` to the parent supervisor
   * if it wants to do an intentional stop.
   *
   * Exceptions in the child are handled with the default supervisionStrategy, which can be changed by using
   * [[BackoffOptions#withSupervisorStrategy]] or [[BackoffOptions#withDefaultStoppingStrategy]]. A
   * `Restart` will perform a normal immediate restart of the child. A `Stop` will
   * stop the child, but it will be started again after the back-off duration.
   *
   * @param childProps   the `akka.actor.Props` of the child actor that
   *                     will be started and supervised
   * @param childName    name of the child actor
   * @param minBackoff   minimum (initial) duration until the child actor will
   *                     started again, if it is terminated
   * @param maxBackoff   the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *                     random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *                     In order to skip this additional delay pass in `0`.
   */
  def onStop(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double
  ): BackoffOptions =
    BackoffOptionsImpl(
      StopImpliesFailure,
      childProps,
      childName,
      minBackoff,
      maxBackoff,
      randomFactor,
      replyWhileStopped = None
    )
}

/**
 * Configures a back-off supervisor actor. Start with `Backoff.onStop` or `Backoff.onFailure`.
 * BackoffOptions is immutable, so be sure to chain methods like:
 * {{{
 * val options = Backoff.onFailure(childProps, childName, minBackoff, maxBackoff, randomFactor)
 *               .withManualReset
 * context.actorOf(BackoffSupervisor.props(options), name)
 * }}}
 */
trait BackoffOptions {
  val backoffType: BackoffType
  val childProps: Props
  val childName: String
  val minBackoff: FiniteDuration
  val maxBackoff: FiniteDuration
  val randomFactor: Double
  val reset: Option[BackoffReset]
  val eventSubscriber: Option[ActorRef]
  val onStartChildHandler: (ActorRef, Option[Throwable]) => Unit
  val onStopChildHandler: ActorRef => Unit
  val supervisorStrategy: OneForOneStrategy
  val replyWhileStopped: Option[Any]
  /**
   * Returns a new BackoffOptions with automatic back-off reset.
   * The back-off algorithm is reset if the child does not crash within the specified `resetBackoff`.
   *
   * @param resetBackoff The back-off is reset if the child does not crash within this duration.
   */
  def withAutoReset(resetBackoff: FiniteDuration): BackoffOptions

  /**
   * Returns a new BackoffOptions with manual back-off reset. The back-off is only reset
   * if the child sends a `BackoffSupervisor.Reset` to its parent (the backoff-supervisor actor).
   */
  def withManualReset: BackoffOptions

  /**
   * Returns a new BackoffOptions with the supervisorStrategy.
   *
   * @param supervisorStrategy the supervisorStrategy that the back-off supervisor will use.
   *                           The default supervisor strategy is used as fallback if the specified supervisorStrategy (its decider)
   *                           does not explicitly handle an exception.
   */
  def withSupervisorStrategy(supervisorStrategy: OneForOneStrategy): BackoffOptions

  /**
   * Returns a new BackoffOptions with a default `SupervisorStrategy.stoppingStrategy`.
   * The default supervisor strategy is used as fallback for throwables not handled by `SupervisorStrategy.stoppingStrategy`.
   */
  def withDefaultStoppingStrategy: BackoffOptions

  def withEventSubscriber(subscriber: Option[ActorRef]): BackoffOptions

  def withOnStartChildHandler(onStartChildHandler: (ActorRef, Option[Throwable]) => Unit): BackoffOptions

  def withOnStopChildHandler(onStopChildHandler: ActorRef => Unit): BackoffOptions

  def withReplyWhileStopped(value: Option[Any]): BackoffOptions

  /**
   * Returns the props to create the back-off supervisor.
   */
  private[backoff] def props: Props
}

object BackoffOptions {

  def custom(
    backoffType: BackoffType = RestartImpliesFailure,
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    reset: Option[BackoffReset] = None,
    supervisorStrategy: OneForOneStrategy,
    replyWhileStopped: Option[Any]
  ): BackoffOptions = {
    BackoffOptionsImpl(
      backoffType: BackoffType,
      childProps,
      childName,
      minBackoff,
      maxBackoff,
      randomFactor,
      reset,
      None,
      (_, _) => (),
      _ => (),
      supervisorStrategy,
      replyWhileStopped
    )
  }

}

private final case class BackoffOptionsImpl(
    backoffType: BackoffType = RestartImpliesFailure,
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    reset: Option[BackoffReset] = None,
    eventSubscriber: Option[ActorRef] = None,
    onStartChildHandler: (ActorRef, Option[Throwable]) => Unit = (_, _) => (),
    onStopChildHandler: ActorRef => Unit = _ => (),
    supervisorStrategy: OneForOneStrategy = OneForOneStrategy()(SupervisorStrategy.defaultStrategy.decider),
    replyWhileStopped: Option[Any]
) extends BackoffOptions {

  val backoffReset: BackoffReset = reset.getOrElse(AutoReset(minBackoff))

  override def withAutoReset(resetBackoff: FiniteDuration): BackoffOptionsImpl = copy(reset = Some(AutoReset(resetBackoff)))

  override def withManualReset: BackoffOptionsImpl = copy(reset = Some(ManualReset))

  override def withSupervisorStrategy(supervisorStrategy: OneForOneStrategy): BackoffOptionsImpl = copy(supervisorStrategy = supervisorStrategy)

  override def withDefaultStoppingStrategy: BackoffOptionsImpl = copy(supervisorStrategy = OneForOneStrategy()(SupervisorStrategy.stoppingStrategy.decider))

  override def withEventSubscriber(subscriber: Option[ActorRef]): BackoffOptions =
    copy(eventSubscriber = subscriber)

  override def withOnStartChildHandler(onStartChildHandler: (ActorRef, Option[Throwable]) => Unit): BackoffOptions =
    copy(onStartChildHandler = onStartChildHandler)

  override def withOnStopChildHandler(onStopChildHandler: ActorRef => Unit): BackoffOptions =
    copy(onStopChildHandler = onStopChildHandler)

  override def withReplyWhileStopped(value: Option[Any]): BackoffOptions = copy(replyWhileStopped = value)

  override def props: Props = {
    require(minBackoff > Duration.Zero, "minBackoff must be > 0")
    require(maxBackoff >= minBackoff, "maxBackoff must be >= minBackoff")
    require(0.0 <= randomFactor && randomFactor <= 1.0, "randomFactor must be between 0.0 and 1.0")
    backoffReset match {
      case AutoReset(resetBackoff) ⇒
        require(minBackoff <= resetBackoff && resetBackoff <= maxBackoff)
      case _ ⇒ // ignore
    }

    backoffType match {
      case RestartImpliesFailure ⇒
        Props(new BackoffOnRestartSupervisorImpl(childProps, childName, minBackoff, maxBackoff, backoffReset, randomFactor,
          eventSubscriber, onStartChildHandler, onStopChildHandler, supervisorStrategy, replyWhileStopped))
      case StopImpliesFailure ⇒
        Props(new BackoffSupervisorImpl(childProps, childName, minBackoff, maxBackoff, backoffReset, randomFactor,
          eventSubscriber, onStartChildHandler, onStopChildHandler, supervisorStrategy, replyWhileStopped))
      case CustomImpliesFailure =>
        throw new IllegalArgumentException("The backoffType is a wrong parameter")
    }
  }

}

sealed trait BackoffType

final case object StopImpliesFailure extends BackoffType

final case object RestartImpliesFailure extends BackoffType

final case object CustomImpliesFailure extends BackoffType

sealed trait BackoffReset

final case object ManualReset extends BackoffReset

final case class AutoReset(resetBackoff: FiniteDuration) extends BackoffReset
