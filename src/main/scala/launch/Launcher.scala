package launch

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.routing.{BalancingPool, RoundRobinPool}
import blocking.BlockingJobActor
import blocking.BlockingJobActor.NewJob
import com.typesafe.config.ConfigFactory
import optimizationV1.OptimizationV1Actor
import optimizationV4.OptimizationV4Actor
import optimizationV5.OptimizationV5Actor
import util.TimerActor
import util.TimerActor.{Start, TotalCount}

object Launcher {

  val count = 50
  val cpuTaskCount = 2
  val nonBlockingTaskCount = 40

  def main(args: Array[String]): Unit ={
    //blocking
    //optimizationV1
    //optimizationV2
    //optimizationV3
    //optimizationV4
    //optimizationV5
    optimizationV6
  }

  def blocking = {
    println("start blocking")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/blocking.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(Props(classOf[BlockingJobActor], cpuTaskCount, nonBlockingTaskCount), "blocking-actor")
    doIt(jobActor, system, "blocking")
  }

  def optimizationV1 = {
    println("start optimizationV1")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/blocking.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(Props(classOf[OptimizationV1Actor], cpuTaskCount, nonBlockingTaskCount), "optimizationV1-actor")
    doIt(jobActor, system, "optimizationV1")
  }

  def optimizationV2 = {
    println("start optimizationV2")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/blocking.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(RoundRobinPool(10).props(Props(classOf[BlockingJobActor], cpuTaskCount, nonBlockingTaskCount)/*.withDispatcher("akka.actor.blocking-io-dispatcher")*/), "optimizationV2-actor")
    doIt(jobActor, system, "optimizationV4")
  }

  def optimizationV3 = {
    println("start optimizationV3")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/blocking.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(BalancingPool(10).props(Props(classOf[BlockingJobActor], cpuTaskCount, nonBlockingTaskCount)), "optimizationV3-actor")
    doIt(jobActor, system, "optimizationV3")
  }

  def optimizationV4 = {
    println("start optimizationV4")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV4.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(Props(classOf[OptimizationV4Actor], cpuTaskCount, nonBlockingTaskCount), "optimizationV4-actor")
    doIt(jobActor, system, "optimizationV4")
  }

  def optimizationV5 = {
    println("start optimizationV5")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV4.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(Props(classOf[OptimizationV5Actor], cpuTaskCount, nonBlockingTaskCount), "optimizationV5-actor")
    doIt(jobActor, system, "optimizationV5")
  }

  def optimizationV6 = {
    println("start optimizationV6")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV4.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(BalancingPool(10).props(Props(classOf[OptimizationV4Actor/*OptimizationV5Actor*/], cpuTaskCount, nonBlockingTaskCount)), "optimizationV6-actor")
    //val jobActor = system.actorOf(RoundRobinPool(10).props(Props(classOf[OptimizationV4Actor/*OptimizationV5Actor*/], cpuTaskCount, nonBlockingTaskCount)), "optimizationV6-actor")

    doIt(jobActor, system, "optimizationV6")
  }

  def doIt(jobActor: ActorRef, system: ActorSystem, name: String): Unit = {
    val timer = system.actorOf(Props[TimerActor], "timer-actor")
    timer ! TotalCount(count + count * cpuTaskCount + count * nonBlockingTaskCount)
    timer ! Start
    (0 until count).foreach{
      _ =>
        jobActor ! NewJob(s"$name-job")
    }
    println("Send messages finished")
  }

}
