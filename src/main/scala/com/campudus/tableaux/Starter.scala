package com.campudus.tableaux

import com.campudus.tableaux.cache.CacheVerticle
import com.campudus.tableaux.database.DatabaseConnection
import com.campudus.tableaux.helper.{FileUtils, VertxAccess}
import com.campudus.tableaux.router.RouterRegistry
import com.typesafe.scalalogging.LazyLogging
import io.vertx.scala.core.http.HttpServer
import io.vertx.scala.core.{DeploymentOptions, Vertx}
import io.vertx.scala.ext.web.Router
import io.vertx.lang.scala.ScalaVerticle
import io.vertx.scala.SQLConnection
import org.vertx.scala.core.json.{Json, JsonObject}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Starter {
  val DEFAULT_HOST = "127.0.0.1"
  val DEFAULT_PORT = 8181

  val DEFAULT_WORKING_DIRECTORY = "./"
  val DEFAULT_UPLOADS_DIRECTORY = "uploads/"
}

class Starter extends ScalaVerticle with LazyLogging {

  private var connection: SQLConnection = _
  private var server: HttpServer = _

  override def startFuture(): Future[Unit] = {
    if (config.isEmpty) {
      logger.error("Provide a config please!")
      Future.failed(new Exception("Provide a config please!"))
    } else if (config.getJsonObject("database", Json.obj()).isEmpty) {
      logger.error("Provide a database config please!")
      Future.failed(new Exception("Provide a database config please!"))
    } else {
      val databaseConfig = config.getJsonObject("database", Json.obj())

      val cacheConfig = config.getJsonObject("cache", Json.obj())
      if (cacheConfig.isEmpty) {
        logger.warn("Cache config is empty, using default settings.")
      }

      val host = getStringDefault(config, "host", Starter.DEFAULT_HOST)
      val port = getIntDefault(config, "port", Starter.DEFAULT_PORT)
      val workingDirectory = getStringDefault(config, "workingDirectory", Starter.DEFAULT_WORKING_DIRECTORY)
      val uploadsDirectory = getStringDefault(config, "uploadsDirectory", Starter.DEFAULT_UPLOADS_DIRECTORY)

      val tableauxConfig = new TableauxConfig(
        vertx = this.vertx,
        databaseConfig = databaseConfig,
        workingDirectory = workingDirectory,
        uploadsDirectory = uploadsDirectory
      )

      connection = SQLConnection(vertxAccessContainer(), databaseConfig)

      for {
        _ <- createUploadsDirectories(tableauxConfig)
        server <- deployHttpServer(port, host, tableauxConfig, connection)
        _ <- deployCacheVerticle(cacheConfig)
      } yield {
        this.server = server
      }
    }
  }

  override def stopFuture(): Future[Unit] = {
    for {
      _ <- connection.close()
      _ <- server.closeFuture()
    } yield ()
  }

  private def createUploadsDirectories(config: TableauxConfig): Future[Unit] = {
    FileUtils(vertxAccessContainer()).mkdirs(config.uploadsDirectoryPath())
  }

  private def deployHttpServer(port: Int,
                               host: String,
                               tableauxConfig: TableauxConfig,
                               connection: SQLConnection): Future[HttpServer] = {
    val dbConnection = DatabaseConnection(vertxAccessContainer(), connection)
    val routerRegistry = RouterRegistry(tableauxConfig, dbConnection)

    val router = Router.router(vertx)

    router.route().handler(routerRegistry.apply)

    vertx
      .createHttpServer()
      .requestHandler(request => router.accept(request))
      .listenFuture(port, host)
  }

  private def deployCacheVerticle(config: JsonObject): Future[String] = {
    val options = DeploymentOptions()
      .setConfig(config)

    val deployFuture = vertx.deployVerticleFuture(ScalaVerticle.nameForVerticle[CacheVerticle], options)

    deployFuture.onComplete({
      case Success(id) =>
        logger.info(s"CacheVerticle deployed with ID $id")
      case Failure(e) =>
        logger.error("CacheVerticle couldn't be deployed.", e)
    })

    deployFuture
  }

  private def getStringDefault(config: JsonObject, field: String, default: String): String = {
    if (config.containsKey(field)) {
      config.getString(field)
    } else {
      logger.warn(s"No $field (config) was set. Use default '$default'.")
      default
    }
  }

  private def getIntDefault(config: JsonObject, field: String, default: Int): Int = {
    if (config.containsKey(field)) {
      config.getInteger(field).toInt
    } else {
      logger.warn(s"No $field (config) was set. Use default '$default'.")
      default
    }
  }

  private def vertxAccessContainer(): VertxAccess = new VertxAccess {
    override val vertx: Vertx = Starter.this.vertx
  }
}
