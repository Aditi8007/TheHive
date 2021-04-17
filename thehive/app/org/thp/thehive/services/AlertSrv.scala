package org.thp.thehive.services

import akka.actor.ActorRef
import com.softwaremill.tagging.@@
import org.apache.tinkerpop.gremlin.process.traversal.{Order, P}
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.controllers.FFile
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.PredicateOps.PredicateOpsDefs
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal._
import org.thp.scalligraph.{BadRequestError, CreateError, EntityId, EntityIdOrName, RichOptionTry, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.v1.InputCustomFieldValue
import org.thp.thehive.models._
import org.thp.thehive.services.AlertOps._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.CaseTemplateOps._
import org.thp.thehive.services.CustomFieldOps._
import org.thp.thehive.services.ObservableOps._
import play.api.libs.json.{JsObject, JsValue, Json}

import java.lang.{Long => JLong}
import java.util.{Date, Map => JMap}
import scala.util.{Failure, Success, Try}

class AlertSrv(
    caseSrv: CaseSrv,
    tagSrv: TagSrv,
    organisationSrv: OrganisationSrv,
    customFieldSrv: CustomFieldSrv,
    caseTemplateSrv: CaseTemplateSrv,
    observableSrv: ObservableSrv,
    auditSrv: AuditSrv,
    attachmentSrv: AttachmentSrv,
    integrityCheckActor: => ActorRef @@ IntegrityCheckTag
) extends VertexSrv[Alert] {

  val alertTagSrv          = new EdgeSrv[AlertTag, Alert, Tag]
  val alertCustomFieldSrv  = new EdgeSrv[AlertCustomField, Alert, CustomField]
  val alertOrganisationSrv = new EdgeSrv[AlertOrganisation, Alert, Organisation]
  val alertCaseSrv         = new EdgeSrv[AlertCase, Alert, Case]
  val alertCaseTemplateSrv = new EdgeSrv[AlertCaseTemplate, Alert, CaseTemplate]
  val alertObservableSrv   = new EdgeSrv[AlertObservable, Alert, Observable]

  override def getByName(name: String)(implicit graph: Graph): Traversal.V[Alert] =
    name.split(';') match {
      case Array(tpe, source, sourceRef) => startTraversal.getBySourceId(tpe, source, sourceRef)
      case _                             => startTraversal.empty
    }

  def create(
      alert: Alert,
      organisation: Organisation with Entity,
      tagNames: Set[String],
      customFields: Seq[InputCustomFieldValue],
      caseTemplate: Option[CaseTemplate with Entity]
  )(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichAlert] =
    tagNames.toTry(tagSrv.getOrCreate).flatMap(create(alert, organisation, _, customFields, caseTemplate))

  private def create(
      alert: Alert,
      organisation: Organisation with Entity,
      tags: Seq[Tag with Entity],
      customFields: Seq[InputCustomFieldValue],
      caseTemplate: Option[CaseTemplate with Entity]
  )(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichAlert] = {
    val alertAlreadyExist = startTraversal.getBySourceId(alert.`type`, alert.source, alert.sourceRef).inOrganisation(organisation._id).exists
    if (alertAlreadyExist)
      Failure(CreateError(s"Alert ${alert.`type`}:${alert.source}:${alert.sourceRef} already exist in organisation ${organisation.name}"))
    else
      for {
        createdAlert <- createEntity(alert.copy(organisationId = organisation._id))
        _            <- alertOrganisationSrv.create(AlertOrganisation(), createdAlert, organisation)
        _            <- caseTemplate.map(ct => alertCaseTemplateSrv.create(AlertCaseTemplate(), createdAlert, ct)).flip
        _            <- tags.toTry(t => alertTagSrv.create(AlertTag(), createdAlert, t))
        cfs          <- customFields.toTry { cf: InputCustomFieldValue => createCustomField(createdAlert, cf) }
        richAlert = RichAlert(createdAlert, cfs, None, caseTemplate.map(_.name), 0)
        _ <- auditSrv.alert.create(createdAlert, richAlert.toJson)
      } yield richAlert
  }

  override def update(
      traversal: Traversal.V[Alert],
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(Traversal.V[Alert], JsObject)] =
    auditSrv.mergeAudits(super.update(traversal, propertyUpdaters)) {
      case (alerts, updatedFields) =>
        alerts
          .clone()
          .getOrFail("Alert")
          .flatMap(auditSrv.alert.update(_, updatedFields))
    }

  def updateTags(alert: Alert with Entity, tags: Set[String])(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[(Seq[Tag with Entity], Seq[Tag with Entity])] =
    for {
      tagsToAdd <- (tags -- alert.tags).toTry(tagSrv.getOrCreate)
      tagsToRemove = get(alert).tags.toSeq.filterNot(t => tags.contains(t.toString))
      _ <- tagsToAdd.toTry(alertTagSrv.create(AlertTag(), alert, _))
      _ = if (tags.nonEmpty) get(alert).outE[AlertTag].filter(_.otherV.hasId(tagsToRemove.map(_._id): _*)).remove()
      _ <- get(alert).update(_.tags, tags.toSeq).getOrFail("Alert")
      _ <- auditSrv.alert.update(alert, Json.obj("tags" -> tags))
    } yield (tagsToAdd, tagsToRemove)

  def addTags(alert: Alert with Entity, tags: Set[String])(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    updateTags(alert, tags ++ alert.tags).map(_ => ())

  def createObservable(alert: Alert with Entity, observable: Observable, data: String)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichObservable] =
    for {
      createdObservable <- observableSrv.create(observable.copy(organisationIds = Set(organisationSrv.currentId), relatedId = alert._id), data)
      _                 <- alertObservableSrv.create(AlertObservable(), alert, createdObservable.observable)
      _                 <- auditSrv.observableInAlert.create(createdObservable.observable, alert, createdObservable.toJson)
    } yield createdObservable

  def createObservable(alert: Alert with Entity, observable: Observable, attachment: Attachment with Entity)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichObservable] =
    for {
      createdObservable <- observableSrv.create(observable.copy(organisationIds = Set(organisationSrv.currentId), relatedId = alert._id), attachment)
      _                 <- alertObservableSrv.create(AlertObservable(), alert, createdObservable.observable)
      _                 <- auditSrv.observableInAlert.create(createdObservable.observable, alert, createdObservable.toJson)
    } yield createdObservable

  def createObservable(alert: Alert with Entity, observable: Observable, file: FFile)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichObservable] =
    attachmentSrv.create(file).flatMap(attachment => createObservable(alert, observable, attachment))

  @deprecated("use createObservable", "0.2")
  def addObservable(alert: Alert with Entity, richObservable: RichObservable)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    val maybeExistingObservable = richObservable.dataOrAttachment match {
      case Left(data)        => get(alert).observables.filterOnData(data)
      case Right(attachment) => get(alert).observables.filterOnAttachmentId(attachment.attachmentId)
    }
    maybeExistingObservable
      .richObservable
      .headOption
      .fold {
        for {
          _ <- alertObservableSrv.create(AlertObservable(), alert, richObservable.observable)
          _ <- auditSrv.observableInAlert.create(richObservable.observable, alert, richObservable.toJson)
        } yield ()
      } { existingObservable =>
        val tags = (existingObservable.tags ++ richObservable.tags).toSet
        if ((tags -- existingObservable.tags).nonEmpty)
          observableSrv.updateTags(existingObservable.observable, tags)
        Success(())
      }
  }

  def createCustomField(
      alert: Alert with Entity,
      inputCf: InputCustomFieldValue
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCustomField] =
    for {
      cf   <- customFieldSrv.getOrFail(EntityIdOrName(inputCf.name))
      ccf  <- CustomFieldType.map(cf.`type`).setValue(AlertCustomField(), inputCf.value).map(_.order_=(inputCf.order))
      ccfe <- alertCustomFieldSrv.create(ccf, alert, cf)
    } yield RichCustomField(cf, ccfe)

  def setOrCreateCustomField(alert: Alert with Entity, cf: InputCustomFieldValue)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    val cfv = get(alert).customFields(EntityIdOrName(cf.name))
    if (cfv.clone().exists)
      cfv.setValue(cf.value)
    else
      createCustomField(alert, cf).map(_ => ())
  }

//  def getCustomField(alert: Alert with Entity, customFieldName: String)(implicit graph: Graph): Option[RichCustomField] =
//    get(alert).customFields(customFieldName).richCustomField.headOption

  def updateCustomField(
      alert: Alert with Entity,
      customFieldValues: Seq[(CustomField, Any)]
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val customFieldNames = customFieldValues.map(_._1.name)
    get(alert)
      .customFields
      .richCustomField
      .toIterator
      .filterNot(rcf => customFieldNames.contains(rcf.name))
      .foreach(rcf => get(alert).customFields(rcf.customField._id).remove())
    customFieldValues
      .toTry { case (cf, v) => setOrCreateCustomField(alert, InputCustomFieldValue(cf.name, Some(v), None)) }
      .map(_ => ())
  }

  def markAsUnread(alertId: EntityIdOrName)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      alert <- get(alertId).update[Boolean](_.read, false).getOrFail("Alert")
      _     <- auditSrv.alert.update(alert, Json.obj("read" -> false))
    } yield ()

  def markAsRead(alertId: EntityIdOrName)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      alert <- get(alertId).update[Boolean](_.read, true).getOrFail("Alert")
      _     <- auditSrv.alert.update(alert, Json.obj("read" -> true))
    } yield ()

  def followAlert(alertId: EntityIdOrName)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      alert <- get(alertId).update[Boolean](_.follow, true).getOrFail("Alert")
      _     <- auditSrv.alert.update(alert, Json.obj("follow" -> true))
    } yield ()

  def unfollowAlert(alertId: EntityIdOrName)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      alert <- get(alertId).update[Boolean](_.follow, false).getOrFail("Alert")
      _     <- auditSrv.alert.update(alert, Json.obj("follow" -> false))
    } yield ()

  def createCase(alert: RichAlert, assignee: Option[User with Entity], organisation: Organisation with Entity)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[RichCase] =
    auditSrv.mergeAudits {
      get(alert.alert).`case`.richCase.getOrFail("Case").orElse {
        for {
          caseTemplate <-
            alert
              .caseTemplate
              .map(ct => caseTemplateSrv.get(EntityIdOrName(ct)).richCaseTemplate.getOrFail("CaseTemplate"))
              .flip
          customField = alert.customFields.map(f => InputCustomFieldValue(f.name, f.value, f.order))
          case0 = Case(
            title = caseTemplate.flatMap(_.titlePrefix).getOrElse("") + alert.title,
            description = alert.description,
            severity = alert.severity,
            startDate = new Date,
            endDate = None,
            flag = false,
            tlp = alert.tlp,
            pap = alert.pap,
            status = CaseStatus.Open,
            summary = None,
            alert.tags
          )

          createdCase <- caseSrv.create(case0, assignee, organisation, customField, caseTemplate, Nil)
          _           <- importObservables(alert.alert, createdCase.`case`)
          _           <- alertCaseSrv.create(AlertCase(), alert.alert, createdCase.`case`)
          _           <- get(alert.alert).update(_.caseId, createdCase._id).getOrFail("Alert")
          _           <- markAsRead(alert._id)
          _ = integrityCheckActor ! EntityAdded("Alert")
        } yield createdCase
      }
    }(richCase => auditSrv.alert.createCase(alert.alert, richCase.`case`, richCase.toJson.as[JsObject]))

  def mergeInCase(alertId: EntityIdOrName, caseId: EntityIdOrName)(implicit graph: Graph, authContext: AuthContext): Try[Case with Entity] =
    for {
      alert       <- getOrFail(alertId)
      case0       <- caseSrv.getOrFail(caseId)
      updatedCase <- mergeInCase(alert, case0)
    } yield updatedCase

  def mergeInCase(alert: Alert with Entity, `case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Case with Entity] =
    if (get(alert).isImported)
      Failure(BadRequestError("Alert is already imported"))
    else
      auditSrv
        .mergeAudits {
          // No audit for markAsRead and observables
          // Audits for customFields, description and tags
          val description = `case`.description + s"\n  \n#### Merged with alert #${alert.sourceRef} ${alert.title}\n\n${alert.description.trim}"
          for {
            _ <- markAsRead(alert._id)
            _ <- importObservables(alert, `case`)
            _ <- importCustomFields(alert, `case`)
            _ <- caseSrv.addTags(`case`, alert.tags.toSet)
            _ <- alertCaseSrv.create(AlertCase(), alert, `case`)
            _ <- get(alert).update(_.caseId, `case`._id).getOrFail("Alert")
            c <- caseSrv.get(`case`).update(_.description, description).getOrFail("Case")
            details <- Success(
              Json.obj(
                "customFields" -> get(alert).richCustomFields.toSeq.map(_.toOutput.toJson),
                "description"  -> c.description,
                "tags"         -> (`case`.tags ++ alert.tags).distinct
              )
            )
          } yield details
        }(details => auditSrv.alert.mergeToCase(alert, `case`, details.as[JsObject]))
        .map(_ => integrityCheckActor ! EntityAdded("Alert"))
        .flatMap(_ => caseSrv.getOrFail(`case`._id))

  def importObservables(alert: Alert with Entity, `case`: Case with Entity)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] =
    get(alert)
      .observables
      .richObservable
      .toIterator
      .toTry { richObservable =>
        richObservable
          .dataOrAttachment
          .fold(
            data => caseSrv.createObservable(`case`, richObservable.observable, data),
            attachment => caseSrv.createObservable(`case`, richObservable.observable, attachment)
          )
          .recover {
            case _: CreateError => // if case already contains observable, update tags
              richObservable
                .dataOrAttachment
                .fold(
                  data => observableSrv.startTraversal.filterOnData(data),
                  attachment => observableSrv.startTraversal.filterOnAttachmentId(attachment.attachmentId)
                )
                .filterOnData(richObservable.dataType)
                .relatedTo(`case`._id)
                .inOrganisation(organisationSrv.currentId)
                .headOption
                .foreach { observable =>
                  val newTags = (observable.tags ++ richObservable.tags).toSet
                  observableSrv.updateTags(observable, newTags)
                }
          }
      }
      .map(_ => ())

  def importCustomFields(alert: Alert with Entity, `case`: Case with Entity)(implicit
      graph: Graph,
      authContext: AuthContext
  ): Try[Unit] =
    get(alert)
      .richCustomFields
      .toIterator
      .toTry { richCustomField =>
        caseSrv
          .setOrCreateCustomField(`case`, richCustomField.customField._id, richCustomField.value, richCustomField.customFieldValue.order)
      }
      .map(_ => ())

  def remove(alert: Alert with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    auditSrv.mergeAudits {
      get(alert).observables.toIterator.foreach(observableSrv.delete(_))
      Success(())
    } { _ =>
      for {
        organisation <- organisationSrv.getOrFail(authContext.organisation)
        _            <- auditSrv.alert.delete(alert, organisation)
      } yield get(alert).remove()
    }
}

object AlertOps {

  implicit class AlertOpsDefs(traversal: Traversal.V[Alert]) {
    def get(idOrSource: EntityIdOrName): Traversal.V[Alert] =
      idOrSource.fold(
        traversal.getByIds(_),
        _.split(';') match {
          case Array(tpe, source, sourceRef) => getBySourceId(tpe, source, sourceRef)
          case _                             => traversal.empty
        }
      )

    def getBySourceId(`type`: String, source: String, sourceRef: String): Traversal.V[Alert] =
      traversal
        .has(_.`type`, `type`)
        .has(_.source, source)
        .has(_.sourceRef, sourceRef)

    def inOrganisation(organisationId: EntityId): Traversal.V[Alert] =
      traversal.has(_.organisationId, organisationId)

    def filterByType(`type`: String): Traversal.V[Alert] = traversal.has(_.`type`, `type`)

    def filterBySource(source: String): Traversal.V[Alert] = traversal.has(_.source, source)

    def organisation: Traversal.V[Organisation] = traversal.out[AlertOrganisation].v[Organisation]

    def tags: Traversal.V[Tag] = traversal.out[AlertTag].v[Tag]

    def `case`: Traversal.V[Case] = traversal.out[AlertCase].v[Case]

    def visible(organisationSrv: OrganisationSrv)(implicit authContext: AuthContext): Traversal.V[Alert] =
      traversal.has(_.organisationId, organisationSrv.currentId(traversal.graph, authContext))

    def can(organisationSrv: OrganisationSrv, permission: Permission)(implicit authContext: AuthContext): Traversal.V[Alert] =
      if (authContext.permissions.contains(permission))
        traversal.visible(organisationSrv)
      else traversal.empty

    def imported: Traversal[Boolean, Boolean, IdentityConverter[Boolean]] =
      traversal.choose(_.nonEmptyId(_.caseId), onTrue = true, onFalse = false)

    def isImported: Boolean =
      traversal.nonEmptyId(_.caseId).exists

    def importDate: Traversal[Date, Date, Converter[Date, Date]] =
      traversal.outE[AlertCase].value(_._createdAt)

    def handlingDuration: Traversal[Long, Long, IdentityConverter[Long]] =
      traversal.coalesceIdent(
        _.filter(_.outE[AlertCase])
          .sack(
            (_: JLong, importDate: JLong) => importDate,
            _.by(_.importDate.graphMap[Long, JLong, Converter[Long, JLong]](_.getTime, Converter.long))
          )
          .sack((_: Long) - (_: JLong), _.by(_._createdAt.graphMap[Long, JLong, Converter[Long, JLong]](_.getTime, Converter.long)))
          .sack[Long],
        _.constant(0L)
      )

    def similarCases(organisationSrv: OrganisationSrv, caseFilter: Option[Traversal.V[Case] => Traversal.V[Case]])(implicit
        authContext: AuthContext
    ): Traversal[(RichCase, SimilarStats), JMap[String, Any], Converter[(RichCase, SimilarStats), JMap[String, Any]]] = {
      val similarObservables = observables
        .filteredSimilar
        .visible(organisationSrv)
      caseFilter
        .fold(similarObservables)(caseFilter => similarObservables.filter(o => caseFilter(o.`case`)))
        .group(_.by(_.`case`))
        .unfold
        .project(
          _.by(
            _.selectKeys
              .project(
                _.by(_.richCaseWithoutPerms)
                  .by((_: Traversal.V[Case]).observables.hasNot(_.ignoreSimilarity, true).groupCount(_.byValue(_.ioc)))
              )
          )
            .by(
              _.selectValues
                .project(
                  _.by(_.unfold.groupCount(_.byValue(_.ioc)))
                    .by(_.unfold.groupCount(_.by(_.typeName)))
                )
            )
        )
        .domainMap {
          case ((richCase, obsStats), (iocStats, observableTypeStats)) =>
            val obsStatsMap     = obsStats.mapValues(_.toInt)
            val similarStatsMap = iocStats.mapValues(_.toInt)
            richCase -> SimilarStats(
              similarStatsMap.values.sum         -> obsStatsMap.values.sum,
              similarStatsMap.getOrElse(true, 0) -> obsStatsMap.getOrElse(true, 0),
              observableTypeStats
            )
        }
    }

    def customFields(idOrName: EntityIdOrName): Traversal.E[AlertCustomField] =
      idOrName
        .fold(
          id => traversal.outE[AlertCustomField].filter(_.inV.getByIds(id)),
          name => traversal.outE[AlertCustomField].filter(_.inV.v[CustomField].has(_.name, name))
        )

    def customFields: Traversal.E[AlertCustomField] = traversal.outE[AlertCustomField]

    def customFieldJsonValue(customFieldSrv: CustomFieldSrv, customField: EntityIdOrName): Traversal.Domain[JsValue] =
      customFieldSrv
        .get(customField)(traversal.graph)
        .value(_.`type`)
        .headOption
        .map(t => CustomFieldType.map(t).getJsonValue(traversal.customFields(customField)))
        .getOrElse(traversal.empty.castDomain)

    def richCustomFields: Traversal[RichCustomField, JMap[String, Any], Converter[RichCustomField, JMap[String, Any]]] =
      traversal
        .outE[AlertCustomField]
        .project(_.by.by(_.inV.v[CustomField]))
        .domainMap {
          case (cfv, cf) => RichCustomField(cf, cfv)
        }

    def customFieldFilter(customFieldSrv: CustomFieldSrv, customField: EntityIdOrName, predicate: P[JsValue]): Traversal.V[Alert] =
      customFieldSrv
        .get(customField)(traversal.graph)
        .value(_.`type`)
        .headOption
        .map {
          case CustomFieldType.boolean =>
            traversal.filter(_.customFields.has(_.booleanValue, predicate.mapValue(_.as[Boolean])).inV.v[CustomField].get(customField))
          case CustomFieldType.date =>
            traversal.filter(_.customFields.has(_.dateValue, predicate.mapValue(_.as[Date])).inV.v[CustomField].get(customField))
          case CustomFieldType.float =>
            traversal.filter(_.customFields.has(_.floatValue, predicate.mapValue(_.as[Double])).inV.v[CustomField].get(customField))
          case CustomFieldType.integer =>
            traversal.filter(_.customFields.has(_.integerValue, predicate.mapValue(_.as[Int])).inV.v[CustomField].get(customField))
          case CustomFieldType.string =>
            traversal.filter(_.customFields.has(_.stringValue, predicate.mapValue(_.as[String])).inV.v[CustomField].get(customField))
        }
        .getOrElse(traversal.empty)

    def hasCustomField(customFieldSrv: CustomFieldSrv, customField: EntityIdOrName): Traversal.V[Alert] = {
      val cfFilter = (t: Traversal.V[CustomField]) => customField.fold(id => t.hasId(id), name => t.has(_.name, name))

      customFieldSrv
        .get(customField)(traversal.graph)
        .value(_.`type`)
        .headOption
        .map {
          case CustomFieldType.boolean => traversal.filter(t => cfFilter(t.outE[AlertCustomField].has(_.booleanValue).inV.v[CustomField]))
          case CustomFieldType.date    => traversal.filter(t => cfFilter(t.outE[AlertCustomField].has(_.dateValue).inV.v[CustomField]))
          case CustomFieldType.float   => traversal.filter(t => cfFilter(t.outE[AlertCustomField].has(_.floatValue).inV.v[CustomField]))
          case CustomFieldType.integer => traversal.filter(t => cfFilter(t.outE[AlertCustomField].has(_.integerValue).inV.v[CustomField]))
          case CustomFieldType.string  => traversal.filter(t => cfFilter(t.outE[AlertCustomField].has(_.stringValue).inV.v[CustomField]))
        }
        .getOrElse(traversal.empty)
    }

    def hasNotCustomField(customFieldSrv: CustomFieldSrv, customField: EntityIdOrName): Traversal.V[Alert] = {
      val cfFilter = (t: Traversal.V[CustomField]) => customField.fold(id => t.hasId(id), name => t.has(_.name, name))

      customFieldSrv
        .get(customField)(traversal.graph)
        .value(_.`type`)
        .headOption
        .map {
          case CustomFieldType.boolean => traversal.filterNot(t => cfFilter(t.outE[AlertCustomField].has(_.booleanValue).inV.v[CustomField]))
          case CustomFieldType.date    => traversal.filterNot(t => cfFilter(t.outE[AlertCustomField].has(_.dateValue).inV.v[CustomField]))
          case CustomFieldType.float   => traversal.filterNot(t => cfFilter(t.outE[AlertCustomField].has(_.floatValue).inV.v[CustomField]))
          case CustomFieldType.integer => traversal.filterNot(t => cfFilter(t.outE[AlertCustomField].has(_.integerValue).inV.v[CustomField]))
          case CustomFieldType.string  => traversal.filterNot(t => cfFilter(t.outE[AlertCustomField].has(_.stringValue).inV.v[CustomField]))
        }
        .getOrElse(traversal.empty)
    }

    def observables: Traversal.V[Observable] = traversal.out[AlertObservable].v[Observable]

    def caseTemplate: Traversal.V[CaseTemplate] = traversal.out[AlertCaseTemplate].v[CaseTemplate]

    def richAlertWithCustomRenderer[D, G, C <: Converter[D, G]](
        entityRenderer: Traversal.V[Alert] => Traversal[D, G, C]
    ): Traversal[(RichAlert, D), JMap[String, Any], Converter[(RichAlert, D), JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.richCustomFields.fold)
            .by(_.`case`._id.option)
            .by(_.caseTemplate.value(_.name).option)
            .by(entityRenderer)
        )
        .domainMap {
          case (alert, customFields, caseId, caseTemplate, renderedEntity) =>
            val observableCount = traversal
              .graph
              .indexCountQuery(
                s"""v."_label":Observable AND """ +
                  s"v.relatedId:${traversal.graph.escapeQueryParameter(alert._id.value)}"
              )
            RichAlert(
              alert,
              customFields,
              caseId,
              caseTemplate,
              observableCount
            ) -> renderedEntity
        }

    def richAlert: Traversal[RichAlert, JMap[String, Any], Converter[RichAlert, JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.richCustomFields.fold)
            .by(_.`case`._id.option)
            .by(_.caseTemplate.value(_.name).option)
            .by(_.outE[AlertObservable].count)
        )
        .domainMap {
          case (alert, customFields, caseId, caseTemplate, observableCount) =>
            RichAlert(
              alert,
              customFields,
              caseId,
              caseTemplate,
              observableCount
            )
        }
  }

  implicit class AlertCustomFieldsOpsDefs(traversal: Traversal.E[AlertCustomField]) extends CustomFieldValueOpsDefs(traversal)
}

class AlertIntegrityCheckOps(val db: Database, val service: AlertSrv, organisationSrv: OrganisationSrv) extends IntegrityCheckOps[Alert] {

  override def resolve(entities: Seq[Alert with Entity])(implicit graph: Graph): Try[Unit] = {
    val (imported, notImported) = entities.partition(_.caseId.isDefined)
    if (imported.nonEmpty && notImported.nonEmpty)
      // Remove all non imported alerts
      service.getByIds(notImported.map(_._id): _*).remove()
    // Keep the last created alert
    lastCreatedEntity(entities).foreach(e => service.getByIds(e._2.map(_._id): _*).remove())
    Success(())
  }

  override def globalCheck(): Map[String, Long] = {
    implicit val authContext: AuthContext = LocalUserSrv.getSystemAuthContext

    val multiImport = db.tryTransaction { implicit graph =>
      // Remove extra link with case
      val linkIds = service
        .startTraversal
        .flatMap(_.outE[AlertCase].range(1, 100)._id)
        .toSeq
      if (linkIds.nonEmpty)
        graph.E[AlertCase](linkIds: _*).remove()
      Success(linkIds.length.toLong)
    }

    val orgMetrics: Map[String, Long] = db
      .tryTransaction { implicit graph =>
        // Check links with organisation
        Try {
          service
            .startTraversal
            .project(
              _.by
                .by(_.organisation._id.fold)
            )
            .toIterator
            .flatMap {
              case (alert, Seq(organisationId)) if alert.organisationId == organisationId => None // It's OK

              case (alert, Seq(organisationId)) =>
                logger.warn(
                  s"Invalid organisationId in alert ${alert._id}(${alert.`type`}:${alert.source}:${alert.sourceRef}), " +
                    s"got ${alert.organisationId}, should be $organisationId. Fixing it."
                )
                service.get(alert).update(_.organisationId, organisationId).iterate()
                Some("invalidOrganisationId")

              case (alert, organisationIds) if organisationIds.isEmpty =>
                organisationSrv.getOrFail(alert.organisationId) match {
                  case Success(organisation) =>
                    logger.warn(
                      s"Link between alert ${alert._id}(${alert.`type`}:${alert.source}:${alert.sourceRef}) and " +
                        s"organisation ${alert.organisationId} has disappeared. Fixing it."
                    )
                    service
                      .alertOrganisationSrv
                      .create(AlertOrganisation(), alert, organisation)
                      .fold(
                        error => {
                          logger.error(
                            s"Fail to create link between alert ${alert._id}(${alert.`type`}:${alert.source}:${alert.sourceRef}) " +
                              s"and organisation ${alert.organisationId}",
                            error
                          )
                          Some("missingOrganisationAndFail")
                        },
                        _ => Some("missingOrganisation")
                      )
                  case _ =>
                    logger.warn(
                      s"Alert ${alert._id}(${alert.`type`}:${alert.source}:${alert.sourceRef}) is not linked to " +
                        s"existing organisation. Fixing it."
                    )
                    service.get(alert).remove()
                    Some("nonExistentOrganisation")
                }

              case (alert, organisationIds) if organisationIds.contains(alert.organisationId) =>
                val (extraLinks, extraOrganisationIds) = organisationIds.partition(_ == alert.organisationId)
                if (extraOrganisationIds.nonEmpty) {
                  logger.warn(
                    s"Alert ${alert._id}(${alert.`type`}:${alert.source}:${alert.sourceRef}) is not linked to " +
                      s"extra organisation(s): ${extraOrganisationIds.mkString(",")}. Fixing it."
                  )
                  service.get(alert).outE[AlertOrganisation].filter(_.inV.hasId(extraOrganisationIds: _*)).remove()
                }
                if (extraLinks.length > 1) {
                  logger.warn(
                    s"Alert ${alert._id}(${alert.`type`}:${alert.source}:${alert.sourceRef}) is linked more than once to " +
                      s"organisation: ${alert.organisationId}. Fixing it."
                  )
                  service.get(alert).flatMap(_.outE[AlertOrganisation].range(1, 100)).remove()
                }
                Some("extraOrganisation")

              case (alert, organisationIds) =>
                logger.warn(
                  s"Alert ${alert._id}(${alert.`type`}:${alert.source}:${alert.sourceRef}) has inconsistent organisation links: " +
                    s"organisation is ${alert.organisationId} but links are ${organisationIds.mkString(",")}. Fixing it."
                )
                service.get(alert).flatMap(_.outE[AlertOrganisation].sort(_.by("_createdAt", Order.asc)).range(1, 100)).remove()
                service.get(alert).organisation._id.getOrFail("Organisation").foreach { organisationId =>
                  service.get(alert).update(_.organisationId, organisationId).iterate()
                }
                Some("incoherent")
            }
            .toSeq
        }
      }
      .getOrElse(Seq("globalFailure"))
      .groupBy(identity)
      .mapValues(_.size.toLong)

    orgMetrics + ("multiImport" -> multiImport.getOrElse(0L))
  }
}
