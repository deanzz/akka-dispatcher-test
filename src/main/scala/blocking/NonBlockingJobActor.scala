package blocking

import akka.actor.Actor
import blocking.NonBlockingJobActor.{NonBlockingJobReq, NonBlockingJobResp}
import org.joda.time.DateTime

class NonBlockingJobActor extends Actor{
  override def receive: Receive = {
    case NonBlockingJobReq(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobReq($info)")
      Thread.sleep(20)
      sender() ! NonBlockingJobResp(s"${info.toUpperCase}")
  }
}

object NonBlockingJobActor{
  case class NonBlockingJobReq(info: String)
  case class NonBlockingJobResp(info: String)
}
