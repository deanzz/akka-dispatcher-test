package launch

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.routing.{BalancingPool, RoundRobinPool}
import blocking.BlockingJobActor
import blocking.BlockingJobActor.NewJob
import com.typesafe.config.ConfigFactory
import optimizationV1.OptimizationV1Actor
import optimizationV2.OptimizationV2Actor
import optimizationV7.OptimizationV7Actor
import util.TimerActor
import util.TimerActor.{Start, TotalCount}

object Launcher {

  val count = 200

  def main(args: Array[String]): Unit ={
    //blocking
    //optimizationV1
    optimizationV2
    //optimizationV3
    //optimizationV4
    //optimizationV5
    //optimizationV6
    //optimizationV7
  }

  def blocking = {
    println("start blocking")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/blocking.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(Props[BlockingJobActor], "blocking-actor")
    doIt(jobActor, system, "blocking")
  }

  def optimizationV1 = {
    println("start optimizationV1")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/blocking.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(Props[OptimizationV1Actor], "optimizationV1-actor")
    doIt(jobActor, system, "optimizationV1")
  }

  def optimizationV2 = {
    println("start optimizationV2")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV2.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(Props[OptimizationV2Actor], "optimizationV2-actor")
    doIt(jobActor, system, "optimizationV2")
  }

  def optimizationV3 = {
    println("start optimizationV3")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV2.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(RoundRobinPool(20).props(Props[BlockingJobActor].withDispatcher("akka.actor.blocking-io-dispatcher")), "optimizationV3-actor")
    doIt(jobActor, system, "optimizationV3")
  }

  def optimizationV4 = {
    println("start optimizationV4")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV2.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(RoundRobinPool(10).props(Props[OptimizationV2Actor]), "optimizationV4-actor")
    doIt(jobActor, system, "optimizationV4")
  }

  def optimizationV5 = {
    println("start optimizationV5")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV2.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(BalancingPool(20).props(Props[BlockingJobActor].withDispatcher("akka.actor.blocking-io-dispatcher")), "optimizationV5-actor")
    doIt(jobActor, system, "optimizationV5")
  }

  def optimizationV6 = {
    println("start optimizationV6")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV2.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(BalancingPool(10).props(Props[OptimizationV2Actor]), "optimizationV6-actor")
    doIt(jobActor, system, "optimizationV6")
  }

  def optimizationV7 = {
    println("start optimizationV7")
    val confPath = "/Users/deanzhang/work/code/github/akka-dispatcher-test/conf/optimizationV2.conf"
    val conf = ConfigFactory.parseFile(new File(confPath))
    val system = ActorSystem("d", conf)
    val jobActor = system.actorOf(Props[OptimizationV7Actor], "optimizationV7-actor")
    doIt(jobActor, system, "optimizationV7")
  }

  def doIt(jobActor: ActorRef, system: ActorSystem, name: String): Unit = {
    val timer = system.actorOf(Props[TimerActor], "timer-actor")
    timer ! Start
    timer ! TotalCount(count)
    (0 until count).foreach{
      _ =>
        jobActor ! NewJob(s"$name-job")
    }
    println("Send messages finished")
  }

}