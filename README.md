# akka-actor之性能优化
>这次给大家分享一下akka-actor如何进行性能优化，以达到最大化利用单台机器上的硬件资源的目的。<br/>
当今，处理器早已步入多核时代。<br/>
所以要最大化利用单台机器上的硬件资源，其中很重要的一点就是要充分利用线程异步完成任务，<br/>
即创造一个非阻塞、异步的消息驱动系统。<br/>

## 基础知识

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
###### Pipe是什么？<br/>
Pipe是一种消息传递方式，它接受Future的结果作为参数，然后将其传递给所提供的Actor引用，<br/>
比如：pipe(future) to sender()
     
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
7. conf/blocking.conf，akka的配置文件

#### 主要代码<br/>
具体内容可阅读代码中blocking包的内容。
1. BlockingJobActor<br/>
按照常规逻辑，<br/>
首先调用dao.findByKey(info)查询数据库获取结果，并显示结果；<br/>
然后调用几次cpuWorker.compute(100)跑计算量较大的算法，并显示结果；<br/>
最后请求内存中查询一些结果，并显示。
```scala
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
```

2. NonBlockingJobActor
```scala
override def receive: Receive = {
    case NonBlockingJobReq(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobReq($info)")
      Thread.sleep(20)
      sender() ! NonBlockingJobResp(s"${info.toUpperCase}")
  }
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
干活的线程就3个，d-akka.actor.default-dispatcher-2、d-akka.actor.default-dispatcher-3和d-akka.actor.default-dispatcher-4，<br/>
d-akka.actor.default-dispatcher-2、3承担了内存中查询结果的工作，由于是非阻塞IO的任务，经常在等待同步任务，所以经常处在等待的状态<br/>
d-akka.actor.default-despatcher-4承担了查询数据库和跑算法的工作，查询数据库是阻塞IO的任务，所以线程在此期间会处于等待状态；跑算法的任务是cpu密集型任务，所以线程在此期间是运行状态。<br/>
由于BlockingJobActor中代码的写法完全是同步方式，导致耗时的工作都放在一个线程上同步执行，浪费了剩余7个线程（配置的10个线程-使用的3个线程），所以延迟很高，吞吐量很低。

## 优化方案1
对于糟糕的同步方案，我们自然而然想到的是用异步操作优化，即将查询数据库的任务和跑算法任务都放到Future里执行。<br/>
我们将原有BlockingJobActor优化为OptimizationV1Actor，Future使用的执行上下文我们先简单的使用default-dispatcher，akka配置不变。

#### 优化的代码
具体内容可阅读代码中optimizationV1包的内容。
1. OptimizationV1Actor<br/>
```scala
implicit val executionContext = context.system.dispatcher

override def receive: Receive = {
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

    case NonBlockingJobResp(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobResp($info)")
      timerActor ! Finish
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
不过每个线程的状态都是不停地在运行和等待状态间交替，说明一个线程一会儿在做阻塞IO的任务，一会儿在做cpu密集型任务，一会儿在做内存查询，导致线程很忙，<br/>
而且本不用依赖其他任务的非阻塞内存查询操作，被阻塞住了。

## 优化方案2
对于糟糕的同步方案，我们除了使用Future，还可以使用Router，单纯的增加干活的actor的数量，我们先使用RoundRobinPool的策略。

#### 优化的代码
1. Launcher.optimizationV2
```scala
val jobActor = system.actorOf(RoundRobinPool(10).props(Props(classOf[BlockingJobActor], cpuTaskCount, nonBlockingTaskCount)), "optimizationV2-actor")
```
#### 日志及线程的使用情况<br/>
执行时间：约120秒<br/>
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
20:03:08: d-akka.actor.default-dispatcher-11, NonBlockingJobResp(DB RESULT IS 
...
```
线程使用情况：<br/>

![线程使用情况](https://raw.githubusercontent.com/deanzz/akka-dispatcher-test/master/pic/v2.png)

#### 总结<br/>
Router中的10个actor都在工作了，default-dispatcher中的10个线程也都用上了，同样也执行完成了，但是依然存在严重的线程等待问题，<br/>
同样本不用依赖其他任务的非阻塞内存查询操作，被阻塞住了。

## 优化方案3
我们还可以使用Router的BalancingPool的策略，来增加干活的actor的数量。

#### 优化的代码
1. Launcher.optimizationV3
```scala
val jobActor = system.actorOf(BalancingPool(10).props(Props(classOf[BlockingJobActor], cpuTaskCount, nonBlockingTaskCount)), "optimizationV3-actor")
```

#### 日志及线程的使用情况<br/>
执行时间：约105秒<br/>
日志：<br/>
```text
20:56:44: d-BalancingPool-/optimizationV3-actor-19, start findByKey(optimizationV3-job)
20:56:44: d-BalancingPool-/optimizationV3-actor-5, start findByKey(optimizationV3-job)
20:56:44: d-BalancingPool-/optimizationV3-actor-7, start findByKey(optimizationV3-job)
20:56:44: d-BalancingPool-/optimizationV3-actor-6, start findByKey(optimizationV3-job)
20:56:44: d-BalancingPool-/optimizationV3-actor-18, start findByKey(optimizationV3-job)
20:56:44: d-BalancingPool-/optimizationV3-actor-8, start findByKey(optimizationV3-job)
20:56:44: d-BalancingPool-/optimizationV3-actor-9, start findByKey(optimizationV3-job)
20:56:44: d-BalancingPool-/optimizationV3-actor-17, start findByKey(optimizationV3-job)
20:56:44: d-BalancingPool-/optimizationV3-actor-10, start findByKey(optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-16, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-13, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-15, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-12, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-4, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-20, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-22, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-akka.actor.default-dispatcher-21, NonBlockingJobReq(db result is optimizationV3-job)
20:56:54: d-BalancingPool-/optimizationV3-actor-17, start compute(100)
...
20:56:54: d-BalancingPool-/optimizationV3-actor-6, start compute(100)
20:56:59: d-akka.actor.default-dispatcher-21, NonBlockingJobReq(21337335)
20:56:59: d-akka.actor.default-dispatcher-16, NonBlockingJobReq(21912069)
20:56:59: d-BalancingPool-/optimizationV3-actor-17, start compute(100)
20:56:59: d-BalancingPool-/optimizationV3-actor-18, start compute(100)
20:56:59: d-BalancingPool-/optimizationV3-actor-9, start compute(100)
20:56:59: d-akka.actor.default-dispatcher-22, NonBlockingJobReq(21851866)
20:56:59: d-BalancingPool-/optimizationV3-actor-19, start compute(100)
20:56:59: d-akka.actor.default-dispatcher-20, NonBlockingJobReq(21988980)
20:56:59: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(23503990)
20:56:59: d-BalancingPool-/optimizationV3-actor-7, start compute(100)
20:56:59: d-akka.actor.default-dispatcher-12, NonBlockingJobReq(21511595)
20:56:59: d-BalancingPool-/optimizationV3-actor-10, start compute(100)
20:56:59: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(21921178)
20:56:59: d-BalancingPool-/optimizationV3-actor-8, start compute(100)
20:56:59: d-BalancingPool-/optimizationV3-actor-5, start compute(100)
20:56:59: d-akka.actor.default-dispatcher-13, NonBlockingJobReq(22026859)
20:56:59: d-akka.actor.default-dispatcher-21, NonBlockingJobReq(21721455)
20:56:59: d-BalancingPool-/optimizationV3-actor-11, start compute(100)
20:56:59: d-BalancingPool-/optimizationV3-actor-6, start compute(100)
20:56:59: d-akka.actor.default-dispatcher-12, NonBlockingJobReq(21849218)
20:57:04: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(23146505)
20:57:04: d-akka.actor.default-dispatcher-12, NonBlockingJobReq(22942295)
20:57:04: d-akka.actor.default-dispatcher-21, NonBlockingJobReq(23012881)
20:57:04: d-BalancingPool-/optimizationV3-actor-17, start findByKey(optimizationV3-job)
20:57:04: d-BalancingPool-/optimizationV3-actor-7, start findByKey(optimizationV3-job)
20:57:04: d-BalancingPool-/optimizationV3-actor-18, start findByKey(optimizationV3-job)
20:57:04: d-akka.actor.default-dispatcher-13, NonBlockingJobReq(23278757)
20:57:04: d-BalancingPool-/optimizationV3-actor-10, start findByKey(optimizationV3-job)
20:57:04: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(23351830)
...
20:57:04: d-BalancingPool-/optimizationV3-actor-5, start findByKey(optimizationV3-job)
20:57:04: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(independent of any result)
20:57:04: d-akka.actor.default-dispatcher-21, NonBlockingJobReq(independent of any result)
20:57:04: d-akka.actor.default-dispatcher-23, NonBlockingJobReq(23426269)
20:57:04: d-BalancingPool-/optimizationV3-actor-11, start findByKey(optimizationV3-job)
20:57:04: d-akka.actor.default-dispatcher-13, NonBlockingJobReq(independent of any result)
20:57:04: d-akka.actor.default-dispatcher-24, NonBlockingJobReq(23207851)
20:57:04: d-BalancingPool-/optimizationV3-actor-6, start findByKey(optimizationV3-job)
20:57:04: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
...
20:57:04: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(independent of any result)
...
```
线程使用情况：<br/>

![线程使用情况](https://raw.githubusercontent.com/deanzz/akka-dispatcher-test/master/pic/v3.png)

#### 总结<br/>
从日志看出，因为BalancingPool策略共享一个邮箱，所以拿取任务更充分，保证pool中的actor一直保持忙碌，但是依然存在严重的线程等待问题，<br/>
同样本不用依赖其他任务的非阻塞内存查询操作，被阻塞住了。

## 优化方案4
上面的优化方案都不尽人意，接下来会采取一种资源隔离的方案，<br/>
把阻塞IO的任务分离到单独的dispatcher，把需要大量计算、运行时间较长的任务分离到单独的dispatcher。

#### 优化的代码
具体内容可阅读代码中conf目录和optimizationV4包的内容。
1. optimizationV4.conf
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
      parallelism-min = 2
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

  blocking-io-dispatcher{
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
      parallelism-min = 4
      # The parallelism factor is used to determine thread pool size using the
      # following formula: ceil(available processors * factor). Resulting size
      # is then bounded by the parallelism-min and parallelism-max values.
      parallelism-factor = 10.0
      # Max number of threads to cap factor-based parallelism number to
      parallelism-max = 15
    }
    # Throughput defines the number of messages that are processed in a batch
    # before the thread is returned to the pool. Set to 1 for as fair as possible.
    throughput = 30
  }

  cpu-work-dispatcher{
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
      parallelism-min = 4
      # The parallelism factor is used to determine thread pool size using the
      # following formula: ceil(available processors * factor). Resulting size
      # is then bounded by the parallelism-min and parallelism-max values.
      parallelism-factor = 10.0
      # Max number of threads to cap factor-based parallelism number to
      parallelism-max = 15
    }
    # Throughput defines the number of messages that are processed in a batch
    # before the thread is returned to the pool. Set to 1 for as fair as possible.
    throughput = 30
  }
}
```

2. OptimizationV4Actor
```scala
override def receive: Receive = {
    case NewJob(info) =>
      // some blocking IO operation
      Future(dao.findByKey(info))(blockingExecutionContext).onComplete {
        case Success(res) =>
          // some non-blocking IO operation depend on blocking IO result
          nonBlockingActor ! NonBlockingJobReq(res)
        case Failure(e) =>
          e.printStackTrace()
          println(e.toString)
      }(blockingExecutionContext)
      // some high cpu work
      (0 until cpuTaskCount).foreach {
        _ =>
          Future(cpuWorker.compute(100))(cpuExecutionContext).onComplete{
            case Success(r) =>
              println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, ComputeResult($r)")
              // some non-blocking IO operation depend on cpu work result
              nonBlockingActor ! NonBlockingJobReq(r.toString)
            case Failure(e) =>
              e.printStackTrace()
              println(e.toString)
          }(cpuExecutionContext)
      }
      // some non-blocking IO operation independent of blocking IO result
      (0 until nonBlockingTaskCount).foreach {
        _ => nonBlockingActor ! NonBlockingJobReq("independent of any result")
      }

    case NonBlockingJobResp(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobResp($info)")
      timerActor ! Finish
  }
```

3. Launcher.optimizationV4
```scala
val jobActor = system.actorOf(Props(classOf[OptimizationV2Actor], cpuTaskCount, nonBlockingTaskCount), "optimizationV4-actor")
```

#### 日志及线程的使用情况<br/>
执行时间：约50秒<br/>
日志：<br/>
```text
00:02:27: d-akka.actor.blocking-io-dispatcher-20, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.cpu-work-dispatcher-17, start compute(100)
00:02:27: d-akka.actor.cpu-work-dispatcher-16, start compute(100)
00:02:27: d-akka.actor.blocking-io-dispatcher-28, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.cpu-work-dispatcher-23, start compute(100)
00:02:27: d-akka.actor.cpu-work-dispatcher-25, start compute(100)
00:02:27: d-akka.actor.cpu-work-dispatcher-10, start compute(100)
00:02:27: d-akka.actor.blocking-io-dispatcher-29, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.cpu-work-dispatcher-9, start compute(100)
00:02:27: d-akka.actor.cpu-work-dispatcher-21, start compute(100)
00:02:27: d-akka.actor.blocking-io-dispatcher-26, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.cpu-work-dispatcher-11, start compute(100)
00:02:27: d-akka.actor.cpu-work-dispatcher-19, start compute(100)
00:02:27: d-akka.actor.cpu-work-dispatcher-27, start compute(100)
00:02:27: d-akka.actor.blocking-io-dispatcher-14, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.blocking-io-dispatcher-5, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.blocking-io-dispatcher-12, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.blocking-io-dispatcher-24, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.cpu-work-dispatcher-13, start compute(100)
00:02:27: d-akka.actor.cpu-work-dispatcher-7, start compute(100)
00:02:27: d-akka.actor.blocking-io-dispatcher-31, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.blocking-io-dispatcher-30, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.cpu-work-dispatcher-15, start compute(100)
00:02:27: d-akka.actor.cpu-work-dispatcher-6, start compute(100)
00:02:27: d-akka.actor.blocking-io-dispatcher-34, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.blocking-io-dispatcher-18, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.blocking-io-dispatcher-33, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.cpu-work-dispatcher-22, start compute(100)
00:02:27: d-akka.actor.blocking-io-dispatcher-8, start findByKey(optimizationV4-job)
00:02:27: d-akka.actor.default-dispatcher-4, NonBlockingJobReq(independent of any result)
00:02:27: d-akka.actor.blocking-io-dispatcher-32, start findByKey(optimizationV4-job)
00:02:28: d-akka.actor.default-dispatcher-4, NonBlockingJobReq(independent of any result)
...
00:02:33: d-akka.actor.default-dispatcher-2, NonBlockingJobResp(INDEPENDENT OF ANY RESULT)
00:02:33: d-akka.actor.default-dispatcher-35, NonBlockingJobReq(independent of any result)
00:02:33: d-akka.actor.cpu-work-dispatcher-17, start compute(100)
00:02:33: d-akka.actor.cpu-work-dispatcher-25, start compute(100)
00:02:33: d-akka.actor.cpu-work-dispatcher-16, start compute(100)
00:02:33: d-akka.actor.cpu-work-dispatcher-15, ComputeResult(12953293)
00:02:33: d-akka.actor.cpu-work-dispatcher-9, ComputeResult(12683075)
00:02:33: d-akka.actor.cpu-work-dispatcher-22, ComputeResult(12150066)
00:02:33: d-akka.actor.cpu-work-dispatcher-21, ComputeResult(12132914)
...
00:03:17: d-akka.actor.default-dispatcher-4, NonBlockingJobResp(12498333)
00:03:17: d-akka.actor.default-dispatcher-35, NonBlockingJobReq(db result is optimizationV4-job)
00:03:17: d-akka.actor.default-dispatcher-4, NonBlockingJobResp(DB RESULT IS OPTIMIZATIONV4-JOB)
00:03:17: d-akka.actor.default-dispatcher-35, NonBlockingJobResp(DB RESULT IS OPTIMIZATIONV4-JOB)
...
```

线程使用情况：<br/>

![线程使用情况](https://raw.githubusercontent.com/deanzz/akka-dispatcher-test/master/pic/v4.png)

#### 总结<br/>
我们重新启用了Future，并将数据库查询任务分离到名叫blocking-io-dispatcher的dispatcher，<br/>
将跑算法的任务分离到名叫cpu-work-dispatcher的dispatcher，默认的default-dispatcher用来跑非阻塞的任务，<br/>
资源隔离减少了不同种类任务资源的竞争，可以确保应用程序在糟糕的情况下仍然能够有资源去运行其他任务，保证应用程序的其他部分还是能够迅速地做出响应。
         
## 优化方案5
接下来我们还沿用资源隔离的方案，但是完全采用actor模型的设计模式，即万物都为actor，所以数据库查询任务抽象到DaoActor，跑算法抽象到CPUWorkerActor,<br/>
同时使用pipe传递future消息。

#### 优化的代码
1. OptimizationV5Actor
```scala
override def receive: Receive = {
    case NewJob(info) =>
      // some blocking IO operation
      daoActor ! FindByKey(info)
      // some non-blocking IO operation independent of blocking IO result
      (0 until nonBlockingTaskCount).foreach {
        _ => nonBlockingActor ! NonBlockingJobReq("independent of any result")
      }
      // some high cpu work
      (0 until cpuTaskCount).foreach{
        _ => cpuWorkActor ! Compute(100)
      }
    case FindByKeyResult(res) =>
      // some non-blocking IO operation depend on blocking IO result
      nonBlockingActor ! NonBlockingJobReq(res)
    case ComputeResult(res) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, ComputeResult($res)")
      // some non-blocking IO operation depend on cpu work result
      nonBlockingActor ! NonBlockingJobReq(res.toString)
    case NonBlockingJobResp(info) =>
      println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, NonBlockingJobResp($info)")
      timerActor ! Finish  
}
```

2. DaoActor
```scala
override def receive: Receive = {
    case FindByKey(key) =>
      val future = Future(FindByKeyResult(findByKey(key)))
      pipe(future) to sender()
  }
```

3. CPUWorkerActor
```scala
override def receive: Receive = {
    case Compute(n) =>
      val future = Future(ComputeResult(compute(n)))
      pipe(future) to sender()
  }
```

4. Launcher.optimizationV5
```scala
val jobActor = system.actorOf(Props(classOf[OptimizationV5Actor], cpuTaskCount, nonBlockingTaskCount), "optimizationV5-actor")
```

#### 日志及线程的使用情况<br/>
执行时间：约50秒<br/>
日志：<br/>
```text
14:42:09: d-akka.actor.cpu-work-dispatcher-23, start compute(100)
14:42:09: d-akka.actor.cpu-work-dispatcher-25, start compute(100)
14:42:09: d-akka.actor.cpu-work-dispatcher-22, start compute(100)
14:42:09: d-akka.actor.blocking-io-dispatcher-16, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.cpu-work-dispatcher-26, start compute(100)
14:42:09: d-akka.actor.blocking-io-dispatcher-21, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.cpu-work-dispatcher-18, start compute(100)
14:42:09: d-akka.actor.blocking-io-dispatcher-13, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.cpu-work-dispatcher-30, start compute(100)
14:42:09: d-akka.actor.cpu-work-dispatcher-15, start compute(100)
14:42:09: d-akka.actor.cpu-work-dispatcher-19, start compute(100)
14:42:09: d-akka.actor.cpu-work-dispatcher-14, start compute(100)
14:42:09: d-akka.actor.cpu-work-dispatcher-20, start compute(100)
14:42:09: d-akka.actor.blocking-io-dispatcher-9, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.blocking-io-dispatcher-11, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.cpu-work-dispatcher-12, start compute(100)
14:42:09: d-akka.actor.blocking-io-dispatcher-28, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.cpu-work-dispatcher-33, start compute(100)
14:42:09: d-akka.actor.blocking-io-dispatcher-32, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.blocking-io-dispatcher-37, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.blocking-io-dispatcher-34, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.blocking-io-dispatcher-38, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.blocking-io-dispatcher-36, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.blocking-io-dispatcher-10, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.blocking-io-dispatcher-35, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.blocking-io-dispatcher-7, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.cpu-work-dispatcher-24, start compute(100)
14:42:09: d-akka.actor.blocking-io-dispatcher-31, start findByKey(optimizationV5-job)
14:42:09: d-akka.actor.cpu-work-dispatcher-27, start compute(100)
14:42:09: d-akka.actor.cpu-work-dispatcher-29, start compute(100)
14:42:09: d-akka.actor.default-dispatcher-4, NonBlockingJobReq(independent of any result)
14:42:09: d-akka.actor.default-dispatcher-4, NonBlockingJobReq(independent of any result)
14:42:09: d-akka.actor.default-dispatcher-8, NonBlockingJobResp(INDEPENDENT OF ANY RESULT)
...
14:43:00: d-akka.actor.default-dispatcher-17, NonBlockingJobResp(20660410)
14:43:00: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(22093932)
14:43:00: d-akka.actor.default-dispatcher-2, NonBlockingJobResp(20728029)
14:43:00: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(db result is optimizationV5-job)
14:43:00: d-akka.actor.default-dispatcher-17, NonBlockingJobResp(22093932)
14:43:00: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(db result is optimizationV5-job)
14:43:00: d-akka.actor.default-dispatcher-2, NonBlockingJobResp(DB RESULT IS OPTIMIZATIONV5-JOB)
14:43:00: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(db result is optimizationV5-job)
14:43:00: d-akka.actor.default-dispatcher-17, NonBlockingJobResp(DB RESULT IS OPTIMIZATIONV5-JOB)
14:43:00: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(db result is optimizationV5-job)
14:43:00: d-akka.actor.default-dispatcher-17, NonBlockingJobResp(DB RESULT IS OPTIMIZATIONV5-JOB)
14:43:00: d-akka.actor.default-dispatcher-3, NonBlockingJobReq(db result is optimizationV5-job)
14:43:00: d-akka.actor.default-dispatcher-2, NonBlockingJobResp(DB RESULT IS OPTIMIZATIONV5-JOB)
14:43:00: d-akka.actor.default-dispatcher-3, NonBlockingJobResp(DB RESULT IS OPTIMIZATIONV5-JOB)
```

线程使用情况：<br/>

![线程使用情况](https://raw.githubusercontent.com/deanzz/akka-dispatcher-test/master/pic/v5.png)

#### 总结<br/>
这种方案的性能与优化方案4的性能相当，因为同样采用了资源隔离和Future的方案，但是这种方案更符合actor的思维模式，也是最为推荐的方式。<br/>

## 最后
请大家牢记在使用akka-actor时，<br/>
1. 尽量使用Future和资源隔离的方案以完成一个非阻塞、异步的系统
2. 尽量避免在actor中使用阻塞IO的的技术，比如数据库驱动，尽量选择非阻塞数据库驱动
3. 尽量避免写出阻塞IO的代码，比如使用Await.result或Await.ready阻塞线程，除非你的场景不得不这样做



