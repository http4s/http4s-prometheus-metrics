package org.http4s.metrics.prometheus

import java.io.Writer

/** This should be equivalent to [[java.io.StringWriter]] but uses
  * a [[java.lang.StringBuilder]] rather than a [[java.lang.StringBuffer]]
  * and consequently is not `synchronized
  */
private[prometheus] class NonSafepointingStringWriter extends Writer {

  private[this] val buf: StringBuilder = new StringBuilder()

  override def write(str: String): Unit = {
    buf.append(str)
    ()
  }

  override def write(buff: Array[Char]): Unit = {
    buf.append(buff)
    ()
  }

  override def write(i: Int): Unit = {
    buf.append(i)
    ()
  }

  override def write(str: String, start: Int, limit: Int): Unit = write(str.slice(start, limit))

  override def write(buff: Array[Char], start: Int, limit: Int): Unit = write(
    buff.slice(start, limit)
  )

  override def append(c: Char): Writer = {
    buf.append(c)
    this
  }

  override def append(cs: CharSequence): Writer = {
    buf.append(cs)
    this
  }

  override def append(cs: CharSequence, start: Int, limit: Int): Writer =
    // ¯\_(ツ)_/¯
    if (cs == null) append("null")
    else
      append(cs.subSequence(start, limit))

  override def flush(): Unit = ()

  override def close(): Unit = ()

  override def toString(): String = buf.toString()

}
