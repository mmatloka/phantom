/*
 * Copyright 2013-2015 Websudos, Limited.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Explicit consent must be obtained from the copyright owner, Websudos Limited before any redistribution is made.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.websudos.phantom.builder.query

import java.util.concurrent.TimeUnit

import com.datastax.driver.core.{ResultSet, Session}
import com.websudos.phantom.builder.syntax.CQLSyntax
import com.websudos.phantom.connectors.KeySpace

import scala.annotation.implicitNotFound
import scala.concurrent.{Await => ScalaAwait, Future => ScalaFuture, ExecutionContext}
import scala.concurrent.duration.FiniteDuration
import org.joda.time.Seconds

import com.twitter.util.{Await => TwitterAwait, Future => TwitterFuture, Duration, StorageUnit}
import com.websudos.phantom.{Manager, CassandraTable}
import com.websudos.phantom.builder._

sealed trait WithBound
sealed trait WithChainned extends WithBound
sealed trait WithUnchainned extends WithBound

sealed trait CompactionBound
sealed trait SpecifiedCompaction extends CompactionBound
sealed trait UnspecifiedCompaction extends CompactionBound


sealed class TablePropertyClause(val qb: CQLQuery) {}

sealed abstract class CompactionStrategy(override val qb: CQLQuery) extends TablePropertyClause(qb)

sealed trait CompactionStrategies {

  private[this] def rootStrategy(strategy: String) = {
    CQLQuery(CQLSyntax.Symbols.`{`).forcePad
      .appendSingleQuote(CQLSyntax.CompactionOptions.`class`)
      .forcePad.append(CQLSyntax.Symbols.`:`)
      .forcePad.appendSingleQuote(strategy)
  }

  sealed class SizeTieredCompactionStrategy(override val qb: CQLQuery) extends CompactionStrategy(qb) {
    def min_sstable_size(unit: StorageUnit): SizeTieredCompactionStrategy = {
      new SizeTieredCompactionStrategy(QueryBuilder.Create.min_sstable_size(qb, unit.toHuman()))
    }

    def sstable_size_in_mb(unit: StorageUnit): SizeTieredCompactionStrategy = {
      new SizeTieredCompactionStrategy(QueryBuilder.Create.sstable_size_in_mb(qb, unit.toHuman()))
    }

    def bucket_high(size: Double): SizeTieredCompactionStrategy = {
      new SizeTieredCompactionStrategy(QueryBuilder.Create.bucket_high(qb, size))
    }

    def bucket_low(size: Double): SizeTieredCompactionStrategy = {
      new SizeTieredCompactionStrategy(QueryBuilder.Create.bucket_low(qb, size))
    }
  }

  case object SizeTieredCompactionStrategy extends SizeTieredCompactionStrategy(rootStrategy(CQLSyntax.CompactionStrategies.SizeTieredCompactionStrategy))
  case object LeveledCompactionStrategy extends CompactionStrategy(rootStrategy(CQLSyntax.CompactionStrategies.SizeTieredCompactionStrategy))
  case object DateTieredCompactionStrategy extends CompactionStrategy(rootStrategy(CQLSyntax.CompactionStrategies.DateTieredCompactionStrategy))
}

sealed class CompressionStrategy(override val qb: CQLQuery) extends TablePropertyClause(qb) {

  def chunk_length_kb(unit: StorageUnit): CompressionStrategy = {
    new CompressionStrategy(QueryBuilder.Create.chunk_length_kb(qb, unit.toHuman()))
  }

  def crc_check_chance(size: Double): CompressionStrategy = {
    new CompressionStrategy(QueryBuilder.Create.crc_check_chance(qb, size))
  }
}

sealed trait CompressionStrategies {

  private[this] def rootStrategy(strategy: String) = {
    CQLQuery(CQLSyntax.Symbols.`{`).forcePad
      .appendSingleQuote(CQLSyntax.CompressionOptions.sstable_compression)
      .forcePad.append(CQLSyntax.Symbols.`:`)
      .forcePad.appendSingleQuote(strategy)
  }

  case object SnappyCompressor extends CompressionStrategy(rootStrategy(CQLSyntax.CompressionStrategies.SnappyCompressor))
  case object LZ4Compressor extends CompressionStrategy(rootStrategy(CQLSyntax.CompressionStrategies.LZ4Compressor))
  case object DeflateCompressor extends CompressionStrategy(rootStrategy(CQLSyntax.CompressionStrategies.DeflateCompressor))
}

sealed class CacheProperty(val qb: CQLQuery) {}

object CacheStrategies {

  case object None extends CacheProperty(CQLQuery(CQLSyntax.CacheStrategies.None))
  case object KeysOnly extends CacheProperty(CQLQuery(CQLSyntax.CacheStrategies.KeysOnly))
}


/**
 * A root implementation trait of a CQL table option.
 * These are implemented with respect to the CQL 3.0 reference available here:
 * {{ http://www.datastax.com/documentation/cql/3.0/cql/cql_reference/tabProp.html }}
 */
sealed trait TableProperty

/**
 * A collection of available table property clauses with all the default objects available.
 * This serves as a helper trait for [[com.websudos.phantom.dsl._]] and brings all the relevant options into scope.
 */
sealed trait TablePropertyClauses extends CompactionStrategies with CompressionStrategies {
  object Storage {
    case object CompactStorage extends TablePropertyClause(CQLQuery(CQLSyntax.StorageMechanisms.CompactStorage))
  }

  /**
   * Helper object used to specify the compression strategy for a table.
   * According to the Cassandra specification, the available strategies are:
   *
   * <ul>
   *   <li>SnappyCompressor</li>
   *   <li>LZ4Compressor</li>
   *   <li>DeflateCompressor</li>
   * </ul>
   *
   * A simple way to define a strategy is by using the {{eqs}} method.
   *
   * {{{
   *  import com.websudos.phantom.dsl._
   *
   *  SomeTable.create.with(compression eqs SnappyCompressor)
   *
   * }}}
   *
   * Using a compression strategy also allows using the API methods of controlling compressor behaviour:
   *
   * {{{
   *   import com.websudos.phantom.dsl._
   *   import com.twitter.conversions.storage._
   *
   *   SomeTable.create.`with`(compression eqs SnappyCompressor.chunk_length_kb(50.kilobytes))
   *
   * }}}
   */
  object compression extends TableProperty {
    def eqs(clause: CompressionStrategy): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.compression(clause.qb))
    }
  }

  /**
   * Table creation clause allowing specification of CQL compaction strategies.
   *
   * <ul>
   *   <li>SizeTieredCompactionStrategy</li>
   *   <li>LeveledCompactionStrategy</li>
   *   <li>DateTieredCompactionStrategy</li>
   * </ul>
   *
   * {{{
   *   import com.websudos.phantom.dsl._
   *
   *   SomeTable.create.`with`(compaction eqs SnappyCompressor)
   * }}}
   */
  object compaction extends TableProperty {
    def eqs(clause: CompactionStrategy): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.compaction(clause.qb))
    }
  }

  object comment extends TableProperty {
    def eqs(clause: String): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.comment(clause))
    }
  }

  object read_repair_chance extends TableProperty {
    def eqs(clause: Double): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.read_repair_chance(clause.toString))
    }
  }

  object dclocal_read_repair_chance extends TableProperty {
    def eqs(clause: Double): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.dclocal_read_repair_chance(clause.toString))
    }
  }

  object replicate_on_write extends TableProperty {
    def apply(clause: Boolean): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.replicate_on_write(clause.toString))
    }

    def eqs = apply _
  }

  object gc_grace_seconds extends TableProperty {

    def eqs(clause: Seconds): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.gc_grace_seconds(clause.getSeconds.toString))
    }

    def eqs(duration: FiniteDuration): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.gc_grace_seconds(duration.toSeconds.toString))
    }

    def eqs(duration: com.twitter.util.Duration): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.gc_grace_seconds(duration.inSeconds.toString))
    }
  }

  object bloom_filter_fp_chance extends TableProperty {
    def eqs(clause: Double): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.bloom_filter_fp_chance(clause.toString))
    }
  }
  
  object caching extends TableProperty {
    def eqs(strategy: CacheProperty): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.caching(strategy.qb.queryString))
    }
  }

  object default_time_to_live extends TableProperty {

    def eqs(time: Long): TablePropertyClause = {
      new TablePropertyClause(QueryBuilder.Create.default_time_to_live(time.toString))
    }

    def eqs(duration: Seconds): TablePropertyClause = {
      eqs(duration.getSeconds.toLong)
    }

    def eqs(duration: FiniteDuration): TablePropertyClause = {
      eqs(duration.toSeconds)
    }

    def eqs(duration: com.twitter.util.Duration): TablePropertyClause = {
      eqs(duration.inLongSeconds)
    }
  }

}

class RootCreateQuery[
  Table <: CassandraTable[Table, _],
  Record
](val table: Table) {

  private[phantom] def default()(implicit keySpace: KeySpace): CQLQuery = {
    CQLQuery(CQLSyntax.create).forcePad.append(CQLSyntax.table)
      .forcePad.append(QueryBuilder.keyspace(keySpace.name, table.tableName)).forcePad
      .append(CQLSyntax.Symbols.`(`)
      .append(QueryBuilder.Utils.join(table.columns.map(_.qb): _*))
      .append(CQLSyntax.Symbols.`,`)
      .forcePad.append(table.defineTableKey())
      .append(CQLSyntax.Symbols.`)`)
  }

  private[phantom] def toQuery()(implicit keySpace: KeySpace): CreateQuery.Default[Table, Record] = {
    new CreateQuery[Table, Record, Unspecified, WithUnchainned](table, default)
  }


  private[this] def lightweight()(implicit keySpace: KeySpace): CQLQuery = {
    CQLQuery(CQLSyntax.create).forcePad.append(CQLSyntax.table)
      .forcePad.append(CQLSyntax.ifNotExists)
      .forcePad.append(QueryBuilder.keyspace(keySpace.name, table.tableName))
      .forcePad.append(CQLSyntax.Symbols.`(`)
      .append(QueryBuilder.Utils.join(table.columns.map(_.qb): _*))
      .append(CQLSyntax.Symbols.`,`)
      .forcePad.append(table.defineTableKey())
      .append(CQLSyntax.Symbols.`)`)
  }

  def ifNotExists()(implicit keySpace: KeySpace): CreateQuery.Default[Table, Record] = {
    new CreateQuery[Table, Record, Unspecified, WithUnchainned](table, lightweight())
  }
}


class CreateQuery[
  Table <: CassandraTable[Table, _],
  Record,
  Status <: ConsistencyBound,
  Chain <: WithBound
](table: Table, val qb: CQLQuery) extends ExecutableStatement {

  @implicitNotFound("You cannot use 2 `with` clauses on the same create query. Use `and` instead.")
  final def `with`(clause: TablePropertyClause)(implicit ev: Chain =:= WithUnchainned): CreateQuery[Table, Record, Status, WithChainned] = {
    new CreateQuery(table, QueryBuilder.Create.`with`(qb, clause.qb))
  }

  @implicitNotFound("You cannot use 2 `with` clauses on the same create query. Use `and` instead.")
  final def option(clause: TablePropertyClause)(implicit ev: Chain =:= WithUnchainned): CreateQuery[Table, Record, Status, WithChainned] = {
    new CreateQuery(table, QueryBuilder.Create.`with`(qb, clause.qb))
  }

  @implicitNotFound("You have to use `with` before using `and` in a create query.")
  final def and(clause: TablePropertyClause)(implicit ev: Chain =:= WithChainned): CreateQuery[Table, Record, Status, WithChainned] = {
    new CreateQuery(table, QueryBuilder.Where.and(qb, clause.qb))
  }

  override def future()(implicit session: Session, keySpace: KeySpace): ScalaFuture[ResultSet] = {

    implicit val ex: ExecutionContext = Manager.scalaExecutor

    if (table.secondaryKeys.isEmpty) {
      scalaQueryStringExecuteToFuture(qb.terminate().queryString)
    } else {

      super.future() flatMap {
        res => {

          val indexes = table.secondaryKeys map {
            key => scalaQueryStringExecuteToFuture(QueryBuilder.Create.index(table.tableName, keySpace.name, key.name).queryString)
          }

          Manager.logger.debug(s"Creating ${indexes.size} indexes on ${QueryBuilder.keyspace(keySpace.name, table.tableName).queryString}")
          ScalaFuture.sequence(indexes) map { _ => res }
        }
      }
    }
  }

  override def execute()(implicit session: Session, keySpace: KeySpace): TwitterFuture[ResultSet] = {

    if (table.secondaryKeys.isEmpty) {
      twitterQueryStringExecuteToFuture(qb.terminate().queryString)
    } else {

      super.execute() flatMap {
        res => {

          val indexes = table.secondaryKeys map {
            key => twitterQueryStringExecuteToFuture(QueryBuilder.Create.index(table.tableName, keySpace.name, key.name).queryString)
          }

          Manager.logger.debug(s"Creating ${indexes.size} indexes on ${QueryBuilder.keyspace(keySpace.name, table.tableName).queryString}")
          TwitterFuture.collect(indexes) map {_ => res}
        }
      }
    }
  }

}

object CreateQuery {
  type Default[T <: CassandraTable[T, _], R] = CreateQuery[T, R, Unspecified, WithUnchainned]
}

private[phantom] trait CreateImplicits extends TablePropertyClauses {

  val Cache = CacheStrategies

  implicit def rootCreateQueryToCreateQuery[T <: CassandraTable[T, _], R](root: RootCreateQuery[T, R])(implicit keySpace: KeySpace): CreateQuery.Default[T,
    R] = {
    new CreateQuery(root.table, root.default)
  }
}
