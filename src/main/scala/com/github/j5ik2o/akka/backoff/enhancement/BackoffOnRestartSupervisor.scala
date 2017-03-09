package com.github.j5ik2o.akka.backoff.enhancement

import akka.actor.SupervisorStrategy._
import akka.actor.{ OneForOneStrategy, _ }

import scala.concurrent.duration._

class BackoffOnRestartSupervisorImpl(
  val childProps: Props,
  val childName: String,
  val minBackoff: FiniteDuration,
  val maxBackoff: FiniteDuration,
  val reset: BackoffReset,
  val randomFactor: Double,
  val strategy: OneForOneStrategy
)
    extends BackoffOnRestartSupervisor {

  import context._

  override val supervisorStrategy = OneForOneStrategy(strategy.maxNrOfRetries, strategy.withinTimeRange, strategy.loggingEnabled) {
    case ex ⇒
      val defaultDirective: Directive =
        super.supervisorStrategy.decider.applyOrElse(ex, (_: Any) ⇒ Escalate)
      strategy.decider.applyOrElse(ex, (_: Any) ⇒ defaultDirective) match {
        case Restart ⇒
          val childRef = sender()
          become(waitChildTerminatedBeforeBackoff(childRef, ex) orElse handleBackoff)
          Stop
        case other ⇒ other
      }
  }

  override def handleBackoff: Receive = super.handleBackoff orElse proxy

  override def handleCommand: Receive = PartialFunction.empty

}

trait BackoffOnRestartSupervisor
    extends Actor with HandleBackoff {

  val minBackoff: FiniteDuration
  val maxBackoff: FiniteDuration
  val randomFactor: Double

  import BackoffSupervisor._
  import context._

  val defaultDecider: SupervisorStrategy.Decider = {
    case ex ⇒
      become(backingOff(sender(), ex))
      Stop
  }

  protected def waitChildTerminatedBeforeBackoff(childRef: ActorRef, ex: Throwable): Receive = {
    case Terminated(`childRef`) ⇒
      log.debug("received Terminated({})", `childRef`)
      become(receive)
      child = None
      onStopChild()
      val restartDelay = BackoffSupervisor.calculateDelay(restartCount, minBackoff, maxBackoff, randomFactor)
      context.system.scheduler.scheduleOnce(restartDelay, self, BackoffSupervisor.StartChild(Some(ex)))
      restartCount += 1
    case StartChild ⇒ // Ignore it, we will schedule a new one once current child terminated.
  }

  protected def onTerminated: Receive = {
    case Terminated(child) ⇒
      log.debug(s"Terminating, because child [$child] terminated itself")
      stop(self)
  }

  def onStartChild(ex: Option[Throwable]): Unit = {}

  def onStopChild(): Unit = {}

  def handleCommand: Receive

  def backingOff(childRef: ActorRef, ex: Throwable): Receive =
    waitChildTerminatedBeforeBackoff(childRef, ex) orElse handleBackoff

  override def receive: Receive = onTerminated orElse handleBackoff orElse handleCommand

}
