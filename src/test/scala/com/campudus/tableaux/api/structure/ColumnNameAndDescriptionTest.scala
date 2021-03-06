package com.campudus.tableaux.api.structure

import com.campudus.tableaux.testtools.TableauxTestBase
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.vertx.scala.core.json.{Json, JsonObject}

@RunWith(classOf[VertxUnitRunner])
class ColumnNameAndDescriptionTest extends TableauxTestBase {

  @Test
  def createColumnsWithIdenticalInternalNames(implicit c: TestContext): Unit = {
    exceptionTest("error.request.unique.column") {
      val postSimpleTable = Json.obj("name" -> "table1")
      val columnWithDisplayName1 = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "displayName" -> Json.obj(
          "de_DE" -> "Spalte Eins",
          "en_US" -> "Column One"
        )
      )
      val columnWithDisplayName2 = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "displayName" -> Json.obj(
          "de_DE" -> "Spalte Zwei",
          "en_US" -> "Column Two"
        )
      )
      val postColumnWithDisplayNames1 = Json.obj("columns" -> Json.arr(columnWithDisplayName1))
      val postColumnWithDisplayNames2 = Json.obj("columns" -> Json.arr(columnWithDisplayName2))

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames1)
        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames2)
      } yield ()
    }
  }

  @Test
  def createMultilanguageNamesForColumn(implicit c: TestContext): Unit = {
    okTest {
      val postSimpleTable = Json.obj("name" -> "table1")
      val columnWithDisplayName = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "displayName" -> Json.obj(
          "de_DE" -> "Spalte Eins",
          "en_US" -> "Column One"
        )
      )
      val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDisplayName))

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
        columnId = postColumnResult.getJsonArray("columns").getJsonObject(0).getLong("id")

        getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
      } yield {
        assertContains(columnWithDisplayName, postColumnResult.getJsonArray("columns").getJsonObject(0))
        assertEquals(getColumnResult,
                     postColumnResult.getJsonArray("columns").getJsonObject(0).mergeIn(Json.obj("status" -> "ok")))
      }
    }
  }

  @Test
  def changeMultilanguageNamesForColumn(implicit c: TestContext): Unit = okTest {
    val postSimpleTable = Json.obj("name" -> "table1")
    val columnWithDisplayName = Json.obj(
      "name" -> "column1",
      "kind" -> "shorttext",
      "displayName" -> Json.obj(
        "de_DE" -> "Spalte Eins",
        "en_US" -> "Column One"
      )
    )
    val columnWithDisplayName2 = Json.obj(
      "displayName" -> Json.obj(
        "de_DE" -> "Erste Spalte",
        "en_US" -> "First column"
      )
    )
    val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDisplayName))

    for {
      table <- sendRequest("POST", "/tables", postSimpleTable)
      tableId = table.getLong("id")

      postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
      createResult = postColumnResult.getJsonArray("columns").getJsonObject(0)
      columnId = createResult.getLong("id")

      changedResult <- sendRequest("POST", s"/tables/$tableId/columns/$columnId", columnWithDisplayName2)
      getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
    } yield {
      assertContains(columnWithDisplayName, createResult)
      assertContains(columnWithDisplayName2, changedResult)
      assertEquals(getColumnResult, changedResult.mergeIn(Json.obj("status" -> "ok")))
    }
  }

  @Test
  def addOtherLanguageDisplayNameToColumnShouldNotDeleteOtherLanguages(implicit c: TestContext): Unit = {
    okTest {
      val postSimpleTable = Json.obj("name" -> "table1")
      val columnWithDisplayName = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "displayName" -> Json.obj(
          "de_DE" -> "Spalte Eins"
        )
      )
      val columnWithDisplayName2 = Json.obj(
        "displayName" -> Json.obj(
          "en_US" -> "Column One"
        )
      )
      val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDisplayName))

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
        createResult = postColumnResult.getJsonArray("columns").getJsonObject(0)
        columnId = createResult.getLong("id")

        changedResult <- sendRequest("POST", s"/tables/$tableId/columns/$columnId", columnWithDisplayName2)
        getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
      } yield {
        logger.info(s"changedResult=${changedResult.encode()}")
        assertContains(columnWithDisplayName, createResult)
        assertEquals(Json.obj("de_DE" -> "Spalte Eins", "en_US" -> "Column One"),
                     changedResult.getJsonObject("displayName"))
        assertEquals(changedResult.mergeIn(Json.obj("status" -> "ok")), getColumnResult)
      }
    }
  }

  @Test
  def deleteLanguageFromDisplayName(implicit c: TestContext): Unit = okTest {
    val postSimpleTable = Json.obj("name" -> "table1")
    val columnWithDisplayName = Json.obj(
      "name" -> "column1",
      "kind" -> "shorttext",
      "displayName" -> Json.obj(
        "de_DE" -> "Spalte Eins",
        "en_US" -> "Column One"
      )
    )
    val columnWithDisplayName2 = Json.obj(
      "displayName" -> Json.obj(
        "en_US" -> null
      )
    )
    val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDisplayName))

    for {
      table <- sendRequest("POST", "/tables", postSimpleTable)
      tableId = table.getLong("id")

      postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
      createResult = postColumnResult.getJsonArray("columns").getJsonObject(0)
      columnId = createResult.getLong("id")

      changedResult <- sendRequest("POST", s"/tables/$tableId/columns/$columnId", columnWithDisplayName2)
      getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
    } yield {
      assertContains(columnWithDisplayName, createResult)
      assertEquals(Json.obj("de_DE" -> "Spalte Eins"), changedResult.getJsonObject("displayName"))
      assertEquals(getColumnResult, changedResult.mergeIn(Json.obj("status" -> "ok")))
    }
  }

  @Test
  def createMultilanguageNameAndDescriptionForColumn(implicit c: TestContext): Unit = {
    okTest {
      val postSimpleTable = Json.obj("name" -> "table1")
      val columnWithDisplayNameAndDescription = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "displayName" -> Json.obj(
          "de_DE" -> "Spalte 1",
          "en_US" -> "Column 1"
        ),
        "description" -> Json.obj(
          "de_DE" -> "Beschreibung Spalte 1",
          "en_US" -> "Description Column 1"
        )
      )
      val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDisplayNameAndDescription))

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
        columnId = postColumnResult.getJsonArray("columns").getJsonObject(0).getLong("id")

        getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
      } yield {
        assertContains(columnWithDisplayNameAndDescription, postColumnResult.getJsonArray("columns").getJsonObject(0))
        assertEquals(getColumnResult,
                     postColumnResult.getJsonArray("columns").getJsonObject(0).mergeIn(Json.obj("status" -> "ok")))
      }
    }
  }

  @Test
  def changeMultilanguageNameAndDescriptionForColumn(implicit c: TestContext): Unit = {
    okTest {
      val postSimpleTable = Json.obj("name" -> "table1")
      val columnWithDisplayNameAndDescription = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "displayName" -> Json.obj(
          "de_DE" -> "Spalte 1",
          "en_US" -> "Column 1",
          "fr_FR" -> "Colonne 1"
        ),
        "description" -> Json.obj(
          "de_DE" -> "Beschreibung Spalte 1",
          "en_US" -> "Description column 1",
          "fr_FR" -> "Description colonne 1"
        )
      )
      val columnWithDisplayName2 = Json.obj(
        "displayName" -> Json.obj(
          "en_US" -> "First column",
          "fr_FR" -> "Première colonne"
        ),
        "description" -> Json.obj(
          "de_DE" -> "Beschreibung Spalte Eins",
          "fr_FR" -> "Description première colonne"
        )
      )
      val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDisplayNameAndDescription))

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
        columnId = postColumnResult.getJsonArray("columns").getJsonObject(0).getLong("id")

        changedResult <- sendRequest("POST", s"/tables/$tableId/columns/$columnId", columnWithDisplayName2)
        getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
      } yield {
        assertContains(columnWithDisplayNameAndDescription, postColumnResult.getJsonArray("columns").getJsonObject(0))
        assertContains(
          Json.obj(
            "displayName" -> Json.obj(
              "de_DE" -> "Spalte 1",
              "en_US" -> "First column",
              "fr_FR" -> "Première colonne"
            ),
            "description" -> Json.obj(
              "de_DE" -> "Beschreibung Spalte Eins",
              "en_US" -> "Description column 1",
              "fr_FR" -> "Description première colonne"
            )
          ),
          changedResult
        )
        assertEquals(changedResult, getColumnResult)
      }
    }
  }

  @Test
  def deleteAllDisplayNamesAndDescriptionsForColumn(implicit c: TestContext): Unit = okTest {
    val postSimpleTable = Json.obj("name" -> "table1")
    val columnWithDisplayNameAndDescription = Json.obj(
      "name" -> "column1",
      "kind" -> "shorttext",
      "displayName" -> Json.obj(
        "de_DE" -> "Spalte 1",
        "en_US" -> "Column 1"
      ),
      "description" -> Json.obj(
        "de_DE" -> "Beschreibung Spalte 1",
        "en_US" -> "Description Column 1"
      )
    )
    val columnWithNulledValues = Json.obj(
      "displayName" -> Json.obj(
        "de_DE" -> null,
        "en_US" -> null
      ),
      "description" -> Json.obj(
        "de_DE" -> null,
        "en_US" -> null
      )
    )
    val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDisplayNameAndDescription))

    for {
      table <- sendRequest("POST", "/tables", postSimpleTable)
      tableId = table.getLong("id")

      postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
      columnId = postColumnResult.getJsonArray("columns").getJsonObject(0).getLong("id")

      changedResult <- sendRequest("POST", s"/tables/$tableId/columns/$columnId", columnWithNulledValues)
      getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
    } yield {
      assertContains(columnWithDisplayNameAndDescription, postColumnResult.getJsonArray("columns").getJsonObject(0))
      logger.info(s"changedResult=${changedResult.encode()}")
      assertContains(Json.obj("displayName" -> Json.obj(), "description" -> Json.obj()), changedResult)
      assertEquals(changedResult, getColumnResult)
    }
  }

  @Test
  def createMultilanguageNameAndOtherDescriptionForColumn(implicit c: TestContext): Unit = {
    okTest {
      val postSimpleTable = Json.obj("name" -> "table1")
      val columnWithDisplayNameAndDescription = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "displayName" -> Json.obj(
          "de_DE" -> "Spalte 1"
        ),
        "description" -> Json.obj(
          "en_US" -> "Description Column 1"
        )
      )
      val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDisplayNameAndDescription))

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
        columnId = postColumnResult.getJsonArray("columns").getJsonObject(0).getLong("id")

        getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
      } yield {
        assertContains(columnWithDisplayNameAndDescription, postColumnResult.getJsonArray("columns").getJsonObject(0))
        assertEquals(getColumnResult,
                     postColumnResult.getJsonArray("columns").getJsonObject(0).mergeIn(Json.obj("status" -> "ok")))
      }
    }
  }

  @Test
  def changeMultilanguageNameAndOtherDescriptionForColumn(implicit c: TestContext): Unit = {
    okTest {
      val postSimpleTable = Json.obj("name" -> "table1")

      def columnWithNameAndDescription(name: JsonObject, description: JsonObject) = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "displayName" -> name,
        "description" -> description
      )

      val postColumnJson = columnWithNameAndDescription(name = Json.obj(
                                                          "de_DE" -> "Spalte 1"
                                                        ),
                                                        description = Json.obj(
                                                          "en_US" -> "Description Column 1"
                                                        ))
      val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(postColumnJson))

      val patchColumnJson = columnWithNameAndDescription(name = Json.obj(
                                                           "de_DE" -> "Erste Spalte"
                                                         ),
                                                         description = Json.obj(
                                                           "en_US" -> "Description of first column"
                                                         ))
      val patchColumnWithDisplayNames = patchColumnJson

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
        columnId = postColumnResult.getJsonArray("columns").getJsonObject(0).getLong("id")

        patchColumnResult <- sendRequest("POST", s"/tables/$tableId/columns/$columnId", patchColumnWithDisplayNames)
        columnId2 = patchColumnResult.getLong("id")

        getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
      } yield {
        assertEquals(columnId, columnId2)
        assertContains(patchColumnJson, patchColumnResult)
        assertEquals(getColumnResult, patchColumnResult.mergeIn(Json.obj("status" -> "ok")))
      }
    }
  }

  @Test
  def createMultilanguageDescriptionForColumn(implicit c: TestContext): Unit = {
    okTest {
      val postSimpleTable = Json.obj("name" -> "table1")
      val columnWithDescription = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "description" -> Json.obj(
          "de_DE" -> "Beschreibung Spalte 1",
          "en_US" -> "Description Column 1"
        )
      )
      val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(columnWithDescription))

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
        columnId = postColumnResult.getJsonArray("columns").getJsonObject(0).getLong("id")

        getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
      } yield {
        assertContains(columnWithDescription, postColumnResult.getJsonArray("columns").getJsonObject(0))
        assertEquals(getColumnResult,
                     postColumnResult.getJsonArray("columns").getJsonObject(0).mergeIn(Json.obj("status" -> "ok")))
      }
    }
  }

  @Test
  def changeMultilanguageDescriptionForColumn(implicit c: TestContext): Unit = {
    okTest {
      val postSimpleTable = Json.obj("name" -> "table1")

      def columnWithDescription(description: JsonObject) = Json.obj(
        "name" -> "column1",
        "kind" -> "shorttext",
        "description" -> description
      )

      val postColumnJson = columnWithDescription(Json.obj("en_US" -> "Description Column 1"))
      val postColumnWithDisplayNames = Json.obj("columns" -> Json.arr(postColumnJson))

      val patchColumnJson = columnWithDescription(Json.obj("en_US" -> "Description first column"))
      val patchColumnWithDisplayNames = patchColumnJson

      for {
        table <- sendRequest("POST", "/tables", postSimpleTable)
        tableId = table.getLong("id")

        postColumnResult <- sendRequest("POST", s"/tables/$tableId/columns", postColumnWithDisplayNames)
        columnId = postColumnResult.getJsonArray("columns").getJsonObject(0).getLong("id")

        patchColumnResult <- sendRequest("POST", s"/tables/$tableId/columns/$columnId", patchColumnWithDisplayNames)
        columnId2 = patchColumnResult.getLong("id")

        getColumnResult <- sendRequest("GET", s"/tables/$tableId/columns/$columnId")
      } yield {
        assertEquals(columnId, columnId2)
        assertContains(patchColumnJson, patchColumnResult)
        assertEquals(getColumnResult, patchColumnResult.mergeIn(Json.obj("status" -> "ok")))
      }
    }
  }

}
