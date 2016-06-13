package com.campudus.tableaux.database.domain

import com.campudus.tableaux.database.model.TableauxModel.TableId
import com.campudus.tableaux.testtools.AssertionHelpers
import org.junit.Assert._
import org.junit.Test
import org.vertx.scala.core.json.Json

abstract class AbstractTableDisplayInfosTest extends AssertionHelpers {

  @Test
  def checkSingleName(): Unit = {
    val di = singleName(1, "de_DE", "Tabelle 1")
    assertTrue(di.nonEmpty)

    val (createStmt, createBind) = di.createSql
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?)", createStmt)
    assertEquals(Seq(1, "de_DE", "Tabelle 1", null), createBind)

    assertEquals(1, di.insertSql.size)
    val (insertStmtDe, insertBindDe) = di.insertSql("de_DE")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name) VALUES (?, ?, ?)", insertStmtDe)
    assertEquals(Seq(1, "de_DE", "Tabelle 1"), insertBindDe)

    assertEquals(1, di.updateSql.size)
    val (updateStmtDe, updateBindDe) = di.updateSql("de_DE")
    assertEquals("UPDATE system_table_lang SET name = ? WHERE table_id = ? AND langtag = ?", updateStmtDe)
    assertEquals(Seq("Tabelle 1", 1, "de_DE"), updateBindDe)
  }

  @Test
  def checkMultipleNames(): Unit = {
    val di = multipleNames(1, List("de_DE" -> "Tabelle 1", "en_US" -> "Table 1"))
    assertTrue(di.nonEmpty)

    val (createStmt, createBind) = di.createSql
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?), (?, ?, ?, ?)", createStmt)
    checkPartsInRandomOrder(Seq(
      Seq(1, "de_DE", "Tabelle 1", null),
      Seq(1, "en_US", "Table 1", null)
    ), createBind)

    assertEquals(2, di.insertSql.size)
    val (insertStmtDe, insertBindDe) = di.insertSql("de_DE")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name) VALUES (?, ?, ?)", insertStmtDe)
    assertEquals(Seq(1, "de_DE", "Tabelle 1"), insertBindDe)
    val (insertStmtEn, insertBindEn) = di.insertSql("en_US")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name) VALUES (?, ?, ?)", insertStmtEn)
    assertEquals(Seq(1, "en_US", "Table 1"), insertBindEn)

    assertEquals(2, di.updateSql.size)
    val (updateStmtDe, updateBindDe) = di.updateSql("de_DE")
    assertEquals("UPDATE system_table_lang SET name = ? WHERE table_id = ? AND langtag = ?", updateStmtDe)
    assertEquals(Seq("Tabelle 1", 1, "de_DE"), updateBindDe)
    val (updateStmtEn, updateBindEn) = di.updateSql("en_US")
    assertEquals("UPDATE system_table_lang SET name = ? WHERE table_id = ? AND langtag = ?", updateStmtEn)
    assertEquals(Seq("Table 1", 1, "en_US"), updateBindEn)
  }

  @Test
  def checkSingleDescription(): Unit = {
    val di = singleDesc(1, "de_DE", "Tabelle 1 Beschreibung")
    assertTrue(di.nonEmpty)

    val (statement, binds) = di.createSql
    assertEquals(
      "INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?)",
      statement
    )
    assertEquals(Seq(1, "de_DE", null, "Tabelle 1 Beschreibung"), binds)

    assertEquals(1, di.insertSql.size)
    val (insertStmtDe, insertBindDe) = di.insertSql("de_DE")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, description) VALUES (?, ?, ?)", insertStmtDe)
    assertEquals(Seq(1, "de_DE", "Tabelle 1 Beschreibung"), insertBindDe)

    assertEquals(1, di.updateSql.size)
    val (updateStmtDe, updateBindDe) = di.updateSql("de_DE")
    assertEquals("UPDATE system_table_lang SET description = ? WHERE table_id = ? AND langtag = ?", updateStmtDe)
    assertEquals(Seq("Tabelle 1 Beschreibung", 1, "de_DE"), updateBindDe)
  }

  @Test
  def checkMultipleDescriptions(): Unit = {
    val di = multipleDescs(1, List("de_DE" -> "Tabelle 1 Beschreibung", "en_US" -> "Table 1 Description"))
    assertTrue(di.nonEmpty)

    val (createStatement, createBind) = di.createSql
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?), (?, ?, ?, ?)", createStatement)
    checkPartsInRandomOrder(Seq(
      Seq(1, "de_DE", null, "Tabelle 1 Beschreibung"),
      Seq(1, "en_US", null, "Table 1 Description")
    ), createBind)

    assertEquals(2, di.insertSql.size)
    val (insertStmtDe, insertBindDe) = di.insertSql("de_DE")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, description) VALUES (?, ?, ?)", insertStmtDe)
    assertEquals(Seq(1, "de_DE", "Tabelle 1 Beschreibung"), insertBindDe)
    val (insertStmtEn, insertBindEn) = di.insertSql("en_US")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, description) VALUES (?, ?, ?)", insertStmtEn)
    assertEquals(Seq(1, "en_US", "Table 1 Description"), insertBindEn)

    assertEquals(2, di.updateSql.size)
    val (updateStmtDe, updateBindDe) = di.updateSql("de_DE")
    assertEquals("UPDATE system_table_lang SET description = ? WHERE table_id = ? AND langtag = ?", updateStmtDe)
    assertEquals(Seq("Tabelle 1 Beschreibung", 1, "de_DE"), updateBindDe)
    val (updateStmtEn, updateBindEn) = di.updateSql("en_US")
    assertEquals("UPDATE system_table_lang SET description = ? WHERE table_id = ? AND langtag = ?", updateStmtEn)
    assertEquals(Seq("Table 1 Description", 1, "en_US"), updateBindEn)
  }

  @Test
  def checkSingleNameAndDescription(): Unit = {
    val di = singleNameAndDesc(1, "de_DE", "Tabelle 1", "Tabelle 1 Beschreibung")
    assertTrue(di.nonEmpty)

    val (createStatement, createBinds) = di.createSql
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?)", createStatement)
    assertEquals(Seq(1, "de_DE", "Tabelle 1", "Tabelle 1 Beschreibung"), createBinds)

    assertEquals(1, di.insertSql.size)
    val (insertStmtDe, insertBindDe) = di.insertSql("de_DE")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?)", insertStmtDe)
    assertEquals(Seq(1, "de_DE", "Tabelle 1", "Tabelle 1 Beschreibung"), insertBindDe)

    assertEquals(1, di.updateSql.size)
    val (updateStmtDe, updateBindDe) = di.updateSql("de_DE")
    assertEquals("UPDATE system_table_lang SET name = ?, description = ? WHERE table_id = ? AND langtag = ?", updateStmtDe)
    assertEquals(Seq("Tabelle 1", "Tabelle 1 Beschreibung", 1, "de_DE"), updateBindDe)
  }

  @Test
  def checkMultipleNamesAndDescriptions(): Unit = {
    val di = multipleNameAndDesc(1, List(
      ("de_DE", "Tabelle 1", "Tabelle 1 Beschreibung"),
      ("en_US", "Table 1", "Table 1 Description")
    ))
    assertTrue(di.nonEmpty)

    val (createStatement, createBinds) = di.createSql
    assertEquals(
      "INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?), (?, ?, ?, ?)",
      createStatement
    )
    checkPartsInRandomOrder(Seq(
      Seq(1, "de_DE", "Tabelle 1", "Tabelle 1 Beschreibung"),
      Seq(1, "en_US", "Table 1", "Table 1 Description")
    ), createBinds)

    assertEquals(2, di.insertSql.size)
    val (insertStmtDe, insertBindDe) = di.insertSql("de_DE")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?)", insertStmtDe)
    assertEquals(Seq(1, "de_DE", "Tabelle 1", "Tabelle 1 Beschreibung"), insertBindDe)
    val (insertStmtEn, insertBindEn) = di.insertSql("en_US")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?)", insertStmtEn)
    assertEquals(Seq(1, "en_US", "Table 1", "Table 1 Description"), insertBindEn)

    assertEquals(2, di.updateSql.size)
    val (updateStmtDe, updateBindDe) = di.updateSql("de_DE")
    assertEquals("UPDATE system_table_lang SET name = ?, description = ? WHERE table_id = ? AND langtag = ?", updateStmtDe)
    assertEquals(Seq("Tabelle 1", "Tabelle 1 Beschreibung", 1, "de_DE"), updateBindDe)
    val (updateStmtEn, updateBindEn) = di.updateSql("en_US")
    assertEquals("UPDATE system_table_lang SET name = ?, description = ? WHERE table_id = ? AND langtag = ?", updateStmtEn)
    assertEquals(Seq("Table 1", "Table 1 Description", 1, "en_US"), updateBindEn)
  }

  @Test
  def checkNameAndOtherDesc(): Unit = {
    val di = multipleNameAndDesc(1, List(("de_DE", "Tabelle 1", null), ("en_US", null, "Table 1 Description")))
    assertTrue(di.nonEmpty)

    val (createStatement, createBinds) = di.createSql
    assertEquals(
      "INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?), (?, ?, ?, ?)",
      createStatement
    )
    checkPartsInRandomOrder(Seq(
      Seq(1, "de_DE", "Tabelle 1", null),
      Seq(1, "en_US", null, "Table 1 Description")
    ), createBinds)

    assertEquals(2, di.insertSql.size)
    val (insertStatementDe, insertBindsDe) = di.insertSql("de_DE")
    val (insertStatementEn, insertBindsEn) = di.insertSql("en_US")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name) VALUES (?, ?, ?)", insertStatementDe)
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, description) VALUES (?, ?, ?)", insertStatementEn)
    assertEquals(Seq(1, "de_DE", "Tabelle 1"), insertBindsDe)
    assertEquals(Seq(1, "en_US", "Table 1 Description"), insertBindsEn)

    assertEquals(2, di.updateSql.size)
    val (updateStatementDe, updateBindsDe) = di.updateSql("de_DE")
    val (updateStatementEn, updateBindsEn) = di.updateSql("en_US")
    assertEquals("UPDATE system_table_lang SET name = ? WHERE table_id = ? AND langtag = ?", updateStatementDe)
    assertEquals("UPDATE system_table_lang SET description = ? WHERE table_id = ? AND langtag = ?", updateStatementEn)
    assertEquals(Seq("Tabelle 1", 1, "de_DE"), updateBindsDe)
    assertEquals(Seq("Table 1 Description", 1, "en_US"), updateBindsEn)
  }

  @Test
  def checkCombinations(): Unit = {
    val di = multipleNameAndDesc(1, List(
      ("de_DE", "Tabelle 1", "Tabelle 1 Beschreibung"),
      ("en_US", null, "Table 1 Description"),
      ("fr_FR", "Tableau 1", null)
    ))
    assertTrue(di.nonEmpty)
    val (createStatement, createBinds) = di.createSql
    assertEquals(
      "INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?), (?, ?, ?, ?), (?, ?, ?, ?)",
      createStatement
    )
    val all = Seq(
      Seq(1, "de_DE", "Tabelle 1", "Tabelle 1 Beschreibung"),
      Seq(1, "en_US", null, "Table 1 Description"),
      Seq(1, "fr_FR", "Tableau 1", null)
    )

    checkPartsInRandomOrder(all, createBinds)

    assertEquals(3, di.insertSql.size)
    val (insertStatementDe, insertBindsDe) = di.insertSql("de_DE")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name, description) VALUES (?, ?, ?, ?)", insertStatementDe)
    assertEquals(Seq(1, "de_DE", "Tabelle 1", "Tabelle 1 Beschreibung"), insertBindsDe)
    val (insertStatementEn, insertBindsEn) = di.insertSql("en_US")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, description) VALUES (?, ?, ?)", insertStatementEn)
    assertEquals(Seq(1, "en_US", "Table 1 Description"), insertBindsEn)
    val (insertStatementFr, insertBindsFr) = di.insertSql("fr_FR")
    assertEquals("INSERT INTO system_table_lang (table_id, langtag, name) VALUES (?, ?, ?)", insertStatementFr)
    assertEquals(Seq(1, "fr_FR", "Tableau 1"), insertBindsFr)

    assertEquals(3, di.updateSql.size)
    val (updateStatementDe, updateBindsDe) = di.updateSql("de_DE")
    assertEquals("UPDATE system_table_lang SET name = ?, description = ? WHERE table_id = ? AND langtag = ?", updateStatementDe)
    assertEquals(Seq("Tabelle 1", "Tabelle 1 Beschreibung", 1, "de_DE"), updateBindsDe)
    val (updateStatementEn, updateBindsEn) = di.updateSql("en_US")
    assertEquals("UPDATE system_table_lang SET description = ? WHERE table_id = ? AND langtag = ?", updateStatementEn)
    assertEquals(Seq("Table 1 Description", 1, "en_US"), updateBindsEn)
    val (updateStatementFr, updateBindsFr) = di.updateSql("fr_FR")
    assertEquals("UPDATE system_table_lang SET name = ? WHERE table_id = ? AND langtag = ?", updateStatementFr)
    assertEquals(Seq("Tableau 1", 1, "fr_FR"), updateBindsFr)
  }

  @Test
  def emptyDisplayStuff(): Unit = {
    val di = emptyDisplayInfo(1)
    assertFalse(di.nonEmpty)
  }

  def emptyDisplayInfo(tableId: TableId): TableDisplayInfos

  def singleName(tableId: TableId, langtag: String, name: String): TableDisplayInfos

  def multipleNames(tableId: TableId, langNames: List[(String, String)]): TableDisplayInfos

  def singleDesc(tableId: TableId, langtag: String, desc: String): TableDisplayInfos

  def multipleDescs(tableId: TableId, langDescs: List[(String, String)]): TableDisplayInfos

  def singleNameAndDesc(tableId: TableId, langtag: String, name: String, desc: String): TableDisplayInfos

  def multipleNameAndDesc(tableId: TableId, infos: List[(String, String, String)]): TableDisplayInfos

}

class TableDisplayInfosTestDirect extends AbstractTableDisplayInfosTest {

  override def emptyDisplayInfo(tableId: TableId): TableDisplayInfos =
    DisplayInfos(tableId, List())

  override def singleName(tableId: TableId, langtag: String, name: String): TableDisplayInfos =
    DisplayInfos(tableId, List(NameOnly(langtag, name)))

  override def multipleNames(tableId: TableId, langNames: List[(String, String)]): TableDisplayInfos =
    DisplayInfos(tableId, langNames.map(t => NameOnly(t._1, t._2)))

  override def singleDesc(tableId: TableId, langtag: String, desc: String): TableDisplayInfos =
    DisplayInfos(tableId, List(DescriptionOnly(langtag, desc)))

  override def multipleDescs(tableId: TableId, langDescs: List[(String, String)]): TableDisplayInfos =
    DisplayInfos(tableId, langDescs.map(t => DescriptionOnly(t._1, t._2)))

  override def singleNameAndDesc(tableId: TableId, langtag: String, name: String, desc: String): TableDisplayInfos =
    DisplayInfos(tableId, List(NameAndDescription(langtag, name, desc)))

  override def multipleNameAndDesc(tableId: TableId, infos: List[(String, String, String)]): TableDisplayInfos =
    DisplayInfos(tableId, infos.map {
      case (lang, name, null) => NameOnly(lang, name)
      case (lang, null, desc) => DescriptionOnly(lang, desc)
      case (lang, name, desc) => NameAndDescription(lang, name, desc)
    })

}

class TableDisplayInfosTestJsonObject extends AbstractTableDisplayInfosTest {

  override def emptyDisplayInfo(tableId: TableId): TableDisplayInfos =
    DisplayInfos(tableId, Json.obj())

  override def singleName(tableId: TableId, langtag: String, name: String): TableDisplayInfos =
    DisplayInfos(tableId, Json.obj("displayName" -> Json.obj(langtag -> name)))

  override def multipleNames(tableId: TableId, langNames: List[(String, String)]): TableDisplayInfos = {
    DisplayInfos(tableId, Json.obj("displayName" -> langNames.foldLeft(Json.obj()) {
      case (json, (langtag, name)) => json.mergeIn(Json.obj(langtag -> name))
    }))
  }

  override def singleDesc(tableId: TableId, langtag: String, desc: String): TableDisplayInfos =
    DisplayInfos(tableId, Json.obj("description" -> Json.obj(langtag -> desc)))

  override def multipleDescs(tableId: TableId, langDescs: List[(String, String)]): TableDisplayInfos =
    DisplayInfos(tableId, Json.obj("description" -> langDescs.foldLeft(Json.obj()) {
      case (json, (langtag, desc)) => json.mergeIn(Json.obj(langtag -> desc))
    }))

  override def singleNameAndDesc(tableId: TableId, langtag: String, name: String, desc: String): TableDisplayInfos =
    DisplayInfos(tableId, Json.obj(
      "displayName" -> Json.obj(langtag -> name),
      "description" -> Json.obj(langtag -> desc)
    ))

  override def multipleNameAndDesc(tableId: TableId, infos: List[(String, String, String)]): TableDisplayInfos = {
    val result = infos.foldLeft(Json.obj()) {
      case (json, (lang, name, null)) => json.mergeIn(Json.obj("displayName" -> json.getJsonObject("displayName", Json.obj()).mergeIn(Json.obj(lang -> name))))
      case (json, (lang, null, desc)) => json.mergeIn(Json.obj("description" -> json.getJsonObject("description", Json.obj()).mergeIn(Json.obj(lang -> desc))))
      case (json, (lang, name, desc)) =>
        val n = json.getJsonObject("displayName", Json.obj()).mergeIn(Json.obj(lang -> name))
        val d = json.getJsonObject("description", Json.obj()).mergeIn(Json.obj(lang -> desc))
        json.mergeIn(Json.obj("displayName" -> n)).mergeIn(Json.obj("description" -> d))
    }
    DisplayInfos(tableId, result)
  }

}