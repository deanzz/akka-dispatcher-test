# akka-actor如何最大化利用单台机器上的硬件资源
>这次给大家分享一下使用akka的actor如何最大化利用单台机器上的硬件资源。<br/>
根据摩尔定律，每隔大约18个月，cpu每一单位面积的晶体管数量就会增加一倍，也就是cpu时钟速度增加一倍。<br/>
不过如今已经今非其比了，越来越多的cpu以多核来吸引眼球，多核时代早已到来。<br/>
所以要最大化利用单台机器上的硬件资源，其中很重要的一点就是榨干机器上的cpu资源。<br/>

## 一个糟糕的例子
我们从一个用akka-actor写的糟糕的例子开始我们的优化之旅。<br/>
假设我们想完成这样一些工作:
1. 查询`1次`数据库获取数据，显示结果（阻塞IO操作）
2. 跑`2个`计算量较大的算法，显示结果（cpu密集型操作）
3. 内存中查询`40次`结果，并显示（非阻塞IO操作）<br/>

根据需求我们写出了如下几个类：

1. BlockingJobActor，干活的actor，接收一个NewJob消息处理上面所说的3个工作
2. NonBlockingJobActor，显示一些字符串的actor，接收一个NonBlockingJobReq消息显示传入的字符串
3. BlockingDao，模拟数据库查询的类，调用findByKey方法，会模拟阻塞等待10秒查询结果
4. BlockingCPUWorker，模拟运行算法的类，调用compute方法，会让cpu满负荷运转5秒
5. Launcher，程序运行的入口，在这里切换调用各种优化方法
6. TimerActor，用于计算程序执行时间的actor
7. blocking.conf，akka的配置文件

下面我们来看看主要代码<br/>

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
```json
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

下面我们来看看运行结果以及现场的使用情况<br/>
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
17:23:54: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(67664446)
17:23:54: d-akka.actor.default-dispatcher-4, start findByKey(blocking-job)
17:23:54: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
...
17:23:55: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(independent of any result)
17:24:04: d-akka.actor.default-dispatcher-2, NonBlockingJobReq(db result is blocking-job)
```

线程使用情况<br/>
![部署目录]()