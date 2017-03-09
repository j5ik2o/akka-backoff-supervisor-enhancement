package com.github.j5ik2o.akka.backoff.enhancement

import akka.actor.{SupervisorStrategy, _}
import akka.testkit._
import BackoffSupervisor.RestartCount
import org.scalatest.FunSpecLike

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object BackoffSupervisorSpec {

  class TestException extends RuntimeException with NoStackTrace

  object Child {
    def props(probe: ActorRef): Props =
      Props(new Child(probe))
  }

  class Child(probe: ActorRef) extends Actor {
    def receive: PartialFunction[Any, Unit] = {
      case "boom" ⇒ throw new TestException
      case msg    ⇒ probe ! msg
    }
  }

  object ManualChild {
    def props(probe: ActorRef): Props =
      Props(new ManualChild(probe))
  }

  class ManualChild(probe: ActorRef) extends Actor {
    def receive: PartialFunction[Any, Unit] = {
      case "boom" ⇒ throw new TestException
      case msg ⇒
        probe ! msg
        context.parent ! BackoffSupervisor.Reset
    }
  }
}

class BackoffSupervisorSpec
  extends TestKit(ActorSystem("BackoffSupervisorSpec"))
  with FunSpecLike with ImplicitSender {
  import BackoffSupervisorSpec._

  def onStopOptions(props: Props = Child.props(testActor)): BackoffOptions = Backoff.onStop(props, "c1", 100.millis, 3.seconds, 0.2)
  def onFailureOptions(props: Props = Child.props(testActor)): BackoffOptions = Backoff.onFailure(props, "c1", 100.millis, 3.seconds, 0.2)
  def create(options: BackoffOptions): ActorRef = system.actorOf(BackoffSupervisor.props(options))

  describe("BackoffSupervisor") {
    it("start child again when it stops when using `Backoff.onStop`") {
      val supervisor = create(onStopOptions())
      supervisor ! BackoffSupervisor.GetCurrentChild
      val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
      watch(c1)
      c1 ! PoisonPill
      expectTerminated(c1)
      awaitAssert {
        supervisor ! BackoffSupervisor.GetCurrentChild
        // new instance
        assert(expectMsgType[BackoffSupervisor.CurrentChild].ref.get !== c1)
      }
    }

    it("forward messages to the child") {
      def assertForward(supervisor: ActorRef): String = {
        supervisor ! "hello"
        expectMsg("hello")
      }
      assertForward(create(onStopOptions()))
      assertForward(create(onFailureOptions()))
    }

    it("support custom supervision strategy") {
      def assertCustomStrategy(supervisor: ActorRef): Unit = {
        supervisor ! BackoffSupervisor.GetCurrentChild
        val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
        watch(c1)
        c1 ! "boom"
        expectTerminated(c1)
        awaitAssert {
          supervisor ! BackoffSupervisor.GetCurrentChild
          // new instance
          assert(expectMsgType[BackoffSupervisor.CurrentChild].ref.get !== c1)
        }
      }
      filterException[TestException] {
        val stoppingStrategy = OneForOneStrategy() {
          case _: TestException ⇒ SupervisorStrategy.Stop
        }
        val restartingStrategy = OneForOneStrategy() {
          case _: TestException ⇒ SupervisorStrategy.Restart
        }

        assertCustomStrategy(
          create(onStopOptions()
            .withSupervisorStrategy(stoppingStrategy))
        )

        assertCustomStrategy(
          create(onFailureOptions()
            .withSupervisorStrategy(restartingStrategy))
        )
      }
    }

    it("support default stopping strategy when using `Backoff.onStop`") {
      filterException[TestException] {
        val supervisor = create(onStopOptions().withDefaultStoppingStrategy)
        supervisor ! BackoffSupervisor.GetCurrentChild
        val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
        watch(c1)
        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(0))

        c1 ! "boom"
        expectTerminated(c1)
        awaitAssert {
          supervisor ! BackoffSupervisor.GetCurrentChild
          // new instance
          assert(expectMsgType[BackoffSupervisor.CurrentChild].ref.get !== c1)
        }
        supervisor ! BackoffSupervisor.GetRestartCount
        expectMsg(BackoffSupervisor.RestartCount(1))

      }
    }

    it("support manual reset") {
      filterException[TestException] {
        def assertManualReset(supervisor: ActorRef): RestartCount = {
          supervisor ! BackoffSupervisor.GetCurrentChild
          val c1 = expectMsgType[BackoffSupervisor.CurrentChild].ref.get
          watch(c1)
          c1 ! "boom"
          expectTerminated(c1)

          awaitAssert {
            supervisor ! BackoffSupervisor.GetRestartCount
            expectMsg(BackoffSupervisor.RestartCount(1))
          }

          awaitAssert {
            supervisor ! BackoffSupervisor.GetCurrentChild
            // new instance
            assert(expectMsgType[BackoffSupervisor.CurrentChild].ref.get !== c1)
          }

          supervisor ! "hello"
          expectMsg("hello")

          // making sure the Reset is handled by supervisor
          supervisor ! "hello"
          expectMsg("hello")

          supervisor ! BackoffSupervisor.GetRestartCount
          expectMsg(BackoffSupervisor.RestartCount(0))
        }

        val stoppingStrategy = OneForOneStrategy() {
          case _: TestException ⇒ SupervisorStrategy.Stop
        }
        val restartingStrategy = OneForOneStrategy() {
          case _: TestException ⇒ SupervisorStrategy.Restart
        }

        assertManualReset(
          create(onStopOptions(ManualChild.props(testActor))
            .withManualReset
            .withSupervisorStrategy(stoppingStrategy))
        )

        assertManualReset(
          create(onFailureOptions(ManualChild.props(testActor))
            .withManualReset
            .withSupervisorStrategy(restartingStrategy))
        )
      }
    }
  }
}
