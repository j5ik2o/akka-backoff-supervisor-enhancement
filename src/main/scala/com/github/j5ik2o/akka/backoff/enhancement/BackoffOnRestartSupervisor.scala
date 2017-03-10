package com.github.j5ik2o.akka.backoff.enhancement

import akka.actor.SupervisorStrategy._
import akka.actor.{ OneForOneStrategy, _ }
import com.github.j5ik2o.akka.backoff.enhancement.BackoffSupervisor.{ ChildStarted, ChildStopped }

import scala.concurrent.duration._

class BackoffOnRestartSupervisorImpl(
  val childProps: Props,
  val childName: String,
  val minBackoff: FiniteDuration,
  val maxBackoff: FiniteDuration,
  val reset: BackoffReset,
  val randomFactor: Double,
  val eventSubscriber: Option[ActorRef],
  val onStartChildHandler: (ActorRef, Option[Throwable]) => Unit,
  val onStopChildHandler: ActorRef => Unit,
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
          become(backingOff(childRef, ex))
          Stop
        case other ⇒ other
      }
  }

  override def handleBackoff: Receive = super.handleBackoff orElse proxy

  override def handleCommand: Receive = PartialFunction.empty

  override def onStartChild(ex: Option[Throwable]): Unit = {
    eventSubscriber.fold(onStartChildHandler(self, ex)) { subscriber =>
      subscriber ! ChildStarted(ex)
    }
  }

  override def onStopChild(): Unit = {
    eventSubscriber.fold(onStopChildHandler(self)) { subscriber =>
      subscriber ! ChildStopped
    }
  }
}

trait BackoffOnRestartSupervisor
    extends Actor with HandleBackoff {

  val minBackoff: FiniteDuration
  val maxBackoff: FiniteDuration
  val randomFactor: Double

  import BackoffSupervisor._
  import context._

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

  def handleCommand: Receive

  def backingOff(childRef: ActorRef, ex: Throwable): Receive =
    waitChildTerminatedBeforeBackoff(childRef, ex) orElse handleBackoff

  override def receive: Receive = onTerminated orElse handleBackoff orElse handleCommand

}
