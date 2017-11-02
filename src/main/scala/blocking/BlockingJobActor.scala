package blocking

import akka.actor.{Actor, Props}
import blocking.BlockingJobActor.NewJob
import blocking.NonBlockingJobActor.{NonBlockingJobReq, NonBlockingJobResp}
import blocking.cpu.BlockingCPUWorker
import blocking.dao.BlockingDao
import org.joda.time.DateTime
import util.TimerActor.Finish

class BlockingJobActor(cpuTaskCount: Int, nonBlockingTaskCount: Int) extends Actor{
  private val dao = new BlockingDao
  private val cpuWorker = new BlockingCPUWorker
  private val nonBlockingActor = context.actorOf(Props[NonBlockingJobActor], "non-blocking-actor")
  private val timerActor = context.actorSelection("akka://d/user/timer-actor")
  override def receive: Receive = {
    case NewJob(info) =>
      // some blocking IO operation
      val res = dao.findByKey(info)
      // some non-blocking IO operation depend on blocking IO result
      nonBlockingActor ! NonBlockingJobReq(res)
      timerActor ! Finish
      // some high cpu operation
      (0 until cpuTaskCount).foreach{
        _ =>
          val r = cpuWorker.compute(100)
          // some non-blocking IO operation depend on cpu work result
          nonBlockingActor ! NonBlockingJobReq(r.toString)
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

object BlockingJobActor{
  case class NewJob(info: String)
}