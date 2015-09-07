package com.campudus.tableaux.router

import com.campudus.tableaux._
import com.campudus.tableaux.database.domain._
import com.campudus.tableaux.database.{EmptyReturn, GetReturn, ReturnType, SetReturn}
import com.campudus.tableaux.helper.StandardVerticle
import org.vertx.scala.core.http.HttpServerRequest
import org.vertx.scala.core.json._
import org.vertx.scala.platform.Verticle
import org.vertx.scala.router.routing.{AsyncReply, Error, Ok}
import org.vertx.scala.router.{Router, RouterException}

import scala.concurrent.{Future, Promise}
import scala.util.matching.Regex

trait BaseRouter extends Router with StandardVerticle {

  val config: TableauxConfig

  /**
   * Regex for a UUID Version 4
   */
  val uuidRegex: String = "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"

  override val verticle: Verticle = config.verticle

  def asyncSetReply: (Future[DomainObject]) => AsyncReply = asyncReply(SetReturn)(_)
  def asyncGetReply: (Future[DomainObject]) => AsyncReply = asyncReply(GetReturn)(_)
  def asyncEmptyReply: (Future[DomainObject]) => AsyncReply = asyncReply(EmptyReturn)(_)

  def asyncReply(reType: ReturnType)(f: => Future[DomainObject]): AsyncReply = AsyncReply {
    f map { d => Ok(Json.obj("status" -> "ok").mergeIn(d.toJson(reType))) } recover {
      case ex@NotFoundInDatabaseException(message, id) => Error(RouterException(message, ex, s"errors.database.$id", 404))
      case ex@DatabaseException(message, id) => Error(RouterException(message, ex, s"errors.database.$id", 500))
      case ex@NoJsonFoundException(message, id) => Error(RouterException(message, ex, s"errors.json.$id", 400))
      case ex@NotEnoughArgumentsException(message, id) => Error(RouterException(message, ex, s"error.json.$id", 400))
      case ex@InvalidJsonException(message, id) => Error(RouterException(message, ex, s"error.json.$id", 400))
      case ex: Throwable => Error(RouterException("unknown error", ex, "errors.unknown", 500))
    }
  }

  def getJson(req: HttpServerRequest): Future[JsonObject] = {
    val p = Promise[JsonObject]()
    req.bodyHandler { buf =>
      buf.length() match {
        case 0 => p.failure(NoJsonFoundException("Warning: No Json found", "not-found"))
        case _ => p.success(Json.fromObjectString(buf.toString()))
      }
    }
    p.future
  }

  def getLongParam(name: String, request: HttpServerRequest): Option[Long] = {
    request.params().find({
      case (n, _) => name == n
    }) match {
      case Some((_, param)) => param.headOption.map(_.toLong)
      case None => None
    }
  }
  
  def getStringParam(name: String, request: HttpServerRequest): Option[String] = {
    request.params().find({
      case (n, _) => name == n
    }) match {
      case Some((_, param)) => param.headOption
      case None => None
    }
  }
}
