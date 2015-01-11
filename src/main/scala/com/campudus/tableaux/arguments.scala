package com.campudus.tableaux

sealed trait ArgumentCheck

case object OkArg extends ArgumentCheck

case class FailArg(message: String) extends ArgumentCheck

/**
 * @author <a href="http://www.campudus.com">Joern Bernhardt</a>.
 */
object ArgumentChecker {

  def notNull(x: Any): ArgumentCheck = if (x != null) OkArg else FailArg("Argument is null")

  def greaterZero(x: Long): ArgumentCheck = if (x > 0) OkArg else FailArg(s"Argument $x is not greater than zero")

  def checkArguments(args: ArgumentCheck*): Unit = {
    val failedArgs: Vector[String] = args.zipWithIndex.foldLeft(Vector[String]()) {
      case (v, (FailArg(ex), idx)) => v :+ s"($idx) $ex"
      case (v, (OkArg, idx)) => v
    }

    if (failedArgs.nonEmpty) throw new IllegalArgumentException(failedArgs.mkString("\n"))
  }

}
