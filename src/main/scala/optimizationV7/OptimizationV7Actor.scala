package optimizationV7

import akka.actor.{Actor, Props}
import blocking.BlockingJobActor.NewJob
import blocking.NonBlockingJobActor
import blocking.NonBlockingJobActor.{NonBlockingJobReq, NonBlockingJobResp}
import optimizationV7.dao.DaoActor
import optimizationV7.dao.DaoActor.{FindByKey, FindByKeyResult}
import org.joda.time.DateTime
import util.TimerActor.Finish


class OptimizationV7Actor extends Actor {
  private val daoActor = context.actorOf(Props[DaoActor], "dao-actor")
  private val nonBlockingActor = context.actorOf(Props[NonBlockingJobActor], "non-blocking-actor")
  private val timerActor = context.actorSelection("akka://d/user/timer-actor")

  override def receive: Receive = {
    case NewJob(info) =>
      // some blocking IO operation
      daoActor ! FindByKey(info)
      // some non-blocking IO operation independent of blocking IO result
      (0 until 20).foreach {
        _ => nonBlockingActor ! NonBlockingJobReq("independent of blocking IO result")
      }

    case FindByKeyResult(res) =>
      // some non-blocking IO operation depend on blocking IO result
      (0 until 10).foreach{
        _ => nonBlockingActor ! NonBlockingJobReq(res)
      }
      timerActor ! Finish

    case NonBlockingJobResp(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobResp($info)")
  }
}

object OptimizationV7Actor {
  case class NewJob(info: String)
}
