package org.http4s
package blazecore
package websocket

import java.util.concurrent.atomic.AtomicReference
import cats.effect._
import cats.implicits._
import fs2._
import org.http4s.{websocket => ws4s}
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage, TrunkBuilder}
import org.http4s.blaze.pipeline.stages.SerializingStage
import org.http4s.blaze.util.Execution.{directec, trampoline}
import org.http4s.websocket.WebsocketBits._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class Http4sWSStage[F[_]](ws: ws4s.Websocket[F])(implicit F: Effect[F], val ec: ExecutionContext)
    extends TailStage[WebSocketFrame] {

  def name: String = "Http4s WebSocket Stage"

  private val deadSignal: AtomicReference[Boolean] = new AtomicReference(false)

  //////////////////////// Source and Sink generators ////////////////////////

  def snk: Sink[F, WebSocketFrame] = _.evalMap { frame =>
    F.async[Unit] { cb =>
      channelWrite(frame).onComplete {
        case Success(res) => cb(Right(res))
        case Failure(t @ Command.EOF) => cb(Left(t))
        case Failure(t) => cb(Left(t))
      }(directec)
    }
  }

  def inputstream: Stream[F, WebSocketFrame] = {
    val t = F.async[WebSocketFrame] { cb =>
      def go(): Unit =
        channelRead().onComplete {
          case Success(ws) =>
            ws match {
              case c @ Close(_) =>
                deadSignal.set(true)
                cb(Right(c)) // With Dead Signal Set, Return callback with the Close Frame
              case Ping(d) =>
                channelWrite(Pong(d)).onComplete {
                  case Success(_) => go()
                  case Failure(Command.EOF) => cb(Left(Command.EOF))
                  case Failure(t) => cb(Left(t))
                }(trampoline)
              case Pong(_) => go()
              case f => cb(Right(f))
            }

          case Failure(Command.EOF) => cb(Left(Command.EOF))
          case Failure(e) => cb(Left(e))
        }(trampoline)
      go()
    }
    Stream.repeatEval(t)
  }

  //////////////////////// Startup and Shutdown ////////////////////////

  override protected def stageStartup(): Unit = {
    super.stageStartup()

    // A latch for shutting down if both streams are closed.
    val count = new java.util.concurrent.atomic.AtomicInteger(2)

    // If both streams are closed set the signal
    val onStreamFinalize: F[Unit] =
      for {
        dec <- F.delay(count.decrementAndGet())
        _ <- if (dec == 0) F.delay(deadSignal.set(true)) else ().pure[F]
      } yield ()

    // Effect to send a close to the other endpoint
    val sendClose: F[Unit] = F.delay(sendOutboundCommand(Command.Disconnect))

    val wsStream = inputstream
      .to(ws.receive)
      .onFinalize(onStreamFinalize)
      .mergeHaltR(ws.send.onFinalize(onStreamFinalize).to(snk).drain)
      .interruptWhen(Stream.repeatEval(F.delay(deadSignal.get()))) // Check The Status of the deadSignal Atomic Ref
      .onFinalize(sendClose)
      .compile
      .drain

    async.unsafeRunAsync {
      wsStream.attempt.flatMap {
        case Left(_) => sendClose
        case Right(_) => ().pure[F]
      }
    } {
      case Left(t) =>
        IO(logger.error(t)("Error closing Web Socket"))
      case Right(_) =>
        // Nothing to do here
        IO.unit
    }
  }

  override protected def stageShutdown(): Unit = {
    deadSignal.set(true)
    super.stageShutdown()
  }
}

object Http4sWSStage {
  def bufferingSegment[F[_]](stage: Http4sWSStage[F]): LeafBuilder[WebSocketFrame] =
    TrunkBuilder(new SerializingStage[WebSocketFrame]).cap(stage)
}
