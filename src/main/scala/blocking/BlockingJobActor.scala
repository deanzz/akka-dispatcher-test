package blocking

import akka.actor.{Actor, Props}
import blocking.BlockingJobActor.NewJob
import blocking.NonBlockingJobActor.{NonBlockingJobReq, NonBlockingJobResp}
import blocking.dao.BlockingDao
import org.joda.time.DateTime
import util.TimerActor.Finish

class BlockingJobActor extends Actor{
  private val dao = new BlockingDao
  private val nonBlockingActor = context.actorOf(Props[NonBlockingJobActor], "non-blocking-actor")
  private val timerActor = context.actorSelection("akka://d/user/timer-actor")
  override def receive: Receive = {
    case NewJob(info) =>

      // some blocking IO operation
      val res = dao.findByKey(info)

      // some non-blocking IO operation depend on blocking IO result
      (0 until 10).foreach{
        _ => nonBlockingActor ! NonBlockingJobReq(res)
      }

      // some non-blocking IO operation independent of blocking IO result
      (0 until 20).foreach {
        _ => nonBlockingActor ! NonBlockingJobReq("independent of blocking IO result")
      }
      timerActor ! Finish

    case NonBlockingJobResp(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobResp($info)")
  }
}

object BlockingJobActor{
  case class NewJob(info: String)
}