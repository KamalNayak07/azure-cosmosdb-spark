/**
  * The MIT License (MIT)
  * Copyright (c) 2016 Microsoft Corporation
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  */
package com.microsoft.azure.cosmosdb.spark

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

import com.microsoft.azure.cosmosdb.spark.config._
import rx.Observable
import com.microsoft.azure.cosmosdb._
import com.microsoft.azure.cosmosdb.internal._
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient
import com.microsoft.azure.cosmosdb.spark.schema.CosmosDBRowConverter
import org.apache.spark.sql.Row

import scala.collection.JavaConversions._
import scala.language.implicitConversions
import scala.reflect.ClassTag


case class AsyncCosmosDBConnection(config: Config) extends LoggingTrait with Serializable {

  private lazy val asyncDocumentClient: AsyncDocumentClient = {
    if (asyncClient == null) {
      this.synchronized {
        if (asyncClient == null) {
          val clientConfig = getClientConfiguration(config)

          asyncClient = new AsyncDocumentClient
          .Builder()
            .withServiceEndpoint(clientConfig.host)
            .withMasterKey(clientConfig.key)
            .withConnectionPolicy(clientConfig.connectionPolicy)
            .withConsistencyLevel(clientConfig.consistencyLevel)
            .build
        }
      }
    }
    asyncClient
  }

  private val databaseName = config.get[String](CosmosDBConfig.Database).get
  private val collectionName = config.get[String](CosmosDBConfig.Collection).get
  val collectionLink = s"${Paths.DATABASES_PATH_SEGMENT}/$databaseName/${Paths.COLLECTIONS_PATH_SEGMENT}/$collectionName"
  // Cosmos DB Java Async SDK supports Gateway mode
  private val connectionMode = ConnectionMode.valueOf(config.get[String](CosmosDBConfig.ConnectionMode)
    .getOrElse(com.microsoft.azure.documentdb.ConnectionMode.Gateway.toString))

  @transient private var asyncClient: AsyncDocumentClient = _

  def importWithRxJava[D: ClassTag](iter: Iterator[D],
                                    connection: AsyncCosmosDBConnection,
                                    writingBatchSize: Integer,
                                    writingBatchDelayMs: Long,
                                    rootPropertyToSave: Option[String],
                                    upsert: Boolean): Unit = {

    var observables = new java.util.ArrayList[Observable[ResourceResponse[Document]]](writingBatchSize)
    var createDocumentObs: Observable[ResourceResponse[Document]] = null
    var batchSize = 0
    iter.foreach(item => {
      val document: Document = item match {
        case doc: Document => doc
        case row: Row =>
          if (rootPropertyToSave.isDefined) {
            new Document(row.getString(row.fieldIndex(rootPropertyToSave.get)))
          } else {
            new Document(CosmosDBRowConverter.rowToJSONObject(row).toString())
          }
        case any => new Document(any.toString)
      }
      logDebug(s"Inserting document $document")
      if (upsert)
        createDocumentObs = connection.upsertDocument(document, null)
      else
        createDocumentObs = connection.createDocument(document, null)
      observables.add(createDocumentObs)
      batchSize = batchSize + 1
      if (batchSize % writingBatchSize == 0) {
        Observable.merge(observables).toBlocking.last()
        if (writingBatchDelayMs > 0) {
          TimeUnit.MILLISECONDS.sleep(writingBatchDelayMs)
        }
        observables.clear()
        batchSize = 0
      }
    })
    if (!observables.isEmpty) {
      Observable.merge(observables).toBlocking.last()
    }
  }

  def upsertDocument(document: Document,
                     requestOptions: RequestOptions): Observable[ResourceResponse[Document]] = {
    logTrace(s"Upserting document $document")
    asyncDocumentClient.upsertDocument(collectionLink, document, requestOptions, false)
  }

  def createDocument(document: Document,
                     requestOptions: RequestOptions): Observable[ResourceResponse[Document]] = {
    logTrace(s"Creating document $document")
    asyncDocumentClient.createDocument(collectionLink, document, requestOptions, false)
  }

  private def getClientConfiguration(config: Config): ClientConfiguration = {
    // Generate connection policy
    val connectionPolicy = new ConnectionPolicy()

    connectionPolicy.setConnectionMode(connectionMode)
    // Merging the Spark connector version with Spark executor process id for user agent
    connectionPolicy.setUserAgentSuffix(Constants.userAgentSuffix + " " + ManagementFactory.getRuntimeMXBean().getName())

    config.get[String](CosmosDBConfig.ConnectionMaxPoolSize) match {
      case Some(maxPoolSizeStr) => connectionPolicy.setMaxPoolSize(maxPoolSizeStr.toInt)
      case None => // skip
    }

    config.get[String](CosmosDBConfig.ConnectionRequestTimeout) match {
      case Some(connectionRequestTimeoutStr) => connectionPolicy.setRequestTimeoutInMillis(connectionRequestTimeoutStr.toInt * 1000)
      case None => // skip
    }

    config.get[String](CosmosDBConfig.ConnectionIdleTimeout) match {
      case Some(connectionIdleTimeoutStr) => connectionPolicy.setIdleConnectionTimeoutInMillis(connectionIdleTimeoutStr.toInt)
      case None => // skip
    }

    val maxRetryAttemptsOnThrottled = config.getOrElse[String](CosmosDBConfig.QueryMaxRetryOnThrottled, CosmosDBConfig.DefaultQueryMaxRetryOnThrottled.toString)
    connectionPolicy.getRetryOptions.setMaxRetryAttemptsOnThrottledRequests(maxRetryAttemptsOnThrottled.toInt)

    val maxRetryWaitTimeSecs = config.getOrElse[String](CosmosDBConfig.QueryMaxRetryWaitTimeSecs, CosmosDBConfig.DefaultQueryMaxRetryWaitTimeSecs.toString)
    connectionPolicy.getRetryOptions.setMaxRetryWaitTimeInSeconds(maxRetryWaitTimeSecs.toInt)

    val preferredRegionList = config.get[String](CosmosDBConfig.PreferredRegionsList)
    if (preferredRegionList.isDefined) {
      logTrace(s"CosmosDBConnection::Input preferred region list: ${preferredRegionList.get}")
      val preferredLocations = preferredRegionList.get.split(";").toSeq.map(_.trim)
      connectionPolicy.setPreferredLocations(preferredLocations)
    }

    // Generate consistency level
    val consistencyLevel = ConsistencyLevel.valueOf(config.get[String](CosmosDBConfig.ConsistencyLevel)
      .getOrElse(CosmosDBConfig.DefaultConsistencyLevel))

    ClientConfiguration(
      config.get[String](CosmosDBConfig.Endpoint).get,
      config.get[String](CosmosDBConfig.Masterkey).get,
      connectionPolicy,
      consistencyLevel
    )
  }

  case class ClientConfiguration(host: String,
                                 key: String,
                                 connectionPolicy: ConnectionPolicy,
                                 consistencyLevel: ConsistencyLevel)

}