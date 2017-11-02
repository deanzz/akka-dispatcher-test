package optimizationV7

import akka.actor.{Actor, Props}
import blocking.BlockingJobActor.NewJob
import blocking.NonBlockingJobActor
import blocking.NonBlockingJobActor.{NonBlockingJobReq, NonBlockingJobResp}
import optimizationV7.cpu.CPUWorkActor
import optimizationV7.cpu.CPUWorkActor.{Compute, ComputeResult}
import optimizationV7.dao.DaoActor
import optimizationV7.dao.DaoActor.{FindByKey, FindByKeyResult}
import org.joda.time.DateTime
import util.TimerActor.Finish

class OptimizationV7Actor(cpuTaskCount: Int, nonBlockingTaskCount: Int) extends Actor {
  private val daoActor = context.actorOf(Props[DaoActor], "dao-actor")
  private val cpuWorkActor = context.actorOf(Props[CPUWorkActor], "cpu-work-actor")
  private val nonBlockingActor = context.actorOf(Props[NonBlockingJobActor], "non-blocking-actor")
  private val timerActor = context.actorSelection("akka://d/user/timer-actor")

  override def receive: Receive = {
    case NewJob(info) =>
      // some blocking IO operation
      daoActor ! FindByKey(info)
      // some non-blocking IO operation independent of blocking IO result
      (0 until nonBlockingTaskCount).foreach {
        _ => nonBlockingActor ! NonBlockingJobReq("independent of any result")
      }

      // some high cpu work
      (0 until cpuTaskCount).foreach{
        _ => cpuWorkActor ! Compute(100)
      }

    case FindByKeyResult(res) =>
      // some non-blocking IO operation depend on blocking IO result
      nonBlockingActor ! NonBlockingJobReq(res)

    case ComputeResult(res) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, ComputeResult($res)")
      // some non-blocking IO operation depend on cpu work result
      nonBlockingActor ! NonBlockingJobReq(res.toString)

    case NonBlockingJobResp(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobResp($info)")
      timerActor ! Finish
  }
}

object OptimizationV7Actor {
  case class NewJob(info: String)
}
