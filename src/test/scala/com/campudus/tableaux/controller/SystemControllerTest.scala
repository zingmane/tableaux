package com.campudus.tableaux.controller

import com.campudus.tableaux.TableauxTestBase
import com.campudus.tableaux.router.SystemRouter
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.vertx.scala.core.json.Json

@RunWith(classOf[VertxUnitRunner])
class SystemControllerTest extends TableauxTestBase {

  @Test
  def resetSystemTables(implicit c: TestContext): Unit = okTest {
    val expectedJson = Json.obj("status" -> "ok")

    val nonce = SystemRouter.generateNonce()

    for {
      result <- sendRequest("POST", s"/reset?nonce=$nonce")
    } yield {
      assertEquals(expectedJson, result)
    }
  }

  @Test
  def resetDemoData(implicit c: TestContext): Unit = okTest {
    val expectedJson1 = Json.obj(
      "status" -> "ok",
      "tables" -> Json.arr(
        Json.obj("id" -> 1),
        Json.obj("id" -> 2)
      )
    )

    val expectedJson2 = Json.obj(
      "status" -> "ok",
      "tables" -> Json.arr(
        Json.obj("id" -> 1, "name" -> "Bundesländer Deutschlands", "hidden" -> false),
        Json.obj("id" -> 2, "name" -> "Regierungsbezirke", "hidden" -> false)
      )
    )

    for {
      _ <- {
        val nonce = SystemRouter.generateNonce()
        sendRequest("POST", s"/reset?nonce=$nonce")
      }
      result <- {
        val nonce = SystemRouter.generateNonce()
        sendRequest("POST", s"/resetDemo?nonce=$nonce")
      }
      tablesResult <- sendRequest("GET", "/tables")
    } yield {
      assertEquals(expectedJson1, result)
      assertEquals(expectedJson2, tablesResult)
    }
  }

  @Test
  def retrieveVersions(implicit c: TestContext): Unit = okTest {
    val expectedJson = Json.obj(
      "versions" -> Json.obj(
        "implementation" -> "DEVELOPMENT",
        "database" -> Json.obj(
          "current" -> 4,
          "specification" -> 4
        )
      ),
      "status" -> "ok"
    )

    for {
      _ <- {
        val nonce = SystemRouter.generateNonce()
        sendRequest("POST", s"/reset?nonce=$nonce")
      }
      _ <- {
        val nonce = SystemRouter.generateNonce()
        sendRequest("POST", s"/resetDemo?nonce=$nonce")
      }
      versions <- sendRequest("GET", "/system/versions")
    } yield {
      assertEquals(expectedJson, versions)
    }
  }

  @Test
  def resetWithOutNonce(implicit c: TestContext): Unit = exceptionTest("error.nonce.none") {
    SystemRouter.nonce = null
    sendRequest("POST", s"/reset")
  }

  @Test
  def resetWithNonceButInvalidRequestNonce(implicit c: TestContext): Unit = exceptionTest("error.nonce.invalid") {
    val nonce = SystemRouter.generateNonce()
    sendRequest("POST", s"/reset?nonce=asdf")
  }
}
