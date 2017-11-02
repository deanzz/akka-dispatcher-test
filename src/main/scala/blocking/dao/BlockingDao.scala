package blocking.dao

import org.joda.time.DateTime

class BlockingDao {
  def findByKey(key: String): String = {
    println(s"${DateTime.now().toString("HH:mm:ss")}: ${Thread.currentThread().getName}, start findByKey($key)")
    Thread.sleep(10000)
    s"db result is $key"
  }
}
