package com.github.j5ik2o.akka.backoff.enhancement

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }

trait HandleBackoff extends ActorLogging {
  this: Actor ⇒

  import context.dispatcher

  def childProps: Props

  def childName: String

  def reset: BackoffReset

  var child: Option[ActorRef] = None

  var restartCount = 0

  import BackoffSupervisor._

  override def preStart(): Unit = startChild(None)

  def startChild(ex: Option[Throwable]): Unit =
    if (child.isEmpty) {
      child = Some(context.watch(context.actorOf(childProps, childName)))
      onStartChild(ex)
    }

  def onStartChild(ex: Option[Throwable]): Unit = {}

  def onStopChild(): Unit = {}

  def handleBackoff: Receive = {
    case StartChild(ex) ⇒
      startChild(ex)
      reset match {
        case AutoReset(resetBackoff) ⇒
          val _ = context.system.scheduler.scheduleOnce(resetBackoff, self, ResetRestartCount(restartCount))
        case _ ⇒ // ignore
      }
    case Reset ⇒
      reset match {
        case ManualReset ⇒ restartCount = 0
        case msg ⇒ unhandled(msg)
      }

    case ResetRestartCount(current) ⇒
      if (current == restartCount)
        restartCount = 0

    case GetRestartCount ⇒
      sender() ! RestartCount(restartCount)

    case GetCurrentChild ⇒
      sender() ! CurrentChild(child)

  }

  def reply: Receive = {
    case msg if child.contains(sender()) ⇒
      // use the BackoffSupervisor as sender
      context.parent ! msg
  }

  def forward: Receive = {
    case msg ⇒ child match {
      case Some(c) ⇒ c.forward(msg)
      case None ⇒ context.system.deadLetters.forward(msg)
    }
  }

  def proxy: Receive = reply orElse forward

}
