package blocking.cpu

import org.joda.time.DateTime

class BlockingCPUWorker {

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
