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
package com.websudos.phantom.udt

import java.util.Date

import com.datastax.driver.core.{ResultSet, Row, Session, UDTValue, UserType}
import com.twitter.util.Future
import com.websudos.phantom.CassandraTable
import com.websudos.phantom.builder.primitives.Primitive
import com.websudos.phantom.builder.query.{CQLQuery, ExecutableStatement}
import com.websudos.phantom.dsl.{Column, KeySpace}

import scala.collection.mutable.{ArrayBuffer => MutableArrayBuffer}
import scala.concurrent.{ExecutionContext, Future => ScalaFuture}
import scala.reflect.runtime.universe.Symbol
import scala.reflect.runtime.{currentMirror => cm, universe => ru}
import scala.util.{DynamicVariable, Try}


/**
 * A global lock for reflecting and collecting fields inside a User Defined Type.
 * This prevents a race condition and bug.
 */
private[phantom] object Lock

/**
 * A field part of a user defined type.
 * @param owner The UDT column that owns the field.
 * @tparam T The Scala type corresponding the underlying Cassandra type of the UDT field.
*/
sealed abstract class AbstractField[@specialized(Int, Double, Float, Long, Boolean, Short) T : Primitive](owner: UDTColumn[_, _, _]) {

  lazy val name: String = cm.reflect(this).symbol.name.toTypeName.decodedName.toString

  protected[udt] lazy val valueBox = new DynamicVariable[Option[T]](None)

  def value: T = valueBox.value.getOrElse(null.asInstanceOf[T])

  private[udt] def setSerialise(data: UDTValue): UDTValue

  private[udt] def set(value: Option[T]): Unit = valueBox.value_=(value)

  private[udt] def set(data: UDTValue): Unit = valueBox.value_=(apply(data))

  def cassandraType: String = Primitive[T].cassandraType

  def apply(row: UDTValue): Option[T]
}


private[udt] abstract class Field[
  Owner <: CassandraTable[Owner, Record],
  Record,
  FieldOwner <: UDTColumn[Owner, Record, _],
  T : Primitive
](column: FieldOwner) extends AbstractField[T](column) {}

object PrimitiveBoxedManifests {
  val StringManifest = manifest[String]
  val IntManifest = manifest[Int]
  val DoubleManifest = manifest[Double]
  val LongManifest = manifest[Long]
  val FloatManifest = manifest[Float]
  val BigDecimalManifest = manifest[BigDecimal]
  val BigIntManifest = manifest[BigInt]
  val DateManifest = manifest[Date]
}


/**
 * This is a centralised singleton that collects references to all UDT column definitions in the entire module.
 * It is used to auto-generate the schema of all the UDT columns in a manner that is completely invisible to the user.
 *
 * The synchronisation of the schema is not done automatically, allowing for fine grained control of events,
 * but the auto-generaiton and execution capabilities are available with a single method call.
 */
private[udt] object UDTCollector {
  private[this] val _udts = MutableArrayBuffer.empty[UDTDefinition[_]]

  def push[T](udt: UDTDefinition[T]): Unit = {
    _udts += udt
  }

  /**
   * This is a working version of an attempt to combine all UDT creation futures in a single result.
   * This way, the end user can await for a single result with a single Future before being able to use the entire set of UDT definitions.
   *
   * @param session The Cassandra database connection session.
   * @return
   */
  def future()(implicit session: Session, ec: ExecutionContext, keySpace: KeySpace): ScalaFuture[Seq[ResultSet]] = {
    ScalaFuture.sequence(_udts.toSeq.map(_.create().future()))
  }

  def execute()(implicit session: Session, keySpace: KeySpace): Future[Seq[ResultSet]] = {
    Future.collect(_udts.map(_.create().execute()))
  }
}


sealed trait UDTDefinition[T] {
  def name: String

  def fields: List[AbstractField[_]] = _fields.toList

  def typeDef()(implicit session: Session, keySpace: KeySpace): UserType = {
    session.getCluster.getMetadata.getKeyspace(keySpace.name).getUserType(name)
  }

  val cassandraType = name.toLowerCase

  private[this] val instanceMirror = cm.reflect(this)
  private[this] val selfType = instanceMirror.symbol.toType

  // Collect all column definitions starting from base class
  private[this] val columnMembers = MutableArrayBuffer.empty[Symbol]

  Lock.synchronized {
    selfType.baseClasses.reverse.foreach {
      baseClass =>
        val baseClassMembers = baseClass.typeSignature.members.sorted
        val baseClassColumns = baseClassMembers.filter(_.typeSignature <:< ru.typeOf[AbstractField[_]])
        baseClassColumns.foreach(symbol => if (!columnMembers.contains(symbol)) columnMembers += symbol)
    }

    columnMembers.foreach {
      symbol =>
        val column = instanceMirror.reflectModule(symbol.asModule).instance
        _fields += column.asInstanceOf[AbstractField[_]]
    }

    UDTCollector.push(this)
  }

  def schema(): String = {
    val queryInit = s"CREATE TYPE IF NOT EXISTS $name("
    val queryColumns = _fields.foldLeft("")((qb, c) => {
      if (qb.isEmpty) {
        s"${c.name} ${c.cassandraType}"
      } else {
        s"$qb, ${c.name} ${c.cassandraType}"
      }
    })
    queryInit + queryColumns + ");"
  }

  def create(): UDTCreateQuery = new UDTCreateQuery(null, this)

  /**
   * Much like the definition of a Cassandra table where the columns are collected, the fields of an UDT are collected inside this buffer.
   * Every new buffer spawned will be a perfect clone of this instance, and the fields will always be pre-initialised on extraction.
   */
  private[udt] lazy val _fields: MutableArrayBuffer[AbstractField[_]] = new MutableArrayBuffer[AbstractField[_]]
}


abstract class UDTColumn[
  Owner <: CassandraTable[Owner, Record],
  Record,
  T
](table: CassandraTable[Owner, Record]) extends Column[Owner, Record, UDTColumn[Owner, Record, T]](table) with UDTDefinition[T] {

   override def apply(row: Row): UDTColumn[Owner, Record, T] = {
    val instance: UDTColumn[Owner, Record, T] = clone().asInstanceOf[UDTColumn[Owner, Record, T]]
    val data = row.getUDTValue(name)

    instance.fields.foreach(field => {
      field.set(data)
    })
    instance
  }

  override def optional(r: Row): Try[UDTColumn[Owner, Record, T]] = {
    Try {
      val instance = clone().asInstanceOf[UDTColumn[Owner, Record, T]]
      val data = r.getUDTValue(name)

      instance.fields.foreach(field => {
        field.set(data)
      })

      instance
    }
  }

  def asCql(v: T)(implicit session: Session, keySpace: KeySpace): String = {
    val data = typeDef.newValue()
    fields.foreach(field => {
      field.setSerialise(data)
    })
    data.toString
  }
}

sealed class UDTCreateQuery(val qb: CQLQuery, udt: UDTDefinition[_]) extends ExecutableStatement {

  override def execute()(implicit session: Session, keySpace: KeySpace): Future[ResultSet] = {
    twitterQueryStringExecuteToFuture(udt.schema())
  }

  override def future()(implicit session: Session, keySpace: KeySpace): ScalaFuture[ResultSet] = {
    scalaQueryStringExecuteToFuture(udt.schema())
  }
}


