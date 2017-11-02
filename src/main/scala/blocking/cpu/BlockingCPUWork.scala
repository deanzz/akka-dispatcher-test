package blocking.cpu

class BlockingCPUWork {

  def compute() = {
    (0 until 2000000000).foreach(_ => _)
  }
}
