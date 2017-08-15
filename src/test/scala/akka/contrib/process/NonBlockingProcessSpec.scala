/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.process

import java.io.File

import akka.actor._
import akka.pattern.ask
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.testkit.TestProbe
import akka.util.{ ByteString, Timeout }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, DurationInt }

class NonBlockingProcessSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  implicit val system = ActorSystem("test", testConfig)

  implicit val processCreationTimeout = Timeout(2.seconds)

  "A NonBlockingProcess" should {
    "read from stdin and write to stdout" in {
      val command = getClass.getResource("/echo.sh").getFile
      new File(command).setExecutable(true)

      val streamProbe = TestProbe()
      val exitProbe = TestProbe()
      val stdinInput = List("abcd", "1234", "quit")
      val receiver = system.actorOf(Props(new NonBlockingReceiver(streamProbe.ref, exitProbe.ref, command, stdinInput, 1)), "receiver1")
      val process = Await.result(receiver.ask(NonBlockingReceiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

      val partiallyReceived =
        streamProbe.expectMsgPF() {
          case NonBlockingReceiver.Out("abcd1234") =>
            false
          case NonBlockingReceiver.Out("abcd") =>
            true
        }

      if (partiallyReceived) {
        streamProbe.expectMsg(NonBlockingReceiver.Out("1234"))
      }

      exitProbe.expectMsgPF() {
        case NonBlockingProcess.Exited(x) => x
      } shouldEqual 0

      exitProbe.watch(process)
      exitProbe.expectTerminated(process)
    }

    "allow a blocking process that is blocked to be destroyed" in {
      expectDestruction(viaDestroy = true)
    }

    "allow a blocking process that is blocked to be stopped" in {
      expectDestruction(viaDestroy = false)
    }

    "be able to create the reference.conf specified limit of processes" in {
      val command = getClass.getResource("/echo.sh").getFile
      new File(command).setExecutable(true)

      val probesAndProcesses = for (seed <- 1 to 100) yield {
        val streamProbe = TestProbe()
        val exitProbe = TestProbe()
        val receiver = system.actorOf(Props(new NonBlockingReceiver(streamProbe.ref, exitProbe.ref, command, List.empty, seed)), "receiver-ref-" + seed)
        val process = Await.result(receiver.ask(NonBlockingReceiver.Process).mapTo[ActorRef], processCreationTimeout.duration)
        (streamProbe, exitProbe, process)
      }

      probesAndProcesses.foreach {
        case (_, exitProbe, process) =>
          process ! NonBlockingProcess.Destroy
          exitProbe.watch(process)
          exitProbe.expectMsgAnyClassOf(classOf[Terminated], classOf[NonBlockingProcess.Exited])
      }
    }

    "detect when a process has exited of its own accord" in {
      val command = getClass.getResource("/loop.sh").getFile
      new File(command).setExecutable(true)
      val nameSeed = scala.concurrent.forkjoin.ThreadLocalRandom.current().nextLong()
      val streamProbe = TestProbe()
      val exitProbe = TestProbe()
      val receiver = system.actorOf(Props(new NonBlockingReceiver(streamProbe.ref, exitProbe.ref, command, List.empty, nameSeed)), "receiver" + nameSeed)
      val process = Await.result(receiver.ask(NonBlockingReceiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

      exitProbe.watch(process)

      streamProbe.expectMsg(NonBlockingReceiver.Out("Starting"))

      process ! NonBlockingProcess.Destroy

      exitProbe.fishForMessage(5.seconds) {
        case NonBlockingProcess.Exited(r) => true
        case _                            => false
      }
    }
  }

  override protected def afterAll(): Unit = {
    Await.ready(system.terminate(), Duration.Inf)
  }

  def expectDestruction(viaDestroy: Boolean): Unit = {
    val command = getClass.getResource("/sleep.sh").getFile
    new File(command).setExecutable(true)
    val nameSeed = scala.concurrent.forkjoin.ThreadLocalRandom.current().nextLong()
    val streamProbe = TestProbe()
    val exitProbe = TestProbe()
    val receiver = system.actorOf(Props(new NonBlockingReceiver(streamProbe.ref, exitProbe.ref, command, List.empty, nameSeed)), "receiver" + nameSeed)
    val process = Await.result(receiver.ask(NonBlockingReceiver.Process).mapTo[ActorRef], processCreationTimeout.duration)

    streamProbe.expectMsg(NonBlockingReceiver.Out("Starting"))

    if (viaDestroy)
      process ! NonBlockingProcess.Destroy
    else
      system.stop(process)

    exitProbe.expectMsgPF(10.seconds) {
      case NonBlockingProcess.Exited(v) => v
    } should not be 0

    exitProbe.watch(process)
    exitProbe.expectTerminated(process, 10.seconds)
  }
}

object NonBlockingReceiver {
  case object Process
  case class Out(s: String)
  case class Err(s: String)
}

class NonBlockingReceiver(streamProbe: ActorRef, exitProbe: ActorRef, command: String, stdinInput: immutable.Seq[String], nameSeed: Long) extends Actor
    with Stash {

  final implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  val process = context.actorOf(NonBlockingProcess.props(List(command)), "process" + nameSeed)
  import NonBlockingReceiver._
  import context.dispatcher

  override def receive: Receive = {
    case Process =>
      sender() ! process

    case NonBlockingProcess.Started(_, stdin, stdout, stderr) =>
      stdout
        .map(element => Out(element.utf8String))
        .merge(stderr.map(element => Err(element.utf8String)))
        .runWith(Sink.foreach(streamProbe.tell(_, Actor.noSender)))
        .onComplete(_ => self ! "flow-complete")

      Source(stdinInput).map(ByteString.apply).runWith(stdin)
    case "flow-complete" =>
      unstashAll()
      context become {
        case exited: NonBlockingProcess.Exited => exitProbe ! exited
      }
    case _ =>
      stash()
  }
}
