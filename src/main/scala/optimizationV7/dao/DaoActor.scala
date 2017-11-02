package optimizationV7.dao

import akka.actor.Actor
import optimizationV7.dao.DaoActor.{FindByKey, FindByKeyResult}
import org.joda.time.DateTime
import akka.pattern.pipe

import scala.concurrent.Future

class DaoActor extends Actor {
  implicit val blockingExecutionContext = context.system.dispatchers.lookup("akka.actor.blocking-io-dispatcher")

  override def receive: Receive = {
    case FindByKey(key) =>
      val future = Future(FindByKeyResult(findByKey(key)))
      pipe(future) to sender()
  }

  def findByKey(key: String): String = {
    println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, start findByKey($key)")
    Thread.sleep(10000)
    s"db result is $key"
  }
}

object DaoActor{
  case class FindByKey(key: String)
  case class FindByKeyResult(res: String)
}
