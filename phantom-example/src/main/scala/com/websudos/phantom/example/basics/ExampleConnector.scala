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
package com.websudos.phantom.example.basics

import com.datastax.driver.core.Session
import com.websudos.phantom.connectors.{KeySpace, SimpleConnector}
import com.websudos.phantom.zookeeper.ZkContactPointLookup

/**
 * This is an example of how to connect to Cassandra in the easiest possible way.
 * The SimpleCassandraConnector is designed to get you up and running immediately, with almost 0 effort.
 *
 * What you have to do now is to tell phantom what keyspace you will be using in Cassandra. This connector will automaticalyl try to connect to localhost:9042.
 * If you want to tell the connector to use a different host:port combination, simply override the address inside it.
 *
 * Otherwise, simply mixing this connector in will magically inject a database session for all your queries and you can immediately run them.
 */
trait ExampleConnector extends SimpleConnector {
  implicit val keySpace = KeySpace("phantom_example")
}

/**
 * Now you might ask yourself how to use service discovery with phantom. The Datastax Java Driver can automatically connect to multiple clusters.
 * Using some underlying magic, phantom can also help you painlessly connect to a series of nodes in a Cassandra cluster via ZooKeeper.
 *
 * Once again, all you need to tell phantom is what your keyspace is. Phantom will make a series of assumptions about which path you are using in ZooKeeper.
 * By default, it will try to connect to localhost:2181, fetch the "/cassandra" path and parse ports found in a "host:port, host1:port1,
 * .." sequence. All these settings are trivial to override in the below connector and you can adjust all the settings to fit your environment.
 */
object ZkDefaults {
  def getConnector(keySpace: KeySpace) = {
    ZkContactPointLookup.local.keySpace(keySpace.name)
  }
}

trait DefaultZookeeperConnector extends SimpleConnector {
  override implicit lazy val session: Session = ZkDefaults.getConnector(keySpace).session
}




