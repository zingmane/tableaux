package com.campudus.tableaux.api.content

import com.campudus.tableaux.testtools.TableauxTestBase
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.vertx.scala.core.json.Json

@RunWith(classOf[VertxUnitRunner])
class CellLevelAnnotationsTest extends TableauxTestBase {

  @Test
  def addAnnotationWithoutLangtags(implicit c: TestContext): Unit = {
    okTest{
      for {
        tableId <- createEmptyDefaultTable()

        // empty row
        result <- sendRequest("POST", s"/tables/$tableId/rows")
        rowId = result.getLong("id")

        _ <- sendRequest("POST",
          s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("type" -> "info", "value" -> "this is a comment"))

        rowJson1 <- sendRequest("GET", s"/tables/$tableId/rows/$rowId")
      } yield {
        val exceptedColumn1Flags = Json.arr(Json.obj("type" -> "info", "value" -> "this is a comment"))

        assertContainsDeep(exceptedColumn1Flags, rowJson1.getJsonArray("annotations").getJsonArray(0))
        assertNull(rowJson1.getJsonArray("annotations").getJsonArray(1))
      }
    }
  }

  @Test
  def addAndDeleteMultipleAnnotationsWithoutLangtags(implicit c: TestContext): Unit = {
    okTest{
      for {
        tableId <- createEmptyDefaultTable()

        // empty row
        result <- sendRequest("POST", s"/tables/$tableId/rows")
        rowId = result.getLong("id")

        _ <- sendRequest("POST", s"/tables/$tableId/columns/1/rows/$rowId/annotations", Json.obj("type" -> "error"))
        _ <- sendRequest("POST",
          s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("type" -> "info", "value" -> "this is a comment"))
        _ <- sendRequest("POST", s"/tables/$tableId/columns/2/rows/$rowId/annotations", Json.obj("type" -> "warning"))
        _ <- sendRequest("POST",
          s"/tables/$tableId/columns/2/rows/$rowId/annotations",
          Json.obj("type" -> "error", "value" -> "this is another comment"))

        rowJson1 <- sendRequest("GET", s"/tables/$tableId/rows/$rowId")

        uuid = rowJson1.getJsonArray("annotations").getJsonArray(0).getJsonObject(0).getString("uuid")
        _ <- sendRequest("DELETE", s"/tables/$tableId/columns/1/rows/$rowId/annotations/$uuid")

        rowJson2 <- sendRequest("GET", s"/tables/$tableId/rows/$rowId")
      } yield {
        val exceptedColumn1Flags = Json
          .arr(Json.obj("type" -> "error", "value" -> null), Json.obj("type" -> "info", "value" -> "this is a comment"))
        val exceptedColumn2Flags = Json
          .arr(Json.obj("type" -> "warning", "value" -> null),
            Json.obj("type" -> "error", "value" -> "this is another comment"))

        assertContainsDeep(exceptedColumn1Flags, rowJson1.getJsonArray("annotations").getJsonArray(0))
        assertContainsDeep(exceptedColumn2Flags, rowJson1.getJsonArray("annotations").getJsonArray(1))

        val exceptedColumn1FlagsAfterDelete = Json.arr(Json.obj("type" -> "info", "value" -> "this is a comment"))

        assertContainsDeep(exceptedColumn1FlagsAfterDelete, rowJson2.getJsonArray("annotations").getJsonArray(0))
      }
    }
  }

  @Test
  def addMultipleAnnotationsWithLangtags(implicit c: TestContext): Unit = {
    okTest{
      for {
        (tableId, _) <- createTableWithMultilanguageColumns("Test")

        // empty row
        result <- sendRequest("POST", s"/tables/$tableId/rows")
        rowId = result.getLong("id")

        _ <- sendRequest("POST",
          s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("de"), "type" -> "error"))
        _ <- sendRequest("POST",
          s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("gb"), "type" -> "info", "value" -> "this is a comment"))
        _ <- sendRequest("POST",
          s"/tables/$tableId/columns/2/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("gb"), "type" -> "warning"))
        _ <- sendRequest("POST",
          s"/tables/$tableId/columns/2/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("de"), "type" -> "error", "value" -> "this is another comment"))

        rowJson1 <- sendRequest("GET", s"/tables/$tableId/rows/$rowId")
      } yield {
        val exceptedColumn1Flags = Json
          .arr(Json.obj("langtags" -> Json.arr("de"), "type" -> "error", "value" -> null),
            Json.obj("langtags" -> Json.arr("gb"), "type" -> "info", "value" -> "this is a comment"))
        val exceptedColumn2Flags = Json
          .arr(Json.obj("langtags" -> Json.arr("gb"), "type" -> "warning", "value" -> null),
            Json.obj("langtags" -> Json.arr("de"), "type" -> "error", "value" -> "this is another comment"))

        assertContainsDeep(exceptedColumn1Flags, rowJson1.getJsonArray("annotations").getJsonArray(0))
        assertContainsDeep(exceptedColumn2Flags, rowJson1.getJsonArray("annotations").getJsonArray(1))
      }
    }
  }

  @Test
  def addAnnotationWithLangtagsOnLanguageNeutralCell(implicit c: TestContext): Unit = {
    exceptionTest(
      "unprocessable.entity"){
      for {
        tableId <- createDefaultTable("Test")

        // empty row
        result <- sendRequest("POST", s"/tables/$tableId/rows")
        rowId = result.getLong("id")

        _ <- sendRequest("POST",
          s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("de"), "type" -> "error"))

      } yield ()
    }
  }

  @Test
  def addInvalidAnnotations(implicit c: TestContext): Unit = {
    exceptionTest("error.arguments"){
      for {
        tableId <- createDefaultTable("Test")

        // empty row
        result <- sendRequest("POST", s"/tables/$tableId/rows")
        rowId = result.getLong("id")

        _ <- sendRequest("POST", s"/tables/$tableId/columns/1/rows/$rowId/annotations", Json.obj("type" -> "invalid"))

      } yield ()
    }
  }

  @Test
  def addSameAnnotationsWithDifferentLangtags(implicit c: TestContext): Unit = {
    okTest{
      for {
        (tableId, _) <- createTableWithMultilanguageColumns("Test")

        // empty row
        result <- sendRequest("POST", s"/tables/$tableId/rows")
        rowId = result.getLong("id")

        _ <- sendRequest("POST", s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("de", "gb"), "type" -> "flag", "value" -> "needs_translation"))
        _ <- sendRequest("POST", s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("fr", "es"), "type" -> "flag", "value" -> "needs_translation"))
        _ <- sendRequest("POST", s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("cs", "nl"), "type" -> "flag", "value" -> "needs_translation"))

        rowJson1 <- sendRequest("GET", s"/tables/$tableId/rows/$rowId")
      } yield {
        // annotations with the same type and value will be merged
        assertEquals(1, rowJson1.getJsonArray("annotations").getJsonArray(0).size())

        val langtags = rowJson1
          .getJsonArray("annotations")
          .getJsonArray(0)
          .getJsonObject(0)
          .getJsonArray("langtags")

        assertContainsDeep(Json.arr(Seq("de", "gb", "fr", "es", "cs", "nl").sorted: _*), langtags)
      }
    }
  }

  @Test
  def deleteLangtagFromExistingAnnotation(implicit c: TestContext): Unit = {
    okTest{
      for {
        (tableId, _) <- createTableWithMultilanguageColumns("Test")

        // empty row
        result <- sendRequest("POST", s"/tables/$tableId/rows")
        rowId = result.getLong("id")

        annotation <- sendRequest("POST",
          s"/tables/$tableId/columns/1/rows/$rowId/annotations",
          Json.obj("langtags" -> Json.arr("de", "en"), "type" -> "error"))

        rowJson <- sendRequest("GET", s"/tables/$tableId/rows/$rowId")

        _ <- sendRequest("DELETE",
          s"/tables/$tableId/columns/1/rows/$rowId/annotations/${annotation.getString("uuid")}/en")

        rowJsonAfterDelete <- sendRequest("GET", s"/tables/$tableId/rows/$rowId")
      } yield {
        val exceptedFlags = Json
          .arr(Json.obj("langtags" -> Json.arr("de", "en"), "type" -> "error", "value" -> null))

        val exceptedFlagsAfterDelete = Json
          .arr(Json.obj("langtags" -> Json.arr("de"), "type" -> "error", "value" -> null))

        assertContainsDeep(exceptedFlags, rowJson.getJsonArray("annotations").getJsonArray(0))
        assertContainsDeep(exceptedFlagsAfterDelete, rowJsonAfterDelete.getJsonArray("annotations").getJsonArray(0))
      }
    }
  }
}