package com.github.j5ik2o.akka.backoff.enhancement

import java.util.Optional
import java.util.concurrent.ThreadLocalRandom

import akka.actor.SupervisorStrategy.{ Directive, Escalate }
import akka.actor.{ Actor, ActorRef, DeadLetterSuppression, OneForOneStrategy, Props, SupervisorStrategy, Terminated }

import scala.concurrent.duration.{ Duration, FiniteDuration }

object BackoffSupervisor {

  /**
   * Props for creating a [[BackoffSupervisor]] actor.
   *
   * Exceptions in the child are handled with the default supervision strategy, i.e.
   * most exceptions will immediately restart the child. You can define another
   * supervision strategy by using [[#propsWithSupervisorStrategy]].
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
  def props(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double
  ): Props = {
    propsWithSupervisorStrategy(childProps, childName, minBackoff, maxBackoff, randomFactor, SupervisorStrategy.defaultStrategy)
  }

  /**
   * Props for creating a [[BackoffSupervisor]] actor with a custom
   * supervision strategy.
   *
   * Exceptions in the child are handled with the given `supervisionStrategy`. A
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
   * @param strategy     the supervision strategy to use for handling exceptions
   *                     in the child
   */
  def propsWithSupervisorStrategy(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    strategy: SupervisorStrategy
  ): Props = {
    require(minBackoff > Duration.Zero, "minBackoff must be > 0")
    require(maxBackoff >= minBackoff, "maxBackoff must be >= minBackoff")
    require(0.0 <= randomFactor && randomFactor <= 1.0, "randomFactor must be between 0.0 and 1.0")
    Props(new BackoffSupervisorImpl(childProps, childName, minBackoff, maxBackoff, randomFactor, strategy))
  }

  /**
   * Props for creating a [[BackoffSupervisor]] actor from [[BackoffOptions]].
   *
   * @param options the [[BackoffOptions]] that specify how to construct a backoff-supervisor.
   */
  def props(options: BackoffOptions): Props = options.props

  /**
   * Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.CurrentChild]] containing the `ActorRef` of the current child, if any.
   */
  final case object GetCurrentChild

  /**
   * Java API: Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.CurrentChild]] containing the `ActorRef` of the current child, if any.
   */
  def getCurrentChild: GetCurrentChild.type = GetCurrentChild

  /**
   * Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.CurrentChild]] containing the `ActorRef` of the current child, if any.
   */
  final case class CurrentChild(ref: Option[ActorRef]) {
    /**
     * Java API: The `ActorRef` of the current child, if any
     */
    def getRef: Optional[ActorRef] = Optional.ofNullable(ref.orNull)
  }

  /**
   * Send this message to the [[BackoffSupervisor]] and it will reset the back-off.
   * This should be used in conjunction with `withManualReset` in [[BackoffOptions]].
   */
  final case object Reset

  /**
   * Java API: Send this message to the [[BackoffSupervisor]] and it will reset the back-off.
   * This should be used in conjunction with `withManualReset` in [[BackoffOptions]].
   */
  def reset: Reset.type = Reset

  /**
   * Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.RestartCount]] containing the current restart count.
   */
  final case object GetRestartCount

  /**
   * Java API: Send this message to the [[BackoffSupervisor]] and it will reply with
   * [[BackoffSupervisor.RestartCount]] containing the current restart count.
   */
  def getRestartCount: GetRestartCount.type = GetRestartCount

  final case class RestartCount(count: Int)

  final case class StartChild(ex: Option[Throwable] = None) extends DeadLetterSuppression

  // not final for binary compatibility with 2.4.1
  case class ResetRestartCount(current: Int) extends DeadLetterSuppression

  /**
   * INTERNAL API
   *
   * Calculates an exponential back off delay.
   */
  def calculateDelay(
    restartCount: Int,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double
  ): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (restartCount >= 30) // Duration overflow protection (> 100 years)
      maxBackoff
    else
      maxBackoff.min(minBackoff * math.pow(2, restartCount.toDouble)) * rnd match {
        case f: FiniteDuration ⇒ f
        case _ ⇒ maxBackoff
      }
  }
}

class BackoffSupervisorImpl(
    val childProps: Props,
    val childName: String,
    val minBackoff: FiniteDuration,
    val maxBackoff: FiniteDuration,
    val reset: BackoffReset,
    val randomFactor: Double,
    val onStartChildHandler: (ActorRef, Option[Throwable]) => Unit,
    val onStopChildHandler: ActorRef => Unit,
    strategy: SupervisorStrategy
) extends BackoffSupervisor {

  // for binary compatibility with 2.4.1
  def this(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double,
    supervisorStrategy: SupervisorStrategy
  ) =
    this(childProps, childName, minBackoff, maxBackoff, AutoReset(minBackoff), randomFactor, (_, _) => (), _ => (), supervisorStrategy)

  // for binary compatibility with 2.4.0
  def this(
    childProps: Props,
    childName: String,
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double
  ) =
    this(childProps, childName, minBackoff, maxBackoff, randomFactor, SupervisorStrategy.defaultStrategy)

  // to keep binary compatibility with 2.4.1
  override val supervisorStrategy = strategy match {
    case oneForOne: OneForOneStrategy ⇒
      OneForOneStrategy(oneForOne.maxNrOfRetries, oneForOne.withinTimeRange, oneForOne.loggingEnabled) {
        case ex ⇒
          val defaultDirective: Directive =
            super.supervisorStrategy.decider.applyOrElse(ex, (_: Any) ⇒ Escalate)
          strategy.decider.applyOrElse(ex, (_: Any) ⇒ defaultDirective)
      }
    case s ⇒ s
  }

  override def handleBackoff: Receive = super.handleBackoff orElse proxy

  override def handleCommand: Receive = PartialFunction.empty

  override def onStartChild(ex: Option[Throwable]): Unit = onStartChildHandler(self, ex)

  override def onStopChild(): Unit = onStopChildHandler(self)
}

/**
 * Back-off supervisor that stops and starts a child actor using a back-off algorithm when the child actor stops.
 * This back-off supervisor is created by using `akka.pattern.BackoffSupervisor.props`
 * with `Backoff.onStop`.
 */
trait BackoffSupervisor
    extends Actor with HandleBackoff {

  val minBackoff: FiniteDuration
  val maxBackoff: FiniteDuration
  val randomFactor: Double

  import BackoffSupervisor._
  import context.dispatcher

  def onTerminated: Receive = {
    case Terminated(ref) if child.contains(ref) ⇒
      log.debug("received Terminated({})", ref)
      child = None
      onStopChild()
      val restartDelay = calculateDelay(restartCount, minBackoff, maxBackoff, randomFactor)
      context.system.scheduler.scheduleOnce(restartDelay, self, StartChild())
      restartCount += 1
  }

  def handleCommand: Receive

  def receive: Receive = handleCommand orElse onTerminated orElse handleBackoff

}

