package com.scylladb.migrator

import com.datastax.spark.connector._
import com.datastax.spark.connector.cql._
import com.datastax.spark.connector.rdd.ReadConf
import com.datastax.spark.connector.writer.{ CassandraRowWriter, WriteConf }
import com.google.common.math.DoubleMath
import org.apache.log4j.{ Level, LogManager, Logger }
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.joda.time.{ DateTime, DateTimeZone }

case class RowComparisonFailure(row: CassandraRow,
                                other: Option[CassandraRow],
                                items: List[RowComparisonFailure.Item]) {
  override def toString: String =
    s"""
       |Row failure:
       |* Source row: ${row}
       |* Target row: ${other.map(_.toString).getOrElse("<MISSING>")}
       |* Failures:
       |${items.map(item => s"  - ${item.description}").mkString("\n")}
     """.stripMargin
}
object RowComparisonFailure {
  sealed abstract class Item(val description: String) extends Serializable
  object Item {
    case object MissingTargetRow extends Item("Missing target row")
    case object MismatchedColumnCount extends Item("Mismatched column count")
    case object MismatchedColumnNames extends Item("Mismatched column names")
    case class DifferingFieldValues(fields: List[String])
        extends Item(s"Differing fields: ${fields.mkString(", ")}")
    case class DifferingTtls(details: List[(String, Long)])
        extends Item(s"Differing TTLs: ${details
          .map {
            case (fieldName, ttlDiff) => s"$fieldName ($ttlDiff millis)"
          }
          .mkString(", ")}")
    case class DifferingWritetimes(details: List[(String, Long)])
        extends Item(s"Differing WRITETIMEs: ${details
          .map {
            case (fieldName, writeTimeDiff) => s"$fieldName ($writeTimeDiff millis)"
          }
          .mkString(", ")}")
  }

  def compareRows(left: CassandraRow,
                  right: Option[CassandraRow],
                  writetimeCutoff: Long,
                  floatingPointTolerance: Double,
                  ttlToleranceMillis: Long,
                  writetimeToleranceMillis: Long,
                  compareTimestamps: Boolean): Option[RowComparisonFailure] =
    right match {
      case None =>
        // If we're checking timestamps, check if the item could have already expired
        // (e.g. if the tolerance is 2s and left has a TTL of 1s, don't indicate a failure)
        val ttlColumnValues =
          left.metaData.columnNames.filter(_.endsWith("_ttl")).flatMap(left.getLongOption(_))
        val rightRowCouldHaveExpired =
          if (!compareTimestamps || ttlColumnValues.isEmpty) false
          else ttlColumnValues.forall(_ <= ttlToleranceMillis)
        if (rightRowCouldHaveExpired) None
        else Some(RowComparisonFailure(left, right, List(Item.MissingTargetRow)))
      case Some(right) if left.columnValues.size != right.columnValues.size =>
        Some(RowComparisonFailure(left, Some(right), List(Item.MismatchedColumnCount)))
      case Some(right) if left.metaData.columnNames != right.metaData.columnNames =>
        Some(RowComparisonFailure(left, Some(right), List(Item.MismatchedColumnNames)))
      case Some(right) =>
        val names = left.metaData.columnNames

        val leftMap = left.toMap
        val rightMap = right.toMap

        val log = LogManager.getLogger("com.scylladb.migrator")

        val differingFieldValues =
          for {
            name <- names
            if !name.endsWith("_ttl") && !name.endsWith("_writetime")

            leftValue  = leftMap.get(name)
            rightValue = rightMap.get(name)

            hasDiff = (rightValue, leftValue) match {
              // All floating-point-like types need to be compared with a configured tolerance
              case (Some(l: Float), Some(r: Float)) =>
                !DoubleMath.fuzzyEquals(l, r, floatingPointTolerance)
              case (Some(l: Double), Some(r: Double)) =>
                !DoubleMath.fuzzyEquals(l, r, floatingPointTolerance)
              case (Some(l: java.math.BigDecimal), Some(r: java.math.BigDecimal)) =>
                l.subtract(r)
                  .abs()
                  .compareTo(new java.math.BigDecimal(floatingPointTolerance)) > 0

              // CQL blobs get converted to byte buffers by the Java driver, and the
              // byte buffers are converted to byte arrays by the Spark connector.
              // Arrays can't be compared with standard equality and must be compared
              // with `sameElements`.
              case (Some(l: Array[_]), Some(r: Array[_])) =>
                !l.sameElements(r)
              /*
              // If a null value is encountered, ensure the non-null TTL isn't within the threshold
              // (if it is, it's perfectly reasonable to expect that the opposing value would be null)
              case (Some(null), Some(null)) => false
              case (Some(_), Some(null)) => {
                log.info(
                  s"HELLO ${left} ${left.getLongOption(name + "_ttl")} ${ttlToleranceMillis} ${left
                    .getLongOption(name + "_ttl")
                    .map(_ > ttlToleranceMillis)}")
                !compareTimestamps || left
                  .getLongOption(name + "_ttl")
                  .map(_ > ttlToleranceMillis)
                  .getOrElse(true)
              }
              case (Some(null), Some(_)) => {
                log.info(
                  s"HELLO ${right} ${right.getLongOption(name + "_ttl")} ${ttlToleranceMillis} ${right
                    .getLongOption(name + "_ttl")
                    .map(_ > ttlToleranceMillis)}")
                !compareTimestamps || right
                  .getLongOption(name + "_ttl")
                  .map(_ > ttlToleranceMillis)
                  .getOrElse(true)
              }
               */
              // All remaining types get compared with standard equality
              case (Some(l), Some(r)) => l != r
              case (Some(_), None)    => true
              case (None, Some(_))    => true
              case (None, None)       => false
            }

            // Diffs are ignored if the column was written to before the cutoff.
            // This is because reading from the two clusters is not atomic, and
            // any updates during validation could result in a false diff.
            writetimeName = name + "_writetime"
            result = if (hasDiff && !compareTimestamps && left.contains(writetimeName)) {
              val leftWritetimeValue = left.getLongOption(writetimeName)
              val rightWritetimeValue = right.getLongOption(writetimeName)
              (leftWritetimeValue, rightWritetimeValue) match {
                case (Some(l), Some(r)) => l < writetimeCutoff && r < writetimeCutoff
                case _                  => hasDiff
              }
            } else {
              hasDiff
            }
            if result
          } yield name

        val differingTtls =
          if (!compareTimestamps) Nil
          else
            for {
              name <- names
              if name.endsWith("_ttl")
              leftTtl  = left.getLongOption(name)
              rightTtl = right.getLongOption(name)
              result <- (leftTtl, rightTtl) match {
                         case (Some(l), Some(r)) if math.abs(l - r) > ttlToleranceMillis =>
                           Some(name -> math.abs(l - r))
                         case (Some(l), None)    => Some(name -> l)
                         case (None, Some(r))    => Some(name -> r)
                         case (Some(l), Some(r)) => None
                         case (None, None)       => None
                       }
            } yield result

        // WRITETIME is expressed in microseconds
        val writetimeToleranceMicros = writetimeToleranceMillis * 1000
        val differingWritetimes =
          if (!compareTimestamps) Nil
          else
            for {
              name <- names
              if name.endsWith("_writetime")
              leftWritetime  = left.getLongOption(name)
              rightWritetime = right.getLongOption(name)
              result <- (leftWritetime, rightWritetime) match {
                         case (Some(l), Some(r)) if math.abs(l - r) > writetimeToleranceMicros =>
                           Some(name -> math.abs(l - r))
                         case (Some(l), None)    => Some(name -> l)
                         case (None, Some(r))    => Some(name -> r)
                         case (Some(l), Some(r)) => None
                         case (None, None)       => None
                       }
            } yield result

        if (differingFieldValues.isEmpty && differingTtls.isEmpty && differingWritetimes.isEmpty)
          None
        else
          Some(
            RowComparisonFailure(
              left,
              Some(right),
              (if (differingFieldValues.nonEmpty)
                 List(Item.DifferingFieldValues(differingFieldValues.toList))
               else Nil) ++
                (if (differingTtls.nonEmpty) List(Item.DifferingTtls(differingTtls.toList))
                 else Nil) ++
                (if (differingWritetimes.nonEmpty)
                   List(Item.DifferingWritetimes(differingWritetimes.toList))
                 else Nil)
            )
          )
    }
}

object Validator {
  val log = LogManager.getLogger("com.scylladb.migrator")

  def runValidation(config: MigratorConfig)(
    implicit spark: SparkSession): RDD[RowComparisonFailure] = {
    val sourceConnector: CassandraConnector =
      Connectors.sourceConnector(spark.sparkContext.getConf, config.source)
    val targetConnector: CassandraConnector =
      Connectors.targetConnector(spark.sparkContext.getConf, config.target)

    val writetimeCutoff = DateTime.now(DateTimeZone.UTC).getMillis() * 1000;

    val renameMap = config.renames.map(rename => rename.from -> rename.to).toMap
    val sourceTableDef =
      Schema.tableFromCassandra(sourceConnector, config.source.keyspace, config.source.table)

    val source = {
      val regularColumnsProjection =
        sourceTableDef.regularColumns.flatMap { colDef =>
          val alias = renameMap.getOrElse(colDef.columnName, colDef.columnName)

          if (config.preserveTimestamps)
            List(
              ColumnName(colDef.columnName, Some(alias)),
              WriteTime(colDef.columnName, Some(alias + "_writetime")),
              TTL(colDef.columnName, Some(alias + "_ttl"))
            )
          else if (!colDef.isCollection)
            List(
              ColumnName(colDef.columnName, Some(alias)),
              WriteTime(colDef.columnName, Some(alias + "_writetime"))
            )
          else
            List(ColumnName(colDef.columnName))
        }

      val primaryKeyProjection =
        (sourceTableDef.partitionKey ++ sourceTableDef.clusteringColumns)
          .map(colDef => ColumnName(colDef.columnName, renameMap.get(colDef.columnName)))

      spark.sparkContext
        .cassandraTable(config.source.keyspace, config.source.table)
        .withConnector(sourceConnector)
        .withReadConf(
          ReadConf
            .fromSparkConf(spark.sparkContext.getConf)
            .copy(
              splitCount      = config.source.splitCount,
              fetchSizeInRows = config.source.fetchSize
            )
        )
        .select(primaryKeyProjection ++ regularColumnsProjection: _*)
    }

    val joined = {
      val regularColumnsProjection =
        sourceTableDef.regularColumns.flatMap { colDef =>
          val renamedColName = renameMap.getOrElse(colDef.columnName, colDef.columnName)

          if (config.preserveTimestamps)
            List(
              ColumnName(renamedColName),
              WriteTime(renamedColName, Some(renamedColName + "_writetime")),
              TTL(renamedColName, Some(renamedColName + "_ttl"))
            )
          else if (!colDef.isCollection)
            List(
              ColumnName(renamedColName),
              WriteTime(renamedColName, Some(renamedColName + "_writetime"))
            )
          else
            List(ColumnName(renamedColName))
        }

      val primaryKeyProjection =
        (sourceTableDef.partitionKey ++ sourceTableDef.clusteringColumns)
          .map(colDef => ColumnName(renameMap.getOrElse(colDef.columnName, colDef.columnName)))

      val joinKey = (sourceTableDef.partitionKey ++ sourceTableDef.clusteringColumns)
        .map(colDef => ColumnName(renameMap.getOrElse(colDef.columnName, colDef.columnName)))

      source
        .leftJoinWithCassandraTable(
          config.target.keyspace,
          config.target.table,
          SomeColumns(primaryKeyProjection ++ regularColumnsProjection: _*),
          SomeColumns(joinKey: _*))
        .withConnector(targetConnector)
    }

    joined
      .flatMap {
        case (l, r) =>
          RowComparisonFailure.compareRows(
            l,
            r,
            writetimeCutoff,
            config.validation.floatingPointTolerance,
            config.validation.ttlToleranceMillis,
            config.validation.writetimeToleranceMillis,
            config.validation.compareTimestamps
          )
      }
  }

  def remediateValidation(config: MigratorConfig, rdd: RDD[RowComparisonFailure])(
    implicit spark: SparkSession): RDD[CassandraRow] = {
    val sourceConnector: CassandraConnector =
      Connectors.sourceConnector(spark.sparkContext.getConf, config.source)
    val targetConnector: CassandraConnector =
      Connectors.targetConnector(spark.sparkContext.getConf, config.target)
    val writeConf = WriteConf.fromSparkConf(spark.sparkContext.getConf)
    val columnRefs =
      Schema
        .tableFromCassandra(targetConnector, config.target.keyspace, config.target.table)
        .columnRefs

    // get all repairable rows
    val rows = rdd
      .flatMap { failure =>
        (failure) match {
          case RowComparisonFailure(row, _, List(RowComparisonFailure.Item.MissingTargetRow)) =>
            Some(row)
          case RowComparisonFailure(
              row,
              _,
              List(RowComparisonFailure.Item.DifferingFieldValues(_))) =>
            Some(row)
          case default => {
            log.error("Unrepairable comparison failure:\n${default.mkString()}")
            None
          }
        }
      }

    val newRows = rows
      .joinWithCassandraTable(
        config.target.keyspace,
        config.target.table
      )
      .withConnector(sourceConnector)
      .withReadConf(ReadConf.fromSparkConf(spark.sparkContext.getConf))
      .map { case (_, row) => row }

    newRows
      .saveToCassandra(
        config.target.keyspace,
        config.target.table,
        AllColumns,
        WriteConf.fromSparkConf(spark.sparkContext.getConf)
      )(targetConnector, CassandraRowWriter.Factory)
    newRows
  }

  def main(args: Array[String]): Unit = {
    implicit val spark = SparkSession
      .builder()
      .appName("scylla-validator")
      .config("spark.cassandra.dev.customFromDriver", "com.scylladb.migrator.CustomUUIDConverter")
      .config("spark.task.maxFailures", "1024")
      .config("spark.stage.maxConsecutiveAttempts", "60")
      .getOrCreate

    Logger.getRootLogger.setLevel(Level.WARN)
    log.setLevel(Level.INFO)
    Logger.getLogger("org.apache.spark.scheduler.TaskSetManager").setLevel(Level.INFO)
    Logger.getLogger("com.datastax.spark.connector.cql.CassandraConnector").setLevel(Level.INFO)

    val migratorConfig =
      MigratorConfig.loadFrom(spark.conf.get("spark.scylla.config"))

    log.info(s"Loaded config: ${migratorConfig}")

    val failures = runValidation(migratorConfig).cache

    val failureCount = failures.count()
    if (failureCount <= 0) {
      log.info("No comparison failures found - enjoy your day!")
    } else {
      log.error(s"Found ${failureCount} comparison failures")
      val timestamp = DateTime.now(DateTimeZone.UTC).getMillis();
      failures
        .coalesce(1)
        .saveAsTextFile(
          s"gs://dataproc-7290e922-fdf8-4832-a421-dd157b235d2d-us-east1/output/${migratorConfig.source.keyspace}/${migratorConfig.source.table}/${timestamp}/")
      remediateValidation(migratorConfig, failures)
    }
  }
}
