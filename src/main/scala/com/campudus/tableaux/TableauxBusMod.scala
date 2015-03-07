package com.campudus.tableaux

import org.vertx.scala.mods.ScalaBusMod
import org.vertx.scala.core.eventbus.Message
import org.vertx.scala.core.json.{ JsonObject, JsonArray }
import org.vertx.scala.mods.replies._
import org.vertx.scala.platform.Verticle
import scala.concurrent.Future
import com.campudus.tableaux.database.DomainObject
import com.campudus.tableaux.HelperFunctions._
import com.campudus.tableaux.database._

class TableauxBusMod(verticle: Verticle) extends ScalaBusMod {
  val container = verticle.container
  val logger = verticle.logger
  val vertx = verticle.vertx

  val controller = new TableauxController(verticle)

  def receive(): Message[JsonObject] => PartialFunction[String, BusModReceiveEnd] = msg => {
    case "reset" => getAsyncReply(SetReturn)(controller.resetDB())
    case "getTable" => getAsyncReply(GetReturn)(controller.getTable(getInfo[Long](msg, "tableId")))
    case "getColumn" => getAsyncReply(GetReturn)(controller.getColumn(getInfo[Long](msg, "tableId"), getInfo[Long](msg, "columns")))
    case "getRow" => getAsyncReply(GetReturn)(controller.getRow(getInfo[Long](msg, "tableId"), getInfo[Long](msg, "rows")))
    case "getCell" => getAsyncReply(GetReturn)(controller.getCell(getInfo[Long](msg, "tableId"), getInfo[Long](msg, "columns"), getInfo[Long](msg, "rows")))
    case "createTable" => getAsyncReply(SetReturn) {
      import scala.collection.JavaConverters._
      if (msg.body().getFieldNames.asScala.toSeq.contains("columns")) {
        if (msg.body().getFieldNames.asScala.toSeq.contains("rows")) {
          controller.createTable(getInfo[String](msg, "tableName"), jsonToSeqOfColumnNameAndType(msg.body()), jsonToSeqOfRowsWithValue(msg.body()))
        } else {
          controller.createTable(getInfo[String](msg, "tableName"), jsonToSeqOfColumnNameAndType(msg.body()), Seq())
        }
      } else {
        controller.createTable(getInfo[String](msg, "tableName"))
      }
    }
    case "createColumn" => getAsyncReply(SetReturn) {
      controller.createColumn(getInfo[Long](msg, "tableId"), jsonToSeqOfColumnNameAndType(msg.body()))
    }
    case "createRow" => getAsyncReply(SetReturn)(controller.createRow(getInfo[Long](msg, "tableId"), Option(jsonToSeqOfRowsWithColumnIdAndValue(msg.body()))))
    case "fillCell" => getAsyncReply(SetReturn)(controller.fillCell(getInfo[Long](msg, "tableId"), getInfo[Long](msg, "column"), getInfo[Long](msg, "row"), jsonToValues(msg.body())))
    case "deleteTable" => getAsyncReply(DeleteReturn)(controller.deleteTable(getInfo[Long](msg, "tableId")))
    case "deleteColumn" => getAsyncReply(DeleteReturn)(controller.deleteColumn(getInfo[Long](msg, "tableId"), getInfo[Long](msg, "columns")))
    case "deleteRow" => getAsyncReply(DeleteReturn)(controller.deleteRow(getInfo[Long](msg, "tableId"), getInfo[Long](msg, "rows")))
    case _ => throw new IllegalArgumentException("Unknown action")
  }

  private def getInfo[A](msg: Message[JsonObject], name: String): A = name match {
    case "tableName" | "tableId" => get[A](msg, name)
    case "columns" | "rows" =>
      val column = get[JsonArray](msg, name).get[JsonObject](0)
      get[A](column, "id")
    case "column" | "row" =>
      val cell = get[JsonArray](msg, "cells").get[JsonObject](0)
      val column = get[JsonObject](cell, name)
      get[A](column, "id")
  }

  private def get[A](msg: Message[JsonObject], name: String): A = msg.body.getField[A](name)

  private def get[A](msg: JsonObject, name: String): A = msg.getField[A](name)

  private def getAsyncReply(reType: ReturnType)(f: => Future[DomainObject]): AsyncReply = AsyncReply {
    f map { d => Ok(d.toJson(reType)) } recover {
      case ex @ NotFoundInDatabaseException(message, id) => Error(message, s"errors.database.$id")
      case ex @ DatabaseException(message, id) => Error(message, s"errors.database.$id")
      case ex @ NoJsonFoundException(message, id) => Error(message, s"errors.json.$id")
      case ex @ NotEnoughArgumentsException(message, id) => Error(message, s"errors.json.$id")
      case ex @ InvalidJsonException(message, id) => Error(message, s"error.json.$id")
      case ex: Throwable => Error("unknown error", "errors.unknown")
    }
  }

}
