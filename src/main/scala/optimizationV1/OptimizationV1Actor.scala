package optimizationV1

import akka.actor.{Actor, Props}
import blocking.BlockingJobActor.NewJob
import blocking.NonBlockingJobActor
import blocking.NonBlockingJobActor.{NonBlockingJobReq, NonBlockingJobResp}
import blocking.dao.BlockingDao
import org.joda.time.DateTime
import util.TimerActor.Finish

import scala.concurrent.Future
import scala.util.{Failure, Success}

class OptimizationV1Actor extends Actor {
  private val dao = new BlockingDao
  private val nonBlockingActor = context.actorOf(Props[NonBlockingJobActor], "non-blocking-actor")
  private val timerActor = context.actorSelection("akka://d/user/timer-actor")
  implicit val blockingExecutionContext = context.system.dispatcher

  override def receive: Receive = {
    case NewJob(info) =>
      // some blocking IO operation
      Future {
        dao.findByKey(info)
      }.onComplete{
        case Success(res) =>
          // some non-blocking IO operation depend on blocking IO result
          (0 until 10).foreach{
            _ => nonBlockingActor ! NonBlockingJobReq(res)
          }
          timerActor ! Finish
        case Failure(e) =>
          e.printStackTrace()
          println(e.toString)
      }

      // some non-blocking IO operation independent of blocking IO result
      (0 until 20).foreach {
        _ => nonBlockingActor ! NonBlockingJobReq("independent of blocking IO result")
      }

    case NonBlockingJobResp(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobResp($info)")
  }
}

object OptimizationV1Actor {
  case class NewJob(info: String)
}