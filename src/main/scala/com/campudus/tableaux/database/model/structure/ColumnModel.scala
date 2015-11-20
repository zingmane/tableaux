package com.campudus.tableaux.database.model.structure

import com.campudus.tableaux.database._
import com.campudus.tableaux.database.domain._
import com.campudus.tableaux.database.model.TableauxModel._
import com.campudus.tableaux.helper.ResultChecker._
import org.vertx.scala.core.json._

import scala.concurrent.Future

class ColumnModel(val connection: DatabaseConnection) extends DatabaseQuery {

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
      case CreateSimpleColumn(name, ordering, kind, languageType) =>
        createValueColumn(table.id, kind, name, ordering, languageType).map {
          case (id, ordering) => Mapper(languageType, kind).apply(table, id, name, ordering)
        }

      case CreateLinkColumn(name, ordering, linkConnection, toName) => for {
        toCol <- retrieve(linkConnection.toTableId, linkConnection.toColumnId).asInstanceOf[Future[SimpleValueColumn[_]]]
        (linkId, id, ordering) <- createLinkColumn(table, name, toName, linkConnection, ordering)
      } yield LinkColumn(table, id, toCol, (linkId, "id_1", "id_2"), name, ordering)

      case CreateAttachmentColumn(name, ordering) =>
        createAttachmentColumn(table.id, name, ordering).map {
          case (id, ordering) => AttachmentColumn(table, id, name, ordering)
        }
    }
  }

  private def createValueColumn(tableId: TableId, kind: TableauxDbType, name: String, ordering: Option[Ordering], languageType: LanguageType): Future[(ColumnId, Ordering)] = {
    connection.transactional { t =>
      for {
        (t, resultJson) <- insertSystemColumn(t, tableId, name, kind, ordering, None, languageType)
        resultRow = insertNotNull(resultJson).head

        (t, _) <- languageType match {
          case MultiLanguage => t.query(s"ALTER TABLE user_table_lang_$tableId ADD column_${resultRow.get[ColumnId](0)} ${kind.toDbType}")
          case SingleLanguage => t.query(s"ALTER TABLE user_table_$tableId ADD column_${resultRow.get[ColumnId](0)} ${kind.toDbType}")
        }
      } yield {
        (t, (resultRow.get[ColumnId](0), resultRow.get[Ordering](1)))
      }
    }
  }

  private def createAttachmentColumn(tableId: TableId, name: String, ordering: Option[Ordering]): Future[(ColumnId, Ordering)] = {
    connection.transactional { t =>
      for {
        (t, result) <- insertSystemColumn(t, tableId, name, AttachmentType, ordering, None, SingleLanguage)
        result <- Future.successful(insertNotNull(result).head)
      } yield (t, (result.get[ColumnId](0), result.get[Ordering](1)))
    }
  }

  private def createLinkColumn(table: Table, name: String, toName: Option[String], link: LinkConnection, ordering: Option[Ordering]): Future[(Long, ColumnId, Ordering)] = {
    val tableId = table.id
    val fromColumnId = link.fromColumnId
    val toTableId = link.toTableId
    val toColumnId = link.toColumnId

    connection.transactional { t =>
      for {
        (t, result) <- t.query("INSERT INTO system_link_table (table_id_1, table_id_2, column_id_1, column_id_2) VALUES (?, ?, ?, ?) RETURNING link_id", Json.arr(tableId, toTableId, fromColumnId, toColumnId))
        linkId <- Future(insertNotNull(result).head.get[Long](0))

        // insert link column on source table
        (t, result) <- insertSystemColumn(t, tableId, name, LinkType, ordering, Some(linkId), SingleLanguage)

        // only add the second link column if tableId != toTableId
        (t, _) <- {
          if (tableId != toTableId) {
            insertSystemColumn(t, toTableId, toName.getOrElse(table.name), LinkType, None, Some(linkId), SingleLanguage)
          } else {
            Future((t, Json.emptyObj()))
          }
        }

        (t, _) <- t.query(
          s"""
             |CREATE TABLE link_table_$linkId (
             |id_1 bigint,
             |id_2 bigint,
             |PRIMARY KEY(id_1, id_2),
             |CONSTRAINT link_table_${linkId}_foreign_1
             |FOREIGN KEY(id_1)
             |REFERENCES user_table_$tableId (id)
             |ON DELETE CASCADE,
             |CONSTRAINT link_table_${linkId}_foreign_2
             |FOREIGN KEY(id_2)
             |REFERENCES user_table_$toTableId (id)
             |ON DELETE CASCADE
             |)""".stripMargin)
      } yield {
        val json = insertNotNull(result).head

        (t, (linkId, json.get[ColumnId](0), json.get[Ordering](1)))
      }
    }
  }

  private def insertSystemColumn(t: connection.Transaction, tableId: TableId, name: String, kind: TableauxDbType, ordering: Option[Ordering], linkId: Option[Long], languageType: LanguageType): Future[(connection.Transaction, JsonObject)] = {
    def insertStatement(tableId: TableId, ordering: String) =
      s"""INSERT INTO system_columns (table_id, column_id, column_type, user_column_name, ordering, link_id, multilanguage) VALUES (
          |?, nextval('system_columns_column_id_table_$tableId'), ?, ?, $ordering, ?, ?) RETURNING column_id, ordering""".stripMargin

    for {
      (t, result) <- ordering match {
        case None => t.query(insertStatement(tableId, s"currval('system_columns_column_id_table_$tableId')"), Json.arr(tableId, kind.name, name, linkId.orNull, languageType.toBoolean))
        case Some(ord) => t.query(insertStatement(tableId, "?"), Json.arr(tableId, kind.name, name, ord, linkId.orNull, languageType.toBoolean))
      }
    } yield (t, result)
  }

  def retrieve(tableId: TableId, columnId: ColumnId): Future[ColumnType[_]] = {
    val select = "SELECT column_id, user_column_name, column_type, ordering, multilanguage FROM system_columns WHERE table_id = ? AND column_id = ?"
    for {
      result <- {
        val json = connection.query(select, Json.arr(tableId, columnId))
        json.map(selectNotNull(_).head)
      }
      mappedColumn <- mapColumn(tableId, result.get[ColumnId](0), result.get[String](1), Mapper.getDatabaseType(result.get[String](2)), result.get[Ordering](3), LanguageType(result.get[Boolean](4)))
    } yield mappedColumn
  }

  def retrieveAll(tableId: TableId): Future[Seq[ColumnType[_]]] = {
    val select = "SELECT column_id, user_column_name, column_type, ordering, multilanguage FROM system_columns WHERE table_id = ? ORDER BY ordering, column_id"
    for {
      result <- connection.query(select, Json.arr(tableId))
      mappedColumns <- {
        val futures = getSeqOfJsonArray(result).map { arr =>
          val columnId = arr.get[ColumnId](0)
          val columnName = arr.get[String](1)
          val kind = Mapper.getDatabaseType(arr.get[String](2))
          val ordering = arr.get[Ordering](3)
          val languageType = LanguageType(arr.get[Boolean](4))

          mapColumn(tableId, columnId, columnName, kind, ordering, languageType)
        }
        Future.sequence(futures)
      }
    } yield mappedColumns
  }

  private def mapColumn(tableId: TableId, columnId: ColumnId, columnName: String, kind: TableauxDbType, ordering: Ordering, languageType: LanguageType): Future[ColumnType[_]] = {
    val table = Table(tableId, "")

    kind match {
      case AttachmentType => mapAttachmentColumn(table, columnId, columnName, ordering)
      case LinkType => mapLinkColumn(table, columnId, columnName, ordering)

      case kind: TableauxDbType => mapValueColumn(table, columnId, columnName, kind, ordering, languageType)
    }
  }

  private def mapValueColumn(table: Table, columnId: ColumnId, columnName: String, kind: TableauxDbType, ordering: Ordering, languageType: LanguageType): Future[ColumnType[_]] = {
    Future(Mapper(languageType, kind).apply(table, columnId, columnName, ordering))
  }

  private def mapAttachmentColumn(table: Table, columnId: ColumnId, columnName: String, ordering: Ordering): Future[AttachmentColumn] = {
    Future(AttachmentColumn(table, columnId, columnName, ordering))
  }

  private def mapLinkColumn(fromTable: Table, linkColumnId: ColumnId, columnName: String, ordering: Ordering): Future[LinkColumn[_]] = {
    for {
      (linkId, id_1, id_2, toTableId, toColumnId) <- getToColumn(fromTable.id, linkColumnId)

      toCol <- retrieve(toTableId, toColumnId).asInstanceOf[Future[SimpleValueColumn[_]]]
    } yield {
      LinkColumn(fromTable, linkColumnId, toCol, (linkId, id_1, id_2), columnName, ordering)
    }
  }

  private def getToColumn(tableId: TableId, columnId: ColumnId): Future[(Long, String, String, TableId, ColumnId)] = {
    for {
      result <- connection.query(
        """
          |SELECT
          | table_id_1,
          | table_id_2,
          | column_id_1,
          | column_id_2,
          | link_id
          |FROM system_link_table
          |WHERE link_id = (
          |    SELECT link_id
          |    FROM system_columns
          |    WHERE table_id = ? AND column_id = ?
          |)""".stripMargin, Json.arr(tableId, columnId))

      (linkId, id_1, id_2, toTableId, toColumnId) <- Future.successful {
        val res = selectNotNull(result).head

        val linkId = res.get[Long](4)
        val table1 = res.get[TableId](0)

        /* we need this because links can go both ways */
        if (tableId == table1) {
          (linkId, "id_1", "id_2", res.get[TableId](1), res.get[ColumnId](3))
        } else {
          (linkId, "id_2", "id_1", res.get[TableId](0), res.get[ColumnId](2))
        }
      }
    } yield (linkId, id_1, id_2, toTableId, toColumnId)
  }

  def delete(tableId: TableId, columnId: ColumnId): Future[Unit] = {
    for {
      column <- retrieve(tableId, columnId)
      _ <- {
        column match {
          case c: LinkColumn[_] => deleteLink(c)
          case c: AttachmentColumn => deleteAttachment(c)
          case c: ColumnType[_] => deleteSimpleColumn(c)
        }
      }
    } yield ()
  }

  private def deleteLink(column: LinkColumn[_]): Future[Unit] = {
    for {
      t <- connection.begin()

      (t, result) <- t.query("SELECT link_id FROM system_columns WHERE column_id = ? AND table_id = ?", Json.arr(column.id, column.table.id))
      linkId <- Future(selectCheckSize(result, 1).head.get[Long](0)) recoverWith t.rollbackAndFail()

      (t, result) <- t.query("DELETE FROM system_columns WHERE link_id = ?", Json.arr(linkId))
      _ <- Future(deleteCheckSize(result, 2)) recoverWith t.rollbackAndFail()

      (t, _) <- t.query(s"DROP TABLE IF EXISTS link_table_$linkId")

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

  def change(tableId: TableId, columnId: ColumnId, columnName: Option[String], ordering: Option[Ordering], kind: Option[TableauxDbType]): Future[Unit] = for {
    t <- connection.begin()

    (t, result1) <- optionToValidFuture(columnName, t, { name: String => t.query(s"UPDATE system_columns SET user_column_name = ? WHERE table_id = ? AND column_id = ?", Json.arr(name, tableId, columnId)) })
    (t, result2) <- optionToValidFuture(ordering, t, { ord: Ordering => t.query(s"UPDATE system_columns SET ordering = ? WHERE table_id = ? AND column_id = ?", Json.arr(ord, tableId, columnId)) })
    (t, result3) <- optionToValidFuture(kind, t, { k: TableauxDbType => t.query(s"UPDATE system_columns SET column_type = ? WHERE table_id = ? AND column_id = ?", Json.arr(k.name, tableId, columnId)) })

    (t, _) <- optionToValidFuture(kind, t, { k: TableauxDbType => t.query(s"ALTER TABLE user_table_$tableId ALTER COLUMN column_$columnId TYPE ${k.toDbType} USING column_$columnId::${k.toDbType}") })

    _ <- Future(checkUpdateResults(result1, result2, result3)) recoverWith t.rollbackAndFail()

    _ <- t.commit()
  } yield ()

  private def checkUpdateResults(seq: JsonObject*): Unit = seq map {
    json => if (json.containsField("message")) updateNotNull(json)
  }

  private def optionToValidFuture[A, B](opt: Option[A], trans: B, someCase: A => Future[(B, JsonObject)]): Future[(B, JsonObject)] = opt match {
    case Some(x) => someCase(x)
    case None => Future.successful(trans, Json.obj())
  }
}
