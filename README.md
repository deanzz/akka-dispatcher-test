# akka-actor如何最大化利用单台机器上的硬件资源
>这次给大家分享一下使用akka的actor如何最大化利用单台机器上的硬件资源。<br/>
根据摩尔定律，每隔大约18个月，cpu每一单位面积的晶体管数量就会增加一倍，也就是cpu时钟速度增加一倍。<br/>
不过如今已经今非其比了，越来越多的cpu以多核来吸引眼球，多核时代早已到来。<br/>
所以要最大化利用单台机器上的硬件资源，其中很重要的一点就是榨干机器上的cpu资源。<br/>

## 基础知识
我们主要了解清楚actor的Dispatcher、Router和future的ExecutionContext的概念就可以了。<br/>
###### Dispatcher是什么？<br/>
Dispatcher是一个执行上下文，actor中的任务都会交由Dispatcher去执行，Dispatcher将如何执行任务与何时运行任务两者解耦。<br/>
大家也可以先简单把Dispatcher看成一个可执行任务的线程池。<br/>
###### ExecutionContext是什么？<br/>
scala.concurrent.ExecutionContext，是scala中Future的可执行上下文，大家可以把Dispatcher和ExecutionContext看成是一个东西，Dispatcher继承了ExecutionContext，他们的作用相同。<br/>
###### Router是什么？<br/>
Router是akka中一个用于负载均衡和路由的抽象。<br/>
Router有很多种，今天我们会涉及到的是RoundRobinPool和BalancingPool。<br/>
RoundRobinPool:<br/>
这种路由的策略是会依次向Pool中的各个节点发送消息，循环往复。<br/>
BalancingPool:<br/>
BalancingPool这个路由策略有点特殊。只可以用于本地Actor。多个Actor共享同一个邮箱，一有空闲就处理邮箱中的任务。这种策略可以确保所有Actor都处于繁忙状态。对于本地集群来说，经常会优先选择这个路由策略。

     
## 一个糟糕的例子
我们从一个用akka-actor写的糟糕的例子开始我们的优化之旅。<br/>
假设我们想完成这样一些工作:
1. 查询`1次`数据库获取数据，显示结果（阻塞IO操作）
2. 跑`2个`计算量较大的算法，显示结果（cpu密集型操作）
3. 内存中查询`40次`结果，并显示（非阻塞IO操作）<br/>

根据需求我们写出了如下几个类：

1. BlockingJobActor，干活的actor，接收一个NewJob消息处理上面所说的3个工作
2. NonBlockingJobActor，内存中查询结果的actor，接收一个NonBlockingJobReq消息显示传入的字符串
3. BlockingDao，模拟数据库查询的类，调用findByKey方法，会模拟阻塞等待10秒查询结果
4. BlockingCPUWorker，模拟运行算法的类，调用compute方法，会让cpu满负荷运转5秒
5. Launcher，程序运行的入口，在这里切换调用各种优化方法
6. TimerActor，用于计算程序执行时间的actor
7. blocking.conf，akka的配置文件

#### 主要代码<br/>

1. BlockingJobActor<br/>
按照常规逻辑，<br/>
首先调用dao.findByKey(info)查询数据库获取结果，并显示结果；<br/>
然后调用几次cpuWorker.compute(100)跑计算量较大的算法，并显示结果；<br/>
最后请求内存中查询一些结果，并显示。
```scala
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
```

2. NonBlockingJobActor
```scala
case NonBlockingJobReq(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobReq($info)")
      Thread.sleep(20)
      sender() ! NonBlockingJobResp(s"${info.toUpperCase}")
```

3. blocking.conf
```text
akka.actor{
  default-dispatcher{
    # Must be one of the following
    # Dispatcher, PinnedDispatcher, or a FQCN to a class inheriting
    # MessageDispatcherConfigurator with a public constructor with
    # both com.typesafe.config.Config parameter and
    # akka.dispatch.DispatcherPrerequisites parameters.
    # PinnedDispatcher must be used together with executor=thread-pool-executor.
    type = "Dispatcher"
    executor = "fork-join-executor"
    fork-join-executor {
      # Min number of threads to cap factor-based parallelism number to
      parallelism-min = 10
      # The parallelism factor is used to determine thread pool size using the
      # following formula: ceil(available processors * factor). Resulting size
      # is then bounded by the parallelism-min and parallelism-max values.
      parallelism-factor = 5.0
      # Max number of threads to cap factor-based parallelism number to
      parallelism-max = 10
    }
    # Throughput defines the number of messages that are processed in a batch
    # before the thread is returned to the pool. Set to 1 for as fair as possible.
    throughput = 20
  }
}
```

#### 日志及线程的使用情况<br/>
执行时间：未完成<br/>
日志：<br/>
```text
17:23:14: d-akka.actor.default-dispatcher-4, start findByKey(blocking-job)
17:23:24: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(db result is blocking-job)
17:23:24: d-akka.actor.default-dispatcher-4, start compute(100)
17:23:29: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(66263807)
17:23:29: d-akka.actor.default-dispatcher-4, start compute(100)
17:23:34: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(69022445)
17:23:34: d-akka.actor.default-dispatcher-4, start findByKey(blocking-job)
17:23:34: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
...
17:23:35: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
17:23:44: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(db result is blocking-job)
17:23:44: d-akka.actor.default-dispatcher-4, start compute(100)
17:23:49: d-akka.actor.default-dispatcher-4, start compute(100)
17:23:49: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(67621455)
17:23:54: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(67664446)
17:23:54: d-akka.actor.default-dispatcher-4, start findByKey(blocking-job)
17:23:54: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
...
17:23:55: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
17:24:04: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(db result is blocking-job)
...
```

线程使用情况：<br/>

![线程使用情况](https://raw.githubusercontent.com/deanzz/akka-dispatcher-test/master/pic/blocking.png)

黄色代表等待<br/>
绿色代表运行<br/>
红色代表阻塞<br/>

#### 总结<br/>
从日志和线程使用情况看可以看出，除去akka内部用于发消息用的调度器线程d-scheduler-1，<br/>
干活的线程就两个，d-akka.actor.default-dispatcher-2、d-akka.actor.default-dispatcher-3和d-akka.actor.default-dispatcher-4，<br/>
d-akka.actor.default-dispatcher-2、3承担了内存中查询结果的工作，由于是非阻塞IO的任务，经常在等待同步任务，所以经常处在等待的状态<br/>
d-akka.actor.default-despatcher-4承担了查询数据库和跑算法的工作，查询数据库是阻塞IO的任务，所以线程在此期间会处于等待状态；跑算法的任务时cpu密集型任务，所以线程在此期间是运行状态。<br/>
由于BlockingJobActor中代码的写法完全是同步方式，导致耗时的工作都放在一个线程上同步执行，浪费了剩余7个线程（配置的10个线程-使用的3个线程），所以延迟很高，吞吐量很低。

## 优化方案1
对于糟糕的同步方案，我们自然而然想到的是用异步操作优化，即将查询数据库的任务和跑算法任务都放到Future里执行。<br/>
我们将原有BlockingJobActor优化为OptimizationV1Actor，Future使用的执行上下文我们先简单的使用default-dispatcher，akka配置不变。

#### 优化的代码
1. OptimizationV1Actor<br/>
```scala
implicit val executionContext = context.system.dispatcher

case NewJob(info) =>
      // some blocking IO operation
      Future(dao.findByKey(info)).onComplete{
        case Success(res) =>
          // some non-blocking IO operation depend on blocking IO result
          nonBlockingActor ! NonBlockingJobReq(res)
        case Failure(e) =>
          e.printStackTrace()
          println(e.toString)
      }
      // some high cpu work
      (0 until cpuTaskCount).foreach {
        _ =>
          Future(cpuWorker.compute(100)).onComplete{
            case Success(r) =>
              println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, ComputeResult($r)")
              // some non-blocking IO operation depend on cpu work result
              nonBlockingActor ! NonBlockingJobReq(r.toString)
            case Failure(e) =>
              e.printStackTrace()
              println(e.toString)
          }
      }
      // some non-blocking IO operation independent of blocking IO result
      (0 until nonBlockingTaskCount).foreach {
        _ => nonBlockingActor ! NonBlockingJobReq("independent of any result")
      }
```

#### 日志及线程的使用情况<br/>
执行时间：约136秒<br/>
日志：<br/>
```text
19:11:54: d-akka.actor.default-dispatcher-9, start compute(100)
19:11:54: d-akka.actor.default-dispatcher-10, start compute(100)
19:11:54: d-akka.actor.default-dispatcher-5, start compute(100)
19:11:54: d-akka.actor.default-dispatcher-7, NonBlockingJobReq(independent of any result)
19:11:54: d-akka.actor.default-dispatcher-6, start compute(100)
19:11:54: d-akka.actor.default-dispatcher-8, start findByKey(optimizationV1-job)
19:11:54: d-akka.actor.default-dispatcher-4, start findByKey(optimizationV1-job)
19:11:54: d-akka.actor.default-dispatcher-3, start compute(100)
19:11:54: d-akka.actor.default-dispatcher-11, start findByKey(optimizationV1-job)
19:11:54: d-akka.actor.default-dispatcher-7, NonBlockingJobReq(independent of any result)
19:11:54: d-akka.actor.default-dispatcher-2, start compute(100)
19:11:54: d-akka.actor.default-dispatcher-7, NonBlockingJobReq(independent of any result)
...
19:11:55: d-akka.actor.default-dispatcher-7, NonBlockingJobReq(independent of any result)
19:11:55: d-akka.actor.default-dispatcher-7, start findByKey(optimizationV1-job)
19:11:59: d-akka.actor.default-dispatcher-9, NonBlockingJobReq(independent of any result)
19:11:59: d-akka.actor.default-dispatcher-6, start compute(100)
19:11:59: d-akka.actor.default-dispatcher-10, ComputeResult(36675535)
19:11:59: d-akka.actor.default-dispatcher-10, start compute(100)
19:11:59: d-akka.actor.default-dispatcher-3, ComputeResult(36280636)
19:11:59: d-akka.actor.default-dispatcher-3, start findByKey(optimizationV1-job)
19:11:59: d-akka.actor.default-dispatcher-5, ComputeResult(37208591)
19:11:59: d-akka.actor.default-dispatcher-5, ComputeResult(36462217)
19:11:59: d-akka.actor.default-dispatcher-5, ComputeResult(36296118)
19:11:59: d-akka.actor.default-dispatcher-5, start compute(100)
19:11:59: d-akka.actor.default-dispatcher-9, NonBlockingJobReq(independent of any result)
19:11:59: d-akka.actor.default-dispatcher-2, start compute(100)
19:11:59: d-akka.actor.default-dispatcher-9, NonBlockingJobReq(independent of any result)
...
19:12:00: d-akka.actor.default-dispatcher-9, NonBlockingJobReq(independent of any result)
19:12:00: d-akka.actor.default-dispatcher-9, start findByKey(optimizationV1-job)
19:12:04: d-akka.actor.default-dispatcher-8, NonBlockingJobReq(independent of any result)
19:12:04: d-akka.actor.default-dispatcher-4, start compute(100)
19:12:04: d-akka.actor.default-dispatcher-6, start compute(100)
19:12:04: d-akka.actor.default-dispatcher-11, ComputeResult(54510901)
19:12:04: d-akka.actor.default-dispatcher-11, start findByKey(optimizationV1-job)
19:12:04: d-akka.actor.default-dispatcher-5, ComputeResult(54440819)
19:12:04: d-akka.actor.default-dispatcher-5, start compute(100)
19:12:04: d-akka.actor.default-dispatcher-8, NonBlockingJobReq(independent of any result)
19:12:04: d-akka.actor.default-dispatcher-2, start compute(100)
19:12:04: d-akka.actor.default-dispatcher-8, NonBlockingJobReq(independent of any result)
...
19:12:04: d-akka.actor.default-dispatcher-8, NonBlockingJobReq(independent of any result)
19:12:04: d-akka.actor.default-dispatcher-10, start findByKey(optimizationV1-job)
19:12:04: d-akka.actor.default-dispatcher-8, NonBlockingJobReq(independent of any result)
...
19:12:05: d-akka.actor.default-dispatcher-8, NonBlockingJobReq(independent of any result)
19:12:05: d-akka.actor.default-dispatcher-7, start compute(100)
19:12:05: d-akka.actor.default-dispatcher-8, start compute(100)
19:12:09: d-akka.actor.default-dispatcher-6, start compute(100)
19:12:09: d-akka.actor.default-dispatcher-3, start compute(100)
19:12:09: d-akka.actor.default-dispatcher-5, ComputeResult(38017172)
19:12:09: d-akka.actor.default-dispatcher-5, ComputeResult(38675825)
19:12:09: d-akka.actor.default-dispatcher-5, start findByKey(optimizationV1-job)
19:12:09: d-akka.actor.default-dispatcher-4, start findByKey(optimizationV1-job)
19:12:09: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
...
19:12:10: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
19:12:10: d-akka.actor.default-dispatcher-7, start compute(100)
19:12:10: d-akka.actor.default-dispatcher-8, ComputeResult(38036533)
...
```

线程使用情况：<br/>

![线程使用情况](https://raw.githubusercontent.com/deanzz/akka-dispatcher-test/master/pic/v1.png)

#### 总结<br/>
从日志和线程使用情况看可以看出，除去akka内部用于发消息用的调度器线程d-scheduler-1，<br/>
其余default-dispatcher中的10个线程都在工作了，非常好，没有线程闲着了，而且执行完成了。<br/>
不过每个线程的状态都是不停地在运行和等待状态间交替，说明一个线程一会儿在做阻塞IO的任务，一会儿在做cpu密集型任务，一会儿在做内存查询，导致线程很忙，不够用。<br/>
下面的优化方案会解决这个问题。

## 优化方案2
对于糟糕的同步方案，我们除了使用Future，还可以使用Router，单纯的增加干活的actor的数量，我们先使用RoundRobinPool的策略。

#### 优化的代码
1. Launcher.optimizationV2
```scala
val jobActor = system.actorOf(RoundRobinPool(10).props(Props(classOf[BlockingJobActor], cpuTaskCount, nonBlockingTaskCount)), "optimizationV2-actor")
```
#### 日志及线程的使用情况<br/>
执行时间：约136秒<br/>
日志：<br/>
```text
20:01:24: d-akka.actor.default-dispatcher-4, start findByKey(optimizationV2-job)
20:01:34: d-akka.actor.default-dispatcher-5, start compute(100)
20:01:34: d-akka.actor.default-dispatcher-3, start compute(100)
...
20:01:39: d-akka.actor.default-dispatcher-8, start compute(100)
20:01:39: d-akka.actor.default-dispatcher-2, start compute(100)
20:01:44: d-akka.actor.default-dispatcher-5, start findByKey(optimizationV2-job)
20:01:44: d-akka.actor.default-dispatcher-11, start findByKey(optimizationV2-job)
20:01:44: d-akka.actor.default-dispatcher-6, start findByKey(optimizationV2-job)
20:01:44: d-akka.actor.default-dispatcher-10, start findByKey(optimizationV2-job)
20:01:44: d-akka.actor.default-dispatcher-9, start findByKey(optimizationV2-job)
...
20:01:44: d-akka.actor.default-dispatcher-7, start findByKey(optimizationV2-job)
20:01:44: d-akka.actor.default-dispatcher-2, start findByKey(optimizationV2-job)
20:01:54: d-akka.actor.default-dispatcher-11, start compute(100)
20:01:54: d-akka.actor.default-dispatcher-5, start compute(100)
20:01:54: d-akka.actor.default-dispatcher-6, start compute(100)
20:01:54: d-akka.actor.default-dispatcher-10, start compute(100)
...
20:01:59: d-akka.actor.default-dispatcher-5, start compute(100)
...
20:03:08: d-akka.actor.default-dispatcher-11, NonBlockingJobResp(INDEPENDENT OF ANY RESULT)
20:03:08: d-akka.actor.default-dispatcher-11, NonBlockingJobResp(DB RESULT IS OPTIMIZATIONV2-JOB)
20:03:08: d-akka.actor.default-dispatcher-11, NonBlockingJobResp(27622057)
```
线程使用情况：<br/>

![线程使用情况](https://raw.githubusercontent.com/deanzz/akka-dispatcher-test/master/pic/v2.png)
