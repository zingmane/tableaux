package com.campudus.tableaux.cache

import java.util.concurrent.TimeUnit

import com.campudus.tableaux.database.model.TableauxModel.{ColumnId, TableId}
import com.google.common.cache.CacheBuilder
import io.vertx.core.eventbus.Message
import io.vertx.scala.ScalaVerticle
import org.vertx.scala.core.json.{Json, JsonObject}

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.language.implicitConversions
import scalacache._
import scalacache.guava._
import scalacache.serialization.InMemoryRepr

object CacheVerticle {
  /**
    * Default never expire
    */
  val DEFAULT_EXPIRE_AFTER_ACCESS = -1l

  /**
    * Max. 10k cached values per column
    */
  val DEFAULT_MAXIMUM_SIZE = 10000l

  val NOT_FOUND_FAILURE = 404
  val INVALID_MESSAGE = 400

  val ADDRESS_SET = "cache.set"
  val ADDRESS_RETRIEVE = "cache.retrieve"

  val ADDRESS_INVALIDATE_CELL = "cache.invalidate.cell"
  val ADDRESS_INVALIDATE_COLUMN = "cache.invalidate.column"
  val ADDRESS_INVALIDATE_ROW = "cache.invalidate.row"
  val ADDRESS_INVALIDATE_TABLE = "cache.invalidate.table"
  val ADDRESS_INVALIDATE_ALL = "cache.invalidate.all"
}

class CacheVerticle extends ScalaVerticle {

  import CacheVerticle._

  lazy val eventBus = vertx.eventBus()

  val caches: mutable.Map[(TableId, ColumnId), ScalaCache[InMemoryRepr]] = mutable.Map.empty

  override def start(promise: Promise[Unit]): Unit = {
    registerOnEventBus()

    promise.success(())
  }

  override def stop(promise: Promise[Unit]): Unit = {
    promise.success(())
  }

  private def registerOnEventBus(): Unit = {
    import io.vertx.scala.FunctionConverters._

    eventBus.localConsumer(ADDRESS_SET, messageHandlerSet(_: Message[JsonObject]))
    eventBus.localConsumer(ADDRESS_RETRIEVE, messageHandlerRetrieve(_: Message[JsonObject]))

    eventBus.localConsumer(ADDRESS_INVALIDATE_CELL, messageHandlerInvalidateCell(_: Message[JsonObject]))
    eventBus.localConsumer(ADDRESS_INVALIDATE_COLUMN, messageHandlerInvalidateColumn(_: Message[JsonObject]))
    eventBus.localConsumer(ADDRESS_INVALIDATE_ROW, messageHandlerInvalidateRow(_: Message[JsonObject]))
    eventBus.localConsumer(ADDRESS_INVALIDATE_TABLE, messageHandlerInvalidateTable(_: Message[JsonObject]))
    eventBus.localConsumer(ADDRESS_INVALIDATE_ALL, messageHandlerInvalidateAll(_: Message[JsonObject]))
  }

  private def getCache(tableId: TableId, columnId: ColumnId): ScalaCache[InMemoryRepr] = {
    def createCache() = {
      val builder = CacheBuilder
        .newBuilder()

      val expireAfterAccess = config().getLong("expireAfterAccess", DEFAULT_EXPIRE_AFTER_ACCESS).toLong
      if (expireAfterAccess > 0) {
        builder.expireAfterAccess(expireAfterAccess, TimeUnit.SECONDS)
      } else {
        logger.info("Cache will not expire!")
      }

      val maximumSize = config().getLong("maximumSize", DEFAULT_MAXIMUM_SIZE).toLong
      if (maximumSize > 0) {
        builder.maximumSize(config().getLong("maximumSize", DEFAULT_MAXIMUM_SIZE).toLong)
      }

      builder.recordStats()

      builder.build[String, Object]
    }

    caches.get((tableId, columnId)) match {
      case Some(cache) => cache
      case None =>
        val cache = ScalaCache(GuavaCache(createCache()))
        caches.put((tableId, columnId), cache)
        cache
    }
  }

  private def removeCache(tableId: TableId, columnId: ColumnId): Unit = {
    caches.remove((tableId, columnId))
  }

  private def messageHandlerSet(message: Message[JsonObject]): Unit = {
    val obj = message.body()

    val value = obj.getValue("value")

    (for {
      tableId <- Option(obj.getLong("tableId")).map(_.toLong)
      columnId <- Option(obj.getLong("columnId")).map(_.toLong)
      rowId <- Option(obj.getLong("rowId")).map(_.toLong)
    } yield (tableId, columnId, rowId)) match {
      case Some((tableId, columnId, rowId)) =>

        implicit val scalaCache = getCache(tableId, columnId)
        put(rowId)(value)
          .map({
            _ =>
              val reply = Json.obj(
                "tableId" -> tableId,
                "columnId" -> columnId,
                "rowId" -> rowId
              )

              message.reply(reply)
          })

      case None =>
        logger.error("Message invalid: Fields (tableId, columnId, rowId) should be a Long")
        message.fail(INVALID_MESSAGE, "Message invalid: Fields (tableId, columnId, rowId) should be a Long")
    }
  }

  private def messageHandlerRetrieve(message: Message[JsonObject]): Unit = {
    val obj = message.body()

    (for {
      tableId <- Option(obj.getLong("tableId")).map(_.toLong)
      columnId <- Option(obj.getLong("columnId")).map(_.toLong)
      rowId <- Option(obj.getLong("rowId")).map(_.toLong)
    } yield (tableId, columnId, rowId)) match {
      case Some((tableId, columnId, rowId)) =>

        implicit val scalaCache = getCache(tableId, columnId)

        get[AnyRef, NoSerialization](rowId)
          .map({
            case Some(value) =>
              val reply = Json.obj(
                "tableId" -> tableId,
                "columnId" -> columnId,
                "rowId" -> rowId,
                "value" -> value
              )

              message.reply(reply)
            case None =>
              logger.debug(s"messageHandlerRetrieve $tableId, $columnId, $rowId not found")
              message.fail(NOT_FOUND_FAILURE, "Not found")
          })

      case None =>
        logger.error("Message invalid: Fields (tableId, columnId, rowId) should be a Long")
        message.fail(INVALID_MESSAGE, "Message invalid: Fields (tableId, columnId, rowId) should be a Long")
    }
  }

  private def messageHandlerInvalidateCell(message: Message[JsonObject]): Unit = {
    val obj = message.body()

    (for {
      tableId <- Option(obj.getLong("tableId")).map(_.toLong)
      columnId <- Option(obj.getLong("columnId")).map(_.toLong)
      rowId <- Option(obj.getLong("rowId")).map(_.toLong)
    } yield (tableId, columnId, rowId)) match {
      case Some((tableId, columnId, rowId)) =>
        // invalidate cell
        implicit val scalaCache = getCache(tableId, columnId)

        remove(rowId)
          .map({
            _ =>
              val reply = Json.obj(
                "tableId" -> tableId,
                "columnId" -> columnId,
                "rowId" -> rowId
              )

              message.reply(reply)
          })

      case None =>
        logger.error("Message invalid: Fields (tableId, columnId, rowId) should be a Long")
        message.fail(INVALID_MESSAGE, "Message invalid: Fields (tableId, columnId, rowId) should be a Long")
    }
  }

  private def messageHandlerInvalidateColumn(message: Message[JsonObject]): Unit = {
    val obj = message.body()

    (for {
      tableId <- Option(obj.getLong("tableId")).map(_.toLong)
      columnId <- Option(obj.getLong("columnId")).map(_.toLong)
    } yield (tableId, columnId)) match {
      case Some((tableId, columnId)) =>
        // invalidate column
        implicit val scalaCache = getCache(tableId, columnId)

        removeAll()
          .map({
            _ =>
              removeCache(tableId, columnId)

              val reply = Json.obj(
                "tableId" -> tableId,
                "columnId" -> columnId
              )

              message.reply(reply)
          })

      case None =>
        logger.error("Message invalid: Fields (tableId, columnId) should be a Long")
        message.fail(INVALID_MESSAGE, "Message invalid: Fields (tableId, columnId) should be a Long")
    }
  }

  private def messageHandlerInvalidateRow(message: Message[JsonObject]): Unit = {
    val obj = message.body()

    (for {
      tableId <- Option(obj.getLong("tableId")).map(_.toLong)
      rowId <- Option(obj.getLong("rowId")).map(_.toLong)
    } yield (tableId, rowId)) match {
      case Some((tableId, rowId)) =>
        // invalidate table
        val filterdCaches = caches
          .filter(_._1._1 == tableId)
          .values

        Future.sequence(filterdCaches
          .map({
            cache =>
              implicit val scalaCache = cache
              remove(rowId)
          }))
          .map({
            _ =>
              val reply = Json.obj(
                "tableId" -> tableId,
                "rowId" -> rowId
              )

              message.reply(reply)
          })

      case None =>
        logger.error("Message invalid: Fields (tableId, rowId) should be a Long")
        message.fail(INVALID_MESSAGE, "Message invalid: Fields (tableId, rowId) should be a Long")
    }
  }

  private def messageHandlerInvalidateTable(message: Message[JsonObject]): Unit = {
    val obj = message.body()

    (for {
      tableId <- Option(obj.getLong("tableId")).map(_.toLong)
    } yield tableId) match {
      case Some(tableId) =>
        // invalidate table
        val filterdCaches = caches
          .filter(_._1._1 == tableId)
          .values

        Future.sequence(filterdCaches
          .map({
            cache =>
              implicit val scalaCache = cache
              removeAll()
          }))
          .map({
            _ =>
              val reply = Json.obj(
                "tableId" -> tableId
              )

              message.reply(reply)
          })

      case None =>
        logger.error("Message invalid: Fields (tableId, columnId) should be a Long")
        message.fail(INVALID_MESSAGE, "Message invalid: Fields (tableId, columnId) should be a Long")
    }
  }

  private def messageHandlerInvalidateAll(message: Message[JsonObject]): Unit = {
    Future.sequence(caches.map({
      case ((tableId, columnId), cache) =>
        removeAll()(cache)
          .map({
            _ =>
              removeCache(tableId, columnId)
          })
    })).onComplete({
      case _ =>
        caches.clear()

        message.reply(Json.emptyObj())
    })
  }
}