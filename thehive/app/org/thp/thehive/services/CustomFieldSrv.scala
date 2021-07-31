package org.thp.thehive.services

import akka.actor.ActorRef
import com.softwaremill.tagging.@@
import org.thp.scalligraph.EntityIdOrName
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services.{IntegrityCheckOps, VertexSrv}
import org.thp.scalligraph.traversal._
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.models._
import play.api.cache.SyncCacheApi
import play.api.libs.json.JsObject

import scala.util.{Success, Try}

class CustomFieldSrv(
    auditSrv: AuditSrv,
    organisationSrv: OrganisationSrv,
    integrityCheckActor: => ActorRef @@ IntegrityCheckTag,
    cacheApi: SyncCacheApi
) extends VertexSrv[CustomField]
    with TheHiveOpsNoDeps {

  override def createEntity(e: CustomField)(implicit graph: Graph, authContext: AuthContext): Try[CustomField with Entity] = {
    integrityCheckActor ! EntityAdded("CustomField")
    cacheApi.remove("describe.v0")
    cacheApi.remove("describe.v1")
    super.createEntity(e)
  }

  def create(e: CustomField)(implicit graph: Graph, authContext: AuthContext): Try[CustomField with Entity] =
    for {
      created <- createEntity(e)
      _       <- auditSrv.customField.create(created, created.toJson)
    } yield created

  override def exists(e: CustomField)(implicit graph: Graph): Boolean = startTraversal.getByName(e.name).exists

  def delete(c: CustomField with Entity, force: Boolean)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(c).remove() // TODO use force
    cacheApi.remove("describe.v0")
    cacheApi.remove("describe.v1")
    organisationSrv.getOrFail(authContext.organisation).flatMap { organisation =>
      auditSrv.customField.delete(c, organisation)
    }
  }

  def useCount(c: CustomField with Entity)(implicit graph: Graph): Map[String, Long] =
    get(c)
      .in()
      .groupCount(_.byLabel)
      .headOption
      .fold(Map.empty[String, Long])(_.filterNot(_._1 == "Audit"))

  override def update(
      traversal: Traversal.V[CustomField],
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(Traversal.V[CustomField], JsObject)] =
    auditSrv.mergeAudits(super.update(traversal, propertyUpdaters)) {
      case (customFieldSteps, updatedFields) =>
        customFieldSteps
          .clone()
          .getOrFail("CustomFields")
          .flatMap { cf =>
            cacheApi.remove("describe.v0")
            cacheApi.remove("describe.v1")
            auditSrv.customField.update(cf, updatedFields)
          }
    }

  override def getByName(name: String)(implicit graph: Graph): Traversal.V[CustomField] =
    startTraversal.getByName(name)
}

trait CustomFieldOps { _: TraversalOps =>
  implicit class CustomFieldOpsDefs(traversal: Traversal.V[CustomField]) {
    def get(idOrName: EntityIdOrName): Traversal.V[CustomField] =
      idOrName.fold(traversal.getByIds(_), getByName)

    def getByName(name: String): Traversal.V[CustomField] = traversal.has(_.name, name)
  }
}

//trait EntityWithCustomFieldOpsNoDepsDefs[E <: Product, CV <: CustomFieldValue] extends TraversalOps {
//  val traversal: Traversal.V[E]
//
//  def customFields: Traversal.E[CV]
//
//  def richCustomFields: Traversal[RichCustomField, JMap[String, Any], Converter[RichCustomField, JMap[String, Any]]] =
//    customFields
//      .project(_.by.by(_.inV.v[CustomField]))
//      .domainMap {
//        case (cfv, cf) => RichCustomField(cf, cfv)
//      }
//
//  def customFieldValue(idOrName: EntityIdOrName): Traversal.E[CV] =
//    idOrName
//      .fold(
//        id => customFields.filter(_.inV.getByIds(id)),
//        name => customFields.filter(_.inV.v[CustomField].has(_.name, name))
//      )
//}

//trait EntityWithCustomFieldOpsDefs[E <: Product, CV <: CustomFieldValue] extends TraversalOps with PredicateOps with CustomFieldOps {
//  val traversal: Traversal.V[E]
//  protected val customFieldSrv: CustomFieldSrv
//  def selectCustomField(traversal: Traversal.V[E]): Traversal.E[CV]
//  private def selectCFV(traversal: Traversal.V[E]): Traversal.E[CustomFieldValue[Nothing]] =
//    selectCustomField(traversal).asInstanceOf[Traversal.E[CustomFieldValue[Nothing]]]
//
////  def customFields: Traversal.E[CV] = selectCustomField(traversal)
//
//  private def customFieldValue(idOrName: EntityIdOrName): Traversal.E[CV] =
//    idOrName
//      .fold(
//        id => selectCustomField(traversal).filter(_.inV.getByIds(id)),
//        name => selectCustomField(traversal).filter(_.inV.v[CustomField].has(_.name, name))
//      )
//
//  def customFieldJsonValue(customField: EntityIdOrName): Traversal.Domain[JsValue] =
//    customFieldSrv
//      .get(customField)(traversal.graph)
//      .value(_.`type`)
//      .headOption
//      .map(_.getJsonValue(customFieldValue(customField)))
//      .getOrElse(traversal.empty.castDomain)
//
//  def customFieldFilter(customField: EntityIdOrName, predicate: P[JsValue]): Traversal.V[E] =
//    customFieldSrv
//      .get(customField)(traversal.graph)
//      .value(_.`type`)
//      .headOption
//      .map {
//        case CustomFieldBoolean =>
//          traversal.filter(selectCFV(_).has(_.booleanValue, predicate.mapValue(_.as[Boolean])).inV.v[CustomField].get(customField))
//        case CustomFieldDate =>
//          traversal.filter(selectCFV(_).has(_.dateValue, predicate.mapValue(_.as[Date])).inV.v[CustomField].get(customField))
//        case CustomFieldFloat =>
//          traversal.filter(selectCFV(_).has(_.floatValue, predicate.mapValue(_.as[Double])).inV.v[CustomField].get(customField))
//        case CustomFieldInteger =>
//          traversal.filter(selectCFV(_).has(_.integerValue, predicate.mapValue(_.as[Int])).inV.v[CustomField].get(customField))
//        case CustomFieldString =>
//          traversal.filter(selectCFV(_).has(_.stringValue, predicate.mapValue(_.as[String])).inV.v[CustomField].get(customField))
//      }
//      .getOrElse(traversal.empty)
//
//  def hasCustomField(customField: EntityIdOrName): Traversal.V[E] =
//    customFieldSrv
//      .get(customField)(traversal.graph)
//      .value(_.`type`)
//      .headOption
//      .map {
//        case CustomFieldBoolean => traversal.filter(selectCFV(_).has(_.booleanValue).inV.v[CustomField].get(customField))
//        case CustomFieldDate    => traversal.filter(selectCFV(_).has(_.dateValue).inV.v[CustomField].get(customField))
//        case CustomFieldFloat   => traversal.filter(selectCFV(_).has(_.floatValue).inV.v[CustomField].get(customField))
//        case CustomFieldInteger => traversal.filter(selectCFV(_).has(_.integerValue).inV.v[CustomField].get(customField))
//        case CustomFieldString  => traversal.filter(selectCFV(_).has(_.stringValue).inV.v[CustomField].get(customField))
//      }
//      .getOrElse(traversal.empty)
//
//  def hasNotCustomField(customField: EntityIdOrName): Traversal.V[E] =
//    customFieldSrv
//      .get(customField)(traversal.graph)
//      .value(_.`type`)
//      .headOption
//      .map {
//        case CustomFieldBoolean => traversal.filterNot(selectCFV(_).has(_.booleanValue).inV.v[CustomField].get(customField))
//        case CustomFieldDate    => traversal.filterNot(selectCFV(_).has(_.dateValue).inV.v[CustomField].get(customField))
//        case CustomFieldFloat   => traversal.filterNot(selectCFV(_).has(_.floatValue).inV.v[CustomField].get(customField))
//        case CustomFieldInteger => traversal.filterNot(selectCFV(_).has(_.integerValue).inV.v[CustomField].get(customField))
//        case CustomFieldString  => traversal.filterNot(selectCFV(_).has(_.stringValue).inV.v[CustomField].get(customField))
//      }
//      .getOrElse(traversal.empty)
//}

class CustomFieldIntegrityCheckOps(val db: Database, val service: CustomFieldSrv) extends IntegrityCheckOps[CustomField] {
  override def resolve(entities: Seq[CustomField with Entity])(implicit graph: Graph): Try[Unit] =
    entities match {
      case head :: tail =>
        tail.foreach(copyEdge(_, head))
        service.getByIds(tail.map(_._id): _*).remove()
        Success(())
      case _ => Success(())
    }

  override def globalCheck(): Map[String, Int] = Map.empty
}
