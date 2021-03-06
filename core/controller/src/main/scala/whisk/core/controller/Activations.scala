/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.controller

import java.time.Instant

import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonMarshaller
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.model.StatusCodes.BadRequest

import spray.json.DeserializationException
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json._

import whisk.common.TransactionId
import whisk.core.entitlement.Collection
import whisk.core.entitlement.Privilege.Privilege
import whisk.core.entitlement.Privilege.READ
import whisk.core.entitlement.Resource
import whisk.core.entity._
import whisk.core.entity.types.ActivationStore
import whisk.http.Messages
import whisk.http.ErrorResponse.terminate

object WhiskActivationsApi {
    protected[core] val maxActivationLimit = 200
}

/** A trait implementing the activations API. */
trait WhiskActivationsApi
    extends Directives
    with AuthenticatedRouteProvider
    with AuthorizedRouteProvider
    with ReadOps {

    protected override val collection = Collection(Collection.ACTIVATIONS)

    /** Database service to GET activations. */
    protected val activationStore: ActivationStore

    /** Path to Actions REST API. */
    protected val activationsPath = "activations"

    /** Path to activation result and logs. */
    private val resultPath = "result"
    private val logsPath = "logs"

    /** Only GET is supported in this API. */
    protected override lazy val entityOps = get

    /** Validated entity name as an ActivationId from the matched path segment. */
    protected override def entityname(n: String) = {
        val activationId = Try { ActivationId(n) }
        validate(activationId.isSuccess, activationId match {
            case Failure(DeserializationException(t, _, _)) => t
            case _ => Messages.activationIdIllegal
        }) & extract(_ => n)
    }

    /**
     * Overrides because API allows for GET on /activations and /activations/[result|log] which
     * would be rejected in the superclass.
     */
    override protected def innerRoutes(user: Identity, ns: EntityPath)(implicit transid: TransactionId) = {
        (entityPrefix & entityOps & requestMethod) { (segment, m) =>
            entityname(segment) {
                // defer rest of the path processing to the fetch operation, which is
                // the only operation supported on activations that reach the inner route
                name => authorizeAndDispatch(m, user, Resource(ns, collection, Some(name)))
            }
        }
    }

    /** Dispatches resource to the proper handler depending on context. */
    protected override def dispatchOp(user: Identity, op: Privilege, resource: Resource)(implicit transid: TransactionId) = {
        resource.entity match {
            case Some(ActivationId(id)) => op match {
                case READ => fetch(resource.namespace, id)
                case _    => reject // should not get here
            }
            case None => op match {
                case READ => list(resource.namespace)
                case _    => reject // should not get here
            }
        }
    }

    /**
     * Gets all activations in namespace. Filters by action name if parameter is given.
     *
     * Responses are one of (Code, Message)
     * - 200 [] or [WhiskActivation as JSON]
     * - 500 Internal Server Error
     */
    private def list(namespace: EntityPath)(implicit transid: TransactionId) = {
        parameter('skip ? 0, 'limit ? collection.listLimit, 'count ? false, 'docs ? false, 'name.as[EntityName]?, 'since.as[Instant]?, 'upto.as[Instant]?) {
            (skip, limit, count, docs, name, since, upto) =>
                val cappedLimit = if (limit == 0) WhiskActivationsApi.maxActivationLimit else limit

                // regardless of limit, cap at maxActivationLimit (200) records, client must paginate
                if (cappedLimit <= WhiskActivationsApi.maxActivationLimit) {
                    val activations = name match {
                        case Some(action) =>
                            WhiskActivation.listCollectionByName(activationStore, namespace, action, skip, cappedLimit, docs, since, upto)
                        case None =>
                            WhiskActivation.listCollectionInNamespace(activationStore, namespace, skip, cappedLimit, docs, since, upto)
                    }

                    listEntities {
                        activations map {
                            l => if (docs) l.right.get map {
                                _.toExtendedJson
                            } else l.left.get
                        }
                    }
                } else {
                    terminate(BadRequest, Messages.maxActivationLimitExceeded(limit, WhiskActivationsApi.maxActivationLimit))
                }
        }
    }

    /**
     * Gets activation. The activation id is prefixed with the namespace to create the primary index key.
     *
     * Responses are one of (Code, Message)
     * - 200 WhiskActivation as JSON
     * - 404 Not Found
     * - 500 Internal Server Error
     */
    private def fetch(namespace: EntityPath, activationId: ActivationId)(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, activationId))
        pathEndOrSingleSlash {
            getEntity(WhiskActivation, activationStore, docid, postProcess = Some((activation: WhiskActivation) =>
                complete(activation.toExtendedJson)))

        } ~ (pathPrefix(resultPath) & pathEnd) { fetchResponse(docid) } ~
            (pathPrefix(logsPath) & pathEnd) { fetchLogs(docid) }
    }

    /**
     * Gets activation result. The activation id is prefixed with the namespace to create the primary index key.
     *
     * Responses are one of (Code, Message)
     * - 200 { result: ..., success: Boolean, statusMessage: String }
     * - 404 Not Found
     * - 500 Internal Server Error
     */
    private def fetchResponse(docid: DocId)(implicit transid: TransactionId) = {
        getEntityAndProject(WhiskActivation, activationStore, docid,
            (activation: WhiskActivation) => activation.response.toExtendedJson)
    }

    /**
     * Gets activation logs. The activation id is prefixed with the namespace to create the primary index key.
     *
     * Responses are one of (Code, Message)
     * - 200 { logs: String }
     * - 404 Not Found
     * - 500 Internal Server Error
     */
    private def fetchLogs(docid: DocId)(implicit transid: TransactionId) = {
        getEntityAndProject(WhiskActivation, activationStore, docid,
            (activation: WhiskActivation) => activation.logs.toJsonObject)
    }

    /** Custom unmarshaller for query parameters "name" into valid entity name. */
    private implicit val stringToEntityName: Unmarshaller[String, EntityName] =
        Unmarshaller.strict[String, EntityName] { value =>
            Try { EntityName(value) } match {
                case Success(e) => e
                case Failure(t) => throw new IllegalArgumentException(Messages.badEntityName(value))
            }
        }

    /** Custom unmarshaller for query parameters "name" into valid namespace. */
    private implicit val stringToNamespace: Unmarshaller[String, EntityPath] =
        Unmarshaller.strict[String, EntityPath] { value =>
            Try { EntityPath(value) } match {
                case Success(e) => e
                case Failure(t) => throw new IllegalArgumentException(Messages.badNamespace(value))
            }
        }

    /** Custom unmarshaller for query parameters "since" and "upto" into a valid Instant. */
    private implicit val stringToInstantDeserializer: Unmarshaller[String, Instant] =
        Unmarshaller.strict[String, Instant] { value =>
            Try { Instant.ofEpochMilli(value.toLong) } match {
                case Success(e) => e
                case Failure(t) => throw new IllegalArgumentException(Messages.badEpoch(value))
            }
        }
}
