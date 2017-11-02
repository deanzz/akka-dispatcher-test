package optimizationV2

import akka.actor.{Actor, Props}
import blocking.BlockingJobActor.NewJob
import blocking.NonBlockingJobActor
import blocking.NonBlockingJobActor.{NonBlockingJobReq, NonBlockingJobResp}
import blocking.cpu.BlockingCPUWorker
import blocking.dao.BlockingDao
import org.joda.time.DateTime
import util.TimerActor.Finish

import scala.concurrent.Future
import scala.util.{Failure, Success}

class OptimizationV2Actor(cpuTaskCount: Int, nonBlockingTaskCount: Int) extends Actor {
  private val dao = new BlockingDao
  private val cpuWorker = new BlockingCPUWorker
  private val nonBlockingActor = context.actorOf(Props[NonBlockingJobActor], "non-blocking-actor")
  private val timerActor = context.actorSelection("akka://d/user/timer-actor")
  private val blockingExecutionContext = context.system.dispatchers.lookup("akka.actor.blocking-io-dispatcher")
  private val cpuExecutionContext = context.system.dispatchers.lookup("akka.actor.cpu-work-dispatcher")

  override def receive: Receive = {
    case NewJob(info) =>
      // some blocking IO operation
      Future(dao.findByKey(info))(blockingExecutionContext).onComplete {
        case Success(res) =>
          // some non-blocking IO operation depend on blocking IO result
          nonBlockingActor ! NonBlockingJobReq(res)
        case Failure(e) =>
          e.printStackTrace()
          println(e.toString)
      }(blockingExecutionContext)
      // some high cpu work
      (0 until cpuTaskCount).foreach {
        _ =>
          Future(cpuWorker.compute(100))(cpuExecutionContext).onComplete{
            case Success(r) =>
              println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, ComputeResult($r)")
              // some non-blocking IO operation depend on cpu work result
              nonBlockingActor ! NonBlockingJobReq(r.toString)
            case Failure(e) =>
              e.printStackTrace()
              println(e.toString)
          }(cpuExecutionContext)
      }
      // some non-blocking IO operation independent of blocking IO result
      (0 until nonBlockingTaskCount).foreach {
        _ => nonBlockingActor ! NonBlockingJobReq("independent of any result")
      }

    case NonBlockingJobResp(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobResp($info)")
      timerActor ! Finish
  }
}

object OptimizationV2Actor {

  case class NewJob(info: String)

}