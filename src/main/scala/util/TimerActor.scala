package util

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Actor
import org.joda.time.DateTime
import util.TimerActor.{Finish, Start, TotalCount}

class TimerActor extends Actor{
  var startTime: DateTime = _
  var count = new AtomicInteger(0)
  var total = 0
  override def receive: Receive = {
    case Start =>
      startTime = DateTime.now()
      println(s"startTime = ${startTime.toString("HH:mm:ss")}")
    case Finish =>
      count.getAndIncrement()
      if(count.get() == total){
        val finishTime = DateTime.now()
        println(s"finished at ${finishTime.toString("yyyy-MM-dd HH:mm:ss")}, elapsed time = ${finishTime.getMillis - startTime.getMillis}ms")
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