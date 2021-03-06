package org.vertx.scala.router

import java.io.FileNotFoundException
import java.net.URLEncoder

import com.campudus.tableaux.helper.VertxAccess
import com.typesafe.scalalogging.LazyLogging
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.scala.core.http.{HttpServerRequest, HttpServerResponse}
import io.vertx.scala.ext.web.RoutingContext
import org.vertx.scala.router.routing._

import scala.concurrent.Future
import scala.io.Source
import scala.util.{Failure, Success}

/**
  * The Router trait can be extended to give access to an easy way to write nice routes for your
  * HTTP server.
  *
  * @author <a href="http://www.campudus.com/">Joern Bernhardt</a>
  */
trait Router extends (RoutingContext => Unit) with VertxAccess with LazyLogging {

  type Routing = PartialFunction[RouteMatch, Reply]

  /**
    * Override this method to define your routes for the request handler.
    *
    * @param context The HttpServerRequest that came in.
    * @return A partial function that matches all routes.
    */
  def routes(implicit context: RoutingContext): Routing

  /** The working directory */
  protected def workingDirectory: String = "./"

  /** File to send if the given file in SendFile was not found. */
  protected def notFoundFile: String = "404.html"

  private def matcherFor(routeMatch: RouteMatch, context: RoutingContext): Reply = {
    val pf: PartialFunction[RouteMatch, Reply] = routes(context)
    val tryAllThenNoRouteMatch: Function[RouteMatch, Reply] = _ => {
      pf.applyOrElse(All(context.normalisedPath()), noRouteMatched(context))
    }
    pf.applyOrElse(routeMatch, tryAllThenNoRouteMatch)
  }

  private def noRouteMatched(context: RoutingContext): (RouteMatch => Reply) = { _ =>
    {
      Error(
        RouterException(message =
                          s"No route found for path ${context.request().method().toString} ${context.normalisedPath()}",
                        id = "NOT FOUND",
                        statusCode = 404))
    }
  }

  private def fileExists(file: String): Future[String] = {
    vertx
      .fileSystem()
      .existsFuture(file)
      .flatMap({
        case true => Future.successful(file)
        case false => Future.failed(new FileNotFoundException(file))
      })
  }

  private def addIndexToDirName(path: String): String = {
    if (path.endsWith("/")) path + "index.html"
    else {
      path + "/index.html"
    }
  }

  private def directoryToIndexFile(path: String): Future[String] = {
    vertx
      .fileSystem()
      .lpropsFuture(path)
      .flatMap({ fp =>
        if (fp.isDirectory) {
          fileExists(addIndexToDirName(path))
        } else {
          Future.successful(path)
        }
      })
  }

  private def urlEncode(str: String) = URLEncoder.encode(str, "UTF-8")

  private def endResponse(resp: HttpServerResponse, reply: SyncReply): Unit = {
    reply match {
      case NoBody =>
        resp.end()
      case OkString(string, contentType) =>
        resp.setStatusCode(200)
        resp.setStatusMessage("OK")
        resp.putHeader("Content-type", contentType)
        resp.end(string)
      case Ok(js) =>
        resp.setStatusCode(200)
        resp.setStatusMessage("OK")
        resp.putHeader("Content-type", "application/json")
        resp.end(js.encode())
      case SendEmbeddedFile(path) =>
        try {
          resp.setStatusCode(200)
          resp.setStatusMessage("OK")

          val extension = if (path.contains(".")) {
            path.split("\\.").toList.last.toLowerCase()
          } else {
            "other"
          }

          val byteResponse = extension match {
            case "html" =>
              resp.putHeader("Content-type", "text/html; charset=UTF-8")
              false
            case "js" =>
              resp.putHeader("Content-type", "application/javascript; charset=UTF-8")
              false
            case "json" =>
              resp.putHeader("Content-type", "application/json; charset=UTF-8")
              false
            case "css" =>
              resp.putHeader("Content-type", "text/css; charset= UTF-8")
              false
            case "png" =>
              resp.putHeader("Content-type", "image/png")
              true
            case "gif" =>
              resp.putHeader("Content-type", "image/gif")
              true
            case _ | "txt" =>
              resp.putHeader("Content-type", "text/plain; charset= UTF-8")
              false
          }

          val is = getClass.getResourceAsStream(path)

          if (byteResponse) {
            val bytes = Stream.continually(is.read).takeWhile(_ != -1).map(_.toByte).toArray
            resp.end(Buffer.buffer(bytes))
          } else {
            val file = Source.fromInputStream(is, "UTF-8").mkString
            resp.end(file)
          }
        } catch {
          case ex: Throwable =>
            endResponse(
              resp,
              Error(RouterException("send embedded file exception", ex, "errors.routing.sendEmbeddedFile", 500)))
        }
      case SendFile(path, absolute) =>
        (for {
          exists <- fileExists(if (absolute) path else s"$workingDirectory/$path")
          file <- directoryToIndexFile(exists)
        } yield {
          logger.info(s"Serving file $file after receiving request for: $path")
          resp.sendFile(file)
        }) recover {
          case ex: FileNotFoundException =>
            endResponse(resp, Error(RouterException("File not found", ex, "errors.routing.fileNotFound", 404)))
          case ex =>
            endResponse(resp, Error(RouterException("send file exception", ex, "errors.routing.sendFile", 500)))
        }
      case Error(RouterException(message, cause, id, 404)) =>
        logger.warn(s"Error 404: $message", cause)
        resp.setStatusCode(404)
        resp.setStatusMessage("NOT FOUND")
        message match {
          case null => resp.end()
          case msg => resp.end(msg)
        }
      case Error(RouterException(message, cause, id, statusCode)) =>
        logger.warn(s"Error $statusCode: $message", cause)
        resp.setStatusCode(statusCode)
        resp.setStatusMessage(id)
        message match {
          case null => resp.end()
          case msg => resp.end(msg)
        }
    }
  }

  private def sendReply(req: HttpServerRequest, reply: Reply): Unit = {
    logger.debug(s"Sending back reply as response: $reply")

    reply match {
      case AsyncReply(future) =>
        future.onComplete {
          case Success(r) => sendReply(req, r)
          case Failure(x: RouterException) => endResponse(req.response(), errorReplyFromException(x))
          case Failure(x: Throwable) => endResponse(req.response(), Error(routerException(x)))
        }
      case SetCookie(key, value, nextReply) =>
        req.response().headers().add("Set-Cookie", s"${urlEncode(key)}=${urlEncode(value)}")
        sendReply(req, nextReply)
      case Header(key, value, nextReply) =>
        req.response().putHeader(key, value)
        sendReply(req, nextReply)
      case StatusCode(statusCode, nextReply) =>
        req.response().setStatusCode(statusCode)
        sendReply(req, nextReply)
      case x: SyncReply => endResponse(req.response(), x)
    }
  }

  private def routerException(ex: Throwable): RouterException = ex match {
    case x: RouterException => x
    case x => RouterException(message = x.getMessage, cause = x)
  }

  private def errorReplyFromException(ex: RouterException) = Error(ex)

  /**
    * To be able to use this in `HttpServer.requestHandler()`, the Router needs to be a `HttpServerRequest => Unit`. This
    * apply method starts the magic to be able to use `override def request() = ...` for the routes.
    */
  override final def apply(context: RoutingContext): Unit = {
    val req = context.request()
    logger.info(s"${req.method()}-Request: ${req.uri()}")

    val path = req.path().orNull

    val reply = try {
      val routeMatch: RouteMatch = req.method() match {
        case HttpMethod.GET => Get(path)
        case HttpMethod.PUT => Put(path)
        case HttpMethod.POST => Post(path)
        case HttpMethod.DELETE => Delete(path)
        case HttpMethod.OPTIONS => Options(path)
        case HttpMethod.HEAD => Head(path)
        case HttpMethod.TRACE => Trace(path)
        case HttpMethod.PATCH => Patch(path)
        case HttpMethod.CONNECT => Connect(path)
        case HttpMethod.OTHER => Other(path)
      }

      matcherFor(routeMatch, context)
    } catch {
      case ex: RouterException =>
        errorReplyFromException(ex)
      case ex: Throwable =>
        logger.warn(s"Uncaught Exception for request ${req.absoluteURI()}", ex)
        errorReplyFromException(routerException(ex))
    }

    sendReply(req, reply)
  }

}

/**
  * @author <a href="http://www.campudus.com/">Joern Bernhardt</a>
  */
case class RouterException(message: String = "",
                           cause: Throwable = null,
                           id: String = "UNKNOWN_SERVER_ERROR",
                           statusCode: Int = 500)
    extends Exception(message, cause)
