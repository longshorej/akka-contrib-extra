/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.contrib.process

import akka.actor.{ Actor, ActorLogging, NoSerializationVerificationNeeded, PoisonPill, Props }
import akka.stream.scaladsl.{ BroadcastHub, Keep, Sink, Source }
import akka.util.ByteString
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

import akka.stream.stage.{ AsyncCallback, GraphStageLogic, GraphStageWithMaterializedValue, OutHandler }
import akka.stream._
import akka.{ Done, NotUsed }
import com.zaxxer.nuprocess.{ NuAbstractProcessHandler, NuProcess, NuProcessBuilder }

import scala.collection.JavaConverters
import scala.collection.immutable
import scala.concurrent.{ Future, blocking }

object NonBlockingProcess {

  /**
   * Sent to the receiver on startup - specifies the streams used for managing input, output and error respectively.
   * This message should only be received by the parent of the NonBlockingProcess and should not be passed across the
   * JVM boundary (the publishers are not serializable).
   *
   * @param pid the process id
   * @param stdin a `akka.stream.scaladsl.Sink[ByteString]` for the standard input stream of the process
   * @param stdout a `akka.stream.scaladsl.Source[ByteString]` for the standard output stream of the process
   * @param stderr a `akka.stream.scaladsl.Source[ByteString]` for the standard error stream of the process
   */
  case class Started(
    pid: Long,
    stdin: Sink[ByteString, Future[Done]],
    stdout: Source[ByteString, NotUsed],
    stderr: Source[ByteString, NotUsed]) extends NoSerializationVerificationNeeded

  /**
   * Sent to the receiver after the process has exited.
   *
   * @param exitValue the exit value of the process
   */
  case class Exited(exitValue: Int)

  /**
   * Send a request to destroy the process.
   * On POSIX, this sends a SIGTERM, but implementation is platform specific.
   */
  case object Destroy

  /**
   * Send a request to forcibly destroy the process.
   * On POSIX, this sends a SIGKILL, but implementation is platform specific.
   */
  case object DestroyForcibly

  /**
   * Create Props for a [[NonBlockingProcess]] actor.
   *
   * @param command signifies the program to be executed and its optional arguments
   * @param workingDir the working directory for the process; default is the current working directory
   * @param environment the environment for the process; default is `Map.emtpy`
   * @return Props for a [[NonBlockingProcess]] actor
   */
  def props(
    command: immutable.Seq[String],
    workingDir: File = new File(System.getProperty("user.dir")),
    environment: Map[String, String] = Map.empty) =
    Props(new NonBlockingProcess(command, workingDir, environment))

  private[process] object PublishIfAvailableSideChannel {
    sealed trait AsyncEvents
    case class Publish[T](e: T) extends AsyncEvents
    case class Complete[T](e: Option[T]) extends AsyncEvents
  }

  private[process] abstract class PublishIfAvailableSideChannel[T] {
    def publishIfAvailable(e: () => T): Unit
    def complete(e: Option[T]): Unit
  }

  /*
   * The motivation for this type of source is to publish *only* if any downstream
   * is ready to receive. The assumption is that only one thread will be calling
   * the publishIfAvailable function of the side channel at any one time.
   */
  private[process] class PublishIfAvailable[T]
      extends GraphStageWithMaterializedValue[SourceShape[T], PublishIfAvailableSideChannel[T]] {

    val out = Outlet[T]("PublishIfAvailable.out")
    override def shape = SourceShape(out)

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, PublishIfAvailableSideChannel[T]) = {
      val asyncCallback = new AtomicReference[AsyncCallback[PublishIfAvailableSideChannel.AsyncEvents]]
      var downstreamReady = false
      val downstreamReadyLock = new ReentrantLock
      val logic = new GraphStageLogic(shape) {

        override def preStart(): Unit =
          asyncCallback.set(getAsyncCallback[PublishIfAvailableSideChannel.AsyncEvents] {
            case PublishIfAvailableSideChannel.Publish(e: T @unchecked) =>
              push(out, e)
            case PublishIfAvailableSideChannel.Complete(e: Option[T] @unchecked) =>
              e.foreach(emit(out, _))
              completeStage()
          })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            downstreamReadyLock.lock()
            try {
              downstreamReady = true
            } finally {
              downstreamReadyLock.unlock()
            }
          }
        })

      }

      logic -> new PublishIfAvailableSideChannel[T] {
        override def publishIfAvailable(e: () => T): Unit =
          Option(asyncCallback.get).foreach { ac =>
            downstreamReadyLock.lock()
            try {
              if (downstreamReady) {
                ac.invoke(PublishIfAvailableSideChannel.Publish(e()))
                downstreamReady = false
              }
            } finally {
              downstreamReadyLock.unlock()
            }
          }

        override def complete(e: Option[T]): Unit =
          Option(asyncCallback.get).foreach(_.invoke(PublishIfAvailableSideChannel.Complete(e)))
      }
    }
  }
}

/**
 * NonBlockingProcess encapsulates an operating system process and its ability to be communicated with via stdio i.e.
 * stdin, stdout and stderr. The reactive streams for stdio are communicated in a NonBlockingProcess.Started event
 * upon the actor being established. The parent actor is then subsequently streamed
 * stdout and stderr events. When the process exists (determined by periodically polling process.isAlive()) then
 * the process's exit code is communicated to the receiver in a NonBlockingProcess.Exited event.
 *
 * All IO is performed in a non-blocking manner. Herein lies the difference between [[BlockingProcess]] and this
 * actor. As such, many more processes can be managed via this while consuming *much* less memory on your JVM.
 * Always favour this actor over its blocking counterpart.
 */
class NonBlockingProcess(
  command: immutable.Seq[String],
  directory: File,
  environment: Map[String, String])
    extends Actor with ActorLogging {

  import NonBlockingProcess._

  val process: NuProcess = {
    import JavaConverters._

    val pb = new NuProcessBuilder(command.asJava)

    pb.environment().putAll(environment.asJava)

    pb.setCwd(directory.toPath)

    val parent = context.parent
    pb.setProcessListener(new NuAbstractProcessHandler {
      override def onPreStart(nuProcess: NuProcess): Unit = {
        // Create our stream based actors away from this one given that we want them to continue
        // for a small while post the actor dying (it may take a tiny bit longer for the process
        // to terminate).
        implicit val stdioMaterializer: ActorMaterializer = ActorMaterializer()(context.system)
        val stdin =
          Sink
            .foreach[ByteString](bytes => nuProcess.writeStdin(bytes.toByteBuffer))
        val (out, stdout) =
          Source
            .fromGraph(new PublishIfAvailable[ByteString])
            .toMat(BroadcastHub.sink)(Keep.both)
            .run
        val (err, stderr) =
          Source
            .fromGraph(new PublishIfAvailable[ByteString])
            .toMat(BroadcastHub.sink)(Keep.both)
            .run

        // FIXME: if we don't consume from stdout/stderr then we know that NuProcess will spin the CPU - see https://github.com/brettwooldridge/NuProcess/issues/53
        nuProcess.setProcessHandler(new NuAbstractProcessHandler {
          override def onStart(nuProcess: NuProcess): Unit =
            parent ! Started(nuProcess.getPID, stdin, stdout, stderr)

          override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit =
            if (!closed)
              err.publishIfAvailable(() => ByteString.fromByteBuffer(buffer))
            else
              err.complete(if (buffer.hasRemaining) Some(ByteString.fromByteBuffer(buffer)) else None)

          override def onExit(exitCode: Int): Unit = {
            parent ! NonBlockingProcess.Exited(exitCode)
            self ! PoisonPill
          }

          override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit =
            if (!closed)
              out.publishIfAvailable(() => ByteString.fromByteBuffer(buffer))
            else
              out.complete(if (buffer.hasRemaining) Some(ByteString.fromByteBuffer(buffer)) else None)
        })
      }
    })

    log.debug("Process starting: {}", command.headOption.getOrElse("<unknown>"))
    pb.start()
  }

  override def receive: Receive = {
    case Destroy =>
      log.debug("Received request to destroy the process.")
      blocking(process.destroy(false))
    case DestroyForcibly =>
      log.debug("Received request to forcibly destroy the process.")
      blocking(process.destroy(true))
  }

  override def postStop(): Unit =
    process.destroy(true)
}
