package com.campudus.tableaux.database

import com.campudus.tableaux.DatabaseException
import com.campudus.tableaux.database.domain.DomainObject
import com.campudus.tableaux.database.model.FolderModel._
import com.campudus.tableaux.helper.ResultChecker._
import com.campudus.tableaux.helper.VertxAccess
import com.typesafe.scalalogging.LazyLogging
import io.vertx.ext.sql.{ResultSet, UpdateResult}
import io.vertx.scala._
import org.joda.time.DateTime
import org.vertx.scala.core.json.{Json, JsonArray, JsonCompatible, JsonObject}

import scala.concurrent.Future
import scala.util.Random

trait DatabaseQuery extends JsonCompatible with LazyLogging {
  protected[this] val connection: DatabaseConnection

  implicit val executionContext = connection.executionContext
}

sealed trait DatabaseHelper {

  implicit def con(id: java.lang.Long): Option[FolderId] = {
    id.toLong
  }

  implicit def convertLongToFolderId(id: Long): Option[FolderId] = {
    //TODO still, not cool!
    Option(id).filter(_ != 0)
  }

  implicit def convertStringToDateTime(str: String): Option[DateTime] = {
    Option(str).map(DateTime.parse)
  }
}

trait DatabaseHandler[O <: DomainObject, ID] extends DatabaseQuery with DatabaseHelper {
  def add(o: O): Future[O]

  def retrieve(id: ID): Future[O]

  def retrieveAll(): Future[Seq[O]]

  def update(o: O): Future[O]

  def delete(o: O): Future[Unit]

  def deleteById(id: ID): Future[Unit]

  def size(): Future[Long]
}

object DatabaseConnection {
  val DEFAULT_TIMEOUT = 5000L

  type ScalaTransaction = io.vertx.scala.Transaction

  def apply(verticle: ScalaVerticle, connection: SQLConnection): DatabaseConnection = {
    new DatabaseConnection(verticle, connection)
  }
}

class DatabaseConnection(val verticle: ScalaVerticle, val connection: SQLConnection) extends VertxAccess with LazyLogging {

  import DatabaseConnection._

  type TransFunc[+A] = Transaction => Future[(Transaction, A)]

  case class Transaction(transaction: ScalaTransaction) {

    def query(stmt: String): Future[(Transaction, JsonObject)] = {
      doMagicQuery(stmt, None, transaction).map(result => (copy(transaction), result))
    }

    def query(stmt: String, values: JsonArray): Future[(Transaction, JsonObject)] = {
      doMagicQuery(stmt, Some(values), transaction).map(result => (copy(transaction), result))
    }

    def commit(): Future[Unit] = transaction.commit()

    def rollback(): Future[Unit] = transaction.rollback()

    def rollbackAndFail(): PartialFunction[Throwable, Future[(Transaction, JsonObject)]] = {
      case ex: Throwable =>
        logger.error(s"Rollback and fail.", ex)
        rollback() flatMap (_ => Future.failed[(Transaction, JsonObject)](ex))
    }
  }

  def query(stmt: String): Future[JsonObject] = {
    doMagicQuery(stmt, None, connection)
  }

  def query(stmt: String, parameter: JsonArray): Future[JsonObject] = {
    doMagicQuery(stmt, Some(parameter), connection)
  }

  def begin(): Future[Transaction] = connection.transaction().map(Transaction)

  def transactional[A](fn: TransFunc[A]): Future[A] = {
    import com.campudus.tableaux.helper.TimeoutScheduler._

    import scala.concurrent.duration.DurationInt

    val random = Random.nextInt()

    for {
      transaction <- begin().withTimeout(DurationInt(1).seconds, s"Transaction-Begin")

      (transaction, result) <- {
        fn(transaction) recoverWith {
          case e =>
            logger.error("Failed executing transactional. Rollback and fail.", e)
            transaction.rollback()
            Future.failed(e)
        }
      }.withTimeout(DurationInt(1).seconds, s"Transactional-Fn $random")

      _ <- {
        transaction.commit().withTimeout(DurationInt(2).seconds, s"Transactional-Commit $random")
      }
    } yield {
      result
    }
  }

  def transactionalFoldLeft[A](values: Seq[A])(fn: (Transaction, JsonObject, A) => Future[(Transaction, JsonObject)]): Future[JsonObject] = {
    transactionalFoldLeft(values, Json.emptyObj())(fn)
  }

  def transactionalFoldLeft[A, B](values: Seq[A], fnStartValue: B)(fn: (Transaction, B, A) => Future[(Transaction, B)]): Future[B] = {
    transactional[B]({ transaction: Transaction =>
      values.foldLeft(Future(transaction, fnStartValue)) {
        (result, value) =>
          result.flatMap {
            case (newTransaction, lastResult) =>
              fn(newTransaction, lastResult, value)
          }
      }
    })
  }

  def selectSingleValue[A](select: String): Future[A] = {
    for {
      resultJson <- query(select)
      resultRow = selectNotNull(resultJson).head
    } yield {
      resultRow.getValue(0).asInstanceOf[A]
    }
  }

  private def doMagicQuery(stmt: String, values: Option[JsonArray], connection: DatabaseAction): Future[JsonObject] = {
    val command = stmt.trim().split("\\s+").head.toUpperCase
    val returning = stmt.trim().toUpperCase.contains("RETURNING")

    val future = (command, returning) match {
      case ("CREATE", _) | ("DROP", _) | ("ALTER", _) =>
        connection.execute(stmt)
      case ("UPDATE", true) =>
        values match {
          case Some(s) => connection.query(stmt + ";--", s)
          case None => connection.query(stmt + ";--")
        }
      case ("INSERT", true) | ("SELECT", _) =>
        values match {
          case Some(s) => connection.query(stmt, s)
          case None => connection.query(stmt)
        }
      case ("DELETE", true) =>
        values match {
          case Some(s) => connection.update(stmt + ";--", s)
          case None => connection.update(stmt + ";--")
        }
      case ("DELETE", false) | ("INSERT", false) | ("UPDATE", false) =>
        values match {
          case Some(s) => connection.update(stmt, s)
          case None => connection.update(stmt)
        }
      case (_, _) =>
        throw new DatabaseException(s"Command $command in Statement $stmt not supported", "error.database.command_not_supported")
    }

    import io.vertx.scala.FunctionConverters._
    val timerId = vertx.setTimer(10000, { d: java.lang.Long => logger.error(s"doMagicQuery $command $returning $stmt exceeded the delay") })

    future.map({
      case r: UpdateResult => {
        vertx.cancelTimer(timerId)

        mapUpdateResult(command, r.toJson)
      }
      case r: ResultSet => {
        vertx.cancelTimer(timerId)

        mapResultSet(r.toJson)
      }
      case _ => {
        vertx.cancelTimer(timerId)

        createExecuteResult(command)
      }
    })
  }

  private def createExecuteResult(msg: String): JsonObject = {
    Json.obj(
      "status" -> "ok",
      "message" -> msg,
      "rows" -> 0
    )
  }

  private def mapUpdateResult(msg: String, obj: JsonObject): JsonObject = {
    import scala.collection.JavaConversions._

    val updated = obj.getInteger("updated", 0)
    val keys = obj.getJsonArray("keys", Json.arr())

    val fields = if (keys.size() >= 1) {
      Json.arr("no_name")
    } else {
      Json.arr()
    }

    val results = new JsonArray(keys.getList.toList.map({ v: Any => Json.arr(v) }))

    Json.obj(
      "status" -> "ok",
      "rows" -> updated,
      "message" -> s"${msg.toUpperCase} $updated",
      "fields" -> fields,
      "results" -> results
    )
  }

  private def mapResultSet(obj: JsonObject): JsonObject = {
    val columnNames = obj.getJsonArray("columnNames", Json.arr())
    val results = obj.getJsonArray("results", Json.arr())

    Json.obj(
      "status" -> "ok",
      "rows" -> results.size(),
      "message" -> s"SELECT ${results.size()}",
      "fields" -> columnNames,
      "results" -> results
    )
  }
}