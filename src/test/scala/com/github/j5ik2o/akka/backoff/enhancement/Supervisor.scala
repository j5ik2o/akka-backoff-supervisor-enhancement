package com.github.j5ik2o.akka.backoff.enhancement

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor.{Actor, OneForOneStrategy, Props, SupervisorStrategy}

import scala.concurrent.duration.FiniteDuration

case class ChildException(errorMessage: Any)
  extends Exception()

object ChildActor {

  def props = Props[ChildActor]

}

class ChildActor extends Actor {

  override def receive: Receive = {
    case msg =>
      throw ChildException(msg)
  }

}

class Supervisor(backoffOptions: BackoffOptions)
  extends BackoffOnRestartSupervisor {

  override val minBackoff: FiniteDuration = backoffOptions.minBackoff
  override val maxBackoff: FiniteDuration = backoffOptions.maxBackoff
  override val randomFactor: Double = backoffOptions.randomFactor
  override val reset: BackoffReset = backoffOptions.reset.getOrElse(AutoReset(minBackoff))

  override def childProps: Props = ChildActor.props
  override def childName: String = "child"

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case ex =>
      context.become(backingOff(sender(), ex))
      Stop
  }

  override def handleCommand: Receive = {
    case msg =>
      child.get forward msg
  }

  override def onStartChild(exOpt: Option[Throwable]): Unit = {
    exOpt.foreach { case ChildException(msg) =>
      child.get ! msg
    }
  }

}
