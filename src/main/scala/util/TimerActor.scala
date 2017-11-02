package util

import akka.actor.Actor
import org.joda.time.DateTime
import util.TimerActor.{Finish, Start, TotalCount}

class TimerActor extends Actor{
  var startTime: DateTime = _
  var count = 0
  var total = 0
  override def receive: Receive = {
    case Start => startTime = DateTime.now()
    case Finish =>
      count += 1
      if(count == total){
        val finishTime = DateTime.now()
        println(s"finished at ${startTime.toString("yyyy-MM-dd HH:mm:ss")}, elapsed time = ${finishTime.getMillis - startTime.getMillis}ms")
        context.system.terminate()
      }
    case TotalCount(cnt) => total = cnt
  }
}

object TimerActor{
  case object Start
  case object Finish
  case class TotalCount(cnt: Int)
}