package optimizationV7.cpu

import akka.actor.Actor
import optimizationV7.cpu.CPUWorkActor.{Compute, ComputeResult}
import akka.pattern.pipe
import org.joda.time.DateTime

import scala.concurrent.Future

class CPUWorkActor extends Actor{
  private implicit val cpuExecutionContext = context.system.dispatchers.lookup("akka.actor.cpu-work-dispatcher")

  override def receive: Receive = {
    case Compute(n) =>
      val future = Future(ComputeResult(compute(n)))
      pipe(future) to sender()
  }

  def compute(n: Int): Long = {
    println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, start compute($n)")
    val start = DateTime.now().getMillis
    var idx: Long = 0
    while(DateTime.now.getMillis - start <= 5000){
      idx += 1
    }
    idx
  }
}

object CPUWorkActor{
  case class Compute(n: Int)
  case class ComputeResult(n: Long)
}
