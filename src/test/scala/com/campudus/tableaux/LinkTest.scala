package com.campudus.tableaux

import org.junit.Test
import org.vertx.testtools.VertxAssert._
import scala.concurrent.Future
import org.vertx.scala.core.json.Json
import org.vertx.scala.core.http.HttpClient
import org.vertx.scala.core.json.JsonObject

class LinkTest extends TableauxTestBase {

  val postLinkCol = Json.obj("columns" -> Json.arr(Json.obj("name" -> "Test Link 1", "kind" -> "link", "fromColumn" -> 1, "toTable" -> 2, "toColumn" -> 1)))

  @Test
  def getLinkColumn(): Unit = okTest {
    val expectedJson = Json.obj("status" -> "ok", "columns" -> Json.arr(Json.obj("id" -> 3, "name" -> "Test Link 1", "kind" -> "link", "toTable" -> 2, "toColumn" -> 1, "ordering" -> 3)))

    for {
      tables <- setupTables()
      _ <- sendRequestWithJson("POST", postLinkCol, "/tables/1/columns")
      test <- sendRequest("GET", "/tables/1/columns/3")
    } yield {
      assertEquals(expectedJson, test)
    }
  }

  @Test
  def createLinkColumn(): Unit = okTest {
    val expectedJson = Json.obj("status" -> "ok", "columns" -> Json.arr(Json.obj("id" -> 3, "ordering" -> 3)))

    for {
      tables <- setupTables()
      test <- sendRequestWithJson("POST", postLinkCol, "/tables/1/columns")
    } yield {
      assertEquals(expectedJson, test)
    }
  }

  @Test
  def fillSingleLinkCell(): Unit = okTest {
    val fillLinkCellJson = Json.obj("cells" -> Json.arr(Json.obj("value" -> Json.arr(1, 1))))
    val expectedJson = Json.obj("status" -> "ok")

    for {
      tables <- setupTables()
      columnId <- sendRequestWithJson("POST", postLinkCol, "/tables/1/columns") map { _.getArray("columns").get[JsonObject](0).getLong("id") }
      test <- sendRequestWithJson("POST", fillLinkCellJson, s"/tables/1/columns/$columnId/rows/1")
    } yield {
      assertEquals(expectedJson, test)
    }
  }

  private def setupTables(): Future[Seq[Long]] = for {
    id1 <- setupDefaultTable()
    id2 <- setupDefaultTable("Test Table 2")
  } yield List(id1, id2)

  private def setupDefaultTable(name: String = "Test Table 1"): Future[Long] = {
    val postTable = Json.obj("tableName" -> name)
    val createStringColumnJson = Json.obj("columns" -> Json.arr(Json.obj("kind" -> "text", "name" -> "Test Column 1")))
    val createNumberColumnJson = Json.obj("columns" -> Json.arr(Json.obj("kind" -> "numeric", "name" -> "Test Column 2")))
    val fillStringCellJson = Json.obj("cells" -> Json.arr(Json.obj("value" -> "Test Fill 1")))
    val fillStringCellJson2 = Json.obj("cells" -> Json.arr(Json.obj("value" -> "Test Fill 2")))
    val fillNumberCellJson = Json.obj("cells" -> Json.arr(Json.obj("value" -> 1)))
    val fillNumberCellJson2 = Json.obj("cells" -> Json.arr(Json.obj("value" -> 2)))

    for {
      tableId <- sendRequestWithJson("POST", postTable, "/tables") map { js => js.getLong("tableId") }
      _ <- sendRequestWithJson("POST", createStringColumnJson, s"/tables/$tableId/columns")
      _ <- sendRequestWithJson("POST", createNumberColumnJson, s"/tables/$tableId/columns")
      _ <- sendRequest("POST", s"/tables/$tableId/rows")
      _ <- sendRequest("POST", s"/tables/$tableId/rows")
      _ <- sendRequestWithJson("POST", fillStringCellJson, s"/tables/$tableId/columns/1/rows/1")
      _ <- sendRequestWithJson("POST", fillStringCellJson2, s"/tables/$tableId/columns/1/rows/2")
      _ <- sendRequestWithJson("POST", fillNumberCellJson, s"/tables/$tableId/columns/2/rows/1")
      _ <- sendRequestWithJson("POST", fillNumberCellJson2, s"/tables/$tableId/columns/2/rows/2")
    } yield tableId
  }
}