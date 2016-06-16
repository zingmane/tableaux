package com.campudus.tableaux.database.model.structure

import java.util.NoSuchElementException

import com.campudus.tableaux.database._
import com.campudus.tableaux.database.domain._
import com.campudus.tableaux.database.model.TableauxModel._
import com.campudus.tableaux.helper.ResultChecker._
import com.campudus.tableaux.{DatabaseException, NotFoundInDatabaseException, ShouldBeUniqueException}
import org.vertx.scala.core.json._

import scala.concurrent.Future


class ColumnModel(val connection: DatabaseConnection) extends DatabaseQuery {

  private lazy val tableStruc = new TableModel(connection)

  private val MAX_DEPTH = 5

  def createColumns(table: Table, createColumns: Seq[CreateColumn]): Future[Seq[ColumnType[_]]] = {
    createColumns.foldLeft(Future.successful(Seq.empty[ColumnType[_]])) {
      case (future, next) =>
        for {
          createdColumns <- future
          createdColumn <- createColumn(table, next)
        } yield {
          createdColumns :+ createdColumn
        }
    }
  }

  def createColumn(table: Table, createColumn: CreateColumn): Future[ColumnType[_]] = {
    createColumn match {
      case simpleColumnInfo: CreateSimpleColumn =>
        createValueColumn(table.id, simpleColumnInfo).map({
          case ColumnInfo(tableId, id, ordering, displayInfos) =>
            Mapper(simpleColumnInfo.languageType, simpleColumnInfo.kind).apply(table, id, simpleColumnInfo.name, ordering, simpleColumnInfo.identifier, displayInfos, simpleColumnInfo.countryCodes)
        })

      case linkColumnInfo: CreateLinkColumn => for {
        toTable <- tableStruc.retrieve(linkColumnInfo.toTable)
        toTableColumns <- retrieveAll(toTable).flatMap({
          columns =>
            if (columns.isEmpty) {
              Future.failed(new NotFoundInDatabaseException(s"Link points at table ${toTable.id} without columns", "no-columns"))
            } else {
              Future.successful(columns)
            }
        })

        toCol = toTableColumns.head.asInstanceOf[ValueColumn[_]]

        (linkId, ColumnInfo(_, id, ordering, displayInfos)) <- createLinkColumn(table, linkColumnInfo)
      } yield LinkColumn(table, id, toCol, linkId, LeftToRight(table.id, linkColumnInfo.toTable), linkColumnInfo.name, ordering, linkColumnInfo.identifier, displayInfos)

      case attachmentColumnInfo: CreateAttachmentColumn =>
        createAttachmentColumn(table.id, attachmentColumnInfo).map({
          case ColumnInfo(tableId, id, ordering, displayInfos) => AttachmentColumn(table, id, attachmentColumnInfo.name, ordering, attachmentColumnInfo.identifier, displayInfos)
        })
    }
  }

  private def createValueColumn(tableId: TableId, simpleColumnInfo: CreateSimpleColumn): Future[ColumnInfo] = {
    connection.transactional { t =>
      for {
        (t, columnInfo) <- insertSystemColumn(t, tableId, simpleColumnInfo, None, simpleColumnInfo.countryCodes)

        (t, _) <- simpleColumnInfo.languageType match {
          case MultiLanguage | MultiCountry => t.query(s"ALTER TABLE user_table_lang_$tableId ADD column_${columnInfo.columnId} ${simpleColumnInfo.kind.toDbType}")
          case SingleLanguage => t.query(s"ALTER TABLE user_table_$tableId ADD column_${columnInfo.columnId} ${simpleColumnInfo.kind.toDbType}")
        }
      } yield {
        (t, columnInfo)
      }
    }
  }

  private def createAttachmentColumn(tableId: TableId, attachmentColumnInfo: CreateAttachmentColumn): Future[ColumnInfo] = {
    connection.transactional { t =>
      for {
        (t, columnInfo) <- insertSystemColumn(t, tableId, attachmentColumnInfo, None, None)
      } yield (t, columnInfo)
    }
  }

  private def createLinkColumn(table: Table, linkColumnInfo: CreateLinkColumn): Future[(LinkId, ColumnInfo)] = {
    val tableId = table.id

    connection.transactional { t =>
      for {
        (t, result) <- t.query("INSERT INTO system_link_table (table_id_1, table_id_2) VALUES (?, ?) RETURNING link_id", Json.arr(tableId, linkColumnInfo.toTable))
        linkId = insertNotNull(result).head.get[Long](0)

        // insert link column on source table
        (t, columnInfo) <- insertSystemColumn(t, tableId, linkColumnInfo, Some(linkId), None)

        // only add the second link column if tableId != toTableId or singleDirection is false
        t <- {
          if (!linkColumnInfo.singleDirection && tableId != linkColumnInfo.toTable) {
            val copiedLinkColumnInfo = linkColumnInfo.copy(
              name = linkColumnInfo.toName.getOrElse(table.name)
            )
            insertSystemColumn(t, linkColumnInfo.toTable, copiedLinkColumnInfo, Some(linkId), None)
              // ColumnInfo will be ignored, so we can lose it
              .map(_._1)
          } else {
            Future(t)
          }
        }

        (t, _) <- t.query(
          s"""
             |CREATE TABLE link_table_$linkId (
             | id_1 bigint,
             | id_2 bigint,
             | ordering_1 serial,
             | ordering_2 serial,
             |
             | PRIMARY KEY(id_1, id_2),
             |
             | CONSTRAINT link_table_${linkId}_foreign_1
             | FOREIGN KEY(id_1) REFERENCES user_table_$tableId (id) ON DELETE CASCADE,
             |
             | CONSTRAINT link_table_${linkId}_foreign_2
             | FOREIGN KEY(id_2) REFERENCES user_table_${linkColumnInfo.toTable} (id) ON DELETE CASCADE
             |)""".stripMargin)
      } yield {
        (t, (linkId, columnInfo))
      }
    }
  }

  private def insertSystemColumn(t: connection.Transaction, tableId: TableId, createColumn: CreateColumn, linkId: Option[LinkId], countryCodes: Option[Seq[String]]): Future[(connection.Transaction, ColumnInfo)] = {
    def insertStatement(tableId: TableId, ordering: String) =
      s"""INSERT INTO system_columns (
          |table_id,
          |column_id,
          |column_type,
          |user_column_name,
          |ordering,
          |link_id,
          |multilanguage,
          |identifier,
          |country_codes
          |)
          |VALUES (?, nextval('system_columns_column_id_table_$tableId'), ?, ?, $ordering, ?, ?, ?, ?)
          |RETURNING column_id, ordering""".stripMargin

    def insertColumn(t: connection.Transaction): Future[(connection.Transaction, ColumnInfo)] = {
      for {
        t <- t.selectSingleValue[Long]("SELECT COUNT(*) FROM system_columns WHERE table_id = ? AND user_column_name = ?", Json.arr(tableId, createColumn.name))
          .flatMap({
            case (t, count) =>
              if (count > 0) {
                Future.failed(new ShouldBeUniqueException("Column name should be unique for each table", "column"))
              } else {
                Future.successful(t)
              }
          })

        (t, result) <- createColumn.ordering match {
          case None => t.query(insertStatement(tableId, s"currval('system_columns_column_id_table_$tableId')"), Json.arr(tableId, createColumn.kind.name, createColumn.name, linkId.orNull, createColumn.languageType.name.orNull, createColumn.identifier, countryCodes.map(f => Json.arr(f: _*)).orNull))
          case Some(ord) => t.query(insertStatement(tableId, "?"), Json.arr(tableId, createColumn.kind.name, createColumn.name, ord, linkId.orNull, createColumn.languageType.name.orNull, createColumn.identifier, countryCodes.map(f => Json.arr(f: _*)).orNull))
        }
      } yield {
        val resultRow = insertNotNull(result).head
        (t, ColumnInfo(tableId, resultRow.getLong(0), resultRow.getLong(1)))
      }
    }

    def insertColumnLang(t: connection.Transaction, displayInfos: ColumnDisplayInfos): Future[(connection.Transaction, Seq[DisplayInfo])] = {
      if (displayInfos.nonEmpty) {
        for {
          (t, result) <- t.query(displayInfos.statement, Json.arr(displayInfos.binds: _*))
        } yield (t, displayInfos.entries)
      } else {
        Future.successful((t, List()))
      }
    }

    for {
      (t, result) <- insertColumn(t)
      (t, resultLang) <- insertColumnLang(t, DisplayInfos(tableId, result.columnId, createColumn.displayInfos))
    } yield (t, result.copy(displayInfos = createColumn.displayInfos))
  }

  def retrieveDependencies(tableId: TableId, depth: Int = MAX_DEPTH): Future[Seq[(TableId, ColumnId)]] = {
    val select =
      s"""SELECT table_id, column_id, identifier
          | FROM system_link_table l JOIN system_columns d ON (l.link_id = d.link_id)
          | WHERE (l.table_id_1 = ? OR l.table_id_2 = ?) AND d.table_id != ? ORDER BY ordering, column_id""".stripMargin

    for {
      dependentColumns <- connection.query(select, Json.arr(tableId, tableId, tableId))
      mappedColumns <- {
        val futures = resultObjectToJsonArray(dependentColumns)
          .map({
            arr =>
              val tableId = arr.get[TableId](0)
              val columnId = arr.get[ColumnId](1)
              val identifier = arr.get[Boolean](2)

              identifier match {
                case false =>
                  Future.successful(Seq((tableId, columnId)))
                case true =>
                  if (depth > 0) {
                    retrieveDependencies(tableId, depth - 1)
                      .map(_ ++ Seq((tableId, columnId)))
                  } else {
                    Future.failed(DatabaseException("Link is too deep. Check schema.", "link-depth"))
                  }
              }
          })

        Future.sequence(futures)
          .map(_.flatten)
      }
    } yield mappedColumns
  }

  def retrieve(table: Table, columnId: ColumnId): Future[ColumnType[_]] = {
    columnId match {
      case 0 =>
        // Column zero could only be a concat column.
        // We need to retrieve all columns, because
        // only then the ConcatColumn is generated.
        retrieveAll(table).map(_.head)
      case _ =>
        retrieveOne(table, columnId, MAX_DEPTH)
    }
  }

  private def retrieveOne(table: Table, columnId: ColumnId, depth: Int): Future[ColumnType[_]] = {
    val select = "SELECT column_id, user_column_name, column_type, ordering, multilanguage, identifier, array_to_json(country_codes) FROM system_columns WHERE table_id = ? AND column_id = ?"
    val selectLang =
      s"""SELECT langtag, name, description
          | FROM system_columns_lang
          | WHERE table_id = ? AND column_id = ?""".stripMargin

    import scala.collection.JavaConverters._

    for {
      result <- {
        val json = connection.query(select, Json.arr(table.id, columnId))
        json.map(selectNotNull(_).head)
      }
      resultLang <- connection.query(selectLang, Json.arr(table.id, columnId))
      dis = resultObjectToJsonArray(resultLang).flatMap { arr =>
        val langtag = arr.getString(0)
        val name = arr.getString(1)
        val description = arr.getString(2)

        if (name != null || description != null) {
          Seq(DisplayInfos.fromString(langtag, name, description))
        } else {
          Seq.empty
        }
      }
      mappedColumn <- {
        val columnId = result.get[ColumnId](0)
        val columnName = result.get[String](1)
        val kind = Mapper.getDatabaseType(result.get[String](2))
        val ordering = result.get[Ordering](3)
        val languageType = LanguageType(Option(result.get[String](4)))
        val identifier = result.get[Boolean](5)
        val countryCodes = Option(result.get[String](6)).map(str => Json.fromArrayString(str).asScala.map({ case code: String => code }).toSeq)

        mapColumn(depth, table, columnId, columnName, kind, ordering, languageType, identifier, dis, countryCodes)
      }
    } yield mappedColumn
  }

  def retrieveAll(table: Table): Future[Seq[ColumnType[_]]] = retrieveAll(table, MAX_DEPTH)

  private def retrieveAll(table: Table, depth: Int): Future[Seq[ColumnType[_]]] = {
    for {
      (concatColumns, columns) <- retrieveColumns(table, depth, identifierOnly = false)
    } yield concatColumnAndColumns(table, concatColumns, columns)
  }

  private def retrieveIdentifiers(table: Table, depth: Int): Future[Seq[ColumnType[_]]] = {
    for {
      (concatColumns, columns) <- retrieveColumns(table, depth, identifierOnly = true)
    } yield {
      if (concatColumns.isEmpty) {
        throw DatabaseException("Link can not point to table without identifier(s).", "missing-identifier")
      } else {
        concatColumnAndColumns(table, concatColumns, columns)
      }
    }
  }

  private def concatColumnAndColumns(table: Table, concatColumns: Seq[ColumnType[_]], columns: Seq[ColumnType[_]]): Seq[ColumnType[_]] = {
    concatColumns.size match {
      case x if x >= 2 =>
        // in case of two or more identifier columns we preserve the order of column
        // and a concatcolumn in front of all columns
        columns.+:(ConcatColumn(table, "ID", concatColumns))
      case x if x == 1 =>
        // in case of one identifier column we don't get a concat column
        // but the identifier column will be the first
        columns.sortBy(_.identifier)(Ordering[Boolean].reverse)
      case _ =>
        // no identifier -> return columns
        columns
    }
  }

  private def retrieveColumns(table: Table, depth: Int, identifierOnly: Boolean): Future[(Seq[ColumnType[_]], Seq[ColumnType[_]])] = {
    val identCondition = if (identifierOnly) " AND identifier IS TRUE " else ""
    val select =
      s"""SELECT column_id, user_column_name, column_type, ordering, multilanguage, identifier, array_to_json(country_codes)
          | FROM system_columns
          | WHERE table_id = ? $identCondition ORDER BY ordering, column_id""".stripMargin

    import scala.collection.JavaConverters._

    for {
      result <- connection.query(select, Json.arr(table.id))
      mappedColumns <- {
        val futures = resultObjectToJsonArray(result).map { arr =>
          val columnId = arr.get[ColumnId](0)
          val columnName = arr.get[String](1)
          val kind = Mapper.getDatabaseType(arr.get[String](2))
          val ordering = arr.get[Ordering](3)
          val languageType = LanguageType(Option(arr.get[String](4)))
          val identifier = arr.get[Boolean](5)
          val countryCodes = Option(arr.get[String](6)).map(str => Json.fromArrayString(str).asScala.map({ case code: String => code }).toSeq)

          val selectLang =
            s"""SELECT langtag, name, description
                | FROM system_columns_lang
                | WHERE table_id = ? AND column_id = ?""".stripMargin

          for {
            result <- connection.query(selectLang, Json.arr(table.id, columnId))
            dis = resultObjectToJsonArray(result).map { arr =>
              DisplayInfos.fromString(arr.getString(0), arr.getString(1), arr.getString(2))
            }
            res <- mapColumn(depth, table, columnId, columnName, kind, ordering, languageType, identifier, dis, countryCodes)
          } yield res
        }
        Future.sequence(futures)
      }
    } yield (mappedColumns.filter(_.identifier), mappedColumns)
  }

  private def mapColumn(depth: Int, table: Table, columnId: ColumnId, columnName: String, kind: TableauxDbType, ordering: Ordering, languageType: LanguageType, identifier: Boolean, displayInfos: Seq[DisplayInfo], countryCodes: Option[Seq[String]]): Future[ColumnType[_]] = {
    kind match {
      case AttachmentType => mapAttachmentColumn(table, columnId, columnName, ordering, identifier, displayInfos)
      case LinkType => mapLinkColumn(depth, table, columnId, columnName, ordering, identifier, displayInfos)

      case _ => mapValueColumn(table, columnId, columnName, kind, ordering, languageType, identifier, displayInfos, countryCodes)
    }
  }

  private def mapValueColumn(table: Table, columnId: ColumnId, columnName: String, kind: TableauxDbType, ordering: Ordering, languageType: LanguageType, identifier: Boolean, displayInfos: Seq[DisplayInfo], countryCodes: Option[Seq[String]]): Future[ColumnType[_]] = {
    Future(Mapper(languageType, kind).apply(table, columnId, columnName, ordering, identifier, displayInfos, countryCodes))
  }

  private def mapAttachmentColumn(table: Table, columnId: ColumnId, columnName: String, ordering: Ordering, identifier: Boolean, displayInfos: Seq[DisplayInfo]): Future[AttachmentColumn] = {
    Future(AttachmentColumn(table, columnId, columnName, ordering, identifier, displayInfos))
  }

  private def mapLinkColumn(depth: Int, fromTable: Table, linkColumnId: ColumnId, columnName: String, ordering: Ordering, identifier: Boolean, displayInfos: Seq[DisplayInfo]): Future[LinkColumn[_]] = {
    for {
      (linkId, linkDirection, toTable) <- getLinkInformation(fromTable, linkColumnId)

      foreignColumns <- {
        if (depth > 0) {
          retrieveIdentifiers(toTable, depth - 1)
        } else {
          Future.failed(DatabaseException("Link is too deep. Check schema.", "link-depth"))
        }
      }

      // Link should point at ConcatColumn if defined
      // Otherwise use the first column which should be an identifier
      // If no identifier is defined, use the first column. Period.
      toColumnOpt = foreignColumns.headOption
    } yield {
      if (toColumnOpt.isEmpty) {
        throw new NotFoundInDatabaseException(s"Link points at table ${toTable.id} without columns", "no-columns")
      }

      val toColumn = toColumnOpt.get.asInstanceOf[ValueColumn[_]]
      LinkColumn(fromTable, linkColumnId, toColumn, linkId, linkDirection, columnName, ordering, identifier, displayInfos)
    }
  }

  private def getLinkInformation(fromTable: Table, columnId: ColumnId): Future[(LinkId, LinkDirection, Table)] = {
    for {
      result <- connection.query(
        """
          |SELECT
          | table_id_1,
          | table_id_2,
          | link_id
          |FROM system_link_table
          |WHERE link_id = (
          |    SELECT link_id
          |    FROM system_columns
          |    WHERE table_id = ? AND column_id = ?
          |)""".stripMargin, Json.arr(fromTable.id, columnId))

      (linkId, linkDirection) = {
        val res = selectNotNull(result).head

        val table1 = res.getLong(0).toLong
        val table2 = res.getLong(1).toLong
        val linkId = res.getLong(2).toLong

        (linkId, LinkDirection(fromTable.id, table1, table2))
      }

      toTable <- tableStruc.retrieve(linkDirection.to)

    } yield (linkId, linkDirection, toTable)
  }

  def deleteLinkBothDirections(table: Table, columnId: ColumnId): Future[Unit] = delete(table, columnId, bothDirections = true)

  def delete(table: Table, columnId: ColumnId): Future[Unit] = delete(table, columnId, bothDirections = false)

  private def delete(table: Table, columnId: ColumnId, bothDirections: Boolean): Future[Unit] = {
    // Retrieve all filter for columnId and check if columns is not empty
    // If columns is empty last column would be deleted => error
    for {
      columns <- retrieveAll(table)
        .filter(_.nonEmpty)
        .recoverWith({
          case _: NoSuchElementException => Future.failed(NotFoundInDatabaseException("No column found at all", "no-column-found"))
        })

      _ <- Future.successful(columns)
        .filter(!_.forall(_.id == columnId))
        .recoverWith({
          case _: NoSuchElementException => Future.failed(DatabaseException("Last column can't be deleted", "delete-last-column"))
        })

      column = columns
        .find(_.id == columnId)
        .getOrElse(throw NotFoundInDatabaseException("Column can't be deleted because it doesn't exist.", "delete-non-existing"))

      _ <- {
        column match {
          case c: ConcatColumn => Future.failed(DatabaseException("ConcatColumn can't be deleted", "delete-concat"))
          case c: LinkColumn[_] => deleteLink(c, bothDirections)
          case c: AttachmentColumn => deleteAttachment(c)
          case c: ColumnType[_] => deleteSimpleColumn(c)
        }
      }
    } yield ()
  }

  private def deleteLink(column: LinkColumn[_], bothDirections: Boolean): Future[Unit] = {
    for {
      t <- connection.begin()

      (t, result) <- t.query("SELECT link_id FROM system_columns WHERE column_id = ? AND table_id = ?", Json.arr(column.id, column.table.id))
      linkId = selectCheckSize(result, 1).head.get[Long](0)

      (t, result) <- t.query("SELECT COUNT(*) = 1 FROM system_columns WHERE link_id = ?", Json.arr(linkId))
      unidirectional = selectCheckSize(result, 1).head.getBoolean(0).booleanValue()

      (t, _) <- {
        // We only want to delete both directions
        // when we delete one of the two tables
        // which are linked together.
        // ColumnModel.deleteLink() with both = true
        // is called by StructureController.deleteTable().
        val deleteFuture = if (bothDirections) {
          t.query("DELETE FROM system_columns WHERE link_id = ?", Json.arr(linkId))
        } else {
          t.query("DELETE FROM system_columns WHERE column_id = ? AND table_id = ?", Json.arr(column.id, column.table.id))
        }

        deleteFuture.flatMap({
          case (t, _) =>
            if (unidirectional || bothDirections) {
              // drop link_table if link is unidirectional or
              // both directions where forcefully deleted
              t.query(s"DROP TABLE IF EXISTS link_table_$linkId")
            } else {
              Future.successful((t, Json.obj()))
            }
        })
      }

      _ <- t.commit()
    } yield ()
  }

  private def deleteAttachment(column: AttachmentColumn): Future[Unit] = {
    for {
      t <- connection.begin()

      (t, _) <- t.query("DELETE FROM system_attachment WHERE column_id = ? AND table_id = ?", Json.arr(column.id, column.table.id))
      (t, _) <- t.query("DELETE FROM system_columns WHERE column_id = ? AND table_id = ?", Json.arr(column.id, column.table.id)).map({ case (t, json) => (t, deleteNotNull(json)) })

      _ <- t.commit()
    } yield ()
  }

  private def deleteSimpleColumn(column: ColumnType[_]): Future[Unit] = {
    val tableId = column.table.id
    val columnId = column.id

    for {
      t <- connection.begin()

      (t, _) <- t.query(s"ALTER TABLE user_table_$tableId DROP COLUMN IF EXISTS column_$columnId")
      (t, _) <- t.query(s"ALTER TABLE user_table_lang_$tableId DROP COLUMN IF EXISTS column_$columnId")

      (t, _) <- t.query("DELETE FROM system_columns WHERE column_id = ? AND table_id = ?", Json.arr(columnId, tableId)).map({ case (t, json) => (t, deleteNotNull(json)) })

      _ <- t.commit()
    } yield ()
  }

  def change(table: Table, columnId: ColumnId, columnName: Option[String], ordering: Option[Ordering], kind: Option[TableauxDbType], identifier: Option[Boolean], displayName: Option[JsonObject], description: Option[JsonObject], countryCodes: Option[Seq[String]]): Future[ColumnType[_]] = {
    val tableId = table.id

    for {
      t <- connection.begin()

      // change column settings
      (t, resultName) <- optionToValidFuture(columnName, t, { name: String => t.query(s"UPDATE system_columns SET user_column_name = ? WHERE table_id = ? AND column_id = ?", Json.arr(name, tableId, columnId)) })
      (t, resultOrdering) <- optionToValidFuture(ordering, t, { ord: Ordering => t.query(s"UPDATE system_columns SET ordering = ? WHERE table_id = ? AND column_id = ?", Json.arr(ord, tableId, columnId)) })
      (t, resultKind) <- optionToValidFuture(kind, t, { k: TableauxDbType => t.query(s"UPDATE system_columns SET column_type = ? WHERE table_id = ? AND column_id = ?", Json.arr(k.name, tableId, columnId)) })
      (t, resultIdentifier) <- optionToValidFuture(identifier, t, { ident: Boolean => t.query(s"UPDATE system_columns SET identifier = ? WHERE table_id = ? AND column_id = ?", Json.arr(ident, tableId, columnId)) })
      (t, resultCountryCodes) <- optionToValidFuture(countryCodes, t, { codes: Seq[String] => t.query(s"UPDATE system_columns SET country_codes = ? WHERE table_id = ? AND column_id = ?", Json.arr(Json.arr(codes: _*), tableId, columnId)) })

      // change display information
      t <- insertOrUpdateColumnLangInfo(t, table, columnId, displayName, description)

      // change column kind
      (t, _) <- optionToValidFuture(kind, t, { k: TableauxDbType => t.query(s"ALTER TABLE user_table_$tableId ALTER COLUMN column_$columnId TYPE ${k.toDbType} USING column_$columnId::${k.toDbType}") })
        .recoverWith(t.rollbackAndFail())

      _ <- Future(checkUpdateResults(resultName, resultOrdering, resultKind, resultIdentifier, resultCountryCodes))
        .recoverWith(t.rollbackAndFail())

      _ <- t.commit()

      column <- retrieve(table, columnId)
    } yield column
  }

  private def insertOrUpdateColumnLangInfo(t: connection.Transaction, table: Table, columnId: ColumnId, displayName: Option[JsonObject], description: Option[JsonObject]): Future[connection.Transaction] = {

    val map: Map[String, (Option[String], Option[String])] = (displayName, description) match {
      case (Some(name), Some(desc)) => zipMultilanguageObjects(name, desc)
      case (Some(name), None) => zipMultilanguageObjects(name, Json.obj())
      case (None, Some(desc)) => zipMultilanguageObjects(Json.obj(), desc)
      case (None, None) => Map.empty
    }

    map.foldLeft(Future.successful(t)) {
      case (future, (key, (nameOpt, descOpt))) =>
        for {
          t <- future
          (t, select) <- t.query("SELECT COUNT(*) FROM system_columns_lang WHERE table_id = ? AND column_id = ? AND langtag = ?", Json.arr(table.id, columnId, key))
          count = select.getJsonArray("results").getJsonArray(0).getLong(0)
          stmt = if (count > 0) {
            updateStatementFromOptions(nameOpt, descOpt)
          } else {
            insertStatementFromOptions(nameOpt, descOpt)
          }
          binds = nameOpt.toList ::: descOpt.toList ::: List(table.id, columnId, key)
          (t, inserted) <- t.query(stmt, Json.arr(binds: _*))
        } yield t
    }
  }

  private def zipMultilanguageObjects(a: JsonObject, b: JsonObject): Map[String, (Option[String], Option[String])] = {
    import scala.collection.JavaConverters._
    Map((for {
      key <- a.fieldNames().asScala ++ b.fieldNames().asScala
    } yield {
      (key,
        (someNullIfExplicitlySet(a, key),
          someNullIfExplicitlySet(b, key)))
    }).toSeq: _*)
  }

  private def someNullIfExplicitlySet(json: JsonObject, key: String): Option[String] = {
    if (json.fieldNames().contains(key)) Some(json.getString(key)) else Option(json.getString(key))
  }

  private def insertStatementFromOptions(nameOpt: Option[String], descOpt: Option[String]): String = {
    val nameAndDesc = nameOpt.map(_ => "name").toList ::: descOpt.map(_ => "description").toList
    s"INSERT INTO system_columns_lang (${nameAndDesc.mkString(", ")}, table_id, column_id, langtag) VALUES (${nameAndDesc.map(_ => "?").mkString(", ")}, ?, ?, ?)"
  }

  private def updateStatementFromOptions(nameOpt: Option[String], descOpt: Option[String]): String = {
    val nameAndDesc = nameOpt.map(_ => "name = ?").toList ::: descOpt.map(_ => "description = ?").toList
    s"UPDATE system_columns_lang SET ${nameAndDesc.mkString(", ")} WHERE table_id = ? AND column_id = ? AND langtag = ?"
  }
}
