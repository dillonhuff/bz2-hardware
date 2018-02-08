package language

object swhen {
  def apply(cond: StreamBool)(block: => Unit): StreamWhenContext = {
    new StreamWhenContext(cond, !cond, cond, block)
  }
}

object swhile {
  def apply(cond: StreamBool)(block: => Unit): Unit = {
    Builder.curBuilder.startSwhile(cond)
    new StreamWhenContext(cond, null, cond, block)
    Builder.curBuilder.endSwhile()
  }
}

class StreamWhenContext(val cond: StreamBool, prevCond: StreamBool, val soloCond: StreamBool,
                        block: => Unit) {
  def elsewhen(elseCond: StreamBool)(block: => Unit): StreamWhenContext = {
    new StreamWhenContext(prevCond && elseCond, prevCond && !elseCond, elseCond, block)
  }

  def otherwise(block: => Unit): Unit = {
    new StreamWhenContext(prevCond, null, null, block)
  }

  Builder.curBuilder.startContext(this)
  block
  Builder.curBuilder.endContext()
}
