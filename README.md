# akka-backoff-supervisor-enhancement

This product is enhancement library to BackOffSupervisor of Akka.
I'm going to propose this enhancement method to akka team after this experiment.


## Usage

### How to send `ChildStarted`/`ChildStopped` message to EventSubscriber

The `ChildStarted`/`ChildStopped` message is sent by `BackOffSupervisor` when the child actor is started/stopped.

```scala
class EventListener extends Actor with ActorLogging {
  override def receive: Receive = {
    case ChildStarted(Some(ChildException(msg))) =>
      log.info(s"child actor was started: $ex")
      sender() ! msg // retry to send a message
    case ChildStopped =>
      log.info("child actor was stopped")
  }
}

val childProps = Props(classOf[EchoActor])
val stopBackOffOptions = Backoff.onStop(childProps, "c1", 100.millis, 3.seconds, 0.2)
  .withEventSubscriber(Some(eventListener))
val stopBackOffSupervisor = system.actorOf(BackoffSupervisor.props(stopBackOffOptions))
```

### How to register `onStartChildHandler`/`onStopChildHandler`

The handler that registered by `withOnStartChildHandler`/`withOnStopChildHandler` is called by `BackOffSupervisor` when the child actor is started/stopped.


```scala
val childProps = Props(classOf[EchoActor])
val stopBackOffOptions = Backoff.onStop(childProps, "c1", 100.millis, 3.seconds, 0.2)
    .withOnStartChildHandler { case (supervisorRef, exOpt) =>
        system.log.info(s"on start child: $supervisorRef, $exOpt")
        exOpt.foreach(supervisorRef ! _) // retry to send a message
}.withOnStopChildHandler { supervisorRef => 
  system.log.info(s"on stop child: $supervisorRef")
}
val stopBackOffSupervisor = system.actorOf(BackoffSupervisor.props(stopBackOffOptions))
```

### How to make Custom Supervisor with backoff

The Custom Supervisor with backoff is supported by `BackoffOnRestartSupervisor` or `BackoffSupervisor` if you want to use the `Actor`.

```scala
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

object Supervisor {

  def props(backoffOptions: BackoffOptions): Props =
    Props(new Supervisor(backoffOptions))

}

class Supervisor(backoffOptions: BackoffOptions)
  extends BackoffOnRestartSupervisor {

  override val minBackoff = backoffOptions.minBackoff
  override val maxBackoff = backoffOptions.maxBackoff
  override val randomFactor = backoffOptions.randomFactor
  override val reset = backoffOptions
    .reset.getOrElse(AutoReset(minBackoff))

  override def childProps: Props = ChildActor.props
  override def childName: String = "child"

  override def handleCommand: Receive = {
    case msg =>
      child.get forward msg
  }

  override def onStartChild(exOpt: Option[Throwable]): Unit =
    exOpt.foreach { case ChildException(msg) =>
      child.get ! msg // retry to send a message
    }

}

val backOffOptions = Backoff.custom(childProps, "c1", 100.millis, 3.seconds, 0.2)
val backOffSupervisor = system.actorOf(Supervisor.props(backOffOptions))
```
